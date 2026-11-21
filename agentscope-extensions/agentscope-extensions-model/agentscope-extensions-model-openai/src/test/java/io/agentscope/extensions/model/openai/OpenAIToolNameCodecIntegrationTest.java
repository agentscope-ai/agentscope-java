/*
 * Copyright 2024-2026 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package io.agentscope.extensions.model.openai;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

import com.fasterxml.jackson.databind.JsonNode;
import io.agentscope.core.message.Msg;
import io.agentscope.core.message.MsgRole;
import io.agentscope.core.message.TextBlock;
import io.agentscope.core.message.ToolResultBlock;
import io.agentscope.core.message.ToolUseBlock;
import io.agentscope.core.model.GenerateOptions;
import io.agentscope.core.model.ToolChoice;
import io.agentscope.core.model.ToolSchema;
import io.agentscope.core.model.transport.HttpTransportFactory;
import io.agentscope.core.util.JsonUtils;
import io.agentscope.extensions.model.openai.compat.kimi.KimiFormatter;
import io.agentscope.extensions.model.openai.formatter.OpenAIChatFormatter;
import io.agentscope.extensions.model.openai.formatter.OpenAIToolNameCodec;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import okhttp3.mockwebserver.MockResponse;
import okhttp3.mockwebserver.MockWebServer;
import okhttp3.mockwebserver.RecordedRequest;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import reactor.test.StepVerifier;

/**
 * End-to-end wiring tests for {@link OpenAIToolNameCodec}: the model must encode tool
 * definitions, tool_choice and history tool_calls before formatting, and decode parsed
 * responses. These use MockWebServer so a missing {@code .map(nameCodec::decode)} or a
 * formatter that encodes a second time is caught by a test rather than by a live gateway.
 */
@DisplayName("OpenAI tool name codec wiring")
class OpenAIToolNameCodecIntegrationTest {

    private static final String TEXT_RESPONSE =
            """
            {"id":"chatcmpl-1","object":"chat.completion","model":"gpt-4",
             "choices":[{"index":0,"message":{"role":"assistant","content":"ok"},
             "finish_reason":"stop"}]}
            """;

    private static final String TOOL_CALL_RESPONSE =
            """
            {"id":"chatcmpl-1","object":"chat.completion","model":"gpt-4",
             "choices":[{"index":0,"message":{"role":"assistant","tool_calls":[
             {"id":"call-1","type":"function","function":{"name":"task_get",
             "arguments":"{\\"taskId\\":\\"t-1\\"}"}}]},"finish_reason":"tool_calls"}]}
            """;

    private MockWebServer server;

    @BeforeEach
    void setUp() throws IOException {
        server = new MockWebServer();
        server.start();
    }

    @AfterEach
    void tearDown() throws IOException {
        server.shutdown();
    }

    private OpenAIChatModel newModel(boolean stream, OpenAIChatFormatter formatter) {
        return OpenAIChatModel.builder().apiKey("test-api-key").modelName("gpt-4").stream(stream)
                .baseUrl(server.url("/").toString().replaceAll("/$", ""))
                .formatter(formatter)
                .httpTransport(HttpTransportFactory.getDefault())
                .build();
    }

    private void enqueueJson(String body) {
        server.enqueue(
                new MockResponse().setBody(body).setHeader("Content-Type", "application/json"));
    }

    private void enqueueSse(String body) {
        server.enqueue(
                new MockResponse().setBody(body).setHeader("Content-Type", "text/event-stream"));
    }

    private JsonNode requestBody() throws Exception {
        RecordedRequest recorded = server.takeRequest(1, TimeUnit.SECONDS);
        assertNotNull(recorded);
        return JsonUtils.getJsonCodec().fromJson(recorded.getBody().readUtf8(), JsonNode.class);
    }

    private static List<String> toolDefinitionNames(JsonNode body) {
        List<String> names = new ArrayList<>();
        for (JsonNode tool : body.path("tools")) {
            names.add(tool.path("function").path("name").asText());
        }
        return names;
    }

    private static List<String> historyToolCallNames(JsonNode body) {
        List<String> names = new ArrayList<>();
        for (JsonNode message : body.path("messages")) {
            for (JsonNode call : message.path("tool_calls")) {
                names.add(call.path("function").path("name").asText());
            }
        }
        return names;
    }

    private static Msg userMsg(String text) {
        return Msg.builder().role(MsgRole.USER).textContent(text).build();
    }

    private static ToolSchema tool(String name) {
        return ToolSchema.builder().name(name).description("d").build();
    }

    @Test
    @DisplayName("Non-streaming: dotted names are encoded on the wire and decoded on responses")
    void testNonStreamingRoundTrip() throws Exception {
        enqueueJson(TOOL_CALL_RESPONSE);
        OpenAIChatModel model = newModel(false, new OpenAIChatFormatter());

        StepVerifier.create(model.stream(List.of(userMsg("hi")), List.of(tool("task.get")), null))
                .assertNext(
                        response -> {
                            ToolUseBlock call = (ToolUseBlock) response.getContent().get(0);
                            assertEquals("task.get", call.getName());
                            assertEquals("call-1", call.getId());
                        })
                .verifyComplete();

        JsonNode body = requestBody();
        assertEquals(List.of("task_get"), toolDefinitionNames(body));
        assertFalse(body.toString().contains("task.get"));
    }

    @Test
    @DisplayName("Streaming: tool call names in chunks are decoded")
    void testStreamingRoundTrip() throws Exception {
        String sse =
                "data:"
                    + " {\"id\":\"c1\",\"object\":\"chat.completion.chunk\",\"choices\":[{\"index\":0,\"delta\":{\"tool_calls\":[{\"index\":0,\"id\":\"call-1\",\"type\":\"function\",\"function\":{\"name\":\"task_get\",\"arguments\":\"{}\"}}]},\"finish_reason\":null}]}\n\n"
                    + "data:"
                    + " {\"id\":\"c1\",\"object\":\"chat.completion.chunk\",\"choices\":[{\"index\":0,\"delta\":{},\"finish_reason\":\"tool_calls\"}]}\n\n"
                    + "data: [DONE]\n\n";
        enqueueSse(sse);
        OpenAIChatModel model = newModel(true, new OpenAIChatFormatter());

        StepVerifier.create(model.stream(List.of(userMsg("hi")), List.of(tool("task.get")), null))
                .assertNext(
                        response ->
                                assertEquals(
                                        "task.get",
                                        ((ToolUseBlock) response.getContent().get(0)).getName()))
                .thenCancel()
                .verify();
    }

    @Test
    @DisplayName("Specific tool_choice is encoded even for a compat formatter override")
    void testKimiSpecificToolChoiceIsEncoded() throws Exception {
        enqueueJson(TEXT_RESPONSE);
        OpenAIChatModel model = newModel(false, new KimiFormatter());
        GenerateOptions options =
                GenerateOptions.builder().toolChoice(new ToolChoice.Specific("task.get")).build();

        StepVerifier.create(
                        model.stream(List.of(userMsg("hi")), List.of(tool("task.get")), options))
                .expectNextCount(1)
                .verifyComplete();

        JsonNode body = requestBody();
        assertEquals("task_get", body.path("tool_choice").path("function").path("name").asText());
        assertEquals(List.of("task_get"), toolDefinitionNames(body));
        assertFalse(body.toString().contains("task.get"));
    }

    @Test
    @DisplayName("History tool_calls are encoded with the request's mapping")
    void testHistoryToolCallsAreEncoded() throws Exception {
        enqueueJson(TEXT_RESPONSE);
        OpenAIChatModel model = newModel(false, new OpenAIChatFormatter());
        Msg assistant =
                Msg.builder()
                        .role(MsgRole.ASSISTANT)
                        .content(
                                ToolUseBlock.builder()
                                        .id("call-1")
                                        .name("task.get")
                                        .input(Map.of("taskId", "t-1"))
                                        .build())
                        .build();
        Msg result =
                Msg.builder()
                        .role(MsgRole.TOOL)
                        .content(
                                ToolResultBlock.builder()
                                        .id("call-1")
                                        .name("task.get")
                                        .output(TextBlock.builder().text("ok").build())
                                        .build())
                        .build();

        StepVerifier.create(
                        model.stream(
                                List.of(userMsg("hi"), assistant, result),
                                List.of(tool("task.get")),
                                null))
                .expectNextCount(1)
                .verifyComplete();

        JsonNode body = requestBody();
        assertEquals(List.of("task_get"), historyToolCallNames(body));
        assertFalse(body.toString().contains("task.get"));
    }

    @Test
    @DisplayName("Mixed collision: wire names stay unique and both tools are decodable")
    void testMixedCollision() throws Exception {
        String response =
                """
                {"id":"chatcmpl-1","object":"chat.completion","model":"gpt-4",
                 "choices":[{"index":0,"message":{"role":"assistant","tool_calls":[
                 {"id":"call-1","type":"function","function":{"name":"task_get_2",
                 "arguments":"{}"}}]},"finish_reason":"tool_calls"}]}
                """;
        enqueueJson(response);
        OpenAIChatModel model = newModel(false, new OpenAIChatFormatter());

        StepVerifier.create(
                        model.stream(
                                List.of(userMsg("hi")),
                                List.of(tool("task.get"), tool("task_get")),
                                null))
                .assertNext(
                        response1 ->
                                assertEquals(
                                        "task_get",
                                        ((ToolUseBlock) response1.getContent().get(0)).getName()))
                .verifyComplete();

        JsonNode body = requestBody();
        assertEquals(List.of("task_get", "task_get_2"), toolDefinitionNames(body));
        assertEquals(
                "task.get",
                OpenAIToolNameCodec.buildDecodeMap(List.of(tool("task.get"), tool("task_get")))
                        .get("task_get"));
    }

    @Test
    @DisplayName("Split tool-name deltas are left alone rather than decoded to a wrong tool")
    void testStreamingSplitNameIsNotMisDecoded() throws Exception {
        // Some gateways split function.name itself across deltas. The model layer decodes each
        // parsed chunk, and the complete name is assembled downstream by the accumulator, which
        // takes the last non-placeholder name. This test pins the behaviour: no chunk is decoded
        // to a wrong original name, and the stream does not fail.
        String sse =
                "data:"
                    + " {\"id\":\"c1\",\"object\":\"chat.completion.chunk\",\"choices\":[{\"index\":0,\"delta\":{\"tool_calls\":[{\"index\":0,\"id\":\"call-1\",\"type\":\"function\",\"function\":{\"name\":\"task_\",\"arguments\":\"\"}}]},\"finish_reason\":null}]}\n\n"
                    + "data:"
                    + " {\"id\":\"c1\",\"object\":\"chat.completion.chunk\",\"choices\":[{\"index\":0,\"delta\":{\"tool_calls\":[{\"index\":0,\"id\":\"call-1\",\"type\":\"function\",\"function\":{\"name\":\"get\",\"arguments\":\"\"}}]},\"finish_reason\":null}]}\n\n"
                    + "data: [DONE]\n\n";
        enqueueSse(sse);
        OpenAIChatModel model = newModel(true, new OpenAIChatFormatter());

        StepVerifier.create(model.stream(List.of(userMsg("hi")), List.of(tool("task.get")), null))
                .assertNext(
                        response ->
                                assertNotEquals(
                                        "task.get",
                                        ((ToolUseBlock) response.getContent().get(0)).getName()))
                .assertNext(
                        response ->
                                assertNotEquals(
                                        "task.get",
                                        ((ToolUseBlock) response.getContent().get(0)).getName()))
                .thenCancel()
                .verify();
    }
}

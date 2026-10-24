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
package io.agentscope.extensions.model.anthropic;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.agentscope.core.agent.accumulator.ReasoningContext;
import io.agentscope.core.message.Msg;
import io.agentscope.core.message.MsgRole;
import io.agentscope.core.message.TextBlock;
import io.agentscope.core.message.ToolResultBlock;
import io.agentscope.core.model.ChatResponse;
import io.agentscope.core.model.GenerateOptions;
import io.agentscope.core.model.ToolSchema;
import io.agentscope.core.model.test.ModelTestUtils;
import java.io.IOException;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import okhttp3.mockwebserver.MockResponse;
import okhttp3.mockwebserver.MockWebServer;
import okhttp3.mockwebserver.RecordedRequest;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/** Integration tests for signed thinking round trips through the Anthropic SDK transport. */
@Tag("integration")
class AnthropicChatModelThinkingRoundTripTest {

    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();
    private static final Duration TIMEOUT = Duration.ofSeconds(5);

    private MockWebServer mockServer;

    @BeforeEach
    void setUp() throws IOException {
        mockServer = new MockWebServer();
        mockServer.start();
    }

    @AfterEach
    void tearDown() throws IOException {
        mockServer.shutdown();
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void testPreservesSignedThinkingInParallelToolRoundTripRequest(boolean streaming)
            throws Exception {
        AnthropicChatModel model =
                AnthropicChatModel.builder()
                        .apiKey("test-api-key")
                        .baseUrl(mockServer.url("/").toString().replaceAll("/$", ""))
                        .modelName("claude-sonnet-4-6")
                        .stream(streaming)
                        .build();
        mockServer.enqueue(
                streaming
                        ? streamResponse(firstAssistantResponse())
                        : jsonResponse(firstAssistantResponse()));

        Msg userMessage =
                Msg.builder()
                        .name("User")
                        .role(MsgRole.USER)
                        .content(TextBlock.builder().text("Check two cities.").build())
                        .build();
        List<ToolSchema> tools =
                List.of(
                        ModelTestUtils.createSimpleToolSchema(
                                "get_weather", "Get weather information"));
        GenerateOptions options =
                GenerateOptions.builder()
                        .maxTokens(4096)
                        .additionalBodyParam(
                                "thinking", Map.of("type", "adaptive", "display", "summarized"))
                        .build();

        ReasoningContext context = new ReasoningContext("Assistant");
        model.stream(List.of(userMessage), tools, options)
                .doOnNext(context::processChunk)
                .blockLast(TIMEOUT);
        Msg assistantMessage = context.buildFinalMessage();
        assertNotNull(assistantMessage);
        assertEquals(3, assistantMessage.getContent().size());

        Msg firstToolResult = toolResult("call_1", "Sunny");
        Msg secondToolResult = toolResult("call_2", "Cloudy");

        mockServer.enqueue(
                streaming
                        ? streamResponse(finalAssistantResponse())
                        : jsonResponse(finalAssistantResponse()));
        ChatResponse finalResponse =
                model.stream(
                                List.of(
                                        userMessage,
                                        assistantMessage,
                                        firstToolResult,
                                        secondToolResult),
                                tools,
                                options)
                        .blockLast(TIMEOUT);
        assertNotNull(finalResponse);

        RecordedRequest firstRequest = mockServer.takeRequest(1, TimeUnit.SECONDS);
        RecordedRequest secondRequest = mockServer.takeRequest(1, TimeUnit.SECONDS);
        assertNotNull(firstRequest);
        assertNotNull(secondRequest);
        assertEquals("/v1/messages", secondRequest.getPath());
        assertEquals("test-api-key", secondRequest.getHeader("x-api-key"));

        JsonNode request = OBJECT_MAPPER.readTree(secondRequest.getBody().readUtf8());
        assertEquals("adaptive", request.path("thinking").path("type").asText());
        assertEquals("summarized", request.path("thinking").path("display").asText());
        JsonNode messages = request.path("messages");
        assertEquals(3, messages.size());

        JsonNode assistantContent = messages.get(1).path("content");
        assertEquals("assistant", messages.get(1).path("role").asText());
        assertEquals(3, assistantContent.size());
        assertEquals("thinking", assistantContent.get(0).path("type").asText());
        assertEquals(
                "I should check both cities.", assistantContent.get(0).path("thinking").asText());
        assertEquals("signature-123", assistantContent.get(0).path("signature").asText());
        assertEquals("call_1", assistantContent.get(1).path("id").asText());
        assertEquals("call_2", assistantContent.get(2).path("id").asText());

        JsonNode resultContent = messages.get(2).path("content");
        assertEquals("user", messages.get(2).path("role").asText());
        assertEquals(2, resultContent.size());
        assertEquals("call_1", resultContent.get(0).path("tool_use_id").asText());
        assertEquals("call_2", resultContent.get(1).path("tool_use_id").asText());
    }

    private static Msg toolResult(String id, String output) {
        return Msg.builder()
                .name("Tool")
                .role(MsgRole.TOOL)
                .content(
                        ToolResultBlock.builder()
                                .id(id)
                                .name("get_weather")
                                .output(TextBlock.builder().text(output).build())
                                .build())
                .build();
    }

    private static MockResponse jsonResponse(String body) {
        return new MockResponse().setHeader("Content-Type", "application/json").setBody(body);
    }

    private static MockResponse streamResponse(String messageJson) throws IOException {
        JsonNode message = OBJECT_MAPPER.readTree(messageJson);
        StringBuilder events = new StringBuilder();
        var start = message.deepCopy();
        ((com.fasterxml.jackson.databind.node.ObjectNode) start).putArray("content");
        appendEvent(events, Map.of("type", "message_start", "message", start));
        for (int index = 0; index < message.path("content").size(); index++) {
            JsonNode block = message.path("content").get(index);
            String type = block.path("type").asText();
            Map<String, Object> initial =
                    switch (type) {
                        case "thinking" -> Map.of("type", type, "thinking", "", "signature", "");
                        case "tool_use" ->
                                Map.of(
                                        "type",
                                        type,
                                        "id",
                                        block.path("id").asText(),
                                        "name",
                                        block.path("name").asText(),
                                        "input",
                                        Map.of());
                        default -> Map.of("type", "text", "text", "");
                    };
            appendEvent(
                    events,
                    Map.of(
                            "type",
                            "content_block_start",
                            "index",
                            index,
                            "content_block",
                            initial));
            Map<String, Object> delta =
                    switch (type) {
                        case "thinking" ->
                                Map.of(
                                        "type",
                                        "thinking_delta",
                                        "thinking",
                                        block.path("thinking").asText());
                        case "tool_use" ->
                                Map.of(
                                        "type",
                                        "input_json_delta",
                                        "partial_json",
                                        block.path("input").toString());
                        default ->
                                Map.of("type", "text_delta", "text", block.path("text").asText());
                    };
            appendEvent(
                    events, Map.of("type", "content_block_delta", "index", index, "delta", delta));
            if ("thinking".equals(type)) {
                String signature = block.path("signature").asText();
                int split = signature.length() / 2;
                for (String fragment :
                        List.of(signature.substring(0, split), signature.substring(split))) {
                    appendEvent(
                            events,
                            Map.of(
                                    "type",
                                    "content_block_delta",
                                    "index",
                                    index,
                                    "delta",
                                    Map.of("type", "signature_delta", "signature", fragment)));
                }
            }
            appendEvent(events, Map.of("type", "content_block_stop", "index", index));
        }
        appendEvent(
                events,
                Map.of(
                        "type",
                        "message_delta",
                        "delta",
                        Map.of("stop_reason", message.path("stop_reason").asText()),
                        "usage",
                        Map.of(
                                "output_tokens",
                                message.path("usage").path("output_tokens").asInt())));
        appendEvent(events, Map.of("type", "message_stop"));
        return new MockResponse()
                .setHeader("Content-Type", "text/event-stream")
                .setBody(events.toString());
    }

    private static void appendEvent(StringBuilder events, Map<String, Object> event)
            throws IOException {
        events.append("event: ")
                .append(event.get("type"))
                .append("\ndata: ")
                .append(OBJECT_MAPPER.writeValueAsString(event))
                .append("\n\n");
    }

    private static String firstAssistantResponse() {
        return """
        {
          "id": "msg_tool_use",
          "type": "message",
          "role": "assistant",
          "model": "claude-sonnet-4-5-20250929",
          "content": [
            {
              "type": "thinking",
              "thinking": "I should check both cities.",
              "signature": "signature-123"
            },
            {
              "type": "tool_use",
              "id": "call_1",
              "name": "get_weather",
              "input": {"city": "Beijing"}
            },
            {
              "type": "tool_use",
              "id": "call_2",
              "name": "get_weather",
              "input": {"city": "Hangzhou"}
            }
          ],
          "stop_reason": "tool_use",
          "stop_sequence": null,
          "usage": {"input_tokens": 10, "output_tokens": 20}
        }
        """;
    }

    private static String finalAssistantResponse() {
        return """
        {
          "id": "msg_final",
          "type": "message",
          "role": "assistant",
          "model": "claude-sonnet-4-5-20250929",
          "content": [{"type": "text", "text": "Done"}],
          "stop_reason": "end_turn",
          "stop_sequence": null,
          "usage": {"input_tokens": 30, "output_tokens": 5}
        }
        """;
    }
}

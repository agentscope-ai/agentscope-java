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
import org.junit.jupiter.api.Test;

/** Integration tests for signed thinking round trips through the Anthropic SDK transport. */
@Tag("integration")
class AnthropicChatModelThinkingRoundTripTest {

    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();
    private static final Duration TIMEOUT = Duration.ofSeconds(5);

    private MockWebServer mockServer;
    private AnthropicChatModel model;

    @BeforeEach
    void setUp() throws IOException {
        mockServer = new MockWebServer();
        mockServer.start();

        String baseUrl = mockServer.url("/").toString().replaceAll("/$", "");
        model =
                AnthropicChatModel.builder()
                        .apiKey("test-api-key")
                        .baseUrl(baseUrl)
                        .modelName("claude-sonnet-4-6")
                        .stream(false)
                        .build();
    }

    @AfterEach
    void tearDown() throws IOException {
        mockServer.shutdown();
    }

    @Test
    void testPreservesSignedThinkingInParallelToolRoundTripRequest() throws Exception {
        mockServer.enqueue(jsonResponse(firstAssistantResponse()));

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

        ChatResponse firstResponse =
                model.stream(List.of(userMessage), tools, options).single().block(TIMEOUT);
        assertNotNull(firstResponse);
        assertEquals(3, firstResponse.getContent().size());

        Msg assistantMessage =
                Msg.builder()
                        .name("Assistant")
                        .role(MsgRole.ASSISTANT)
                        .content(firstResponse.getContent())
                        .build();
        Msg firstToolResult = toolResult("call_1", "Sunny");
        Msg secondToolResult = toolResult("call_2", "Cloudy");

        mockServer.enqueue(jsonResponse(finalAssistantResponse()));
        ChatResponse finalResponse =
                model.stream(
                                List.of(
                                        userMessage,
                                        assistantMessage,
                                        firstToolResult,
                                        secondToolResult),
                                tools,
                                options)
                        .single()
                        .block(TIMEOUT);
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

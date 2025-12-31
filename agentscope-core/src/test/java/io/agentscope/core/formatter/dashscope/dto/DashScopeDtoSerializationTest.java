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
package io.agentscope.core.formatter.dashscope.dto;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * Tests for DashScope DTO serialization and deserialization.
 */
class DashScopeDtoSerializationTest {

    private ObjectMapper objectMapper;

    @BeforeEach
    void setUp() {
        objectMapper = new ObjectMapper();
    }

    @Test
    void testDashScopeMessageTextContent() throws Exception {
        DashScopeMessage message =
                DashScopeMessage.builder().role("user").content("Hello, world!").build();

        String json = objectMapper.writeValueAsString(message);
        DashScopeMessage deserialized = objectMapper.readValue(json, DashScopeMessage.class);

        assertEquals("user", deserialized.getRole());
        assertEquals("Hello, world!", deserialized.getContentAsString());
        assertFalse(deserialized.isMultimodal());
    }

    @Test
    void testDashScopeMessageMultimodalContent() throws Exception {
        List<DashScopeContentPart> parts =
                Arrays.asList(
                        DashScopeContentPart.text("What is in this image?"),
                        DashScopeContentPart.image("https://example.com/image.jpg"));

        DashScopeMessage message = DashScopeMessage.builder().role("user").content(parts).build();

        String json = objectMapper.writeValueAsString(message);
        assertTrue(json.contains("text"));
        assertTrue(json.contains("image"));
    }

    @Test
    void testDashScopeMessageWithToolCalls() throws Exception {
        DashScopeFunction function =
                DashScopeFunction.of("get_weather", "{\"location\":\"Beijing\"}");
        DashScopeToolCall toolCall =
                DashScopeToolCall.builder()
                        .id("call_123")
                        .type("function")
                        .function(function)
                        .build();

        DashScopeMessage message =
                DashScopeMessage.builder()
                        .role("assistant")
                        .content((String) null)
                        .toolCalls(List.of(toolCall))
                        .build();

        String json = objectMapper.writeValueAsString(message);
        assertTrue(json.contains("tool_calls"));
        assertTrue(json.contains("call_123"));
        assertTrue(json.contains("get_weather"));
    }

    @Test
    void testDashScopeToolResultMessage() throws Exception {
        DashScopeMessage message =
                DashScopeMessage.builder()
                        .role("tool")
                        .toolCallId("call_123")
                        .name("get_weather")
                        .content("The weather in Beijing is sunny, 25°C")
                        .build();

        String json = objectMapper.writeValueAsString(message);
        assertTrue(json.contains("tool"));
        assertTrue(json.contains("tool_call_id"));
        assertTrue(json.contains("call_123"));
    }

    @Test
    void testDashScopeContentPartTypes() {
        DashScopeContentPart text = DashScopeContentPart.text("Hello");
        assertEquals("Hello", text.getText());
        assertNull(text.getImage());

        DashScopeContentPart image = DashScopeContentPart.image("http://example.com/img.png");
        assertEquals("http://example.com/img.png", image.getImage());
        assertNull(image.getText());

        DashScopeContentPart audio = DashScopeContentPart.audio("http://example.com/audio.mp3");
        assertEquals("http://example.com/audio.mp3", audio.getAudio());

        DashScopeContentPart video = DashScopeContentPart.video("http://example.com/video.mp4");
        assertEquals("http://example.com/video.mp4", video.getVideoAsString());
    }

    @Test
    void testDashScopeRequestSerialization() throws Exception {
        DashScopeMessage msg =
                DashScopeMessage.builder().role("user").content("Tell me a joke").build();

        DashScopeParameters params =
                DashScopeParameters.builder()
                        .resultFormat("message")
                        .temperature(0.7)
                        .maxTokens(1000)
                        .build();

        DashScopeRequest request =
                DashScopeRequest.builder()
                        .model("qwen-plus")
                        .input(DashScopeInput.of(List.of(msg)))
                        .parameters(params)
                        .build();

        String json = objectMapper.writeValueAsString(request);

        assertTrue(json.contains("qwen-plus"));
        assertTrue(json.contains("Tell me a joke"));
        assertTrue(json.contains("temperature"));
        assertTrue(json.contains("result_format"));
    }

    @Test
    void testDashScopeRequestWithTools() throws Exception {
        Map<String, Object> parameters = new HashMap<>();
        parameters.put("type", "object");
        Map<String, Object> properties = new HashMap<>();
        Map<String, Object> locationProp = new HashMap<>();
        locationProp.put("type", "string");
        locationProp.put("description", "City name");
        properties.put("location", locationProp);
        parameters.put("properties", properties);
        parameters.put("required", List.of("location"));

        DashScopeToolFunction toolFunction =
                DashScopeToolFunction.builder()
                        .name("get_weather")
                        .description("Get weather information")
                        .parameters(parameters)
                        .build();

        DashScopeTool tool = DashScopeTool.function(toolFunction);

        DashScopeParameters params =
                DashScopeParameters.builder()
                        .resultFormat("message")
                        .tools(List.of(tool))
                        .toolChoice("auto")
                        .build();

        DashScopeRequest request =
                DashScopeRequest.builder()
                        .model("qwen-plus")
                        .input(DashScopeInput.of(List.of()))
                        .parameters(params)
                        .build();

        String json = objectMapper.writeValueAsString(request);

        assertTrue(json.contains("tools"));
        assertTrue(json.contains("function"));
        assertTrue(json.contains("get_weather"));
        assertTrue(json.contains("tool_choice"));
    }

    @Test
    void testDashScopeResponseDeserialization() throws Exception {
        String json =
                """
                {
                  "request_id": "req-123",
                  "output": {
                    "choices": [
                      {
                        "message": {
                          "role": "assistant",
                          "content": "Hello! How can I help you?"
                        },
                        "finish_reason": "stop"
                      }
                    ]
                  },
                  "usage": {
                    "input_tokens": 10,
                    "output_tokens": 8,
                    "total_tokens": 18
                  }
                }
                """;

        DashScopeResponse response = objectMapper.readValue(json, DashScopeResponse.class);

        assertEquals("req-123", response.getRequestId());
        assertFalse(response.isError());
        assertNotNull(response.getOutput());
        assertNotNull(response.getOutput().getFirstChoice());

        DashScopeChoice choice = response.getOutput().getFirstChoice();
        assertEquals("stop", choice.getFinishReason());
        assertEquals("assistant", choice.getMessage().getRole());
        assertEquals("Hello! How can I help you?", choice.getMessage().getContentAsString());

        DashScopeUsage usage = response.getUsage();
        assertEquals(10, usage.getInputTokens());
        assertEquals(8, usage.getOutputTokens());
    }

    @Test
    void testDashScopeResponseWithToolCalls() throws Exception {
        String json =
                """
                {
                  "request_id": "req-456",
                  "output": {
                    "choices": [
                      {
                        "message": {
                          "role": "assistant",
                          "content": null,
                          "tool_calls": [
                            {
                              "id": "call_abc",
                              "type": "function",
                              "function": {
                                "name": "get_weather",
                                "arguments": "{\\"location\\": \\"Beijing\\"}"
                              }
                            }
                          ]
                        },
                        "finish_reason": "tool_calls"
                      }
                    ]
                  },
                  "usage": {
                    "input_tokens": 20,
                    "output_tokens": 15
                  }
                }
                """;

        DashScopeResponse response = objectMapper.readValue(json, DashScopeResponse.class);

        DashScopeMessage message = response.getOutput().getFirstChoice().getMessage();
        assertNull(message.getContentAsString());
        assertNotNull(message.getToolCalls());
        assertEquals(1, message.getToolCalls().size());

        DashScopeToolCall toolCall = message.getToolCalls().get(0);
        assertEquals("call_abc", toolCall.getId());
        assertEquals("function", toolCall.getType());
        assertEquals("get_weather", toolCall.getFunction().getName());
        assertTrue(toolCall.getFunction().getArguments().contains("Beijing"));
    }

    @Test
    void testDashScopeResponseWithThinking() throws Exception {
        String json =
                """
                {
                  "request_id": "req-789",
                  "output": {
                    "choices": [
                      {
                        "message": {
                          "role": "assistant",
                          "reasoning_content": "Let me think about this...",
                          "content": "The answer is 42."
                        },
                        "finish_reason": "stop"
                      }
                    ]
                  }
                }
                """;

        DashScopeResponse response = objectMapper.readValue(json, DashScopeResponse.class);

        DashScopeMessage message = response.getOutput().getFirstChoice().getMessage();
        assertEquals("Let me think about this...", message.getReasoningContent());
        assertEquals("The answer is 42.", message.getContentAsString());
    }

    @Test
    void testDashScopeErrorResponse() throws Exception {
        String json =
                """
                {
                  "request_id": "req-err",
                  "code": "InvalidParameter",
                  "message": "Invalid model name"
                }
                """;

        DashScopeResponse response = objectMapper.readValue(json, DashScopeResponse.class);

        assertTrue(response.isError());
        assertEquals("InvalidParameter", response.getCode());
        assertEquals("Invalid model name", response.getMessage());
    }

    @Test
    void testDashScopeParametersBuilder() {
        DashScopeParameters params =
                DashScopeParameters.builder()
                        .resultFormat("message")
                        .incrementalOutput(true)
                        .temperature(0.8)
                        .topP(0.95)
                        .maxTokens(2048)
                        .enableThinking(true)
                        .thinkingBudget(500)
                        .seed(42)
                        .frequencyPenalty(0.5)
                        .presencePenalty(0.3)
                        .build();

        assertEquals("message", params.getResultFormat());
        assertTrue(params.getIncrementalOutput());
        assertEquals(0.8, params.getTemperature());
        assertEquals(0.95, params.getTopP());
        assertEquals(2048, params.getMaxTokens());
        assertTrue(params.getEnableThinking());
        assertEquals(500, params.getThinkingBudget());
        assertEquals(42, params.getSeed());
        assertEquals(0.5, params.getFrequencyPenalty());
        assertEquals(0.3, params.getPresencePenalty());
    }
}

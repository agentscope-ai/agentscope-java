/*
 * Copyright 2024-2026 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      https://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package io.agentscope.core.formatter.ollama;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.agentscope.core.formatter.ollama.dto.OllamaFunction;
import io.agentscope.core.formatter.ollama.dto.OllamaMessage;
import io.agentscope.core.formatter.ollama.dto.OllamaResponse;
import io.agentscope.core.formatter.ollama.dto.OllamaToolCall;
import io.agentscope.core.message.ContentBlock;
import io.agentscope.core.message.TextBlock;
import io.agentscope.core.message.ToolUseBlock;
import io.agentscope.core.model.ChatResponse;
import io.agentscope.core.model.ChatUsage;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Unit tests for OllamaResponseParser.
 */
@DisplayName("OllamaResponseParser Unit Tests")
class OllamaResponseParserTest {

    private OllamaResponseParser parser;

    @BeforeEach
    void setUp() {
        parser = new OllamaResponseParser();
    }

    @Test
    @DisplayName("Should create parser successfully")
    void testConstructor() {
        assertNotNull(parser);
    }

    @Test
    @DisplayName("Should parse response with text content")
    void testParseResponseWithTextContent() {
        // Arrange
        OllamaResponse response = new OllamaResponse();
        response.setModel("test-model");
        response.setCreatedAt("2024-01-01T00:00:00.000Z");

        OllamaMessage message = new OllamaMessage("assistant", "Hello, world!");
        response.setMessage(message);

        // Act
        ChatResponse chatResponse = parser.parseResponse(response);

        // Assert
        assertNotNull(chatResponse);
        assertEquals("test-model", chatResponse.getMetadata().get("model"));
        assertEquals(1, chatResponse.getContent().size());
        assertTrue(chatResponse.getContent().get(0) instanceof TextBlock);
        assertEquals("Hello, world!", ((TextBlock) chatResponse.getContent().get(0)).getText());
    }

    @Test
    @DisplayName("Should parse response with tool calls")
    void testParseResponseWithToolCalls() {
        // Arrange
        OllamaResponse response = new OllamaResponse();
        response.setModel("test-model");

        OllamaMessage message = new OllamaMessage("assistant", "");

        OllamaToolCall toolCall = new OllamaToolCall();
        OllamaFunction function = new OllamaFunction();
        function.setName("test_function");
        function.setArguments(Map.of("param1", "value1"));
        toolCall.setFunction(function);

        message.setToolCalls(Arrays.asList(toolCall));
        response.setMessage(message);

        // Act
        ChatResponse chatResponse = parser.parseResponse(response);

        // Assert
        assertNotNull(chatResponse);
        List<ContentBlock> content = chatResponse.getContent();
        assertEquals(1, content.size());
        assertTrue(content.get(0) instanceof ToolUseBlock);

        ToolUseBlock toolBlock = (ToolUseBlock) content.get(0);
        assertEquals("test_function", toolBlock.getName());
        assertEquals("value1", toolBlock.getInput().get("param1"));
    }

    @Test
    @DisplayName("Should parse response with both text and tool calls")
    void testParseResponseWithTextAndToolCalls() {
        // Arrange
        OllamaResponse response = new OllamaResponse();
        response.setModel("test-model");

        OllamaMessage message = new OllamaMessage("assistant", "Here is the result:");

        OllamaToolCall toolCall = new OllamaToolCall();
        OllamaFunction function = new OllamaFunction();
        function.setName("calculate");
        function.setArguments(Map.of("operation", "add", "a", 5, "b", 3));
        toolCall.setFunction(function);

        message.setToolCalls(Arrays.asList(toolCall));
        response.setMessage(message);

        // Act
        ChatResponse chatResponse = parser.parseResponse(response);

        // Assert
        assertNotNull(chatResponse);
        List<ContentBlock> content = chatResponse.getContent();
        assertEquals(2, content.size());

        // First block should be text
        assertTrue(content.get(0) instanceof TextBlock);
        assertEquals("Here is the result:", ((TextBlock) content.get(0)).getText());

        // Second block should be tool call
        assertTrue(content.get(1) instanceof ToolUseBlock);
        ToolUseBlock toolBlock = (ToolUseBlock) content.get(1);
        assertEquals("calculate", toolBlock.getName());
        assertEquals(5, toolBlock.getInput().get("a"));
    }

    @Test
    @DisplayName("Should handle response with null message")
    void testParseResponseWithNullMessage() {
        // Arrange
        OllamaResponse response = new OllamaResponse();
        response.setModel("test-model");
        response.setMessage(null);

        // Act
        ChatResponse chatResponse = parser.parseResponse(response);

        // Assert
        assertNotNull(chatResponse);
        assertTrue(chatResponse.getContent().isEmpty());
    }

    @Test
    @DisplayName("Should handle response with empty message content")
    void testParseResponseWithEmptyMessage() {
        // Arrange
        OllamaResponse response = new OllamaResponse();
        response.setModel("test-model");

        OllamaMessage message = new OllamaMessage("assistant", "");
        response.setMessage(message);

        // Act
        ChatResponse chatResponse = parser.parseResponse(response);

        // Assert
        assertNotNull(chatResponse);
        assertTrue(chatResponse.getContent().isEmpty());
    }

    @Test
    @DisplayName("Should map usage information correctly")
    void testUsageMapping() {
        // Arrange
        OllamaResponse response = new OllamaResponse();
        response.setPromptEvalCount(10);
        response.setEvalCount(20);
        response.setTotalDuration(5000000000L); // 5 seconds in nanoseconds

        OllamaMessage message = new OllamaMessage("assistant", "Hello");
        response.setMessage(message);

        // Act
        ChatResponse chatResponse = parser.parseResponse(response);

        // Assert
        ChatUsage usage = chatResponse.getUsage();
        assertNotNull(usage);
        assertEquals(10, usage.getInputTokens());
        assertEquals(20, usage.getOutputTokens());
        assertEquals(5.0, usage.getTime(), 0.01); // 5 seconds
    }

    @Test
    @DisplayName("Should map metadata correctly")
    void testMetadataMapping() {
        // Arrange
        String createdAt = "2024-01-01T00:00:00.000Z";
        OllamaResponse response = new OllamaResponse();
        response.setModel("test-model");
        response.setCreatedAt(createdAt);
        response.setTotalDuration(1000L);
        response.setLoadDuration(500L);
        response.setPromptEvalCount(10);
        response.setEvalCount(20);
        response.setDone(true);
        response.setDoneReason("stop");

        OllamaMessage message = new OllamaMessage("assistant", "Hello");
        response.setMessage(message);

        // Act
        ChatResponse chatResponse = parser.parseResponse(response);

        // Assert
        Map<String, Object> metadata = chatResponse.getMetadata();
        assertNotNull(metadata);
        assertEquals("test-model", metadata.get("model"));
        assertEquals(createdAt, metadata.get("created_at"));
        assertEquals(1000L, metadata.get("total_duration"));
        assertEquals(500L, metadata.get("load_duration"));
        assertEquals(10, metadata.get("prompt_eval_count"));
        assertEquals(20, metadata.get("eval_count"));
        assertEquals(true, metadata.get("done"));
    }

    @Test
    @DisplayName("Should set finish reason correctly")
    void testFinishReason() {
        // Arrange
        OllamaResponse response = new OllamaResponse();
        response.setDoneReason("stop");

        OllamaMessage message = new OllamaMessage("assistant", "Hello");
        response.setMessage(message);

        // Act
        ChatResponse chatResponse = parser.parseResponse(response);

        // Assert
        assertEquals("stop", chatResponse.getFinishReason());
    }

    @Test
    @DisplayName("Should set fallback finish reason when done is true but doneReason is null")
    void testFallbackFinishReason() {
        // Arrange
        OllamaResponse response = new OllamaResponse();
        response.setDone(true);

        OllamaMessage message = new OllamaMessage("assistant", "Hello");
        response.setMessage(message);

        // Act
        ChatResponse chatResponse = parser.parseResponse(response);

        // Assert
        assertEquals("stop", chatResponse.getFinishReason());
    }

    @Test
    @DisplayName("Should parse tool call with no parameters (empty arguments map)")
    void testParseToolCallWithNoParameters() {
        // Arrange - This reproduces the bug from issue #569
        // Ollama returns empty map {} for tools with no parameters
        OllamaResponse response = new OllamaResponse();
        response.setModel("test-model");

        OllamaMessage message = new OllamaMessage("assistant", "");

        OllamaToolCall toolCall = new OllamaToolCall();
        OllamaFunction function = new OllamaFunction();
        function.setName("getTime");
        function.setArguments(Map.of()); // Empty map for no parameters
        toolCall.setFunction(function);

        message.setToolCalls(Arrays.asList(toolCall));
        response.setMessage(message);

        // Act
        ChatResponse chatResponse = parser.parseResponse(response);

        // Assert
        assertNotNull(chatResponse);
        List<ContentBlock> content = chatResponse.getContent();
        assertEquals(1, content.size());
        assertTrue(content.get(0) instanceof ToolUseBlock);

        ToolUseBlock toolBlock = (ToolUseBlock) content.get(0);
        assertEquals("getTime", toolBlock.getName());
        assertTrue(toolBlock.getInput().isEmpty(), "Input should be empty map");

        // Verify that getContent() returns "{}" (not null) for no-parameter tools
        assertNotNull(
                toolBlock.getContent(),
                "ToolUseBlock content should not be null for validation in ToolExecutor");
        assertEquals(
                "{}",
                toolBlock.getContent(),
                "ToolUseBlock content should be empty JSON object string for no-parameter tools");
    }
}

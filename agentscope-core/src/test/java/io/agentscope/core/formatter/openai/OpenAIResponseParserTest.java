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
package io.agentscope.core.formatter.openai;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.agentscope.core.formatter.openai.dto.OpenAIChoice;
import io.agentscope.core.formatter.openai.dto.OpenAIMessage;
import io.agentscope.core.formatter.openai.dto.OpenAIReasoningDetail;
import io.agentscope.core.formatter.openai.dto.OpenAIResponse;
import io.agentscope.core.formatter.openai.dto.OpenAIUsage;
import io.agentscope.core.message.ContentBlock;
import io.agentscope.core.message.TextBlock;
import io.agentscope.core.message.ThinkingBlock;
import io.agentscope.core.message.ToolUseBlock;
import io.agentscope.core.model.ChatResponse;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

/**
 * Unit tests for OpenAIResponseParser.
 *
 * <p>Tests the parsing of OpenAI responses including reasoning content and details.
 */
@Tag("unit")
@DisplayName("OpenAIResponseParser Unit Tests")
class OpenAIResponseParserTest {

    private OpenAIResponseParser parser;
    private Instant startTime;

    @BeforeEach
    void setUp() {
        parser = new OpenAIResponseParser();
        startTime = Instant.now();
    }

    @Test
    @DisplayName("Should parse response with reasoning content")
    void testParseResponseWithReasoningContent() {
        OpenAIResponse response = new OpenAIResponse();
        response.setObject("chat.completion");

        OpenAIUsage usage = new OpenAIUsage();
        usage.setPromptTokens(10);
        usage.setCompletionTokens(20);
        response.setUsage(usage);

        OpenAIMessage message = new OpenAIMessage();
        message.setReasoningContent("This is my thinking process");
        message.setContent("Final answer");
        message.setRole("assistant");

        OpenAIChoice choice = new OpenAIChoice();
        choice.setMessage(message);
        choice.setFinishReason("stop");
        choice.setIndex(0);

        response.setChoices(List.of(choice));

        ChatResponse result = parser.parseResponse(response, startTime);

        assertNotNull(result);
        List<ContentBlock> content = result.getContent();
        assertEquals(2, content.size());

        assertTrue(content.get(0) instanceof ThinkingBlock);
        ThinkingBlock thinking = (ThinkingBlock) content.get(0);
        assertEquals("This is my thinking process", thinking.getThinking());

        assertTrue(content.get(1) instanceof TextBlock);
        TextBlock text = (TextBlock) content.get(1);
        assertEquals("Final answer", text.getText());
    }

    @Test
    @DisplayName("Should parse response with reasoning details")
    void testParseResponseWithReasoningDetails() {
        OpenAIResponse response = new OpenAIResponse();
        response.setObject("chat.completion");

        OpenAIReasoningDetail detail = new OpenAIReasoningDetail();
        detail.setId("reason-123");
        detail.setType("reasoning.encrypted");
        detail.setData("signature-data");
        detail.setIndex(0);

        OpenAIMessage message = new OpenAIMessage();
        message.setReasoningDetails(List.of(detail));
        message.setContent("Answer");
        message.setRole("assistant");

        OpenAIChoice choice = new OpenAIChoice();
        choice.setMessage(message);
        choice.setFinishReason("stop");
        choice.setIndex(0);

        response.setChoices(List.of(choice));

        ChatResponse result = parser.parseResponse(response, startTime);

        assertNotNull(result);
        List<ContentBlock> content = result.getContent();
        assertTrue(content.get(0) instanceof TextBlock);
    }

    @Test
    @DisplayName("Should parse response with null usage")
    void testParseResponseWithNullUsage() {
        OpenAIResponse response = new OpenAIResponse();
        response.setObject("chat.completion");
        response.setUsage(null);

        OpenAIMessage message = new OpenAIMessage();
        message.setContent("Answer");
        message.setRole("assistant");

        OpenAIChoice choice = new OpenAIChoice();
        choice.setMessage(message);
        choice.setFinishReason("stop");
        choice.setIndex(0);

        response.setChoices(List.of(choice));

        ChatResponse result = parser.parseResponse(response, startTime);

        assertNotNull(result);
        assertNull(result.getUsage());
    }

    @Test
    @DisplayName("Should parse response with null prompt tokens")
    void testParseResponseWithNullPromptTokens() {
        OpenAIResponse response = new OpenAIResponse();
        response.setObject("chat.completion");

        OpenAIUsage usage = new OpenAIUsage();
        usage.setPromptTokens(null);
        usage.setCompletionTokens(20);
        response.setUsage(usage);

        OpenAIMessage message = new OpenAIMessage();
        message.setContent("Answer");
        message.setRole("assistant");

        OpenAIChoice choice = new OpenAIChoice();
        choice.setMessage(message);
        choice.setFinishReason("stop");
        choice.setIndex(0);

        response.setChoices(List.of(choice));

        ChatResponse result = parser.parseResponse(response, startTime);

        assertNotNull(result);
        assertNotNull(result.getUsage());
        assertEquals(0, result.getUsage().getInputTokens());
        assertEquals(20, result.getUsage().getOutputTokens());
    }

    @Test
    @DisplayName("Should parse chunk response")
    void testParseChunkResponse() {
        OpenAIResponse response = new OpenAIResponse();
        response.setObject("chat.completion.chunk");

        OpenAIMessage delta = new OpenAIMessage();
        delta.setContent("Partial answer");
        delta.setRole("assistant");

        OpenAIChoice choice = new OpenAIChoice();
        choice.setDelta(delta);
        choice.setIndex(0);

        response.setChoices(List.of(choice));

        ChatResponse result = parser.parseResponse(response, startTime);

        assertNotNull(result);
        List<ContentBlock> content = result.getContent();
        assertEquals(1, content.size());
        assertTrue(content.get(0) instanceof TextBlock);
        assertEquals("Partial answer", ((TextBlock) content.get(0)).getText());
    }

    @Test
    @DisplayName("Should parse response with tool calls")
    void testParseResponseWithToolCalls() {
        OpenAIResponse response = new OpenAIResponse();
        response.setObject("chat.completion");

        OpenAIMessage message = new OpenAIMessage();
        message.setContent("Let me call a tool");
        message.setRole("assistant");

        io.agentscope.core.formatter.openai.dto.OpenAIToolCall toolCall =
                new io.agentscope.core.formatter.openai.dto.OpenAIToolCall();
        toolCall.setId("call_123");
        toolCall.setType("function");

        io.agentscope.core.formatter.openai.dto.OpenAIFunction function =
                new io.agentscope.core.formatter.openai.dto.OpenAIFunction();
        function.setName("test_tool");
        function.setArguments("{\"param\":\"value\"}");
        toolCall.setFunction(function);

        message.setToolCalls(List.of(toolCall));

        OpenAIChoice choice = new OpenAIChoice();
        choice.setMessage(message);
        choice.setFinishReason("tool_calls");
        choice.setIndex(0);

        response.setChoices(List.of(choice));

        ChatResponse result = parser.parseResponse(response, startTime);

        assertNotNull(result);
        List<ContentBlock> content = result.getContent();
        assertTrue(content.get(0) instanceof TextBlock);
        assertTrue(content.get(1) instanceof ToolUseBlock);

        ToolUseBlock toolBlock = (ToolUseBlock) content.get(1);
        assertEquals("test_tool", toolBlock.getName());
    }

    @Test
    @DisplayName("Should parse response with empty reasoning content")
    void testParseResponseWithEmptyReasoningContent() {
        OpenAIResponse response = new OpenAIResponse();
        response.setObject("chat.completion");

        OpenAIMessage message = new OpenAIMessage();
        message.setReasoningContent("");
        message.setContent("Answer");
        message.setRole("assistant");

        OpenAIChoice choice = new OpenAIChoice();
        choice.setMessage(message);
        choice.setFinishReason("stop");
        choice.setIndex(0);

        response.setChoices(List.of(choice));

        ChatResponse result = parser.parseResponse(response, startTime);

        assertNotNull(result);
        List<ContentBlock> content = result.getContent();
        // Empty reasoning content should not create ThinkingBlock
        assertEquals(1, content.size());
        assertTrue(content.get(0) instanceof TextBlock);
    }

    @Test
    @DisplayName("Should parse response with null choices")
    void testParseResponseWithNullChoices() {
        OpenAIResponse response = new OpenAIResponse();
        response.setObject("chat.completion");
        response.setChoices(null);

        ChatResponse result = parser.parseResponse(response, startTime);

        assertNotNull(result);
        assertTrue(result.getContent().isEmpty());
    }

    @Test
    @DisplayName("Should parse response with null message in choice")
    void testParseResponseWithNullMessage() {
        OpenAIResponse response = new OpenAIResponse();
        response.setObject("chat.completion");

        OpenAIChoice choice = new OpenAIChoice();
        choice.setMessage(null);
        choice.setFinishReason("stop");
        choice.setIndex(0);

        response.setChoices(List.of(choice));

        ChatResponse result = parser.parseResponse(response, startTime);

        assertNotNull(result);
        assertTrue(result.getContent().isEmpty());
    }

    @Test
    @DisplayName("Should parse response with null completion tokens")
    void testParseResponseWithNullCompletionTokens() {
        OpenAIResponse response = new OpenAIResponse();
        response.setObject("chat.completion");

        OpenAIUsage usage = new OpenAIUsage();
        usage.setPromptTokens(10);
        usage.setCompletionTokens(null);
        response.setUsage(usage);

        OpenAIMessage message = new OpenAIMessage();
        message.setContent("Answer");
        message.setRole("assistant");

        OpenAIChoice choice = new OpenAIChoice();
        choice.setMessage(message);
        choice.setFinishReason("stop");
        choice.setIndex(0);

        response.setChoices(List.of(choice));

        ChatResponse result = parser.parseResponse(response, startTime);

        assertNotNull(result);
        assertNotNull(result.getUsage());
        assertEquals(10, result.getUsage().getInputTokens());
        assertEquals(0, result.getUsage().getOutputTokens());
    }
}

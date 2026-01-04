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
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.agentscope.core.formatter.openai.dto.OpenAIChoice;
import io.agentscope.core.formatter.openai.dto.OpenAIError;
import io.agentscope.core.formatter.openai.dto.OpenAIFunction;
import io.agentscope.core.formatter.openai.dto.OpenAIMessage;
import io.agentscope.core.formatter.openai.dto.OpenAIReasoningDetail;
import io.agentscope.core.formatter.openai.dto.OpenAIResponse;
import io.agentscope.core.formatter.openai.dto.OpenAIToolCall;
import io.agentscope.core.formatter.openai.dto.OpenAIUsage;
import io.agentscope.core.message.ContentBlock;
import io.agentscope.core.message.TextBlock;
import io.agentscope.core.message.ThinkingBlock;
import io.agentscope.core.message.ToolUseBlock;
import io.agentscope.core.model.ChatResponse;
import io.agentscope.core.model.exception.OpenAIException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
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
        // ThinkingBlock comes first (to store reasoning_details metadata)
        assertTrue(content.get(0) instanceof ThinkingBlock);
        ThinkingBlock thinkingBlock = (ThinkingBlock) content.get(0);
        assertNotNull(thinkingBlock.getMetadata());
        assertTrue(
                thinkingBlock.getMetadata().containsKey(ThinkingBlock.METADATA_REASONING_DETAILS));
        // TextBlock comes second
        assertTrue(content.get(1) instanceof TextBlock);
        assertEquals("Answer", ((TextBlock) content.get(1)).getText());
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

    @Nested
    @DisplayName("Streaming Error Handling Tests")
    class StreamingErrorTests {

        @Test
        @DisplayName("Should throw exception for error in streaming response")
        void testStreamingResponseWithError() {
            OpenAIResponse response = new OpenAIResponse();
            response.setObject("chat.completion.chunk");

            OpenAIError error = new OpenAIError();
            error.setMessage("Rate limit exceeded");
            error.setCode("rate_limit_exceeded");
            response.setError(error);

            assertThrows(OpenAIException.class, () -> parser.parseResponse(response, startTime));
        }

        @Test
        @DisplayName("Should throw exception with null error message")
        void testStreamingResponseWithNullErrorMessage() {
            OpenAIResponse response = new OpenAIResponse();
            response.setObject("chat.completion.chunk");

            OpenAIError error = new OpenAIError();
            error.setMessage(null);
            error.setCode(null);
            response.setError(error);

            OpenAIException ex =
                    assertThrows(
                            OpenAIException.class, () -> parser.parseResponse(response, startTime));
            assertTrue(ex.getMessage().contains("Unknown error"));
        }
    }

    @Nested
    @DisplayName("Reasoning Details Type Tests")
    class ReasoningDetailsTypeTests {

        @Test
        @DisplayName("Should parse reasoning.text type")
        void testParseReasoningTextType() {
            OpenAIResponse response = new OpenAIResponse();
            response.setObject("chat.completion");

            OpenAIReasoningDetail textDetail = new OpenAIReasoningDetail();
            textDetail.setType("reasoning.text");
            textDetail.setText("Step by step reasoning...");

            OpenAIMessage message = new OpenAIMessage();
            message.setReasoningDetails(List.of(textDetail));
            message.setContent("Final answer");
            message.setRole("assistant");

            OpenAIChoice choice = new OpenAIChoice();
            choice.setMessage(message);
            choice.setFinishReason("stop");

            response.setChoices(List.of(choice));

            ChatResponse result = parser.parseResponse(response, startTime);

            assertNotNull(result);
            List<ContentBlock> content = result.getContent();
            assertTrue(content.get(0) instanceof ThinkingBlock);
            ThinkingBlock thinking = (ThinkingBlock) content.get(0);
            assertEquals("Step by step reasoning...", thinking.getThinking());
        }

        @Test
        @DisplayName("Should parse reasoning.summary type")
        void testParseReasoningSummaryType() {
            OpenAIResponse response = new OpenAIResponse();
            response.setObject("chat.completion");

            OpenAIReasoningDetail summaryDetail = new OpenAIReasoningDetail();
            summaryDetail.setType("reasoning.summary");
            summaryDetail.setText("Summary of reasoning");

            OpenAIMessage message = new OpenAIMessage();
            message.setReasoningDetails(List.of(summaryDetail));
            message.setContent("Answer");
            message.setRole("assistant");

            OpenAIChoice choice = new OpenAIChoice();
            choice.setMessage(message);
            choice.setFinishReason("stop");

            response.setChoices(List.of(choice));

            ChatResponse result = parser.parseResponse(response, startTime);

            assertNotNull(result);
            List<ContentBlock> content = result.getContent();
            assertTrue(content.get(0) instanceof ThinkingBlock);
            ThinkingBlock thinking = (ThinkingBlock) content.get(0);
            assertEquals("Summary of reasoning", thinking.getThinking());
        }

        @Test
        @DisplayName("Should combine multiple reasoning detail types")
        void testCombineMultipleReasoningTypes() {
            OpenAIResponse response = new OpenAIResponse();
            response.setObject("chat.completion");

            OpenAIReasoningDetail textDetail = new OpenAIReasoningDetail();
            textDetail.setType("reasoning.text");
            textDetail.setText("First part");

            OpenAIReasoningDetail summaryDetail = new OpenAIReasoningDetail();
            summaryDetail.setType("reasoning.summary");
            summaryDetail.setText(" and second part");

            OpenAIMessage message = new OpenAIMessage();
            message.setReasoningDetails(List.of(textDetail, summaryDetail));
            message.setContent("Answer");
            message.setRole("assistant");

            OpenAIChoice choice = new OpenAIChoice();
            choice.setMessage(message);
            choice.setFinishReason("stop");

            response.setChoices(List.of(choice));

            ChatResponse result = parser.parseResponse(response, startTime);

            assertNotNull(result);
            ThinkingBlock thinking = (ThinkingBlock) result.getContent().get(0);
            assertEquals("First part and second part", thinking.getThinking());
        }
    }

    @Nested
    @DisplayName("Tool Call Edge Cases")
    class ToolCallEdgeCasesTests {

        @Test
        @DisplayName("Should skip tool call with null name")
        void testToolCallWithNullName() {
            OpenAIResponse response = new OpenAIResponse();
            response.setObject("chat.completion");

            OpenAIFunction function = new OpenAIFunction();
            function.setName(null);
            function.setArguments("{}");

            OpenAIToolCall toolCall = new OpenAIToolCall();
            toolCall.setId("call_123");
            toolCall.setType("function");
            toolCall.setFunction(function);

            OpenAIMessage message = new OpenAIMessage();
            message.setToolCalls(List.of(toolCall));
            message.setRole("assistant");

            OpenAIChoice choice = new OpenAIChoice();
            choice.setMessage(message);
            choice.setFinishReason("tool_calls");

            response.setChoices(List.of(choice));

            ChatResponse result = parser.parseResponse(response, startTime);

            assertNotNull(result);
            // Tool call with null name should be skipped
            assertTrue(
                    result.getContent().stream().noneMatch(block -> block instanceof ToolUseBlock));
        }

        @Test
        @DisplayName("Should generate ID for tool call with null ID")
        void testToolCallWithNullId() {
            OpenAIResponse response = new OpenAIResponse();
            response.setObject("chat.completion");

            OpenAIFunction function = new OpenAIFunction();
            function.setName("test_tool");
            function.setArguments("{}");

            OpenAIToolCall toolCall = new OpenAIToolCall();
            toolCall.setId(null);
            toolCall.setType("function");
            toolCall.setFunction(function);

            OpenAIMessage message = new OpenAIMessage();
            message.setToolCalls(List.of(toolCall));
            message.setRole("assistant");

            OpenAIChoice choice = new OpenAIChoice();
            choice.setMessage(message);
            choice.setFinishReason("tool_calls");

            response.setChoices(List.of(choice));

            ChatResponse result = parser.parseResponse(response, startTime);

            assertNotNull(result);
            ToolUseBlock toolBlock =
                    result.getContent().stream()
                            .filter(block -> block instanceof ToolUseBlock)
                            .map(block -> (ToolUseBlock) block)
                            .findFirst()
                            .orElse(null);

            assertNotNull(toolBlock);
            assertNotNull(toolBlock.getId());
            assertTrue(toolBlock.getId().startsWith("tool_call_"));
        }

        @Test
        @DisplayName("Should handle tool call with null arguments")
        void testToolCallWithNullArguments() {
            OpenAIResponse response = new OpenAIResponse();
            response.setObject("chat.completion");

            OpenAIFunction function = new OpenAIFunction();
            function.setName("test_tool");
            function.setArguments(null);

            OpenAIToolCall toolCall = new OpenAIToolCall();
            toolCall.setId("call_123");
            toolCall.setType("function");
            toolCall.setFunction(function);

            OpenAIMessage message = new OpenAIMessage();
            message.setToolCalls(List.of(toolCall));
            message.setRole("assistant");

            OpenAIChoice choice = new OpenAIChoice();
            choice.setMessage(message);
            choice.setFinishReason("tool_calls");

            response.setChoices(List.of(choice));

            ChatResponse result = parser.parseResponse(response, startTime);

            assertNotNull(result);
            ToolUseBlock toolBlock =
                    result.getContent().stream()
                            .filter(block -> block instanceof ToolUseBlock)
                            .map(block -> (ToolUseBlock) block)
                            .findFirst()
                            .orElse(null);

            assertNotNull(toolBlock);
            assertTrue(toolBlock.getInput().isEmpty());
        }

        @Test
        @DisplayName("Should handle malformed JSON arguments gracefully")
        void testToolCallWithMalformedJson() {
            OpenAIResponse response = new OpenAIResponse();
            response.setObject("chat.completion");

            OpenAIFunction function = new OpenAIFunction();
            function.setName("test_tool");
            function.setArguments("{malformed json}");

            OpenAIToolCall toolCall = new OpenAIToolCall();
            toolCall.setId("call_123");
            toolCall.setType("function");
            toolCall.setFunction(function);

            OpenAIMessage message = new OpenAIMessage();
            message.setToolCalls(List.of(toolCall));
            message.setRole("assistant");

            OpenAIChoice choice = new OpenAIChoice();
            choice.setMessage(message);
            choice.setFinishReason("tool_calls");

            response.setChoices(List.of(choice));

            ChatResponse result = parser.parseResponse(response, startTime);

            // Should not throw, but tool call may not be added due to parse error
            assertNotNull(result);
        }
    }

    @Nested
    @DisplayName("Streaming Tool Call Tests")
    class StreamingToolCallTests {

        @Test
        @DisplayName("Should parse streaming tool call with complete metadata")
        void testStreamingToolCallComplete() {
            OpenAIResponse response = new OpenAIResponse();
            response.setObject("chat.completion.chunk");

            OpenAIFunction function = new OpenAIFunction();
            function.setName("test_tool");
            function.setArguments("{\"key\":\"value\"}");

            OpenAIToolCall toolCall = new OpenAIToolCall();
            toolCall.setId("call_123");
            toolCall.setType("function");
            toolCall.setFunction(function);

            OpenAIMessage delta = new OpenAIMessage();
            delta.setToolCalls(List.of(toolCall));
            delta.setRole("assistant");

            OpenAIChoice choice = new OpenAIChoice();
            choice.setDelta(delta);
            choice.setIndex(0);

            response.setChoices(List.of(choice));

            ChatResponse result = parser.parseResponse(response, startTime);

            assertNotNull(result);
            ToolUseBlock toolBlock =
                    result.getContent().stream()
                            .filter(block -> block instanceof ToolUseBlock)
                            .map(block -> (ToolUseBlock) block)
                            .findFirst()
                            .orElse(null);

            assertNotNull(toolBlock);
            assertEquals("test_tool", toolBlock.getName());
        }

        @Test
        @DisplayName("Should create fragment placeholder for streaming argument chunks")
        void testStreamingToolCallFragment() {
            OpenAIResponse response = new OpenAIResponse();
            response.setObject("chat.completion.chunk");

            OpenAIFunction function = new OpenAIFunction();
            function.setName(""); // Empty name indicates fragment
            function.setArguments("{\"partial");

            OpenAIToolCall toolCall = new OpenAIToolCall();
            toolCall.setId(null);
            toolCall.setType("function");
            toolCall.setFunction(function);

            OpenAIMessage delta = new OpenAIMessage();
            delta.setToolCalls(List.of(toolCall));
            delta.setRole("assistant");

            OpenAIChoice choice = new OpenAIChoice();
            choice.setDelta(delta);
            choice.setIndex(0);

            response.setChoices(List.of(choice));

            ChatResponse result = parser.parseResponse(response, startTime);

            assertNotNull(result);
            ToolUseBlock toolBlock =
                    result.getContent().stream()
                            .filter(block -> block instanceof ToolUseBlock)
                            .map(block -> (ToolUseBlock) block)
                            .findFirst()
                            .orElse(null);

            assertNotNull(toolBlock);
            assertEquals(OpenAIResponseParser.FRAGMENT_PLACEHOLDER, toolBlock.getName());
        }

        @Test
        @DisplayName("Should parse chunk with reasoning content")
        void testChunkWithReasoningContent() {
            OpenAIResponse response = new OpenAIResponse();
            response.setObject("chat.completion.chunk");

            OpenAIMessage delta = new OpenAIMessage();
            delta.setReasoningContent("Thinking...");
            delta.setRole("assistant");

            OpenAIChoice choice = new OpenAIChoice();
            choice.setDelta(delta);
            choice.setIndex(0);

            response.setChoices(List.of(choice));

            ChatResponse result = parser.parseResponse(response, startTime);

            assertNotNull(result);
            ThinkingBlock thinkingBlock =
                    result.getContent().stream()
                            .filter(block -> block instanceof ThinkingBlock)
                            .map(block -> (ThinkingBlock) block)
                            .findFirst()
                            .orElse(null);

            assertNotNull(thinkingBlock);
            assertEquals("Thinking...", thinkingBlock.getThinking());
        }

        @Test
        @DisplayName("Should parse chunk with reasoning details")
        void testChunkWithReasoningDetails() {
            OpenAIResponse response = new OpenAIResponse();
            response.setObject("chat.completion.chunk");

            OpenAIReasoningDetail detail = new OpenAIReasoningDetail();
            detail.setType("reasoning.text");
            detail.setText("Chunk thinking");

            OpenAIMessage delta = new OpenAIMessage();
            delta.setReasoningDetails(List.of(detail));
            delta.setRole("assistant");

            OpenAIChoice choice = new OpenAIChoice();
            choice.setDelta(delta);
            choice.setIndex(0);

            response.setChoices(List.of(choice));

            ChatResponse result = parser.parseResponse(response, startTime);

            assertNotNull(result);
            ThinkingBlock thinkingBlock =
                    result.getContent().stream()
                            .filter(block -> block instanceof ThinkingBlock)
                            .map(block -> (ThinkingBlock) block)
                            .findFirst()
                            .orElse(null);

            assertNotNull(thinkingBlock);
            assertEquals("Chunk thinking", thinkingBlock.getThinking());
        }

        @Test
        @DisplayName("Should handle chunk with null delta")
        void testChunkWithNullDelta() {
            OpenAIResponse response = new OpenAIResponse();
            response.setObject("chat.completion.chunk");

            OpenAIChoice choice = new OpenAIChoice();
            choice.setDelta(null);
            choice.setIndex(0);

            response.setChoices(List.of(choice));

            ChatResponse result = parser.parseResponse(response, startTime);

            assertNotNull(result);
            assertTrue(result.getContent().isEmpty());
        }

        @Test
        @DisplayName("Should handle chunk with usage information")
        void testChunkWithUsage() {
            OpenAIResponse response = new OpenAIResponse();
            response.setObject("chat.completion.chunk");

            OpenAIUsage usage = new OpenAIUsage();
            usage.setPromptTokens(100);
            usage.setCompletionTokens(50);
            response.setUsage(usage);

            OpenAIMessage delta = new OpenAIMessage();
            delta.setContent("");
            delta.setRole("assistant");

            OpenAIChoice choice = new OpenAIChoice();
            choice.setDelta(delta);
            choice.setFinishReason("stop");
            choice.setIndex(0);

            response.setChoices(List.of(choice));

            ChatResponse result = parser.parseResponse(response, startTime);

            assertNotNull(result);
            assertNotNull(result.getUsage());
            assertEquals(100, result.getUsage().getInputTokens());
            assertEquals(50, result.getUsage().getOutputTokens());
        }
    }

    @Nested
    @DisplayName("Thought Signature Tests")
    class ThoughtSignatureTests {

        @Test
        @DisplayName("Should preserve thought signature in tool call")
        void testToolCallWithThoughtSignature() {
            OpenAIResponse response = new OpenAIResponse();
            response.setObject("chat.completion");

            OpenAIFunction function = new OpenAIFunction();
            function.setName("test_tool");
            function.setArguments("{}");

            OpenAIToolCall toolCall = new OpenAIToolCall();
            toolCall.setId("call_123");
            toolCall.setType("function");
            toolCall.setFunction(function);
            // Parser calls toolCall.getThoughtSignature(), not function.getThoughtSignature()
            toolCall.setThoughtSignature("signature123");

            OpenAIMessage message = new OpenAIMessage();
            message.setToolCalls(List.of(toolCall));
            message.setRole("assistant");

            OpenAIChoice choice = new OpenAIChoice();
            choice.setMessage(message);
            choice.setFinishReason("tool_calls");

            response.setChoices(List.of(choice));

            ChatResponse result = parser.parseResponse(response, startTime);

            assertNotNull(result);
            ToolUseBlock toolBlock =
                    result.getContent().stream()
                            .filter(block -> block instanceof ToolUseBlock)
                            .map(block -> (ToolUseBlock) block)
                            .findFirst()
                            .orElse(null);

            assertNotNull(toolBlock);
            assertNotNull(toolBlock.getMetadata());
            assertEquals(
                    "signature123",
                    toolBlock.getMetadata().get(ToolUseBlock.METADATA_THOUGHT_SIGNATURE));
        }

        @Test
        @DisplayName("Should get thought signature from reasoning details")
        void testToolCallWithSignatureFromReasoningDetails() {
            OpenAIResponse response = new OpenAIResponse();
            response.setObject("chat.completion");

            OpenAIReasoningDetail encryptedDetail = new OpenAIReasoningDetail();
            encryptedDetail.setId("call_123");
            encryptedDetail.setType("reasoning.encrypted");
            encryptedDetail.setData("signature_from_detail");

            OpenAIFunction function = new OpenAIFunction();
            function.setName("test_tool");
            function.setArguments("{}");

            OpenAIToolCall toolCall = new OpenAIToolCall();
            toolCall.setId("call_123");
            toolCall.setType("function");
            toolCall.setFunction(function);

            OpenAIMessage message = new OpenAIMessage();
            message.setReasoningDetails(List.of(encryptedDetail));
            message.setToolCalls(List.of(toolCall));
            message.setRole("assistant");

            OpenAIChoice choice = new OpenAIChoice();
            choice.setMessage(message);
            choice.setFinishReason("tool_calls");

            response.setChoices(List.of(choice));

            ChatResponse result = parser.parseResponse(response, startTime);

            assertNotNull(result);
            // Find the ToolUseBlock
            List<ToolUseBlock> toolBlocks = new ArrayList<>();
            for (ContentBlock block : result.getContent()) {
                if (block instanceof ToolUseBlock) {
                    toolBlocks.add((ToolUseBlock) block);
                }
            }

            assertNotNull(toolBlocks);
            assertTrue(!toolBlocks.isEmpty());
            ToolUseBlock toolBlock = toolBlocks.get(0);
            assertNotNull(toolBlock.getMetadata());
            assertEquals(
                    "signature_from_detail",
                    toolBlock.getMetadata().get(ToolUseBlock.METADATA_THOUGHT_SIGNATURE));
        }
    }
}

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
package io.agentscope.core.formatter.dashscope;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.agentscope.core.formatter.dashscope.dto.DashScopeChoice;
import io.agentscope.core.formatter.dashscope.dto.DashScopeFunction;
import io.agentscope.core.formatter.dashscope.dto.DashScopeMessage;
import io.agentscope.core.formatter.dashscope.dto.DashScopeOutput;
import io.agentscope.core.formatter.dashscope.dto.DashScopeResponse;
import io.agentscope.core.formatter.dashscope.dto.DashScopeToolCall;
import io.agentscope.core.formatter.dashscope.dto.DashScopeUsage;
import io.agentscope.core.message.ContentBlock;
import io.agentscope.core.message.TextBlock;
import io.agentscope.core.message.ThinkingBlock;
import io.agentscope.core.message.ToolUseBlock;
import io.agentscope.core.model.ChatResponse;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

/** Tests for DashScopeResponseParser. */
@Tag("unit")
class DashScopeResponseParserTest {

    private DashScopeResponseParser parser;
    private Instant startTime;

    @BeforeEach
    void setUp() {
        parser = new DashScopeResponseParser();
        startTime = Instant.now();
    }

    @Test
    void testParseSimpleTextResponse() {
        DashScopeMessage message =
                DashScopeMessage.builder()
                        .role("assistant")
                        .content("Hello, how can I help you?")
                        .build();

        DashScopeChoice choice = new DashScopeChoice();
        choice.setMessage(message);
        choice.setFinishReason("stop");

        DashScopeOutput output = new DashScopeOutput();
        output.setChoices(List.of(choice));

        DashScopeResponse response = new DashScopeResponse();
        response.setRequestId("req-123");
        response.setOutput(output);

        ChatResponse chatResponse = parser.parseResponse(response, startTime);

        assertNotNull(chatResponse);
        assertEquals("req-123", chatResponse.getId());
        assertEquals("stop", chatResponse.getFinishReason());
        assertEquals(1, chatResponse.getContent().size());
        assertTrue(chatResponse.getContent().get(0) instanceof TextBlock);
        assertEquals(
                "Hello, how can I help you?",
                ((TextBlock) chatResponse.getContent().get(0)).getText());
    }

    @Test
    void testParseResponseWithThinkingContent() {
        DashScopeMessage message =
                DashScopeMessage.builder().role("assistant").content("The answer is 42.").build();
        message.setReasoningContent("Let me think about this step by step...");

        DashScopeChoice choice = new DashScopeChoice();
        choice.setMessage(message);
        choice.setFinishReason("stop");

        DashScopeOutput output = new DashScopeOutput();
        output.setChoices(List.of(choice));

        DashScopeResponse response = new DashScopeResponse();
        response.setRequestId("req-thinking");
        response.setOutput(output);

        ChatResponse chatResponse = parser.parseResponse(response, startTime);

        assertNotNull(chatResponse);
        assertEquals(2, chatResponse.getContent().size());

        // ThinkingBlock should come first
        assertTrue(chatResponse.getContent().get(0) instanceof ThinkingBlock);
        assertEquals(
                "Let me think about this step by step...",
                ((ThinkingBlock) chatResponse.getContent().get(0)).getThinking());

        // Then TextBlock
        assertTrue(chatResponse.getContent().get(1) instanceof TextBlock);
        assertEquals("The answer is 42.", ((TextBlock) chatResponse.getContent().get(1)).getText());
    }

    @Test
    void testParseResponseWithToolCalls() {
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
                        .content("Let me check the weather.")
                        .toolCalls(List.of(toolCall))
                        .build();

        DashScopeChoice choice = new DashScopeChoice();
        choice.setMessage(message);
        choice.setFinishReason("tool_calls");

        DashScopeOutput output = new DashScopeOutput();
        output.setChoices(List.of(choice));

        DashScopeResponse response = new DashScopeResponse();
        response.setRequestId("req-tool");
        response.setOutput(output);

        ChatResponse chatResponse = parser.parseResponse(response, startTime);

        assertNotNull(chatResponse);
        assertEquals("tool_calls", chatResponse.getFinishReason());
        assertEquals(2, chatResponse.getContent().size());

        // TextBlock first
        assertTrue(chatResponse.getContent().get(0) instanceof TextBlock);

        // Then ToolUseBlock
        assertTrue(chatResponse.getContent().get(1) instanceof ToolUseBlock);
        ToolUseBlock toolUse = (ToolUseBlock) chatResponse.getContent().get(1);
        assertEquals("call_123", toolUse.getId());
        assertEquals("get_weather", toolUse.getName());
        assertEquals("Beijing", toolUse.getInput().get("location"));
    }

    @Test
    void testParseResponseWithUsage() {
        DashScopeMessage message =
                DashScopeMessage.builder().role("assistant").content("Hello!").build();

        DashScopeChoice choice = new DashScopeChoice();
        choice.setMessage(message);
        choice.setFinishReason("stop");

        DashScopeOutput output = new DashScopeOutput();
        output.setChoices(List.of(choice));

        DashScopeUsage usage = new DashScopeUsage();
        usage.setInputTokens(10);
        usage.setOutputTokens(5);

        DashScopeResponse response = new DashScopeResponse();
        response.setRequestId("req-usage");
        response.setOutput(output);
        response.setUsage(usage);

        ChatResponse chatResponse = parser.parseResponse(response, startTime);

        assertNotNull(chatResponse);
        assertNotNull(chatResponse.getUsage());
        assertEquals(10, chatResponse.getUsage().getInputTokens());
        assertEquals(5, chatResponse.getUsage().getOutputTokens());
    }

    @Test
    void testParseEmptyResponse() {
        DashScopeResponse response = new DashScopeResponse();
        response.setRequestId("req-empty");

        ChatResponse chatResponse = parser.parseResponse(response, startTime);

        assertNotNull(chatResponse);
        assertEquals("req-empty", chatResponse.getId());
        assertTrue(chatResponse.getContent().isEmpty());
        assertNull(chatResponse.getUsage());
    }

    @Test
    void testParseResponseWithEmptyChoices() {
        DashScopeOutput output = new DashScopeOutput();
        output.setChoices(List.of());

        DashScopeResponse response = new DashScopeResponse();
        response.setRequestId("req-empty-choices");
        response.setOutput(output);

        ChatResponse chatResponse = parser.parseResponse(response, startTime);

        assertNotNull(chatResponse);
        assertTrue(chatResponse.getContent().isEmpty());
    }

    @Test
    void testParseResponseWithOutputLevelFinishReason() {
        DashScopeMessage message =
                DashScopeMessage.builder().role("assistant").content("Done").build();

        DashScopeChoice choice = new DashScopeChoice();
        choice.setMessage(message);
        // No finish_reason at choice level

        DashScopeOutput output = new DashScopeOutput();
        output.setChoices(List.of(choice));
        output.setFinishReason("length");

        DashScopeResponse response = new DashScopeResponse();
        response.setRequestId("req-output-finish");
        response.setOutput(output);

        ChatResponse chatResponse = parser.parseResponse(response, startTime);

        assertNotNull(chatResponse);
        assertEquals("length", chatResponse.getFinishReason());
    }

    @Test
    void testParseResponseWithInvalidToolCallJson() {
        DashScopeFunction function = DashScopeFunction.of("my_tool", "invalid-json");
        DashScopeToolCall toolCall =
                DashScopeToolCall.builder()
                        .id("call_invalid")
                        .type("function")
                        .function(function)
                        .build();

        DashScopeMessage message =
                DashScopeMessage.builder().role("assistant").toolCalls(List.of(toolCall)).build();

        DashScopeChoice choice = new DashScopeChoice();
        choice.setMessage(message);

        DashScopeOutput output = new DashScopeOutput();
        output.setChoices(List.of(choice));

        DashScopeResponse response = new DashScopeResponse();
        response.setRequestId("req-invalid-json");
        response.setOutput(output);

        ChatResponse chatResponse = parser.parseResponse(response, startTime);

        assertNotNull(chatResponse);
        assertEquals(1, chatResponse.getContent().size());
        assertTrue(chatResponse.getContent().get(0) instanceof ToolUseBlock);
        ToolUseBlock toolUse = (ToolUseBlock) chatResponse.getContent().get(0);
        assertEquals("invalid-json", toolUse.getContent());
    }

    @Test
    void testParseResponseWithFragmentToolCall() {
        // Simulate streaming tool call with fragment (no name, only arguments)
        DashScopeFunction function = new DashScopeFunction();
        function.setArguments("{\"partial\":\"data\"}");

        DashScopeToolCall toolCall =
                DashScopeToolCall.builder()
                        .id("fragment_1")
                        .type("function")
                        .function(function)
                        .build();

        DashScopeMessage message =
                DashScopeMessage.builder().role("assistant").toolCalls(List.of(toolCall)).build();

        DashScopeChoice choice = new DashScopeChoice();
        choice.setMessage(message);

        DashScopeOutput output = new DashScopeOutput();
        output.setChoices(List.of(choice));

        DashScopeResponse response = new DashScopeResponse();
        response.setRequestId("req-fragment");
        response.setOutput(output);

        ChatResponse chatResponse = parser.parseResponse(response, startTime);

        assertNotNull(chatResponse);
        assertEquals(1, chatResponse.getContent().size());
        assertTrue(chatResponse.getContent().get(0) instanceof ToolUseBlock);
        ToolUseBlock toolUse = (ToolUseBlock) chatResponse.getContent().get(0);
        assertEquals(DashScopeResponseParser.FRAGMENT_PLACEHOLDER, toolUse.getName());
    }

    @Test
    void testParseResponseWithNullToolCalls() {
        DashScopeMessage message =
                DashScopeMessage.builder()
                        .role("assistant")
                        .content("No tools here")
                        .toolCalls(null)
                        .build();

        DashScopeChoice choice = new DashScopeChoice();
        choice.setMessage(message);

        DashScopeOutput output = new DashScopeOutput();
        output.setChoices(List.of(choice));

        DashScopeResponse response = new DashScopeResponse();
        response.setRequestId("req-no-tools");
        response.setOutput(output);

        ChatResponse chatResponse = parser.parseResponse(response, startTime);

        assertNotNull(chatResponse);
        assertEquals(1, chatResponse.getContent().size());
        assertTrue(chatResponse.getContent().get(0) instanceof TextBlock);
    }

    @Test
    void testParseResponseWithEmptyContent() {
        DashScopeMessage message = DashScopeMessage.builder().role("assistant").content("").build();

        DashScopeChoice choice = new DashScopeChoice();
        choice.setMessage(message);

        DashScopeOutput output = new DashScopeOutput();
        output.setChoices(List.of(choice));

        DashScopeResponse response = new DashScopeResponse();
        response.setRequestId("req-empty-content");
        response.setOutput(output);

        ChatResponse chatResponse = parser.parseResponse(response, startTime);

        assertNotNull(chatResponse);
        assertTrue(chatResponse.getContent().isEmpty());
    }

    @Test
    void testParseResponseWithNullUsageFields() {
        DashScopeMessage message =
                DashScopeMessage.builder().role("assistant").content("Test").build();

        DashScopeChoice choice = new DashScopeChoice();
        choice.setMessage(message);

        DashScopeOutput output = new DashScopeOutput();
        output.setChoices(List.of(choice));

        DashScopeUsage usage = new DashScopeUsage();
        // Leave inputTokens and outputTokens as null

        DashScopeResponse response = new DashScopeResponse();
        response.setRequestId("req-null-usage");
        response.setOutput(output);
        response.setUsage(usage);

        ChatResponse chatResponse = parser.parseResponse(response, startTime);

        assertNotNull(chatResponse);
        assertNotNull(chatResponse.getUsage());
        assertEquals(0, chatResponse.getUsage().getInputTokens());
        assertEquals(0, chatResponse.getUsage().getOutputTokens());
    }

    @Test
    void testAddToolCallsFromMessageWithEmptyList() {
        DashScopeMessage message =
                DashScopeMessage.builder().role("assistant").toolCalls(List.of()).build();

        List<ContentBlock> blocks = new ArrayList<>();
        parser.addToolCallsFromMessage(message, blocks);

        assertTrue(blocks.isEmpty());
    }

    @Test
    void testAddToolCallsFromMessageWithNullFunction() {
        DashScopeToolCall toolCall =
                DashScopeToolCall.builder()
                        .id("call_null_func")
                        .type("function")
                        .function(null)
                        .build();

        DashScopeMessage message =
                DashScopeMessage.builder().role("assistant").toolCalls(List.of(toolCall)).build();

        List<ContentBlock> blocks = new ArrayList<>();
        parser.addToolCallsFromMessage(message, blocks);

        assertTrue(blocks.isEmpty());
    }

    @Test
    void testAddToolCallsFromMessageWithNullToolCallInList() {
        List<DashScopeToolCall> toolCalls = new ArrayList<>();
        toolCalls.add(null);
        toolCalls.add(
                DashScopeToolCall.builder()
                        .id("valid_call")
                        .type("function")
                        .function(DashScopeFunction.of("valid_tool", "{}"))
                        .build());

        DashScopeMessage message =
                DashScopeMessage.builder().role("assistant").toolCalls(toolCalls).build();

        List<ContentBlock> blocks = new ArrayList<>();
        parser.addToolCallsFromMessage(message, blocks);

        assertEquals(1, blocks.size());
        assertTrue(blocks.get(0) instanceof ToolUseBlock);
        assertEquals("valid_tool", ((ToolUseBlock) blocks.get(0)).getName());
    }

    @Test
    void testAddToolCallsWithAutoGeneratedId() {
        DashScopeFunction function = DashScopeFunction.of("test_tool", "{\"key\":\"value\"}");
        DashScopeToolCall toolCall =
                DashScopeToolCall.builder()
                        .type("function")
                        .function(function)
                        .build(); // No id set

        DashScopeMessage message =
                DashScopeMessage.builder().role("assistant").toolCalls(List.of(toolCall)).build();

        List<ContentBlock> blocks = new ArrayList<>();
        parser.addToolCallsFromMessage(message, blocks);

        assertEquals(1, blocks.size());
        assertTrue(blocks.get(0) instanceof ToolUseBlock);
        ToolUseBlock toolUse = (ToolUseBlock) blocks.get(0);
        assertNotNull(toolUse.getId());
        assertTrue(toolUse.getId().startsWith("tool_call_"));
    }
}

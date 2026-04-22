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
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.agentscope.core.formatter.openai.dto.OpenAIChoice;
import io.agentscope.core.formatter.openai.dto.OpenAIFunction;
import io.agentscope.core.formatter.openai.dto.OpenAIMessage;
import io.agentscope.core.formatter.openai.dto.OpenAIResponse;
import io.agentscope.core.formatter.openai.dto.OpenAIToolCall;
import io.agentscope.core.message.ContentBlock;
import io.agentscope.core.message.ToolUseBlock;
import io.agentscope.core.model.ChatResponse;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

/**
 * Tests for streaming tool call accumulation in OpenAI response parsing.
 *
 * <p>These tests verify that streaming tool calls with fragments are correctly parsed
 * and can be accumulated by the ToolCallsAccumulator.
 */
@Tag("unit")
@DisplayName("OpenAI Streaming Tool Call Tests")
class OpenAIStreamingToolCallTest {

    private OpenAIResponseParser parser;

    @BeforeEach
    void setUp() {
        parser = new OpenAIResponseParser();
    }

    @Test
    @DisplayName("Should parse streaming tool call with id and name in first chunk")
    void testStreamingToolCallFirstChunk() {
        OpenAIResponse response = new OpenAIResponse();
        response.setId("chatcmpl-tool");
        response.setObject("chat.completion.chunk");

        OpenAIChoice choice = new OpenAIChoice();
        choice.setIndex(0);

        OpenAIMessage delta = new OpenAIMessage();
        List<OpenAIToolCall> toolCalls = new ArrayList<>();

        OpenAIToolCall toolCall = new OpenAIToolCall();
        toolCall.setId("call_abc123");
        toolCall.setIndex(0);
        toolCall.setType("function");

        OpenAIFunction function = new OpenAIFunction();
        function.setName("get_weather");
        function.setArguments("{\"location\":");
        toolCall.setFunction(function);
        toolCalls.add(toolCall);

        delta.setToolCalls(toolCalls);
        choice.setDelta(delta);
        response.setChoices(List.of(choice));

        ChatResponse chatResponse = parser.parseResponse(response, Instant.now());

        assertNotNull(chatResponse);
        assertNotNull(chatResponse.getContent());
        assertEquals(1, chatResponse.getContent().size());

        ContentBlock block = chatResponse.getContent().get(0);
        assertTrue(block instanceof ToolUseBlock);
        ToolUseBlock toolUse = (ToolUseBlock) block;
        assertEquals("call_abc123", toolUse.getId());
        assertEquals("get_weather", toolUse.getName());
        assertNotNull(toolUse.getContent());
        assertTrue(toolUse.getContent().contains("location"));
    }

    @Test
    @DisplayName("Should parse streaming tool call fragment (subsequent chunk)")
    void testStreamingToolCallFragment() {
        OpenAIResponse response = new OpenAIResponse();
        response.setId("chatcmpl-tool");
        response.setObject("chat.completion.chunk");

        OpenAIChoice choice = new OpenAIChoice();
        choice.setIndex(0);

        OpenAIMessage delta = new OpenAIMessage();
        List<OpenAIToolCall> toolCalls = new ArrayList<>();

        // Fragment chunk: no name, only arguments
        OpenAIToolCall toolCall = new OpenAIToolCall();
        toolCall.setId("call_abc123");
        toolCall.setIndex(0);
        toolCall.setType("function");

        OpenAIFunction function = new OpenAIFunction();
        // No name in fragment
        function.setName(null);
        function.setArguments("\"Beijing\"}");
        toolCall.setFunction(function);
        toolCalls.add(toolCall);

        delta.setToolCalls(toolCalls);
        choice.setDelta(delta);
        response.setChoices(List.of(choice));

        ChatResponse chatResponse = parser.parseResponse(response, Instant.now());

        assertNotNull(chatResponse);
        assertNotNull(chatResponse.getContent());
        assertEquals(1, chatResponse.getContent().size());

        ContentBlock block = chatResponse.getContent().get(0);
        assertTrue(block instanceof ToolUseBlock);
        ToolUseBlock toolUse = (ToolUseBlock) block;
        // Fragment should have empty id or placeholder name
        assertEquals(OpenAIResponseParser.FRAGMENT_PLACEHOLDER, toolUse.getName());
        assertNotNull(toolUse.getContent());
        assertTrue(toolUse.getContent().contains("Beijing"));
    }

    @Test
    @DisplayName("Should parse multiple streaming tool calls")
    void testMultipleStreamingToolCalls() {
        OpenAIResponse response = new OpenAIResponse();
        response.setId("chatcmpl-tool");
        response.setObject("chat.completion.chunk");

        OpenAIChoice choice = new OpenAIChoice();
        choice.setIndex(0);

        OpenAIMessage delta = new OpenAIMessage();
        List<OpenAIToolCall> toolCalls = new ArrayList<>();

        // First tool call
        OpenAIToolCall toolCall1 = new OpenAIToolCall();
        toolCall1.setId("call_1");
        toolCall1.setIndex(0);
        toolCall1.setType("function");

        OpenAIFunction function1 = new OpenAIFunction();
        function1.setName("get_weather");
        function1.setArguments("{\"location\":");
        toolCall1.setFunction(function1);
        toolCalls.add(toolCall1);

        // Second tool call
        OpenAIToolCall toolCall2 = new OpenAIToolCall();
        toolCall2.setId("call_2");
        toolCall2.setIndex(1);
        toolCall2.setType("function");

        OpenAIFunction function2 = new OpenAIFunction();
        function2.setName("get_time");
        function2.setArguments("{\"timezone\":");
        toolCall2.setFunction(function2);
        toolCalls.add(toolCall2);

        delta.setToolCalls(toolCalls);
        choice.setDelta(delta);
        response.setChoices(List.of(choice));

        ChatResponse chatResponse = parser.parseResponse(response, Instant.now());

        assertNotNull(chatResponse);
        assertNotNull(chatResponse.getContent());
        assertEquals(2, chatResponse.getContent().size());

        // Verify both tool calls are present
        boolean foundWeather = false;
        boolean foundTime = false;
        for (ContentBlock block : chatResponse.getContent()) {
            if (block instanceof ToolUseBlock) {
                ToolUseBlock toolUse = (ToolUseBlock) block;
                if ("get_weather".equals(toolUse.getName())) {
                    foundWeather = true;
                    assertEquals("call_1", toolUse.getId());
                } else if ("get_time".equals(toolUse.getName())) {
                    foundTime = true;
                    assertEquals("call_2", toolUse.getId());
                }
            }
        }

        assertTrue(foundWeather, "Should have found get_weather tool call");
        assertTrue(foundTime, "Should have found get_time tool call");
    }

    @Test
    @DisplayName("Should handle tool call with empty arguments")
    void testToolCallWithEmptyArguments() {
        OpenAIResponse response = new OpenAIResponse();
        response.setId("chatcmpl-tool");
        response.setObject("chat.completion.chunk");

        OpenAIChoice choice = new OpenAIChoice();
        choice.setIndex(0);

        OpenAIMessage delta = new OpenAIMessage();
        List<OpenAIToolCall> toolCalls = new ArrayList<>();

        OpenAIToolCall toolCall = new OpenAIToolCall();
        toolCall.setId("call_empty");
        toolCall.setIndex(0);
        toolCall.setType("function");

        OpenAIFunction function = new OpenAIFunction();
        function.setName("no_args_tool");
        function.setArguments("{}");
        toolCall.setFunction(function);
        toolCalls.add(toolCall);

        delta.setToolCalls(toolCalls);
        choice.setDelta(delta);
        response.setChoices(List.of(choice));

        ChatResponse chatResponse = parser.parseResponse(response, Instant.now());

        assertNotNull(chatResponse);
        assertNotNull(chatResponse.getContent());
        assertEquals(1, chatResponse.getContent().size());

        ContentBlock block = chatResponse.getContent().get(0);
        assertTrue(block instanceof ToolUseBlock);
        ToolUseBlock toolUse = (ToolUseBlock) block;
        assertEquals("call_empty", toolUse.getId());
        assertEquals("no_args_tool", toolUse.getName());
        assertNotNull(toolUse.getInput());
        assertTrue(toolUse.getInput().isEmpty());
    }

    @Test
    @DisplayName("Should handle tool call with null arguments")
    void testToolCallWithNullArguments() {
        OpenAIResponse response = new OpenAIResponse();
        response.setId("chatcmpl-tool");
        response.setObject("chat.completion.chunk");

        OpenAIChoice choice = new OpenAIChoice();
        choice.setIndex(0);

        OpenAIMessage delta = new OpenAIMessage();
        List<OpenAIToolCall> toolCalls = new ArrayList<>();

        OpenAIToolCall toolCall = new OpenAIToolCall();
        toolCall.setId("call_null");
        toolCall.setIndex(0);
        toolCall.setType("function");

        OpenAIFunction function = new OpenAIFunction();
        function.setName("null_args_tool");
        function.setArguments(null);
        toolCall.setFunction(function);
        toolCalls.add(toolCall);

        delta.setToolCalls(toolCalls);
        choice.setDelta(delta);
        response.setChoices(List.of(choice));

        ChatResponse chatResponse = parser.parseResponse(response, Instant.now());

        assertNotNull(chatResponse);
        assertNotNull(chatResponse.getContent());
        assertEquals(1, chatResponse.getContent().size());

        ContentBlock block = chatResponse.getContent().get(0);
        assertTrue(block instanceof ToolUseBlock);
        ToolUseBlock toolUse = (ToolUseBlock) block;
        assertEquals("call_null", toolUse.getId());
        assertEquals("null_args_tool", toolUse.getName());
    }
}

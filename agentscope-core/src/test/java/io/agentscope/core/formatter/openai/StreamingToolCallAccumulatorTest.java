/*
 * Copyright 2024-2025 the original author or authors.
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
package io.agentscope.core.formatter.openai;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.agentscope.core.formatter.openai.dto.OpenAIFunction;
import io.agentscope.core.formatter.openai.dto.OpenAIToolCall;
import io.agentscope.core.message.ToolUseBlock;
import java.util.Arrays;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

/**
 * Unit tests for StreamingToolCallAccumulator.
 *
 * <p>Tests the accumulation of streaming tool call data from OpenAI API responses.
 */
@Tag("unit")
@DisplayName("StreamingToolCallAccumulator Unit Tests")
class StreamingToolCallAccumulatorTest {

    private StreamingToolCallAccumulator accumulator;

    @BeforeEach
    void setUp() {
        accumulator = new StreamingToolCallAccumulator();
    }

    @Test
    @DisplayName("Should handle null tool calls")
    void testAccumulateNull() {
        accumulator.accumulate((List<OpenAIToolCall>) null);
        assertTrue(accumulator.isEmpty());
        assertEquals(0, accumulator.size());
    }

    @Test
    @DisplayName("Should handle empty tool calls list")
    void testAccumulateEmptyList() {
        accumulator.accumulate(new java.util.ArrayList<>());
        assertTrue(accumulator.isEmpty());
        assertEquals(0, accumulator.size());
    }

    @Test
    @DisplayName("Should accumulate single tool call with complete data")
    void testAccumulateSingleToolCall() {
        OpenAIFunction function = OpenAIFunction.of("test_function", "{\"key\": \"value\"}");

        OpenAIToolCall toolCall =
                OpenAIToolCall.builder().id("call_123").index(0).function(function).build();

        accumulator.accumulate(toolCall);

        assertFalse(accumulator.isEmpty());
        assertEquals(1, accumulator.size());
        assertEquals("call_123", accumulator.getToolCallId(0));
        assertEquals("test_function", accumulator.getToolName(0));
        assertEquals("{\"key\": \"value\"}", accumulator.getAccumulatedArguments(0));
        assertTrue(accumulator.isComplete(0));
    }

    @Test
    @DisplayName("Should accumulate fragmented tool call arguments")
    void testAccumulateFragmentedArguments() {
        // First chunk: ID and function name
        OpenAIFunction function1 = OpenAIFunction.of("calculator", "");
        OpenAIToolCall toolCall1 =
                OpenAIToolCall.builder().id("call_456").index(0).function(function1).build();

        accumulator.accumulate(toolCall1);

        // Second chunk: partial arguments
        OpenAIFunction function2 = OpenAIFunction.of(null, "{\"operation\":");
        OpenAIToolCall toolCall2 = OpenAIToolCall.builder().index(0).function(function2).build();

        accumulator.accumulate(toolCall2);

        // Third chunk: rest of arguments
        OpenAIFunction function3 = OpenAIFunction.of(null, " \"add\", \"a\": 1, \"b\": 2}");
        OpenAIToolCall toolCall3 = OpenAIToolCall.builder().index(0).function(function3).build();

        accumulator.accumulate(toolCall3);

        assertTrue(accumulator.isComplete(0));
        assertEquals(
                "{\"operation\": \"add\", \"a\": 1, \"b\": 2}",
                accumulator.getAccumulatedArguments(0));
    }

    @Test
    @DisplayName("Should handle multiple parallel tool calls")
    void testAccumulateMultipleToolCalls() {
        // First tool call at index 0
        OpenAIFunction function1 = OpenAIFunction.of("function1", "{\"param1\": \"value1\"}");
        OpenAIToolCall toolCall1 =
                OpenAIToolCall.builder().id("call_1").index(0).function(function1).build();

        // Second tool call at index 1
        OpenAIFunction function2 = OpenAIFunction.of("function2", "{\"param2\": \"value2\"}");
        OpenAIToolCall toolCall2 =
                OpenAIToolCall.builder().id("call_2").index(1).function(function2).build();

        accumulator.accumulate(Arrays.asList(toolCall1, toolCall2));

        assertEquals(2, accumulator.size());
        assertEquals("call_1", accumulator.getToolCallId(0));
        assertEquals("call_2", accumulator.getToolCallId(1));
        assertTrue(accumulator.isComplete(0));
        assertTrue(accumulator.isComplete(1));
    }

    @Test
    @DisplayName("Should handle missing index (defaults to 0)")
    void testAccumulateMissingIndex() {
        OpenAIFunction function = OpenAIFunction.of("test_func", "{\"key\": \"value\"}");
        OpenAIToolCall toolCall =
                OpenAIToolCall.builder().id("call_789").function(function).build();

        accumulator.accumulate(toolCall);

        assertEquals("call_789", accumulator.getToolCallId(0));
        assertEquals("test_func", accumulator.getToolName(0));
    }

    @Test
    @DisplayName("Should handle null function in tool call")
    void testAccumulateNullFunction() {
        OpenAIToolCall toolCall = OpenAIToolCall.builder().id("call_999").function(null).build();

        accumulator.accumulate(toolCall);

        assertTrue(accumulator.isEmpty());
    }

    @Test
    @DisplayName("Should return empty arguments for non-existent index")
    void testGetAccumulatedArgumentsNonExistent() {
        OpenAIFunction function = OpenAIFunction.of("func", "{}");
        OpenAIToolCall toolCall =
                OpenAIToolCall.builder().id("call_1").index(0).function(function).build();

        accumulator.accumulate(toolCall);

        assertEquals("", accumulator.getAccumulatedArguments(999));
    }

    @Test
    @DisplayName("Should correctly identify incomplete tool calls")
    void testIsCompleteIncomplete() {
        // Tool call missing arguments
        OpenAIFunction function = OpenAIFunction.of("incomplete_func", "");
        OpenAIToolCall toolCall =
                OpenAIToolCall.builder().id("call_incomplete").index(0).function(function).build();

        accumulator.accumulate(toolCall);

        assertFalse(accumulator.isComplete(0));
    }

    @Test
    @DisplayName("Should parse JSON arguments and create ToolUseBlock")
    void testGetCompletedToolCalls() {
        OpenAIFunction function = OpenAIFunction.of("add_numbers", "{\"a\": 10, \"b\": 20}");
        OpenAIToolCall toolCall =
                OpenAIToolCall.builder().id("call_add").index(0).function(function).build();

        accumulator.accumulate(toolCall);

        List<ToolUseBlock> completed = accumulator.getCompletedToolCalls();

        assertEquals(1, completed.size());
        ToolUseBlock block = completed.get(0);
        assertEquals("call_add", block.getId());
        assertEquals("add_numbers", block.getName());
        assertNotNull(block.getInput());
        assertEquals(10, block.getInput().get("a"));
        assertEquals(20, block.getInput().get("b"));
    }

    @Test
    @DisplayName("Should skip incomplete tool calls in getCompletedToolCalls")
    void testGetCompletedToolCallsSkipsIncomplete() {
        // Complete tool call
        OpenAIFunction function1 = OpenAIFunction.of("complete_func", "{\"key\": \"value\"}");
        OpenAIToolCall toolCall1 =
                OpenAIToolCall.builder().id("call_1").index(0).function(function1).build();

        // Incomplete tool call (missing arguments)
        OpenAIFunction function2 = OpenAIFunction.of("incomplete_func", "");
        OpenAIToolCall toolCall2 =
                OpenAIToolCall.builder().id("call_2").index(1).function(function2).build();

        accumulator.accumulate(Arrays.asList(toolCall1, toolCall2));

        List<ToolUseBlock> completed = accumulator.getCompletedToolCalls();

        assertEquals(1, completed.size());
        assertEquals("complete_func", completed.get(0).getName());
    }

    @Test
    @DisplayName("Should handle multiple tool calls in getCompletedToolCalls")
    void testGetCompletedToolCallsMultiple() {
        OpenAIFunction function1 = OpenAIFunction.of("func1", "{\"x\": 1}");
        OpenAIToolCall toolCall1 =
                OpenAIToolCall.builder().id("id1").index(0).function(function1).build();

        OpenAIFunction function2 = OpenAIFunction.of("func2", "{\"y\": 2}");
        OpenAIToolCall toolCall2 =
                OpenAIToolCall.builder().id("id2").index(1).function(function2).build();

        accumulator.accumulate(Arrays.asList(toolCall1, toolCall2));

        List<ToolUseBlock> completed = accumulator.getCompletedToolCalls();

        assertEquals(2, completed.size());
        assertEquals("func1", completed.get(0).getName());
        assertEquals("func2", completed.get(1).getName());
    }

    @Test
    @DisplayName("Should handle malformed JSON arguments gracefully")
    void testHandleMalformedJson() {
        OpenAIFunction function = OpenAIFunction.of("broken_func", "{invalid json}");
        OpenAIToolCall toolCall =
                OpenAIToolCall.builder().id("call_broken").index(0).function(function).build();

        accumulator.accumulate(toolCall);

        // Should still be considered complete structurally
        assertTrue(accumulator.isComplete(0));

        // But input should be empty when parsing fails
        List<ToolUseBlock> completed = accumulator.getCompletedToolCalls();
        assertEquals(1, completed.size());
        // Input map should be empty due to JSON parse failure
        assertTrue(completed.get(0).getInput().isEmpty());
    }

    @Test
    @DisplayName("Should clear all accumulated data")
    void testClear() {
        OpenAIFunction function = OpenAIFunction.of("func", "{}");
        OpenAIToolCall toolCall =
                OpenAIToolCall.builder().id("call").index(0).function(function).build();

        accumulator.accumulate(toolCall);
        assertFalse(accumulator.isEmpty());

        accumulator.clear();
        assertTrue(accumulator.isEmpty());
        assertEquals(0, accumulator.size());
    }

    @Test
    @DisplayName("Should track size correctly through accumulation")
    void testSize() {
        assertTrue(accumulator.isEmpty());
        assertEquals(0, accumulator.size());

        OpenAIFunction function1 = OpenAIFunction.of("func1", "{}");
        OpenAIToolCall toolCall1 =
                OpenAIToolCall.builder().id("call1").index(0).function(function1).build();

        accumulator.accumulate(toolCall1);
        assertEquals(1, accumulator.size());

        OpenAIFunction function2 = OpenAIFunction.of("func2", "{}");
        OpenAIToolCall toolCall2 =
                OpenAIToolCall.builder().id("call2").index(1).function(function2).build();

        accumulator.accumulate(toolCall2);
        assertEquals(2, accumulator.size());
    }

    @Test
    @DisplayName("Should handle getToolCallId and getToolName for non-existent indices")
    void testGettersNonExistent() {
        accumulator.accumulate(
                OpenAIToolCall.builder()
                        .id("call")
                        .index(0)
                        .function(OpenAIFunction.of("func", "{}"))
                        .build());

        assertEquals(null, accumulator.getToolCallId(999));
        assertEquals(null, accumulator.getToolName(999));
    }
}

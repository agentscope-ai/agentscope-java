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
package io.agentscope.core.agent.accumulator;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.agentscope.core.message.ToolUseBlock;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Unit tests for ToolCallsAccumulator.
 */
@DisplayName("ToolCallsAccumulator Tests")
class ToolCallsAccumulatorTest {

    private ToolCallsAccumulator accumulator;

    @BeforeEach
    void setUp() {
        accumulator = new ToolCallsAccumulator();
    }

    @Test
    @DisplayName("Should accumulate metadata from tool call chunks")
    void testAccumulateMetadata() {
        // First chunk with thoughtSignature
        byte[] signature = "test-thought-signature".getBytes();
        Map<String, Object> metadata = new HashMap<>();
        metadata.put(ToolUseBlock.METADATA_THOUGHT_SIGNATURE, signature);

        ToolUseBlock chunk1 =
                ToolUseBlock.builder().id("call_1").name("get_weather").metadata(metadata).build();

        // Second chunk with arguments (no metadata)
        Map<String, Object> args = new HashMap<>();
        args.put("city", "Tokyo");

        ToolUseBlock chunk2 =
                ToolUseBlock.builder().id("call_1").name("get_weather").input(args).build();

        // Accumulate both chunks
        accumulator.add(chunk1);
        accumulator.add(chunk2);

        // Build and verify
        List<ToolUseBlock> result = accumulator.buildAllToolCalls();

        assertEquals(1, result.size());
        ToolUseBlock toolCall = result.get(0);

        assertEquals("call_1", toolCall.getId());
        assertEquals("get_weather", toolCall.getName());
        assertEquals("Tokyo", toolCall.getInput().get("city"));

        // Verify metadata is preserved
        assertNotNull(toolCall.getMetadata());
        assertTrue(toolCall.getMetadata().containsKey(ToolUseBlock.METADATA_THOUGHT_SIGNATURE));
        assertArrayEquals(
                signature,
                (byte[]) toolCall.getMetadata().get(ToolUseBlock.METADATA_THOUGHT_SIGNATURE));
    }

    @Test
    @DisplayName("Should accumulate without metadata")
    void testAccumulateWithoutMetadata() {
        Map<String, Object> args = new HashMap<>();
        args.put("query", "test");

        ToolUseBlock chunk = ToolUseBlock.builder().id("call_2").name("search").input(args).build();

        accumulator.add(chunk);

        List<ToolUseBlock> result = accumulator.buildAllToolCalls();

        assertEquals(1, result.size());
        ToolUseBlock toolCall = result.get(0);

        assertEquals("call_2", toolCall.getId());
        assertEquals("search", toolCall.getName());
        // Metadata should be empty (null metadata passed to builder)
        assertTrue(toolCall.getMetadata().isEmpty());
    }

    @Test
    @DisplayName("Should handle parallel tool calls with different metadata")
    void testParallelToolCallsWithMetadata() {
        // First tool call with metadata
        byte[] sig1 = "sig-1".getBytes();
        Map<String, Object> metadata1 = new HashMap<>();
        metadata1.put(ToolUseBlock.METADATA_THOUGHT_SIGNATURE, sig1);

        ToolUseBlock call1 =
                ToolUseBlock.builder()
                        .id("call_a")
                        .name("tool_a")
                        .input(Map.of("param", "value_a"))
                        .metadata(metadata1)
                        .build();

        // Second tool call without metadata
        ToolUseBlock call2 =
                ToolUseBlock.builder()
                        .id("call_b")
                        .name("tool_b")
                        .input(Map.of("param", "value_b"))
                        .build();

        accumulator.add(call1);
        accumulator.add(call2);

        List<ToolUseBlock> result = accumulator.buildAllToolCalls();

        assertEquals(2, result.size());

        // First call should have metadata
        ToolUseBlock resultA =
                result.stream().filter(t -> "call_a".equals(t.getId())).findFirst().orElse(null);
        assertNotNull(resultA);
        assertTrue(resultA.getMetadata().containsKey(ToolUseBlock.METADATA_THOUGHT_SIGNATURE));

        // Second call should not have metadata
        ToolUseBlock resultB =
                result.stream().filter(t -> "call_b".equals(t.getId())).findFirst().orElse(null);
        assertNotNull(resultB);
        assertTrue(resultB.getMetadata().isEmpty());
    }

    @Test
    @DisplayName("Should save raw content to content field in build()")
    void testBuildSavesRawContentToContentField() {
        // Simulate streaming chunks with raw content
        ToolUseBlock chunk1 =
                ToolUseBlock.builder()
                        .id("call_1")
                        .name("get_weather")
                        .content("{\"city\":")
                        .build();

        ToolUseBlock chunk2 =
                ToolUseBlock.builder()
                        .id("call_1")
                        .name("__fragment__")
                        .content("\"Beijing\"}")
                        .build();

        accumulator.add(chunk1);
        accumulator.add(chunk2);

        List<ToolUseBlock> result = accumulator.buildAllToolCalls();

        assertEquals(1, result.size());
        ToolUseBlock toolCall = result.get(0);

        // Verify content field contains accumulated raw content
        assertEquals("{\"city\":\"Beijing\"}", toolCall.getContent());
        // Verify input was parsed from raw content
        assertEquals("Beijing", toolCall.getInput().get("city"));
    }

    @Test
    @DisplayName("Should get accumulated tool call by ID")
    void testGetAccumulatedToolCallById() {
        ToolUseBlock call1 =
                ToolUseBlock.builder()
                        .id("call_1")
                        .name("weather")
                        .input(Map.of("city", "Tokyo"))
                        .build();

        ToolUseBlock call2 =
                ToolUseBlock.builder()
                        .id("call_2")
                        .name("calculator")
                        .input(Map.of("expr", "1+1"))
                        .build();

        accumulator.add(call1);
        accumulator.add(call2);

        // Get by specific ID
        ToolUseBlock result1 = accumulator.getAccumulatedToolCall("call_1");
        assertNotNull(result1);
        assertEquals("call_1", result1.getId());
        assertEquals("weather", result1.getName());
        assertEquals("Tokyo", result1.getInput().get("city"));

        ToolUseBlock result2 = accumulator.getAccumulatedToolCall("call_2");
        assertNotNull(result2);
        assertEquals("call_2", result2.getId());
        assertEquals("calculator", result2.getName());
    }

    @Test
    @DisplayName("Should fallback to lastToolCallKey when ID is null or empty")
    void testGetAccumulatedToolCallFallbackToLastKey() {
        ToolUseBlock call =
                ToolUseBlock.builder()
                        .id("call_1")
                        .name("weather")
                        .input(Map.of("city", "Tokyo"))
                        .build();

        accumulator.add(call);

        // Get with null ID should fallback to last key
        ToolUseBlock resultNull = accumulator.getAccumulatedToolCall(null);
        assertNotNull(resultNull);
        assertEquals("call_1", resultNull.getId());

        // Get with empty ID should fallback to last key
        ToolUseBlock resultEmpty = accumulator.getAccumulatedToolCall("");
        assertNotNull(resultEmpty);
        assertEquals("call_1", resultEmpty.getId());
    }

    @Test
    @DisplayName("Should return null when no tool calls accumulated")
    void testGetAccumulatedToolCallReturnsNullWhenEmpty() {
        ToolUseBlock result = accumulator.getAccumulatedToolCall("nonexistent");
        assertNull(result);

        ToolUseBlock resultNull = accumulator.getAccumulatedToolCall(null);
        assertNull(resultNull);
    }

    @Test
    @DisplayName("Should get all accumulated tool calls")
    void testGetAllAccumulatedToolCalls() {
        ToolUseBlock call1 =
                ToolUseBlock.builder()
                        .id("call_1")
                        .name("weather")
                        .input(Map.of("city", "Tokyo"))
                        .build();

        ToolUseBlock call2 =
                ToolUseBlock.builder()
                        .id("call_2")
                        .name("calculator")
                        .input(Map.of("expr", "1+1"))
                        .build();

        accumulator.add(call1);
        accumulator.add(call2);

        List<ToolUseBlock> result = accumulator.getAllAccumulatedToolCalls();

        assertEquals(2, result.size());
        assertTrue(result.stream().anyMatch(t -> "call_1".equals(t.getId())));
        assertTrue(result.stream().anyMatch(t -> "call_2".equals(t.getId())));
    }

    @Test
    @DisplayName("Should accumulate multiple parallel tool calls with streaming chunks")
    void testMultipleParallelToolCallsWithStreamingChunks() {
        // Simulate interleaved streaming chunks for two parallel tool calls
        ToolUseBlock chunk1a =
                ToolUseBlock.builder().id("call_1").name("weather").content("{\"city\":").build();

        ToolUseBlock chunk2a =
                ToolUseBlock.builder()
                        .id("call_2")
                        .name("calculator")
                        .content("{\"expr\":")
                        .build();

        ToolUseBlock chunk1b =
                ToolUseBlock.builder()
                        .id("call_1")
                        .name("__fragment__")
                        .content("\"Beijing\"}")
                        .build();

        ToolUseBlock chunk2b =
                ToolUseBlock.builder()
                        .id("call_2")
                        .name("__fragment__")
                        .content("\"1+1\"}")
                        .build();

        // Add chunks in interleaved order
        accumulator.add(chunk1a);
        accumulator.add(chunk2a);
        accumulator.add(chunk1b);
        accumulator.add(chunk2b);

        // Verify both tool calls are accumulated correctly
        ToolUseBlock result1 = accumulator.getAccumulatedToolCall("call_1");
        assertNotNull(result1);
        assertEquals("weather", result1.getName());
        assertEquals("{\"city\":\"Beijing\"}", result1.getContent());
        assertEquals("Beijing", result1.getInput().get("city"));

        ToolUseBlock result2 = accumulator.getAccumulatedToolCall("call_2");
        assertNotNull(result2);
        assertEquals("calculator", result2.getName());
        assertEquals("{\"expr\":\"1+1\"}", result2.getContent());
        assertEquals("1+1", result2.getInput().get("expr"));

        // Verify getAllAccumulatedToolCalls returns both
        List<ToolUseBlock> allCalls = accumulator.getAllAccumulatedToolCalls();
        assertEquals(2, allCalls.size());
    }
}

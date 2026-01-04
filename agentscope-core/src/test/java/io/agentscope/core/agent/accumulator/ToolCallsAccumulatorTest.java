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
}

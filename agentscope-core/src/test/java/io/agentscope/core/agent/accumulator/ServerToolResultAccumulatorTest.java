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

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.agentscope.core.message.ContentBlock;
import io.agentscope.core.message.TextBlock;
import io.agentscope.core.message.ToolResultBlock;
import io.agentscope.core.message.ToolUseBlock;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** Unit tests for {@link ServerToolResultAccumulator}. */
@DisplayName("ServerToolResultAccumulator Tests")
class ServerToolResultAccumulatorTest {

    private final ServerToolResultAccumulator accumulator = new ServerToolResultAccumulator();

    private static ToolUseBlock toolCall(String id) {
        return ToolUseBlock.builder()
                .id(id)
                .name("web_search")
                .input(Map.of("query", "AgentScope"))
                .metadata(Map.of(ToolUseBlock.METADATA_SERVER_TOOL, true))
                .build();
    }

    private static ToolResultBlock toolResult(String id, String text) {
        return ToolResultBlock.builder()
                .id(id)
                .name("web_search")
                .output(TextBlock.builder().text(text).build())
                .metadata(Map.of(ToolResultBlock.METADATA_SERVER_TOOL, true))
                .build();
    }

    @Test
    @DisplayName("Should place each result immediately after its matching tool call")
    void testPlaceAfterMatchingToolCalls() {
        accumulator.add(toolResult("call_2", "second"));
        accumulator.add(toolResult("call_1", "first"));

        List<ContentBlock> blocks =
                accumulator.placeAfterToolCalls(List.of(toolCall("call_1"), toolCall("call_2")));

        assertEquals(4, blocks.size());
        ToolUseBlock firstCall = assertInstanceOf(ToolUseBlock.class, blocks.get(0));
        ToolResultBlock firstResult = assertInstanceOf(ToolResultBlock.class, blocks.get(1));
        ToolUseBlock secondCall = assertInstanceOf(ToolUseBlock.class, blocks.get(2));
        ToolResultBlock secondResult = assertInstanceOf(ToolResultBlock.class, blocks.get(3));

        assertEquals("call_1", firstCall.getId());
        assertEquals("call_1", firstResult.getId());
        assertEquals("first", ((TextBlock) firstResult.getOutput().get(0)).getText());
        assertEquals("call_2", secondCall.getId());
        assertEquals("call_2", secondResult.getId());
        assertEquals("second", ((TextBlock) secondResult.getOutput().get(0)).getText());
    }

    @Test
    @DisplayName("Should keep the first result for a duplicate tool call id")
    void testDuplicateResultIdsKeepFirstResult() {
        accumulator.add(toolResult("call_1", "first"));
        accumulator.add(toolResult("call_1", "duplicate"));

        List<ToolResultBlock> results = accumulator.buildAllToolResults();

        assertEquals(1, results.size());
        assertEquals("first", ((TextBlock) results.get(0).getOutput().get(0)).getText());
    }

    @Test
    @DisplayName("Should preserve unmatched results in arrival order")
    void testUnmatchedResultsArePreserved() {
        accumulator.add(toolResult("orphan_1", "first orphan"));
        accumulator.add(toolResult("orphan_2", "second orphan"));

        List<ContentBlock> blocks = accumulator.placeAfterToolCalls(List.of(toolCall("call_1")));

        assertEquals(3, blocks.size());
        assertInstanceOf(ToolUseBlock.class, blocks.get(0));
        assertEquals("orphan_1", assertInstanceOf(ToolResultBlock.class, blocks.get(1)).getId());
        assertEquals("orphan_2", assertInstanceOf(ToolResultBlock.class, blocks.get(2)).getId());
    }

    @Test
    @DisplayName("Should ignore local tool results")
    void testLocalToolResultsAreIgnored() {
        ToolResultBlock localResult =
                ToolResultBlock.builder()
                        .id("call_1")
                        .name("weather")
                        .output(TextBlock.builder().text("Sunny").build())
                        .build();

        accumulator.add(localResult);

        assertFalse(accumulator.hasContent());
        assertTrue(accumulator.buildAllToolResults().isEmpty());
    }

    @Test
    @DisplayName("Should reset accumulated results")
    void testReset() {
        accumulator.add(toolResult("call_1", "first"));
        assertTrue(accumulator.hasContent());

        accumulator.reset();

        assertFalse(accumulator.hasContent());
        assertTrue(accumulator.buildAllToolResults().isEmpty());
    }
}

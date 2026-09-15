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
import static org.junit.jupiter.api.Assertions.assertNull;

import io.agentscope.core.message.ContentBlock;
import io.agentscope.core.message.ThinkingBlock;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class ThinkingAccumulatorReasoningDetailsTest {

    @Test
    void snapshotsInitiallyEmptyLists() {
        ThinkingAccumulator accumulator = new ThinkingAccumulator();
        List<String> callerDetails = new ArrayList<>();
        accumulator.add(block(callerDetails));
        ThinkingBlock first = (ThinkingBlock) accumulator.buildAggregated();
        callerDetails.add("late mutation");
        assertEquals(List.of(), details(first));
        assertEquals(List.of(), details(accumulator.buildAggregated()));
        accumulator.add(block(List.of("received")));
        assertEquals(List.of(), details(first));
        assertEquals(List.of("received"), details(accumulator.buildAggregated()));
    }

    @Test
    void scalarReplacesListAndResetClearsState() {
        ThinkingAccumulator accumulator = new ThinkingAccumulator();
        ThinkingBlock scalar =
                ThinkingBlock.builder()
                        .metadata(Map.of(ThinkingBlock.METADATA_REASONING_DETAILS, "scalar"))
                        .build();

        // Scalar before any list: scalar used
        accumulator.add(scalar);
        assertEquals(
                "scalar",
                ((ThinkingBlock) accumulator.buildAggregated())
                        .getMetadata()
                        .get(ThinkingBlock.METADATA_REASONING_DETAILS));

        // List after scalar: list replaces scalar
        accumulator.add(block(List.of("a")));
        assertEquals(List.of("a"), details(accumulator.buildAggregated()));

        // Scalar after list: scalar replaces accumulated list (last-write-wins)
        accumulator.add(scalar);
        assertEquals(
                "scalar",
                ((ThinkingBlock) accumulator.buildAggregated())
                        .getMetadata()
                        .get(ThinkingBlock.METADATA_REASONING_DETAILS));

        // Reset clears state
        accumulator.reset();
        accumulator.add(block(List.of("fresh")));
        assertEquals(List.of("fresh"), details(accumulator.buildAggregated()));
    }

    @Test
    void preservesDetailsAcrossChunksWithoutChangingOtherMetadataSemantics() {
        ThinkingAccumulator accumulator = new ThinkingAccumulator();
        accumulator.add(
                ThinkingBlock.builder()
                        .thinking("First ")
                        .metadata(
                                Map.of(
                                        ThinkingBlock.METADATA_REASONING_DETAILS,
                                        List.of("a", "b"),
                                        "provider",
                                        "old"))
                        .build());
        accumulator.add(
                ThinkingBlock.builder()
                        .thinking("second")
                        .metadata(
                                Map.of(
                                        ThinkingBlock.METADATA_REASONING_DETAILS,
                                        List.of("c"),
                                        "provider",
                                        "new"))
                        .build());
        accumulator.add(
                ThinkingBlock.builder()
                        .metadata(Map.of(ThinkingBlock.METADATA_REASONING_DETAILS, List.of()))
                        .build());

        ThinkingBlock result = (ThinkingBlock) accumulator.buildAggregated();
        assertEquals("First second", result.getThinking());
        assertEquals(List.of("a", "b", "c"), details(result));
        assertEquals("new", result.getMetadata().get("provider"));
    }

    @Test
    void keepsBuiltListsIndependentFromLaterWritesAndReset() {
        ThinkingAccumulator accumulator = new ThinkingAccumulator();
        List<String> callerDetails = new ArrayList<>(List.of("a"));
        accumulator.add(block(callerDetails));
        callerDetails.clear();
        ThinkingBlock first = (ThinkingBlock) accumulator.buildAggregated();
        assertEquals(List.of("a"), details(first));

        accumulator.add(block(List.of("b")));
        ThinkingBlock second = (ThinkingBlock) accumulator.buildAggregated();
        assertEquals(List.of("a"), details(first));
        assertEquals(List.of("a", "b"), details(second));
        details(first).clear();
        assertEquals(List.of("a", "b"), details(accumulator.buildAggregated()));

        accumulator.reset();
        assertFalse(accumulator.hasContent());
        assertNull(accumulator.buildAggregated());
        assertEquals(List.of("a", "b"), details(second));
        accumulator.add(block(List.of("c")));
        assertEquals(List.of("c"), details(accumulator.buildAggregated()));
    }

    private static ThinkingBlock block(List<String> details) {
        return ThinkingBlock.builder()
                .metadata(Map.of(ThinkingBlock.METADATA_REASONING_DETAILS, details))
                .build();
    }

    private static List<?> details(ContentBlock block) {
        return (List<?>)
                ((ThinkingBlock) block).getMetadata().get(ThinkingBlock.METADATA_REASONING_DETAILS);
    }
}

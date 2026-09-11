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
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.agentscope.core.message.ThinkingBlock;
import java.util.Map;
import org.junit.jupiter.api.Test;

class ThinkingAccumulatorTest {

    @Test
    void shouldHandleNullBlocksAndClearMetadataOnReset() {
        ThinkingAccumulator accumulator = new ThinkingAccumulator();

        accumulator.add(null);
        assertFalse(accumulator.hasContent());
        assertNull(accumulator.buildAggregated());

        accumulator.add(ThinkingBlock.builder().thinking("Reasoning").build());
        accumulator.add(
                ThinkingBlock.builder()
                        .metadata(
                                Map.of(
                                        ThinkingBlock.METADATA_ANTHROPIC_SIGNATURE,
                                        "stream-signature"))
                        .build());

        ThinkingBlock aggregated = (ThinkingBlock) accumulator.buildAggregated();
        assertNotNull(aggregated);
        assertEquals("Reasoning", aggregated.getThinking());
        assertNotNull(aggregated.getMetadata());
        assertEquals(
                "stream-signature",
                aggregated.getMetadata().get(ThinkingBlock.METADATA_ANTHROPIC_SIGNATURE));

        accumulator.reset();

        assertFalse(accumulator.hasContent());
        assertNull(accumulator.buildAggregated());
        assertTrue(accumulator.getAccumulated().isEmpty());
    }
}

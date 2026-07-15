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
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.agentscope.core.message.ThinkingBlock;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/** Unit tests for {@link ThinkingAccumulator}. */
class ThinkingAccumulatorTest {

    private ThinkingAccumulator accumulator;

    @BeforeEach
    void setUp() {
        accumulator = new ThinkingAccumulator();
    }

    @Test
    void testEmptyAccumulator() {
        accumulator.add(null);

        assertFalse(accumulator.hasContent());
        assertNull(accumulator.buildAggregated());
        assertEquals("", accumulator.getAccumulated());
    }

    @Test
    void testAccumulatesTextAndMetadata() {
        accumulator.add(ThinkingBlock.builder().thinking("first ").build());
        accumulator.add(
                ThinkingBlock.builder()
                        .thinking("second")
                        .metadata(Map.of("signature", "signature-123"))
                        .build());

        ThinkingBlock result = (ThinkingBlock) accumulator.buildAggregated();

        assertTrue(accumulator.hasContent());
        assertEquals("first second", result.getThinking());
        assertEquals("signature-123", result.getMetadata().get("signature"));
    }

    @Test
    void testMetadataOnlyContentAndReset() {
        accumulator.add(
                ThinkingBlock.builder().metadata(Map.of("signature", "signature-123")).build());

        ThinkingBlock result = (ThinkingBlock) accumulator.buildAggregated();
        accumulator.reset();

        assertEquals("", result.getThinking());
        assertEquals("signature-123", result.getMetadata().get("signature"));
        assertFalse(accumulator.hasContent());
        assertEquals("", accumulator.getAccumulated());
    }
}

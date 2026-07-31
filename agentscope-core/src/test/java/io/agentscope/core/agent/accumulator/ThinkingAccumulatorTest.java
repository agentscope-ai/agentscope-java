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
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.agentscope.core.message.ContentBlockMetadataKeys;
import io.agentscope.core.message.ThinkingBlock;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class ThinkingAccumulatorTest {

    private final ThinkingAccumulator accumulator = new ThinkingAccumulator();

    @Test
    void shouldIgnoreNullAndRemainEmpty() {
        accumulator.add(null);

        assertFalse(accumulator.hasContent());
        assertNull(accumulator.buildAggregated());
        assertTrue(accumulator.buildAllThinkingBlocks().isEmpty());
    }

    @Test
    void shouldAccumulateThinkingAndMetadata() {
        accumulator.add(ThinkingBlock.builder().thinking("Think").build());
        accumulator.add(
                ThinkingBlock.builder()
                        .thinking(" carefully")
                        .metadata(Map.of("provider", "gemini"))
                        .build());

        ThinkingBlock aggregated = (ThinkingBlock) accumulator.buildAggregated();
        List<ThinkingBlock> blocks = accumulator.buildAllThinkingBlocks();

        assertEquals("Think carefully", aggregated.getThinking());
        assertEquals("gemini", aggregated.getMetadata().get("provider"));
        assertEquals(1, blocks.size());
        assertEquals("Think carefully", blocks.get(0).getThinking());
        assertEquals("gemini", blocks.get(0).getMetadata().get("provider"));
    }

    @Test
    void shouldBuildThinkingOnlyBlockWithoutMetadata() {
        accumulator.add(ThinkingBlock.builder().thinking("Reasoning").build());

        ThinkingBlock aggregated = (ThinkingBlock) accumulator.buildAggregated();
        List<ThinkingBlock> blocks = accumulator.buildAllThinkingBlocks();

        assertTrue(accumulator.hasContent());
        assertEquals("Reasoning", aggregated.getThinking());
        assertNull(aggregated.getMetadata());
        assertEquals(1, blocks.size());
        assertEquals("Reasoning", blocks.get(0).getThinking());
        assertNull(blocks.get(0).getMetadata());
    }

    @Test
    void shouldBuildMetadataOnlyBlock() {
        accumulator.add(
                ThinkingBlock.builder()
                        .thinking("")
                        .metadata(Map.of("provider", "gemini"))
                        .build());

        ThinkingBlock aggregated = (ThinkingBlock) accumulator.buildAggregated();
        List<ThinkingBlock> blocks = accumulator.buildAllThinkingBlocks();

        assertTrue(accumulator.hasContent());
        assertEquals("", aggregated.getThinking());
        assertEquals("gemini", aggregated.getMetadata().get("provider"));
        assertEquals(1, blocks.size());
        assertEquals("", blocks.get(0).getThinking());
        assertEquals("gemini", blocks.get(0).getMetadata().get("provider"));
    }

    @Test
    void shouldAttachMetadataOnlySignatureChunkToCurrentPart() {
        byte[] signature = "signature".getBytes(StandardCharsets.UTF_8);
        accumulator.add(ThinkingBlock.builder().thinking("Reasoning").build());
        accumulator.add(
                ThinkingBlock.builder()
                        .thinking("")
                        .metadata(Map.of(ContentBlockMetadataKeys.THOUGHT_SIGNATURE, signature))
                        .build());

        List<ThinkingBlock> blocks = accumulator.buildAllThinkingBlocks();

        assertEquals(1, blocks.size());
        assertEquals("Reasoning", blocks.get(0).getThinking());
        assertArrayEquals(
                signature,
                (byte[])
                        blocks.get(0)
                                .getMetadata()
                                .get(ContentBlockMetadataKeys.THOUGHT_SIGNATURE));
    }

    @Test
    void shouldPreserveDistinctSignaturePartBoundaries() {
        byte[] firstSignature = "first-signature".getBytes(StandardCharsets.UTF_8);
        byte[] secondSignature = "second-signature".getBytes(StandardCharsets.UTF_8);
        accumulator.add(signedBlock("First", firstSignature));
        accumulator.add(signedBlock("Second", secondSignature));

        List<ThinkingBlock> blocks = accumulator.buildAllThinkingBlocks();

        assertEquals(2, blocks.size());
        assertEquals("First", blocks.get(0).getThinking());
        assertEquals("Second", blocks.get(1).getThinking());
        assertArrayEquals(firstSignature, signatureOf(blocks.get(0)));
        assertArrayEquals(secondSignature, signatureOf(blocks.get(1)));
        assertEquals("FirstSecond", accumulator.getAccumulated());
    }

    @Test
    void shouldClearThinkingMetadataAndPartBoundariesOnReset() {
        accumulator.add(signedBlock("Completed", "signature".getBytes(StandardCharsets.UTF_8)));
        accumulator.add(ThinkingBlock.builder().thinking("Pending").build());

        accumulator.reset();

        assertEquals("", accumulator.getAccumulated());
        assertFalse(accumulator.hasContent());
        assertNull(accumulator.buildAggregated());
        assertTrue(accumulator.buildAllThinkingBlocks().isEmpty());
    }

    private ThinkingBlock signedBlock(String thinking, byte[] signature) {
        return ThinkingBlock.builder()
                .thinking(thinking)
                .metadata(Map.of(ContentBlockMetadataKeys.THOUGHT_SIGNATURE, signature))
                .build();
    }

    private byte[] signatureOf(ThinkingBlock block) {
        return (byte[]) block.getMetadata().get(ContentBlockMetadataKeys.THOUGHT_SIGNATURE);
    }
}

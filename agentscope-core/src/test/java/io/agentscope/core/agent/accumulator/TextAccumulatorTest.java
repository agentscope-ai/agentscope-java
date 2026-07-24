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
import io.agentscope.core.message.TextBlock;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class TextAccumulatorTest {

    private final TextAccumulator accumulator = new TextAccumulator();

    @Test
    void shouldIgnoreNullAndRemainEmpty() {
        accumulator.add(null);

        assertFalse(accumulator.hasContent());
        assertNull(accumulator.buildAggregated());
        assertTrue(accumulator.buildAllTextBlocks().isEmpty());
    }

    @Test
    void shouldAccumulateTextAndMetadata() {
        accumulator.add(TextBlock.builder().text("Hello").build());
        accumulator.add(
                TextBlock.builder().text(" world").metadata(Map.of("provider", "gemini")).build());

        TextBlock aggregated = (TextBlock) accumulator.buildAggregated();
        List<TextBlock> blocks = accumulator.buildAllTextBlocks();

        assertEquals("Hello world", aggregated.getText());
        assertEquals("gemini", aggregated.getMetadata().get("provider"));
        assertEquals(1, blocks.size());
        assertEquals("Hello world", blocks.get(0).getText());
        assertEquals("gemini", blocks.get(0).getMetadata().get("provider"));
    }

    @Test
    void shouldBuildTextOnlyBlockWithoutMetadata() {
        accumulator.add(TextBlock.builder().text("Answer").build());

        TextBlock aggregated = (TextBlock) accumulator.buildAggregated();
        List<TextBlock> blocks = accumulator.buildAllTextBlocks();

        assertTrue(accumulator.hasContent());
        assertEquals("Answer", aggregated.getText());
        assertNull(aggregated.getMetadata());
        assertEquals(1, blocks.size());
        assertEquals("Answer", blocks.get(0).getText());
        assertNull(blocks.get(0).getMetadata());
    }

    @Test
    void shouldBuildMetadataOnlyBlock() {
        accumulator.add(
                TextBlock.builder().text("").metadata(Map.of("provider", "gemini")).build());

        TextBlock aggregated = (TextBlock) accumulator.buildAggregated();
        List<TextBlock> blocks = accumulator.buildAllTextBlocks();

        assertTrue(accumulator.hasContent());
        assertEquals("", aggregated.getText());
        assertEquals("gemini", aggregated.getMetadata().get("provider"));
        assertEquals(1, blocks.size());
        assertEquals("", blocks.get(0).getText());
        assertEquals("gemini", blocks.get(0).getMetadata().get("provider"));
    }

    @Test
    void shouldAttachMetadataOnlySignatureChunkToCurrentPart() {
        byte[] signature = "signature".getBytes(StandardCharsets.UTF_8);
        accumulator.add(TextBlock.builder().text("Answer").build());
        accumulator.add(
                TextBlock.builder()
                        .text("")
                        .metadata(Map.of(ContentBlockMetadataKeys.THOUGHT_SIGNATURE, signature))
                        .build());

        List<TextBlock> blocks = accumulator.buildAllTextBlocks();

        assertEquals(1, blocks.size());
        assertEquals("Answer", blocks.get(0).getText());
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

        List<TextBlock> blocks = accumulator.buildAllTextBlocks();

        assertEquals(2, blocks.size());
        assertEquals("First", blocks.get(0).getText());
        assertEquals("Second", blocks.get(1).getText());
        assertArrayEquals(firstSignature, signatureOf(blocks.get(0)));
        assertArrayEquals(secondSignature, signatureOf(blocks.get(1)));
        assertEquals("FirstSecond", accumulator.getAccumulated());
    }

    @Test
    void shouldClearTextMetadataAndPartBoundariesOnReset() {
        accumulator.add(signedBlock("Completed", "signature".getBytes(StandardCharsets.UTF_8)));
        accumulator.add(TextBlock.builder().text("Pending").build());

        accumulator.reset();

        assertEquals("", accumulator.getAccumulated());
        assertFalse(accumulator.hasContent());
        assertNull(accumulator.buildAggregated());
        assertTrue(accumulator.buildAllTextBlocks().isEmpty());
    }

    private TextBlock signedBlock(String text, byte[] signature) {
        return TextBlock.builder()
                .text(text)
                .metadata(Map.of(ContentBlockMetadataKeys.THOUGHT_SIGNATURE, signature))
                .build();
    }

    private byte[] signatureOf(TextBlock block) {
        return (byte[]) block.getMetadata().get(ContentBlockMetadataKeys.THOUGHT_SIGNATURE);
    }
}

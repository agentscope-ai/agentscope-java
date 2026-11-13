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
package io.agentscope.harness.agent.memory.compaction;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;

import io.agentscope.core.message.ContentBlock;
import io.agentscope.core.message.TextBlock;
import java.util.List;
import java.util.stream.Collectors;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class ToolResultPreviewTest {

    private static final String MARKER = "\n...\n";

    @Test
    @DisplayName("Head/tail preview keeps plain text byte-for-byte as before")
    void previewKeepsPlainTextUnchanged() {
        assertEquals(
                "abc" + MARKER + "fgh",
                preview("abcdefgh", 3),
                "a boundary that does not touch a surrogate pair must keep the current preview");
    }

    @Test
    @DisplayName("Head/tail preview must not split a surrogate pair")
    void previewDoesNotSplitSurrogatePairs() {
        // Four code points, eight UTF-16 chars. A three-char allowance ends the head between
        // the two halves of the second emoji and starts the tail on the low half of the third.
        String text = "\uD83D\uDE00\uD83D\uDE00\uD83D\uDE00\uD83D\uDE00";
        String preview = preview(text, 3);

        assertFalse(
                hasUnpairedSurrogate(preview),
                "preview must not contain lone surrogates: "
                        + preview.codePoints()
                                .mapToObj(c -> String.format("U+%04X", c))
                                .collect(Collectors.joining(" ")));
        assertEquals(
                "\uD83D\uDE00" + MARKER + "\uD83D\uDE00",
                preview,
                "whole code points must be kept at both ends");
    }

    @Test
    @DisplayName("A tail boundary that starts on a high surrogate keeps the whole pair")
    void previewKeepsTrailingPairWhenTailStartsOnHighSurrogate() {
        // Five chars, three code points: the tail starts exactly on the emoji, so it is kept.
        assertEquals("ab" + MARKER + "\uD83D\uDE00", preview("abc\uD83D\uDE00", 2));
    }

    @Test
    @DisplayName("An allowance that covers the whole text returns the original blocks")
    void previewReturnsOriginalBlocksWhenNothingIsRemoved() {
        List<ContentBlock> blocks = List.of(TextBlock.builder().text("\uD83D\uDE00").build());
        assertSame(blocks, ToolResultPreview.create(blocks, 1, "", MARKER));
    }

    private static String preview(String text, int charsPerEnd) {
        List<ContentBlock> blocks = List.of(TextBlock.builder().text(text).build());
        return ToolResultPreview.create(blocks, charsPerEnd, "", MARKER).stream()
                .filter(TextBlock.class::isInstance)
                .map(TextBlock.class::cast)
                .map(TextBlock::getText)
                .collect(Collectors.joining());
    }

    private static boolean hasUnpairedSurrogate(String value) {
        for (int i = 0; i < value.length(); i++) {
            char current = value.charAt(i);
            if (Character.isHighSurrogate(current)) {
                if (i + 1 >= value.length() || !Character.isLowSurrogate(value.charAt(i + 1))) {
                    return true;
                }
                i++;
            } else if (Character.isLowSurrogate(current)) {
                return true;
            }
        }
        return false;
    }
}

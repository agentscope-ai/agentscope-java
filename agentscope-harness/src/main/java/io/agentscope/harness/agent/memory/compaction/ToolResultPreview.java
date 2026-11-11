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

import io.agentscope.core.message.ContentBlock;
import io.agentscope.core.message.TextBlock;
import java.util.ArrayList;
import java.util.List;

/** Shared text preview operations for Harness tool-result pruning and offloading. */
public final class ToolResultPreview {

    private ToolResultPreview() {}

    /** Counts original text characters, including whitespace, without joining text blocks. */
    public static int textLength(List<ContentBlock> blocks) {
        return blocks.stream()
                .filter(TextBlock.class::isInstance)
                .map(TextBlock.class::cast)
                .mapToInt(text -> text.getText().length())
                .sum();
    }

    /**
     * Retains a shared head/tail text allowance while preserving non-text block order.
     *
     * @param blocks original content blocks
     * @param charsPerEnd non-negative character allowance at each end of the complete text
     * @param prefix text prepended to the first text block
     * @param marker text replacing the removed middle, inserted only when text is removed
     * @return preview blocks, or the original blocks when no replacement is needed
     */
    public static List<ContentBlock> create(
            List<ContentBlock> blocks, int charsPerEnd, String prefix, String marker) {
        int totalChars = textLength(blocks);
        int headEnd = Math.min(charsPerEnd, totalChars);
        int tailStart = Math.max(headEnd, totalChars - charsPerEnd);
        if (headEnd == tailStart && prefix.isEmpty()) {
            return blocks;
        }
        List<ContentBlock> output = new ArrayList<>();
        int consumed = 0;
        String remainingPrefix = prefix;
        boolean markerInserted = false;
        for (ContentBlock block : blocks) {
            if (!(block instanceof TextBlock textBlock)) {
                output.add(block);
                continue;
            }
            String text = textBlock.getText();
            int headLength = Math.min(text.length(), Math.max(0, headEnd - consumed));
            int tailOffset = Math.min(text.length(), Math.max(0, tailStart - consumed));
            String gap = "";
            if (!markerInserted
                    && headEnd < tailStart
                    && consumed < tailStart
                    && consumed + text.length() > headEnd) {
                gap = marker;
                markerInserted = true;
            }
            String preview =
                    remainingPrefix
                            + text.substring(0, headLength)
                            + gap
                            + text.substring(tailOffset);
            if (!preview.isEmpty()) {
                output.add(TextBlock.builder().text(preview).build());
            }
            remainingPrefix = "";
            consumed += text.length();
        }
        return output;
    }
}

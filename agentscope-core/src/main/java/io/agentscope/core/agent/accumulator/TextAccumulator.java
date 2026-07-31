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

import io.agentscope.core.message.Citation;
import io.agentscope.core.message.ContentBlock;
import io.agentscope.core.message.TextBlock;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Text content accumulator for accumulating streaming text chunks.
 *
 * <p>This accumulator concatenates all text chunks in order to build the complete text content.
 * @hidden
 */
public class TextAccumulator implements ContentAccumulator<TextBlock> {

    private static final Long DEFAULT_PROVIDER_BLOCK_INDEX = -1L;

    private final StringBuilder accumulated = new StringBuilder();
    private final Map<Long, TextSegment> segments = new LinkedHashMap<>();

    /**
     * @hidden
     */
    @Override
    public void add(TextBlock block) {
        if (block == null) {
            return;
        }

        String text = block.getText() != null ? block.getText() : "";
        accumulated.append(text);

        Long providerBlockIndex =
                block.getProviderBlockIndex() != null
                        ? block.getProviderBlockIndex()
                        : DEFAULT_PROVIDER_BLOCK_INDEX;

        TextSegment segment =
                segments.computeIfAbsent(providerBlockIndex, ignored -> new TextSegment());
        segment.text.append(text);
        segment.citations.addAll(block.getCitations());
    }

    /**
     * @hidden
     */
    @Override
    public boolean hasContent() {
        return accumulated.length() > 0 || segments.values().stream().anyMatch(TextSegment::cited);
    }

    /**
     * @hidden
     */
    @Override
    public ContentBlock buildAggregated() {
        if (!hasContent()) {
            return null;
        }

        List<Citation> citations =
                segments.values().stream().flatMap(segment -> segment.citations.stream()).toList();
        return TextBlock.builder().text(accumulated.toString()).citations(citations).build();
    }

    /**
     * Builds text blocks while preserving provider block boundaries when citations are present.
     *
     * <p>Uncited text keeps the historical behavior of producing one aggregated block. When any
     * segment is cited, every provider segment is returned in original order so each citation
     * remains attached to the exact claim it supports.
     *
     * @hidden
     * @return aggregated text blocks, or an empty list when no text or citations were received
     */
    public List<TextBlock> buildAggregatedBlocks() {
        if (!hasContent()) {
            return List.of();
        }

        boolean hasCitations = segments.values().stream().anyMatch(TextSegment::cited);
        if (!hasCitations) {
            return List.of(TextBlock.builder().text(accumulated.toString()).build());
        }

        List<TextBlock> blocks = new ArrayList<>();
        for (TextSegment segment : segments.values()) {
            if (segment.text.length() == 0 && segment.citations.isEmpty()) {
                continue;
            }
            blocks.add(
                    TextBlock.builder()
                            .text(segment.text.toString())
                            .citations(segment.citations)
                            .build());
        }
        return blocks;
    }

    /**
     * @hidden
     */
    @Override
    public void reset() {
        accumulated.setLength(0);
        segments.clear();
    }

    /**
     * Get the accumulated text content.
     *
     * @hidden
     * @return accumulated text as string
     */
    public String getAccumulated() {
        return accumulated.toString();
    }

    private static final class TextSegment {

        private final StringBuilder text = new StringBuilder();
        private final List<Citation> citations = new ArrayList<>();

        private boolean cited() {
            return !citations.isEmpty();
        }
    }
}

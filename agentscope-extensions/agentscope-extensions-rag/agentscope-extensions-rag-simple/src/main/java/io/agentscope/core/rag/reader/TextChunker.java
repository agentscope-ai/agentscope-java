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
package io.agentscope.core.rag.reader;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;

/**
 * Utility class for chunking text into smaller pieces.
 *
 * <p>This class provides methods to split text according to different strategies,
 * with support for overlap between chunks to preserve context.
 */
public final class TextChunker {

    private static final int CHARS_PER_TOKEN = 4; // Approximate: 1 token ≈ 4 characters
    private static final Pattern PARAGRAPH_SEPARATOR = Pattern.compile("\\n\\s*\\n");

    private TextChunker() {
        // Utility class, prevent instantiation
    }

    /**
     * Chunks text according to the specified strategy.
     *
     * @param text the text to chunk
     * @param chunkSize the target size for each chunk (interpreted based on strategy)
     * @param strategy the splitting strategy
     * @param overlapSize the number of characters/tokens to overlap between chunks
     * @return a list of text chunks
     */
    public static List<String> chunkText(
            String text, int chunkSize, SplitStrategy strategy, int overlapSize) {
        if (text == null) {
            throw new IllegalArgumentException("Text cannot be null");
        }
        if (chunkSize <= 0) {
            throw new IllegalArgumentException("Chunk size must be positive");
        }
        if (overlapSize < 0) {
            throw new IllegalArgumentException("Overlap size cannot be negative");
        }
        if (overlapSize >= chunkSize) {
            throw new IllegalArgumentException("Overlap size must be less than chunk size");
        }

        if (text.isEmpty()) {
            return List.of("");
        }

        return switch (strategy) {
            case CHARACTER -> chunkByCharacter(text, chunkSize, overlapSize);
            case PARAGRAPH -> chunkByParagraph(text, chunkSize, overlapSize);
            case TOKEN -> chunkByToken(text, chunkSize, overlapSize);
            case SEMANTIC -> chunkBySemantic(text, chunkSize, overlapSize);
        };
    }

    /**
     * Chunks text by character count.
     *
     * @param text the text to chunk
     * @param chunkSize the number of characters per chunk
     * @param overlapSize the number of characters to overlap
     * @return a list of text chunks
     */
    private static List<String> chunkByCharacter(String text, int chunkSize, int overlapSize) {
        List<String> chunks = new ArrayList<>();
        int start = 0;

        while (start < text.length()) {
            int end = Math.min(start + chunkSize, text.length());
            chunks.add(text.substring(start, end));

            if (end >= text.length()) {
                break;
            }

            // Move start position forward, accounting for overlap
            start = end - overlapSize;
            if (start < 0) {
                start = 0;
            }
        }

        return chunks;
    }

    /**
     * Chunks text by paragraphs, respecting chunk size limits.
     *
     * @param text the text to chunk
     * @param chunkSize the maximum number of characters per chunk
     * @param overlapSize the number of characters to overlap
     * @return a list of text chunks
     */
    private static List<String> chunkByParagraph(String text, int chunkSize, int overlapSize) {
        List<String> chunks = new ArrayList<>();
        String[] paragraphs = PARAGRAPH_SEPARATOR.split(text);

        StringBuilder currentChunk = new StringBuilder();
        for (String paragraph : paragraphs) {
            String trimmedParagraph = paragraph.trim();
            if (trimmedParagraph.isEmpty()) {
                continue;
            }

            // If adding this paragraph would exceed chunk size, save current chunk
            if (currentChunk.length() > 0
                    && currentChunk.length() + trimmedParagraph.length() + 2 > chunkSize) {
                chunks.add(currentChunk.toString().trim());
                currentChunk = new StringBuilder();

                // Add overlap from previous chunk
                if (!chunks.isEmpty() && overlapSize > 0) {
                    String lastChunk = chunks.get(chunks.size() - 1);
                    int overlapStart = Math.max(0, lastChunk.length() - overlapSize);
                    currentChunk.append(lastChunk.substring(overlapStart));
                }
            }

            if (currentChunk.length() > 0) {
                currentChunk.append("\n\n");
            }
            currentChunk.append(trimmedParagraph);

            // If a single paragraph exceeds chunk size, split it by character
            if (currentChunk.length() > chunkSize) {
                String largeParagraph = currentChunk.toString();
                currentChunk = new StringBuilder();
                List<String> subChunks = chunkByCharacter(largeParagraph, chunkSize, overlapSize);
                chunks.addAll(subChunks.subList(0, subChunks.size() - 1));
                if (!subChunks.isEmpty()) {
                    currentChunk.append(subChunks.get(subChunks.size() - 1));
                }
            }
        }

        if (currentChunk.length() > 0) {
            chunks.add(currentChunk.toString().trim());
        }

        return chunks.isEmpty() ? List.of(text) : chunks;
    }

    /**
     * Chunks text by approximate token count.
     *
     * <p>Uses a simple heuristic: 1 token ≈ 4 characters.
     *
     * @param text the text to chunk
     * @param chunkSize the target number of tokens per chunk
     * @param overlapSize the number of tokens to overlap
     * @return a list of text chunks
     */
    private static List<String> chunkByToken(String text, int chunkSize, int overlapSize) {
        // Convert token sizes to character sizes
        int charChunkSize = chunkSize * CHARS_PER_TOKEN;
        int charOverlapSize = overlapSize * CHARS_PER_TOKEN;

        // Use character-based chunking with token-based sizes
        return chunkByCharacter(text, charChunkSize, charOverlapSize);
    }

    /**
     * Chunks text by semantic boundaries.
     *
     * <p>Currently falls back to paragraph-based chunking. A full implementation
     * would use NLP to identify sentence and clause boundaries.
     *
     * @param text the text to chunk
     * @param chunkSize the target size for each chunk
     * @param overlapSize the number of characters to overlap
     * @return a list of text chunks
     */
    private static List<String> chunkBySemantic(String text, int chunkSize, int overlapSize) {
        // For now, fall back to paragraph-based chunking
        // A full implementation would use sentence segmentation
        return chunkByParagraph(text, chunkSize, overlapSize);
    }
}

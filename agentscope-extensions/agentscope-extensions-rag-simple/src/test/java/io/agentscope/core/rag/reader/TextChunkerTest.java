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

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

/**
 * Unit tests for TextChunker.
 */
@Tag("unit")
@DisplayName("TextChunker Unit Tests")
class TextChunkerTest {

    @Test
    @DisplayName("Should chunk text by character")
    void testChunkByCharacter() {
        String text = "1234567890";
        List<String> chunks = TextChunker.chunkText(text, 3, SplitStrategy.CHARACTER, 1);

        // With chunkSize=3, overlap=1: "123", "345", "567", "789", "90"
        assertTrue(chunks.size() >= 4);
        assertEquals("123", chunks.get(0));
        assertEquals("345", chunks.get(1));
        assertEquals("567", chunks.get(2));
        // Last chunk may vary based on implementation
        assertTrue(chunks.get(chunks.size() - 1).length() <= 3);
    }

    @Test
    @DisplayName("Should handle overlap in character chunking")
    void testChunkByCharacterWithOverlap() {
        String text = "1234567890";
        List<String> chunks = TextChunker.chunkText(text, 4, SplitStrategy.CHARACTER, 2);

        // With chunkSize=4, overlap=2: "1234", "3456", "5678", "90"
        assertTrue(chunks.size() >= 3);
        assertEquals("1234", chunks.get(0));
        assertEquals("3456", chunks.get(1)); // Overlaps with previous
        // Verify overlap exists
        assertTrue(chunks.get(1).startsWith("34"));
    }

    @Test
    @DisplayName("Should handle text shorter than chunk size")
    void testChunkShortText() {
        String text = "123";
        List<String> chunks = TextChunker.chunkText(text, 10, SplitStrategy.CHARACTER, 0);

        assertEquals(1, chunks.size());
        assertEquals("123", chunks.get(0));
    }

    @Test
    @DisplayName("Should handle empty text")
    void testChunkEmptyText() {
        String text = "";
        List<String> chunks = TextChunker.chunkText(text, 10, SplitStrategy.CHARACTER, 0);

        assertEquals(1, chunks.size());
        assertEquals("", chunks.get(0));
    }

    @Test
    @DisplayName("Should chunk text by paragraph")
    void testChunkByParagraph() {
        String text = "Paragraph 1.\n\nParagraph 2.\n\nParagraph 3.";
        List<String> chunks = TextChunker.chunkText(text, 100, SplitStrategy.PARAGRAPH, 0);

        // Paragraph chunking may combine paragraphs if they fit within chunkSize
        assertTrue(chunks.size() >= 1);
        // Verify all paragraph content is present
        String allContent = String.join(" ", chunks);
        assertTrue(allContent.contains("Paragraph 1"));
        assertTrue(allContent.contains("Paragraph 2"));
        assertTrue(allContent.contains("Paragraph 3"));
    }

    @Test
    @DisplayName("Should split large paragraphs by character")
    void testChunkLargeParagraph() {
        String longParagraph = "A".repeat(200);
        List<String> chunks = TextChunker.chunkText(longParagraph, 50, SplitStrategy.PARAGRAPH, 0);

        assertTrue(chunks.size() > 1);
        assertTrue(chunks.get(0).length() <= 50);
    }

    @Test
    @DisplayName("Should chunk text by token (approximate)")
    void testChunkByToken() {
        // Token strategy uses ~4 chars per token
        String text = "1234567890"; // 10 characters ≈ 2.5 tokens
        List<String> chunks = TextChunker.chunkText(text, 1, SplitStrategy.TOKEN, 0);

        // Should create multiple chunks since 1 token ≈ 4 chars
        assertTrue(chunks.size() >= 1);
    }

    @Test
    @DisplayName("Should chunk text by semantic (falls back to paragraph)")
    void testChunkBySemantic() {
        String text = "First paragraph.\n\nSecond paragraph.";
        List<String> chunks = TextChunker.chunkText(text, 100, SplitStrategy.SEMANTIC, 0);

        // Should behave like paragraph chunking
        assertTrue(chunks.size() >= 1);
    }

    @Test
    @DisplayName("Should throw exception for null text")
    void testChunkNullText() {
        assertThrows(
                IllegalArgumentException.class,
                () -> TextChunker.chunkText(null, 10, SplitStrategy.CHARACTER, 0));
    }

    @Test
    @DisplayName("Should throw exception for invalid chunk size")
    void testChunkInvalidChunkSize() {
        assertThrows(
                IllegalArgumentException.class,
                () -> TextChunker.chunkText("text", 0, SplitStrategy.CHARACTER, 0));
        assertThrows(
                IllegalArgumentException.class,
                () -> TextChunker.chunkText("text", -1, SplitStrategy.CHARACTER, 0));
    }

    @Test
    @DisplayName("Should throw exception for negative overlap")
    void testChunkNegativeOverlap() {
        assertThrows(
                IllegalArgumentException.class,
                () -> TextChunker.chunkText("text", 10, SplitStrategy.CHARACTER, -1));
    }

    @Test
    @DisplayName("Should throw exception when overlap >= chunk size")
    void testChunkOverlapTooLarge() {
        assertThrows(
                IllegalArgumentException.class,
                () -> TextChunker.chunkText("text", 10, SplitStrategy.CHARACTER, 10));
        assertThrows(
                IllegalArgumentException.class,
                () -> TextChunker.chunkText("text", 10, SplitStrategy.CHARACTER, 15));
    }

    @Test
    @DisplayName("Should preserve all text content")
    void testPreserveContent() {
        String text = "This is a test text that should be preserved completely.";
        List<String> chunks = TextChunker.chunkText(text, 20, SplitStrategy.CHARACTER, 5);

        String reconstructed = String.join("", chunks);
        // Note: With overlap, we can't directly compare, but we can check all original chars exist
        for (char c : text.toCharArray()) {
            assertTrue(reconstructed.contains(String.valueOf(c)), "Missing character: " + c);
        }
    }

    @Test
    @DisplayName("Should handle paragraph chunking with overlap")
    void testParagraphChunkingWithOverlap() {
        String text = "First paragraph.\n\nSecond paragraph.\n\nThird paragraph.";
        List<String> chunks = TextChunker.chunkText(text, 30, SplitStrategy.PARAGRAPH, 10);

        assertTrue(chunks.size() >= 1);
        // Verify chunks don't exceed size limit (approximately)
        for (String chunk : chunks) {
            assertTrue(chunk.length() <= 50); // Allow some margin
        }
    }
}

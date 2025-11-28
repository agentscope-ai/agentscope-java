/*
 * Copyright 2024-2025 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * You may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      https://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package io.agentscope.core.rag.model;

import io.agentscope.core.message.ContentBlock;
import io.agentscope.core.message.TextBlock;

/**
 * Document metadata containing content and chunking information.
 *
 * <p>This class stores metadata about a document chunk, including the content
 * (which can be text, image, video, etc.), document ID, chunk ID, and total
 * number of chunks.
 *
 * <p>The content field uses {@link ContentBlock} which is a sealed hierarchy
 * supporting different content types (TextBlock, ImageBlock, VideoBlock, etc.).
 */
public class DocumentMetadata {

    private final ContentBlock content;
    private final String docId;
    private final int chunkId;
    private final int totalChunks;

    /**
     * Creates a new DocumentMetadata instance.
     *
     * @param content the content block (text, image, video, etc.)
     * @param docId the document ID
     * @param chunkId the chunk ID within the document
     * @param totalChunks the total number of chunks in the document
     */
    public DocumentMetadata(ContentBlock content, String docId, int chunkId, int totalChunks) {
        if (content == null) {
            throw new IllegalArgumentException("Content cannot be null");
        }
        if (docId == null) {
            throw new IllegalArgumentException("Document ID cannot be null");
        }
        if (chunkId < 0) {
            throw new IllegalArgumentException("Chunk ID must be non-negative");
        }
        if (totalChunks <= 0) {
            throw new IllegalArgumentException("Total chunks must be positive");
        }
        if (chunkId >= totalChunks) {
            throw new IllegalArgumentException("Chunk ID must be less than total chunks");
        }
        this.content = content;
        this.docId = docId;
        this.chunkId = chunkId;
        this.totalChunks = totalChunks;
    }

    /**
     * Gets the content block.
     *
     * @return the content block
     */
    public ContentBlock getContent() {
        return content;
    }

    /**
     * Gets the document ID.
     *
     * @return the document ID
     */
    public String getDocId() {
        return docId;
    }

    /**
     * Gets the chunk ID.
     *
     * @return the chunk ID
     */
    public int getChunkId() {
        return chunkId;
    }

    /**
     * Gets the total number of chunks.
     *
     * @return the total number of chunks
     */
    public int getTotalChunks() {
        return totalChunks;
    }

    /**
     * Gets the text content from the content block.
     *
     * <p>This is a convenience method that extracts text from the ContentBlock.
     * For TextBlock, it returns the text. For other block types, it returns their
     * string representation.
     *
     * @return the text content, or empty string if not available
     */
    public String getContentText() {
        if (content instanceof TextBlock textBlock) {
            return textBlock.getText();
        }
        return content != null ? content.toString() : "";
    }
}

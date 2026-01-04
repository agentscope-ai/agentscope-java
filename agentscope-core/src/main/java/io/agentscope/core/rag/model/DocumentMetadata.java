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
package io.agentscope.core.rag.model;

import io.agentscope.core.message.ContentBlock;
import io.agentscope.core.message.TextBlock;

/**
 * Document metadata containing content and chunking information.
 *
 * <p>This class stores metadata about a document chunk, including the content
 * (which can be text, image, video, etc.), document ID, and chunk ID.
 *
 * <p>The content field uses {@link ContentBlock} which is a sealed hierarchy
 * supporting different content types (TextBlock, ImageBlock, VideoBlock, etc.).
 */
public class DocumentMetadata {

    private final ContentBlock content;
    private final String docId;
    private final String chunkId;

    /**
     * Creates a new DocumentMetadata instance.
     *
     * @param content the content block (text, image, video, etc.)
     * @param docId the document ID
     * @param chunkId the chunk ID within the document
     */
    public DocumentMetadata(ContentBlock content, String docId, String chunkId) {
        if (content == null) {
            throw new IllegalArgumentException("Content cannot be null");
        }
        if (docId == null) {
            throw new IllegalArgumentException("Document ID cannot be null");
        }
        if (chunkId == null) {
            throw new IllegalArgumentException("Chunk ID cannot be null");
        }
        this.content = content;
        this.docId = docId;
        this.chunkId = chunkId;
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
    public String getChunkId() {
        return chunkId;
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

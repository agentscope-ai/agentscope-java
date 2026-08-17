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

/**
 * Abstract base class for readers that support text chunking.
 *
 * <p>This class provides common functionality for document readers that split content
 * into chunks based on a chunking strategy. It handles parameter validation and provides
 * shared access to chunk size, split strategy, and overlap size settings.
 *
 * <p>Subclasses should implement the {@link Reader} interface to provide specific
 * reading logic for different file formats.
 */
public abstract class AbstractChunkingReader implements Reader {

    protected final int chunkSize;
    protected final SplitStrategy splitStrategy;
    protected final int overlapSize;

    /**
     * Creates a new AbstractChunkingReader with the specified configuration.
     *
     * @param chunkSize the target size for each chunk (interpreted based on strategy)
     * @param splitStrategy the strategy for splitting text
     * @param overlapSize the number of characters/tokens to overlap between chunks
     * @throws IllegalArgumentException if parameters are invalid
     */
    protected AbstractChunkingReader(int chunkSize, SplitStrategy splitStrategy, int overlapSize) {
        validateChunkingParameters(chunkSize, splitStrategy, overlapSize);
        this.chunkSize = chunkSize;
        this.splitStrategy = splitStrategy;
        this.overlapSize = overlapSize;
    }

    /**
     * Validates chunking parameters.
     *
     * @param chunkSize the chunk size to validate
     * @param splitStrategy the split strategy to validate
     * @param overlapSize the overlap size to validate
     * @throws IllegalArgumentException if any parameter is invalid
     */
    protected static void validateChunkingParameters(
            int chunkSize, SplitStrategy splitStrategy, int overlapSize) {
        if (chunkSize <= 0) {
            throw new IllegalArgumentException("Chunk size must be positive");
        }
        if (splitStrategy == null) {
            throw new IllegalArgumentException("Split strategy cannot be null");
        }
        if (overlapSize < 0) {
            throw new IllegalArgumentException("Overlap size cannot be negative");
        }
        if (overlapSize >= chunkSize) {
            throw new IllegalArgumentException("Overlap size must be less than chunk size");
        }
    }

    /**
     * Gets the chunk size.
     *
     * @return the chunk size
     */
    public int getChunkSize() {
        return chunkSize;
    }

    /**
     * Gets the split strategy.
     *
     * @return the split strategy
     */
    public SplitStrategy getSplitStrategy() {
        return splitStrategy;
    }

    /**
     * Gets the overlap size.
     *
     * @return the overlap size
     */
    public int getOverlapSize() {
        return overlapSize;
    }
}

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

import io.agentscope.core.message.ContentBlock;

/**
 * Content accumulator interface for accumulating streaming content blocks.
 *
 * <p>This interface defines the contract for accumulating content blocks from streaming responses.
 * Different content types (text, thinking, tool calls) have different accumulation strategies.
 *
 * @hidden
 * @param <T> The type of content block to accumulate
 */
public interface ContentAccumulator<T extends ContentBlock> {

    /**
     * Add a content block chunk to the accumulator.
     *
     * @hidden
     * @param block The content block chunk to add
     */
    void add(T block);

    /**
     * Check if the accumulator has any accumulated content.
     *
     * @hidden
     * @return true if there is accumulated content, false otherwise
     */
    boolean hasContent();

    /**
     * Build the aggregated content block from all accumulated chunks.
     *
     * @hidden
     * @return The aggregated content block, or null if no content
     */
    ContentBlock buildAggregated();

    /**
     * @hidden
     * Reset the accumulator state, clearing all accumulated content.
     */
    void reset();
}

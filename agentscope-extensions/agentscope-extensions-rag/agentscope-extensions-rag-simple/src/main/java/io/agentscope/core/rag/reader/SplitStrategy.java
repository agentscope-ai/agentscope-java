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
 * Strategy for splitting text into chunks.
 *
 * <p>Different strategies are suitable for different types of content:
 * <ul>
 *   <li>CHARACTER: Split by character count (simple, fast, may break words)
 *   <li>PARAGRAPH: Split by paragraphs (preserves semantic units)
 *   <li>TOKEN: Split by token count (approximates LLM token limits)
 *   <li>SEMANTIC: Split by semantic boundaries (requires NLP, not yet implemented)
 * </ul>
 */
public enum SplitStrategy {
    /**
     * Split text by character count.
     *
     * <p>This is the simplest strategy - it splits text into fixed-size chunks
     * based on character count. May break words in the middle.
     */
    CHARACTER,

    /**
     * Split text by paragraphs.
     *
     * <p>This strategy splits text at paragraph boundaries (double newlines),
     * preserving semantic units. Chunks may vary in size.
     */
    PARAGRAPH,

    /**
     * Split text by token count (approximate).
     *
     * <p>This strategy attempts to split text based on approximate token count,
     * which is useful for LLM context limits. Uses a simple heuristic:
     * ~4 characters per token.
     */
    TOKEN,

    /**
     * Split text by semantic boundaries.
     *
     * <p>This strategy would split text at semantic boundaries (sentences,
     * clauses, etc.) to preserve meaning. Currently not implemented and
     * falls back to PARAGRAPH strategy.
     */
    SEMANTIC
}

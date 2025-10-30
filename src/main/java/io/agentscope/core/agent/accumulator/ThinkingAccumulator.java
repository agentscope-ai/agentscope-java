/*
 * Copyright 2024-2025 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
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
package io.agentscope.core.agent.accumulator;

import io.agentscope.core.message.ContentBlock;
import io.agentscope.core.message.ThinkingBlock;

/**
 * Thinking content accumulator for accumulating streaming thinking chunks.
 *
 * <p>This accumulator concatenates all thinking chunks in order to build the complete thinking
 * content.
 */
public class ThinkingAccumulator implements ContentAccumulator<ThinkingBlock> {

    private final StringBuilder accumulated = new StringBuilder();

    @Override
    public void add(ThinkingBlock block) {
        if (block != null && block.getThinking() != null) {
            accumulated.append(block.getThinking());
        }
    }

    @Override
    public boolean hasContent() {
        return accumulated.length() > 0;
    }

    @Override
    public ContentBlock buildAggregated() {
        if (!hasContent()) {
            return null;
        }
        return ThinkingBlock.builder().text(accumulated.toString()).build();
    }

    @Override
    public void reset() {
        accumulated.setLength(0);
    }

    /**
     * Get the accumulated thinking content.
     *
     * @return accumulated thinking as string
     */
    public String getAccumulated() {
        return accumulated.toString();
    }
}

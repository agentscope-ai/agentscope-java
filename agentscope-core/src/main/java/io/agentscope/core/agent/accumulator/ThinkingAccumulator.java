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
import io.agentscope.core.message.ThinkingBlock;
import java.util.HashMap;
import java.util.Map;

/**
 * Thinking content accumulator for accumulating streaming thinking chunks.
 *
 * <p>This accumulator concatenates all thinking chunks in order to build the complete thinking
 * content.
 * @hidden
 */
public class ThinkingAccumulator implements ContentAccumulator<ThinkingBlock> {

    private final StringBuilder accumulated = new StringBuilder();
    private final Map<String, Object> metadata = new HashMap<>();

    /**
     * @hidden
     */
    @Override
    public void add(ThinkingBlock block) {
        if (block == null) {
            return;
        }
        if (block.getThinking() != null) {
            accumulated.append(block.getThinking());
        }
        if (block.getMetadata() != null && !block.getMetadata().isEmpty()) {
            metadata.putAll(block.getMetadata());
        }
    }

    /**
     * @hidden
     */
    @Override
    public boolean hasContent() {
        return accumulated.length() > 0 || !metadata.isEmpty();
    }

    /**
     * @hidden
     */
    @Override
    public ContentBlock buildAggregated() {
        if (!hasContent()) {
            return null;
        }
        return ThinkingBlock.builder()
                .thinking(accumulated.toString())
                .metadata(metadata.isEmpty() ? null : metadata)
                .build();
    }

    /**
     * @hidden
     */
    @Override
    public void reset() {
        accumulated.setLength(0);
        metadata.clear();
    }

    /**
     * Get the accumulated thinking content.
     *
     * @hidden
     * @return accumulated thinking as string
     */
    public String getAccumulated() {
        return accumulated.toString();
    }
}

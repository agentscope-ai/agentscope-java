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
package io.agentscope.extensions.model.anthropic.formatter;

import com.anthropic.models.messages.ContentBlockParam;
import com.anthropic.models.messages.RedactedThinkingBlockParam;
import com.anthropic.models.messages.ThinkingBlockParam;
import io.agentscope.core.message.ThinkingBlock;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;

/** Stores Anthropic thinking blocks in provider-specific {@link ThinkingBlock} metadata. */
final class AnthropicThinkingMetadata {

    private static final String KEY_PREFIX = "anthropicThinkingBlock:";

    private AnthropicThinkingMetadata() {}

    static Map<String, Object> thinking(long index, String thinking, String signature) {
        return Map.of(
                key(index),
                Map.of(
                        "type",
                        "thinking",
                        "thinking",
                        thinking != null ? thinking : "",
                        "signature",
                        signature));
    }

    static Map<String, Object> redactedThinking(long index, String data) {
        return Map.of(key(index), Map.of("type", "redacted_thinking", "data", data));
    }

    static List<ContentBlockParam> toContentBlockParams(ThinkingBlock block) {
        if (block == null || block.getMetadata() == null || block.getMetadata().isEmpty()) {
            return List.of();
        }

        List<Map.Entry<Long, Object>> storedBlocks = new ArrayList<>();
        for (Map.Entry<String, Object> entry : block.getMetadata().entrySet()) {
            String key = entry.getKey();
            if (key == null || !key.startsWith(KEY_PREFIX)) {
                continue;
            }

            try {
                long index = Long.parseLong(key.substring(KEY_PREFIX.length()));
                if (entry.getValue() == null) {
                    return List.of();
                }
                storedBlocks.add(Map.entry(index, entry.getValue()));
            } catch (NumberFormatException e) {
                return List.of();
            }
        }
        storedBlocks.sort(Comparator.comparingLong(Map.Entry::getKey));

        List<ContentBlockParam> result = new ArrayList<>(storedBlocks.size());
        for (Map.Entry<Long, Object> storedBlock : storedBlocks) {
            ContentBlockParam converted = convert(storedBlock.getValue());
            if (converted == null) {
                return List.of();
            }
            result.add(converted);
        }
        return result;
    }

    private static ContentBlockParam convert(Object value) {
        if (!(value instanceof Map<?, ?> map)) {
            return null;
        }

        if ("thinking".equals(map.get("type"))) {
            if (!(map.get("thinking") instanceof String thinking)
                    || !(map.get("signature") instanceof String signature)) {
                return null;
            }
            return ContentBlockParam.ofThinking(
                    ThinkingBlockParam.builder().thinking(thinking).signature(signature).build());
        }

        if ("redacted_thinking".equals(map.get("type"))) {
            if (!(map.get("data") instanceof String data)) {
                return null;
            }
            return ContentBlockParam.ofRedactedThinking(
                    RedactedThinkingBlockParam.builder().data(data).build());
        }

        return null;
    }

    private static String key(long index) {
        return KEY_PREFIX + index;
    }
}

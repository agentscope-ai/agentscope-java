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
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/** Stores Anthropic thinking blocks in provider-specific {@link ThinkingBlock} metadata. */
final class AnthropicThinkingMetadata {

    private static final String KEY_PREFIX = "anthropicThinkingBlock:";
    private static final String TYPE = "type";
    private static final String THINKING = "thinking";
    private static final String SIGNATURE = "signature";
    private static final String DATA = "data";
    private static final String TYPE_THINKING = "thinking";
    private static final String TYPE_REDACTED_THINKING = "redacted_thinking";

    private AnthropicThinkingMetadata() {}

    static Map<String, Object> thinking(long index, String thinking, String signature) {
        Map<String, Object> value = new LinkedHashMap<>();
        value.put(TYPE, TYPE_THINKING);
        value.put(THINKING, thinking != null ? thinking : "");
        value.put(SIGNATURE, signature);
        return Map.of(key(index), value);
    }

    static Map<String, Object> redactedThinking(long index, String data) {
        Map<String, Object> value = new LinkedHashMap<>();
        value.put(TYPE, TYPE_REDACTED_THINKING);
        value.put(DATA, data);
        return Map.of(key(index), value);
    }

    static List<ContentBlockParam> toContentBlockParams(ThinkingBlock block) {
        if (block == null || block.getMetadata() == null || block.getMetadata().isEmpty()) {
            return List.of();
        }

        List<Map.Entry<String, Object>> metadataEntries =
                block.getMetadata().entrySet().stream()
                        .filter(
                                entry ->
                                        entry.getKey() != null
                                                && entry.getKey().startsWith(KEY_PREFIX))
                        .toList();

        if (metadataEntries.isEmpty()) {
            return List.of();
        }

        List<Map.Entry<Long, Object>> storedBlocks = new ArrayList<>(metadataEntries.size());
        for (Map.Entry<String, Object> entry : metadataEntries) {
            long index = parseIndex(entry.getKey());
            if (entry.getValue() == null) {
                throw invalidMetadata(index);
            }
            storedBlocks.add(Map.entry(index, entry.getValue()));
        }
        storedBlocks.sort(Comparator.comparingLong(Map.Entry::getKey));

        List<ContentBlockParam> result = new ArrayList<>(storedBlocks.size());
        Long previousIndex = null;
        for (Map.Entry<Long, Object> storedBlock : storedBlocks) {
            long index = storedBlock.getKey();
            if (previousIndex != null && previousIndex == index) {
                throw invalidMetadata(index);
            }
            ContentBlockParam converted =
                    convert(storedBlock.getValue()).orElseThrow(() -> invalidMetadata(index));
            result.add(converted);
            previousIndex = index;
        }
        return result;
    }

    private static Optional<ContentBlockParam> convert(Object value) {
        if (!(value instanceof Map<?, ?> map)) {
            return Optional.empty();
        }

        String type = stringValue(map.get(TYPE));
        if (TYPE_THINKING.equals(type)) {
            String thinking = stringValue(map.get(THINKING));
            String signature = stringValue(map.get(SIGNATURE));
            if (thinking == null || signature == null || signature.isEmpty()) {
                return Optional.empty();
            }
            return Optional.of(
                    ContentBlockParam.ofThinking(
                            ThinkingBlockParam.builder()
                                    .thinking(thinking)
                                    .signature(signature)
                                    .build()));
        }

        if (TYPE_REDACTED_THINKING.equals(type)) {
            String data = stringValue(map.get(DATA));
            if (data == null || data.isEmpty()) {
                return Optional.empty();
            }
            return Optional.of(
                    ContentBlockParam.ofRedactedThinking(
                            RedactedThinkingBlockParam.builder().data(data).build()));
        }

        return Optional.empty();
    }

    private static String key(long index) {
        return KEY_PREFIX + index;
    }

    private static long parseIndex(String key) {
        try {
            long index = Long.parseLong(key.substring(KEY_PREFIX.length()));
            if (index < 0 || !key.equals(key(index))) {
                throw invalidMetadataKey(key);
            }
            return index;
        } catch (NumberFormatException e) {
            throw invalidMetadataKey(key);
        }
    }

    private static IllegalArgumentException invalidMetadata(long index) {
        return new IllegalArgumentException(
                "Invalid Anthropic thinking metadata at content index " + index);
    }

    private static IllegalArgumentException invalidMetadataKey(String key) {
        return new IllegalArgumentException("Invalid Anthropic thinking metadata key: " + key);
    }

    private static String stringValue(Object value) {
        return value instanceof String string ? string : null;
    }
}

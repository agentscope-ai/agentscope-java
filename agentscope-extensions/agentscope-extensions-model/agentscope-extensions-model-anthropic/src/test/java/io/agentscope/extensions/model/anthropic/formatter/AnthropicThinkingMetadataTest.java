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

import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.anthropic.models.messages.ContentBlockParam;
import io.agentscope.core.message.ThinkingBlock;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/** Unit tests for Anthropic thinking metadata validation and conversion. */
class AnthropicThinkingMetadataTest {

    private static final String KEY_PREFIX = "anthropicThinkingBlock:";

    @Test
    void testReturnsEmptyWithoutAnthropicMetadata() {
        assertAll(
                () -> assertTrue(AnthropicThinkingMetadata.toContentBlockParams(null).isEmpty()),
                () ->
                        assertTrue(
                                AnthropicThinkingMetadata.toContentBlockParams(
                                                ThinkingBlock.builder().build())
                                        .isEmpty()),
                () ->
                        assertTrue(
                                AnthropicThinkingMetadata.toContentBlockParams(
                                                block(Map.of("provider", "other")))
                                        .isEmpty()));
    }

    @Test
    void testConvertsNullThinkingToEmptyText() {
        ThinkingBlock block = block(AnthropicThinkingMetadata.thinking(0, null, "signature-123"));

        List<ContentBlockParam> result = AnthropicThinkingMetadata.toContentBlockParams(block);

        assertEquals(1, result.size());
        assertEquals("", result.get(0).asThinking().thinking());
        assertEquals("signature-123", result.get(0).asThinking().signature());
    }

    @Test
    void testRejectsMalformedMetadataKeys() {
        assertAll(
                () -> assertInvalid(Map.of(KEY_PREFIX + "invalid", Map.of())),
                () -> assertInvalid(Map.of(KEY_PREFIX + "-1", Map.of())),
                () -> assertInvalid(Map.of(KEY_PREFIX, Map.of())),
                () ->
                        assertInvalid(
                                Map.of(
                                        KEY_PREFIX + "1",
                                        validThinking("one"),
                                        KEY_PREFIX + "01",
                                        validThinking("duplicate"))));
    }

    @Test
    void testRejectsMalformedThinkingBlocks() {
        Map<String, Object> nullValue = new HashMap<>();
        nullValue.put(KEY_PREFIX + "0", null);

        assertAll(
                () -> assertInvalid(nullValue),
                () -> assertInvalid(Map.of(KEY_PREFIX + "0", "not-a-map")),
                () -> assertInvalid(storedBlock(Map.of("type", "unknown"))),
                () ->
                        assertInvalid(
                                storedBlock(
                                        Map.of(
                                                "type", "thinking",
                                                "signature", "signature-123"))),
                () ->
                        assertInvalid(
                                storedBlock(
                                        Map.of(
                                                "type", "thinking",
                                                "thinking", "reasoning"))),
                () ->
                        assertInvalid(
                                storedBlock(
                                        Map.of(
                                                "type", "thinking",
                                                "thinking", "reasoning",
                                                "signature", ""))),
                () ->
                        assertInvalid(
                                storedBlock(
                                        Map.of(
                                                "type", "thinking",
                                                "thinking", 1,
                                                "signature", "signature-123"))));
    }

    @Test
    void testRejectsMalformedRedactedThinkingBlocks() {
        assertAll(
                () -> assertInvalid(storedBlock(Map.of("type", "redacted_thinking"))),
                () ->
                        assertInvalid(
                                storedBlock(
                                        Map.of(
                                                "type", "redacted_thinking",
                                                "data", ""))),
                () -> assertInvalid(storedBlock(Map.of("type", "redacted_thinking", "data", 1))));
    }

    private static Map<String, Object> storedBlock(Object value) {
        return Map.of(KEY_PREFIX + "0", value);
    }

    private static Map<String, Object> validThinking(String thinking) {
        return Map.of(
                "type", "thinking",
                "thinking", thinking,
                "signature", "signature-123");
    }

    private static ThinkingBlock block(Map<String, Object> metadata) {
        return ThinkingBlock.builder().thinking("reasoning").metadata(metadata).build();
    }

    private static void assertInvalid(Map<String, Object> metadata) {
        assertThrows(
                IllegalArgumentException.class,
                () -> AnthropicThinkingMetadata.toContentBlockParams(block(metadata)));
    }
}

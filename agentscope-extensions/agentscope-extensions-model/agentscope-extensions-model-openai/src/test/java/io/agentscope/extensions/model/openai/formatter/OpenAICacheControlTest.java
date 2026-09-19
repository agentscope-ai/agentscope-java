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
package io.agentscope.extensions.model.openai.formatter;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.core.type.TypeReference;
import io.agentscope.core.message.MessageMetadataKeys;
import io.agentscope.core.message.Msg;
import io.agentscope.core.message.MsgRole;
import io.agentscope.core.util.JsonUtils;
import io.agentscope.extensions.model.openai.dto.OpenAIContentPart;
import io.agentscope.extensions.model.openai.dto.OpenAIMessage;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

/**
 * Tests for cache_control support in OpenAI formatter.
 */
class OpenAICacheControlTest {

    private static final Map<String, String> EPHEMERAL = Map.of("type", "ephemeral");
    private static final Map<String, String> NO_CACHE = Map.of();

    private OpenAIChatFormatter formatter;

    @BeforeEach
    void setUp() {
        formatter = new OpenAIChatFormatter();
    }

    @Nested
    @DisplayName("applyCacheControl - automatic strategy")
    class ApplyCacheControlTest {

        @Test
        @DisplayName("should add cache_control to system and last message")
        void systemAndLastMessage() {
            List<OpenAIMessage> messages = new ArrayList<>();
            messages.add(
                    OpenAIMessage.builder().role("system").content("You are helpful.").build());
            messages.add(OpenAIMessage.builder().role("user").content("Hello").build());
            messages.add(OpenAIMessage.builder().role("assistant").content("Hi").build());
            messages.add(OpenAIMessage.builder().role("user").content("Question").build());

            formatter.applyCacheControl(messages);

            assertCacheControlOnLastContentPart(messages.get(0), EPHEMERAL);
            assertNoSerializedCacheControl(messages.get(1));
            assertNoSerializedCacheControl(messages.get(2));
            assertCacheControlOnLastContentPart(messages.get(3), EPHEMERAL);
            assertEquals("You are helpful.", messages.get(0).getContentAsList().get(0).getText());
            assertEquals("Question", messages.get(3).getContentAsList().get(0).getText());
        }

        @Test
        @DisplayName("should handle no system message - only last message")
        void noSystemMessage() {
            List<OpenAIMessage> messages = new ArrayList<>();
            messages.add(OpenAIMessage.builder().role("user").content("Hello").build());
            messages.add(OpenAIMessage.builder().role("assistant").content("Hi").build());

            formatter.applyCacheControl(messages);

            assertNoSerializedCacheControl(messages.get(0));
            assertCacheControlOnLastContentPart(messages.get(1), EPHEMERAL);
        }

        @Test
        @DisplayName("should handle empty list without error")
        void emptyList() {
            List<OpenAIMessage> messages = new ArrayList<>();
            formatter.applyCacheControl(messages);
            // No exception thrown
        }

        @Test
        @DisplayName("should handle null list without error")
        void nullList() {
            formatter.applyCacheControl(null);
            // No exception thrown
        }

        @Test
        @DisplayName("should handle single system message (both system and last)")
        void singleSystemMessage() {
            List<OpenAIMessage> messages = new ArrayList<>();
            messages.add(
                    OpenAIMessage.builder().role("system").content("You are helpful.").build());

            formatter.applyCacheControl(messages);

            assertCacheControlOnLastContentPart(messages.get(0), EPHEMERAL);
        }

        @Test
        @DisplayName("should migrate a legacy system cache_control to its last content block")
        void legacySystemMarkerMigrated() {
            Map<String, String> customCacheControl = Map.of("type", "custom");

            List<OpenAIMessage> messages = new ArrayList<>();
            messages.add(
                    OpenAIMessage.builder()
                            .role("system")
                            .content("System")
                            .cacheControl(customCacheControl)
                            .build());
            messages.add(OpenAIMessage.builder().role("user").content("User").build());

            formatter.applyCacheControl(messages);

            assertCacheControlOnLastContentPart(messages.get(0), customCacheControl);
            assertCacheControlOnLastContentPart(messages.get(1), EPHEMERAL);
        }

        @Test
        @DisplayName("should migrate a legacy last-message cache_control without overwriting it")
        void legacyLastMessageMarkerMigrated() {
            Map<String, String> customCacheControl = Map.of("type", "custom");

            List<OpenAIMessage> messages = new ArrayList<>();
            messages.add(OpenAIMessage.builder().role("system").content("System").build());
            messages.add(
                    OpenAIMessage.builder()
                            .role("user")
                            .content("User")
                            .cacheControl(customCacheControl)
                            .build());

            formatter.applyCacheControl(messages);

            assertCacheControlOnLastContentPart(messages.get(0), EPHEMERAL);
            assertCacheControlOnLastContentPart(messages.get(1), customCacheControl);
        }

        @Test
        @DisplayName("should handle multiple system messages")
        void multipleSystemMessages() {
            List<OpenAIMessage> messages = new ArrayList<>();
            messages.add(OpenAIMessage.builder().role("system").content("System 1").build());
            messages.add(OpenAIMessage.builder().role("system").content("System 2").build());
            messages.add(OpenAIMessage.builder().role("user").content("User").build());

            formatter.applyCacheControl(messages);

            assertCacheControlOnLastContentPart(messages.get(0), EPHEMERAL);
            assertCacheControlOnLastContentPart(messages.get(1), EPHEMERAL);
            assertCacheControlOnLastContentPart(messages.get(2), EPHEMERAL);
        }

        @Test
        @DisplayName("should mark only the last existing multimodal content part")
        void existingMultimodalContent() {
            OpenAIContentPart text = OpenAIContentPart.text("Look at this image");
            OpenAIContentPart image =
                    OpenAIContentPart.imageUrl("https://example.com/image.png", "high");
            List<OpenAIContentPart> content = new ArrayList<>(List.of(text, image));
            OpenAIMessage message = OpenAIMessage.builder().role("user").content(content).build();

            formatter.applyCacheControl(List.of(message));

            assertSame(content, message.getContent());
            assertEquals("Look at this image", content.get(0).getText());
            assertEquals("https://example.com/image.png", content.get(1).getImageUrl().getUrl());
            assertEquals("high", content.get(1).getImageUrl().getDetail());
            assertCacheControlOnLastContentPart(message, EPHEMERAL);
            Map<String, Object> payload = serialize(message);
            List<?> parts = (List<?>) payload.get("content");
            assertFalse(((Map<?, ?>) parts.get(0)).containsKey("cache_control"));
        }
    }

    @Nested
    @DisplayName("metadata-based cache_control marking")
    class MetadataMarkingTest {

        @Test
        @DisplayName("should set cache_control from Msg metadata")
        void metadataMarking() {
            Map<String, Object> metadata = new HashMap<>();
            metadata.put(MessageMetadataKeys.CACHE_CONTROL, true);
            Msg msg =
                    Msg.builder()
                            .role(MsgRole.USER)
                            .textContent("Important context")
                            .metadata(metadata)
                            .build();

            List<OpenAIMessage> result = formatter.format(List.of(msg));

            assertEquals(1, result.size());
            assertCacheControlOnLastContentPart(result.get(0), EPHEMERAL);
            assertEquals("Important context", result.get(0).getContentAsList().get(0).getText());
        }

        @Test
        @DisplayName("should not set cache_control when metadata flag is absent")
        void noMetadata() {
            Msg msg = Msg.builder().role(MsgRole.USER).textContent("Hello").build();

            List<OpenAIMessage> result = formatter.format(List.of(msg));

            assertEquals(1, result.size());
            assertNull(result.get(0).getCacheControl());
            assertNoSerializedCacheControl(result.get(0));
        }

        @Test
        @DisplayName("should mark explicit no-cache when metadata flag is false")
        void metadataFalse() {
            Map<String, Object> metadata = new HashMap<>();
            metadata.put(MessageMetadataKeys.CACHE_CONTROL, false);
            Msg msg =
                    Msg.builder()
                            .role(MsgRole.USER)
                            .textContent("Hello")
                            .metadata(metadata)
                            .build();

            List<OpenAIMessage> result = formatter.format(List.of(msg));

            assertEquals(1, result.size());
            assertEquals(NO_CACHE, result.get(0).getCacheControl());
            assertNoSerializedCacheControl(result.get(0));
        }

        @Test
        @DisplayName("should not auto-cache a system message explicitly marked false")
        void systemMessageExplicitNoCache() {
            Map<String, Object> metadata = new HashMap<>();
            metadata.put(MessageMetadataKeys.CACHE_CONTROL, false);
            Msg systemMsg =
                    Msg.builder()
                            .role(MsgRole.SYSTEM)
                            .textContent("System prompt")
                            .metadata(metadata)
                            .build();
            Msg userMsg = Msg.builder().role(MsgRole.USER).textContent("User msg").build();

            List<OpenAIMessage> result = formatter.format(List.of(systemMsg, userMsg));
            formatter.applyCacheControl(result);
            formatter.applyCacheControl(result);

            assertEquals(NO_CACHE, result.get(0).getCacheControl());
            assertNoSerializedCacheControl(result.get(0));
            assertCacheControlOnLastContentPart(result.get(1), EPHEMERAL);
        }

        @Test
        @DisplayName("should not serialize the no-cache marker into the API payload")
        void noCacheNotSerialized() throws Exception {
            Map<String, Object> metadata = new HashMap<>();
            metadata.put(MessageMetadataKeys.CACHE_CONTROL, false);
            Msg msg =
                    Msg.builder()
                            .role(MsgRole.USER)
                            .textContent("Hello")
                            .metadata(metadata)
                            .build();

            List<OpenAIMessage> result = formatter.format(List.of(msg));
            String json = JsonUtils.getJsonCodec().toJson(result.get(0));

            assertEquals(NO_CACHE, result.get(0).getCacheControl());
            assertFalse(json.contains("no_cache"));
            assertFalse(json.contains("cache_control"));
        }

        @Test
        @DisplayName("should set cache_control on system message via metadata")
        void systemMessageMetadata() {
            Map<String, Object> metadata = new HashMap<>();
            metadata.put(MessageMetadataKeys.CACHE_CONTROL, true);
            Msg systemMsg =
                    Msg.builder()
                            .role(MsgRole.SYSTEM)
                            .textContent("System prompt")
                            .metadata(metadata)
                            .build();
            Msg userMsg = Msg.builder().role(MsgRole.USER).textContent("User msg").build();

            List<OpenAIMessage> result = formatter.format(List.of(systemMsg, userMsg));

            assertEquals(2, result.size());
            assertCacheControlOnLastContentPart(result.get(0), EPHEMERAL);
            assertNoSerializedCacheControl(result.get(1));
        }
    }

    @Nested
    @DisplayName("OpenAIMultiAgentFormatter cache_control")
    class MultiAgentFormatterTest {

        @Test
        @DisplayName("should preserve true metadata on a merged multi-agent message")
        void mergedMessageMetadataTrueMarksContentBlock() {
            OpenAIMultiAgentFormatter multiFormatter = new OpenAIMultiAgentFormatter();
            Msg msg =
                    Msg.builder()
                            .role(MsgRole.USER)
                            .textContent("Remember this")
                            .metadata(Map.of(MessageMetadataKeys.CACHE_CONTROL, true))
                            .build();

            List<OpenAIMessage> messages = multiFormatter.format(List.of(msg));

            assertCacheControlOnLastContentPart(messages.get(0), EPHEMERAL);
        }

        @Test
        @DisplayName("should preserve false metadata on a merged multi-agent message")
        void mergedMessageMetadataFalseBlocksRepeatedAutomaticMarker() {
            OpenAIMultiAgentFormatter multiFormatter = new OpenAIMultiAgentFormatter();
            Msg msg =
                    Msg.builder()
                            .role(MsgRole.USER)
                            .textContent("Do not mark this message")
                            .metadata(Map.of(MessageMetadataKeys.CACHE_CONTROL, false))
                            .build();

            List<OpenAIMessage> messages = multiFormatter.format(List.of(msg));
            multiFormatter.applyCacheControl(messages);
            multiFormatter.applyCacheControl(messages);

            assertEquals(NO_CACHE, messages.get(0).getCacheControl());
            assertNoSerializedCacheControl(messages.get(0));
        }
    }

    private static Map<String, Object> serialize(OpenAIMessage message) {
        return JsonUtils.getJsonCodec()
                .fromJson(
                        JsonUtils.getJsonCodec().toJson(message),
                        new TypeReference<Map<String, Object>>() {});
    }

    private static void assertCacheControlOnLastContentPart(
            OpenAIMessage message, Map<String, String> expected) {
        assertNull(message.getCacheControl());
        Map<String, Object> payload = serialize(message);
        assertFalse(payload.containsKey("cache_control"));
        assertTrue(payload.get("content") instanceof List<?>);
        List<?> content = (List<?>) payload.get("content");
        assertFalse(content.isEmpty());
        assertTrue(content.get(content.size() - 1) instanceof Map<?, ?>);
        Map<?, ?> lastPart = (Map<?, ?>) content.get(content.size() - 1);
        assertEquals(expected, lastPart.get("cache_control"));
    }

    private static void assertNoSerializedCacheControl(OpenAIMessage message) {
        Map<String, Object> payload = serialize(message);
        assertFalse(payload.containsKey("cache_control"));
        Object content = payload.get("content");
        if (content instanceof List<?> parts) {
            for (Object part : parts) {
                assertNotNull(part);
                if (part instanceof Map<?, ?> partMap) {
                    assertFalse(partMap.containsKey("cache_control"));
                }
            }
        }
    }
}

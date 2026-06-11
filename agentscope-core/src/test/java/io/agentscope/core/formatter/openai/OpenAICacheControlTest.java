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
package io.agentscope.core.formatter.openai;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.agentscope.core.formatter.openai.dto.OpenAIContentPart;
import io.agentscope.core.formatter.openai.dto.OpenAIMessage;
import io.agentscope.core.message.MessageMetadataKeys;
import io.agentscope.core.message.Msg;
import io.agentscope.core.message.MsgRole;
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
 * Validates that cache_control is placed at content block level.
 */
class OpenAICacheControlTest {

    private static final Map<String, String> EPHEMERAL = Map.of("type", "ephemeral");

    private OpenAIChatFormatter formatter;

    @BeforeEach
    void setUp() {
        formatter = new OpenAIChatFormatter();
    }

    /** Helper: get the last content part's cacheControl from a message. */
    private Map<String, String> getLastPartCacheControl(OpenAIMessage msg) {
        List<OpenAIContentPart> parts = msg.getContentAsList();
        assertNotNull(parts, "Content should be array format after applyCacheControl");
        assertTrue(!parts.isEmpty(), "Content parts should not be empty");
        return parts.get(parts.size() - 1).getCacheControl();
    }

    /** Helper: assert no content block in the message has cache_control set. */
    private void assertNoCacheControlOnParts(OpenAIMessage msg) {
        List<OpenAIContentPart> parts = msg.getContentAsList();
        if (parts == null) {
            return;
        }
        for (OpenAIContentPart part : parts) {
            assertNull(part.getCacheControl(), "No content block should have cache_control");
        }
    }

    @Nested
    @DisplayName("applyCacheControl - content block level")
    class ApplyCacheControlTest {

        @Test
        @DisplayName("should add cache_control to last content block of system and last message")
        void systemAndLastMessage() {
            List<OpenAIMessage> messages = new ArrayList<>();
            messages.add(
                    OpenAIMessage.builder().role("system").content("You are helpful.").build());
            messages.add(OpenAIMessage.builder().role("user").content("Hello").build());
            messages.add(OpenAIMessage.builder().role("assistant").content("Hi").build());
            messages.add(OpenAIMessage.builder().role("user").content("Question").build());

            formatter.applyCacheControl(messages);

            assertEquals(EPHEMERAL, getLastPartCacheControl(messages.get(0)));
            assertNull(messages.get(0).getCacheControl());

            assertNoCacheControlOnParts(messages.get(1));
            assertNoCacheControlOnParts(messages.get(2));

            assertEquals(EPHEMERAL, getLastPartCacheControl(messages.get(3)));
            assertNull(messages.get(3).getCacheControl());
        }

        @Test
        @DisplayName("should handle no system message - only last message")
        void noSystemMessage() {
            List<OpenAIMessage> messages = new ArrayList<>();
            messages.add(OpenAIMessage.builder().role("user").content("Hello").build());
            messages.add(OpenAIMessage.builder().role("assistant").content("Hi").build());

            formatter.applyCacheControl(messages);

            assertNoCacheControlOnParts(messages.get(0));
            assertEquals(EPHEMERAL, getLastPartCacheControl(messages.get(1)));
        }

        @Test
        @DisplayName("should handle empty list without error")
        void emptyList() {
            List<OpenAIMessage> messages = new ArrayList<>();
            formatter.applyCacheControl(messages);
        }

        @Test
        @DisplayName("should handle null list without error")
        void nullList() {
            formatter.applyCacheControl(null);
        }

        @Test
        @DisplayName("should handle single system message (both system and last)")
        void singleSystemMessage() {
            List<OpenAIMessage> messages = new ArrayList<>();
            messages.add(
                    OpenAIMessage.builder().role("system").content("You are helpful.").build());

            formatter.applyCacheControl(messages);

            assertEquals(EPHEMERAL, getLastPartCacheControl(messages.get(0)));
        }

        @Test
        @DisplayName("should not overwrite content block with existing cache_control")
        void manuallyMarkedNotOverridden() {
            Map<String, String> customCacheControl = Map.of("type", "custom");

            OpenAIContentPart part =
                    OpenAIContentPart.builder()
                            .type("text")
                            .text("System")
                            .cacheControl(customCacheControl)
                            .build();
            List<OpenAIMessage> messages = new ArrayList<>();
            messages.add(OpenAIMessage.builder().role("system").content(List.of(part)).build());
            messages.add(OpenAIMessage.builder().role("user").content("User").build());

            formatter.applyCacheControl(messages);

            assertEquals(customCacheControl, getLastPartCacheControl(messages.get(0)));
            assertEquals(EPHEMERAL, getLastPartCacheControl(messages.get(1)));
        }

        @Test
        @DisplayName("should not overwrite last message content block with existing cache_control")
        void lastMessageManuallyMarkedNotOverridden() {
            Map<String, String> customCacheControl = Map.of("type", "custom");

            OpenAIContentPart part =
                    OpenAIContentPart.builder()
                            .type("text")
                            .text("User")
                            .cacheControl(customCacheControl)
                            .build();
            List<OpenAIMessage> messages = new ArrayList<>();
            messages.add(OpenAIMessage.builder().role("system").content("System").build());
            messages.add(OpenAIMessage.builder().role("user").content(List.of(part)).build());

            formatter.applyCacheControl(messages);

            assertEquals(EPHEMERAL, getLastPartCacheControl(messages.get(0)));
            assertEquals(customCacheControl, getLastPartCacheControl(messages.get(1)));
        }

        @Test
        @DisplayName("should handle multiple system messages")
        void multipleSystemMessages() {
            List<OpenAIMessage> messages = new ArrayList<>();
            messages.add(OpenAIMessage.builder().role("system").content("System 1").build());
            messages.add(OpenAIMessage.builder().role("system").content("System 2").build());
            messages.add(OpenAIMessage.builder().role("user").content("User").build());

            formatter.applyCacheControl(messages);

            assertEquals(EPHEMERAL, getLastPartCacheControl(messages.get(0)));
            assertEquals(EPHEMERAL, getLastPartCacheControl(messages.get(1)));
            assertEquals(EPHEMERAL, getLastPartCacheControl(messages.get(2)));
        }

        @Test
        @DisplayName("should convert string content to array format")
        void stringContentConvertedToArray() {
            List<OpenAIMessage> messages = new ArrayList<>();
            messages.add(OpenAIMessage.builder().role("system").content("Hello world").build());

            formatter.applyCacheControl(messages);

            assertTrue(messages.get(0).isMultimodal(), "Content should be array format");
            List<OpenAIContentPart> parts = messages.get(0).getContentAsList();
            assertNotNull(parts);
            assertEquals(1, parts.size());
            assertEquals("text", parts.get(0).getType());
            assertEquals("Hello world", parts.get(0).getText());
            assertEquals(EPHEMERAL, parts.get(0).getCacheControl());
        }
    }

    @Nested
    @DisplayName("metadata-based cache_control marking")
    class MetadataMarkingTest {

        @Test
        @DisplayName("should set cache_control on content block from Msg metadata")
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
            assertNull(
                    result.get(0).getCacheControl(), "Message-level cache_control should be null");
            assertEquals(EPHEMERAL, getLastPartCacheControl(result.get(0)));
        }

        @Test
        @DisplayName("should not set cache_control when metadata flag is absent")
        void noMetadata() {
            Msg msg = Msg.builder().role(MsgRole.USER).textContent("Hello").build();

            List<OpenAIMessage> result = formatter.format(List.of(msg));

            assertEquals(1, result.size());
            assertNull(result.get(0).getCacheControl());
        }

        @Test
        @DisplayName("should not set cache_control when metadata flag is false")
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
            assertNull(result.get(0).getCacheControl());
        }

        @Test
        @DisplayName("should set cache_control on system message content block via metadata")
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
            assertNull(result.get(0).getCacheControl());
            assertEquals(EPHEMERAL, getLastPartCacheControl(result.get(0)));
            assertNull(result.get(1).getCacheControl());
        }
    }
}

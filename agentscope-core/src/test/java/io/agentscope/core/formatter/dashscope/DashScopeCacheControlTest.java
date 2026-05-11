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
package io.agentscope.core.formatter.dashscope;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.agentscope.core.formatter.dashscope.dto.DashScopeContentPart;
import io.agentscope.core.formatter.dashscope.dto.DashScopeMessage;
import io.agentscope.core.message.MessageMetadataKeys;
import io.agentscope.core.message.Msg;
import io.agentscope.core.message.MsgRole;
import io.agentscope.core.util.JsonCodec;
import io.agentscope.core.util.JsonUtils;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

/**
 * Tests for cache_control support in DashScope formatter.
 * Validates that cache_control is placed at content block level per DashScope API spec.
 */
class DashScopeCacheControlTest {

    private static final Map<String, String> EPHEMERAL = Map.of("type", "ephemeral");

    private DashScopeChatFormatter formatter;

    @BeforeEach
    void setUp() {
        formatter = new DashScopeChatFormatter();
    }

    /** Helper: get the last content part's cacheControl from a message. */
    private Map<String, String> getLastPartCacheControl(DashScopeMessage msg) {
        List<DashScopeContentPart> parts = msg.getContentAsList();
        assertNotNull(parts, "Content should be array format after applyCacheControl");
        assertTrue(!parts.isEmpty(), "Content parts should not be empty");
        return parts.get(parts.size() - 1).getCacheControl();
    }

    /** Helper: assert no content block in the message has cache_control set. */
    private void assertNoCacheControlOnParts(DashScopeMessage msg) {
        List<DashScopeContentPart> parts = msg.getContentAsList();
        if (parts == null) {
            return;
        }
        for (DashScopeContentPart part : parts) {
            assertNull(part.getCacheControl(), "No content block should have cache_control");
        }
    }

    @Nested
    @DisplayName("applyCacheControl - content block level")
    class ApplyCacheControlTest {

        @Test
        @DisplayName("should add cache_control to last content block of system and last message")
        void systemAndLastMessage() {
            List<DashScopeMessage> messages = new ArrayList<>();
            messages.add(
                    DashScopeMessage.builder().role("system").content("You are helpful.").build());
            messages.add(DashScopeMessage.builder().role("user").content("Hello").build());
            messages.add(DashScopeMessage.builder().role("assistant").content("Hi").build());
            messages.add(DashScopeMessage.builder().role("user").content("Question").build());

            formatter.applyCacheControl(messages);

            // system message: content converted to array, last part has cache_control
            assertEquals(EPHEMERAL, getLastPartCacheControl(messages.get(0)));
            assertNull(
                    messages.get(0).getCacheControl(),
                    "Message-level cache_control should be null");

            // middle messages: no cache_control
            assertNoCacheControlOnParts(messages.get(1));
            assertNoCacheControlOnParts(messages.get(2));

            // last message: content converted to array, last part has cache_control
            assertEquals(EPHEMERAL, getLastPartCacheControl(messages.get(3)));
            assertNull(
                    messages.get(3).getCacheControl(),
                    "Message-level cache_control should be null");
        }

        @Test
        @DisplayName("should handle no system message - only last message")
        void noSystemMessage() {
            List<DashScopeMessage> messages = new ArrayList<>();
            messages.add(DashScopeMessage.builder().role("user").content("Hello").build());
            messages.add(DashScopeMessage.builder().role("assistant").content("Hi").build());

            formatter.applyCacheControl(messages);

            assertNoCacheControlOnParts(messages.get(0));
            assertEquals(EPHEMERAL, getLastPartCacheControl(messages.get(1)));
        }

        @Test
        @DisplayName("should handle empty list without error")
        void emptyList() {
            List<DashScopeMessage> messages = new ArrayList<>();
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
            List<DashScopeMessage> messages = new ArrayList<>();
            messages.add(
                    DashScopeMessage.builder().role("system").content("You are helpful.").build());

            formatter.applyCacheControl(messages);

            assertEquals(EPHEMERAL, getLastPartCacheControl(messages.get(0)));
        }

        @Test
        @DisplayName("should not overwrite content block with existing cache_control")
        void manuallyMarkedNotOverridden() {
            Map<String, String> customCacheControl = Map.of("type", "custom");

            DashScopeContentPart part =
                    DashScopeContentPart.builder()
                            .text("System")
                            .cacheControl(customCacheControl)
                            .build();
            List<DashScopeMessage> messages = new ArrayList<>();
            messages.add(DashScopeMessage.builder().role("system").content(List.of(part)).build());
            messages.add(DashScopeMessage.builder().role("user").content("User").build());

            formatter.applyCacheControl(messages);

            // System message keeps its custom cache_control on the content block
            assertEquals(customCacheControl, getLastPartCacheControl(messages.get(0)));
            // Last message gets ephemeral on content block
            assertEquals(EPHEMERAL, getLastPartCacheControl(messages.get(1)));
        }

        @Test
        @DisplayName("should not overwrite last message content block with existing cache_control")
        void lastMessageManuallyMarkedNotOverridden() {
            Map<String, String> customCacheControl = Map.of("type", "custom");

            DashScopeContentPart part =
                    DashScopeContentPart.builder()
                            .text("User")
                            .cacheControl(customCacheControl)
                            .build();
            List<DashScopeMessage> messages = new ArrayList<>();
            messages.add(DashScopeMessage.builder().role("system").content("System").build());
            messages.add(DashScopeMessage.builder().role("user").content(List.of(part)).build());

            formatter.applyCacheControl(messages);

            // System message gets ephemeral
            assertEquals(EPHEMERAL, getLastPartCacheControl(messages.get(0)));
            // Last message keeps its custom cache_control
            assertEquals(customCacheControl, getLastPartCacheControl(messages.get(1)));
        }

        @Test
        @DisplayName("should handle multiple system messages")
        void multipleSystemMessages() {
            List<DashScopeMessage> messages = new ArrayList<>();
            messages.add(DashScopeMessage.builder().role("system").content("System 1").build());
            messages.add(DashScopeMessage.builder().role("system").content("System 2").build());
            messages.add(DashScopeMessage.builder().role("user").content("User").build());

            formatter.applyCacheControl(messages);

            assertEquals(EPHEMERAL, getLastPartCacheControl(messages.get(0)));
            assertEquals(EPHEMERAL, getLastPartCacheControl(messages.get(1)));
            assertEquals(EPHEMERAL, getLastPartCacheControl(messages.get(2)));
        }

        @Test
        @DisplayName("should convert string content to array format")
        void stringContentConvertedToArray() {
            List<DashScopeMessage> messages = new ArrayList<>();
            messages.add(DashScopeMessage.builder().role("system").content("Hello world").build());

            formatter.applyCacheControl(messages);

            // Content should now be array format
            assertTrue(messages.get(0).isMultimodal(), "Content should be array format");
            List<DashScopeContentPart> parts = messages.get(0).getContentAsList();
            assertNotNull(parts);
            assertEquals(1, parts.size());
            assertEquals("Hello world", parts.get(0).getText());
            assertEquals(EPHEMERAL, parts.get(0).getCacheControl());
        }

        @Test
        @DisplayName("should set cache_control on last part when content is already array")
        void arrayContentLastPartMarked() {
            List<DashScopeContentPart> contentParts =
                    List.of(
                            DashScopeContentPart.text("First part"),
                            DashScopeContentPart.text("Second part"),
                            DashScopeContentPart.text("Third part"));
            List<DashScopeMessage> messages = new ArrayList<>();
            messages.add(
                    DashScopeMessage.builder()
                            .role("system")
                            .content(new ArrayList<>(contentParts))
                            .build());

            formatter.applyCacheControl(messages);

            List<DashScopeContentPart> parts = messages.get(0).getContentAsList();
            assertNull(parts.get(0).getCacheControl());
            assertNull(parts.get(1).getCacheControl());
            assertEquals(EPHEMERAL, parts.get(2).getCacheControl());
        }
    }

    @Nested
    @DisplayName("JSON serialization verification")
    class JsonSerializationTest {

        @Test
        @DisplayName("cache_control should appear inside content block, not at message level")
        void cacheControlInContentBlock() throws Exception {
            List<DashScopeMessage> messages = new ArrayList<>();
            messages.add(
                    DashScopeMessage.builder().role("system").content("You are helpful.").build());
            messages.add(DashScopeMessage.builder().role("user").content("Hello").build());

            formatter.applyCacheControl(messages);

            JsonCodec jsonCodec = JsonUtils.getJsonCodec();
            String json = jsonCodec.toJson(messages);

            // cache_control should be within content blocks
            assertTrue(json.contains("\"cache_control\""), "JSON should contain cache_control");
            assertTrue(json.contains("\"ephemeral\""), "JSON should contain ephemeral");

            // Verify message-level cache_control is NOT present
            for (DashScopeMessage msg : messages) {
                assertNull(
                        msg.getCacheControl(),
                        "Message-level cache_control should be null for role: " + msg.getRole());
            }
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

            List<DashScopeMessage> result = formatter.format(List.of(msg));

            assertEquals(1, result.size());
            // cache_control should be on content block, not message
            assertNull(
                    result.get(0).getCacheControl(), "Message-level cache_control should be null");
            assertEquals(EPHEMERAL, getLastPartCacheControl(result.get(0)));
        }

        @Test
        @DisplayName("should not set cache_control when metadata flag is absent")
        void noMetadata() {
            Msg msg = Msg.builder().role(MsgRole.USER).textContent("Hello").build();

            List<DashScopeMessage> result = formatter.format(List.of(msg));

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

            List<DashScopeMessage> result = formatter.format(List.of(msg));

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

            List<DashScopeMessage> result = formatter.format(List.of(systemMsg, userMsg));

            assertEquals(2, result.size());
            // System message: cache_control on content block
            assertNull(result.get(0).getCacheControl());
            assertEquals(EPHEMERAL, getLastPartCacheControl(result.get(0)));
            // User message: no cache_control
            assertNull(result.get(1).getCacheControl());
        }
    }

    @Nested
    @DisplayName("DashScopeMultiAgentFormatter cache_control")
    class MultiAgentFormatterTest {

        @Test
        @DisplayName("should add cache_control to content blocks of system and last message")
        void applyCacheControl() {
            DashScopeMultiAgentFormatter multiFormatter = new DashScopeMultiAgentFormatter();

            List<DashScopeMessage> messages = new ArrayList<>();
            messages.add(
                    DashScopeMessage.builder().role("system").content("You are helpful.").build());
            messages.add(DashScopeMessage.builder().role("user").content("Hello").build());

            multiFormatter.applyCacheControl(messages);

            assertEquals(EPHEMERAL, getLastPartCacheControl(messages.get(0)));
            assertEquals(EPHEMERAL, getLastPartCacheControl(messages.get(1)));
            // message-level should be null
            assertNull(messages.get(0).getCacheControl());
            assertNull(messages.get(1).getCacheControl());
        }
    }
}

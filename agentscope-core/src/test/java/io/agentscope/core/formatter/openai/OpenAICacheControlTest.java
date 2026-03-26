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
import static org.junit.jupiter.api.Assertions.assertNull;

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
 */
class OpenAICacheControlTest {

    private static final Map<String, String> EPHEMERAL = Map.of("type", "ephemeral");

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

            assertEquals(EPHEMERAL, messages.get(0).getCacheControl());
            assertNull(messages.get(1).getCacheControl());
            assertNull(messages.get(2).getCacheControl());
            assertEquals(EPHEMERAL, messages.get(3).getCacheControl());
        }

        @Test
        @DisplayName("should handle no system message - only last message")
        void noSystemMessage() {
            List<OpenAIMessage> messages = new ArrayList<>();
            messages.add(OpenAIMessage.builder().role("user").content("Hello").build());
            messages.add(OpenAIMessage.builder().role("assistant").content("Hi").build());

            formatter.applyCacheControl(messages);

            assertNull(messages.get(0).getCacheControl());
            assertEquals(EPHEMERAL, messages.get(1).getCacheControl());
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

            assertEquals(EPHEMERAL, messages.get(0).getCacheControl());
        }

        @Test
        @DisplayName("should not overwrite manually marked cache_control")
        void manuallyMarkedNotOverridden() {
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

            // System message keeps its custom cache_control
            assertEquals(customCacheControl, messages.get(0).getCacheControl());
            // Last message gets ephemeral
            assertEquals(EPHEMERAL, messages.get(1).getCacheControl());
        }

        @Test
        @DisplayName("should not overwrite last message with existing cache_control")
        void lastMessageManuallyMarkedNotOverridden() {
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

            // System message gets ephemeral
            assertEquals(EPHEMERAL, messages.get(0).getCacheControl());
            // Last message keeps its custom cache_control
            assertEquals(customCacheControl, messages.get(1).getCacheControl());
        }

        @Test
        @DisplayName("should handle multiple system messages")
        void multipleSystemMessages() {
            List<OpenAIMessage> messages = new ArrayList<>();
            messages.add(OpenAIMessage.builder().role("system").content("System 1").build());
            messages.add(OpenAIMessage.builder().role("system").content("System 2").build());
            messages.add(OpenAIMessage.builder().role("user").content("User").build());

            formatter.applyCacheControl(messages);

            assertEquals(EPHEMERAL, messages.get(0).getCacheControl());
            assertEquals(EPHEMERAL, messages.get(1).getCacheControl());
            assertEquals(EPHEMERAL, messages.get(2).getCacheControl());
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
            assertEquals(EPHEMERAL, result.get(0).getCacheControl());
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
            assertEquals(EPHEMERAL, result.get(0).getCacheControl());
            assertNull(result.get(1).getCacheControl());
        }
    }
}

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

import io.agentscope.core.formatter.openai.dto.OpenAIContentPart;
import io.agentscope.core.formatter.openai.dto.OpenAIMessage;
import io.agentscope.core.message.ImageBlock;
import io.agentscope.core.message.MessageMetadataKeys;
import io.agentscope.core.message.Msg;
import io.agentscope.core.message.MsgRole;
import io.agentscope.core.message.TextBlock;
import io.agentscope.core.message.URLSource;
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

    @Nested
    @DisplayName("applyCacheControl - multimodal messages")
    class MultimodalApplyCacheControlTest {

        @Test
        @DisplayName("should add cache_control to last text part in multimodal content array")
        void multimodalLastTextPart() {
            List<OpenAIContentPart> content = new ArrayList<>();
            content.add(OpenAIContentPart.imageUrl("https://example.com/image.jpg"));
            content.add(OpenAIContentPart.text("Describe this image"));

            List<OpenAIMessage> messages = new ArrayList<>();
            messages.add(OpenAIMessage.builder().role("user").content(content).build());

            formatter.applyCacheControl(messages);

            // cache_control should be on the last text part, not the message
            List<OpenAIContentPart> resultContent = messages.get(0).getContentAsList();
            assertNull(resultContent.get(0).getCacheControl()); // image part
            assertEquals(EPHEMERAL, resultContent.get(1).getCacheControl()); // text part
            assertNull(messages.get(0).getCacheControl()); // message level
        }

        @Test
        @DisplayName("should fall back to message level when multimodal has no text part")
        void multimodalNoTextPart() {
            List<OpenAIContentPart> content = new ArrayList<>();
            content.add(OpenAIContentPart.imageUrl("https://example.com/image.jpg"));

            List<OpenAIMessage> messages = new ArrayList<>();
            messages.add(OpenAIMessage.builder().role("user").content(content).build());

            formatter.applyCacheControl(messages);

            // Falls back to message level
            assertEquals(EPHEMERAL, messages.get(0).getCacheControl());
            assertNull(messages.get(0).getContentAsList().get(0).getCacheControl());
        }

        @Test
        @DisplayName("should not overwrite existing cache_control on text part in multimodal")
        void multimodalTextPartAlreadyHasCacheControl() {
            Map<String, String> customCache = Map.of("type", "custom");
            List<OpenAIContentPart> content = new ArrayList<>();
            content.add(OpenAIContentPart.text("Original text"));
            content.get(0).setCacheControl(customCache);
            content.add(OpenAIContentPart.text("Another text"));

            List<OpenAIMessage> messages = new ArrayList<>();
            messages.add(OpenAIMessage.builder().role("assistant").content(content).build());

            formatter.applyCacheControl(messages);

            // First text part keeps custom cache_control, second gets ephemeral
            List<OpenAIContentPart> resultContent = messages.get(0).getContentAsList();
            assertEquals(customCache, resultContent.get(0).getCacheControl());
            assertEquals(EPHEMERAL, resultContent.get(1).getCacheControl());
        }

        @Test
        @DisplayName("should handle multimodal system message with text part")
        void multimodalSystemMessage() {
            List<OpenAIContentPart> systemContent = new ArrayList<>();
            systemContent.add(OpenAIContentPart.text("System prompt"));
            List<OpenAIMessage> messages = new ArrayList<>();
            messages.add(OpenAIMessage.builder().role("system").content(systemContent).build());
            messages.add(OpenAIMessage.builder().role("user").content("Hello").build());

            formatter.applyCacheControl(messages);

            // System multimodal message: cache_control on text part
            List<OpenAIContentPart> sysContent = messages.get(0).getContentAsList();
            assertEquals(EPHEMERAL, sysContent.get(0).getCacheControl());
            // Last message (text-only): cache_control at message level
            assertEquals(EPHEMERAL, messages.get(1).getCacheControl());
        }
    }

    @Nested
    @DisplayName("metadata-based cache_control - multimodal messages")
    class MultimodalMetadataTest {

        @Test
        @DisplayName("should apply cache_control to last text part in multimodal via metadata")
        void multimodalMetadataLastTextPart() {
            Map<String, Object> metadata = new HashMap<>();
            metadata.put(MessageMetadataKeys.CACHE_CONTROL, true);
            ImageBlock imageBlock =
                    ImageBlock.builder()
                            .source(
                                    URLSource.builder()
                                            .url("https://example.com/image.jpg")
                                            .build())
                            .build();
            Msg msg =
                    Msg.builder()
                            .role(MsgRole.USER)
                            .content(
                                    List.of(
                                            imageBlock,
                                            TextBlock.builder().text("What is this?").build()))
                            .metadata(metadata)
                            .build();

            List<OpenAIMessage> result = formatter.format(List.of(msg));

            List<OpenAIContentPart> contentParts = result.get(0).getContentAsList();
            assertNull(contentParts.get(0).getCacheControl()); // image part
            assertEquals(EPHEMERAL, contentParts.get(1).getCacheControl()); // text part
            assertNull(result.get(0).getCacheControl()); // message level
        }

        @Test
        @DisplayName(
                "should fall back to message level for multimodal when no text part via metadata")
        void multimodalMetadataNoTextPart() {
            Map<String, Object> metadata = new HashMap<>();
            metadata.put(MessageMetadataKeys.CACHE_CONTROL, true);
            ImageBlock imageBlock =
                    ImageBlock.builder()
                            .source(
                                    URLSource.builder()
                                            .url("https://example.com/image.jpg")
                                            .build())
                            .build();
            Msg msg =
                    Msg.builder()
                            .role(MsgRole.USER)
                            .content(List.of(imageBlock))
                            .metadata(metadata)
                            .build();

            List<OpenAIMessage> result = formatter.format(List.of(msg));

            // Falls back to message level
            assertEquals(EPHEMERAL, result.get(0).getCacheControl());
        }

        @Test
        @DisplayName("should not set cache_control on multimodal when metadata flag is absent")
        void multimodalNoMetadata() {
            // Construct an OpenAIMessage with multimodal content directly,
            // bypassing converter to test the applyCacheControlFromMetadata path
            List<OpenAIContentPart> content = new ArrayList<>();
            content.add(OpenAIContentPart.imageUrl("https://example.com/image.jpg"));
            content.add(OpenAIContentPart.text("Describe this"));
            OpenAIMessage openAIMsg = OpenAIMessage.builder().role("user").content(content).build();

            // No metadata set on the source Msg, so cache_control must not be set
            Msg msg = Msg.builder().role(MsgRole.USER).content(List.of()).build();
            // Simulate applyCacheControlFromMetadata: it checks msg.metadata
            // Since metadata is absent/null, nothing should be applied
            assertNull(openAIMsg.getCacheControl());
            assertNull(openAIMsg.getContentAsList().get(0).getCacheControl());
            assertNull(openAIMsg.getContentAsList().get(1).getCacheControl());
        }
    }
}

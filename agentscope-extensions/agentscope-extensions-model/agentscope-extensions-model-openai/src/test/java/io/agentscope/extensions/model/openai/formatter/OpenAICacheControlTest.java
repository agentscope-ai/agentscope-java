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
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

import com.fasterxml.jackson.databind.JsonNode;
import io.agentscope.core.message.MessageMetadataKeys;
import io.agentscope.core.message.Msg;
import io.agentscope.core.message.MsgRole;
import io.agentscope.core.util.JsonUtils;
import io.agentscope.extensions.model.openai.dto.OpenAIContentPart;
import io.agentscope.extensions.model.openai.dto.OpenAIMessage;
import io.agentscope.extensions.model.openai.dto.OpenAIRequest;
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

            assertEquals(NO_CACHE, result.get(0).getCacheControl());
            assertEquals(EPHEMERAL, result.get(1).getCacheControl());
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
            assertEquals(EPHEMERAL, result.get(0).getCacheControl());
            assertNull(result.get(1).getCacheControl());
        }
    }

    @Nested
    @DisplayName("Official OpenAI prompt caching")
    class OfficialOpenAITest {

        @Test
        @DisplayName("should serialize explicit metadata using the official protocol")
        void exactExplicitProtocolJson() {
            Msg msg =
                    Msg.builder()
                            .role(MsgRole.USER)
                            .textContent("Pinned context")
                            .metadata(Map.of(MessageMetadataKeys.CACHE_CONTROL, true))
                            .build();
            OpenAIRequest request =
                    OpenAIRequest.builder()
                            .model("gpt-5.6")
                            .messages(formatter.format(List.of(msg)))
                            .stream(false)
                            .build();

            formatter.applyOpenAIPromptCache(request);

            JsonNode json = toJsonTree(request);
            JsonNode message = json.path("messages").get(0);
            assertFalse(message.has("cache_control"));
            assertEquals(
                    "explicit",
                    message.path("content")
                            .get(0)
                            .path("prompt_cache_breakpoint")
                            .path("mode")
                            .asText());
            assertFalse(message.path("content").get(0).has("cache_control"));
            assertEquals("explicit", json.path("prompt_cache_options").path("mode").asText());
        }

        @Test
        @DisplayName("should rely on server automatic caching when there is no explicit marker")
        void noInventedAutomaticMarkers() {
            OpenAIRequest request =
                    OpenAIRequest.builder()
                            .model("gpt-5.6")
                            .messages(
                                    new ArrayList<>(
                                            List.of(
                                                    OpenAIMessage.builder()
                                                            .role("system")
                                                            .content("System")
                                                            .build(),
                                                    OpenAIMessage.builder()
                                                            .role("user")
                                                            .content("Question")
                                                            .build())))
                            .build();

            formatter.applyOpenAIPromptCache(request);

            JsonNode json = toJsonTree(request);
            assertFalse(json.has("prompt_cache_options"));
            assertFalse(json.toString().contains("prompt_cache_breakpoint"));
            assertFalse(json.toString().contains("cache_control"));
        }

        @Test
        @DisplayName("should not turn explicit no-cache into an official breakpoint")
        void explicitNoCacheDoesNotEnableOfficialBreakpoints() {
            Msg msg =
                    Msg.builder()
                            .role(MsgRole.USER)
                            .textContent("Dynamic context")
                            .metadata(Map.of(MessageMetadataKeys.CACHE_CONTROL, false))
                            .build();
            OpenAIRequest request =
                    OpenAIRequest.builder()
                            .model("gpt-5.6")
                            .messages(formatter.format(List.of(msg)))
                            .build();

            formatter.applyOpenAIPromptCache(request);

            JsonNode json = toJsonTree(request);
            assertFalse(json.has("prompt_cache_options"));
            assertFalse(json.toString().contains("prompt_cache_breakpoint"));
            assertFalse(json.toString().contains("cache_control"));
        }

        @Test
        @DisplayName("should preserve other prompt cache options and force explicit mode")
        void mergesConfiguredPromptCacheOptions() {
            OpenAIMessage message =
                    OpenAIMessage.builder()
                            .role("user")
                            .content("Context")
                            .cacheControl(EPHEMERAL)
                            .build();
            OpenAIRequest request =
                    OpenAIRequest.builder()
                            .model("gpt-5.6")
                            .messages(List.of(message))
                            .extraParam(
                                    "prompt_cache_options",
                                    Map.of("mode", "implicit", "ttl", "30m"))
                            .build();

            formatter.applyOpenAIPromptCache(request);

            JsonNode json = toJsonTree(request);
            assertEquals("explicit", json.path("prompt_cache_options").path("mode").asText());
            assertEquals("30m", json.path("prompt_cache_options").path("ttl").asText());
        }

        @Test
        @DisplayName("should place a multimodal breakpoint on the final content part")
        void multimodalFinalPart() {
            OpenAIMessage message =
                    OpenAIMessage.builder()
                            .role("user")
                            .content(
                                    new ArrayList<>(
                                            List.of(
                                                    OpenAIContentPart.text("Look"),
                                                    OpenAIContentPart.imageUrl(
                                                            "https://example.com/image.png"))))
                            .cacheControl(EPHEMERAL)
                            .build();
            OpenAIRequest request = OpenAIRequest.builder().messages(List.of(message)).build();

            formatter.applyOpenAIPromptCache(request);

            assertNull(message.getContentAsList().get(0).getPromptCacheBreakpoint());
            assertEquals(
                    Map.of("mode", "explicit"),
                    message.getContentAsList().get(1).getPromptCacheBreakpoint());
        }

        @Test
        @DisplayName("should reject more than four explicit breakpoints")
        void tooManyBreakpoints() {
            List<OpenAIMessage> messages = new ArrayList<>();
            for (int i = 0; i < 5; i++) {
                messages.add(
                        OpenAIMessage.builder()
                                .role("user")
                                .content("Context " + i)
                                .cacheControl(EPHEMERAL)
                                .build());
            }
            OpenAIRequest request = OpenAIRequest.builder().messages(messages).build();

            assertThrows(
                    IllegalArgumentException.class,
                    () -> formatter.applyOpenAIPromptCache(request));
        }

        @Test
        @DisplayName("should clear legacy fields for unknown compatible providers")
        void clearUnknownCompatibleMarkers() {
            OpenAIContentPart part =
                    OpenAIContentPart.builder()
                            .type("text")
                            .text("Context")
                            .cacheControl(EPHEMERAL)
                            .build();
            OpenAIMessage message =
                    OpenAIMessage.builder()
                            .role("user")
                            .content(List.of(part))
                            .cacheControl(EPHEMERAL)
                            .build();

            formatter.clearLegacyCacheControl(List.of(message));

            JsonNode json = toJsonTree(message);
            assertFalse(json.toString().contains("cache_control"));
            assertFalse(json.toString().contains("prompt_cache_breakpoint"));
        }
    }

    @Nested
    @DisplayName("DashScope-compatible cache_control")
    class DashScopeCompatibleTest {

        @Test
        @DisplayName("should serialize markers on content blocks using shared strategy")
        void exactContentBlockJson() {
            List<OpenAIMessage> messages =
                    new ArrayList<>(
                            List.of(
                                    OpenAIMessage.builder()
                                            .role("system")
                                            .content("System 1")
                                            .build(),
                                    OpenAIMessage.builder()
                                            .role("system")
                                            .content("System 2")
                                            .build(),
                                    OpenAIMessage.builder()
                                            .role("user")
                                            .content("Question")
                                            .build()));

            formatter.applyDashScopeCacheControl(messages, true);

            JsonNode first = toJsonTree(messages.get(0));
            JsonNode second = toJsonTree(messages.get(1));
            JsonNode last = toJsonTree(messages.get(2));
            assertFalse(first.has("cache_control"));
            assertEquals("text", first.path("content").get(0).path("type").asText());
            assertEquals(
                    "ephemeral",
                    first.path("content").get(0).path("cache_control").path("type").asText());
            assertFalse(second.has("cache_control"));
            assertEquals("System 2", second.path("content").asText());
            assertEquals(
                    "ephemeral",
                    last.path("content").get(0).path("cache_control").path("type").asText());
        }

        @Test
        @DisplayName("should retain explicit metadata marker when automatic strategy is disabled")
        void explicitMetadataWithoutAutomaticStrategy() {
            Msg msg =
                    Msg.builder()
                            .role(MsgRole.USER)
                            .textContent("Pinned context")
                            .metadata(Map.of(MessageMetadataKeys.CACHE_CONTROL, true))
                            .build();
            List<OpenAIMessage> messages = formatter.format(List.of(msg));

            formatter.applyDashScopeCacheControl(messages, false);

            assertNull(messages.get(0).getCacheControl());
            assertEquals(EPHEMERAL, messages.get(0).getContentAsList().get(0).getCacheControl());
        }

        @Test
        @DisplayName("should skip explicit no-cache during automatic selection")
        void explicitNoCacheIsNotReenabled() {
            Msg system =
                    Msg.builder()
                            .role(MsgRole.SYSTEM)
                            .textContent("Dynamic system")
                            .metadata(Map.of(MessageMetadataKeys.CACHE_CONTROL, false))
                            .build();
            Msg user = Msg.builder().role(MsgRole.USER).textContent("Cacheable user").build();
            List<OpenAIMessage> messages = formatter.format(List.of(system, user));

            formatter.applyDashScopeCacheControl(messages, true);

            JsonNode first = toJsonTree(messages.get(0));
            JsonNode last = toJsonTree(messages.get(1));
            assertFalse(first.toString().contains("cache_control"));
            assertEquals(
                    "ephemeral",
                    last.path("content").get(0).path("cache_control").path("type").asText());
        }

        @Test
        @DisplayName("should preserve explicit no-cache as a multi-agent boundary")
        void explicitNoCachePreventsMultiAgentMerge() {
            OpenAIMultiAgentFormatter multiFormatter = new OpenAIMultiAgentFormatter();
            Msg notCached =
                    Msg.builder()
                            .name("agent-a")
                            .role(MsgRole.USER)
                            .textContent("Dynamic context")
                            .metadata(Map.of(MessageMetadataKeys.CACHE_CONTROL, false))
                            .build();
            Msg following =
                    Msg.builder()
                            .name("agent-b")
                            .role(MsgRole.USER)
                            .textContent("Cacheable question")
                            .build();
            List<OpenAIMessage> messages = multiFormatter.format(List.of(notCached, following));
            assertEquals(2, messages.size());

            multiFormatter.applyDashScopeCacheControl(messages, true);

            assertFalse(toJsonTree(messages.get(0)).toString().contains("cache_control"));
            assertEquals(
                    "ephemeral",
                    toJsonTree(messages.get(1))
                            .path("content")
                            .get(0)
                            .path("cache_control")
                            .path("type")
                            .asText());
        }

        @Test
        @DisplayName("should place multimodal marker on the final content block")
        void multimodalFinalBlock() {
            OpenAIMessage message =
                    OpenAIMessage.builder()
                            .role("user")
                            .content(
                                    new ArrayList<>(
                                            List.of(
                                                    OpenAIContentPart.text("Look"),
                                                    OpenAIContentPart.imageUrl(
                                                            "https://example.com/image.png"))))
                            .build();

            formatter.applyDashScopeCacheControl(List.of(message), true);

            assertNull(message.getContentAsList().get(0).getCacheControl());
            assertEquals(EPHEMERAL, message.getContentAsList().get(1).getCacheControl());
        }

        @Test
        @DisplayName("should reject more than four explicit content-block markers")
        void tooManyExplicitMarkers() {
            List<OpenAIMessage> messages = new ArrayList<>();
            for (int i = 0; i < 5; i++) {
                messages.add(
                        OpenAIMessage.builder()
                                .role("user")
                                .content(
                                        new ArrayList<>(
                                                List.of(
                                                        OpenAIContentPart.builder()
                                                                .type("text")
                                                                .text("Context " + i)
                                                                .cacheControl(EPHEMERAL)
                                                                .build())))
                                .build());
            }

            assertThrows(
                    IllegalArgumentException.class,
                    () -> formatter.applyDashScopeCacheControl(messages, false));
        }

        private JsonNode toJsonTree(OpenAIMessage message) {
            return JsonUtils.getJsonCodec()
                    .fromJson(JsonUtils.getJsonCodec().toJson(message), JsonNode.class);
        }
    }

    private JsonNode toJsonTree(Object value) {
        return JsonUtils.getJsonCodec()
                .fromJson(JsonUtils.getJsonCodec().toJson(value), JsonNode.class);
    }
}

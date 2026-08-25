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
package io.agentscope.extensions.model.dashscope.formatter;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.JsonNode;
import io.agentscope.core.message.MessageMetadataKeys;
import io.agentscope.core.message.Msg;
import io.agentscope.core.message.MsgRole;
import io.agentscope.core.util.JsonUtils;
import io.agentscope.extensions.model.dashscope.dto.DashScopeContentPart;
import io.agentscope.extensions.model.dashscope.dto.DashScopeMessage;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

/** Tests DashScope content-block prompt cache markers. */
class DashScopeCacheControlTest {

    private static final Map<String, String> EPHEMERAL = Map.of("type", "ephemeral");

    private DashScopeChatFormatter formatter;

    @BeforeEach
    void setUp() {
        formatter = new DashScopeChatFormatter();
    }

    @Nested
    @DisplayName("automatic cache strategy")
    class AutomaticCacheStrategyTest {

        @Test
        void marksFirstSystemAndLastCacheableNonSystemMessage() {
            List<DashScopeMessage> messages =
                    new ArrayList<>(
                            List.of(
                                    message("system", "stable system"),
                                    message("system", "dynamic system"),
                                    message("user", "question"),
                                    message("assistant", "answer")));

            formatter.applyCacheControl(messages, true);

            assertEquals(EPHEMERAL, lastPart(messages.get(0)).getCacheControl());
            assertNoCacheControl(messages.get(1));
            assertNoCacheControl(messages.get(2));
            assertEquals(EPHEMERAL, lastPart(messages.get(3)).getCacheControl());
            messages.forEach(message -> assertNull(message.getCacheControl()));
        }

        @Test
        void automaticFalseKeepsOnlyExplicitMarkers() {
            DashScopeMessage system = message("system", "system");
            DashScopeMessage explicit =
                    DashScopeMessage.builder()
                            .role("user")
                            .content("explicit")
                            .cacheControl(Map.of("type", "custom"))
                            .build();
            DashScopeMessage last = message("assistant", "last");

            formatter.applyCacheControl(new ArrayList<>(List.of(system, explicit, last)), false);

            assertNoCacheControl(system);
            assertEquals(Map.of("type", "custom"), lastPart(explicit).getCacheControl());
            assertNull(explicit.getCacheControl());
            assertNoCacheControl(last);
        }

        @Test
        void rejectsMoreThanFourExplicitMarkers() {
            List<DashScopeMessage> messages = new ArrayList<>();
            for (int i = 0; i < 5; i++) {
                DashScopeContentPart part =
                        DashScopeContentPart.builder()
                                .type("text")
                                .text("part-" + i)
                                .cacheControl(EPHEMERAL)
                                .build();
                messages.add(
                        DashScopeMessage.builder()
                                .role("user")
                                .content(new ArrayList<>(List.of(part)))
                                .build());
            }

            IllegalArgumentException error =
                    assertThrows(
                            IllegalArgumentException.class,
                            () -> formatter.applyCacheControl(messages, false));

            assertTrue(error.getMessage().contains("at most 4"));
        }

        @Test
        void handlesNullAndEmptyLists() {
            formatter.applyCacheControl(null, true);
            formatter.applyCacheControl(List.of(), true);
        }
    }

    @Nested
    @DisplayName("wire format")
    class WireFormatTest {

        @Test
        void serializesMarkerInsideTextContentBlockOnly() {
            List<DashScopeMessage> messages =
                    new ArrayList<>(List.of(message("system", "You are helpful")));

            formatter.applyCacheControl(messages, true);

            JsonNode tree =
                    JsonUtils.getJsonCodec()
                            .fromJson(
                                    JsonUtils.getJsonCodec().toJson(messages.get(0)),
                                    JsonNode.class);
            assertFalse(tree.has("cache_control"));
            assertTrue(tree.get("content").isArray());
            assertEquals("text", tree.at("/content/0/type").asText());
            assertEquals("You are helpful", tree.at("/content/0/text").asText());
            assertEquals("ephemeral", tree.at("/content/0/cache_control/type").asText());
        }

        @Test
        void marksLastExistingMultimodalContentPart() {
            DashScopeContentPart text = DashScopeContentPart.text("describe");
            DashScopeContentPart image = DashScopeContentPart.image("https://example.com/a.png");
            DashScopeMessage message =
                    DashScopeMessage.builder()
                            .role("user")
                            .content(new ArrayList<>(List.of(text, image)))
                            .build();

            formatter.applyCacheControl(new ArrayList<>(List.of(message)), true);

            assertNull(text.getCacheControl());
            assertEquals(EPHEMERAL, image.getCacheControl());
            assertNull(message.getCacheControl());
        }
    }

    @Nested
    @DisplayName("metadata and multi-agent formatting")
    class MetadataAndMultiAgentTest {

        @Test
        void metadataMarkerIsPlacedOnContentBlockWithoutAutomaticOption() {
            Msg msg =
                    Msg.builder()
                            .role(MsgRole.USER)
                            .textContent("cache this")
                            .metadata(Map.of(MessageMetadataKeys.CACHE_CONTROL, true))
                            .build();

            DashScopeMessage formatted = formatter.format(List.of(msg)).get(0);

            assertNull(formatted.getCacheControl());
            assertEquals(EPHEMERAL, lastPart(formatted).getCacheControl());
        }

        @Test
        void explicitMetadataPreventsMultiAgentHistoryMerge() {
            DashScopeMultiAgentFormatter multiFormatter = new DashScopeMultiAgentFormatter();
            Msg cached =
                    Msg.builder()
                            .name("agent-a")
                            .role(MsgRole.USER)
                            .textContent("stable context")
                            .metadata(Map.of(MessageMetadataKeys.CACHE_CONTROL, true))
                            .build();
            Msg following =
                    Msg.builder()
                            .name("agent-b")
                            .role(MsgRole.USER)
                            .textContent("dynamic question")
                            .build();

            List<DashScopeMessage> formatted = multiFormatter.format(List.of(cached, following));

            assertEquals(2, formatted.size());
            assertEquals(EPHEMERAL, lastPart(formatted.get(0)).getCacheControl());
            assertNoCacheControl(formatted.get(1));
        }

        @Test
        void multiAgentAutomaticStrategyUsesContentBlocks() {
            DashScopeMultiAgentFormatter multiFormatter = new DashScopeMultiAgentFormatter();
            List<DashScopeMessage> messages =
                    new ArrayList<>(List.of(message("system", "system"), message("user", "user")));

            multiFormatter.applyCacheControl(messages, true);

            assertEquals(EPHEMERAL, lastPart(messages.get(0)).getCacheControl());
            assertEquals(EPHEMERAL, lastPart(messages.get(1)).getCacheControl());
            messages.forEach(message -> assertNull(message.getCacheControl()));
        }
    }

    private static DashScopeMessage message(String role, String content) {
        return DashScopeMessage.builder().role(role).content(content).build();
    }

    private static DashScopeContentPart lastPart(DashScopeMessage message) {
        List<DashScopeContentPart> parts = message.getContentAsList();
        assertNotNull(parts);
        assertFalse(parts.isEmpty());
        return parts.get(parts.size() - 1);
    }

    private static void assertNoCacheControl(DashScopeMessage message) {
        assertNull(message.getCacheControl());
        List<DashScopeContentPart> parts = message.getContentAsList();
        if (parts != null) {
            assertTrue(parts.stream().allMatch(part -> part.getCacheControl() == null));
        }
    }
}

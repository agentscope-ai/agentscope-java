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
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.core.type.TypeReference;
import io.agentscope.core.message.MessageMetadataKeys;
import io.agentscope.core.message.Msg;
import io.agentscope.core.message.MsgRole;
import io.agentscope.core.model.GenerateOptions;
import io.agentscope.core.util.JsonUtils;
import io.agentscope.extensions.model.openai.dto.OpenAIMessage;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** Tests for content-block cache control in OpenAI-compatible formatters. */
class OpenAICacheControlTest {

    private static final Map<String, String> EPHEMERAL = Map.of("type", "ephemeral");

    private OpenAIChatFormatter formatter;

    @BeforeEach
    void setUp() {
        formatter = new OpenAIChatFormatter();
    }

    @Test
    @DisplayName("automatic strategy marks system messages and the final message")
    void automaticStrategy() {
        List<Msg> messages =
                List.of(
                        message(MsgRole.SYSTEM, "You are helpful."),
                        message(MsgRole.USER, "Hello"),
                        message(MsgRole.ASSISTANT, "Hi"),
                        message(MsgRole.USER, "Question"));

        List<OpenAIMessage> result =
                formatter.format(messages, GenerateOptions.builder().cacheControl(true).build());

        assertEquals(4, result.size());
        assertMarker(result.get(0), EPHEMERAL);
        assertNoMarker(result.get(1));
        assertNoMarker(result.get(2));
        assertMarker(result.get(3), EPHEMERAL);
    }

    @Test
    @DisplayName("automatic strategy is disabled unless cacheControl is true")
    void automaticStrategyDisabled() {
        List<Msg> messages =
                List.of(
                        message(MsgRole.SYSTEM, "You are helpful."),
                        message(MsgRole.USER, "Question"));

        List<OpenAIMessage> result = formatter.format(messages, null);

        assertEquals(2, result.size());
        assertNoMarker(result.get(0));
        assertNoMarker(result.get(1));
        assertTrue(result.get(0).getContent() instanceof String);
    }

    @Test
    @DisplayName("explicit true metadata is applied without the global option")
    void explicitTrueMetadata() {
        Msg msg = message(MsgRole.USER, "Important context", true);

        List<OpenAIMessage> result = formatter.format(List.of(msg), null);

        assertMarker(result.get(0), EPHEMERAL);
    }

    @Test
    @DisplayName("explicit false metadata blocks automatic marking without a serialized sentinel")
    void explicitFalseMetadata() {
        Msg system = message(MsgRole.SYSTEM, "Stable prompt", false);
        Msg user = message(MsgRole.USER, "Question");

        List<OpenAIMessage> result =
                formatter.format(
                        List.of(system, user),
                        GenerateOptions.builder().cacheControl(true).build());

        assertNoMarker(result.get(0));
        assertTrue(result.get(0).getContent() instanceof String);
        assertMarker(result.get(1), EPHEMERAL);
    }

    @Test
    @DisplayName("automatic strategy respects the provider four-marker limit")
    void markerLimit() {
        List<Msg> messages = new ArrayList<>();
        for (int i = 0; i < 5; i++) {
            messages.add(message(MsgRole.SYSTEM, "System " + i));
        }
        messages.add(message(MsgRole.USER, "Question"));

        List<OpenAIMessage> result =
                formatter.format(messages, GenerateOptions.builder().cacheControl(true).build());

        assertEquals(4, countMarkers(result));
        assertMarker(result.get(2), EPHEMERAL);
        assertNoMarker(result.get(3));
        assertNoMarker(result.get(4));
        assertMarker(result.get(5), EPHEMERAL);
    }

    @Test
    @DisplayName("multi-agent formatter preserves explicit directives")
    void multiAgentDirectives() {
        OpenAIMultiAgentFormatter multiFormatter = new OpenAIMultiAgentFormatter();
        Msg system = message(MsgRole.SYSTEM, "Stable prompt", false);
        Msg user = message(MsgRole.USER, "Question", true);

        List<OpenAIMessage> result =
                multiFormatter.format(
                        List.of(system, user),
                        GenerateOptions.builder().cacheControl(true).build());

        assertNoMarker(result.get(0));
        assertMarker(result.get(1), EPHEMERAL);
    }

    @Test
    @DisplayName("multi-agent formatter lets false metadata block the merged final message")
    void multiAgentMergedFalseMetadata() {
        OpenAIMultiAgentFormatter multiFormatter = new OpenAIMultiAgentFormatter();
        Msg user = message(MsgRole.USER, "Do not cache", false);

        List<OpenAIMessage> result =
                multiFormatter.format(
                        List.of(user), GenerateOptions.builder().cacheControl(true).build());

        assertEquals(1, result.size());
        assertNoMarker(result.get(0));
    }

    private static Msg message(MsgRole role, String text) {
        return message(role, text, null);
    }

    private static Msg message(MsgRole role, String text, Boolean cacheControl) {
        Map<String, Object> metadata = new HashMap<>();
        if (cacheControl != null) {
            metadata.put(MessageMetadataKeys.CACHE_CONTROL, cacheControl);
        }
        return Msg.builder().role(role).textContent(text).metadata(metadata).build();
    }

    private static void assertMarker(OpenAIMessage message, Map<String, String> expected) {
        Map<String, Object> payload = serialize(message);
        assertFalse(payload.containsKey("cache_control"));
        assertTrue(payload.get("content") instanceof List<?>);
        List<?> content = (List<?>) payload.get("content");
        assertFalse(content.isEmpty());
        Map<?, ?> lastPart = (Map<?, ?>) content.get(content.size() - 1);
        assertEquals(expected, lastPart.get("cache_control"));
    }

    private static void assertNoMarker(OpenAIMessage message) {
        Map<String, Object> payload = serialize(message);
        assertFalse(payload.containsKey("cache_control"));
        Object content = payload.get("content");
        if (content instanceof List<?> parts) {
            for (Object part : parts) {
                if (part instanceof Map<?, ?> partMap) {
                    assertFalse(partMap.containsKey("cache_control"));
                }
            }
        }
    }

    private static int countMarkers(List<OpenAIMessage> messages) {
        int count = 0;
        for (OpenAIMessage message : messages) {
            Map<String, Object> payload = serialize(message);
            if (payload.get("content") instanceof List<?> parts) {
                for (Object part : parts) {
                    if (part instanceof Map<?, ?> partMap && partMap.containsKey("cache_control")) {
                        count++;
                        break;
                    }
                }
            }
        }
        return count;
    }

    private static Map<String, Object> serialize(OpenAIMessage message) {
        return JsonUtils.getJsonCodec()
                .fromJson(
                        JsonUtils.getJsonCodec().toJson(message),
                        new TypeReference<Map<String, Object>>() {});
    }
}

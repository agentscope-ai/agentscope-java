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
package io.agentscope.core.message;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;

import io.agentscope.core.model.ChatUsage;
import io.agentscope.core.util.JsonUtils;
import org.junit.jupiter.api.Test;

class MsgUsageSerializationTest {

    @Test
    void usageFieldRoundTripsViaJson() {
        ChatUsage usage = new ChatUsage(100, 50, 1.5);
        Msg msg =
                Msg.builder()
                        .name("assistant")
                        .role(MsgRole.ASSISTANT)
                        .textContent("hello")
                        .usage(usage)
                        .build();

        assertEquals(100, msg.getUsage().getInputTokens());
        assertEquals(50, msg.getUsage().getOutputTokens());

        String json = JsonUtils.getJsonCodec().toJson(msg);
        Msg deserialized = JsonUtils.getJsonCodec().fromJson(json, Msg.class);

        assertNotNull(deserialized.getUsage());
        assertEquals(100, deserialized.getUsage().getInputTokens());
        assertEquals(50, deserialized.getUsage().getOutputTokens());
    }

    @Test
    void cachedTokensRoundTripViaJson() {
        ChatUsage usage =
                ChatUsage.builder().inputTokens(100).outputTokens(50).cachedTokens(30).build();
        Msg msg =
                Msg.builder()
                        .name("assistant")
                        .role(MsgRole.ASSISTANT)
                        .textContent("hello")
                        .usage(usage)
                        .build();

        assertEquals(30, msg.getUsage().getCachedTokens());

        String json = JsonUtils.getJsonCodec().toJson(msg);
        Msg deserialized = JsonUtils.getJsonCodec().fromJson(json, Msg.class);

        assertNotNull(deserialized.getUsage());
        assertEquals(100, deserialized.getUsage().getInputTokens());
        assertEquals(50, deserialized.getUsage().getOutputTokens());
        assertEquals(30, deserialized.getUsage().getCachedTokens());
    }

    @Test
    void cachedTokensDefaultsToZeroForLegacyConstructor() {
        ChatUsage usage = new ChatUsage(100, 50, 1.5);
        assertEquals(0, usage.getCachedTokens());
    }

    @Test
    void usageNullByDefault() {
        Msg msg = Msg.builder().name("user").role(MsgRole.USER).textContent("hi").build();

        assertNull(msg.getUsage());
    }

    @Test
    void getChatUsagePrefersDirectField() {
        ChatUsage usage = new ChatUsage(200, 100, 2.0);
        Msg msg =
                Msg.builder()
                        .name("assistant")
                        .role(MsgRole.ASSISTANT)
                        .textContent("reply")
                        .usage(usage)
                        .build();

        ChatUsage retrieved = msg.getChatUsage();
        assertNotNull(retrieved);
        assertEquals(200, retrieved.getInputTokens());
        assertEquals(100, retrieved.getOutputTokens());
    }

    @Test
    void getChatUsageFallsBackToMetadata() {
        ChatUsage usage = new ChatUsage(300, 150, 3.0);
        Msg msg =
                Msg.builder()
                        .name("assistant")
                        .role(MsgRole.ASSISTANT)
                        .textContent("reply")
                        .metadata(java.util.Map.of(MessageMetadataKeys.CHAT_USAGE, usage))
                        .build();

        assertNull(msg.getUsage());
        ChatUsage retrieved = msg.getChatUsage();
        assertNotNull(retrieved);
        assertEquals(300, retrieved.getInputTokens());
    }

    @Test
    void usageDeserializesFromJsonWithoutField() {
        String json =
                "{\"id\":\"test-1\",\"name\":\"user\",\"role\":\"USER\","
                        + "\"content\":[{\"type\":\"text\",\"text\":\"hello\"}],"
                        + "\"metadata\":{}}";
        Msg msg = JsonUtils.getJsonCodec().fromJson(json, Msg.class);
        assertNull(msg.getUsage());
    }
}

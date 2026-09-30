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
package io.agentscope.extensions.channel.feishu;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.agentscope.core.message.Msg;
import io.agentscope.extensions.channel.common.ChannelMediaMetadata;
import io.agentscope.harness.agent.gateway.channel.InboundMessage;
import io.agentscope.harness.agent.gateway.channel.PeerKind;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.Test;

/** Tests for {@link FeishuInboundMapper} text and media message mapping. */
class FeishuInboundMapperTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private final FeishuInboundMapper mapper = new FeishuInboundMapper("feishu");

    private static JsonNode envelope(String messageType, String contentJson) throws Exception {
        return envelope(messageType, contentJson, "p2p");
    }

    private static JsonNode envelope(String messageType, String contentJson, String chatType)
            throws Exception {
        String contentField = contentJson == null ? "" : contentJson;
        return MAPPER.readTree(
                "{\"schema\":\"2.0\","
                    + "\"header\":{\"event_id\":\"evt-1\",\"event_type\":\"im.message.receive_v1\","
                    + "\"tenant_key\":\"tenant-1\"},\"event\":{"
                    + "\"sender\":{\"sender_id\":{\"open_id\":\"ou-alice\"},\"sender_type\":\"user\"},"
                    + "\"message\":{\"message_id\":\"om-1\",\"chat_id\":\"oc-1\",\"chat_type\":\""
                        + chatType
                        + "\","
                        + "\"message_type\":\""
                        + messageType
                        + "\","
                        + "\"content\":"
                        + jsonString(contentField)
                        + "}"
                        + "}"
                        + "}");
    }

    private static String jsonString(String raw) {
        return "\"" + raw.replace("\\", "\\\\").replace("\"", "\\\"") + "\"";
    }

    private InboundMessage map(JsonNode envelope) {
        Optional<InboundMessage> inbound = mapper.map(envelope);
        assertTrue(inbound.isPresent(), "expected a mapped message");
        return inbound.get();
    }

    @Test
    void mapsTextMessage() throws Exception {
        InboundMessage inbound = map(envelope("text", "{\"text\":\"hello\"}"));
        Msg msg = inbound.messages().get(0);
        assertEquals("hello", msg.getTextContent());
        assertEquals("om-1", msg.getMetadata().get("channelMessageId"));
        assertEquals(PeerKind.DIRECT, inbound.peer().kind());
        assertEquals("oc-1", inbound.peer().id());
        assertEquals("ou-alice", inbound.senderId());
        assertEquals("tenant-1", inbound.accountId());
    }

    @Test
    void mapsImageByKey() throws Exception {
        InboundMessage inbound = map(envelope("image", "{\"image_key\":\"img_v2_1\"}"));
        Msg msg = inbound.messages().get(0);
        assertEquals("[image]", msg.getTextContent());
        Map<String, Object> metadata = msg.getMetadata();
        assertEquals(ChannelMediaMetadata.KIND_IMAGE, metadata.get(ChannelMediaMetadata.KIND));
        assertEquals("image", metadata.get(ChannelMediaMetadata.PROVIDER_TYPE));
        assertEquals("img_v2_1", metadata.get(ChannelMediaMetadata.ID));
        assertEquals("om-1", metadata.get("channelMessageId"));
    }

    @Test
    void mapsFileWithName() throws Exception {
        InboundMessage inbound =
                map(envelope("file", "{\"file_key\":\"file_v2_1\",\"file_name\":\"test.txt\"}"));
        Msg msg = inbound.messages().get(0);
        assertEquals("[file: test.txt]", msg.getTextContent());
        Map<String, Object> metadata = msg.getMetadata();
        assertEquals(ChannelMediaMetadata.KIND_FILE, metadata.get(ChannelMediaMetadata.KIND));
        assertEquals("file_v2_1", metadata.get(ChannelMediaMetadata.ID));
        assertEquals("test.txt", metadata.get(ChannelMediaMetadata.FILE_NAME));
    }

    @Test
    void mapsAudioWithDuration() throws Exception {
        InboundMessage inbound =
                map(envelope("audio", "{\"file_key\":\"file_v2_2\",\"duration\":2000}"));
        Msg msg = inbound.messages().get(0);
        assertEquals("[audio]", msg.getTextContent());
        Map<String, Object> metadata = msg.getMetadata();
        assertEquals(ChannelMediaMetadata.KIND_AUDIO, metadata.get(ChannelMediaMetadata.KIND));
        assertEquals("file_v2_2", metadata.get(ChannelMediaMetadata.ID));
        assertEquals("2000", metadata.get(ChannelMediaMetadata.DURATION_MS));
    }

    @Test
    void mapsMediaAsVideoWithCover() throws Exception {
        InboundMessage inbound =
                map(
                        envelope(
                                "media",
                                "{\"file_key\":\"file_v2_3\",\"image_key\":\"img_cover\","
                                        + "\"file_name\":\"clip.mp4\",\"duration\":5000}"));
        Msg msg = inbound.messages().get(0);
        assertEquals("[video: clip.mp4]", msg.getTextContent());
        Map<String, Object> metadata = msg.getMetadata();
        assertEquals(ChannelMediaMetadata.KIND_VIDEO, metadata.get(ChannelMediaMetadata.KIND));
        assertEquals("media", metadata.get(ChannelMediaMetadata.PROVIDER_TYPE));
        assertEquals("file_v2_3", metadata.get(ChannelMediaMetadata.ID));
        assertEquals("img_cover", metadata.get(ChannelMediaMetadata.SECONDARY_ID));
        assertEquals("clip.mp4", metadata.get(ChannelMediaMetadata.FILE_NAME));
        assertEquals("5000", metadata.get(ChannelMediaMetadata.DURATION_MS));
    }

    @Test
    void mapsFolderAsFile() throws Exception {
        InboundMessage inbound =
                map(envelope("folder", "{\"file_key\":\"file_v2_4\",\"file_name\":\"docs\"}"));
        Msg msg = inbound.messages().get(0);
        assertEquals("[file: docs]", msg.getTextContent());
        Map<String, Object> metadata = msg.getMetadata();
        assertEquals(ChannelMediaMetadata.KIND_FILE, metadata.get(ChannelMediaMetadata.KIND));
        assertEquals("folder", metadata.get(ChannelMediaMetadata.PROVIDER_TYPE));
        assertEquals("file_v2_4", metadata.get(ChannelMediaMetadata.ID));
    }

    @Test
    void mapsGroupMediaToGroupPeer() throws Exception {
        InboundMessage inbound = map(envelope("image", "{\"image_key\":\"img_v2_5\"}", "group"));
        assertEquals(PeerKind.GROUP, inbound.peer().kind());
        assertEquals("oc-1", inbound.peer().id());
        assertEquals("ou-alice", inbound.senderId());
    }

    @Test
    void dropsNonMediaTypes() throws Exception {
        assertTrue(mapper.map(envelope("post", "{\"content\":[]}")).isEmpty());
        assertTrue(mapper.map(envelope("sticker", "{\"file_key\":\"fs_1\"}")).isEmpty());
        assertTrue(
                mapper.map(envelope("interactive", "{\"elements\":[{\"tag\":\"div\"}]}"))
                        .isEmpty());
    }

    @Test
    void dropsMediaWithoutMaterialId() throws Exception {
        assertTrue(mapper.map(envelope("image", "{}")).isEmpty());
        assertTrue(mapper.map(envelope("file", "{\"file_name\":\"orphan.txt\"}")).isEmpty());
    }
}

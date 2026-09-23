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
package io.agentscope.extensions.channel.dingtalk;

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

/** Tests for {@link DingTalkInboundMapper} text and media message mapping. */
class DingTalkInboundMapperTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private final DingTalkInboundMapper mapper = new DingTalkInboundMapper("dingtalk", "app-1");

    private static JsonNode payload(String body) throws Exception {
        return MAPPER.readTree(
                "{"
                        + "\"conversationId\":\"cid-1\","
                        + "\"conversationType\":\"1\","
                        + "\"msgId\":\"msg-1\","
                        + "\"senderStaffId\":\"staff-alice\","
                        + "\"senderId\":\"sender-1\","
                        + body
                        + "}");
    }

    private InboundMessage map(JsonNode payload) {
        Optional<InboundMessage> inbound = mapper.map(payload);
        assertTrue(inbound.isPresent(), "expected a mapped message");
        return inbound.get();
    }

    @Test
    void mapsTextMessage() throws Exception {
        InboundMessage inbound =
                map(payload("\"msgtype\":\"text\",\"text\":{\"content\":\"hello\"}"));
        Msg msg = inbound.messages().get(0);
        assertEquals("hello", msg.getTextContent());
        assertEquals("msg-1", msg.getMetadata().get("channelMessageId"));
        assertEquals(PeerKind.DIRECT, inbound.peer().kind());
        assertEquals("staff-alice", inbound.peer().id());
        assertEquals("staff-alice", inbound.senderId());
        assertEquals("app-1", inbound.accountId());
    }

    @Test
    void mapsPictureWithTopLevelFields() throws Exception {
        InboundMessage inbound =
                map(
                        payload(
                                "\"msgtype\":\"picture\","
                                        + "\"downloadCode\":\"dl-1\","
                                        + "\"pictureDownloadCode\":\"pic-dl-1\""));
        Msg msg = inbound.messages().get(0);
        assertEquals("[image]", msg.getTextContent());
        Map<String, Object> metadata = msg.getMetadata();
        assertEquals(ChannelMediaMetadata.KIND_IMAGE, metadata.get(ChannelMediaMetadata.KIND));
        assertEquals("picture", metadata.get(ChannelMediaMetadata.PROVIDER_TYPE));
        assertEquals("dl-1", metadata.get(ChannelMediaMetadata.ID));
        assertEquals("pic-dl-1", metadata.get(ChannelMediaMetadata.SECONDARY_ID));
        assertEquals("msg-1", metadata.get("channelMessageId"));
    }

    @Test
    void mapsPictureWithContentNestedFields() throws Exception {
        InboundMessage inbound =
                map(
                        payload(
                                "\"msgtype\":\"picture\","
                                        + "\"content\":{"
                                        + "\"downloadCode\":\"dl-2\","
                                        + "\"pictureDownloadCode\":\"pic-dl-2\""
                                        + "}"));
        Msg msg = inbound.messages().get(0);
        assertEquals("[image]", msg.getTextContent());
        assertEquals("dl-2", msg.getMetadata().get(ChannelMediaMetadata.ID));
        assertEquals("pic-dl-2", msg.getMetadata().get(ChannelMediaMetadata.SECONDARY_ID));
    }

    @Test
    void mapsAudioWithRecognitionAndDuration() throws Exception {
        InboundMessage inbound =
                map(
                        payload(
                                "\"msgtype\":\"audio\","
                                        + "\"downloadCode\":\"dl-3\","
                                        + "\"recognition\":\"你好\","
                                        + "\"duration\":1800"));
        Msg msg = inbound.messages().get(0);
        assertEquals("[audio]", msg.getTextContent());
        Map<String, Object> metadata = msg.getMetadata();
        assertEquals(ChannelMediaMetadata.KIND_AUDIO, metadata.get(ChannelMediaMetadata.KIND));
        assertEquals("dl-3", metadata.get(ChannelMediaMetadata.ID));
        assertEquals("你好", metadata.get(ChannelMediaMetadata.RECOGNITION));
        assertEquals("1800", metadata.get(ChannelMediaMetadata.DURATION_MS));
    }

    @Test
    void mapsVideoWithTypeAndDuration() throws Exception {
        InboundMessage inbound =
                map(
                        payload(
                                "\"msgtype\":\"video\","
                                        + "\"downloadCode\":\"dl-4\","
                                        + "\"videoType\":\"mp4\","
                                        + "\"duration\":5000"));
        Msg msg = inbound.messages().get(0);
        assertEquals("[video]", msg.getTextContent());
        Map<String, Object> metadata = msg.getMetadata();
        assertEquals(ChannelMediaMetadata.KIND_VIDEO, metadata.get(ChannelMediaMetadata.KIND));
        assertEquals("dl-4", metadata.get(ChannelMediaMetadata.ID));
        assertEquals("mp4", metadata.get(ChannelMediaMetadata.FORMAT));
        assertEquals("5000", metadata.get(ChannelMediaMetadata.DURATION_MS));
    }

    @Test
    void mapsFileWithName() throws Exception {
        InboundMessage inbound =
                map(
                        payload(
                                "\"msgtype\":\"file\",\"downloadCode\":\"dl-5\",\"fileName\":\"r.zip\""));
        Msg msg = inbound.messages().get(0);
        assertEquals("[file: r.zip]", msg.getTextContent());
        Map<String, Object> metadata = msg.getMetadata();
        assertEquals(ChannelMediaMetadata.KIND_FILE, metadata.get(ChannelMediaMetadata.KIND));
        assertEquals("dl-5", metadata.get(ChannelMediaMetadata.ID));
        assertEquals("r.zip", metadata.get(ChannelMediaMetadata.FILE_NAME));
    }

    @Test
    void mapsGroupMediaToGroupPeer() throws Exception {
        JsonNode node =
                MAPPER.readTree(
                        "{"
                                + "\"conversationId\":\"cid-9\","
                                + "\"conversationType\":\"2\","
                                + "\"msgId\":\"msg-9\","
                                + "\"senderStaffId\":\"staff-bob\","
                                + "\"msgtype\":\"picture\","
                                + "\"downloadCode\":\"dl-9\""
                                + "}");
        InboundMessage inbound = map(node);
        assertEquals(PeerKind.GROUP, inbound.peer().kind());
        assertEquals("cid-9", inbound.peer().id());
        assertEquals("staff-bob", inbound.senderId());
        assertEquals("[image]", inbound.messages().get(0).getTextContent());
    }

    @Test
    void dropsNonMediaTypes() throws Exception {
        assertTrue(
                mapper.map(
                                payload(
                                        "\"msgtype\":\"richText\","
                                                + "\"content\":{\"richText\":[{\"text\":\"hi\"}]}"))
                        .isEmpty());
        assertTrue(mapper.map(payload("\"msgtype\":\"unknownMsgType\"")).isEmpty());
        assertTrue(mapper.map(payload("\"msgtype\":\"file\"")).isEmpty());
    }

    @Test
    void dropsMediaWithoutDownloadCode() throws Exception {
        assertTrue(mapper.map(payload("\"msgtype\":\"picture\"")).isEmpty());
        assertTrue(
                mapper.map(
                                payload(
                                        "\"msgtype\":\"file\","
                                                + "\"content\":{\"fileName\":\"orphan.txt\"}"))
                        .isEmpty());
    }
}

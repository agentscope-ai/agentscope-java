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
package io.agentscope.extensions.channel.wecom;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.agentscope.core.message.Msg;
import io.agentscope.extensions.channel.common.ChannelMediaMetadata;
import io.agentscope.harness.agent.gateway.channel.InboundMessage;
import io.agentscope.harness.agent.gateway.channel.PeerKind;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.Test;

/** Tests for {@link WeComInboundMapper} text and media message mapping. */
class WeComInboundMapperTest {

    private final WeComInboundMapper mapper = new WeComInboundMapper("wecom", "corp-1");

    private static String xml(String msgType, String extra) {
        return "<xml><ToUserName><![CDATA[corp-1]]></ToUserName>"
                + "<FromUserName><![CDATA[alice]]></FromUserName>"
                + "<CreateTime>1348831860</CreateTime>"
                + "<MsgType><![CDATA["
                + msgType
                + "]]></MsgType>"
                + extra
                + "<MsgId>1234567890123456</MsgId>"
                + "<AgentID>1</AgentID></xml>";
    }

    private InboundMessage map(String xml) {
        Optional<InboundMessage> inbound = mapper.map(xml);
        assertTrue(inbound.isPresent(), "expected a mapped message: " + xml);
        return inbound.get();
    }

    @Test
    void mapsTextMessage() {
        InboundMessage inbound = map(xml("text", "<Content><![CDATA[hello]]></Content>"));
        Msg msg = inbound.messages().get(0);
        assertEquals("hello", msg.getTextContent());
        assertEquals("1234567890123456", msg.getMetadata().get("channelMessageId"));
        assertEquals(PeerKind.DIRECT, inbound.peer().kind());
        assertEquals("alice", inbound.peer().id());
        assertEquals("alice", inbound.senderId());
        assertEquals("corp-1", inbound.accountId());
    }

    @Test
    void mapsImageWithPicUrlAndMediaId() {
        InboundMessage inbound =
                map(
                        xml(
                                "image",
                                "<PicUrl><![CDATA[https://pic.example/a.jpg]]></PicUrl>"
                                        + "<MediaId><![CDATA[media-1]]></MediaId>"));
        Msg msg = inbound.messages().get(0);
        assertEquals("[image]", msg.getTextContent());
        Map<String, Object> metadata = msg.getMetadata();
        assertEquals(ChannelMediaMetadata.KIND_IMAGE, metadata.get(ChannelMediaMetadata.KIND));
        assertEquals("image", metadata.get(ChannelMediaMetadata.PROVIDER_TYPE));
        assertEquals("media-1", metadata.get(ChannelMediaMetadata.ID));
        assertEquals("https://pic.example/a.jpg", metadata.get(ChannelMediaMetadata.URL));
        assertEquals("1234567890123456", metadata.get("channelMessageId"));
    }

    @Test
    void mapsVoiceAsAudioWithFormat() {
        InboundMessage inbound =
                map(
                        xml(
                                "voice",
                                "<MediaId><![CDATA[media-2]]></MediaId>"
                                        + "<Format><![CDATA[amr]]></Format>"));
        Msg msg = inbound.messages().get(0);
        assertEquals("[audio]", msg.getTextContent());
        assertEquals(
                ChannelMediaMetadata.KIND_AUDIO, msg.getMetadata().get(ChannelMediaMetadata.KIND));
        assertEquals("media-2", msg.getMetadata().get(ChannelMediaMetadata.ID));
        assertEquals("amr", msg.getMetadata().get(ChannelMediaMetadata.FORMAT));
    }

    @Test
    void mapsVideoWithThumbnail() {
        InboundMessage inbound =
                map(
                        xml(
                                "video",
                                "<MediaId><![CDATA[media-3]]></MediaId>"
                                        + "<ThumbMediaId><![CDATA[thumb-1]]></ThumbMediaId>"));
        Msg msg = inbound.messages().get(0);
        assertEquals("[video]", msg.getTextContent());
        assertEquals(
                ChannelMediaMetadata.KIND_VIDEO, msg.getMetadata().get(ChannelMediaMetadata.KIND));
        assertEquals("media-3", msg.getMetadata().get(ChannelMediaMetadata.ID));
        assertEquals("thumb-1", msg.getMetadata().get(ChannelMediaMetadata.SECONDARY_ID));
    }

    @Test
    void dropsNonMediaTypes() {
        assertTrue(
                mapper.map(
                                xml(
                                        "location",
                                        "<Location_X>31.2</Location_X><Location_Y>121.4</Location_Y>"
                                            + "<Scale>15</Scale><Label><![CDATA[poi]]></Label>"))
                        .isEmpty());
        assertTrue(
                mapper.map(
                                xml(
                                        "link",
                                        "<Title><![CDATA[t]]></Title>"
                                                + "<Description><![CDATA[d]]></Description>"
                                                + "<Url><![CDATA[https://example.com]]></Url>"))
                        .isEmpty());
        assertTrue(
                mapper.map(
                                xml(
                                        "event",
                                        "<Event><![CDATA[subscribe]]></Event>"
                                                + "<EventKey><![CDATA[]]></EventKey>"))
                        .isEmpty());
    }

    @Test
    void dropsMediaWithoutMediaId() {
        assertTrue(
                mapper.map(xml("image", "<PicUrl><![CDATA[https://pic.example/a.jpg]]></PicUrl>"))
                        .isEmpty());
    }
}

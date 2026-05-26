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
package io.agentscope.claw2.runtime.channel.feishu;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.agentscope.claw2.runtime.channel.InboundMessage;
import io.agentscope.claw2.runtime.channel.PeerKind;
import java.util.Optional;
import org.junit.jupiter.api.Test;

class FeishuInboundMapperTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    @Test
    void mapsP2pTextMessageAsDirectPeer() throws Exception {
        String envelope =
                "{"
                        + "\"schema\":\"2.0\","
                        + "\"header\":{"
                        + "  \"event_id\":\"ev_p2p_1\","
                        + "  \"event_type\":\"im.message.receive_v1\","
                        + "  \"tenant_key\":\"tk_xyz\""
                        + "},"
                        + "\"event\":{"
                        + "  \"sender\":{\"sender_id\":{\"open_id\":\"ou_user1\"}},"
                        + "  \"message\":{"
                        + "    \"message_id\":\"om_1\","
                        + "    \"chat_id\":\"oc_p2p_chat\","
                        + "    \"chat_type\":\"p2p\","
                        + "    \"message_type\":\"text\","
                        + "    \"content\":\"{\\\"text\\\":\\\"hello from p2p\\\"}\""
                        + "  }"
                        + "}"
                        + "}";

        JsonNode node = MAPPER.readTree(envelope);
        FeishuInboundMapper mapper = new FeishuInboundMapper("feishu-dev");
        Optional<InboundMessage> result = mapper.map(node);

        assertTrue(result.isPresent(), "p2p text should map");
        InboundMessage in = result.get();
        assertEquals("feishu-dev", in.channelId());
        assertEquals(PeerKind.DIRECT, in.peer().kind());
        assertEquals("oc_p2p_chat", in.peer().id());
        assertEquals("ou_user1", in.senderId());
        assertEquals("tk_xyz", in.accountId());
        assertEquals(1, in.messages().size());
        assertEquals("hello from p2p", in.messages().get(0).getTextContent());
    }

    @Test
    void mapsGroupTextMessageAsGroupPeer() throws Exception {
        String envelope =
                "{"
                        + "\"schema\":\"2.0\","
                        + "\"header\":{\"event_id\":\"ev_grp_1\",\"tenant_key\":\"t1\"},"
                        + "\"event\":{"
                        + "  \"sender\":{\"sender_id\":{\"open_id\":\"ou_user2\"}},"
                        + "  \"message\":{"
                        + "    \"message_id\":\"om_2\","
                        + "    \"chat_id\":\"oc_group_abc\","
                        + "    \"chat_type\":\"group\","
                        + "    \"message_type\":\"text\","
                        + "    \"content\":\"{\\\"text\\\":\\\"@_user_1 hi\\\"}\""
                        + "  }"
                        + "}"
                        + "}";

        JsonNode node = MAPPER.readTree(envelope);
        FeishuInboundMapper mapper = new FeishuInboundMapper("feishu-dev");
        Optional<InboundMessage> result = mapper.map(node);

        assertTrue(result.isPresent(), "group text should map");
        InboundMessage in = result.get();
        assertEquals(PeerKind.GROUP, in.peer().kind());
        assertEquals("oc_group_abc", in.peer().id());
        assertEquals("ou_user2", in.senderId());
        assertEquals("@_user_1 hi", in.messages().get(0).getTextContent());
    }

    @Test
    void skipsNonTextMessages() throws Exception {
        String envelope =
                "{\"event\":{\"message\":{"
                        + "\"chat_id\":\"oc_x\",\"chat_type\":\"p2p\","
                        + "\"message_type\":\"image\","
                        + "\"content\":\"{\\\"image_key\\\":\\\"img_v2_xxx\\\"}\""
                        + "}}}";
        JsonNode node = MAPPER.readTree(envelope);
        FeishuInboundMapper mapper = new FeishuInboundMapper("feishu-dev");
        assertTrue(mapper.map(node).isEmpty());
    }

    @Test
    void extractsUrlChallenge() throws Exception {
        String challenge =
                "{\"type\":\"url_verification\",\"challenge\":\"ch_xyz\",\"token\":\"tk_y\"}";
        JsonNode node = MAPPER.readTree(challenge);
        Optional<String> result = FeishuInboundMapper.extractUrlChallenge(node);
        assertTrue(result.isPresent());
        assertEquals("ch_xyz", result.get());
    }

    @Test
    void extractsEventIdAndSenderOpenId() throws Exception {
        String envelope =
                "{\"header\":{\"event_id\":\"ev_99\"},"
                        + "\"event\":{\"sender\":{\"sender_id\":{\"open_id\":\"ou_bot\"}}}}";
        JsonNode node = MAPPER.readTree(envelope);
        assertEquals("ev_99", FeishuInboundMapper.extractEventId(node).orElseThrow());
        assertEquals("ou_bot", FeishuInboundMapper.extractSenderOpenId(node).orElseThrow());
    }

    @Test
    void extractsMissingFieldsAsEmpty() throws Exception {
        JsonNode node = MAPPER.readTree("{}");
        assertTrue(FeishuInboundMapper.extractEventId(node).isEmpty());
        assertTrue(FeishuInboundMapper.extractSenderOpenId(node).isEmpty());
        assertTrue(FeishuInboundMapper.extractUrlChallenge(node).isEmpty());
        assertTrue(FeishuInboundMapper.extractTenantKey(node).isEmpty());
        assertNotNull(node);
    }
}

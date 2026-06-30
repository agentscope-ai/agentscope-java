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
package io.agentscope.harness.agent.gateway.channel;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.agentscope.core.message.Msg;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class InboundMessageBusinessContextTest {

    private static final String CHANNEL = "test";
    private static final String PEER_ID = "user-1";
    private static final String SENDER_ID = "user-1";
    private static final List<Msg> MSGS = List.of();

    @Test
    void dm_factory_hasEmptyBusinessContext() {
        InboundMessage msg = InboundMessage.dm(CHANNEL, PEER_ID, MSGS);
        assertNotNull(msg.businessContext());
        assertTrue(msg.businessContext().isEmpty());
    }

    @Test
    void dmFor_factory_hasEmptyBusinessContext() {
        InboundMessage msg = InboundMessage.dmFor(CHANNEL, PEER_ID, "agent-1", MSGS);
        assertNotNull(msg.businessContext());
        assertTrue(msg.businessContext().isEmpty());
    }

    @Test
    void channel_factory_withSender_hasEmptyBusinessContext() {
        InboundMessage msg = InboundMessage.channel(CHANNEL, "room-1", SENDER_ID, "guild-1", MSGS);
        assertNotNull(msg.businessContext());
        assertTrue(msg.businessContext().isEmpty());
    }

    @Test
    void channel_factory_withoutSender_hasEmptyBusinessContext() {
        InboundMessage msg = InboundMessage.channel(CHANNEL, "room-1", "guild-1", MSGS);
        assertNotNull(msg.businessContext());
        assertTrue(msg.businessContext().isEmpty());
    }

    @Test
    void builder_setsBusinessContext() {
        Map<String, Object> ctx = Map.of("tenantId", "tenant-123", "modelConfigId", "gpt-4");
        InboundMessage msg =
                InboundMessage.builder(CHANNEL, Peer.direct(PEER_ID), MSGS)
                        .businessContext(ctx)
                        .build();
        assertEquals("tenant-123", msg.businessContext().get("tenantId"));
        assertEquals("gpt-4", msg.businessContext().get("modelConfigId"));
    }

    @Test
    void builder_putBusinessParam_addsSingleParam() {
        InboundMessage msg =
                InboundMessage.builder(CHANNEL, Peer.direct(PEER_ID), MSGS)
                        .putBusinessParam("tenantId", "tenant-123")
                        .build();
        assertEquals("tenant-123", msg.businessContext().get("tenantId"));
    }

    @Test
    void builder_putBusinessParam_multipleParams() {
        InboundMessage msg =
                InboundMessage.builder(CHANNEL, Peer.direct(PEER_ID), MSGS)
                        .putBusinessParam("tenantId", "tenant-123")
                        .putBusinessParam("modelConfigId", "gpt-4")
                        .putBusinessParam("maxTokens", 4096)
                        .build();
        assertEquals("tenant-123", msg.businessContext().get("tenantId"));
        assertEquals("gpt-4", msg.businessContext().get("modelConfigId"));
        assertEquals(4096, msg.businessContext().get("maxTokens"));
        assertEquals(3, msg.businessContext().size());
    }

    @Test
    void builder_putBusinessParam_nullKey_ignored() {
        InboundMessage msg =
                InboundMessage.builder(CHANNEL, Peer.direct(PEER_ID), MSGS)
                        .putBusinessParam(null, "value")
                        .build();
        assertTrue(msg.businessContext().isEmpty());
    }

    @Test
    void builder_putBusinessParam_nullValue_removesKey() {
        InboundMessage msg =
                InboundMessage.builder(CHANNEL, Peer.direct(PEER_ID), MSGS)
                        .putBusinessParam("tenantId", "tenant-123")
                        .putBusinessParam("tenantId", null)
                        .build();
        assertTrue(msg.businessContext().isEmpty());
    }

    @Test
    void builder_businessContext_null_defaultsToEmpty() {
        InboundMessage msg =
                InboundMessage.builder(CHANNEL, Peer.direct(PEER_ID), MSGS)
                        .businessContext(null)
                        .build();
        assertNotNull(msg.businessContext());
        assertTrue(msg.businessContext().isEmpty());
    }

    @Test
    void businessContext_thenPutBusinessParam_works() {
        // businessContext(Map) followed by putBusinessParam — the Map.of result is not a HashMap
        InboundMessage msg =
                InboundMessage.builder(CHANNEL, Peer.direct(PEER_ID), MSGS)
                        .businessContext(Map.of("a", "1"))
                        .putBusinessParam("b", "2")
                        .build();
        assertEquals("1", msg.businessContext().get("a"));
        assertEquals("2", msg.businessContext().get("b"));
        assertEquals(2, msg.businessContext().size());
    }

    @Test
    void putBusinessParam_thenBusinessContext_overrides() {
        InboundMessage msg =
                InboundMessage.builder(CHANNEL, Peer.direct(PEER_ID), MSGS)
                        .putBusinessParam("a", "1")
                        .businessContext(Map.of("b", "2"))
                        .build();
        assertFalse(msg.businessContext().containsKey("a"));
        assertEquals("2", msg.businessContext().get("b"));
    }

    @Test
    void businessContext_isImmutable() {
        InboundMessage msg =
                InboundMessage.builder(CHANNEL, Peer.direct(PEER_ID), MSGS)
                        .putBusinessParam("key", "value")
                        .build();
        assertThrows(
                UnsupportedOperationException.class, () -> msg.businessContext().put("new", "v"));
    }

    @Test
    void nullBusinessContext_defaultsToEmpty() {
        InboundMessage msg =
                new InboundMessage(
                        CHANNEL,
                        null,
                        Peer.direct(PEER_ID),
                        PEER_ID,
                        null,
                        null,
                        null,
                        null,
                        MSGS,
                        null,
                        null);
        assertNotNull(msg.businessContext());
        assertTrue(msg.businessContext().isEmpty());
    }

    @Test
    void builder_withAllFieldsAndBusinessContext() {
        Map<String, Object> ctx = Map.of("tenantId", "tenant-123");
        InboundMessage msg =
                InboundMessage.builder(CHANNEL, Peer.direct(PEER_ID), MSGS)
                        .senderId(SENDER_ID)
                        .guild("guild-1")
                        .preferredAgentId("agent-1")
                        .businessContext(ctx)
                        .build();
        assertEquals(SENDER_ID, msg.senderId());
        assertEquals("guild-1", msg.guild());
        assertEquals("agent-1", msg.preferredAgentId());
        assertEquals("tenant-123", msg.businessContext().get("tenantId"));
    }
}

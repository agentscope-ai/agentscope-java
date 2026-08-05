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
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.agentscope.harness.agent.gateway.MsgContext;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class RouteResultBusinessContextTest {

    private static final String CHANNEL = "test";
    private static final String PEER_ID = "user-1";

    private ChannelRouter router;
    private ChannelConfig config;

    @BeforeEach
    void setUp() {
        router = new ChannelRouter("default-agent");
        config = ChannelConfig.of("test", "main");
    }

    @Test
    void router_propagatesBusinessContext() {
        InboundMessage msg =
                InboundMessage.builder(CHANNEL, Peer.direct(PEER_ID), List.of())
                        .putBusinessParam("tenantId", "tenant-123")
                        .putBusinessParam("modelConfigId", "gpt-4")
                        .build();

        RouteResult route = router.resolveRoute(config, msg);

        assertEquals("tenant-123", route.context().businessContext().get("tenantId"));
        assertEquals("gpt-4", route.context().businessContext().get("modelConfigId"));
    }

    @Test
    void router_emptyBusinessContext_whenNoneProvided() {
        InboundMessage msg = InboundMessage.dm(CHANNEL, PEER_ID, List.of());
        RouteResult route = router.resolveRoute(config, msg);

        assertNotNull(route.context().businessContext());
        assertTrue(route.context().businessContext().isEmpty());
    }

    @Test
    void router_injectsBusinessContextIntoMsgContext() {
        InboundMessage msg =
                InboundMessage.builder(CHANNEL, Peer.direct(PEER_ID), List.of())
                        .putBusinessParam("tenantId", "tenant-123")
                        .build();

        RouteResult route = router.resolveRoute(config, msg);

        MsgContext ctx = route.context();
        assertEquals("tenant-123", ctx.businessContext().get("tenantId"));
    }

    @Test
    void router_msgContextBusinessContext_doesNotAffectCanonicalKey() {
        String keyWithout =
                router.resolveRoute(config, InboundMessage.dm(CHANNEL, PEER_ID, List.of()))
                        .context()
                        .canonicalKey();

        String keyWith =
                router.resolveRoute(
                                config,
                                InboundMessage.builder(CHANNEL, Peer.direct(PEER_ID), List.of())
                                        .putBusinessParam("tenantId", "tenant-123")
                                        .build())
                        .context()
                        .canonicalKey();

        assertEquals(keyWithout, keyWith);
    }

    @Test
    void router_singleBusinessParam() {
        InboundMessage msg =
                InboundMessage.builder(CHANNEL, Peer.direct(PEER_ID), List.of())
                        .putBusinessParam("tenantId", "single-tenant")
                        .build();

        RouteResult route = router.resolveRoute(config, msg);

        Map<String, Object> bc = route.context().businessContext();
        assertEquals(1, bc.size());
        assertEquals("single-tenant", bc.get("tenantId"));
    }

    @Test
    void router_knowsMatchedBy_andHasBusinessContext() {
        InboundMessage msg =
                InboundMessage.builder(CHANNEL, Peer.direct(PEER_ID), List.of())
                        .putBusinessParam("key", "value")
                        .build();

        RouteResult route = router.resolveRoute(config, msg);

        assertEquals("channel-default", route.matchedBy());
        assertEquals("value", route.context().businessContext().get("key"));
    }
}

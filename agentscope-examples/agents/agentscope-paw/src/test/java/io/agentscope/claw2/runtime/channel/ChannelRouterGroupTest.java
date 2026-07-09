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
package io.agentscope.claw2.runtime.channel;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.agentscope.core.message.Msg;
import io.agentscope.core.message.MsgRole;
import io.agentscope.harness.agent.gateway.channel.ChannelBinding;
import io.agentscope.harness.agent.gateway.channel.ChannelConfig;
import io.agentscope.harness.agent.gateway.channel.ChannelRouter;
import io.agentscope.harness.agent.gateway.channel.InboundMessage;
import io.agentscope.harness.agent.gateway.channel.OutboundAddress;
import io.agentscope.harness.agent.gateway.channel.Peer;
import io.agentscope.harness.agent.gateway.channel.PeerKind;
import io.agentscope.harness.agent.gateway.channel.RouteResult;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * Routing-tier regression test: a group @-mention should route to a GROUP peer with the binding's
 * agent, and the {@link OutboundAddress} should embed the peer kind so outbound adapters can pick
 * the correct send endpoint (DM vs group).
 */
class ChannelRouterGroupTest {

    @Test
    void groupMentionRoutesToGroupPeerAndOutboundIncludesKind() {
        ChannelConfig config =
                ChannelConfig.builder("wecom-prod")
                        .defaultAgentId("main")
                        .binding(ChannelBinding.forPeer("group:gid_123", "ops-agent"))
                        .build();
        ChannelRouter router = new ChannelRouter("fallback");

        Msg msg = Msg.builder().role(MsgRole.USER).name("u1").textContent("@bot hi").build();
        InboundMessage inbound =
                InboundMessage.builder(
                                "wecom-prod", new Peer(PeerKind.GROUP, "gid_123"), List.of(msg))
                        .accountId("corp-1")
                        .senderId("u1")
                        .build();

        RouteResult route = router.resolveRoute(config, inbound);

        assertEquals("ops-agent", route.agentId());
        assertEquals("peer", route.matchedBy());
        // Outbound address must encode the kind so e.g. WeComOutboundClient routes to
        // /cgi-bin/appchat/send instead of /cgi-bin/message/send.
        assertEquals("wecom-prod:GROUP:gid_123", route.outboundAddress().to());
        assertEquals("corp-1", route.outboundAddress().accountId());
        assertNull(route.outboundAddress().threadId());
    }

    @Test
    void dmRoutesViaChannelDefault() {
        ChannelConfig config = ChannelConfig.of("wecom-prod", "main");
        ChannelRouter router = new ChannelRouter("fallback");

        Msg msg = Msg.builder().role(MsgRole.USER).name("u9").textContent("hi").build();
        InboundMessage inbound = InboundMessage.dm("wecom-prod", "u9", List.of(msg));

        RouteResult route = router.resolveRoute(config, inbound);

        assertEquals("main", route.agentId());
        assertEquals("channel-default", route.matchedBy());
        assertEquals("wecom-prod:DIRECT:u9", route.outboundAddress().to());
    }

    @Test
    void noBindingFallsBackToGlobalDefault() {
        ChannelConfig config = ChannelConfig.of("wecom-prod");
        ChannelRouter router = new ChannelRouter("fallback-agent");

        Msg msg = Msg.builder().role(MsgRole.USER).name("u").textContent("hi").build();
        InboundMessage inbound = InboundMessage.dm("wecom-prod", "u", List.of(msg));

        RouteResult route = router.resolveRoute(config, inbound);

        assertEquals("fallback-agent", route.agentId());
        assertEquals("global-default", route.matchedBy());
        assertTrue(route.outboundAddress().to().startsWith("wecom-prod:"));
    }
}

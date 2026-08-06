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
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.agentscope.core.message.Msg;
import io.agentscope.core.message.UserMessage;
import java.util.List;
import org.junit.jupiter.api.Test;

class ChannelRouterOutboundAddressTest {

    private final ChannelRouter router = new ChannelRouter("default-agent");

    @Test
    void directPeerProducesThreeSegmentAddress() {
        Peer peer = new Peer(PeerKind.DIRECT, "user123");
        InboundMessage msg = InboundMessage.builder("dingtalk", peer, msgs()).build();

        RouteResult result = router.resolveRoute(defaultConfig(), msg);

        assertEquals("dingtalk:DIRECT:user123", result.outboundAddress().to());
        assertNull(result.outboundAddress().threadId());
    }

    @Test
    void groupPeerProducesThreeSegmentAddress() {
        Peer peer = new Peer(PeerKind.GROUP, "cidAbcDef123");
        InboundMessage msg = InboundMessage.builder("dingtalk", peer, msgs()).build();

        RouteResult result = router.resolveRoute(defaultConfig(), msg);

        assertEquals("dingtalk:GROUP:cidAbcDef123", result.outboundAddress().to());
        assertNull(result.outboundAddress().threadId());
    }

    @Test
    void channelPeerProducesThreeSegmentAddress() {
        Peer peer = new Peer(PeerKind.CHANNEL, "C0123456");
        InboundMessage msg = InboundMessage.builder("slack", peer, msgs()).build();

        RouteResult result = router.resolveRoute(defaultConfig(), msg);

        assertEquals("slack:CHANNEL:C0123456", result.outboundAddress().to());
        assertNull(result.outboundAddress().threadId());
    }

    @Test
    void threadPeerIncludesThreadId() {
        Peer peer = new Peer(PeerKind.THREAD, "thread-99");
        Peer parent = new Peer(PeerKind.CHANNEL, "C0123456");
        InboundMessage msg =
                InboundMessage.builder("slack", peer, msgs()).parentPeer(parent).build();

        RouteResult result = router.resolveRoute(defaultConfig(), msg);

        assertEquals("slack:THREAD:thread-99", result.outboundAddress().to());
        assertEquals("thread-99", result.outboundAddress().threadId());
    }

    @Test
    void groupAddressCanBeParsedByDingTalkPattern() {
        Peer peer = new Peer(PeerKind.GROUP, "cidXYZ");
        InboundMessage msg = InboundMessage.builder("dingtalk", peer, msgs()).build();

        RouteResult result = router.resolveRoute(defaultConfig(), msg);
        String to = result.outboundAddress().to();

        // Simulate DingTalkOutboundClient.parseAddress logic
        int sep = to.indexOf(':');
        assertTrue(sep > 0);
        String rest = to.substring(sep + 1);
        int sep2 = rest.indexOf(':');
        assertTrue(sep2 > 0, "should have 3 segments with kind in the middle");
        String kindRaw = rest.substring(0, sep2);
        String id = rest.substring(sep2 + 1);
        assertEquals("GROUP", kindRaw);
        assertEquals("cidXYZ", id);
    }

    private static ChannelConfig defaultConfig() {
        return ChannelConfig.of("test-channel", "default-agent");
    }

    private static List<Msg> msgs() {
        return List.of(new UserMessage("hello"));
    }
}

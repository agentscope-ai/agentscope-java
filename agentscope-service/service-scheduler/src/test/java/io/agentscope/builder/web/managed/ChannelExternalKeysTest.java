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
package io.agentscope.builder.web.managed;

import static org.junit.jupiter.api.Assertions.assertEquals;

import io.agentscope.harness.agent.gateway.MsgContext;
import io.agentscope.harness.agent.gateway.channel.OutboundAddress;
import java.util.Map;
import org.junit.jupiter.api.Test;

class ChannelExternalKeysTest {

    @Test
    void mainDmCollapsesToChannelMain() {
        MsgContext ctx =
                new MsgContext("ding-sales", null, null, null, null, Map.of("agentId", "a"));
        OutboundAddress out = OutboundAddress.direct("ding-sales", "ding-sales:DIRECT:u1");
        assertEquals("ding-sales|main", ChannelExternalKeys.forInbound(ctx, out, "MAIN"));
    }

    @Test
    void perPeerDmUsesDirectPeer() {
        MsgContext ctx =
                new MsgContext("ding-sales", null, "u1", null, null, Map.of("agentId", "a"), "u1");
        OutboundAddress out = OutboundAddress.direct("ding-sales", "ding-sales:DIRECT:u1");
        assertEquals("ding-sales|direct|u1", ChannelExternalKeys.forInbound(ctx, out, "PER_PEER"));
    }

    @Test
    void groupAlwaysUsesGroupPeerEvenUnderMain() {
        MsgContext ctx =
                new MsgContext("ding-sales", null, "cid99", null, null, Map.of("agentId", "a"));
        OutboundAddress out =
                new OutboundAddress("ding-sales", null, "ding-sales:GROUP:cid99", null);
        assertEquals("ding-sales|group|cid99", ChannelExternalKeys.forInbound(ctx, out, "MAIN"));
    }
}

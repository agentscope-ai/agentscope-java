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

import io.agentscope.harness.agent.gateway.MsgContext;
import io.agentscope.harness.agent.gateway.channel.OutboundAddress;

/**
 * Builds stable {@code externalKey} values for managed-session find-or-create, aligned with
 * channel {@code dmScope}:
 *
 * <ul>
 *   <li>MAIN DMs → {@code {channelId}|main}
 *   <li>PER_PEER DMs / groups → {@code {channelId}|{peerKind}|{peerId}} from the outbound address
 *   <li>Groups always use the group peer id even under MAIN (so group and DM never share a slot)
 * </ul>
 */
public final class ChannelExternalKeys {

    private ChannelExternalKeys() {}

    /**
     * @param ctx inbound routing context
     * @param outbound delivery address constructed by the channel router (may be null)
     * @param dmScope channel-level scope string; blank/null treated as {@code MAIN}
     */
    public static String forInbound(MsgContext ctx, OutboundAddress outbound, String dmScope) {
        String channelId =
                ctx != null && ctx.channel() != null && !ctx.channel().isBlank()
                        ? ctx.channel()
                        : "default";
        ParsedPeer peer = parseOutbound(outbound);
        boolean main = dmScope == null || dmScope.isBlank() || "MAIN".equalsIgnoreCase(dmScope);

        if (peer != null && "GROUP".equalsIgnoreCase(peer.kind)) {
            return channelId + "|group|" + peer.peerId;
        }
        if (peer != null && "THREAD".equalsIgnoreCase(peer.kind)) {
            return channelId + "|thread|" + peer.peerId;
        }
        if (main) {
            // MAIN collapses all DMs for this channel identity.
            return channelId + "|main";
        }
        if (peer != null) {
            return channelId + "|" + peer.kind.toLowerCase() + "|" + peer.peerId;
        }
        // Fallback: use MsgContext.room when present (PER_PEER puts peerId there).
        if (ctx != null && ctx.room() != null && !ctx.room().isBlank()) {
            return channelId + "|direct|" + ctx.room();
        }
        if (ctx != null && ctx.userId() != null && !ctx.userId().isBlank()) {
            return channelId + "|direct|" + ctx.userId();
        }
        return channelId + "|main";
    }

    private static ParsedPeer parseOutbound(OutboundAddress outbound) {
        if (outbound == null || outbound.to() == null || outbound.to().isBlank()) {
            return null;
        }
        // Format: channelId:peerKind:peerId (peerId may contain ':')
        String to = outbound.to();
        int first = to.indexOf(':');
        if (first < 0) {
            return null;
        }
        int second = to.indexOf(':', first + 1);
        if (second < 0) {
            return null;
        }
        String kind = to.substring(first + 1, second);
        String peerId = to.substring(second + 1);
        if (kind.isBlank() || peerId.isBlank()) {
            return null;
        }
        return new ParsedPeer(kind, peerId);
    }

    private record ParsedPeer(String kind, String peerId) {}
}

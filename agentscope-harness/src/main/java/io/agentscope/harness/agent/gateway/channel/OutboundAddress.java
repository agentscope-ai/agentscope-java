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

import java.util.Objects;

/**
 * Delivery target for outbound (proactive) messages. Constructed by {@link ChannelRouter} during
 * inbound routing and stored by the gateway as the session's "last route" so that proactive replies
 * (e.g. subagent completion announces) can be delivered back to the correct channel and peer.
 *
 * @param channelId the channel adapter to deliver through (e.g. {@code "chatui"}, {@code "slack"})
 * @param accountId optional multi-account identifier (nullable for single-account channels)
 * @param to delivery address in {@code "channelId:peerKind:peerId"} format (e.g. {@code "dingtalk:GROUP:cidXXX"})
 * @param threadId optional thread context for threaded replies (nullable)
 */
public record OutboundAddress(String channelId, String accountId, String to, String threadId) {

    public OutboundAddress {
        Objects.requireNonNull(channelId, "channelId");
        Objects.requireNonNull(to, "to");
    }

    /** Creates an address for a direct (non-threaded) message. */
    public static OutboundAddress direct(String channelId, String to) {
        return new OutboundAddress(channelId, null, to, null);
    }

    /** Creates an address with account context. */
    public static OutboundAddress withAccount(String channelId, String accountId, String to) {
        return new OutboundAddress(channelId, accountId, to, null);
    }
}

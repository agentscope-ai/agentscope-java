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
package io.agentscope.builder.runtime.outbound;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

/**
 * Agent-initiated outbound message request.
 *
 * <p>A request describes <em>where</em> the message should be delivered (channel + peer kind +
 * peer id, plus optional account/thread context) and <em>what</em> the message says (text or
 * markdown). The {@link OutboundService} translates this into a {@link
 * io.agentscope.harness.agent.gateway.channel.OutboundAddress} and delegates to the matching channel.
 *
 * @param channelId target channel id (must match a registered {@link
 *     io.agentscope.harness.agent.gateway.channel.Channel}); required
 * @param peerKind one of {@code DIRECT}, {@code CHANNEL}, {@code GROUP}, {@code THREAD}; required
 * @param peerId provider-specific peer id (user id, group/conversation id, ...); required
 * @param accountId optional multi-account dimension (corp id, app instance); nullable
 * @param threadId optional thread anchor for threaded replies; nullable
 * @param text plain-text message body; either {@code text} or {@code markdown} must be set
 * @param markdown markdown message body; either {@code text} or {@code markdown} must be set
 * @param agentId optional caller-supplied agent id. When set, the service verifies that the
 *     channel's routing for {@code peerId} resolves to the same agent and refuses delivery
 *     otherwise. Use this to prevent one agent from posting into a channel/peer that is bound to
 *     a different agent. Skipped when {@code null} or blank.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record OutboundRequest(
        String channelId,
        String peerKind,
        String peerId,
        String accountId,
        String threadId,
        String text,
        String markdown,
        String agentId) {

    /** Convenience constructor without {@code agentId} (no caller-side routing check). */
    public OutboundRequest(
            String channelId,
            String peerKind,
            String peerId,
            String accountId,
            String threadId,
            String text,
            String markdown) {
        this(channelId, peerKind, peerId, accountId, threadId, text, markdown, null);
    }
}

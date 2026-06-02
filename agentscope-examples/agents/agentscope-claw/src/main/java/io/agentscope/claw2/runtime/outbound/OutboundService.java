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
package io.agentscope.claw2.runtime.outbound;

import io.agentscope.claw2.runtime.channel.Channel;
import io.agentscope.claw2.runtime.channel.ChannelRouter;
import io.agentscope.claw2.runtime.channel.InboundMessage;
import io.agentscope.claw2.runtime.channel.OutboundAddress;
import io.agentscope.claw2.runtime.channel.Peer;
import io.agentscope.claw2.runtime.channel.PeerKind;
import io.agentscope.claw2.runtime.channel.RouteResult;
import io.agentscope.claw2.runtime.gateway.ChannelManager;
import io.agentscope.core.message.Msg;
import io.agentscope.core.message.MsgRole;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Translates an {@link OutboundRequest} into an {@link OutboundAddress} + {@link Msg} and pushes
 * it through the {@link ChannelManager}.
 *
 * <p>Used by both {@link OutboundController} (HTTP) and {@link OutboundTool} (agent tool). Errors
 * are surfaced as {@link IllegalArgumentException} (bad request) or {@link IllegalStateException}
 * (no such channel / channel unhealthy).
 */
public final class OutboundService {

    private static final Logger log = LoggerFactory.getLogger(OutboundService.class);

    private final ChannelManager channelManager;
    private final ChannelRouter router = new ChannelRouter(null);

    public OutboundService(ChannelManager channelManager) {
        this.channelManager = Objects.requireNonNull(channelManager, "channelManager");
    }

    /** Delivers the message described by {@code request}. */
    public void send(OutboundRequest request) {
        Objects.requireNonNull(request, "request");
        if (request.channelId() == null || request.channelId().isBlank()) {
            throw new IllegalArgumentException("channelId is required");
        }
        if (request.peerId() == null || request.peerId().isBlank()) {
            throw new IllegalArgumentException("peerId is required");
        }
        boolean hasText = request.text() != null && !request.text().isBlank();
        boolean hasMd = request.markdown() != null && !request.markdown().isBlank();
        if (!hasText && !hasMd) {
            throw new IllegalArgumentException("either text or markdown must be provided");
        }

        PeerKind kind = parsePeerKind(request.peerKind());
        Peer peer = new Peer(kind, request.peerId().trim());

        Optional<Channel> ch = channelManager.getChannel(request.channelId());
        if (ch.isEmpty()) {
            throw new IllegalStateException(
                    "No channel registered for channelId='" + request.channelId() + "'");
        }

        if (request.agentId() != null && !request.agentId().isBlank()) {
            verifyAgentRouting(ch.get(), peer, request);
        }

        OutboundAddress address =
                new OutboundAddress(
                        request.channelId(),
                        emptyToNull(request.accountId()),
                        request.channelId() + ":" + peer.key(),
                        emptyToNull(request.threadId()));

        Msg msg =
                Msg.builder()
                        .role(MsgRole.ASSISTANT)
                        .name("outbound")
                        .textContent(hasMd ? request.markdown() : request.text())
                        .build();

        log.debug(
                "Outbound send: channel={}, peer={}, account={}, thread={}, mode={}",
                request.channelId(),
                peer.key(),
                request.accountId(),
                request.threadId(),
                hasMd ? "markdown" : "text");

        channelManager.deliver(address, List.of(msg));
    }

    /**
     * Probes the channel's router with a synthetic inbound on {@code peer} and refuses delivery
     * when the channel's bindings (or its {@code defaultAgentId}) route to a different agent than
     * {@code request.agentId()}. The probe carries the requested agent as a hint so that channels
     * without any binding (and without a channel-default) accept the caller — explicit bindings
     * still take precedence.
     */
    private void verifyAgentRouting(Channel channel, Peer peer, OutboundRequest request) {
        String wanted = request.agentId().trim();
        InboundMessage probe =
                InboundMessage.builder(
                                request.channelId(),
                                peer,
                                List.of(
                                        Msg.builder()
                                                .role(MsgRole.USER)
                                                .textContent("__outbound_probe__")
                                                .build()))
                        .senderId(peer.id())
                        .accountId(emptyToNull(request.accountId()))
                        .requestedAgentId(wanted)
                        .build();
        RouteResult route = router.resolveRoute(channel.config(), probe);
        String resolved = route.agentId();
        if (!Objects.equals(wanted, resolved)) {
            throw new IllegalStateException(
                    "Routing mismatch: channel '"
                            + request.channelId()
                            + "' / peer '"
                            + peer.key()
                            + "' resolves to agent '"
                            + resolved
                            + "' (matchedBy="
                            + route.matchedBy()
                            + ") but caller declared agent '"
                            + wanted
                            + "'. Update the channel binding or omit agent_id to override.");
        }
    }

    private static PeerKind parsePeerKind(String raw) {
        if (raw == null || raw.isBlank()) {
            return PeerKind.DIRECT;
        }
        try {
            return PeerKind.valueOf(raw.trim().toUpperCase());
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException(
                    "Unknown peerKind '" + raw + "'. Allowed: DIRECT | CHANNEL | GROUP | THREAD");
        }
    }

    private static String emptyToNull(String s) {
        return (s == null || s.isBlank()) ? null : s;
    }
}

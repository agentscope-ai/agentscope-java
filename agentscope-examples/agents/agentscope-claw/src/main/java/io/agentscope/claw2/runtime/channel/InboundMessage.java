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

import io.agentscope.core.message.Msg;
import java.util.List;
import java.util.Objects;
import java.util.Set;

/**
 * Normalized inbound message produced by a {@link Channel} adapter before routing. Carries all
 * identity and context metadata needed by {@link ChannelRouter} to resolve an agent and build a
 * stable session key.
 *
 * <p>Mirrors OpenClaw's normalized routing context (channel, accountId, peer/chat shape, optional
 * guild/team/roles/thread metadata).
 *
 * <p>Two identity fields are deliberately separate (mirroring OpenClaw's {@code sender} vs {@code
 * peer} split):
 * <ul>
 *   <li>{@link #peer} — the <em>conversation</em> identifier used to construct the session key
 *       (DM user id, group chat id, channel id)
 *   <li>{@link #senderId} — the <em>message sender's</em> identity, used as {@code userId} for
 *       HarnessAgent namespace isolation. In DM scenarios these are equal; in group/channel
 *       scenarios {@code peer.id} is the group and {@code senderId} is the individual author.
 * </ul>
 *
 * @param channelId identifier of the originating channel (e.g. {@code "chatui"}, {@code "slack"})
 * @param accountId optional multi-account identifier (e.g. Slack app installation id); null for
 *     single-account channels
 * @param peer the conversation peer (DM partner, channel, group, or thread) — used for session key
 * @param senderId optional sender identity for user-level isolation; if null, {@link #peer}'s id is
 *     used as fallback in DM contexts
 * @param parentPeer optional parent peer for thread-nested messages; used for
 *     {@code binding.peer.parent} inheritance
 * @param guild optional guild / server / workspace id (Discord guild, Slack workspace)
 * @param team optional team id (Slack team, MS Teams team)
 * @param roles optional set of role ids the sender holds within the guild; used for
 *     {@code binding.guild+roles} tier evaluation
 * @param requestedAgentId optional caller-supplied agent hint (e.g. the chatui surface uses this to
 *     route a specific agent's chat tab through the same {@link ChannelRouter} as remote channels).
 *     {@link ChannelRouter} consults this only after explicit bindings and the channel's
 *     {@code defaultAgentId} fail to match — bindings still take precedence so administrators can
 *     pin routes regardless of caller hints.
 * @param messages the actual message content to forward to the agent
 */
public record InboundMessage(
        String channelId,
        String accountId,
        Peer peer,
        String senderId,
        Peer parentPeer,
        String guild,
        String team,
        Set<String> roles,
        String requestedAgentId,
        List<Msg> messages) {

    public InboundMessage {
        Objects.requireNonNull(channelId, "channelId");
        Objects.requireNonNull(peer, "peer");
        Objects.requireNonNull(messages, "messages");
        roles = roles != null ? Set.copyOf(roles) : Set.of();
    }

    // -----------------------------------------------------------------
    //  Factories
    // -----------------------------------------------------------------

    /**
     * Single-turn DM with no guild/team/role metadata (typical for direct chat UI use).
     *
     * <p>In DM context {@code senderId} equals {@code peerId} — the conversation partner is also
     * the message author.
     */
    public static InboundMessage dm(String channelId, String peerId, List<Msg> messages) {
        return new InboundMessage(
                channelId,
                null,
                Peer.direct(peerId),
                peerId,
                null,
                null,
                null,
                Set.of(),
                null,
                List.copyOf(messages));
    }

    /**
     * Channel / group message with optional guild context. The {@code senderId} identifies the
     * individual author separately from the {@code roomId} conversation peer.
     */
    public static InboundMessage channel(
            String channelId, String roomId, String senderId, String guild, List<Msg> messages) {
        return new InboundMessage(
                channelId,
                null,
                Peer.channel(roomId),
                senderId,
                null,
                guild,
                null,
                Set.of(),
                null,
                List.copyOf(messages));
    }

    /**
     * Channel message with optional guild context (no explicit senderId — sender identity unknown,
     * falls back to room peer for isolation).
     */
    public static InboundMessage channel(
            String channelId, String roomId, String guild, List<Msg> messages) {
        return new InboundMessage(
                channelId,
                null,
                Peer.channel(roomId),
                null,
                null,
                guild,
                null,
                Set.of(),
                null,
                List.copyOf(messages));
    }

    // -----------------------------------------------------------------
    //  Convenience
    // -----------------------------------------------------------------

    /** Whether this message originates from a direct / DM peer. */
    public boolean isDm() {
        return peer.kind().isDirect();
    }

    /** Whether this message is a thread-nested reply. */
    public boolean isThread() {
        return peer.kind().isThread();
    }

    /**
     * Returns a copy with the {@code requestedAgentId} hint set. Lightweight helper for callers
     * (typically channel adapters that obtain the hint outside the originating factory).
     */
    public InboundMessage withRequestedAgentId(String requestedAgentId) {
        return new InboundMessage(
                channelId,
                accountId,
                peer,
                senderId,
                parentPeer,
                guild,
                team,
                roles,
                requestedAgentId,
                messages);
    }

    /** Returns a builder for constructing messages with all optional fields. */
    public static Builder builder(String channelId, Peer peer, List<Msg> messages) {
        return new Builder(channelId, peer, messages);
    }

    public static final class Builder {
        private final String channelId;
        private final Peer peer;
        private final List<Msg> messages;
        private String accountId;
        private String senderId;
        private Peer parentPeer;
        private String guild;
        private String team;
        private Set<String> roles = Set.of();
        private String requestedAgentId;

        private Builder(String channelId, Peer peer, List<Msg> messages) {
            this.channelId = channelId;
            this.peer = peer;
            this.messages = messages;
        }

        public Builder accountId(String accountId) {
            this.accountId = accountId;
            return this;
        }

        /**
         * Sets the message sender's identity (used as {@code userId} for namespace isolation).
         * In DM context this typically equals {@code peer.id()}; in group/channel context it is
         * the individual author's id.
         */
        public Builder senderId(String senderId) {
            this.senderId = senderId;
            return this;
        }

        public Builder parentPeer(Peer parentPeer) {
            this.parentPeer = parentPeer;
            return this;
        }

        public Builder guild(String guild) {
            this.guild = guild;
            return this;
        }

        public Builder team(String team) {
            this.team = team;
            return this;
        }

        public Builder roles(Set<String> roles) {
            this.roles = roles != null ? roles : Set.of();
            return this;
        }

        /**
         * Sets the caller-supplied agent hint consulted by {@link ChannelRouter} after explicit
         * bindings and {@code channel.defaultAgentId} both fail to match.
         */
        public Builder requestedAgentId(String requestedAgentId) {
            this.requestedAgentId = requestedAgentId;
            return this;
        }

        public InboundMessage build() {
            return new InboundMessage(
                    channelId,
                    accountId,
                    peer,
                    senderId,
                    parentPeer,
                    guild,
                    team,
                    roles,
                    requestedAgentId,
                    messages);
        }
    }
}

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
package io.agentscope.dataagent.runtime.channel;

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
 * @param messages the actual message content to forward to the agent
 * @param preferredAgentId optional explicit agent override evaluated by {@link ChannelRouter} as a
 *     tier-0 short-circuit before any binding tier. Set this when the caller already knows which
 *     agent should handle the request (e.g. a path-mapped Web UI where the user clicked an agent
 *     card); the router still builds session and outbound context normally so bindings continue to
 *     control {@code sessionScope} and outbound addressing. {@code null} for normal binding-driven
 *     routing.
 * @param conversationId optional caller-supplied conversation id that {@link ChannelRouter} folds
 *     into the {@code MsgContext.threadId} for DM peers, so a single (userId, agentId) pair can host
 *     multiple ChatGPT-style sessions. {@code null} collapses to the legacy single-session
 *     behaviour.
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
        List<Msg> messages,
        String preferredAgentId,
        String conversationId) {

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
                List.copyOf(messages),
                null,
                null);
    }

    /**
     * Single-turn DM with an explicit {@link #preferredAgentId()} override — the router will use
     * the supplied {@code agentId} as a tier-0 short-circuit instead of evaluating bindings.
     * Suitable for path-mapped Web UI calls where the user has already chosen the target agent.
     */
    public static InboundMessage dmFor(
            String channelId, String peerId, String agentId, List<Msg> messages) {
        return dmFor(channelId, peerId, agentId, messages, null);
    }

    /**
     * Same as {@link #dmFor(String, String, String, List)} but threads a caller-supplied
     * {@code conversationId} so a single (userId, agentId) pair can address multiple persisted
     * sessions. {@code null} preserves the single-session behaviour.
     */
    public static InboundMessage dmFor(
            String channelId,
            String peerId,
            String agentId,
            List<Msg> messages,
            String conversationId) {
        Objects.requireNonNull(agentId, "agentId");
        return new InboundMessage(
                channelId,
                null,
                Peer.direct(peerId),
                peerId,
                null,
                null,
                null,
                Set.of(),
                List.copyOf(messages),
                agentId,
                conversationId);
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
                List.copyOf(messages),
                null,
                null);
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
                List.copyOf(messages),
                null,
                null);
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
        private String preferredAgentId;
        private String conversationId;

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
         * Sets an explicit agent override evaluated by {@link ChannelRouter} as a tier-0
         * short-circuit. See {@link InboundMessage#preferredAgentId()}.
         */
        public Builder preferredAgentId(String preferredAgentId) {
            this.preferredAgentId = preferredAgentId;
            return this;
        }

        /**
         * Sets the caller-supplied conversation id; see {@link InboundMessage#conversationId()}.
         */
        public Builder conversationId(String conversationId) {
            this.conversationId = conversationId;
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
                    messages,
                    preferredAgentId,
                    conversationId);
        }
    }
}

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

import io.agentscope.harness.agent.gateway.MsgContext;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * Resolves the target {@code agentId} and stable {@link MsgContext} for an {@link InboundMessage}
 * by evaluating {@link ChannelBinding} rules in deterministic priority order.
 *
 * <h2>Binding evaluation tiers (highest to lowest priority)</h2>
 *
 * <ol>
 *   <li><b>explicit</b> — {@link InboundMessage#preferredAgentId()} short-circuit when the caller
 *       has already nominated a specific agent. Bindings are still consulted to determine
 *       {@code sessionScope} and outbound addressing.
 *   <li><b>peer</b> — exact {@link Peer#key()} match (e.g. {@code "direct:u_42"})
 *   <li><b>peer.parent</b> — exact match on the thread-parent peer's key
 *   <li><b>guild + roles</b> — guild id matches AND sender holds at least one of the binding's
 *       roles
 *   <li><b>guild</b> — guild id matches (no role constraint)
 *   <li><b>team</b> — team id matches
 *   <li><b>account</b> — account id matches
 *   <li><b>channel</b> — channel id matches
 *   <li><b>default</b> — {@link ChannelConfig#defaultAgentId()} or {@code globalDefaultAgentId}
 * </ol>
 *
 * <p>Within each tier the first binding in {@link ChannelConfig#bindings()} list order that
 * matches wins.
 *
 * <h2>Session key construction ({@link MsgContext})</h2>
 *
 * After resolving {@code agentId}, the router builds a {@link MsgContext} whose {@link
 * MsgContext#canonicalKey()} produces a stable session key:
 *
 * <ul>
 *   <li>DM + {@link DmScope#MAIN} — {@code channel} field only; all DMs share one session
 *   <li>DM + other scopes — room = peerId (optionally group = accountId)
 *   <li>Thread — room = parentPeer id, threadId = peer id
 *   <li>Non-DM channel/group — room = peer id, group = guild
 * </ul>
 *
 * The resolved {@code agentId} is always included in {@link MsgContext#extra()} under key {@code
 * "agentId"} so that the gateway can pick the correct agent from its registry.
 */
public final class ChannelRouter {

    private final String globalDefaultAgentId;

    /**
     * @param globalDefaultAgentId fallback agent id when no binding and no channel-level default
     *     match; typically the id of the agent registered via
     *     {@link io.agentscope.harness.agent.gateway.Gateway#bindMainAgent}
     */
    public ChannelRouter(String globalDefaultAgentId) {
        this.globalDefaultAgentId = globalDefaultAgentId != null ? globalDefaultAgentId : "main";
    }

    /**
     * Evaluates bindings in priority order and returns a {@link RouteResult} ready for gateway
     * execution.
     *
     * @param config channel-level routing config (bindings + dmScope + channel default agent)
     * @param msg normalized inbound message
     */
    public RouteResult resolveRoute(ChannelConfig config, InboundMessage msg) {
        Objects.requireNonNull(config, "config");
        Objects.requireNonNull(msg, "msg");

        String agentId;
        String matchedBy;
        String explicit = msg.preferredAgentId();
        ChannelBinding matchedBinding = findMatchedBinding(config.bindings(), msg);

        if (explicit != null && !explicit.isBlank()) {
            agentId = explicit;
            matchedBy = "explicit";
        } else if (matchedBinding != null) {
            agentId = matchedBinding.agentId();
            matchedBy = detectMatchedTier(config.bindings(), msg);
        } else if (config.defaultAgentId() != null) {
            agentId = config.defaultAgentId();
            matchedBy = "channel-default";
        } else {
            agentId = globalDefaultAgentId;
            matchedBy = "global-default";
        }

        DmScope effectiveScope =
                matchedBinding != null && matchedBinding.sessionScope() != null
                        ? matchedBinding.sessionScope()
                        : config.dmScope();

        String userId =
                msg.senderId() != null
                        ? msg.senderId()
                        : (msg.peer().kind().isDirect() ? msg.peer().id() : null);

        MsgContext context = buildContext(msg, effectiveScope, agentId, userId);
        OutboundAddress outbound = buildOutboundAddress(msg);
        return new RouteResult(agentId, context, matchedBy, outbound);
    }

    // -----------------------------------------------------------------
    //  Binding evaluation - 7 tiers
    // -----------------------------------------------------------------

    private static final List<String> TIERS =
            List.of("peer", "parentPeer", "guildRoles", "guild", "team", "account", "channel");

    private ChannelBinding findMatchedBinding(List<ChannelBinding> bindings, InboundMessage msg) {
        for (String tier : TIERS) {
            ChannelBinding b = findFirstBinding(bindings, msg, tier);
            if (b != null) return b;
        }
        return null;
    }

    private String detectMatchedTier(List<ChannelBinding> bindings, InboundMessage msg) {
        for (String tier : TIERS) {
            if (findFirstBinding(bindings, msg, tier) != null) {
                return tier.equals("guildRoles") ? "guild+roles" : tier;
            }
        }
        return "none";
    }

    private ChannelBinding findFirstBinding(
            List<ChannelBinding> bindings, InboundMessage msg, String tier) {
        for (ChannelBinding b : bindings) {
            if (matches(b, msg, tier)) {
                return b;
            }
        }
        return null;
    }

    private boolean matches(ChannelBinding b, InboundMessage msg, String tier) {
        return switch (tier) {
            case "peer" -> b.peer() != null && b.peer().equals(msg.peer().key());
            case "parentPeer" ->
                    b.parentPeer() != null
                            && msg.parentPeer() != null
                            && b.parentPeer().equals(msg.parentPeer().key());
            case "guildRoles" ->
                    b.guild() != null
                            && !b.roles().isEmpty()
                            && b.guild().equals(msg.guild())
                            && msg.roles().stream().anyMatch(b.roles()::contains);
            case "guild" ->
                    b.guild() != null && b.roles().isEmpty() && b.guild().equals(msg.guild());
            case "team" -> b.team() != null && b.team().equals(msg.team());
            case "account" -> b.account() != null && b.account().equals(msg.accountId());
            case "channel" -> b.channel() != null && b.channel().equals(msg.channelId());
            default -> false;
        };
    }

    // -----------------------------------------------------------------
    //  MsgContext construction from routing result + dmScope
    // -----------------------------------------------------------------

    private MsgContext buildContext(
            InboundMessage msg, DmScope dmScope, String agentId, String userId) {
        Map<String, String> extra = Map.of("agentId", agentId);
        String channel = msg.channelId();
        Peer peer = msg.peer();

        MsgContext ctx;
        if (peer.kind().isThread()) {
            String parentRoom = msg.parentPeer() != null ? msg.parentPeer().id() : null;
            ctx = new MsgContext(channel, msg.guild(), parentRoom, peer.id(), null, extra);
        } else if (peer.kind().isDirect()) {
            ctx = buildDmContext(channel, peer.id(), msg.accountId(), dmScope, extra);
        } else {
            ctx = new MsgContext(channel, msg.guild(), peer.id(), null, null, extra);
        }

        return userId != null ? ctx.withUserId(userId) : ctx;
    }

    private MsgContext buildDmContext(
            String channel,
            String peerId,
            String accountId,
            DmScope dmScope,
            Map<String, String> extra) {
        return switch (dmScope) {
            case MAIN -> new MsgContext(channel, null, null, null, null, extra);
            case PER_PEER -> new MsgContext(channel, null, peerId, null, null, extra);
            case PER_CHANNEL_PEER -> new MsgContext(channel, null, peerId, null, null, extra);
            case PER_ACCOUNT_CHANNEL_PEER ->
                    new MsgContext(channel, accountId, peerId, null, null, extra);
        };
    }

    // -----------------------------------------------------------------
    //  OutboundAddress construction
    // -----------------------------------------------------------------

    private OutboundAddress buildOutboundAddress(InboundMessage msg) {
        String to = msg.channelId() + ":" + msg.peer().kind().name() + ":" + msg.peer().id();
        String threadId = msg.peer().kind().isThread() ? msg.peer().id() : null;
        return new OutboundAddress(msg.channelId(), msg.accountId(), to, threadId);
    }
}

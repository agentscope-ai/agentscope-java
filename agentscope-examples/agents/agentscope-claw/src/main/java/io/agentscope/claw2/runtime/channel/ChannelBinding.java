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

import java.util.Objects;
import java.util.Set;

/**
 * One routing rule that maps a set of inbound-message conditions to a target {@code agentId}.
 *
 * <p>A binding is evaluated by {@link ChannelRouter} at the tier corresponding to the most
 * specific non-null condition field it carries (OpenClaw priority order):
 *
 * <ol>
 *   <li>{@link #peer} — exact peer-key match ({@code "direct:u_123"} or {@code "channel:c_help"})
 *   <li>{@link #parentPeer} — parent-peer key match (thread-parent inheritance)
 *   <li>{@link #guild} + {@link #roles} — guild membership AND at least one role matches
 *   <li>{@link #guild} alone — guild membership (no role constraint)
 *   <li>{@link #team} — team id match
 *   <li>{@link #account} — account id match
 *   <li>{@link #channel} — channel id match
 * </ol>
 *
 * <p>Within a tier, the first binding in {@link ChannelConfig#bindings()} list order that matches
 * wins. Only one condition tier is active per binding: the most specific non-null field determines
 * the tier.
 *
 * <p>The optional {@link #sessionScope} field mirrors OpenClaw's per-binding session override:
 * when non-null it takes precedence over the channel-level {@link ChannelConfig#dmScope()} for DM
 * session key construction. This allows fine-grained control — e.g. a guild binding can use
 * {@link DmScope#PER_PEER} while the channel default remains {@link DmScope#MAIN}.
 *
 * @param agentId the target agent id when this binding matches (required)
 * @param peer exact peer key to match (e.g. {@code "direct:u_42"}); highest priority
 * @param parentPeer parent-peer key for thread-parent inheritance (e.g. {@code "channel:c_ops"})
 * @param guild guild/server/workspace id to match
 * @param roles role ids to match within the guild (evaluated alongside {@code guild}); if non-empty
 *     the binding is a guild+roles tier binding; if empty it is a guild-only tier binding
 * @param team team id to match
 * @param account account id to match
 * @param channel channel id to match (lower priority; rarely needed inside channel-specific config)
 * @param sessionScope optional per-binding DM session scope override; null means inherit from
 *     {@link ChannelConfig#dmScope()}
 */
public record ChannelBinding(
        String agentId,
        String peer,
        String parentPeer,
        String guild,
        Set<String> roles,
        String team,
        String account,
        String channel,
        DmScope sessionScope) {

    public ChannelBinding {
        Objects.requireNonNull(agentId, "agentId");
        roles = roles != null ? Set.copyOf(roles) : Set.of();
    }

    // -----------------------------------------------------------------
    //  Factory helpers for common single-condition bindings
    // -----------------------------------------------------------------

    /** Matches an exact peer (e.g. a specific DM user or channel id). */
    public static ChannelBinding forPeer(String peer, String agentId) {
        return new ChannelBinding(agentId, peer, null, null, Set.of(), null, null, null, null);
    }

    /** Matches messages that are threads under a specific parent peer. */
    public static ChannelBinding forParentPeer(String parentPeer, String agentId) {
        return new ChannelBinding(
                agentId, null, parentPeer, null, Set.of(), null, null, null, null);
    }

    /** Matches any message from a specific guild (server/workspace). */
    public static ChannelBinding forGuild(String guild, String agentId) {
        return new ChannelBinding(agentId, null, null, guild, Set.of(), null, null, null, null);
    }

    /** Matches messages from a guild where the sender holds at least one of the given roles. */
    public static ChannelBinding forGuildRoles(String guild, Set<String> roles, String agentId) {
        return new ChannelBinding(agentId, null, null, guild, roles, null, null, null, null);
    }

    /** Matches a specific team id. */
    public static ChannelBinding forTeam(String team, String agentId) {
        return new ChannelBinding(agentId, null, null, null, Set.of(), team, null, null, null);
    }

    /** Matches a specific account id. */
    public static ChannelBinding forAccount(String account, String agentId) {
        return new ChannelBinding(agentId, null, null, null, Set.of(), null, account, null, null);
    }

    /** Matches a specific channel id. */
    public static ChannelBinding forChannel(String channel, String agentId) {
        return new ChannelBinding(agentId, null, null, null, Set.of(), null, null, channel, null);
    }

    /**
     * Returns a copy of this binding with a per-binding session scope override. When the router
     * matches this binding, {@link io.agentscope.claw2.runtime.channel.DmScope} from this field
     * takes precedence over the channel-level {@code dmScope}.
     */
    public ChannelBinding withSessionScope(DmScope scope) {
        return new ChannelBinding(
                agentId, peer, parentPeer, guild, roles, team, account, channel, scope);
    }
}

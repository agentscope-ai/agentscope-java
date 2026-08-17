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
package io.agentscope.builder.runtime.config;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;
import io.agentscope.harness.agent.gateway.channel.ChannelBinding;
import io.agentscope.harness.agent.gateway.channel.DmScope;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * JSON-serializable counterpart of {@link ChannelBinding}.
 *
 * <p>Field order corresponds to the OpenClaw routing priority tiers evaluated by {@link
 * io.agentscope.harness.agent.gateway.channel.ChannelRouter}:
 *
 * <ol>
 *   <li>{@link #peer}
 *   <li>{@link #parentPeer}
 *   <li>{@link #guild} + {@link #roles}
 *   <li>{@link #guild} alone
 *   <li>{@link #team}
 *   <li>{@link #account}
 *   <li>{@link #channel}
 * </ol>
 *
 * <p>The most specific non-null field determines the tier at which this binding is evaluated.
 * {@link #agentId} is required; {@link #sessionScope} optionally overrides the channel-level DM
 * scope for sessions created by this binding.
 *
 * <h2>Example JSON</h2>
 *
 * <pre>{@code
 * {
 *   "agentId": "support",
 *   "guild":   "ws-alpha",
 *   "roles":   ["staff"],
 *   "sessionScope": "PER_PEER"
 * }
 * }</pre>
 */
@JsonInclude(JsonInclude.Include.NON_EMPTY)
@JsonIgnoreProperties(ignoreUnknown = true)
public class BindingConfigEntry {

    @JsonProperty("agentId")
    private String agentId;

    @JsonProperty("peer")
    private String peer;

    @JsonProperty("parentPeer")
    private String parentPeer;

    @JsonProperty("guild")
    private String guild;

    @JsonProperty("roles")
    private List<String> roles;

    @JsonProperty("team")
    private String team;

    @JsonProperty("account")
    private String account;

    @JsonProperty("channel")
    private String channel;

    /**
     * Optional per-binding DM scope override. One of {@code MAIN}, {@code PER_PEER}, {@code
     * PER_CHANNEL_PEER}, {@code PER_ACCOUNT_CHANNEL_PEER}. When null the channel-level scope
     * applies.
     */
    @JsonProperty("sessionScope")
    private String sessionScope;

    // -----------------------------------------------------------------
    //  Getters / setters
    // -----------------------------------------------------------------

    public String getAgentId() {
        return agentId;
    }

    public void setAgentId(String agentId) {
        this.agentId = agentId;
    }

    public String getPeer() {
        return peer;
    }

    public void setPeer(String peer) {
        this.peer = peer;
    }

    public String getParentPeer() {
        return parentPeer;
    }

    public void setParentPeer(String parentPeer) {
        this.parentPeer = parentPeer;
    }

    public String getGuild() {
        return guild;
    }

    public void setGuild(String guild) {
        this.guild = guild;
    }

    public List<String> getRoles() {
        return roles;
    }

    public void setRoles(List<String> roles) {
        this.roles = roles;
    }

    public String getTeam() {
        return team;
    }

    public void setTeam(String team) {
        this.team = team;
    }

    public String getAccount() {
        return account;
    }

    public void setAccount(String account) {
        this.account = account;
    }

    public String getChannel() {
        return channel;
    }

    public void setChannel(String channel) {
        this.channel = channel;
    }

    public String getSessionScope() {
        return sessionScope;
    }

    public void setSessionScope(String sessionScope) {
        this.sessionScope = sessionScope;
    }

    // -----------------------------------------------------------------
    //  Conversion
    // -----------------------------------------------------------------

    /**
     * Converts this entry to a runtime {@link ChannelBinding}.
     *
     * @throws IllegalArgumentException if {@code agentId} is missing or blank
     */
    public ChannelBinding toBinding() {
        if (agentId == null || agentId.isBlank()) {
            throw new IllegalArgumentException("BindingConfigEntry.agentId is required");
        }
        Set<String> rolesSet =
                roles == null || roles.isEmpty() ? Set.of() : new LinkedHashSet<>(roles);
        DmScope scope = null;
        if (sessionScope != null && !sessionScope.isBlank()) {
            try {
                scope = DmScope.valueOf(sessionScope.toUpperCase());
            } catch (IllegalArgumentException ignored) {
                // Unknown value → leave null so channel-level scope applies
            }
        }
        return new ChannelBinding(
                agentId.trim(),
                blankToNull(peer),
                blankToNull(parentPeer),
                blankToNull(guild),
                rolesSet,
                blankToNull(team),
                blankToNull(account),
                blankToNull(channel),
                scope);
    }

    /** Inverse helper: build a {@link BindingConfigEntry} from a runtime {@link ChannelBinding}. */
    public static BindingConfigEntry fromBinding(ChannelBinding b) {
        BindingConfigEntry e = new BindingConfigEntry();
        e.setAgentId(b.agentId());
        e.setPeer(b.peer());
        e.setParentPeer(b.parentPeer());
        e.setGuild(b.guild());
        if (b.roles() != null && !b.roles().isEmpty()) {
            e.setRoles(List.copyOf(b.roles()));
        }
        e.setTeam(b.team());
        e.setAccount(b.account());
        e.setChannel(b.channel());
        if (b.sessionScope() != null) {
            e.setSessionScope(b.sessionScope().name());
        }
        return e;
    }

    private static String blankToNull(String s) {
        return s == null || s.isBlank() ? null : s;
    }
}

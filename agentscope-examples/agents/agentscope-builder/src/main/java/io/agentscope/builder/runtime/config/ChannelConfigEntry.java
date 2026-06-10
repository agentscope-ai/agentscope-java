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
import io.agentscope.builder.runtime.BuilderBootstrap;
import io.agentscope.harness.agent.gateway.channel.ChannelBinding;
import io.agentscope.harness.agent.gateway.channel.ChannelConfig;
import io.agentscope.harness.agent.gateway.channel.DmScope;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Per-channel section in {@code agentscope.json} under {@code channels.<channelId>}.
 *
 * <p>Defines routing configuration for a channel adapter. The built-in {@code chatui} channel is
 * automatically created from this entry if no programmatic {@link
 * BuilderBootstrap.Builder#channel(io.agentscope.harness.agent.gateway.channel.Channel...)} registration exists for
 * it. For other channel types, this entry provides the {@link ChannelConfig} routing rules that are
 * applied at bootstrap time.
 *
 * <h2>Example</h2>
 *
 * <pre>{@code
 * "channels": {
 *   "chatui": {
 *     "defaultAgentId": "main",
 *     "dmScope": "PER_PEER"
 *   },
 *   "slack": {
 *     "defaultAgentId": "support",
 *     "dmScope": "PER_PEER"
 *   }
 * }
 * }</pre>
 *
 * @see AgentscopeConfig
 */
@JsonInclude(JsonInclude.Include.NON_EMPTY)
@JsonIgnoreProperties(ignoreUnknown = true)
public class ChannelConfigEntry {

    /**
     * Channel type discriminator, looked up by {@link ChannelTypeRegistry} to select the
     * {@link ChannelFactory} that builds the channel instance. Built-in types: {@code chatui},
     * {@code dingtalk}, {@code wecom}, {@code feishu}, {@code github}, {@code gitlab}. Optional
     * when the channel is registered programmatically via
     * {@link BuilderBootstrap.Builder#channel(io.agentscope.harness.agent.gateway.channel.Channel...)}.
     */
    @JsonProperty("type")
    private String type;

    /**
     * Type-specific provider properties (e.g. credentials, endpoints, signing secrets). Forwarded
     * as-is to {@link ChannelFactory#create(String, ChannelConfig, Map)}.
     */
    @JsonProperty("properties")
    private Map<String, Object> properties;

    /**
     * Fallback agent id when no binding matches. If omitted, falls back to the globally bound main
     * agent.
     */
    @JsonProperty("defaultAgentId")
    private String defaultAgentId;

    /**
     * Controls how DM session keys are scoped. One of {@code MAIN}, {@code PER_PEER}, {@code
     * PER_CHANNEL_PEER}, {@code PER_ACCOUNT_CHANNEL_PEER}. Defaults to {@code MAIN} when omitted.
     *
     * @see DmScope
     */
    @JsonProperty("dmScope")
    private String dmScope;

    /**
     * When {@code true}, this channel entry is ignored at bootstrap time — no channel instance is
     * created and any programmatically registered channel with the same id is not started.
     */
    @JsonProperty("disabled")
    private Boolean disabled;

    /**
     * Ordered list of {@link io.agentscope.harness.agent.gateway.channel.ChannelBinding} routing rules,
     * evaluated by {@link io.agentscope.harness.agent.gateway.channel.ChannelRouter} in priority tiers.
     * First matching binding within the highest-priority tier wins.
     */
    @JsonProperty("bindings")
    private List<BindingConfigEntry> bindings;

    public String getType() {
        return type;
    }

    public void setType(String type) {
        this.type = type;
    }

    public Map<String, Object> getProperties() {
        return properties != null ? properties : Map.of();
    }

    public void setProperties(Map<String, Object> properties) {
        this.properties = properties == null ? null : new LinkedHashMap<>(properties);
    }

    public String getDefaultAgentId() {
        return defaultAgentId;
    }

    public void setDefaultAgentId(String defaultAgentId) {
        this.defaultAgentId = defaultAgentId;
    }

    public String getDmScope() {
        return dmScope;
    }

    public void setDmScope(String dmScope) {
        this.dmScope = dmScope;
    }

    public Boolean getDisabled() {
        return disabled;
    }

    public void setDisabled(Boolean disabled) {
        this.disabled = disabled;
    }

    public List<BindingConfigEntry> getBindings() {
        return bindings;
    }

    public void setBindings(List<BindingConfigEntry> bindings) {
        this.bindings = bindings;
    }

    /** Converts this entry into a {@link ChannelConfig} for the given channel id. */
    public ChannelConfig toChannelConfig(String channelId) {
        DmScope scope = DmScope.MAIN;
        if (dmScope != null && !dmScope.isBlank()) {
            try {
                scope = DmScope.valueOf(dmScope.toUpperCase());
            } catch (IllegalArgumentException ignored) {
                // Unknown value → fall back to MAIN
            }
        }
        List<ChannelBinding> resolved = new ArrayList<>();
        if (bindings != null) {
            for (BindingConfigEntry e : bindings) {
                if (e == null) continue;
                try {
                    resolved.add(e.toBinding());
                } catch (IllegalArgumentException ignored) {
                    // Skip malformed bindings (missing agentId)
                }
            }
        }
        return ChannelConfig.builder(channelId)
                .defaultAgentId(defaultAgentId)
                .dmScope(scope)
                .bindings(resolved)
                .build();
    }
}

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
package io.agentscope.claw2.runtime.config;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;
import io.agentscope.claw2.runtime.ClawBootstrap;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;

/**
 * Root document for {@code ${cwd}/.agentscope/agentscope.json}.
 *
 * <p>Contains a {@code main} entry id, an {@code agents} map keyed by agent id, and an optional
 * {@code channels} map keyed by channel id.
 */
@JsonInclude(JsonInclude.Include.NON_EMPTY)
@JsonIgnoreProperties(ignoreUnknown = true)
public class AgentscopeConfig {

    /** Optional JSON Schema reference for editors. */
    @JsonProperty("$schema")
    private String schema;

    /**
     * Agent id of the default entry point. Programmatic {@link
     * ClawBootstrap.Builder#mainAgent(String)} overrides this when set.
     */
    @JsonProperty("main")
    private String main;

    @JsonProperty("agents")
    private Map<String, AgentConfigEntry> agents = new LinkedHashMap<>();

    /**
     * Optional channel configurations keyed by channel id (e.g. {@code "chatui"}, {@code
     * "slack"}). The built-in {@code chatui} channel is auto-created from its entry when no
     * programmatic channel registration covers the same id. For other channel types, the entry
     * provides routing config ({@link ChannelConfigEntry#toChannelConfig}) applied at bootstrap.
     */
    @JsonProperty("channels")
    private Map<String, ChannelConfigEntry> channels = new LinkedHashMap<>();

    /**
     * Optional session-lifecycle config (auto-reset, maintenance). Maps to runtime {@link
     * io.agentscope.claw2.runtime.session.SessionMaintenanceConfig} and an internal scheduler for
     * daily / idle resets.
     */
    @JsonProperty("session")
    private SessionLifecycleConfig session;

    /**
     * Optional global skill marketplaces, keyed by a user-chosen id (e.g. {@code "team-git"},
     * {@code "prod-nacos"}). Materialised at startup into the claw-side
     * {@code ClawMarketplaceRegistry}; users browse and install skills from these into any
     * agent's {@code workspace/skills/}. Independent of the runtime
     * {@code agents.<id>.skillRepositories} field, which controls what the agent itself loads at
     * runtime.
     */
    @JsonProperty("marketplaces")
    private Map<String, MarketplaceConfigEntry> marketplaces = new LinkedHashMap<>();

    public String getSchema() {
        return schema;
    }

    public void setSchema(String schema) {
        this.schema = schema;
    }

    public String getMain() {
        return main;
    }

    public void setMain(String main) {
        this.main = main;
    }

    public Map<String, AgentConfigEntry> getAgents() {
        return agents;
    }

    public void setAgents(Map<String, AgentConfigEntry> agents) {
        this.agents = agents != null ? agents : new LinkedHashMap<>();
    }

    public Map<String, ChannelConfigEntry> getChannels() {
        return channels;
    }

    public void setChannels(Map<String, ChannelConfigEntry> channels) {
        this.channels = Objects.requireNonNullElseGet(channels, LinkedHashMap::new);
    }

    public SessionLifecycleConfig getSession() {
        return session;
    }

    public void setSession(SessionLifecycleConfig session) {
        this.session = session;
    }

    public Map<String, MarketplaceConfigEntry> getMarketplaces() {
        return marketplaces;
    }

    public void setMarketplaces(Map<String, MarketplaceConfigEntry> marketplaces) {
        this.marketplaces = Objects.requireNonNullElseGet(marketplaces, LinkedHashMap::new);
    }
}

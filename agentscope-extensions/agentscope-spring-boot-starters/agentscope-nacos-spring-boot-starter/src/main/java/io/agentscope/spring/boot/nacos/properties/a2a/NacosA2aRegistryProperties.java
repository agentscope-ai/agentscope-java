/*
 * Copyright 2024-2026 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * You may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package io.agentscope.spring.boot.nacos.properties.a2a;

/**
 * A2a properties for registering to Nacos A2A Registry.
 *
 * <p>This class contains the sub properties for registering Agents to Nacos A2A Registry. To help developers and users
 * control the register behaviors according to their requests.
 *
 * <p>Example with yaml:
 * <pre>{@code
 * agentscope:
 *   a2a:
 *     nacos:
 *       registry:
 *         enabled: true
 *         overwrite-preferred-transport: JSONRPC
 * }</pre>
 *
 * <p>The discovery properties see {@link NacosA2aDiscoveryProperties}.
 */
public class NacosA2aRegistryProperties {

    /**
     * Whether to enable Nacos A2A Registry.
     *
     * <p>Default is {@code true}, which means the program will read the all information about this agent such as
     * AgentCard and A2A Transport information from {@link io.agentscope.core.a2a.server.AgentScopeA2aServer} and
     * register this agent to Nacos.
     */
    private boolean enabled = true;

    /**
     * Whether register current new version as the latest version of agent.
     *
     * <p>Default is {@code true}, which means register current new version as the latest version of agent.
     *
     * <p>Only affects the non-exist new version of this agent, If current version of this agent already registered
     * in Nacos, the register behavior will be ignored by Nacos Server, which means even configure the properties as
     * {@code true}, the current version will not be changed to latest version.
     *
     * <p>It is designed for avoiding the latest version of agent to be overwritten by other agent.
     */
    private boolean registerAsLatest = true;

    /**
     * Whether enable register transport endpoint(s) to Nacos under this agent.
     *
     * <p>Default is {@code true}, which means the program will read the A2A Transport information that needs to be
     * exposed from {@link io.agentscope.core.a2a.server.AgentScopeA2aServer}, and convert it
     * into an A2A Endpoint to register with Nacos.
     *
     * <p>Property {@link #enabled} is higher priority, If {@link #enabled} is false, the register endpoint feature also
     * disabled even if this property is {@code true}.
     */
    private boolean enabledRegisterEndpoint = true;

    /**
     * If setting with this property, the preferredTransport and url in agentCard will be overwritten.
     *
     * <p>If not found the transport from agentscope and all properties, overwrite will be ignored.
     */
    private String overwritePreferredTransport;

    public boolean isEnabled() {
        return enabled;
    }

    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
    }

    public boolean isRegisterAsLatest() {
        return registerAsLatest;
    }

    public void setRegisterAsLatest(boolean registerAsLatest) {
        this.registerAsLatest = registerAsLatest;
    }

    public boolean isEnabledRegisterEndpoint() {
        return enabledRegisterEndpoint;
    }

    public void setEnabledRegisterEndpoint(boolean enabledRegisterEndpoint) {
        this.enabledRegisterEndpoint = enabledRegisterEndpoint;
    }

    public String getOverwritePreferredTransport() {
        return overwritePreferredTransport;
    }

    public void setOverwritePreferredTransport(String overwritePreferredTransport) {
        this.overwritePreferredTransport = overwritePreferredTransport;
    }
}

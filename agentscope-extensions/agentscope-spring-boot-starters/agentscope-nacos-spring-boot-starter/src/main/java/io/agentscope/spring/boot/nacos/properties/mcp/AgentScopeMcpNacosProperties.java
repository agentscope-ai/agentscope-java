/*
 * Copyright 2024-2026 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
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

package io.agentscope.spring.boot.nacos.properties.mcp;

import io.agentscope.spring.boot.nacos.constants.NacosConstants;
import io.agentscope.spring.boot.nacos.properties.AgentScopeNacosProperties;
import io.agentscope.spring.boot.nacos.properties.BaseNacosProperties;
import java.util.LinkedHashMap;
import java.util.Map;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Spring Boot configuration properties for AgentScope Nacos MCP integration.
 *
 * <p>This class extends {@link BaseNacosProperties} so that a dedicated Nacos cluster can be
 * configured for MCP discovery; unspecified fields fall back to {@link AgentScopeNacosProperties}.
 * It uses the prefix from {@link NacosConstants#NACOS_MCP_PREFIX} for property binding.
 *
 * <p>Example with yaml:
 * <pre>{@code
 * agentscope:
 *   nacos:
 *     server-addr: 127.0.0.1:8848
 *     namespace: public
 *     mcp:
 *       enabled: true
 *       load-balance: round-robin
 *       connections:
 *         weather:
 *           service-name: weather-mcp-server
 *           version: 1.0.0
 *         amap:
 *           service-name: amap-mcp-server
 *           load-balance: sticky
 * }</pre>
 */
@ConfigurationProperties(prefix = NacosConstants.NACOS_MCP_PREFIX)
public class AgentScopeMcpNacosProperties extends BaseNacosProperties {

    /**
     * Whether the Nacos MCP discovery integration is enabled. Gates the auto-configuration through
     * {@code agentscope.nacos.mcp.enabled}.
     */
    private boolean enabled = false;

    /**
     * The default load balancing strategy applied to connections that do not override it.
     */
    private NacosMcpConnectionProperties.LoadBalanceStrategy loadBalance =
            NacosMcpConnectionProperties.LoadBalanceStrategy.ROUND_ROBIN;

    /**
     * The MCP server connections to subscribe from the Nacos MCP registry. The map key is the
     * logical MCP client name, the value describes the registered MCP server to subscribe.
     */
    private Map<String, NacosMcpConnectionProperties> connections = new LinkedHashMap<>();

    public boolean isEnabled() {
        return enabled;
    }

    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
    }

    public NacosMcpConnectionProperties.LoadBalanceStrategy getLoadBalance() {
        return loadBalance;
    }

    public void setLoadBalance(NacosMcpConnectionProperties.LoadBalanceStrategy loadBalance) {
        this.loadBalance = loadBalance;
    }

    public Map<String, NacosMcpConnectionProperties> getConnections() {
        return connections;
    }

    public void setConnections(Map<String, NacosMcpConnectionProperties> connections) {
        this.connections = connections;
    }
}

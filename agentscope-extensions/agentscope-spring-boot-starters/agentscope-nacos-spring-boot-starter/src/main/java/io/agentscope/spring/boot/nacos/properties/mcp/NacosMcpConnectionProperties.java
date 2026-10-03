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

/**
 * Properties describing one MCP server connection discovered from the Nacos MCP registry.
 *
 * <p>Each connection entry under {@code agentscope.nacos.mcp.connections} maps a logical client
 * name to a registered MCP server:
 * <pre>{@code
 * agentscope:
 *   nacos:
 *     mcp:
 *       enabled: true
 *       connections:
 *         weather:
 *           service-name: weather-mcp-server
 *           version: 1.0.0
 *           load-balance: round-robin
 * }</pre>
 */
public class NacosMcpConnectionProperties {

    /**
     * Load balancing strategy for distributing tool calls across MCP server endpoints.
     */
    public enum LoadBalanceStrategy {
        /** Distribute calls evenly across endpoints; requires stateless MCP server instances. */
        ROUND_ROBIN,
        /** Stick to one endpoint as long as it stays available; for stateful MCP servers. */
        STICKY
    }

    /**
     * The name of the MCP server registered in the Nacos MCP registry.
     */
    private String serviceName;

    /**
     * The version of the MCP server; empty means the default version.
     */
    private String version;

    /**
     * The load balancing strategy; null means falling back to the global
     * {@code agentscope.nacos.mcp.load-balance}.
     */
    private LoadBalanceStrategy loadBalance;

    public String getServiceName() {
        return serviceName;
    }

    public void setServiceName(String serviceName) {
        this.serviceName = serviceName;
    }

    public String getVersion() {
        return version;
    }

    public void setVersion(String version) {
        this.version = version;
    }

    public LoadBalanceStrategy getLoadBalance() {
        return loadBalance;
    }

    public void setLoadBalance(LoadBalanceStrategy loadBalance) {
        this.loadBalance = loadBalance;
    }
}

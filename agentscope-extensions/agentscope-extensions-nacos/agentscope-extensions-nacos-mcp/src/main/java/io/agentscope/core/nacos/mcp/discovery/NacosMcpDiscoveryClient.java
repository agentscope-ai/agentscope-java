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
package io.agentscope.core.nacos.mcp.discovery;

import com.alibaba.nacos.api.ai.AiFactory;
import com.alibaba.nacos.api.ai.AiService;
import com.alibaba.nacos.api.ai.listener.AbstractNacosMcpServerListener;
import com.alibaba.nacos.api.ai.model.mcp.McpEndpointInfo;
import com.alibaba.nacos.api.ai.model.mcp.McpServerDetailInfo;
import com.alibaba.nacos.api.exception.NacosException;
import java.util.ArrayList;
import java.util.List;
import java.util.Properties;

/**
 * Discovery client for MCP servers registered in the Nacos MCP registry.
 *
 * <p>This client wraps the Nacos {@link AiService} and provides push-based subscription to MCP
 * server metadata (backend endpoints, export path and protocol). It requires a Nacos 3.x server
 * with the MCP registry capability enabled.
 *
 * <p>Example usage:
 * <pre>{@code
 * Properties properties = new Properties();
 * properties.put(PropertyKeyConst.SERVER_ADDR, "localhost:8848");
 * NacosMcpDiscoveryClient discoveryClient = new NacosMcpDiscoveryClient(properties);
 * }</pre>
 *
 * @see io.agentscope.core.nacos.mcp.loadbalance.NacosLoadBalancedMcpClientWrapper
 */
public class NacosMcpDiscoveryClient {

    private static final int MAX_PORT = 65535;

    private final AiService aiService;

    /**
     * Creates a new instance with a fresh Nacos client built from the given properties.
     *
     * @param properties the Nacos client properties (server address, namespace, auth, etc.)
     * @throws NacosException if the Nacos client cannot be created
     */
    public NacosMcpDiscoveryClient(Properties properties) throws NacosException {
        this(AiFactory.createAiService(properties));
    }

    /**
     * Creates a new instance reusing an existing Nacos {@link AiService}.
     *
     * @param aiService the Nacos AI service
     */
    public NacosMcpDiscoveryClient(AiService aiService) {
        this.aiService = aiService;
    }

    /**
     * Subscribes to an MCP server registered in the Nacos MCP registry.
     *
     * <p>The returned detail info reflects the current state of the MCP server; the given listener
     * is invoked whenever the server metadata (endpoints, protocol, export path, etc.) changes.
     *
     * @param serverName the name of the MCP server in the Nacos registry
     * @param version the version of the MCP server, may be null to use the default version
     * @param listener the listener notified on MCP server changes
     * @return the current MCP server detail info, or null if not found
     * @throws NacosException if the subscription fails
     */
    public McpServerDetailInfo subscribe(
            String serverName, String version, AbstractNacosMcpServerListener listener)
            throws NacosException {
        if (version == null || version.isEmpty()) {
            return aiService.subscribeMcpServer(serverName, listener);
        }
        return aiService.subscribeMcpServer(serverName, version, listener);
    }

    /**
     * Unsubscribes from an MCP server previously subscribed via
     * {@link #subscribe(String, String, AbstractNacosMcpServerListener)}.
     *
     * @param serverName the name of the MCP server in the Nacos registry
     * @param version the version of the MCP server, may be null
     * @param listener the listener previously registered
     * @throws NacosException if the unsubscription fails
     */
    public void unsubscribe(
            String serverName, String version, AbstractNacosMcpServerListener listener)
            throws NacosException {
        if (version == null || version.isEmpty()) {
            aiService.unsubscribeMcpServer(serverName, listener);
        } else {
            aiService.unsubscribeMcpServer(serverName, version, listener);
        }
    }

    /**
     * Resolves the load-balancable endpoints of an MCP server from its detail info.
     *
     * <p>Backend endpoints are preferred; if the MCP server exposes no backend endpoints, the
     * frontend endpoints are used instead. Entries without an address, or with a port outside
     * {@code 1..65535}, are skipped.
     *
     * @param detailInfo the MCP server detail info from the Nacos registry
     * @return the resolved endpoints, may be empty but never null
     */
    public static List<NacosMcpEndpoint> resolveEndpoints(McpServerDetailInfo detailInfo) {
        List<NacosMcpEndpoint> result = new ArrayList<>();
        if (detailInfo == null) {
            return result;
        }
        List<McpEndpointInfo> endpointInfos = detailInfo.getBackendEndpoints();
        if (endpointInfos == null || endpointInfos.isEmpty()) {
            endpointInfos = detailInfo.getFrontendEndpoints();
        }
        if (endpointInfos == null) {
            return result;
        }
        String exportPath =
                detailInfo.getRemoteServerConfig() != null
                        ? detailInfo.getRemoteServerConfig().getExportPath()
                        : null;
        for (McpEndpointInfo endpointInfo : endpointInfos) {
            if (endpointInfo == null
                    || endpointInfo.getAddress() == null
                    || endpointInfo.getAddress().isEmpty()) {
                continue;
            }
            int port = endpointInfo.getPort();
            if (port <= 0 || port > MAX_PORT) {
                // A bad port would otherwise surface much later as a malformed URL inside the
                // transport, e.g. http://10.0.0.1:0/mcp.
                continue;
            }
            result.add(NacosMcpEndpoint.from(endpointInfo, exportPath));
        }
        return result;
    }
}

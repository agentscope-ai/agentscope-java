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

import com.alibaba.nacos.api.ai.model.mcp.McpEndpointInfo;

/**
 * An immutable view of a single MCP server endpoint resolved from the Nacos MCP registry.
 *
 * <p>Each endpoint represents one backend instance of an MCP server, identified by its address, port,
 * export path and HTTP scheme. Endpoints are used as the unit of load balancing by
 * {@link io.agentscope.core.nacos.mcp.loadbalance.NacosLoadBalancedMcpClientWrapper}.
 */
public class NacosMcpEndpoint {

    private final String address;

    private final int port;

    private final String path;

    private final String scheme;

    /**
     * Constructs a new endpoint.
     *
     * @param address the host or IP address of the endpoint
     * @param port the port of the endpoint
     * @param path the export path of the MCP server (e.g. {@code /mcp}); it is normalized to always
     *     start with {@code /} and never end with one, so that {@code mcp}, {@code /mcp} and
     *     {@code /mcp/} describe the same endpoint
     * @param scheme the HTTP scheme, either {@code http} or {@code https}
     */
    public NacosMcpEndpoint(String address, int port, String path, String scheme) {
        this.address = address;
        this.port = port;
        this.path = normalizePath(path);
        this.scheme = scheme;
    }

    /**
     * Normalizes an export path so that it can be concatenated after {@code host:port}: an empty or
     * null path becomes {@code /}, a path without a leading slash gets one, and a trailing slash is
     * dropped.
     */
    private static String normalizePath(String path) {
        if (path == null || path.isEmpty()) {
            return "/";
        }
        String normalized = path.startsWith("/") ? path : "/" + path;
        while (normalized.length() > 1 && normalized.endsWith("/")) {
            normalized = normalized.substring(0, normalized.length() - 1);
        }
        return normalized;
    }

    /**
     * Creates an endpoint from a Nacos {@link McpEndpointInfo} and the MCP server export path.
     *
     * <p>If the endpoint info does not carry an explicit {@code http}/{@code https} protocol, the
     * scheme is inferred from the port (443/8443 means {@code https}, otherwise {@code http}).
     *
     * @param endpointInfo the endpoint info from the Nacos MCP registry
     * @param exportPath the export path of the MCP server
     * @return the resolved endpoint
     */
    public static NacosMcpEndpoint from(McpEndpointInfo endpointInfo, String exportPath) {
        String scheme = endpointInfo.getProtocol();
        if (scheme == null || (!"http".equals(scheme) && !"https".equals(scheme))) {
            int port = endpointInfo.getPort();
            scheme = (port == 443 || port == 8443) ? "https" : "http";
        }
        String path = exportPath;
        if (path == null || path.isEmpty()) {
            path = endpointInfo.getPath();
        }
        return new NacosMcpEndpoint(
                endpointInfo.getAddress(), endpointInfo.getPort(), path, scheme);
    }

    /**
     * Returns a unique key identifying this endpoint, used to diff endpoint changes.
     *
     * <p>The scheme is part of the key: an MCP server that flips from {@code http} to {@code https}
     * on the same address, port and path becomes a different endpoint, so the reconcile closes the
     * stale connection instead of keeping it.
     *
     * @return the endpoint key in the form of {@code scheme@@address@@port@@path}
     */
    public String key() {
        return scheme + "@@" + address + "@@" + port + "@@" + path;
    }

    /**
     * Returns the full URL of this endpoint, e.g. {@code http://127.0.0.1:8080/mcp}.
     *
     * @return the endpoint URL
     */
    public String url() {
        return scheme + "://" + address + ":" + port + path;
    }

    public String getAddress() {
        return address;
    }

    public int getPort() {
        return port;
    }

    public String getPath() {
        return path;
    }

    public String getScheme() {
        return scheme;
    }

    @Override
    public String toString() {
        return url();
    }
}

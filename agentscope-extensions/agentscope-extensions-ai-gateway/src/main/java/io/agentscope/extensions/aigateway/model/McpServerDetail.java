/*
 * Copyright 2024-2025 the original author or authors.
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
package io.agentscope.extensions.aigateway.model;

import java.util.List;

/**
 * Detailed information about an MCP Server from AI Gateway.
 *
 * <p>Contains all metadata returned from the ListMcpServers API, including server configuration,
 * domain information, backend services, and deployment status.
 */
public class McpServerDetail {

    private String mcpServerId;
    private String name;
    private String description;
    private String type;
    private String protocol;
    private String mcpServerPath;
    private String exposedUriPath;
    private String gatewayId;
    private String environmentId;
    private String routeId;
    private String deployStatus;
    private String createFromType;
    private List<String> domainIds;
    private List<DomainInfo> domainInfos;
    private Backend backend;
    private List<AssembledSource> assembledSources;

    public String getMcpServerId() {
        return mcpServerId;
    }

    public void setMcpServerId(String mcpServerId) {
        this.mcpServerId = mcpServerId;
    }

    public String getName() {
        return name;
    }

    public void setName(String name) {
        this.name = name;
    }

    public String getDescription() {
        return description;
    }

    public void setDescription(String description) {
        this.description = description;
    }

    public String getType() {
        return type;
    }

    public void setType(String type) {
        this.type = type;
    }

    public String getProtocol() {
        return protocol;
    }

    public void setProtocol(String protocol) {
        this.protocol = protocol;
    }

    public String getMcpServerPath() {
        return mcpServerPath;
    }

    public void setMcpServerPath(String mcpServerPath) {
        this.mcpServerPath = mcpServerPath;
    }

    public String getExposedUriPath() {
        return exposedUriPath;
    }

    public void setExposedUriPath(String exposedUriPath) {
        this.exposedUriPath = exposedUriPath;
    }

    public String getGatewayId() {
        return gatewayId;
    }

    public void setGatewayId(String gatewayId) {
        this.gatewayId = gatewayId;
    }

    public String getEnvironmentId() {
        return environmentId;
    }

    public void setEnvironmentId(String environmentId) {
        this.environmentId = environmentId;
    }

    public String getRouteId() {
        return routeId;
    }

    public void setRouteId(String routeId) {
        this.routeId = routeId;
    }

    public String getDeployStatus() {
        return deployStatus;
    }

    public void setDeployStatus(String deployStatus) {
        this.deployStatus = deployStatus;
    }

    public String getCreateFromType() {
        return createFromType;
    }

    public void setCreateFromType(String createFromType) {
        this.createFromType = createFromType;
    }

    public List<String> getDomainIds() {
        return domainIds;
    }

    public void setDomainIds(List<String> domainIds) {
        this.domainIds = domainIds;
    }

    public List<DomainInfo> getDomainInfos() {
        return domainInfos;
    }

    public void setDomainInfos(List<DomainInfo> domainInfos) {
        this.domainInfos = domainInfos;
    }

    public Backend getBackend() {
        return backend;
    }

    public void setBackend(Backend backend) {
        this.backend = backend;
    }

    public List<AssembledSource> getAssembledSources() {
        return assembledSources;
    }

    public void setAssembledSources(List<AssembledSource> assembledSources) {
        this.assembledSources = assembledSources;
    }

    /**
     * Gets the full SSE endpoint URL for this MCP server.
     *
     * @return the SSE endpoint URL, or null if no domain info available
     */
    public String getSseEndpointUrl() {
        if (domainInfos == null || domainInfos.isEmpty()) {
            return null;
        }
        DomainInfo domain = domainInfos.get(0);
        String protocol = "HTTP".equalsIgnoreCase(domain.getProtocol()) ? "http" : "https";
        String path = mcpServerPath != null ? mcpServerPath : "";
        String ssePath = exposedUriPath != null ? exposedUriPath : "/sse";
        return protocol + "://" + domain.getName() + path + ssePath;
    }

    /**
     * Gets the Streamable HTTP endpoint URL for this MCP server.
     *
     * @return the HTTP endpoint URL, or null if no domain info available
     */
    public String getHttpEndpointUrl() {
        if (domainInfos == null || domainInfos.isEmpty()) {
            return null;
        }
        DomainInfo domain = domainInfos.get(0);
        String protocol = "HTTP".equalsIgnoreCase(domain.getProtocol()) ? "http" : "https";
        String path = mcpServerPath != null ? mcpServerPath : "";
        return protocol + "://" + domain.getName() + path;
    }

    @Override
    public String toString() {
        return "McpServerDetail{"
                + "mcpServerId='"
                + mcpServerId
                + '\''
                + ", name='"
                + name
                + '\''
                + ", description='"
                + description
                + '\''
                + ", type='"
                + type
                + '\''
                + ", deployStatus='"
                + deployStatus
                + '\''
                + '}';
    }

    /** Domain information for an MCP server. */
    public static class DomainInfo {
        private String domainId;
        private String name;
        private String protocol;

        public String getDomainId() {
            return domainId;
        }

        public void setDomainId(String domainId) {
            this.domainId = domainId;
        }

        public String getName() {
            return name;
        }

        public void setName(String name) {
            this.name = name;
        }

        public String getProtocol() {
            return protocol;
        }

        public void setProtocol(String protocol) {
            this.protocol = protocol;
        }
    }

    /** Backend service configuration. */
    public static class Backend {
        private String scene;
        private List<Service> services;

        public String getScene() {
            return scene;
        }

        public void setScene(String scene) {
            this.scene = scene;
        }

        public List<Service> getServices() {
            return services;
        }

        public void setServices(List<Service> services) {
            this.services = services;
        }
    }

    /** Backend service definition. */
    public static class Service {
        private String serviceId;
        private String name;
        private String protocol;
        private int port;
        private int weight;
        private String version;

        public String getServiceId() {
            return serviceId;
        }

        public void setServiceId(String serviceId) {
            this.serviceId = serviceId;
        }

        public String getName() {
            return name;
        }

        public void setName(String name) {
            this.name = name;
        }

        public String getProtocol() {
            return protocol;
        }

        public void setProtocol(String protocol) {
            this.protocol = protocol;
        }

        public int getPort() {
            return port;
        }

        public void setPort(int port) {
            this.port = port;
        }

        public int getWeight() {
            return weight;
        }

        public void setWeight(int weight) {
            this.weight = weight;
        }

        public String getVersion() {
            return version;
        }

        public void setVersion(String version) {
            this.version = version;
        }
    }

    /** Assembled MCP source information. */
    public static class AssembledSource {
        private String mcpServerId;
        private String mcpServerName;
        private List<String> tools;

        public String getMcpServerId() {
            return mcpServerId;
        }

        public void setMcpServerId(String mcpServerId) {
            this.mcpServerId = mcpServerId;
        }

        public String getMcpServerName() {
            return mcpServerName;
        }

        public void setMcpServerName(String mcpServerName) {
            this.mcpServerName = mcpServerName;
        }

        public List<String> getTools() {
            return tools;
        }

        public void setTools(List<String> tools) {
            this.tools = tools;
        }
    }
}

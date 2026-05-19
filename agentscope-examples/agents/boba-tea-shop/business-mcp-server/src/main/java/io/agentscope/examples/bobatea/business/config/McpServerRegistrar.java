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

package io.agentscope.examples.bobatea.business.config;

import com.alibaba.nacos.api.ai.AiService;
import com.alibaba.nacos.api.ai.constant.AiConstants;
import com.alibaba.nacos.api.ai.model.mcp.McpEndpointSpec;
import com.alibaba.nacos.api.ai.model.mcp.McpServerBasicInfo;
import com.alibaba.nacos.api.ai.model.mcp.McpServerRemoteServiceConfig;
import com.alibaba.nacos.api.ai.model.mcp.McpTool;
import com.alibaba.nacos.api.ai.model.mcp.McpToolSpecification;
import com.alibaba.nacos.api.ai.model.mcp.registry.ServerVersionDetail;
import com.alibaba.nacos.api.exception.NacosException;
import com.alibaba.nacos.api.utils.NetUtils;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

/**
 * MCP Server Registrar.
 * Registers the MCP Server to Nacos when application starts.
 *
 */
@Component
public class McpServerRegistrar {

    private static final Logger logger = LoggerFactory.getLogger(McpServerRegistrar.class);

    @Autowired private AiService aiService;

    @Value("${agentscope.mcp.nacos.register.enabled:true}")
    private boolean registerEnabled;

    @Value("${server.port:10002}")
    private int serverPort;

    @Value("${agentscope.mcp.nacos.register.server-name:business-mcp-server}")
    private String serverName;

    @Value("${agentscope.mcp.nacos.register.server-version:0.0.1}")
    private String serverVersion;

    @Value("${agentscope.mcp.nacos.register.server-description:Business MCP Server}")
    private String serverDescription;

    @Value("${agentscope.mcp.nacos.register.protocol:sse}")
    private String protocol;

    @Value("${agentscope.mcp.nacos.register.endpoint-path:/sse}")
    private String endpointPath;

    @Value("${agentscope.mcp.nacos.namespace}")
    private String namespace;

    @EventListener(ApplicationReadyEvent.class)
    public void registerMcpServerToNacos() {
        logger.info(
                "MCP Server registration check - enabled: {}, aiService: {}",
                registerEnabled,
                aiService != null ? "available" : "not available");

        if (!registerEnabled) {
            logger.info("MCP Server registration is disabled.");
            return;
        }

        if (aiService == null) {
            logger.warn("AiService is not available, skipping MCP Server registration to Nacos.");
            return;
        }

        try {
            logger.info("Starting MCP Server registration to Nacos...");

            McpServerBasicInfo serverSpec = new McpServerBasicInfo();
            ServerVersionDetail versionDetail = new ServerVersionDetail();
            versionDetail.setVersion(serverVersion);
            serverSpec.setName(serverName);
            serverSpec.setVersion(serverVersion);
            serverSpec.setVersionDetail(versionDetail);
            serverSpec.setDescription(serverDescription);
            serverSpec.setProtocol(protocol);

            McpServerRemoteServiceConfig remoteServerConfigInfo =
                    new McpServerRemoteServiceConfig();
            remoteServerConfigInfo.setExportPath(endpointPath);
            serverSpec.setRemoteServerConfig(remoteServerConfigInfo);
            serverSpec.setProtocol(AiConstants.Mcp.MCP_PROTOCOL_SSE);
            serverSpec.setFrontProtocol(AiConstants.Mcp.MCP_PROTOCOL_SSE);

            McpEndpointSpec endpointSpec = new McpEndpointSpec();
            String serverAddr = NetUtils.localIp();
            endpointSpec.setType(AiConstants.Mcp.MCP_ENDPOINT_TYPE_REF);
            Map<String, String> endpointSpecData = new HashMap<>();
            endpointSpecData.put("serviceName", serverName);
            String groupName = "DEFAULT_GROUP";
            endpointSpecData.put("groupName", groupName);
            endpointSpecData.put("namespaceId", namespace);
            endpointSpec.setData(endpointSpecData);

            // Build tool specifications using shared definitions
            McpToolSpecification toolSpec = buildToolSpecification();

            try {
                aiService.getMcpServer(serverName, serverVersion);
            } catch (NacosException e) {
                if (e.getErrCode() == NacosException.NOT_FOUND) {
                    try {
                        aiService.releaseMcpServer(serverSpec, toolSpec, endpointSpec);
                    } catch (NacosException ex) {
                        if (ex.getErrCode() == NacosException.CONFLICT) {
                            logger.info(
                                    "MCP Server '{}' already exists in Nacos, skipping release.",
                                    serverName);
                        } else {
                            logger.error(
                                    "Failed to release MCP Server to Nacos: {}", ex.getMessage());
                        }
                    }
                }
            }

            aiService.registerMcpServerEndpoint(serverName, serverAddr, serverPort);

            logger.info(
                    "MCP Server '{}' registered to Nacos successfully. Endpoint: {}, Tools: {}",
                    serverName,
                    serverAddr,
                    toolSpec.getTools().size());

        } catch (Exception e) {
            logger.error("Failed to register MCP Server to Nacos: {}", e.getMessage());
        }
    }

    /**
     * Build tool specification for all MCP tools.
     * Uses shared McpToolDefinitions for consistency with McpServerConfig.
     */
    private McpToolSpecification buildToolSpecification() {
        McpToolSpecification toolSpec = new McpToolSpecification();
        List<McpTool> tools = new ArrayList<>();

        // Convert all tool definitions to McpTool format
        for (McpToolDefinitions.ToolDefinition def : McpToolDefinitions.getAllTools()) {
            tools.add(createTool(def.name(), def.description(), def.schema()));
        }

        toolSpec.setTools(tools);
        return toolSpec;
    }

    private McpTool createTool(String name, String description, Map<String, Object> inputSchema) {
        McpTool tool = new McpTool();
        tool.setName(name);
        tool.setDescription(description);
        tool.setInputSchema(inputSchema);
        return tool;
    }
}

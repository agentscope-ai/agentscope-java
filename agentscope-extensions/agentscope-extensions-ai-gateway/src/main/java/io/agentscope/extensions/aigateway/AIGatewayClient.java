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
package io.agentscope.extensions.aigateway;

import com.aliyun.auth.credentials.Credential;
import com.aliyun.auth.credentials.provider.StaticCredentialProvider;
import com.aliyun.sdk.service.apig20240327.AsyncClient;
import com.aliyun.sdk.service.apig20240327.DefaultAsyncClientBuilder;
import com.aliyun.sdk.service.apig20240327.models.Backend;
import com.aliyun.sdk.service.apig20240327.models.HttpApiDomainInfo;
import com.aliyun.sdk.service.apig20240327.models.ListMcpServersRequest;
import com.aliyun.sdk.service.apig20240327.models.ListMcpServersResponse;
import com.aliyun.sdk.service.apig20240327.models.ListMcpServersResponseBody;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import darabonba.core.client.ClientOverrideConfiguration;
import io.agentscope.core.message.ToolResultBlock;
import io.agentscope.core.tool.AgentTool;
import io.agentscope.core.tool.ToolCallParam;
import io.agentscope.core.tool.mcp.McpClientBuilder;
import io.agentscope.core.tool.mcp.McpClientWrapper;
import io.agentscope.core.tool.mcp.McpContentConverter;
import io.agentscope.extensions.aigateway.config.AIGatewayConfig;
import io.agentscope.extensions.aigateway.config.MCPAuthConfig;
import io.agentscope.extensions.aigateway.exception.GatewayException;
import io.agentscope.extensions.aigateway.model.McpServerDetail;
import io.agentscope.extensions.aigateway.model.SearchedTool;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicLong;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import reactor.core.publisher.Mono;

public class AIGatewayClient implements AutoCloseable {

    private static final Logger logger = LoggerFactory.getLogger(AIGatewayClient.class);

    private static final String TOOL_SEARCH_METHOD = "tools/call";
    private static final String TOOL_SEARCH_NAME = "x_higress_tool_search";
    private static final String TOOL_SEARCH_SERVER_NAME = "union-tools-search";
    private static final String JSON_RPC_VERSION = "2.0";
    private static final Duration DEFAULT_TIMEOUT = Duration.ofSeconds(30);

    private static final TypeReference<Map<String, Object>> MAP_TYPE_REF =
            new TypeReference<Map<String, Object>>() {};

    private final AIGatewayConfig config;
    private final HttpClient httpClient;
    private final ObjectMapper objectMapper;
    private final AtomicLong requestIdGenerator;
    private AsyncClient aliyunClient;
    private volatile McpClientWrapper toolExecutionClient;

    /**
     * Creates a new AIGatewayClient with the specified configuration.
     *
     * @param config the gateway configuration
     * @throws GatewayException if configuration is invalid
     */
    public AIGatewayClient(AIGatewayConfig config) {
        if (config == null) {
            throw new GatewayException("INVALID_CONFIG", "Gateway configuration cannot be null");
        }
        this.config = config;
        this.httpClient =
                HttpClient.newBuilder()
                        .connectTimeout(DEFAULT_TIMEOUT)
                        .version(HttpClient.Version.HTTP_1_1)
                        .build();
        this.objectMapper = new ObjectMapper();
        this.requestIdGenerator = new AtomicLong(1);

        logger.info("AIGatewayClient initialized");
    }

    // ==================== Interface 1: listSearchedTools ====================

    /**
     * Searches for tools using semantic query and returns executable AgentTools.
     *
     * <p>Calls the AI Gateway's tool search endpoint to find the most relevant tools based on the
     * query string. The gateway uses semantic matching to return the top-k most suitable tools.
     * The returned AgentTools can be directly registered with a Toolkit.
     *
     * <p>Example usage:
     * <pre>{@code
     * List<AgentTool> tools = client.listSearchedTools("weather query", 5);
     * for (AgentTool tool : tools) {
     *     toolkit.registration().agentTool(tool).apply();
     * }
     * }</pre>
     *
     * @param query the search query describing what tools are needed
     * @param topK the maximum number of tools to return
     * @return list of executable AgentTools matching the query
     * @throws GatewayException if the search fails
     */
    public List<AgentTool> listSearchedTools(String query, int topK) {
        if (query == null || query.trim().isEmpty()) {
            throw new GatewayException("INVALID_QUERY", "Query cannot be null or empty");
        }
        if (topK <= 0) {
            throw new GatewayException("INVALID_QUERY", "topK should more than 0");
        }

        String endpoint = config.getToolSearchEndpoint();
        if (endpoint == null || endpoint.trim().isEmpty()) {
            throw new GatewayException(
                    "MISSING_ENDPOINT",
                    "Gateway endpoint is not configured. "
                            + "Please set gatewayEndpoint in AIGatewayConfig.");
        }

        logger.info("Searching tools with query: '{}', topK: {}", query, topK);

        try {
            // Build JSON-RPC request
            Map<String, Object> arguments = new HashMap<>();
            arguments.put("query", query);
            arguments.put("topK", topK);

            Map<String, Object> params = new HashMap<>();
            params.put("name", TOOL_SEARCH_NAME);
            params.put("arguments", arguments);

            Map<String, Object> requestBody = new HashMap<>();
            requestBody.put("jsonrpc", JSON_RPC_VERSION);
            requestBody.put("id", requestIdGenerator.getAndIncrement());
            requestBody.put("method", TOOL_SEARCH_METHOD);
            requestBody.put("params", params);

            String jsonBody = objectMapper.writeValueAsString(requestBody);
            logger.debug("Tool search request: {}", jsonBody);

            // Handle query parameter authentication
            String finalEndpoint = applyQueryAuth(endpoint);

            // Build HTTP request
            HttpRequest.Builder requestBuilder =
                    HttpRequest.newBuilder()
                            .uri(URI.create(finalEndpoint))
                            .header("Content-Type", "application/json")
                            .timeout(DEFAULT_TIMEOUT)
                            .POST(HttpRequest.BodyPublishers.ofString(jsonBody));

            // Apply header authentication
            applyHeaderAuth(requestBuilder);

            HttpRequest request = requestBuilder.build();

            // Execute request
            HttpResponse<String> response =
                    httpClient.send(request, HttpResponse.BodyHandlers.ofString());

            if (response.statusCode() != 200) {
                throw GatewayException.builder(
                                "TOOL_SEARCH_HTTP_ERROR",
                                "Tool search request failed with status: " + response.statusCode())
                        .endpoint(endpoint)
                        .context("statusCode", response.statusCode())
                        .context("body", response.body())
                        .build();
            }

            logger.debug("Tool search response: {}", response.body());

            // Parse response and convert to AgentTools
            List<SearchedTool> searchedTools = parseToolSearchResponse(response.body());
            List<AgentTool> agentTools = new ArrayList<>();
            for (SearchedTool searchedTool : searchedTools) {
                agentTools.add(toAgentTool(searchedTool));
            }
            return agentTools;

        } catch (GatewayException e) {
            throw e;
        } catch (Exception e) {
            throw GatewayException.builder(
                            "TOOL_SEARCH_ERROR", "Failed to search tools: " + e.getMessage())
                    .cause(e)
                    .endpoint(endpoint)
                    .build();
        }
    }

    private List<SearchedTool> parseToolSearchResponse(String responseBody) throws Exception {
        JsonNode root = objectMapper.readTree(responseBody);

        // Check for JSON-RPC error
        if (root.has("error")) {
            JsonNode error = root.get("error");
            String errorMsg =
                    error.has("message") ? error.get("message").asText() : "Unknown error";
            throw new GatewayException("TOOL_SEARCH_RPC_ERROR", "JSON-RPC error: " + errorMsg);
        }

        JsonNode result = root.get("result");
        if (result == null) {
            throw new GatewayException("TOOL_SEARCH_PARSE_ERROR", "Missing 'result' in response");
        }

        List<SearchedTool> tools = new ArrayList<>();

        // Try to parse from structuredContent first (preferred)
        if (result.has("structuredContent")) {
            JsonNode structuredContent = result.get("structuredContent");
            if (structuredContent.has("tools")) {
                JsonNode toolsNode = structuredContent.get("tools");
                for (JsonNode toolNode : toolsNode) {
                    tools.add(parseToolNode(toolNode));
                }
            }
        }
        // Fall back to parsing from content[].text
        else if (result.has("content")) {
            JsonNode content = result.get("content");
            for (JsonNode contentItem : content) {
                if ("text".equals(contentItem.path("type").asText())) {
                    String text = contentItem.get("text").asText();
                    JsonNode textJson = objectMapper.readTree(text);
                    if (textJson.has("tools")) {
                        for (JsonNode toolNode : textJson.get("tools")) {
                            tools.add(parseToolNode(toolNode));
                        }
                    }
                }
            }
        }

        logger.info("Parsed {} tools from search response", tools.size());
        return tools;
    }

    /**
     * Applies header-based authentication to the request builder (for tool search).
     *
     * <p>Uses the auth config for "union-tools-search" server.
     *
     * @param requestBuilder the HTTP request builder
     */
    private void applyHeaderAuth(HttpRequest.Builder requestBuilder) {
        MCPAuthConfig authConfig = config.getAuthConfigForServer(TOOL_SEARCH_SERVER_NAME);
        if (authConfig == null || !authConfig.isValid()) {
            return;
        }

        switch (authConfig.getType()) {
            case BEARER:
                requestBuilder.header("Authorization", "Bearer " + authConfig.getToken());
                logger.debug("Applied Bearer token authentication for tool search");
                break;
            case HEADER:
                requestBuilder.header(authConfig.getHeaderName(), authConfig.getHeaderValue());
                logger.debug(
                        "Applied custom header authentication: {}", authConfig.getHeaderName());
                break;
            default:
                // QUERY auth is handled by applyQueryAuth method
                break;
        }
    }

    /**
     * Applies query parameter authentication to the endpoint URL (for tool search).
     *
     * <p>Uses the auth config for "union-tools-search" server.
     *
     * @param endpoint the original endpoint URL
     * @return the endpoint URL with query parameter if applicable
     */
    private String applyQueryAuth(String endpoint) {
        MCPAuthConfig authConfig = config.getAuthConfigForServer(TOOL_SEARCH_SERVER_NAME);
        if (authConfig == null
                || !authConfig.isValid()
                || authConfig.getType() != MCPAuthConfig.AuthType.QUERY) {
            return endpoint;
        }

        String encodedName = URLEncoder.encode(authConfig.getQueryName(), StandardCharsets.UTF_8);
        String encodedValue = URLEncoder.encode(authConfig.getQueryValue(), StandardCharsets.UTF_8);
        String queryParam = encodedName + "=" + encodedValue;

        if (endpoint.contains("?")) {
            return endpoint + "&" + queryParam;
        } else {
            return endpoint + "?" + queryParam;
        }
    }

    /**
     * Converts a SearchedTool to an executable AgentTool.
     *
     * <p>The returned AgentTool can be registered with a Toolkit and invoked by agents. Tool
     * execution is performed via MCP protocol over HTTP.
     *
     * @param searchedTool the tool metadata from search results
     * @return an executable AgentTool
     */
    private AgentTool toAgentTool(SearchedTool searchedTool) {
        return new AgentTool() {
            @Override
            public String getName() {
                return searchedTool.getName();
            }

            @Override
            public String getDescription() {
                return searchedTool.getDescription() != null
                        ? searchedTool.getDescription()
                        : searchedTool.getTitle();
            }

            @Override
            public Map<String, Object> getParameters() {
                return searchedTool.getInputSchema() != null
                        ? searchedTool.getInputSchema()
                        : new HashMap<>();
            }

            @Override
            public Mono<ToolResultBlock> callAsync(ToolCallParam param) {
                return getToolExecutionClient()
                        .flatMap(
                                client -> {
                                    logger.debug(
                                            "Calling tool '{}' with input: {}",
                                            searchedTool.getName(),
                                            param.getInput());
                                    return client.callTool(searchedTool.getName(), param.getInput())
                                            .map(McpContentConverter::convertCallToolResult);
                                })
                        .doOnSuccess(result -> logger.debug("Tool '{}' completed", getName()))
                        .onErrorResume(
                                e -> {
                                    logger.error(
                                            "Error calling tool '{}': {}",
                                            getName(),
                                            e.getMessage());
                                    return Mono.just(
                                            ToolResultBlock.error(
                                                    "Tool call error: " + e.getMessage()));
                                });
            }
        };
    }

    /**
     * Gets or creates the shared MCP client for tool execution.
     *
     * @return Mono emitting the initialized MCP client
     */
    private Mono<McpClientWrapper> getToolExecutionClient() {
        if (toolExecutionClient != null) {
            return Mono.just(toolExecutionClient);
        }

        return Mono.defer(
                () -> {
                    synchronized (this) {
                        if (toolExecutionClient != null) {
                            return Mono.just(toolExecutionClient);
                        }

                        String endpoint = getToolExecutionEndpoint();
                        McpClientBuilder builder =
                                McpClientBuilder.create("gateway-tool-execution")
                                        .streamableHttpTransport(endpoint)
                                        .timeout(DEFAULT_TIMEOUT);

                        // Apply authentication
                        applyToolExecutionAuth(builder);

                        return builder.buildAsync()
                                .flatMap(client -> client.initialize().thenReturn(client))
                                .doOnSuccess(
                                        client -> {
                                            toolExecutionClient = client;
                                            logger.info(
                                                    "Tool execution MCP client initialized: {}",
                                                    endpoint);
                                        });
                    }
                });
    }

    /**
     * Gets the endpoint URL for tool execution.
     */
    private String getToolExecutionEndpoint() {
        String endpoint = config.getToolSearchEndpoint();
        // Apply query parameter authentication if configured
        return applyQueryAuth(endpoint);
    }

    /**
     * Applies authentication to the tool execution MCP client builder.
     */
    private void applyToolExecutionAuth(McpClientBuilder builder) {
        MCPAuthConfig authConfig = config.getAuthConfigForServer(TOOL_SEARCH_SERVER_NAME);
        if (authConfig == null || !authConfig.isValid()) {
            return;
        }

        switch (authConfig.getType()) {
            case BEARER:
                builder.header("Authorization", "Bearer " + authConfig.getToken());
                logger.debug("Applied Bearer token authentication for tool execution");
                break;
            case HEADER:
                builder.header(authConfig.getHeaderName(), authConfig.getHeaderValue());
                logger.debug(
                        "Applied custom header authentication: {}", authConfig.getHeaderName());
                break;
            default:
                // QUERY auth is handled in getToolExecutionEndpoint
                break;
        }
    }

    private SearchedTool parseToolNode(JsonNode toolNode) {
        SearchedTool tool = new SearchedTool();
        tool.setName(toolNode.path("name").asText(null));
        tool.setTitle(toolNode.path("title").asText(null));
        tool.setDescription(toolNode.path("description").asText(null));

        if (toolNode.has("inputSchema")) {
            tool.setInputSchema(
                    objectMapper.convertValue(toolNode.get("inputSchema"), MAP_TYPE_REF));
        }
        if (toolNode.has("outputSchema")) {
            tool.setOutputSchema(
                    objectMapper.convertValue(toolNode.get("outputSchema"), MAP_TYPE_REF));
        }

        return tool;
    }

    // ==================== Interface 2: listAllMcpClients ====================

    /**
     * Fetches all MCP servers from the gateway using Aliyun SDK.
     *
     * @return list of all MCP server details
     * @throws GatewayException if the API call fails
     */
    private List<McpServerDetail> fetchMcpServersFromGateway() {
        String gatewayId = config.getGatewayId();
        if (gatewayId == null || gatewayId.trim().isEmpty()) {
            throw new GatewayException("MISSING_GATEWAY_ID", "Gateway ID is not configured");
        }

        logger.info("Listing MCP servers for gateway: {}", gatewayId);

        try {
            AsyncClient client = getAliyunClient();
            List<McpServerDetail> allServers = new ArrayList<>();
            int pageNumber = 1;
            int pageSize = 100;

            while (true) {
                ListMcpServersRequest request =
                        ListMcpServersRequest.builder()
                                .gatewayId(gatewayId)
                                .pageNumber(pageNumber)
                                .pageSize(pageSize)
                                .build();

                CompletableFuture<ListMcpServersResponse> responseFuture =
                        client.listMcpServers(request);
                ListMcpServersResponse response = responseFuture.get();
                ListMcpServersResponseBody body = response.getBody();

                if (body == null || body.getData() == null || body.getData().getItems() == null) {
                    break;
                }

                List<ListMcpServersResponseBody.Items> items = body.getData().getItems();

                for (ListMcpServersResponseBody.Items item : items) {
                    allServers.add(convertToMcpServerDetail(item));
                }

                // Check if there are more pages
                Integer totalSize = body.getData().getTotalSize();
                if (totalSize == null || allServers.size() >= totalSize) {
                    break;
                }
                pageNumber++;
            }

            logger.info("Found {} MCP servers", allServers.size());
            return allServers;

        } catch (GatewayException e) {
            throw e;
        } catch (Exception e) {
            throw GatewayException.builder(
                            "LIST_MCP_SERVERS_ERROR",
                            "Failed to list MCP servers: " + e.getMessage())
                    .cause(e)
                    .gatewayId(gatewayId)
                    .build();
        }
    }

    /**
     * Lists all MCP servers and returns connected McpClientWrappers.
     *
     * <p>This method fetches all deployed MCP servers from the gateway and creates McpClientWrapper
     * instances for each. Only servers with "Deployed" status are connected.
     *
     * <p>Example usage:
     *
     * <pre>{@code
     * List<McpClientWrapper> clients = gatewayClient.listAllMcpClients();
     * for (McpClientWrapper client : clients) {
     *     toolkit.registration().mcpClient(client).apply();
     * }
     * }</pre>
     *
     * @return list of connected McpClientWrappers
     * @throws GatewayException if the API call fails
     */
    public List<McpClientWrapper> listAllMcpClients() {
        List<McpServerDetail> servers = fetchMcpServersFromGateway();
        List<McpClientWrapper> clients = new ArrayList<>();

        for (McpServerDetail server : servers) {
            // Only connect to deployed servers
            if (!"Deployed".equals(server.getDeployStatus())) {
                logger.debug(
                        "Skipping server {} with status: {}",
                        server.getName(),
                        server.getDeployStatus());
                continue;
            }

            // Build endpoint from gatewayEndpoint + mcpServerPath
            String endpoint = buildMcpEndpoint(server);
            if (endpoint == null) {
                logger.warn(
                        "Skipping server {} - no valid endpoint could be constructed",
                        server.getName());
                continue;
            }

            try {
                // Determine transport type based on exposedUriPath
                // Default to SSE as most AI Gateway MCP servers use SSE protocol
                String exposedUriPath = server.getExposedUriPath();

                // Use Streamable HTTP only if exposedUriPath explicitly indicates it
                // (e.g., contains "mcp" but not "sse")
                // Otherwise default to SSE for compatibility with AI Gateway
                boolean useStreamableHttp =
                        exposedUriPath != null
                                && !exposedUriPath.isEmpty()
                                && exposedUriPath.contains("mcp")
                                && !exposedUriPath.contains("sse");

                // Build the full endpoint URL
                String fullEndpoint;
                if (useStreamableHttp) {
                    fullEndpoint = endpoint;
                } else {
                    // For SSE, append /sse if not already present
                    fullEndpoint = endpoint.endsWith("/sse") ? endpoint : endpoint + "/sse";
                }

                String transportType = useStreamableHttp ? "Streamable HTTP" : "SSE";

                logger.info(
                        "Connecting to MCP server: {} at {} ({}) [exposedUriPath={}]",
                        server.getName(),
                        fullEndpoint,
                        transportType,
                        exposedUriPath);

                McpClientBuilder builder = McpClientBuilder.create(server.getName());
                if (useStreamableHttp) {
                    builder.streamableHttpTransport(fullEndpoint);
                } else {
                    builder.sseTransport(fullEndpoint);
                }

                // Apply authentication: server-specific config takes priority over default
                MCPAuthConfig authConfig = config.getAuthConfigForServer(server.getName());
                if (authConfig != null && authConfig.isValid()) {
                    logger.debug(
                            "Applying auth config for MCP server {}: {}",
                            server.getName(),
                            authConfig.getType());
                    switch (authConfig.getType()) {
                        case BEARER:
                            builder.header("Authorization", "Bearer " + authConfig.getToken());
                            break;
                        case HEADER:
                            builder.header(authConfig.getHeaderName(), authConfig.getHeaderValue());
                            break;
                        case QUERY:
                            builder.queryParam(
                                    authConfig.getQueryName(), authConfig.getQueryValue());
                            break;
                    }
                }

                McpClientWrapper client = builder.buildAsync().block();
                clients.add(client);
                logger.info("Connected to MCP server: {}", server.getName());

            } catch (Exception e) {
                logger.warn(
                        "Failed to connect to MCP server {}: {}", server.getName(), e.getMessage());
            }
        }

        logger.info("Connected to {} MCP servers", clients.size());
        return clients;
    }

    /**
     * Builds the full endpoint URL for an MCP server.
     *
     * <p>Priority:
     * <ol>
     *   <li>If gatewayEndpoint is configured: gatewayEndpoint + mcpServerPath
     *   <li>Otherwise: use server's getHttpEndpointUrl() from domain info
     * </ol>
     *
     * @param server the MCP server detail
     * @return the full endpoint URL, or null if cannot be constructed
     */
    private String buildMcpEndpoint(McpServerDetail server) {
        String gatewayEndpoint = config.getGatewayEndpoint();

        if (gatewayEndpoint != null && !gatewayEndpoint.trim().isEmpty()) {
            // Use gatewayEndpoint + mcpServerPath
            String baseUrl = gatewayEndpoint.trim();
            if (baseUrl.endsWith("/")) {
                baseUrl = baseUrl.substring(0, baseUrl.length() - 1);
            }

            String path = server.getMcpServerPath();
            if (path == null || path.isEmpty()) {
                logger.warn("Server {} has no mcpServerPath", server.getName());
                return null;
            }

            if (!path.startsWith("/")) {
                path = "/" + path;
            }

            String endpoint = baseUrl + path;
            logger.debug("Built endpoint from gatewayEndpoint: {}", endpoint);
            return endpoint;
        }

        // Fallback to server's domain-based endpoint
        String endpoint = server.getHttpEndpointUrl();
        if (endpoint != null && !endpoint.contains("*") && !endpoint.contains("{")) {
            logger.debug("Using server's HTTP endpoint: {}", endpoint);
            return endpoint;
        }

        return null;
    }

    private AsyncClient getAliyunClient() {
        if (aliyunClient == null) {
            String accessKeyId = config.getAccessKeyId();
            String accessKeySecret = config.getAccessKeySecret();

            if (accessKeyId == null || accessKeySecret == null) {
                throw new GatewayException(
                        "MISSING_CREDENTIALS",
                        "AccessKeyId and AccessKeySecret are required for listing MCP servers");
            }

            String region = config.getRegionId() != null ? config.getRegionId() : "cn-hangzhou";

            StaticCredentialProvider credentialProvider =
                    StaticCredentialProvider.create(
                            Credential.builder()
                                    .accessKeyId(accessKeyId)
                                    .accessKeySecret(accessKeySecret)
                                    .build());

            aliyunClient =
                    new DefaultAsyncClientBuilder()
                            .region(region)
                            .credentialsProvider(credentialProvider)
                            .overrideConfiguration(
                                    ClientOverrideConfiguration.create()
                                            .setEndpointOverride(
                                                    "apig." + region + ".aliyuncs.com"))
                            .build();
        }
        return aliyunClient;
    }

    private McpServerDetail convertToMcpServerDetail(ListMcpServersResponseBody.Items item) {
        McpServerDetail detail = new McpServerDetail();
        detail.setMcpServerId(item.getMcpServerId());
        detail.setName(item.getName());
        detail.setDescription(item.getDescription());
        detail.setType(item.getType());
        detail.setProtocol(item.getProtocol());
        detail.setMcpServerPath(item.getMcpServerPath());
        detail.setExposedUriPath(item.getExposedUriPath());
        detail.setGatewayId(item.getGatewayId());
        detail.setEnvironmentId(item.getEnvironmentId());
        detail.setRouteId(item.getRouteId());
        detail.setDeployStatus(item.getDeployStatus());
        detail.setCreateFromType(item.getCreateFromType());
        detail.setDomainIds(item.getDomainIds());

        // Convert domain infos
        if (item.getDomainInfos() != null) {
            List<McpServerDetail.DomainInfo> domainInfos = new ArrayList<>();
            for (HttpApiDomainInfo di : item.getDomainInfos()) {
                McpServerDetail.DomainInfo domainInfo = new McpServerDetail.DomainInfo();
                domainInfo.setDomainId(di.getDomainId());
                domainInfo.setName(di.getName());
                domainInfo.setProtocol(di.getProtocol());
                domainInfos.add(domainInfo);
            }
            detail.setDomainInfos(domainInfos);
        }

        // Convert backend
        Backend backend = item.getBackend();
        if (backend != null) {
            McpServerDetail.Backend backendDetail = new McpServerDetail.Backend();
            backendDetail.setScene(backend.getScene());
            if (backend.getServices() != null) {
                List<McpServerDetail.Service> services = new ArrayList<>();
                for (Backend.Services svc : backend.getServices()) {
                    McpServerDetail.Service service = new McpServerDetail.Service();
                    service.setServiceId(svc.getServiceId());
                    service.setName(svc.getName());
                    service.setProtocol(svc.getProtocol());
                    if (svc.getPort() != null) {
                        service.setPort(svc.getPort().intValue());
                    }
                    if (svc.getWeight() != null) {
                        service.setWeight(svc.getWeight().intValue());
                    }
                    service.setVersion(svc.getVersion());
                    services.add(service);
                }
                backendDetail.setServices(services);
            }
            detail.setBackend(backendDetail);
        }

        // Convert assembled sources
        if (item.getAssembledSources() != null) {
            List<McpServerDetail.AssembledSource> sources = new ArrayList<>();
            for (ListMcpServersResponseBody.AssembledSources as : item.getAssembledSources()) {
                McpServerDetail.AssembledSource source = new McpServerDetail.AssembledSource();
                source.setMcpServerId(as.getMcpServerId());
                source.setMcpServerName(as.getMcpServerName());
                source.setTools(as.getTools());
                sources.add(source);
            }
            detail.setAssembledSources(sources);
        }

        return detail;
    }

    @Override
    public void close() {
        if (toolExecutionClient != null) {
            try {
                toolExecutionClient.close();
            } catch (Exception e) {
                logger.warn("Error closing tool execution client", e);
            }
        }
        if (aliyunClient != null) {
            try {
                aliyunClient.close();
            } catch (Exception e) {
                logger.warn("Error closing Aliyun client", e);
            }
        }
        logger.info("AIGatewayClient closed");
    }
}

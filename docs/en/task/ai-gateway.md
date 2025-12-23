# AI Gateway Extension

AgentScope Java provides the AI Gateway extension, enabling agents to manage and invoke MCP tool services uniformly through Alibaba Cloud AI Gateway.

## What is AI Gateway?

AI Gateway is purpose-built for AI scenarios, providing unified proxy for LLM APIs and MCP Servers with rich integration and governance capabilities. This includes multi-model unified integration, user authentication, token rate limiting, web search, content security, and more.

## Prerequisites

### Maven Dependency

```xml
<dependency>
    <groupId>io.agentscope</groupId>
    <artifactId>agentscope-extensions-ai-gateway</artifactId>
    <version>${agentscope.version}</version>
</dependency>
```

### Preparation

1. Create and deploy MCP services in the Alibaba Cloud AI Gateway Console: `https://apig.console.aliyun.com/ai-gateway-overview#/overview`
2. Obtain the gateway base URL (e.g., `http://env-xxx.alicloudapi.com`)
3. Obtain authentication credentials for each MCP service (Bearer Token or API Key)

## Core Features

The AI Gateway extension provides two core interfaces:

| Interface | Function | Return Type | Authentication |
|-----------|----------|-------------|----------------|
| `listSearchedTools(query, topK)` | Semantic search and return executable tools | `List<AgentTool>` | Per-service config |
| `listAllMcpClients()` | Get all MCP services and auto-connect | `List<McpClientWrapper>` | Aliyun AccessKey + Per-service config |

## Quick Start

### Method 1: Semantic Tool Search

Semantic search can analyze and understand user intent based on user requests, intelligently selecting appropriate MCP tools to reduce context tokens and improve retrieval efficiency.

```java
import io.agentscope.core.tool.AgentTool;
import io.agentscope.core.tool.Toolkit;
import io.agentscope.extensions.aigateway.AIGatewayClient;
import io.agentscope.extensions.aigateway.config.AIGatewayConfig;
import io.agentscope.extensions.aigateway.config.MCPAuthConfig;

// 1. Configure AI Gateway (gateway endpoint + authentication)
// Tool search endpoint is automatically constructed as: gatewayEndpoint + /mcp-servers/union-tools-search
AIGatewayConfig config = AIGatewayConfig.builder()
        .gatewayEndpoint("http://env-xxx.alicloudapi.com")
        .mcpServerAuthConfig("union-tools-search", MCPAuthConfig.bearer("your-token"))
        .build();

// 2. Search for relevant tools (returns executable AgentTools directly)
try (AIGatewayClient client = new AIGatewayClient(config)) {
    List<AgentTool> tools = client.listSearchedTools("query weather", 5);
    
    // 3. Register with Toolkit
    Toolkit toolkit = new Toolkit();
    for (AgentTool tool : tools) {
        toolkit.registration().agentTool(tool).apply();
    }
    
    // 4. Create Agent
    ReActAgent agent = ReActAgent.builder()
            .name("WeatherAgent")
            .model(model)
            .toolkit(toolkit)
            .build();
}
```

### Method 2: Get All MCP Services

Get all deployed MCP services on the gateway and auto-connect:

```java
import io.agentscope.core.tool.mcp.McpClientWrapper;
import io.agentscope.extensions.aigateway.config.MCPAuthConfig;

// 1. Configure AI Gateway (requires Aliyun credentials + per-service authentication)
AIGatewayConfig config = AIGatewayConfig.builder()
        // Aliyun credentials
        .accessKeyId(System.getenv("ALIBABA_CLOUD_ACCESS_KEY_ID"))
        .accessKeySecret(System.getenv("ALIBABA_CLOUD_ACCESS_KEY_SECRET"))
        .gatewayId("gw-xxxxxx")
        .regionId("cn-hangzhou")
        .gatewayEndpoint("http://env-xxx.alicloudapi.com")
        // Configure authentication for each MCP service
        .mcpServerAuthConfig("union-tools-search", MCPAuthConfig.bearer("search-token"))
        .mcpServerAuthConfig("weather", MCPAuthConfig.header("X-API-Key", "weather-key"))
        .build();

// 2. Get all MCP clients (auto-connects to deployed services)
try (AIGatewayClient client = new AIGatewayClient(config)) {
    List<McpClientWrapper> mcpClients = client.listAllMcpClients();
    
    // 3. Register with Toolkit
    Toolkit toolkit = new Toolkit();
    for (McpClientWrapper mcpClient : mcpClients) {
        toolkit.registration().mcpClient(mcpClient).apply();
    }
    
    // 4. Create Agent
    ReActAgent agent = ReActAgent.builder()
            .name("GatewayAgent")
            .model(model)
            .toolkit(toolkit)
            .build();
}
```

## Configuration Details

### AIGatewayConfig Options

```java
AIGatewayConfig config = AIGatewayConfig.builder()
        // Gateway base URL (required)
        // Tool search endpoint is automatically constructed as: gatewayEndpoint + /mcp-servers/union-tools-search
        // MCP client endpoint is automatically constructed as: gatewayEndpoint + mcpServerPath
        .gatewayEndpoint("http://env-xxx.alicloudapi.com")
        
        // Configure authentication separately for each MCP service
        .mcpServerAuthConfig("union-tools-search", MCPAuthConfig.bearer("search-token"))
        .mcpServerAuthConfig("map", MCPAuthConfig.bearer("map-token"))
        .mcpServerAuthConfig("weather", MCPAuthConfig.header("X-API-Key", "weather-key"))
        .mcpServerAuthConfig("payment", MCPAuthConfig.query("apiKey", "payment-key"))
        
        // Aliyun credentials (required for listAllMcpClients)
        .accessKeyId("your-access-key-id")
        .accessKeySecret("your-access-key-secret")
        .gatewayId("gw-xxxxxx")
        .regionId("cn-hangzhou")  
        
        .build();
```

### Authentication Methods

| Method | Creation | Request Format | Supported Protocols |
|--------|----------|----------------|---------------------|
| Bearer Token | `MCPAuthConfig.bearer("token")` | `Authorization: Bearer token` | SSE, Streamable HTTP |
| Custom Header | `MCPAuthConfig.header("X-API-Key", "value")` | `X-API-Key: value` | SSE, Streamable HTTP |
| Query Parameter | `MCPAuthConfig.query("apiKey", "value")` | `?apiKey=value` | SSE, Streamable HTTP |

**Note**: All authentication methods support both SSE and Streamable HTTP transport protocols.

### Per-Service Authentication

Each MCP service requires **separate** authentication configuration.

**Warning Log Format**:

```
WARN  i.a.e.aigateway.AIGatewayClient - Failed to connect to MCP server {serverName}: {errorMessage}
```

Example:
```
WARN  i.a.e.aigateway.AIGatewayClient - Failed to connect to MCP server map: Sending message failed with a non-OK HTTP code: 401 - Unauthorized
```

**Recommendation**: Pre-configure authentication for all MCP services that require it to avoid connection failures. This extension uses SSE transport protocol by default.

## API Reference

### AIGatewayClient

```java
public class AIGatewayClient implements AutoCloseable {
    
    /**
     * Semantic search for tools
     * @param query search query (e.g., "query weather")
     * @param topK maximum number of tools to return
     * @return list of executable AgentTools
     */
    public List<AgentTool> listSearchedTools(String query, int topK);
    
    /**
     * Get all deployed MCP clients (auto-connects)
     * @return list of McpClientWrappers
     */
    public List<McpClientWrapper> listAllMcpClients();
}
```

## Complete Examples

See the complete example code:

- **MCP Server List Example**: `agentscope-examples/quickstart/src/main/java/io/agentscope/examples/quickstart/AIGatewayMcpServersExample.java`
- **Semantic Tool Search Example**: `agentscope-examples/quickstart/src/main/java/io/agentscope/examples/quickstart/AIGatewaySearchToolExample.java`

### Running Examples

```bash
# MCP Server List Example (requires environment variables)
cd agentscope-examples/quickstart
export ALIBABA_CLOUD_ACCESS_KEY_ID=your-access-key-id
export ALIBABA_CLOUD_ACCESS_KEY_SECRET=your-access-key-secret
mvn exec:java -Dexec.mainClass="io.agentscope.examples.quickstart.AIGatewayMcpServersExample"
```

## FAQ

### 1. 401 Unauthorized Error

**Symptom**: The following warning appears in logs:

```
WARN  i.a.e.aigateway.AIGatewayClient - Failed to connect to MCP server map: Sending message failed with a non-OK HTTP code: 401 - Unauthorized
```

**Cause**: Authentication not configured for the MCP service, but the server requires authentication.

**Solution**: Configure authentication separately for each MCP service that requires it:

```java
AIGatewayConfig config = AIGatewayConfig.builder()
    .mcpServerAuthConfig("union-tools-search", MCPAuthConfig.bearer("token-1"))
    .mcpServerAuthConfig("map", MCPAuthConfig.bearer("token-2"))
    .build();
```

**Note**: Services without configured authentication will attempt unauthenticated connections. If the server requires authentication, the connection will fail and be skipped; other services with configured authentication are not affected.

### 2. No Results from Tool Search

Check:
- Is the correct `gatewayEndpoint` configured?
- Is the correct authentication configured for `union-tools-search`?
- Are MCP services deployed on the gateway?
- Is the topK parameter greater than 0?

### 3. MCP Connection Failure

**Log Format**:

```
WARN  i.a.e.aigateway.AIGatewayClient - Failed to connect to MCP server {serverName}: {errorMessage}
```

**Possible Causes**:

| Error Message | Cause | Solution |
|---------------|-------|----------|
| `401 - Unauthorized` | Authentication not configured or incorrect | Configure correct authentication using `mcpServerAuthConfig()` |
| `unsupported URI http://*/...` | Domain configured as wildcard `*` | Configure correct `gatewayEndpoint`; invalid URLs are automatically skipped |
| `Failed to send message: DummyEvent` | Transport protocol mismatch (service doesn't support Streamable HTTP) | The extension defaults to SSE; if issues persist, check service configuration |
| `Connection refused` | Service not deployed or network unreachable | Confirm service status is "Deployed" |

## Related Documentation

- [MCP (Model Context Protocol)](./mcp.md)
- [Tool Usage](./tool.md)
- [Alibaba Cloud AI Gateway Documentation](https://help.aliyun.com/zh/api-gateway/ai-gateway/product-overview/what-is-an-ai-gateway?)


# AI Gateway 插件

AgentScope Java 提供 AI Gateway 插件，使智能体能够通过阿里云 AI 网关统一管理和调用 MCP 工具服务。

## 什么是 AI Gateway？

AI 网关面向 AI 场景全新打造，统一代理大模型 API 和 MCP Server，并提供丰富的集成和治理能力。包括多模型统一对接、用户鉴权、Token限流、联网搜索、内容安全等能力。


## 前置条件

### Maven 依赖

```xml
<dependency>
    <groupId>io.agentscope</groupId>
    <artifactId>agentscope-extensions-ai-gateway</artifactId>
    <version>${agentscope.version}</version>
</dependency>
```

### 准备工作

1. 在阿里云 AI 网关控制台创建并部署 MCP 服务 `https://apig.console.aliyun.com/ai-gateway-overview#/overview`    
2. 获取网关基础地址（如 `http://env-xxx.alicloudapi.com`）
3. 获取每个 MCP 服务的认证凭证（Bearer Token 或 API Key）

## 核心功能

AI Gateway 插件提供两个核心接口：

| 接口 | 功能 | 返回类型 | 认证方式 |
|------|------|---------|---------|
| `listSearchedTools(query, topK)` | 语义检索并返回可执行工具 | `List<AgentTool>` | 按服务配置 |
| `listAllMcpClients()` | 获取所有 MCP 服务并自动连接 | `List<McpClientWrapper>` | Aliyun AccessKey + 按服务配置 |

## 快速开始

### 方式一：工具语义检索

语义检索可基于用户请求，通过分析理解用户意图，智能选择合适的MCP工具，从而降低上下文Token，提升检索效率。

```java
import io.agentscope.core.tool.AgentTool;
import io.agentscope.core.tool.Toolkit;
import io.agentscope.extensions.aigateway.AIGatewayClient;
import io.agentscope.extensions.aigateway.config.AIGatewayConfig;
import io.agentscope.extensions.aigateway.config.MCPAuthConfig;

// 1. 配置 AI Gateway（网关地址 + 认证）
// 工具搜索端点会自动构建为: gatewayEndpoint + /mcp-servers/union-tools-search
AIGatewayConfig config = AIGatewayConfig.builder()
        .gatewayEndpoint("http://env-xxx.alicloudapi.com")
        .mcpServerAuthConfig("union-tools-search", MCPAuthConfig.bearer("your-token"))
        .build();

// 2. 搜索相关工具（直接返回可执行的 AgentTool）
try (AIGatewayClient client = new AIGatewayClient(config)) {
    List<AgentTool> tools = client.listSearchedTools("查询天气", 5);
    
    // 3. 注册到 Toolkit
    Toolkit toolkit = new Toolkit();
    for (AgentTool tool : tools) {
        toolkit.registration().agentTool(tool).apply();
    }
    
    // 4. 创建 Agent
    ReActAgent agent = ReActAgent.builder()
            .name("WeatherAgent")
            .model(model)
            .toolkit(toolkit)
            .build();
}
```

### 方式二：获取所有 MCP 服务

获取网关上所有已部署的 MCP 服务并自动连接：

```java
import io.agentscope.core.tool.mcp.McpClientWrapper;
import io.agentscope.extensions.aigateway.config.MCPAuthConfig;

// 1. 配置 AI Gateway（需要阿里云凭证 + 每个服务的认证）
AIGatewayConfig config = AIGatewayConfig.builder()
        // 阿里云凭证
        .accessKeyId(System.getenv("ALIBABA_CLOUD_ACCESS_KEY_ID"))
        .accessKeySecret(System.getenv("ALIBABA_CLOUD_ACCESS_KEY_SECRET"))
        .gatewayId("gw-xxxxxx")
        .regionId("cn-hangzhou")
        .gatewayEndpoint("http://env-xxx.alicloudapi.com")
        // 为每个 MCP 服务配置认证
        .mcpServerAuthConfig("union-tools-search", MCPAuthConfig.bearer("search-token"))
        .mcpServerAuthConfig("weather", MCPAuthConfig.header("X-API-Key", "weather-key"))
        .build();

// 2. 获取所有 MCP 客户端（自动连接已部署的服务）
try (AIGatewayClient client = new AIGatewayClient(config)) {
    List<McpClientWrapper> mcpClients = client.listAllMcpClients();
    
    // 3. 注册到 Toolkit
    Toolkit toolkit = new Toolkit();
    for (McpClientWrapper mcpClient : mcpClients) {
        toolkit.registration().mcpClient(mcpClient).apply();
    }
    
    // 4. 创建 Agent
    ReActAgent agent = ReActAgent.builder()
            .name("GatewayAgent")
            .model(model)
            .toolkit(toolkit)
            .build();
}
```

## 配置详解

### AIGatewayConfig 配置项

```java
AIGatewayConfig config = AIGatewayConfig.builder()
        // 网关基础地址（必需）
        // 工具搜索端点自动构建为: gatewayEndpoint + /mcp-servers/union-tools-search
        // MCP 客户端端点自动构建为: gatewayEndpoint + mcpServerPath
        .gatewayEndpoint("http://env-xxx.alicloudapi.com")
        
        // 为每个 MCP 服务单独配置认证
        .mcpServerAuthConfig("union-tools-search", MCPAuthConfig.bearer("search-token"))
        .mcpServerAuthConfig("map", MCPAuthConfig.bearer("map-token"))
        .mcpServerAuthConfig("weather", MCPAuthConfig.header("X-API-Key", "weather-key"))
        .mcpServerAuthConfig("payment", MCPAuthConfig.query("apiKey", "payment-key"))
        
        // 阿里云凭证（listAllMcpClients 需要）
        .accessKeyId("your-access-key-id")
        .accessKeySecret("your-access-key-secret")
        .gatewayId("gw-xxxxxx")
        .regionId("cn-hangzhou")  
        
        .build();
```

### 认证方式说明

| 认证方式 | 创建方法 | 请求格式 | 支持的传输协议 |
|---------|---------|--------------|---------------|
| Bearer Token | `MCPAuthConfig.bearer("token")` | `Authorization: Bearer token` | SSE, Streamable HTTP |
| 自定义 Header | `MCPAuthConfig.header("X-API-Key", "value")` | `X-API-Key: value` | SSE, Streamable HTTP |
| Query 参数 | `MCPAuthConfig.query("apiKey", "value")` | `?apiKey=value` | SSE, Streamable HTTP |

**说明**：所有认证方式都支持 SSE 和 Streamable HTTP 两种传输协议。

### 按服务配置认证

每个 MCP 服务需要**单独配置**认证信息：

 
**警告日志格式**：

```
WARN  i.a.e.aigateway.AIGatewayClient - Failed to connect to MCP server {服务名}: {错误信息}
```

示例：
```
WARN  i.a.e.aigateway.AIGatewayClient - Failed to connect to MCP server map: Sending message failed with a non-OK HTTP code: 401 - Unauthorized
```

**建议**：为所有需要认证的 MCP 服务预先配置好认证信息，避免连接失败。改插件默认采用SSE传输协议。
 

## API 参考

### AIGatewayClient

```java
public class AIGatewayClient implements AutoCloseable {
    
    /**
     * 语义搜索工具
     * @param query 搜索查询（如 "查询天气"）
     * @param topK 返回的最大工具数量
     * @return 可执行的 AgentTool 列表
     */
    public List<AgentTool> listSearchedTools(String query, int topK);
    
    /**
     * 获取所有已部署的 MCP 客户端（自动连接）
     * @return  McpClientWrapper 列表
     */
    public List<McpClientWrapper> listAllMcpClients();
}
```
\

## 完整示例

查看完整的示例代码：

- **MCP 服务列表示例**：`agentscope-examples/quickstart/src/main/java/io/agentscope/examples/quickstart/AIGatewayMcpServersExample.java`
- **工具语义检索示例**：`agentscope-examples/quickstart/src/main/java/io/agentscope/examples/quickstart/AIGatewaySearchToolExample.java`

### 运行示例

```bash
# MCP 服务列表示例（需要设置环境变量）
cd agentscope-examples/quickstart
export ALIBABA_CLOUD_ACCESS_KEY_ID=your-access-key-id
export ALIBABA_CLOUD_ACCESS_KEY_SECRET=your-access-key-secret
mvn exec:java -Dexec.mainClass="io.agentscope.examples.quickstart.AIGatewayMcpServersExample"
```

## 常见问题

### 1. 401 Unauthorized 错误

**现象**：日志中出现如下警告：

```
WARN  i.a.e.aigateway.AIGatewayClient - Failed to connect to MCP server map: Sending message failed with a non-OK HTTP code: 401 - Unauthorized
```

**原因**：未为该 MCP 服务配置认证，但服务器要求认证。

**解决方案**：为每个需要认证的 MCP 服务单独配置认证：

```java
AIGatewayConfig config = AIGatewayConfig.builder()
    .mcpServerAuthConfig("union-tools-search", MCPAuthConfig.bearer("token-1"))
    .mcpServerAuthConfig("map", MCPAuthConfig.bearer("token-2"))
    .build();
```

**注意**：未配置认证的服务会尝试无认证连接。如果服务器要求认证，连接会失败并被跳过，其他已配置认证的服务不受影响。

### 2. 工具搜索无结果

检查：
- 是否配置了正确的 `gatewayEndpoint`
- 是否为 `union-tools-search` 配置了正确的认证
- 网关上是否已部署 MCP 服务
- topK 参数是否大于 0

### 3. MCP 连接失败

**日志格式**：

```
WARN  i.a.e.aigateway.AIGatewayClient - Failed to connect to MCP server {服务名}: {错误信息}
```

**可能的原因**：

| 错误信息 | 原因 | 解决方案 |
|---------|------|---------|
| `401 - Unauthorized` | 未配置认证或认证信息错误 | 使用 `mcpServerAuthConfig()` 配置正确的认证 |
| `unsupported URI http://*/...` | 域名配置为通配符 `*` | 配置正确的 `gatewayEndpoint`，无效 URL 会被自动跳过 |
| `Failed to send message: DummyEvent` | 传输协议不匹配（服务不支持 Streamable HTTP） | 插件默认使用 SSE，如仍有问题请检查服务配置 |
| `Connection refused` | 服务未部署或网络不通 | 确认服务状态为 "Deployed" |

**其他检查项**：
- `gatewayEndpoint` 是否配置正确
- 服务状态是否为 "Deployed"

**自动过滤的无效端点**：
插件会自动跳过以下无效端点（记录 DEBUG 日志）：
- 包含通配符 `*` 的 URL（如 `http://*/mcp-servers/map`）
- 包含模板变量 `{` 的 URL

### 4. 405 Method Not Allowed

确保使用 Streamable HTTP 端点（不带 `/sse`）进行工具调用。插件会自动处理端点格式转换。

### 5. Query 参数认证

Query 参数认证现在**完全支持** SSE 和 Streamable HTTP 两种传输协议：

```java
// Query 参数认证配置
.mcpServerAuthConfig("my-server", MCPAuthConfig.query("apiKey", "your-api-key"))
```

Query 参数会附加到所有请求的 URL 中，包括：
- SSE 连接的 GET 请求
- SSE 发送消息的 POST 请求
- Streamable HTTP 的 POST 请求

**推荐优先级**（按安全性排序）：
1. `MCPAuthConfig.bearer("token")` - Bearer Token（最安全）
2. `MCPAuthConfig.header("X-API-Key", "key")` - 自定义 Header
3. `MCPAuthConfig.query("apiKey", "key")` - Query 参数（URL 中可见）

### 6. 如何知道服务名称？

服务名称来自 AI Gateway 控制台配置的 MCP 服务名称。也可以通过 `listAllMcpClients()` 获取所有已连接的客户端：

```java
List<McpClientWrapper> clients = gatewayClient.listAllMcpClients();
for (McpClientWrapper client : clients) {
    System.out.println("服务名称: " + client.getName());
}
```

## 相关文档

- [MCP（模型上下文协议）](./mcp.md)
- [工具使用](./tool.md)
- [阿里云 AI 网关文档](https://help.aliyun.com/zh/api-gateway/ai-gateway/product-overview/what-is-an-ai-gateway?)

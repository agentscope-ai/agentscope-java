# MCP (Model Context Protocol)

AgentScope Java 提供对 MCP (Model Context Protocol) 的完整支持，使智能体能够连接到外部工具服务器并使用 MCP 生态系统中的工具。

## 什么是 MCP？

MCP 是用于将 AI 应用程序连接到外部数据源和工具的标准协议。它支持：

- **统一的工具接口**：通过单个协议访问各种工具
- **外部工具服务器**：连接到专门的服务（文件系统、git、数据库等）
- **生态系统集成**：使用不断增长的 MCP 生态系统中的工具
- **灵活的传输**：支持 StdIO、SSE 和 HTTP 传输

## 传输类型

AgentScope 支持三种 MCP 传输机制：

| 传输     | 使用场景           | 连接方式        | 状态   |
|----------|-------------------|----------------|--------|
| **StdIO** | 本地进程通信       | 启动子进程      | 有状态 |
| **SSE**   | HTTP Server-Sent Events | HTTP 流式      | 有状态 |
| **HTTP**  | 可流式 HTTP        | 请求/响应       | 无状态 |

## 快速开始

### 1. 连接到 MCP 服务器

```java
import io.agentscope.core.tool.mcp.McpClientBuilder;
import io.agentscope.core.tool.mcp.McpClientWrapper;

// StdIO 传输 - 连接到本地 MCP 服务器
McpClientWrapper mcpClient = McpClientBuilder.create("filesystem-mcp")
        .stdioTransport("npx", "-y", "@modelcontextprotocol/server-filesystem", "/tmp")
        .buildAsync()
        .block();
```

### 2. 注册 MCP 工具

```java
import io.agentscope.core.tool.Toolkit;

Toolkit toolkit = new Toolkit();

// 注册 MCP 服务器的所有工具
toolkit.registerMcpClient(mcpClient).block();
```

### 3. 在智能体中配置 MCP

```java
import io.agentscope.core.ReActAgent;
import io.agentscope.core.memory.InMemoryMemory;

ReActAgent agent = ReActAgent.builder()
        .name("Assistant")
        .model(model)
        .toolkit(toolkit)  // MCP 工具现已可用
        .memory(new InMemoryMemory())
        .build();
```

## 传输配置

### StdIO 传输

用于本地进程通信：

```java
// 文件系统服务器
McpClientWrapper fsClient = McpClientBuilder.create("fs-mcp")
        .stdioTransport("npx", "-y", "@modelcontextprotocol/server-filesystem", "/path/to/dir")
        .buildAsync()
        .block();

// Git 服务器
McpClientWrapper gitClient = McpClientBuilder.create("git-mcp")
        .stdioTransport("python", "-m", "mcp_server_git")
        .buildAsync()
        .block();

// 自定义命令
McpClientWrapper customClient = McpClientBuilder.create("custom-mcp")
        .stdioTransport("/path/to/executable", "arg1", "arg2")
        .buildAsync()
        .block();
```

### SSE 传输

用于 HTTP Server-Sent Events：

```java
McpClientWrapper sseClient = McpClientBuilder.create("remote-mcp")
        .sseTransport("https://mcp.example.com/sse")
        .header("Authorization", "Bearer " + apiToken)
        .queryParam("tenant", "my-tenant")
        .timeout(Duration.ofSeconds(60))
        .buildAsync()
        .block();
```

### HTTP 传输

用于无状态 HTTP：

```java
McpClientWrapper httpClient = McpClientBuilder.create("http-mcp")
        .streamableHttpTransport("https://mcp.example.com/http")
        .header("X-API-Key", apiKey)
        .queryParam("env", "production")
        .buildAsync()
        .block();
```

## 工具过滤

控制要注册哪些 MCP 工具：

### 启用特定工具

```java
// 仅启用特定工具
List<String> enableTools = List.of("read_file", "write_file", "list_directory");

toolkit.registration().mcpClient(mcpClient).enableTools(enableTools).apply();
```

### 禁用特定工具

```java
// 启用除黑名单外的所有工具
List<String> disableTools = List.of("delete_file", "move_file");

toolkit.registration().mcpClient(mcpClient).disableTools(disableTools).apply();
```

### 同时使用启用和禁用

```java
// 白名单与黑名单结合
List<String> enableTools = List.of("read_file", "list_directory");
List<String> disableTools = List.of("write_file");

toolkit.registration().mcpClient(mcpClient).enableTools(enableTools).disableTools(disableTools).apply();
```

## 工具组

将 MCP 工具分配到组以进行选择性激活：

```java
// 创建工具组并激活
Toolkit toolkit = new Toolkit();
String groupName = "filesystem";
toolkit.createToolGroup(groupName, "Tools for operating system files", true);

// 将 MCP 工具注册到组中
toolkit.registration().mcpClient(mcpClient).group("groupName").apply();

// 创建仅使用特定组的智能体
ReActAgent agent = ReActAgent.builder()
        .name("Assistant")
        .model(model)
        .toolkit(toolkit)
        .build();
```

## 配置选项

### 超时设置

```java
import java.time.Duration;

McpClientWrapper client = McpClientBuilder.create("mcp")
        .stdioTransport("npx", "-y", "@modelcontextprotocol/server-filesystem", "/tmp")
        .timeout(Duration.ofSeconds(120))      // 请求超时
        .initializationTimeout(Duration.ofSeconds(30)) // 初始化超时
        .buildAsync()
        .block();
```

### HTTP 头

```java
McpClientWrapper client = McpClientBuilder.create("mcp")
        .sseTransport("https://mcp.example.com/sse")
        .header("Authorization", "Bearer " + token)
        .header("X-Client-Version", "1.0")
        .header("X-Custom-Header", "value")
        .buildAsync()
        .block();
```

### Query 参数

为 HTTP 传输添加 URL 查询参数：

```java
// 单个参数
McpClientWrapper client = McpClientBuilder.create("mcp")
        .sseTransport("https://mcp.example.com/sse")
        .queryParam("token", "abc123")
        .queryParam("tenant", "my-tenant")
        .buildAsync()
        .block();

// 批量参数
McpClientWrapper client = McpClientBuilder.create("mcp")
        .streamableHttpTransport("https://mcp.example.com/http")
        .queryParams(Map.of("token", "abc123", "env", "prod"))
        .buildAsync()
        .block();

// 与 URL 中已有参数合并（额外参数优先）
McpClientWrapper client = McpClientBuilder.create("mcp")
        .sseTransport("https://mcp.example.com/sse?version=v1")
        .queryParam("token", "abc123")  // 最终: ?version=v1&token=abc123
        .buildAsync()
        .block();
```

> **注意**：Query 参数仅对 HTTP 传输（SSE 和 HTTP）有效，对 StdIO 传输会被忽略。

### 同步 vs 异步客户端

```java
// 异步客户端（推荐）
McpClientWrapper asyncClient = McpClientBuilder.create("async-mcp")
        .stdioTransport("npx", "-y", "@modelcontextprotocol/server-filesystem", "/tmp")
        .buildAsync()
        .block();

// 同步客户端（用于阻塞操作）
McpClientWrapper syncClient = McpClientBuilder.create("sync-mcp")
        .stdioTransport("npx", "-y", "@modelcontextprotocol/server-filesystem", "/tmp")
        .buildSync();
```

## 管理 MCP 客户端

### 列出 MCP 服务器的工具

```java
// 注册后，工具会出现在工具包中
Set<String> toolNames = toolkit.getToolNames();
System.out.println("可用工具: " + toolNames);
```

### 移除 MCP 客户端

```java
// 移除 MCP 客户端及其所有工具
toolkit.removeMcpClient("filesystem-mcp").block();
```

## 完整示例

查看完整的 MCP 示例：
- `agentscope-examples/quickstart/src/main/java/io/agentscope/examples/quickstart/McpToolExample.java`

运行示例：
```bash
cd agentscope-examples/quickstart
mvn exec:java -Dexec.mainClass="io.agentscope.examples.quickstart.McpToolExample"
```

# MCP（模型上下文协议）

AgentScope Java 提供对模型上下文协议（MCP）的完整支持，使智能体能够连接到外部工具服务器并使用 MCP 生态系统中的工具。

## 什么是 MCP？

MCP 是用于将 AI 应用程序连接到外部数据源和工具的标准协议。它支持：

- **统一的工具接口**：通过单个协议访问各种工具
- **外部工具服务器**：连接到专门的服务（文件系统、git、数据库等）
- **生态系统集成**：使用不断增长的 MCP 生态系统中的工具
- **灵活的传输**：支持 StdIO、SSE 和 HTTP 传输

## 前置条件

### Maven 依赖

要使用 MCP 功能，您需要在项目中添加 MCP SDK 依赖：

```xml
<dependency>
    <groupId>io.modelcontextprotocol.sdk</groupId>
    <artifactId>mcp</artifactId>
    <version>0.14.1</version>
</dependency>
```

**注意**：MCP SDK 不会自动包含在 AgentScope 中。您必须显式地将其添加到 `pom.xml` 中。

### Gradle 依赖

对于 Gradle 项目：

```gradle
implementation 'io.modelcontextprotocol.sdk:mcp:0.14.1'
```

## 传输类型

AgentScope 支持三种 MCP 传输机制：

| 传输     | 使用场景           | 连接方式        | 状态   |
|----------|-------------------|----------------|--------|
| **StdIO** | 本地进程通信       | 启动子进程      | 有状态 |
| **SSE**   | HTTP Server-Sent Events | HTTP 流式      | 有状态 |
| **HTTP**  | 可流式 HTTP        | 请求/响应       | 无状态 |

### 选择传输方式

- **StdIO**
- **SSE**
- **HTTP**

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

### 3. 与智能体一起使用

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
        .buildAsync()
        .block();
```

## 工具过滤

控制要注册哪些 MCP 工具：

### 启用特定工具

```java
// 仅启用特定工具
List<String> enableTools = List.of("read_file", "write_file", "list_directory");

toolkit.registerMcpClient(mcpClient, enableTools).block();
```

### 禁用特定工具

```java
// 启用除黑名单外的所有工具
List<String> disableTools = List.of("delete_file", "move_file");

toolkit.registerMcpClient(mcpClient, null, disableTools).block();
```

### 同时使用启用和禁用

```java
// 白名单与黑名单结合
List<String> enableTools = List.of("read_file", "write_file", "list_directory");
List<String> disableTools = List.of("write_file");  // 进一步限制

toolkit.registerMcpClient(mcpClient, enableTools, disableTools).block();
```

## 工具组

将 MCP 工具分配到组以进行选择性激活：

```java
// 将 MCP 工具注册到组中
String groupName = "filesystem";
toolkit.registerMcpClient(mcpClient, null, null, groupName).block();

// 创建仅使用特定组的智能体
ReActAgent agent = ReActAgent.builder()
        .name("Assistant")
        .model(model)
        .toolkit(toolkit)
        .enableToolGroups(List.of("filesystem"))  // 仅使用文件系统工具
        .build();
```

## 配置选项

### 超时设置

```java
import java.time.Duration;

McpClientWrapper client = McpClientBuilder.create("mcp")
        .stdioTransport("npx", "-y", "@modelcontextprotocol/server-filesystem", "/tmp")
        .requestTimeout(Duration.ofSeconds(120))      // 请求超时
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

### 更新 MCP 客户端

```java
// 移除旧客户端并注册新客户端
toolkit.removeMcpClient("old-mcp").block();

McpClientWrapper newClient = McpClientBuilder.create("new-mcp")
        .stdioTransport("npx", "-y", "@modelcontextprotocol/server-filesystem", "/new/path")
        .buildAsync()
        .block();

toolkit.registerMcpClient(newClient).block();
```

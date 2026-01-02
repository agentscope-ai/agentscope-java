# MCP (Model Context Protocol)

AgentScope Java provides full support for Model Context Protocol (MCP), enabling agents to connect to external tool servers and use tools from the MCP ecosystem.

## What is MCP?

MCP is a standard protocol for connecting AI applications to external data sources and tools. It enables:

- **Unified Tool Interface**: Access diverse tools through a single protocol
- **External Tool Servers**: Connect to specialized services (filesystem, git, databases, etc.)
- **Ecosystem Integration**: Use tools from the growing MCP ecosystem
- **Flexible Transport**: Support for StdIO, SSE, and HTTP transports

## Transport Types

AgentScope supports three MCP transport mechanisms:

| Transport | Use Case | Connection | State |
|-----------|----------|------------|-------|
| **StdIO** | Local process communication | Spawns child process | Stateful |
| **SSE** | HTTP Server-Sent Events | HTTP streaming | Stateful |
| **HTTP** | Streamable HTTP | Request/response | Stateless |

## Quick Start

### 1. Connect to MCP Server

```java
import io.agentscope.core.tool.mcp.McpClientBuilder;
import io.agentscope.core.tool.mcp.McpClientWrapper;

// StdIO transport - connect to local MCP server
McpClientWrapper mcpClient = McpClientBuilder.create("filesystem-mcp")
        .stdioTransport("npx", "-y", "@modelcontextprotocol/server-filesystem", "/tmp")
        .buildAsync()
        .block();
```

### 2. Register MCP Tools

```java
import io.agentscope.core.tool.Toolkit;

Toolkit toolkit = new Toolkit();

// Register all tools from MCP server
toolkit.registerMcpClient(mcpClient).block();
```

### 3. Configure MCP in Agent

```java
import io.agentscope.core.ReActAgent;
import io.agentscope.core.memory.InMemoryMemory;

ReActAgent agent = ReActAgent.builder()
        .name("Assistant")
        .model(model)
        .toolkit(toolkit)  // MCP tools are now available
        .memory(new InMemoryMemory())
        .build();
```

## Transport Configuration

### StdIO Transport

For local process communication:

```java
// Filesystem server
McpClientWrapper fsClient = McpClientBuilder.create("fs-mcp")
        .stdioTransport("npx", "-y", "@modelcontextprotocol/server-filesystem", "/path/to/dir")
        .buildAsync()
        .block();

// Git server
McpClientWrapper gitClient = McpClientBuilder.create("git-mcp")
        .stdioTransport("python", "-m", "mcp_server_git")
        .buildAsync()
        .block();

// Custom command
McpClientWrapper customClient = McpClientBuilder.create("custom-mcp")
        .stdioTransport("/path/to/executable", "arg1", "arg2")
        .buildAsync()
        .block();
```

### SSE Transport

For HTTP Server-Sent Events:

```java
McpClientWrapper sseClient = McpClientBuilder.create("remote-mcp")
        .sseTransport("https://mcp.example.com/sse")
        .header("Authorization", "Bearer " + apiToken)
        .queryParam("queryKey", "queryValue")
        .timeout(Duration.ofSeconds(60))
        .buildAsync()
        .block();
```

### HTTP Transport

For stateless HTTP:

```java
McpClientWrapper httpClient = McpClientBuilder.create("http-mcp")
        .streamableHttpTransport("https://mcp.example.com/http")
        .header("X-API-Key", apiKey)
        .queryParam("queryKey", "queryValue")
        .buildAsync()
        .block();
```

## Tool Filtering

Control which MCP tools to register:

### Enable Specific Tools

```java
// Only enable specific tools
List<String> enableTools = List.of("read_file", "write_file", "list_directory");

toolkit.registration().mcpClient(mcpClient).enableTools(enableTools).apply();
```

### Disable Specific Tools

```java
// Enable all except blacklisted tools
List<String> disableTools = List.of("delete_file", "move_file");

toolkit.registration().mcpClient(mcpClient).disableTools(disableTools).apply();
```

### Both Enable and Disable

```java
// Whitelist with blacklist
List<String> enableTools = List.of("read_file", "list_directory");
List<String> disableTools = List.of("write_file");

toolkit.registration().mcpClient(mcpClient).enableTools(enableTools).disableTools(disableTools).apply();
```

## Tool Groups

Assign MCP tools to a group for selective activation:

```java
// Create tool group and activate
Toolkit toolkit = new Toolkit();
String groupName = "filesystem";
toolkit.createToolGroup(groupName, "Tools for operating system files", true);

// Register MCP tools in a group
toolkit.registration().mcpClient(mcpClient).group("groupName").apply();

// Create agent that only uses specific groups
ReActAgent agent = ReActAgent.builder()
        .name("Assistant")
        .model(model)
        .toolkit(toolkit)
        .build();
```

## Configuration Options

### Timeouts

```java
import java.time.Duration;

McpClientWrapper client = McpClientBuilder.create("mcp")
        .stdioTransport("npx", "-y", "@modelcontextprotocol/server-filesystem", "/tmp")
        .timeout(Duration.ofSeconds(120))      // Request timeout
        .initializationTimeout(Duration.ofSeconds(30)) // Init timeout
        .buildAsync()
        .block();
```

### HTTP Headers

```java
McpClientWrapper client = McpClientBuilder.create("mcp")
        .sseTransport("https://mcp.example.com/sse")
        .header("Authorization", "Bearer " + token)
        .header("X-Client-Version", "1.0")
        .header("X-Custom-Header", "value")
        .buildAsync()
        .block();
```

### Query Parameters

Add URL query parameters for HTTP transports:

```java
// Single parameter
McpClientWrapper client = McpClientBuilder.create("mcp")
        .sseTransport("https://mcp.example.com/sse")
        .queryParam("queryKey1", "queryValue1")
        .queryParam("queryKey2", "queryValue2")
        .buildAsync()
        .block();

// Multiple parameters at once
McpClientWrapper client = McpClientBuilder.create("mcp")
        .streamableHttpTransport("https://mcp.example.com/http")
        .queryParams(Map.of("queryKey1", "queryValue1", "queryKey2", "queryValue2"))
        .buildAsync()
        .block();

// Merge with existing URL parameters (additional params take precedence)
McpClientWrapper client = McpClientBuilder.create("mcp")
        .sseTransport("https://mcp.example.com/sse?version=v1")
        .queryParam("queryKey", "queryValue")  // Result: ?version=v1&queryKey=queryValue
        .buildAsync()
        .block();
```

> **Note**: Query parameters only apply to HTTP transports (SSE and HTTP). They are ignored for StdIO transport.

### Synchronous vs Asynchronous Clients

```java
// Asynchronous client (recommended)
McpClientWrapper asyncClient = McpClientBuilder.create("async-mcp")
        .stdioTransport("npx", "-y", "@modelcontextprotocol/server-filesystem", "/tmp")
        .buildAsync()
        .block();

// Synchronous client (for blocking operations)
McpClientWrapper syncClient = McpClientBuilder.create("sync-mcp")
        .stdioTransport("npx", "-y", "@modelcontextprotocol/server-filesystem", "/tmp")
        .buildSync();
```

## Managing MCP Clients

### List Tools from MCP Server

```java
// After registration, tools appear in toolkit
Set<String> toolNames = toolkit.getToolNames();
System.out.println("Available tools: " + toolNames);
```

### Remove MCP Client

```java
// Remove MCP client and all its tools
toolkit.removeMcpClient("filesystem-mcp").block();
```

## Higress AI Gateway Integration

AgentScope provides a Higress AI Gateway extension that enables unified access to MCP tools through the Higress gateway, with semantic search capabilities to automatically select the most suitable tools.

### Add Dependency

```xml
<dependency>
    <groupId>io.agentscope</groupId>
    <artifactId>agentscope-extensions-higress</artifactId>
    <version>${agentscope.version}</version>
</dependency>
```

### Basic Usage

```java
import io.agentscope.extensions.higress.HigressMcpClientBuilder;
import io.agentscope.extensions.higress.HigressMcpClientWrapper;
import io.agentscope.extensions.higress.HigressToolkit;

// 1. Create Higress MCP client
HigressMcpClientWrapper higressClient = HigressMcpClientBuilder
        .create("higress")
        .streamableHttpEndpoint("your higress mcp server endpoint")
        .buildAsync()
        .block();

// 2. Register with HigressToolkit
HigressToolkit toolkit = new HigressToolkit();
toolkit.registerMcpClient(higressClient).block();

```

### Enable Semantic Tool Search

Use the `toolSearch()` method to enable semantic search. Higress will automatically select the most relevant tools for your query:

```java
// Enable tool search, return top 5 most relevant tools
HigressMcpClientWrapper higressClient = HigressMcpClientBuilder
        .create("higress")
        .streamableHttpEndpoint("http://your-higress-gateway/mcp-servers/union-tools-search")
        .toolSearch("query weather and map information", 5)  // query and topK
        .buildAsync()
        .block();
```


### Higress Example

See the complete Higress example:
- `agentscope-examples/quickstart/src/main/java/io/agentscope/examples/quickstart/HigressToolExample.java`

## Complete Example

See the complete MCP example:
- `agentscope-examples/quickstart/src/main/java/io/agentscope/examples/quickstart/McpToolExample.java`

Run the example:
```bash
cd agentscope-examples/quickstart
mvn exec:java -Dexec.mainClass="io.agentscope.examples.quickstart.McpToolExample"
```

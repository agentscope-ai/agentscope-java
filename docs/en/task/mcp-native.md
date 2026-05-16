# Native MCP Server Implementation

AgentScope Java provides native support for implementing MCP (Model Context Protocol) servers. This enables your agents to expose tools via the MCP protocol for external clients to consume.

## What is Native MCP Server?

Native MCP server implementation allows you to:

- **Build Custom Tool Servers**: Implement domain-specific tools and expose them via MCP
- **External Tool Exposure**: Allow external clients (Claude, other agents, IDEs) to discover and use your tools
- **Protocol Support**: Full JSON-RPC 2.0 support with StdIO and TCP transports
- **Tool Management**: Register, discover, and execute tools through standard MCP protocol

## Key Difference: Client vs Server

| Aspect | MCP Client (mcp.md) | MCP Native Server (mcp-native.md) |
|--------|-------------------|-----------------------------------|
| **Purpose** | Connect to external tool servers | Build your own tool server |
| **Direction** | Agent calls external tools | External clients call your tools |
| **Transport** | StdIO, SSE, HTTP (client-side) | StdIO, TCP (server-side) |
| **Use Case** | Integrate existing tools | Expose custom domain tools |

## Quick Start

### 1. Implement Custom Tools

Create a tool implementing the `Tool` interface:

```java
import io.agentscope.core.mcp.tool.Tool;
import java.util.HashMap;
import java.util.Map;

public class CalculatorTool implements Tool {
    
    @Override
    public String getName() {
        return "calculator.compute";
    }
    
    @Override
    public String getDescription() {
        return "Performs basic arithmetic operations (add, subtract, multiply, divide)";
    }
    
    @Override
    public Map<String, Object> getInputSchema() {
        Map<String, Object> schema = new HashMap<>();
        schema.put("type", "object");
        schema.put("properties", Map.of(
            "operation", Map.of("type", "string", "description", "Operation: add, subtract, multiply, divide"),
            "a", Map.of("type", "number", "description", "First operand"),
            "b", Map.of("type", "number", "description", "Second operand")
        ));
        schema.put("required", List.of("operation", "a", "b"));
        return schema;
    }
    
    @Override
    public Object execute(Object params) {
        Map<String, Object> args = (Map<String, Object>) params;
        String operation = (String) args.get("operation");
        double a = ((Number) args.get("a")).doubleValue();
        double b = ((Number) args.get("b")).doubleValue();
        
        double result = switch(operation) {
            case "add" -> a + b;
            case "subtract" -> a - b;
            case "multiply" -> a * b;
            case "divide" -> b != 0 ? a / b : throw new IllegalArgumentException("Division by zero");
            default -> throw new IllegalArgumentException("Unknown operation: " + operation);
        };
        
        Map<String, Object> response = new HashMap<>();
        response.put("operation", operation);
        response.put("a", a);
        response.put("b", b);
        response.put("result", result);
        return response;
    }
}
```

### 2. Start MCP Server with StdIO Transport

```java
import io.agentscope.core.mcp.server.McpServer;
import io.agentscope.core.mcp.transport.StdioTransport;

public class McpServerRunner {
    public static void main(String[] args) throws Exception {
        // Create transport (StdIO for local process communication)
        StdioTransport transport = new StdioTransport();
        
        // Create and start server
        McpServer server = new McpServer(transport);
        server.registerTool(new CalculatorTool());
        server.start();
        
        System.err.println("MCP Server started. Listening on stdin/stdout...");
    }
}
```

### 3. Start MCP Server with TCP Transport

For network access:

```java
import io.agentscope.core.mcp.server.McpServer;
import io.agentscope.core.mcp.transport.TcpTransport;

public class TcpServerRunner {
    public static void main(String[] args) throws Exception {
        // Create TCP transport on port 9999
        TcpTransport transport = new TcpTransport("localhost", 9999);
        
        // Create and start server
        McpServer server = new McpServer(transport);
        server.registerTool(new CalculatorTool());
        server.start();
        
        System.err.println("MCP Server listening on tcp://localhost:9999");
    }
}
```

## Tool Implementation Details

### Required Methods

Every tool must implement `io.agentscope.core.mcp.tool.Tool`:

```java
public interface Tool {
    /**
     * Returns the unique tool name
     */
    String getName();
    
    /**
     * Returns human-readable description
     */
    String getDescription();
    
    /**
     * Returns JSON Schema describing input parameters
     */
    Map<String, Object> getInputSchema();
    
    /**
     * Executes the tool with given parameters
     * 
     * @param params Tool parameters (typically a Map)
     * @return Tool result (wrapped in content blocks by server)
     */
    Object execute(Object params);
}
```

### Input Schema Format

Use JSON Schema to describe tool parameters:

```java
@Override
public Map<String, Object> getInputSchema() {
    return Map.of(
        "type", "object",
        "properties", Map.of(
            "operation", Map.of(
                "type", "string",
                "description", "Operation to perform",
                "enum", List.of("add", "subtract", "multiply", "divide")
            ),
            "a", Map.of(
                "type", "number",
                "description", "First number"
            ),
            "b", Map.of(
                "type", "number",
                "description", "Second number"
            )
        ),
        "required", List.of("operation", "a", "b")
    );
}
```

### Return Value Formats

Tools can return different types - the server wraps them appropriately:

```java
// String result - wrapped as text content
@Override
public Object execute(Object params) {
    return "Result: 42";
}

// Map result - converted to JSON
@Override
public Object execute(Object params) {
    return Map.of(
        "status", "success",
        "value", 42
    );
}

// List result - treated as array
@Override
public Object execute(Object params) {
    return List.of("item1", "item2", "item3");
}
```

## Tool Management

### Register Multiple Tools

```java
McpServer server = new McpServer(transport);
server.registerTool(new CalculatorTool());
server.registerTool(new StringToolTool());
server.registerTool(new FileSystemTool());
server.start();
```

### List Registered Tools

```java
// From client perspective, fetch tools via tools/list request
// Server automatically provides list of all registered tools
```

## Protocol Details

### Supported MCP Methods

The native server automatically handles:

| Method | Purpose | Response |
|--------|---------|----------|
| `initialize` | Client handshake | Server info & capabilities |
| `tools/list` | Get available tools | Tool metadata array |
| `tools/call` | Execute a tool | Tool result content |

### JSON-RPC Message Format

Requests:
```json
{
    "jsonrpc": "2.0",
    "id": 1,
    "method": "tools/call",
    "params": {
        "name": "calculator.compute",
        "arguments": {
            "operation": "multiply",
            "a": 5,
            "b": 3
        }
    }
}
```

Responses:
```json
{
    "jsonrpc": "2.0",
    "id": 1,
    "result": {
        "content": [
            {
                "type": "text",
                "text": "{\"operation\":\"multiply\",\"a\":5,\"b\":3,\"result\":15}"
            }
        ]
    }
}
```

## Error Handling

### Tool Execution Errors

```java
@Override
public Object execute(Object params) {
    Map<String, Object> args = (Map<String, Object>) params;
    String operation = (String) args.get("operation");
    
    if (!isValidOperation(operation)) {
        throw new IllegalArgumentException("Invalid operation: " + operation);
    }
    
    // ... execute ...
}
```

### Server Error Response

Server automatically returns error responses:

```json
{
    "jsonrpc": "2.0",
    "id": 1,
    "error": {
        "code": -32603,
        "message": "Internal error",
        "data": "Invalid operation: unknown"
    }
}
```

## Transport Configuration

### StdIO Transport

Best for local process integration (agent, IDE, local tools):

```java
StdioTransport transport = new StdioTransport();
McpServer server = new McpServer(transport);
server.start();
// Communicates via stdin/stdout
```

### TCP Transport

Best for network access and remote clients:

```java
// Server binding to port 9999
TcpTransport transport = new TcpTransport("0.0.0.0", 9999);
McpServer server = new McpServer(transport);
server.start();

// Client connects to
// tcp://localhost:9999
```

## Complete Example

See the complete native MCP server example:
- `agentscope-examples/mcp-native-example/`

Key files:
- `McpServerRunner.java` - StdIO server with tools
- `ReActAgentCliRunner.java` - CLI agent using calculator tools
- `CalculatorToolAdapter.java` - Tool implementation with logging

Build:
```bash
cd agentscope-examples/mcp-native-example
mvn clean install
```

Run MCP Server (StdIO):
```bash
java -jar target/mcp-native-example.jar io.agentscope.examples.mcp.McpServerRunner
```

Run CLI Agent:
```bash
OPENAI_API_KEY="sk-..." java -cp target/mcp-native-example.jar \
    io.agentscope.examples.mcp.ReActAgentCliRunner
```

## Best Practices

### 1. Clear Tool Names and Descriptions

```java
// ✅ Good - descriptive, namespaced
getName() -> "filesystem.read_file"
getDescription() -> "Read contents of a file"

// ❌ Poor - vague, generic
getName() -> "read"
getDescription() -> "Read something"
```

### 2. Comprehensive Input Schemas

```java
// ✅ Good - complete schema with constraints
"properties": {
    "path": {
        "type": "string",
        "description": "File path (absolute or relative)"
    },
    "encoding": {
        "type": "string",
        "description": "Character encoding",
        "enum": ["utf-8", "ascii", "utf-16"],
        "default": "utf-8"
    }
}

// ❌ Poor - minimal schema
"properties": {
    "input": {"type": "object"}
}
```

### 3. Error Messages

```java
// ✅ Good - actionable error
throw new IllegalArgumentException("File not found at: " + path + 
    " (expected absolute path or relative from " + workingDir + ")");

// ❌ Poor - vague error
throw new Exception("Error");
```

### 4. Logging Tool Invocations

Add logging to track tool usage:

```java
@Override
public Object execute(Object params) {
    logger.info("[TOOL CALLED] " + getName() + " with args: " + params);
    try {
        Object result = executeLogic(params);
        logger.info("[TOOL SUCCESS] " + getName() + " returned: " + result);
        return result;
    } catch (Exception e) {
        logger.error("[TOOL ERROR] " + getName() + " failed: " + e.getMessage());
        throw e;
    }
}
```

## Integration with ReActAgent

Use native MCP tools with ReActAgent:

```java
// Create toolkit and register adapter
Toolkit toolkit = new Toolkit();
toolkit.registration().tool(new CalculatorToolAdapter()).apply();

// Create agent with tools
ReActAgent agent = ReActAgent.builder()
    .name("Assistant")
    .model(model)
    .toolkit(toolkit)  // Tools registered via MCP or direct
    .memory(new InMemoryMemory())
    .build();
```

## Troubleshooting

### Tool Not Found Error

```
Error: Tool "calculator.compute" not found
```

**Solution**: Verify tool name matches exactly (case-sensitive):
```java
// Ensure consistent naming
server.registerTool(new CalculatorTool()); // Must return exact name in getName()
```

### Schema Validation Error

```
Error: Arguments do not match schema
```

**Solution**: Check JSON Schema matches actual parameters:
```java
// Test schema with sample arguments
Map<String, Object> testArgs = Map.of("operation", "add", "a", 5, "b", 3);
// Verify this matches getInputSchema()
```

### Transport Connection Issues

**StdIO**: Check process is receiving stdin/stdout correctly
**TCP**: Verify port is available and firewall allows access
```bash
# Check port availability
lsof -i :9999
```

## Related Documentation

- [MCP Client Integration](mcp.md) - Connect to external MCP servers
- [Toolkit Guide](../guide/toolkit.md) - Tool registration and management
- [ReActAgent Guide](../guide/react-agent.md) - Agent implementation
- [MCP Native Example README](../../examples/mcp-native-example/README.md) - Complete working example source

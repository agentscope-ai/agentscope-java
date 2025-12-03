# Tool

The tool system enables Agents to break through the limitations of pure text generation and perform external operations such as API calls, database queries, file operations, etc.

---

## Core Features

- **Annotation-Based**: Quickly define tools using `@Tool` and `@ToolParam`
- **Reactive Programming**: Native support for `Mono`/`Flux` asynchronous execution
- **Automatic Schema**: Automatically generate JSON Schema for LLM understanding
- **Tool Groups**: Dynamically activate/deactivate tool collections
- **Preset Parameters**: Hide sensitive parameters (e.g., API Keys)
- **Parallel Execution**: Support parallel invocation of multiple tools
- **MCP Support**: Integration with Model Context Protocol

---

## Quick Start

### 1. Define Tools

```java
import io.agentscope.core.tool.Tool;
import io.agentscope.core.tool.ToolParam;

public class WeatherService {

    @Tool(description = "Get current weather for a specified location")
    public String getWeather(
            @ToolParam(name = "location", description = "City name")
            String location) {
        // Call weather API
        return location + " weather: Sunny, 25°C";
    }
}
```

> **Important**: The `name` attribute of `@ToolParam` is required because Java doesn't preserve parameter names by default.

### 2. Register and Use

```java
// Create toolkit
Toolkit toolkit = new Toolkit();
toolkit.registerTool(new WeatherService());

// Create Agent
ReActAgent agent = ReActAgent.builder()
    .name("Assistant")
    .model(model)
    .toolkit(toolkit)
    .sysPrompt("You are a helpful assistant that can query weather information.")
    .build();

// Use
Msg query = Msg.builder()
    .role(MsgRole.USER)
    .textContent("What's the weather in Shanghai?")
    .build();

Msg response = agent.call(query).block();
```

---

## Tool Registration

### Annotation Method (Recommended)

```java
public class BasicTools {

    // Multi-parameter tool
    @Tool(description = "Calculate the sum of two numbers")
    public int add(
            @ToolParam(name = "a", description = "First number") int a,
            @ToolParam(name = "b", description = "Second number") int b) {
        return a + b;
    }

    // Async tool
    @Tool(description = "Async search")
    public Mono<String> searchWeb(
            @ToolParam(name = "query", description = "Search query") String query) {
        return Mono.delay(Duration.ofSeconds(1))
            .map(ignored -> "Search results: " + query);
    }
}

// Register
toolkit.registerTool(new BasicTools());
```

### AgentTool Interface Method

When fine-grained control is needed, directly implement the `AgentTool` interface:

```java
public class CustomTool implements AgentTool {

    @Override
    public String getName() {
        return "custom_tool";
    }

    @Override
    public String getDescription() {
        return "Custom tool";
    }

    @Override
    public Map<String, Object> getParameters() {
        return Map.of(
            "type", "object",
            "properties", Map.of(
                "query", Map.of("type", "string", "description", "Query content")
            ),
            "required", List.of("query")
        );
    }

    @Override
    public Mono<ToolResultBlock> callAsync(ToolCallParam param) {
        String query = (String) param.getInput().get("query");
        return Mono.just(ToolResultBlock.text("Result: " + query));
    }
}
```

### Builder API

```java
// With tool group
toolkit.registration()
    .tool(new WeatherService())
    .group("weather_group")
    .apply();

// With preset parameters
toolkit.registration()
    .tool(new APIService())
    .presetParameters(Map.of(
        "callAPI", Map.of("apiKey", System.getenv("API_KEY"))
    ))
    .apply();
```

---

## Tool Group Management

### Why Tool Groups?

- **Scenario Management**: Activate different tool sets for different scenarios
- **Permission Control**: Restrict users to specific tools only
- **Performance Optimization**: Reduce the number of tools visible to LLM

### Basic Operations

```java
// 1. Create tool groups
toolkit.createToolGroup("basic", "Basic Tools", true);
toolkit.createToolGroup("admin", "Admin Tools", false);

// 2. Register tools to groups
toolkit.registration()
    .tool(new BasicTools())
    .group("basic")
    .apply();

// 3. Dynamically activate/deactivate
toolkit.updateToolGroups(List.of("admin"), true);   // Activate
toolkit.updateToolGroups(List.of("basic"), false);  // Deactivate

// 4. Query status
List<String> activeGroups = toolkit.getActiveGroups();
```

### Scenario Example

```java
// Dynamically switch tools based on user role
String userRole = getCurrentUserRole();

switch (userRole) {
    case "guest":
        toolkit.updateToolGroups(List.of("guest"), true);
        toolkit.updateToolGroups(List.of("user", "admin"), false);
        break;
    case "user":
        toolkit.updateToolGroups(List.of("guest", "user"), true);
        toolkit.updateToolGroups(List.of("admin"), false);
        break;
    case "admin":
        toolkit.updateToolGroups(List.of("guest", "user", "admin"), true);
        break;
}
```

---

## Preset Parameters

### Overview

**Preset parameters** are automatically injected during tool execution but **do not** appear in the Schema visible to LLM, suitable for:

- **Sensitive Information**: API Keys, passwords
- **Context Information**: User ID, session ID
- **Fixed Configuration**: Server address, region

### Usage

```java
// Define tool
public class EmailService {
    @Tool(description = "Send email")
    public String sendEmail(
            @ToolParam(name = "to", description = "Recipient") String to,
            @ToolParam(name = "subject", description = "Subject") String subject,
            @ToolParam(name = "apiKey", description = "API Key") String apiKey,
            @ToolParam(name = "from", description = "Sender") String from) {
        return String.format("Sent from %s to %s", from, to);
    }
}

// Set preset parameters during registration
Map<String, Map<String, Object>> presetParams = Map.of(
    "sendEmail", Map.of(
        "apiKey", System.getenv("EMAIL_API_KEY"),
        "from", "noreply@example.com"
    )
);

toolkit.registration()
    .tool(new EmailService())
    .presetParameters(presetParams)
    .apply();
```

**Effect**: LLM only sees `to` and `subject`, while `apiKey` and `from` are auto-injected.

### Runtime Updates

```java
// Update user context after login
toolkit.updateToolPresetParameters("uploadFile", Map.of(
    "userId", userId,
    "sessionId", sessionId
));
```

### Parameter Priority

```text
LLM-provided parameters > Preset parameters
```

LLM can override preset parameters (if needed).

---

## Tool Execution Context

### Overview

**Tool Execution Context** provides a type-safe way to pass custom objects without exposing them in the Schema.

### Difference from Preset Parameters

| Feature | Preset Parameters | Execution Context |
|---------|------------------|-------------------|
| Passing Method | Key-Value Map | Typed Objects |
| Injection Method | Match by tool name | Auto-inject by type |
| Type Safety | Runtime conversion | Compile-time check |

### Usage

```java
// 1. Define context class
public class UserContext {
    private final String userId;
    private final String role;

    public UserContext(String userId, String role) {
        this.userId = userId;
        this.role = role;
    }

    public String getUserId() { return userId; }
    public String getRole() { return role; }
}

// 2. Register to Agent
ToolExecutionContext context = ToolExecutionContext.builder()
    .register(new UserContext("user-123", "admin"))
    .build();

ReActAgent agent = ReActAgent.builder()
    .toolExecutionContext(context)
    .build();

// 3. Use in tools
@Tool(description = "Get user information")
public String getUserInfo(
        @ToolParam(name = "infoType") String infoType,
        UserContext context  // Auto-injected, no @ToolParam needed
) {
    return String.format("User %s (role: %s) information",
        context.getUserId(), context.getRole());
}
```

### Multi-Type Context

```java
// Register multiple contexts
ToolExecutionContext context = ToolExecutionContext.builder()
    .register(new UserContext(...))
    .register(new DatabaseContext(...))
    .register(new LoggingContext(...))
    .build();

// Tool auto-injects needed contexts
@Tool
public String tool(
        @ToolParam(name = "query") String query,
        UserContext userCtx,
        DatabaseContext dbCtx) {
    // Use multiple contexts
}
```

---

## Built-in Tools

AgentScope provides a set of ready-to-use built-in tools to help Agents perform common tasks.

### File Operation Tools

The file operation toolkit (`io.agentscope.core.tool.file`) provides capabilities for reading and writing text files.

**Quick Start:**

```java
import io.agentscope.core.tool.file.ReadFileTool;
import io.agentscope.core.tool.file.WriteFileTool;

// Basic registration
toolkit.registerTool(new ReadFileTool());
toolkit.registerTool(new WriteFileTool());

// Secure mode (recommended for production)
toolkit.registerTool(new ReadFileTool("/safe/workspace"));
toolkit.registerTool(new WriteFileTool("/safe/workspace"));
```

**Main Features:**

| Tool | Method | Description |
|------|--------|-------------|
| `ReadFileTool` | `view_text_file` | View files with line ranges (e.g., `1,100`) and negative indices (e.g., `-50,-1` for last 50 lines) |
| `WriteFileTool` | `write_text_file` | Create/overwrite/replace file content with optional line ranges |
| `WriteFileTool` | `insert_text_file` | Insert content at specified line number |

**Security Feature:**

Constructor supports `baseDir` parameter to restrict file access scope and prevent path traversal attacks:

```java
// Create isolated workspaces for different Agents
public Toolkit createAgentToolkit(String agentId) {
    String workspace = "/workspaces/agent_" + agentId;
    Toolkit toolkit = new Toolkit();
    toolkit.registerTool(new ReadFileTool(workspace));
    toolkit.registerTool(new WriteFileTool(workspace));
    return toolkit;
}
```

**Note:** UTF-8 encoding, line numbers start from 1, recommended to set `baseDir` in production

---

## Advanced Features

### 1. Parallel Tool Execution

```java
Toolkit toolkit = new Toolkit(ToolkitConfig.builder()
    .parallel(true)  // Enable parallel
    .build());
```

Multiple tools execute in parallel, significantly improving efficiency.

### 2. Timeout and Retry

```java
Toolkit toolkit = new Toolkit(ToolkitConfig.builder()
    .executionConfig(ExecutionConfig.builder()
        .timeout(Duration.ofSeconds(10))
        .maxRetries(2)
        .build())
    .build());
```

### 3. Streaming Tool Response

```java
@Tool
public ToolResultBlock generateData(
        @ToolParam(name = "count") int count,
        ToolEmitter emitter  // Send streaming data
) {
    for (int i = 0; i < count; i++) {
        emitter.emit(ToolResultBlock.text("Progress " + i));
    }
    return ToolResultBlock.text("Completed");
}
```

### 4. Meta Tools

Allow Agent to autonomously manage tool groups:

```java
toolkit.registerMetaTool();

// Agent can call "reset_equipped_tools" to activate tool groups
```

### 5. Prevent Tool Deletion

```java
Toolkit toolkit = new Toolkit(ToolkitConfig.builder()
    .allowToolDeletion(false)
    .build());
```


## Complete Example

```java
package io.agentscope.tutorial;

import io.agentscope.core.ReActAgent;
import io.agentscope.core.memory.InMemoryMemory;
import io.agentscope.core.message.Msg;
import io.agentscope.core.message.MsgRole;
import io.agentscope.core.message.TextBlock;
import io.agentscope.core.model.DashScopeChatModel;
import io.agentscope.core.tool.Tool;
import io.agentscope.core.tool.ToolParam;
import io.agentscope.core.tool.Toolkit;

public class ToolExample {

    /** Weather service tool */
    public static class WeatherService {
        @Tool(description = "Get current weather for a city")
        public String getWeather(
                @ToolParam(name = "city", description = "City name")
                String city) {
            // Simulate API call
            return String.format("%s weather: Sunny, 25°C", city);
        }

        @Tool(description = "Get weather forecast for next N days")
        public String getForecast(
                @ToolParam(name = "city", description = "City name")
                String city,
                @ToolParam(name = "days", description = "Number of days")
                int days) {
            return String.format("%s %d-day forecast: Mostly sunny", city, days);
        }
    }

    /** Calculator tool */
    public static class Calculator {
        @Tool(description = "Add two numbers")
        public double add(
                @ToolParam(name = "a", description = "First number") double a,
                @ToolParam(name = "b", description = "Second number") double b) {
            return a + b;
        }

        @Tool(description = "Multiply two numbers")
        public double multiply(
                @ToolParam(name = "a", description = "First number") double a,
                @ToolParam(name = "b", description = "Second number") double b) {
            return a * b;
        }
    }

    public static void main(String[] args) {
        // Create model
        DashScopeChatModel model = DashScopeChatModel.builder()
                .apiKey(System.getenv("DASHSCOPE_API_KEY"))
                .modelName("qwen-plus")
                .build();

        // Create toolkit and register tools
        Toolkit toolkit = new Toolkit();
        toolkit.registerTool(new WeatherService());
        toolkit.registerTool(new Calculator());

        // Create Agent
        ReActAgent agent = ReActAgent.builder()
                .name("Assistant")
                .sysPrompt("You are a helpful assistant. Use available tools to answer questions.")
                .model(model)
                .toolkit(toolkit)
                .memory(new InMemoryMemory())
                .maxIters(5)
                .build();

        // Test tool usage
        Msg question = Msg.builder()
                .name("user")
                .role(MsgRole.USER)
                .content(TextBlock.builder()
                        .text("What's the weather in Beijing? Also, what is 15 + 27?")
                        .build())
                .build();

        Msg response = agent.call(question).block();
        System.out.println("Question: " + question.getTextContent());
        System.out.println("Answer: " + response.getTextContent());
    }
}
```

---

## More Resources

- **Example Code**: [ToolCallingExample.java](../../examples/src/main/java/io/agentscope/examples/ToolCallingExample.java)
- **Hook Documentation**: [hook.md](./hook.md) - Monitor tool execution
- **MCP Documentation**: [mcp.md](./mcp.md) - Integrate external tools

---

**Issue Feedback**: [GitHub Issues](https://github.com/modelscope/agentscope/issues)

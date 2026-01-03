# Tool

The tool system enables agents to perform external operations such as API calls, database queries, file operations, etc.

## Core Features

- **Annotation-Based**: Quickly define tools using `@Tool` and `@ToolParam`
- **Reactive Programming**: Native support for `Mono`/`Flux` async execution
- **Auto Schema**: Automatically generate JSON Schema for LLM understanding
- **Tool Groups**: Dynamically activate/deactivate tool collections
- **Preset Parameters**: Hide sensitive parameters (e.g., API Keys)
- **Parallel Execution**: Support parallel invocation of multiple tools

## Quick Start

### Define Tools

```java
public class WeatherService {
    @Tool(description = "Get weather for a specified city")
    public String getWeather(
            @ToolParam(name = "city", description = "City name") String city) {
        return city + " weather: Sunny, 25°C";
    }
}
```

> **Note**: The `name` attribute of `@ToolParam` is required because Java doesn't preserve parameter names by default.

### Register and Use

```java
Toolkit toolkit = new Toolkit();
toolkit.registerTool(new WeatherService());

ReActAgent agent = ReActAgent.builder()
    .name("Assistant")
    .model(model)
    .toolkit(toolkit)
    .build();
```

## Tool Types

### Sync Tools

Return results directly, suitable for quick operations:

```java
@Tool(description = "Calculate sum of two numbers")
public int add(
        @ToolParam(name = "a", description = "First number") int a,
        @ToolParam(name = "b", description = "Second number") int b) {
    return a + b;
}
```

### Async Tools

Return `Mono<T>` or `Flux<T>`, suitable for I/O operations:

```java
@Tool(description = "Async search")
public Mono<String> search(
        @ToolParam(name = "query", description = "Search query") String query) {
    return webClient.get()
        .uri("/search?q=" + query)
        .retrieve()
        .bodyToMono(String.class);
}
```

### Streaming Tools

Use `ToolEmitter` to send intermediate progress, suitable for long-running tasks:

```java
@Tool(description = "Generate data")
public ToolResultBlock generate(
        @ToolParam(name = "count") int count,
        ToolEmitter emitter) {  // Auto-injected, no @ToolParam needed
    for (int i = 0; i < count; i++) {
        emitter.emit(ToolResultBlock.text("Progress " + i));
    }
    return ToolResultBlock.text("Completed");
}
```

### Return Types

| Return Type | Description |
|-------------|-------------|
| `String`, `int`, `Object`, etc. | Sync execution, auto-converted to `ToolResultBlock` |
| `Mono<T>` | Async execution |
| `Flux<T>` | Streaming execution |
| `ToolResultBlock` | Direct control over return format (text, image, error, etc.) |

## Tool Groups

Manage tools by scenario with dynamic activation/deactivation:

```java
// Create tool groups
toolkit.createToolGroup("basic", "Basic Tools", true);   // Active by default
toolkit.createToolGroup("admin", "Admin Tools", false);  // Inactive by default

// Register to tool groups
toolkit.registration()
    .tool(new BasicTools())
    .group("basic")
    .apply();

// Dynamic switching
toolkit.updateToolGroups(List.of("admin"), true);   // Activate
toolkit.updateToolGroups(List.of("basic"), false);  // Deactivate
```

**Use Cases**:
- Permission control: Activate different tools based on user roles
- Scenario switching: Use different tool sets at different conversation stages
- Performance optimization: Reduce the number of tools visible to LLM

## Preset Parameters

Hide sensitive parameters (e.g., API Key) from LLM:

```java
public class EmailService {
    @Tool(description = "Send email")
    public String send(
            @ToolParam(name = "to") String to,
            @ToolParam(name = "subject") String subject,
            @ToolParam(name = "apiKey") String apiKey) {  // Preset, invisible to LLM
        return "Sent";
    }
}

toolkit.registration()
    .tool(new EmailService())
    .presetParameters(Map.of(
        "send", Map.of("apiKey", System.getenv("EMAIL_API_KEY"))
    ))
    .apply();
```

**Effect**: LLM only sees `to` and `subject` parameters; `apiKey` is auto-injected.

## Tool Execution Context

Pass business objects (e.g., user info) to tools without exposing to LLM:

```java
// 1. Define context class
public class UserContext {
    private final String userId;
    public UserContext(String userId) { this.userId = userId; }
    public String getUserId() { return userId; }
}

// 2. Register to Agent
ToolExecutionContext context = ToolExecutionContext.builder()
    .register(new UserContext("user-123"))
    .build();

ReActAgent agent = ReActAgent.builder()
    .name("Assistant")
    .model(model)
    .toolkit(toolkit)
    .toolExecutionContext(context)
    .build();

// 3. Use in tools (auto-injected)
@Tool(description = "Query user data")
public String query(
        @ToolParam(name = "sql") String sql,
        UserContext ctx) {  // Auto-injected, no @ToolParam needed
    return "Data for user " + ctx.getUserId();
}
```

> See [Agent](../quickstart/agent.md) documentation for detailed configuration.

## Built-in Tools

### File Tools

```java
import io.agentscope.core.tool.file.ReadFileTool;
import io.agentscope.core.tool.file.WriteFileTool;

// Basic registration
toolkit.registerTool(new ReadFileTool());
toolkit.registerTool(new WriteFileTool());

// Secure mode (recommended): Restrict file access scope
toolkit.registerTool(new ReadFileTool("/safe/workspace"));
toolkit.registerTool(new WriteFileTool("/safe/workspace"));
```

| Tool | Method | Description |
|------|--------|-------------|
| `ReadFileTool` | `view_text_file` | View files by line range |
| `WriteFileTool` | `write_text_file` | Create/overwrite/replace file content |
| `WriteFileTool` | `insert_text_file` | Insert content at specified line |

### Shell Command Tool

| Tool | Features |
|------|----------|
| `ShellCommandTool` | Execute shell commands with whitelist, callback approval, and timeout support |

**Quick Start:**

```java
import io.agentscope.core.tool.coding.ShellCommandTool;

Function<String, Boolean> callback = cmd -> askUserForApproval(cmd);
toolkit.registerTool(new ShellCommandTool(allowedCommands, callback));
```

### Multimodal Tools

```java
import io.agentscope.core.tool.multimodal.DashScopeMultiModalTool;
import io.agentscope.core.tool.multimodal.OpenAIMultiModalTool;

toolkit.registerTool(new DashScopeMultiModalTool(System.getenv("DASHSCOPE_API_KEY")));
toolkit.registerTool(new OpenAIMultiModalTool(System.getenv("OPENAI_API_KEY")));
```

| Tool | Capabilities |
|------|--------------|
| `DashScopeMultiModalTool` | Text-to-image, image-to-text, text-to-speech, speech-to-text |
| `OpenAIMultiModalTool` | Text-to-image, image editing, image variations, image-to-text, text-to-speech, speech-to-text |

### Sub-agent Tools

Agents can be registered as tools for other agents to call. See [Agent as Tool](../multi-agent/agent-as-tool.md) for details.

## AgentTool Interface

For fine-grained control, implement the interface directly:

```java
public class CustomTool implements AgentTool {
    @Override
    public String getName() { return "custom_tool"; }

    @Override
    public String getDescription() { return "Custom tool"; }

    @Override
    public Map<String, Object> getParameters() {
        return Map.of(
            "type", "object",
            "properties", Map.of(
                "query", Map.of("type", "string", "description", "Query")
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

## Configuration Options

```java
Toolkit toolkit = new Toolkit(ToolkitConfig.builder()
    .parallel(true)                    // Parallel execution of multiple tools
    .allowToolDeletion(false)          // Prevent tool deletion
    .executionConfig(ExecutionConfig.builder()
        .timeout(Duration.ofSeconds(30))
        .build())
    .build());
```

| Option | Description | Default |
|--------|-------------|---------|
| `parallel` | Whether to execute multiple tools in parallel | `true` |
| `allowToolDeletion` | Whether to allow tool deletion | `true` |
| `executionConfig.timeout` | Tool execution timeout | 5 minutes |

## Meta Tools

Allow agents to autonomously manage tool groups:

```java
toolkit.registerMetaTool();
// Agent can call "reset_equipped_tools" to activate/deactivate tool groups
```

When there are many tool groups, agents can autonomously choose which groups to activate based on task requirements.

## Tool Suspend

When a tool throws `ToolSuspendException`, the Agent execution pauses and returns to the caller, allowing external systems to perform the actual execution before resuming.

**Use Cases**:
- Tool requires external system execution (e.g., remote API, user manual operation)
- Need to asynchronously wait for external results

**Usage**:

```java
@Tool(name = "external_api", description = "Call external API")
public ToolResultBlock callExternalApi(
        @ToolParam(name = "url") String url) {
    // Throw exception to suspend execution
    throw new ToolSuspendException("Awaiting external API response: " + url);
}
```

**Resume Execution**:

```java
Msg response = agent.call(userMsg).block();

// Check if suspended
if (response.getGenerateReason() == GenerateReason.TOOL_SUSPENDED) {
    // Get pending tool calls
    List<ToolUseBlock> pendingTools = response.getContentBlocks(ToolUseBlock.class);

    // After external execution, provide result
    Msg toolResult = Msg.builder()
        .role(MsgRole.TOOL)
        .content(ToolResultBlock.of(toolUse.getId(), toolUse.getName(),
            TextBlock.builder().text("External execution result").build()))
        .build();

    // Resume execution
    response = agent.call(toolResult).block();
}
```

## Schema Only Tool

Register only the tool's schema (name, description, parameters) without execution logic. When LLM calls this tool, the framework automatically triggers suspension and returns to the caller for execution.

**Use Cases**:
- Tool implemented by external systems (e.g., frontend, other services)
- Dynamically register third-party tools

**Usage**:

```java
// Method 1: Using ToolSchema
ToolSchema schema = ToolSchema.builder()
    .name("query_database")
    .description("Query external database")
    .parameters(Map.of(
        "type", "object",
        "properties", Map.of("sql", Map.of("type", "string")),
        "required", List.of("sql")
    ))
    .build();

toolkit.registerSchema(schema);

// Method 2: Batch registration
toolkit.registerSchemas(List.of(schema1, schema2));

// Check if it's an external tool
boolean isExternal = toolkit.isExternalTool("query_database");  // true
```

The call flow is the same as Tool Suspend: LLM calls → returns `TOOL_SUSPENDED` → external execution → provide result to resume.

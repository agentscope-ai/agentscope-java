# Tool Execution Engine

This document provides an in-depth look at the internal implementation of the AgentScope tool system, including tool registration, Schema generation, parameter injection, and execution scheduling.

## Architecture Overview

The tool system consists of the following core components:

```
                    ┌─────────────────────────────────────────┐
                    │               Toolkit                    │
                    │  ┌─────────────────────────────────────┐│
                    │  │         Tool Registry               ││
                    │  │  ┌───────────┐  ┌───────────┐      ││
                    │  │  │ AgentTool │  │ AgentTool │ ...  ││
                    │  │  └───────────┘  └───────────┘      ││
                    │  └─────────────────────────────────────┘│
                    │  ┌─────────────────────────────────────┐│
                    │  │         Tool Groups                 ││
                    │  └─────────────────────────────────────┘│
                    │  ┌─────────────────────────────────────┐│
                    │  │      Preset Parameters              ││
                    │  └─────────────────────────────────────┘│
                    │  ┌─────────────────────────────────────┐│
                    │  │    ToolExecutionContext             ││
                    │  └─────────────────────────────────────┘│
                    └─────────────────────────────────────────┘
                                        │
                                        ▼
                    ┌─────────────────────────────────────────┐
                    │            Tool Executor                │
                    │  ┌─────────────┐  ┌─────────────────┐  │
                    │  │ Parameter   │  │ Result          │  │
                    │  │ Injector    │  │ Processor       │  │
                    │  └─────────────┘  └─────────────────┘  │
                    └─────────────────────────────────────────┘
```

## Tool Registration

### Annotation Parsing

When registering an object with `@Tool` annotations, the framework parses method information via reflection:

```java
// Registration process
toolkit.registerTool(new WeatherService());

// Framework internal processing
for (Method method : clazz.getDeclaredMethods()) {
    Tool annotation = method.getAnnotation(Tool.class);
    if (annotation != null) {
        AgentTool tool = buildToolFromMethod(instance, method, annotation);
        registry.put(tool.getName(), tool);
    }
}
```

### Method to AgentTool Conversion

```java
@Tool(name = "get_weather", description = "Get weather information")
public String getWeather(
    @ToolParam(name = "city", description = "City name") String city,
    @ToolParam(name = "date", description = "Date", required = false) String date
) { ... }

// Converts to AgentTool
AgentTool {
    name: "get_weather",
    description: "Get weather information",
    parameters: {
        "type": "object",
        "properties": {
            "city": {"type": "string", "description": "City name"},
            "date": {"type": "string", "description": "Date"}
        },
        "required": ["city"]
    }
}
```

## Schema Generation

### JSON Schema Structure

Tool parameters are generated in JSON Schema format for LLM understanding:

```json
{
  "type": "object",
  "properties": {
    "city": {
      "type": "string",
      "description": "City name"
    },
    "days": {
      "type": "integer",
      "description": "Forecast days"
    }
  },
  "required": ["city"]
}
```

### Java Type to JSON Schema Mapping

| Java Type | JSON Schema Type |
|-----------|------------------|
| `String` | `"string"` |
| `int`, `Integer` | `"integer"` |
| `long`, `Long` | `"integer"` |
| `double`, `Double` | `"number"` |
| `float`, `Float` | `"number"` |
| `boolean`, `Boolean` | `"boolean"` |
| `List<T>` | `"array"` |
| `Map<String, T>` | `"object"` |
| POJO | `"object"` (nested properties) |

### Complex Type Handling

```java
// POJO parameter
public class SearchParams {
    private String query;
    private int limit;
    private List<String> filters;
}

@Tool(description = "Search")
public String search(@ToolParam(name = "params") SearchParams params) { ... }

// Generated Schema
{
  "type": "object",
  "properties": {
    "params": {
      "type": "object",
      "properties": {
        "query": {"type": "string"},
        "limit": {"type": "integer"},
        "filters": {"type": "array", "items": {"type": "string"}}
      }
    }
  }
}
```

## Parameter Injection Mechanism

During tool execution, parameters come from multiple sources with priority-based injection.

### Injection Priority

```
┌─────────────────────────────────────────────────────────────┐
│                  Parameter Source Priority                   │
│                                                             │
│  Priority 1: LLM-provided parameters                        │
│     ↓                                                       │
│  Priority 2: Preset Parameters                              │
│     ↓                                                       │
│  Priority 3: ToolExecutionContext                           │
│                                                             │
└─────────────────────────────────────────────────────────────┘
```

### Preset Parameters

Preset parameters are configured during registration, invisible to LLM but auto-injected during execution:

```java
// Define tool
@Tool(description = "Send email")
public String sendEmail(
    @ToolParam(name = "to") String to,
    @ToolParam(name = "subject") String subject,
    @ToolParam(name = "apiKey") String apiKey  // Hidden parameter
) { ... }

// Set preset parameters during registration
toolkit.registration()
    .tool(new EmailService())
    .presetParameters(Map.of(
        "sendEmail", Map.of("apiKey", System.getenv("EMAIL_API_KEY"))
    ))
    .apply();
```

**Effect**:
- LLM sees Schema with only `to` and `subject`
- `apiKey` is auto-injected from preset parameters during execution

### Execution Context

Execution context auto-injects objects by type matching:

```java
// Define context class
public class UserContext {
    private String userId;
    private String role;
    // getters...
}

// Tool method receives context
@Tool(description = "Get user data")
public String getUserData(
    @ToolParam(name = "dataType") String dataType,
    UserContext ctx  // No @ToolParam needed, injected by type
) {
    return "User " + ctx.getUserId() + " data: " + dataType;
}

// Configure context
ToolExecutionContext context = ToolExecutionContext.builder()
    .register(new UserContext("user123", "admin"))
    .build();

ReActAgent agent = ReActAgent.builder()
    .toolExecutionContext(context)
    .build();
```

### Context Priority Chain

Context can be configured at multiple levels, forming a priority chain:

```
Call-level Context (highest priority)
        ↓
Agent-level Context
        ↓
Toolkit-level Context (lowest priority)
```

## Special Parameter Injection

Certain parameter types are auto-injected by the framework without `@ToolParam`.

### ToolEmitter

Used for streaming tool progress:

```java
@Tool(description = "Batch processing")
public ToolResultBlock batchProcess(
    @ToolParam(name = "items") List<String> items,
    ToolEmitter emitter  // Auto-injected
) {
    for (int i = 0; i < items.size(); i++) {
        processItem(items.get(i));
        emitter.emit(ToolResultBlock.text("Progress: " + (i + 1) + "/" + items.size()));
    }
    return ToolResultBlock.text("Complete");
}
```

### Agent Reference

Tools can get the current executing Agent instance:

```java
@Tool(description = "Get agent name")
public String getAgentName(Agent agent) {  // Auto-injected
    return agent.getName();
}
```

## Execution Scheduling

### Sync vs Async Execution

The framework supports both synchronous and asynchronous tools:

```java
// Synchronous tool
@Tool(description = "Sync calculation")
public int add(int a, int b) {
    return a + b;
}

// Asynchronous tool
@Tool(description = "Async search")
public Mono<String> search(String query) {
    return webClient.get()
        .uri("/search?q=" + query)
        .retrieve()
        .bodyToMono(String.class);
}
```

### Parallel Execution

When LLM returns multiple tool calls, they can be executed in parallel:

```java
ToolkitConfig config = ToolkitConfig.builder()
    .parallel(true)
    .build();
```

**Parallel Execution Flow**:

```
LLM returns: [ToolUseBlock A, ToolUseBlock B, ToolUseBlock C]
                    │
                    ▼
            ┌───────┴───────┐
            │Parallel Executor│
            └───────┬───────┘
                    │
        ┌───────────┼───────────┐
        ▼           ▼           ▼
    Execute A   Execute B   Execute C
        │           │           │
        └───────────┼───────────┘
                    │
                    ▼
            [Result A, Result B, Result C]
```

### Timeout and Retry

```java
ToolkitConfig config = ToolkitConfig.builder()
    .executionConfig(ExecutionConfig.builder()
        .timeout(Duration.ofSeconds(30))
        .maxAttempts(3)
        .initialBackoff(Duration.ofSeconds(1))
        .backoffMultiplier(2.0)
        .build())
    .build();
```

## Tool Group Management

Tool groups allow dynamic activation/deactivation of tool sets.

### Internal Structure

```java
class Toolkit {
    // All registered tools
    private Map<String, AgentTool> allTools;

    // Tool group definitions
    private Map<String, ToolGroup> toolGroups;

    // Currently active groups
    private Set<String> activeGroups;

    // Get currently available tools (only from active groups)
    public List<AgentTool> getActiveTools() {
        return allTools.values().stream()
            .filter(tool -> isToolActive(tool))
            .toList();
    }
}
```

### Dynamic Switching

```java
// Create tool groups
toolkit.createToolGroup("basic", "Basic tools", true);
toolkit.createToolGroup("admin", "Admin tools", false);

// Register to group
toolkit.registration()
    .tool(new BasicTools())
    .group("basic")
    .apply();

// Dynamic switching
toolkit.updateToolGroups(List.of("admin"), true);   // Activate
toolkit.updateToolGroups(List.of("basic"), false);  // Deactivate
```

## Result Processing

### Return Value Conversion

Tool return values are automatically converted to `ToolResultBlock`:

| Return Type | Conversion Result |
|-------------|-------------------|
| `String` | `ToolResultBlock.text(value)` |
| `ToolResultBlock` | Used directly |
| `Mono<String>` | Wait async then convert |
| `Mono<ToolResultBlock>` | Wait async then use |
| Other objects | JSON serialize then wrap as TextBlock |

### Error Handling

Tool execution exceptions are automatically converted to error results:

```java
@Tool(description = "Risky operation")
public String riskyOperation(String input) {
    if (invalid(input)) {
        throw new IllegalArgumentException("Invalid input");
    }
    return "Success";
}

// Exception auto-converts to:
ToolResultBlock.error(toolUseId, "Invalid input");
```

## Related Documentation

- [ReAct Loop Internals](./react-loop.md)
- [Tool System Usage](../task/tool.md)

# Tool

Tools enable agents to perform actions beyond text generation, such as calling APIs, executing code, or accessing external systems.

## Tool System Overview

AgentScope Java provides a comprehensive tool system with these features:

- **Annotation-based** tool registration from Java methods
- Support for **synchronous** and **asynchronous** tools
- **Type-safe** parameter binding
- **Automatic** JSON schema generation
- **Streaming** tool responses
- **Tool groups** for dynamic tool management

## Creating Tools

### Basic Tool

Use `@Tool` and `@ToolParam` annotations to create tools:

```java
import io.agentscope.core.tool.Tool;
import io.agentscope.core.tool.ToolParam;

public class WeatherService {

    @Tool(description = "Get current weather for a location")
    public String getWeather(
            @ToolParam(name = "location", description = "City name")
            String location) {
        // Call weather API
        return "Sunny, 25°C in " + location;
    }
}
```

> **Important**: The `@ToolParam` annotation **requires** an explicit `name` attribute because Java does not preserve parameter names at runtime by default.

### Async Tools

Use `Mono` or `Flux` for asynchronous operations:

```java
import reactor.core.publisher.Mono;
import java.time.Duration;

public class AsyncService {

    @Tool(description = "Search the web asynchronously")
    public Mono<String> searchWeb(
            @ToolParam(name = "query", description = "Search query")
            String query) {
        return Mono.delay(Duration.ofSeconds(1))
                .map(ignored -> "Results for: " + query);
    }
}
```

### Multiple Parameters

Tools can have multiple parameters:

```java
public class Calculator {

    @Tool(description = "Calculate the sum of two numbers")
    public int add(
            @ToolParam(name = "a", description = "First number") int a,
            @ToolParam(name = "b", description = "Second number") int b) {
        return a + b;
    }

    @Tool(description = "Calculate power of a number")
    public double power(
            @ToolParam(name = "base", description = "Base number") double base,
            @ToolParam(name = "exponent", description = "Exponent") double exponent) {
        return Math.pow(base, exponent);
    }
}
```

## Toolkit

The `Toolkit` class manages tool registration and execution.

### Registering Tools

```java
import io.agentscope.core.tool.Toolkit;

Toolkit toolkit = new Toolkit();

// Register all @Tool methods from an object
toolkit.registerTool(new WeatherService());
toolkit.registerTool(new Calculator());

// Register multiple objects at once
toolkit.registerTool(
        new WeatherService(),
        new Calculator(),
        new DataService()
);
```

### Using with Agent

```java
import io.agentscope.core.ReActAgent;
import io.agentscope.core.model.DashScopeChatModel;

Toolkit toolkit = new Toolkit();
toolkit.registerTool(new WeatherService());

ReActAgent agent = ReActAgent.builder()
        .name("Assistant")
        .model(model)
        .toolkit(toolkit)  // Provide toolkit to agent
        .sysPrompt("You are a helpful assistant. Use tools when needed.")
        .build();
```

## Advanced Registration Options

For scenarios requiring more configuration, the Builder API provides clearer syntax:

### Registering Tools with Builder

```java
// Basic registration
toolkit.registration()
    .tool(new WeatherService())
    .apply();

// Specify tool group
toolkit.registration()
    .tool(new WeatherService())
    .group("weatherTools")
    .apply();

// Register AgentTool instance
toolkit.registration()
    .agentTool(customAgentTool)
    .group("customTools")
    .apply();

// Combine multiple options
toolkit.registration()
    .tool(new APIService())
    .group("apiTools")
    .presetParameters(Map.of("apiKey", "secret"))
    .extendedModel(customModel)
    .apply();

// Register MCP client
toolkit.registration()
    .mcpClient(mcpClientWrapper)
    .enableTools(List.of("tool1", "tool2"))
    .group("mcpTools")
    .apply();
```

### Builder API Benefits

- **Clarity**: Parameter intent is explicit, no need to remember parameter order
- **Optional**: Only set parameters you need
- **Type-safe**: Compile-time checking of all configurations
- **Extensible**: Future options can be added without modifying existing code

## Preset Parameters

Preset parameters allow you to set default parameter values during tool registration that are automatically injected during execution but not exposed in the JSON schema. This is useful for passing contextual information such as API keys, user IDs, or session information.

### Registering Tools with Preset Parameters

```java
import java.util.Map;

public class APIService {
    @Tool(description = "Call external API")
    public String callAPI(
            @ToolParam(name = "query", description = "Query content") String query,
            @ToolParam(name = "apiKey", description = "API key") String apiKey,
            @ToolParam(name = "userId", description = "User ID") String userId) {
        // Use apiKey and userId to call API
        return String.format("Results for user %s querying '%s'", userId, query);
    }
}

// Provide preset parameters when registering the tool
Toolkit toolkit = new Toolkit();
Map<String, Map<String, Object>> presetParams = Map.of(
    "callAPI", Map.of(
        "apiKey", "sk-your-api-key",
        "userId", "user-123"
    )
);
toolkit.registration()
    .tool(new APIService())
    .presetParameters(presetParams)
    .apply();
```

In the above example:
- `apiKey` and `userId` are automatically injected into every tool call
- These parameters are **not** exposed in the tool's JSON schema
- The agent only needs to provide the `query` parameter

### Parameter Priority

Agent-provided parameters can override preset parameters:

```java
// Preset parameters: apiKey="default-key", userId="default-user"
Map<String, Map<String, Object>> presetParams = Map.of(
    "callAPI", Map.of("apiKey", "default-key", "userId", "default-user")
);
toolkit.registration()
    .tool(service)
    .presetParameters(presetParams)
    .apply();

// When the agent provides userId, it overrides the preset value
// Actual execution: query="test", apiKey="default-key", userId="agent-user"
```

### Updating Preset Parameters at Runtime

You can dynamically update a tool's preset parameters at runtime:

```java
// Initial registration
Map<String, Map<String, Object>> initialParams = Map.of(
    "sessionTool", Map.of("sessionId", "session-001")
);
toolkit.registration()
    .tool(new SessionTool())
    .presetParameters(initialParams)
    .apply();

// Later update the session ID
Map<String, Object> updatedParams = Map.of("sessionId", "session-002");
toolkit.updateToolPresetParameters("sessionTool", updatedParams);
```

### Preset Parameters for MCP Tools

MCP (Model Context Protocol) tools also support preset parameters using the Builder API:

```java
// Set different preset parameters for different MCP tools
Map<String, Map<String, Object>> presetMapping = Map.of(
    "tool1", Map.of("apiKey", "key1", "region", "us-west"),
    "tool2", Map.of("apiKey", "key2", "region", "eu-central")
);

toolkit.registration()
    .mcpClient(mcpClientWrapper)
    .enableTools(List.of("tool1", "tool2"))
    .disableTools(List.of("tool3"))
    .group("mcp-group")
    .presetParameters(presetMapping)
    .apply();
```

## Tool Schemas

AgentScope automatically generates JSON schemas for tools:

```java
Toolkit toolkit = new Toolkit();
toolkit.registerTool(new WeatherService());

// Get all tool schemas
List<ToolSchema> schemas = toolkit.getToolSchemas();

for (ToolSchema schema : schemas) {
    System.out.println("Tool: " + schema.getName());
    System.out.println("Description: " + schema.getDescription());
    System.out.println("Parameters: " + schema.getParameters());
}
```

## Complete Example

```java
package io.agentscope.tutorial.task;

import io.agentscope.core.ReActAgent;
import io.agentscope.core.memory.InMemoryMemory;
import io.agentscope.core.message.Msg;
import io.agentscope.core.message.MsgRole;
import io.agentscope.core.message.TextBlock;
import io.agentscope.core.model.DashScopeChatModel;
import io.agentscope.core.tool.Tool;
import io.agentscope.core.tool.ToolParam;
import io.agentscope.core.tool.Toolkit;
import java.util.List;

public class ToolExample {

    /** Weather service tool */
    public static class WeatherService {
        @Tool(description = "Get current weather for a city")
        public String getWeather(
                @ToolParam(name = "city", description = "City name")
                String city) {
            // Simulate API call
            return String.format("Weather in %s: Sunny, 25°C", city);
        }

        @Tool(description = "Get weather forecast for next N days")
        public String getForecast(
                @ToolParam(name = "city", description = "City name")
                String city,
                @ToolParam(name = "days", description = "Number of days")
                int days) {
            return String.format("%d-day forecast for %s: Mostly sunny", days, city);
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

        // Create agent with tools
        ReActAgent agent = ReActAgent.builder()
                .name("Assistant")
                .sysPrompt("You are a helpful assistant. Use the available tools to answer questions.")
                .model(model)
                .toolkit(toolkit)
                .memory(new InMemoryMemory())
                .maxIters(5)
                .build();

        // Test with tool usage
        Msg question = Msg.builder()
                .name("user")
                .role(MsgRole.USER)
                .content(List.of(TextBlock.builder().text(
                        "What's the weather in Beijing? Also, what is 15 + 27?"
                )))
                .build();

        Msg response = agent.call(question).block();
        System.out.println("Question: " + question.getTextContent());
        System.out.println("Answer: " + response.getTextContent());
    }
}
```

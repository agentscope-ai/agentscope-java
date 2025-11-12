# Tool

Tools enable agents to perform actions beyond text generation, such as calling APIs, executing code, or accessing external systems.

## Tool System Overview

AgentScope Java provides a comprehensive tool system with these features:

- **Annotation-based** tool registration from Java methods
- Support for **synchronous** and **asynchronous** tools
- **Type-safe** parameter binding
- **Automatic** JSON schema generation
- **Streaming** tool responses (not yet implemented but planned)
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

        // Check tool schemas
        System.out.println("\nRegistered tools:");
        for (var schema : toolkit.getToolSchemas()) {
            System.out.println("- " + schema.getName() + ": " + schema.getDescription());
        }
    }
}
```

# Agent

AgentScope provides an out-of-the-box ReAct agent implementation that supports hooks, tools, memory, structured output, and real-time interruption.

### Basic Example

```java
package io.agentscope.tutorial.quickstart;

import io.agentscope.core.ReActAgent;
import io.agentscope.core.memory.InMemoryMemory;
import io.agentscope.core.message.Msg;
import io.agentscope.core.message.MsgRole;
import io.agentscope.core.message.TextBlock;
import io.agentscope.core.model.DashScopeChatModel;
import io.agentscope.core.tool.Toolkit;
import java.util.List;

public class AgentExample {
    public static void main(String[] args) {
        // Build the agent
        ReActAgent agent = ReActAgent.builder()
                .name("Jarvis")
                .sysPrompt("You are a helpful assistant named Jarvis.")
                .model(DashScopeChatModel.builder()
                        .apiKey(System.getenv("DASHSCOPE_API_KEY"))
                        .modelName("qwen-plus")
                        .build())
                .toolkit(new Toolkit())
                .memory(new InMemoryMemory())
                .maxIters(10)
                .build();

        // Create input message
        Msg inputMsg = Msg.builder()
                .name("user")
                .role(MsgRole.USER)
                .content(List.of(TextBlock.builder().text("Hello! What can you do?").build()))
                .build();

        // Call the agent (blocking)
        Msg response = agent.call(inputMsg).block();
        System.out.println("Agent: " + response.getTextContent());
    }
}
```

## Builder Parameters

The `ReActAgent.Builder` exposes the following parameters:

| Parameter             | Type                    | Required | Description                                              |
|-----------------------|-------------------------|----------|----------------------------------------------------------|
| name                  | String                  | Yes      | The name of the agent                                    |
| sysPrompt             | String                  | Yes      | System prompt for the agent                              |
| model                 | Model                   | Yes      | The LLM model for reasoning                              |
| toolkit               | Toolkit                 | No       | Tools available to the agent (default: empty)            |
| memory                | Memory                  | No       | Short-term memory (default: InMemoryMemory)              |
| maxIters              | int                     | No       | Maximum reasoning-acting iterations (default: 10)        |
| hooks                 | List<Hook>              | No       | Hooks for monitoring execution (default: empty)          |
| modelExecutionConfig  | ExecutionConfig         | No       | Timeout/retry config for model calls                     |
| toolExecutionConfig   | ExecutionConfig         | No       | Timeout/retry config for tool calls                      |

## Agent Methods

### call()

Process input message(s) and generate a response:

```java
// Call with single message
Mono<Msg> response = agent.call(inputMsg);

// Call with multiple messages
Mono<Msg> response = agent.call(List.of(msg1, msg2, msg3));

// Call without new input (continue from current state)
Mono<Msg> response = agent.call();
```

### stream()

Stream responses with real-time updates:

```java
import io.agentscope.core.agent.Event;
import io.agentscope.core.agent.EventType;

Flux<Event> eventStream = agent.stream(inputMsg);

eventStream.subscribe(event -> {
    if (event.getEventType() == EventType.TEXT_CHUNK) {
        System.out.print(event.getChunk().getText());
    } else if (event.getEventType() == EventType.TEXT_COMPLETE) {
        System.out.println("\n[Complete]");
    }
});
```

### interrupt()

Interrupt the agent during execution:

```java
// In another thread
agent.interrupt();

// Or interrupt with a message
Msg interruptMsg = Msg.builder()
        .name("user")
        .role(MsgRole.USER)
        .content(List.of(TextBlock.builder().text("Please stop.").build()))
        .build();
agent.interrupt(interruptMsg);
```

## Adding Tools

Tools enable agents to perform actions beyond text generation:

```java
import io.agentscope.core.tool.Tool;
import io.agentscope.core.tool.ToolParam;

public class WeatherService {

    @Tool(description = "Get current weather for a location")
    public String getWeather(
            @ToolParam(name = "location", description = "City name")
            String location) {
        // Call actual weather API here
        return "Sunny, 25°C in " + location;
    }
}

// Register tools
Toolkit toolkit = new Toolkit();
toolkit.registerTool(new WeatherService());

// Build agent with tools
ReActAgent agent = ReActAgent.builder()
        .name("Assistant")
        .sysPrompt("You are a helpful assistant. Use available tools to answer questions.")
        .model(DashScopeChatModel.builder()
                .apiKey(System.getenv("DASHSCOPE_API_KEY"))
                .modelName("qwen-plus")
                .build())
        .toolkit(toolkit)
        .build();
```

> **Important**: The `@ToolParam` annotation requires an explicit `name` attribute because Java does not preserve parameter names at runtime by default.

## Structured Output

Request structured output from the agent:

```java
public class TaskPlan {
    public String goal;
    public List<String> steps;
    public int priority;
}

// Call with structured output
Mono<Msg> response = agent.call(inputMsg, TaskPlan.class);

// Extract structured data
Msg result = response.block();
if (result.hasStructuredData()) {
    TaskPlan plan = result.getStructuredData(TaskPlan.class);
    System.out.println("Goal: " + plan.goal);
    System.out.println("Steps: " + plan.steps);
}
```

## Memory Management

AgentScope automatically manages conversation history through the `Memory` interface:

```java
import io.agentscope.core.memory.InMemoryMemory;

Memory memory = new InMemoryMemory();

ReActAgent agent = ReActAgent.builder()
        .name("Assistant")
        .model(DashScopeChatModel.builder()
                .apiKey(System.getenv("DASHSCOPE_API_KEY"))
                .modelName("qwen-plus")
                .build())
        .memory(memory)
        .build();

// The agent automatically stores messages in memory
agent.call(msg1).block();
agent.call(msg2).block();

// Access conversation history
List<Msg> history = memory.getAllMessages();
System.out.println("Total messages: " + history.size());
```

## Reactive Programming

AgentScope Java uses Project Reactor for asynchronous operations:

```java
// Non-blocking execution
Mono<Msg> responseMono = agent.call(inputMsg);

// Chain operations
responseMono
    .map(msg -> msg.getTextContent())
    .doOnNext(text -> System.out.println("Response: " + text))
    .subscribe();

// Multiple parallel calls
List<Mono<Msg>> calls = List.of(
    agent.call(msg1),
    agent.call(msg2),
    agent.call(msg3)
);

Flux.merge(calls)
    .collectList()
    .subscribe(responses -> {
        System.out.println("All responses received: " + responses.size());
    });
```

## Complete Example with Tools

```java
package io.agentscope.tutorial.quickstart;

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

public class CompleteAgentExample {

    // Tool class
    public static class Calculator {
        @Tool(description = "Add two numbers")
        public int add(
                @ToolParam(name = "a", description = "First number") int a,
                @ToolParam(name = "b", description = "Second number") int b) {
            return a + b;
        }

        @Tool(description = "Multiply two numbers")
        public int multiply(
                @ToolParam(name = "a", description = "First number") int a,
                @ToolParam(name = "b", description = "Second number") int b) {
            return a * b;
        }
    }

    public static void main(String[] args) {
        // Create toolkit with calculator tools
        Toolkit toolkit = new Toolkit();
        toolkit.registerTool(new Calculator());

        // Build agent
        ReActAgent agent =
                ReActAgent.builder()
                        .name("MathAssistant")
                        .sysPrompt("You are a math assistant. Use the calculator tools to help users.")
                        .model(DashScopeChatModel.builder()
                                .apiKey(System.getenv("DASHSCOPE_API_KEY"))
                                .modelName("qwen-plus")
                                .build())
                        .toolkit(toolkit)
                        .memory(new InMemoryMemory())
                        .maxIters(5)
                        .build();

        // Test the agent
        Msg question =
                Msg.builder()
                        .name("user")
                        .role(MsgRole.USER)
                        .content(List.of(TextBlock.builder().text("What is (15 + 7) * 3?").build()))
                        .build();

        Msg response = agent.call(question).block();
        System.out.println("Question: " + question.getTextContent());
        System.out.println("Answer: " + response.getTextContent());
    }
}
```

## Next Steps

- [Tool](../task/tool.md) - Learn about tool system in detail
- [Hook](../task/hook.md) - Customize agent behavior with hooks
- [Model](../task/model.md) - Configure different LLM models
- [Pipeline](../task/pipeline.md) - Compose multiple agents

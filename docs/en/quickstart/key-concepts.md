# Key Concepts

## Overview

AgentScope is built around these core concepts:

**Data Flow**:
- **Message**: The fundamental data structure - everything flows as messages
- **Tool**: Functions agents can call to interact with external systems
- **Memory**: Storage for conversation history

**Agent System**:
- **Agent**: Processes messages and generates responses
- **ReActAgent**: Main implementation using reasoning + acting loop
- **Formatter**: Converts messages to LLM-specific formats

**Execution Control**:
- **Hook**: Extension points for customizing behavior at specific stages
- **Reactive Programming**: Non-blocking async operations using Reactor

**State & Composition**:
- **State Management**: Save and restore agent state
- **Session**: Persistent storage across application runs
- **Pipeline**: Compose multiple agents into workflows

**How they work together**:

```
User Input (Message)
    ↓
Agent (ReActAgent)
    ├─→ Formatter → LLM API
    ├─→ Tool execution
    ├─→ Memory storage
    └─→ Hook events
    ↓
Response (Message)
```

Now let's explore each concept in detail.

---

## Message

Message is the fundamental data structure in AgentScope - used for agent communication, memory storage, and LLM I/O.

Structure:
- **name**: Sender identity (useful in multi-agent scenarios)
- **role**: `USER`, `ASSISTANT`, `SYSTEM`, or `TOOL`
- **content**: List of content blocks (text, images, tool calls, etc.)
- **metadata**: Optional structured data

Content types:
- **TextBlock**: Plain text
- **ImageBlock/AudioBlock/VideoBlock**: Media (URL or Base64)
- **ThinkingBlock**: Reasoning traces
- **ToolUseBlock**: Tool invocation (from LLM)
- **ToolResultBlock**: Tool execution result

Example:

```java
// Text message
Msg msg = Msg.builder()
    .name("Alice")
    .textContent("Hello!")
    .build();

// Multimodal
Msg imgMsg = Msg.builder()
    .name("Assistant")
    .content(List.of(
        TextBlock.builder().text("Here's the chart:").build(),
        ImageBlock.builder().source(URLSource.of("https://example.com/chart.png")).build()
    ))
    .build();
```

## Tool

Any Java method with `@Tool` annotation becomes a tool. Supports instance/static methods, sync/async, streaming/non-streaming.

```java
public class WeatherService {
    @Tool(description = "Get current weather")
    public String getWeather(
        @ToolParam(name = "location", description = "City name") String location) {
        return "Sunny, 25°C";
    }
}
```

**Note**: `@ToolParam` needs explicit `name` - Java doesn't preserve parameter names at runtime.

## Agent

Agent interface defines the core contract:

```java
public interface Agent {
    String getAgentId();
    String getName();
    Mono<Msg> call(Msg msg);
    // ... other methods
}
```

Core methods:
- **call()**: Process message and generate response
- **stream()**: Stream response in real-time
- **interrupt()**: Stop execution

### ReActAgent

Main implementation using ReAct algorithm (Reasoning + Acting):

```java
ReActAgent agent = ReActAgent.builder()
    .name("Assistant")
    .model(DashScopeChatModel.builder()
        .apiKey(System.getenv("DASHSCOPE_API_KEY"))
        .modelName("qwen-plus")
        .build())
    .sysPrompt("You are a helpful assistant.")
    .build();
```

## Formatter

Converts messages to LLM-specific API formats. Handles prompt engineering, validation, and multi-agent formatting.

Providers:
- **DashScopeFormatter**: For Alibaba Cloud DashScope
- **OpenAIFormatter**: For OpenAI-compatible APIs

Automatically selected based on model - no manual configuration needed.

## Hook

Extension points for customizing agent behavior. All hooks implement `onEvent()` with pattern matching.

Event types:
- **Modifiable**: PreReasoningEvent, PostReasoningEvent, PreActingEvent, PostActingEvent, PostCallEvent
- **Notification-only**: PreCallEvent, ReasoningChunkEvent, ActingChunkEvent, ErrorEvent

Example:

```java
Hook myHook = new Hook() {
    @Override
    public <T extends HookEvent> Mono<T> onEvent(T event) {
        return switch (event) {
            case PreReasoningEvent e -> {
                System.out.println("Reasoning: " + e.getModelName());
                yield Mono.just(e);
            }
            case ReasoningChunkEvent e -> {
                System.out.print(e.getChunk().getTextContent());
                yield Mono.just(e);
            }
            default -> Mono.just(event);
        };
    }
};
```

Priority: Lower value = higher priority (default 100).

## Memory

Manages conversation history. ReActAgent automatically stores all exchanged messages.

- **InMemoryMemory**: Simple in-memory history
- Custom implementations available for advanced needs

## Reactive Programming

Built on Project Reactor using `Mono<T>` (0-1 item) and `Flux<T>` (0-N items).

Benefits: Non-blocking I/O, efficient resources, natural streaming support, composable pipelines.

```java
// Non-blocking
Mono<Msg> responseMono = agent.call(msg);

// Block when needed
Msg response = responseMono.block();

// Or async
responseMono.subscribe(response ->
    System.out.println(response.getTextContent())
);
```

## Builder Pattern

Used throughout for type-safe, readable configuration:

```java
ReActAgent agent = ReActAgent.builder()
    .name("Assistant")
    .model(model)
    .sysPrompt("You are a helpful assistant.")
    .tools(List.of(weatherService))
    .maxIterations(10)
    .build();
```

## State Management

Separates initialization from state, allowing restore to different states:

- **saveState()**: Save to JSON-serializable map
- **loadState()**: Restore from saved state

Supports conversation persistence, checkpointing, and state migration.

## Session

Persistent storage for components across runs. Manages multiple components together.

```java
// Save
SessionManager.forSessionId("user123")
    .withSession(new JsonSession(Path.of("sessions")))
    .addComponent(agent)
    .addComponent(memory)
    .saveSession();

// Load
SessionManager.forSessionId("user123")
    .withSession(new JsonSession(Path.of("sessions")))
    .addComponent(agent)
    .addComponent(memory)
    .loadIfExists();
```

**JsonSession**: Default implementation using JSON files (`~/.agentscope/sessions/`).

## Pipeline

Composition patterns for multi-agent workflows:

- **SequentialPipeline**: Execute agents in sequence
- **FanoutPipeline**: Execute agents in parallel

```java
Pipeline pipeline = Pipelines.sequential(agent1, agent2, agent3);
Msg result = pipeline.call(inputMsg).block();
```

## Next Steps

- [Build Your First Agent](agent.md) - Create a working agent
- [Explore Tools](../task/tool.md) - Add tools to your agent
- [Use Hooks](../task/hook.md) - Customize agent behavior

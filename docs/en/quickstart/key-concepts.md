# Key Concepts

This chapter introduces key concepts from an engineering perspective in AgentScope Java.

> **Note**: The goal of introducing key concepts is to clarify what practical problems AgentScope addresses and how it supports developers, rather than to offer formal definitions.

## Message

In AgentScope, **Message** is the fundamental data structure, used to:

- Exchange information between agents
- Display information in the user interface
- Store information in memory
- Act as a unified medium between AgentScope and different LLM APIs

A message consists of four fields:

- **name**: The name/identity of the message sender
- **role**: The role of the sender (`USER`, `ASSISTANT`, `SYSTEM`, or `TOOL`)
- **content**: A list of content blocks (text, images, audio, video, tool calls, etc.)
- **metadata**: Optional metadata for structured output or additional information

### Content Blocks

AgentScope supports multimodal content through various block types:

- **TextBlock**: Plain text content
- **ImageBlock**: Image data (URL or Base64)
- **AudioBlock**: Audio data (URL or Base64)
- **VideoBlock**: Video data (URL or Base64)
- **ThinkingBlock**: Reasoning content for reasoning models
- **ToolUseBlock**: Tool call requests
- **ToolResultBlock**: Tool execution results

Example:

```java
import io.agentscope.core.message.Msg;
import io.agentscope.core.message.MsgRole;
import io.agentscope.core.message.TextBlock;

Msg msg = Msg.builder()
    .name("Alice")
    .role(MsgRole.USER)
    .content(List.of(TextBlock.builder().text("Hello, AgentScope!").build()))
    .build();
```

## Tool

A **Tool** in AgentScope refers to any Java method annotated with `@Tool`, whether it is:

- Instance method
- Static method
- Synchronous or asynchronous
- Streaming or non-streaming

Tools are registered using annotation-based discovery:

```java
import io.agentscope.core.tool.Tool;
import io.agentscope.core.tool.ToolParam;

public class WeatherService {

    @Tool(description = "Get current weather for a location")
    public String getWeather(
            @ToolParam(name = "location", description = "City name") String location) {
        // Implementation
        return "Sunny, 25°C";
    }
}
```

**Important**: The `@ToolParam` annotation requires an explicit `name` attribute because Java does not preserve parameter names at runtime by default.

## Agent

In AgentScope, the **Agent** interface defines the core contract for all agents:

```java
public interface Agent {
    String getAgentId();
    String getName();
    Mono<Msg> call(Msg msg);
    Mono<Msg> call(List<Msg> msgs);
    Mono<Msg> call();
    // ... other methods
}
```

### Core Methods

- **call()**: Process input message(s) and generate a response
- **stream()**: Stream response with real-time updates
- **interrupt()**: Interrupt the agent's execution

### ReActAgent

The most important agent implementation is **ReActAgent** (`io.agentscope.core.ReActAgent`), which uses the ReAct (Reasoning + Acting) algorithm:

- **reasoning()**: Think and generate tool calls by calling the LLM
- **acting()**: Execute the tool functions and collect results

Example:

```java
import io.agentscope.core.ReActAgent;
import io.agentscope.core.model.DashScopeChatModel;

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

**Formatter** is the core component for LLM compatibility in AgentScope, responsible for:

- Converting message objects into the required format for LLM APIs
- Handling prompt engineering
- Message validation and truncation
- Multi-agent (multi-identity) message formatting

AgentScope provides formatters for different LLM providers:

- **DashScopeFormatter**: For Alibaba Cloud DashScope models
- **OpenAIFormatter**: For OpenAI-compatible APIs

Formatters are automatically selected based on the model you choose. You don't need to specify them explicitly.

## Hook

**Hooks** are extension points that allow you to customize agent behavior at specific execution stages. All hooks implement a single `onEvent()` method and use pattern matching to handle specific event types.

### Event Types

AgentScope provides these event types:

**Modifiable Events** (can be changed to affect execution):
- **PreReasoningEvent**: Before LLM reasoning
- **PostReasoningEvent**: After reasoning completes
- **PreActingEvent**: Before tool execution
- **PostActingEvent**: After tool execution
- **PostCallEvent**: After agent call completes

**Notification Events** (read-only):
- **PreCallEvent**: When agent call starts
- **ReasoningChunkEvent**: During reasoning streaming
- **ActingChunkEvent**: During tool execution streaming
- **ErrorEvent**: When errors occur

### Creating Hooks

Hooks are created by implementing the `Hook` interface and using pattern matching:

```java
import io.agentscope.core.hook.Hook;
import io.agentscope.core.hook.HookEvent;
import io.agentscope.core.hook.PreReasoningEvent;
import io.agentscope.core.hook.ReasoningChunkEvent;

Hook myHook = new Hook() {
    @Override
    public <T extends HookEvent> Mono<T> onEvent(T event) {
        return switch (event) {
            case PreReasoningEvent e -> {
                System.out.println("Reasoning with: " + e.getModelName());
                yield Mono.just(e);
            }
            case ReasoningChunkEvent e -> {
                // Display streaming output
                System.out.print(e.getChunk().getTextContent());
                yield Mono.just(e);
            }
            default -> Mono.just(event);
        };
    }
};

ReActAgent agent = ReActAgent.builder()
    .name("Assistant")
    .model(model)
    .hooks(List.of(myHook))
    .build();
```

### Hook Priority

Hooks execute in priority order (lower value = higher priority). Default is 100:

```java
Hook highPriorityHook = new Hook() {
    @Override
    public int priority() {
        return 10;  // Executes before default hooks
    }

    @Override
    public <T extends HookEvent> Mono<T> onEvent(T event) {
        // Handle events...
        return Mono.just(event);
    }
};
```

## Memory

**Memory** manages the conversation history for agents. AgentScope provides:

- **InMemoryMemory**: Simple in-memory conversation history
- Custom memory implementations for advanced scenarios

Memory is automatically managed by agents like ReActAgent, storing all messages exchanged during conversations.

## Reactive Programming

AgentScope Java is built on **Project Reactor**, using reactive types for asynchronous operations:

- **Mono<T>**: A publisher that emits 0 or 1 item
- **Flux<T>**: A publisher that emits 0 to N items

This design enables:

- Non-blocking I/O operations
- Efficient resource utilization
- Natural support for streaming responses
- Composable asynchronous pipelines

Example:

```java
// Non-blocking agent call
Mono<Msg> responseMono = agent.call(msg);

// Block to get result (for testing or simple cases)
Msg response = responseMono.block();

// Or handle asynchronously
responseMono.subscribe(response -> {
    System.out.println(response.getTextContent());
});
```

## Builder Pattern

AgentScope extensively uses the **Builder Pattern** for object construction, providing:

- Type-safe configuration
- Readable and maintainable code
- Optional parameters with defaults
- Immutable objects after construction

Example:

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

AgentScope separates object initialization from state management, allowing objects to be restored to different states:

- **saveState()**: Save current state to a JSON-serializable map
- **loadState()**: Restore object from saved state

This supports:

- Conversation persistence
- Agent checkpointing
- State migration between environments

## Session

**Session** provides persistent storage for stateful components across application runs. It allows you to:

- Save and restore agent states, memories, and other components
- Resume conversations from where they left off
- Migrate application state between environments

Session builds on top of State Management, providing a higher-level API for managing multiple components together.

### SessionManager

The **SessionManager** provides a fluent API for session operations:

- **forSessionId()**: Create a manager for a specific session ID
- **withJsonSession()**: Configure JSON file storage (default implementation)
- **addComponent()**: Add StateModule components to manage
- **saveSession()**: Save current state of all components
- **loadIfExists()**: Load state if session exists
- **sessionExists()**: Check if a session is stored

Example:

```java
import io.agentscope.core.session.SessionManager;
import java.nio.file.Path;

// Save session
SessionManager.forSessionId("user123")
    .withJsonSession(Path.of("sessions"))
    .addComponent(agent)
    .addComponent(memory)
    .saveSession();

// Load session
SessionManager.forSessionId("user123")
    .withJsonSession(Path.of("sessions"))
    .addComponent(agent)
    .addComponent(memory)
    .loadIfExists();
```

### Multi-Component Sessions

Sessions can manage multiple components simultaneously, preserving relationships between agents, memories, and other stateful objects:

```java
ReActAgent agent = ReActAgent.builder()
    .name("Assistant")
    .model(model)
    .build();

InMemoryMemory memory = new InMemoryMemory();

// Both agent and memory are saved together
SessionManager.forSessionId("conversation-001")
    .withJsonSession(Path.of("./sessions"))
    .addComponent(agent)
    .addComponent(memory)
    .saveSession();
```

### JsonSession

**JsonSession** is the default session implementation, storing state as JSON files on the filesystem:

- Default storage: `~/.agentscope/sessions/`
- Each session is a single JSON file named by session ID
- Automatic directory creation
- UTF-8 encoding with pretty printing

You can also implement custom session backends by extending `SessionBase` (e.g., database storage, cloud storage).

## Pipeline

**Pipeline** provides composition patterns for multi-agent workflows:

- **SequentialPipeline**: Agents execute in sequence
- **FanoutPipeline**: Multiple agents process in parallel

Example:

```java
import io.agentscope.core.pipeline.Pipeline;
import io.agentscope.core.pipeline.Pipelines;

Pipeline pipeline = Pipelines.sequential(agent1, agent2, agent3);
Msg result = pipeline.call(inputMsg).block();
```

## Next Steps

Now that you understand the key concepts, you can:

- [Learn about Messages](message.md) - Deep dive into message construction
- [Build Your First Agent](agent.md) - Create a working agent
- [Explore Tools](../task/tool.md) - Add tools to your agent
- [Use Hooks](../task/hook.md) - Customize agent behavior

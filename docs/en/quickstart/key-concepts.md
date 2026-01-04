# Key Concepts

This chapter introduces the core concepts in AgentScope from an engineering perspective to help you understand the framework's design philosophy.

> **Note**: The goal of this document is to clarify the problems AgentScope solves in engineering practice and the help it provides to developers, rather than providing rigorous academic definitions.

## Core Flow

Before diving into each concept, let's understand how an agent works. The core of AgentScope is the **ReAct Loop** (Reasoning + Acting):

```
                            User Input (Message)
                                   |
                                   v
+-----------------------------------------------------------------------+
|                           ReActAgent                                  |
|                                                                       |
|   +---------------------------+     +---------------------------+     |
|   |         Memory            |     |          Toolkit          |     |
|   |  (stores conversation     |     |  (manages callable tool   |     |
|   |   history and context)    |     |   functions)              |     |
|   +---------------------------+     +---------------------------+     |
|               |                                   |                   |
|               v                                   |                   |
|   +-----------------------------------------------+---------------+   |
|   |                     1. Reasoning                              |   |
|   |   +-------------+     +-----------+     +---------------+     |   |
|   |   |   Memory    | --> | Formatter | --> |     Model     |     |   |
|   |   | (read hist) |     | (convert) |     |  (call LLM)   |     |   |
|   |   +-------------+     +-----------+     +---------------+     |   |
|   +---------------------------------------------------------------+   |
|                                   |                                   |
|                                   v                                   |
|                            Need tool call?                            |
|                             /        \                                |
|                          Yes          No                              |
|                           /            \                              |
|                          v              v                             |
|   +---------------------------+    +---------------------------+      |
|   |       2. Acting           |    |    Return final response  |      |
|   |   +---------+             |    +---------------------------+      |
|   |   | Toolkit | (exec tool) |                 |                     |
|   |   +---------+             |                 |                     |
|   |        |                  |                 |                     |
|   |        v                  |                 |                     |
|   |   Store result in Memory  |                 |                     |
|   |        |                  |                 |                     |
|   |        v                  |                 |                     |
|   |   Back to step 1          |                 |                     |
|   +---------------------------+                 |                     |
|                                                 |                     |
+-----------------------------------------------------------------------+
                                                  |
                                                  v
                                        Agent Response (Message)
```

Now let's explore each concept in detail.

---

## Message

**Problem solved**: Agents need a unified data structure to carry various types of information—text, images, tool calls, etc.

Message is the most fundamental data structure in AgentScope, used for:
- Exchanging information between agents
- Storing conversation history in memory
- Serving as a unified medium for LLM API interactions

**Core fields**:

| Field | Description |
|-------|-------------|
| `name` | Sender's name, used to distinguish identities in multi-agent scenarios |
| `role` | Role: `USER`, `ASSISTANT`, `SYSTEM`, or `TOOL` |
| `content` | List of content blocks, supports multiple types |
| `metadata` | Optional structured data |

**Content types**:

- `TextBlock` - Plain text
- `ImageBlock` / `AudioBlock` / `VideoBlock` - Multimodal content
- `ThinkingBlock` - Reasoning traces (for reasoning models)
- `ToolUseBlock` - Tool invocation initiated by LLM
- `ToolResultBlock` - Tool execution result

**Response Metadata**:

Messages returned by Agent contain additional metadata to help understand execution state:

| Method | Description |
|--------|-------------|
| `getGenerateReason()` | Reason for message generation, used to determine next actions |
| `getChatUsage()` | Token usage statistics (input/output tokens, time) |

**GenerateReason Values**:

| Value | Description |
|-------|-------------|
| `MODEL_STOP` | Task completed normally |
| `TOOL_SUSPENDED` | Tool needs external execution, waiting for result |
| `REASONING_STOP_REQUESTED` | Paused by Hook during Reasoning phase (HITL) |
| `ACTING_STOP_REQUESTED` | Paused by Hook during Acting phase (HITL) |
| `INTERRUPTED` | Agent was interrupted |
| `MAX_ITERATIONS` | Maximum iterations reached |

**Example**:

```java
// Create a text message
Msg msg = Msg.builder()
    .name("user")
    .textContent("What's the weather like in Beijing today?")
    .build();

// Create a multimodal message
Msg imgMsg = Msg.builder()
    .name("user")
    .content(List.of(
        TextBlock.builder().text("What is in this image?").build(),
        ImageBlock.builder().source(new URLSource("https://example.com/photo.jpg")).build()
    ))
    .build();
```

---

## Agent

**Problem solved**: Need a unified abstraction to encapsulate the logic of "receive message → process → return response".

The Agent interface defines the core contract:

```java
public interface Agent {
    Mono<Msg> call(Msg msg);      // Process message and return response
    Flux<Msg> stream(Msg msg);    // Stream response in real-time
    void interrupt();             // Stop execution
}
```

### Stateful Design

Agents in AgentScope are **stateful objects**. Each Agent instance holds its own:
- **Memory**: Conversation history
- **Toolkit**: Tool collection and their state
- **Configuration**: System prompt, model settings, etc.

> **Important**: Since both Agent and Toolkit are stateful, **the same instance cannot be called concurrently**. If you need to handle multiple concurrent requests, create independent Agent instances for each request or use an object pool.

```java
// ❌ Wrong: sharing the same agent instance across threads
ReActAgent agent = ReActAgent.builder()...build();
executor.submit(() -> agent.call(msg1));  // Concurrency issue!
executor.submit(() -> agent.call(msg2));  // Concurrency issue!

// ✅ Correct: use independent agent instance for each request
executor.submit(() -> {
    ReActAgent agent = ReActAgent.builder()...build();
    agent.call(msg1);
});
```

### ReActAgent

`ReActAgent` is the main implementation provided by the framework, using the ReAct algorithm (Reasoning + Acting loop):

```java
ReActAgent agent = ReActAgent.builder()
    .name("Assistant")
    .model(DashScopeChatModel.builder()
        .apiKey(System.getenv("DASHSCOPE_API_KEY"))
        .modelName("qwen3-max")
        .build())
    .sysPrompt("You are a helpful assistant.")
    .toolkit(toolkit)  // Optional: add tools
    .build();

// Call the agent
Msg response = agent.call(userMsg).block();
```

> For detailed configuration, see [Creating a ReAct Agent](agent.md).

---

## Tool

**Problem solved**: LLMs can only generate text and cannot perform actual operations. Tools enable agents to query databases, call APIs, perform calculations, etc.

In AgentScope, a "tool" is a Java method annotated with `@Tool`, supporting:
- Instance methods, static methods, class methods
- Synchronous or asynchronous calls
- Streaming or non-streaming returns

**Example**:

```java
public class WeatherService {
    @Tool(name = "get_weather", description = "Get weather for a specified city")
    public String getWeather(
            @ToolParam(name = "city", description = "City name") String city) {
        // Call weather API
        return "Beijing: Sunny, 25°C";
    }
}

// Register tools
Toolkit toolkit = new Toolkit();
toolkit.registerTool(new WeatherService());
```

> **Important**: `@ToolParam` must explicitly specify the `name` attribute because Java does not preserve method parameter names at runtime.

---

## Memory

**Problem solved**: Agents need to remember conversation history to have contextual conversations.

Memory manages conversation history. `ReActAgent` automatically:
- Adds user messages to memory
- Adds tool calls and results to memory
- Adds agent responses to memory
- Reads memory as context during reasoning

Uses `InMemoryMemory` (in-memory storage) by default. For cross-session persistence, see [State Management](../task/state.md).

---

## Formatter

**Problem solved**: Different LLM providers have different API formats, requiring an adapter layer to abstract away differences.

Formatter is responsible for converting AgentScope messages to the format required by specific LLM APIs, including:
- Prompt engineering (adding system prompts, formatting multi-turn conversations)
- Message validation
- Identity handling in multi-agent scenarios

**Built-in implementations**:
- `DashScopeFormatter` - Alibaba Cloud DashScope (Qwen series)
- `OpenAIFormatter` - OpenAI and compatible APIs

> Formatter is automatically selected based on Model type; manual configuration is usually not needed.

---

## Hook

**Problem solved**: Need to insert custom logic at various stages of agent execution, such as logging, monitoring, message modification, etc.

Hook provides extension points at key nodes of the ReAct loop through an event mechanism:

| Event Type | Trigger Point | Modifiable |
|------------|---------------|------------|
| `PreCallEvent` | Before agent starts processing | ✓ |
| `PostCallEvent` | After agent completes processing | ✓ |
| `PreReasoningEvent` | Before calling LLM | ✓ |
| `PostReasoningEvent` | After LLM returns | ✓ |
| `ReasoningChunkEvent` | During LLM streaming output | - |
| `PreActingEvent` | Before executing tool | ✓ |
| `PostActingEvent` | After tool execution | ✓ |
| `ActingChunkEvent` | During tool streaming output | - |
| `ErrorEvent` | When error occurs | - |

**Hook Priority**: Hooks execute in priority order (lower value = higher priority), default is 100.

**Example**:

```java
Hook loggingHook = new Hook() {
    @Override
    public <T extends HookEvent> Mono<T> onEvent(T event) {
        return switch (event) {
            case PreCallEvent e -> {
                System.out.println("Agent starting...");
                yield Mono.just(event);
            }
            case ReasoningChunkEvent e -> {
                System.out.print(e.getIncrementalChunk().getTextContent());  // Print streaming output
                yield Mono.just(event);
            }
            case PostCallEvent e -> {
                System.out.println("Completed: " + e.getFinalMessage().getTextContent());
                yield Mono.just(event);
            }
            default -> Mono.just(event);
        };
    }

    @Override
    public int priority() {
        return 50;  // High priority
    }
};

ReActAgent agent = ReActAgent.builder()
    // ... other configurations
    .hook(loggingHook)
    .build();
```

> For detailed usage, see [Hook System](../task/hook.md).

---

## State Management and Session

**Problem solved**: Agent state such as conversation history and configuration needs to be saved and restored to support session persistence.

AgentScope separates "initialization" from "state":
- `saveState()` - Export current state as a serializable Map
- `loadState()` - Restore from saved state

**Session** provides persistent storage across runs:

```java
// Save session
SessionManager.forSessionId("user123")
    .withSession(new JsonSession(Path.of("sessions")))
    .addComponent(agent)
    .saveSession();

// Restore session
SessionManager.forSessionId("user123")
    .withSession(new JsonSession(Path.of("sessions")))
    .addComponent(agent)
    .loadIfExists();
```

---

## Reactive Programming

**Problem solved**: LLM calls and tool execution typically involve I/O operations; synchronous blocking wastes resources.

AgentScope is built on [Project Reactor](https://projectreactor.io/), using:
- `Mono<T>` - Returns 0 or 1 result
- `Flux<T>` - Returns 0 to N results (for streaming)

```java
// Non-blocking call
Mono<Msg> responseMono = agent.call(msg);

// Block when result is needed
Msg response = responseMono.block();

// Or handle asynchronously
responseMono.subscribe(response ->
    System.out.println(response.getTextContent())
);
```

---

## Next Steps

- [Creating a ReAct Agent](agent.md) - Complete agent creation tutorial
- [Tool System](../task/tool.md) - Learn advanced tool usage
- [Hook System](../task/hook.md) - Customize agent behavior
- [Model Integration](../task/model.md) - Integrate different LLM providers

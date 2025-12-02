# Agent

ReActAgent is the main agent implementation using the ReAct (Reasoning + Acting) algorithm.

## How to Use ReActAgent

Using ReActAgent involves three core steps:

**1. Build** - Configure the agent with model, tools, and memory:

```java
ReActAgent agent = ReActAgent.builder()
    .name("Assistant")
    .sysPrompt("You are a helpful assistant.")
    .model(model)                    // LLM for reasoning
    .toolkit(toolkit)                // Tools the agent can use
    .memory(memory)                  // Conversation history
    .build();
```

**2. Call** - Send messages and get responses:

```java
Msg response = agent.call(inputMsg).block();
```

**3. Manage State** - Use Session to persist conversation across requests:

```java
// Save state
SessionManager.forSessionId(userId)
    .withSession(new JsonSession(path))
    .addComponent(agent)
    .saveSession();

// Load state
SessionManager.forSessionId(userId)
    .withSession(new JsonSession(path))
    .addComponent(agent)
    .loadIfExists();
```

**Recommended Pattern** (for web applications):

Create fresh agent instances per request and use Session for state persistence. This ensures thread-safety while maintaining conversation continuity:

```java
public Msg handleRequest(String userId, Msg inputMsg) {
    // 1. Build fresh instances
    Toolkit toolkit = new Toolkit();
    toolkit.registerTool(new WeatherService());

    Memory memory = new InMemoryMemory();
    ReActAgent agent = ReActAgent.builder()
        .name("Assistant")
        .model(model)
        .toolkit(toolkit)
        .memory(memory)
        .build();

    // 2. Load previous state
    SessionManager.forSessionId(userId)
        .withSession(new JsonSession(Path.of("sessions")))
        .addComponent(agent)
        .addComponent(memory)
        .addComponent(toolkit)
        .loadIfExists();

    // 3. Process request
    Msg response = agent.call(inputMsg).block();

    // 4. Save state
    SessionManager.forSessionId(userId)
        .withSession(new JsonSession(Path.of("sessions")))
        .addComponent(agent)
        .addComponent(memory)
        .addComponent(toolkit)
        .saveSession();

    return response;
}
```

Now let's explore each aspect in detail.

---

## Basic Usage

Minimal example:

```java
ReActAgent agent = ReActAgent.builder()
    .name("Assistant")
    .sysPrompt("You are a helpful assistant.")
    .model(DashScopeChatModel.builder()
        .apiKey(System.getenv("DASHSCOPE_API_KEY"))
        .modelName("qwen-plus")
        .build())
    .build();

Msg response = agent.call(inputMsg).block();
```

## Builder Parameters

Required:
- **name**: Agent identifier
- **sysPrompt**: System prompt
- **model**: LLM model for reasoning

Optional:
- **toolkit**: Tools available to agent (default: empty)
- **memory**: Conversation history storage (default: InMemoryMemory)
- **maxIters**: Max reasoning-acting iterations (default: 10)
- **hooks**: Event hooks for customization (default: empty)
- **modelExecutionConfig**: Timeout/retry for model calls
- **toolExecutionConfig**: Timeout/retry for tool calls

## Core Methods

### call()

Process messages and generate response:

```java
// Single message
Mono<Msg> response = agent.call(inputMsg);

// Multiple messages
Mono<Msg> response = agent.call(List.of(msg1, msg2));

// Continue from current state
Mono<Msg> response = agent.call();
```

### stream()

Get real-time streaming updates:

```java
Flux<Event> eventStream = agent.stream(inputMsg);

eventStream.subscribe(event -> {
    if (event.getEventType() == EventType.TEXT_CHUNK) {
        System.out.print(event.getChunk().getText());
    }
});
```

## Adding Tools

```java
public class WeatherService {
    @Tool(description = "Get weather")
    public String getWeather(
        @ToolParam(name = "location", description = "City") String location) {
        return "Sunny, 25°C in " + location;
    }
}

Toolkit toolkit = new Toolkit();
toolkit.registerTool(new WeatherService());

ReActAgent agent = ReActAgent.builder()
    .name("Assistant")
    .sysPrompt("Use tools to answer questions.")
    .model(model)
    .toolkit(toolkit)
    .build();
```

## Structured Output

Request structured data from agent:

```java
public class TaskPlan {
    public String goal;
    public List<String> steps;
}

Mono<Msg> response = agent.call(inputMsg, TaskPlan.class);

Msg result = response.block();
if (result.hasStructuredData()) {
    TaskPlan plan = result.getStructuredData(TaskPlan.class);
}
```

## Memory Management

Memory stores conversation history automatically:

```java
Memory memory = new InMemoryMemory();

ReActAgent agent = ReActAgent.builder()
    .name("Assistant")
    .model(model)
    .memory(memory)
    .build();

// Memory auto-stores all messages
agent.call(msg1).block();
agent.call(msg2).block();

// Access history
List<Msg> history = memory.getAllMessages();
```

## Concurrency

> **Important**: Agent objects are **not thread-safe**. Do not call the same agent instance concurrently from multiple threads.

For concurrent execution:
- Create separate agent instances per thread
- Use external synchronization
- Process requests sequentially

```java
// ❌ Wrong - concurrent calls on same agent
Flux.merge(
    agent.call(msg1),
    agent.call(msg2),
    agent.call(msg3)
).subscribe();

// ✅ Correct - separate agents
ReActAgent agent1 = ReActAgent.builder()...build();
ReActAgent agent2 = ReActAgent.builder()...build();
ReActAgent agent3 = ReActAgent.builder()...build();

Flux.merge(
    agent1.call(msg1),
    agent2.call(msg2),
    agent3.call(msg3)
).subscribe();

// ✅ Correct - sequential execution
agent.call(msg1)
    .flatMap(r1 -> agent.call(msg2))
    .flatMap(r2 -> agent.call(msg3))
    .subscribe();
```

## Complete Example

```java
public class Calculator {
    @Tool(description = "Add numbers")
    public int add(@ToolParam(name = "a") int a, @ToolParam(name = "b") int b) {
        return a + b;
    }
}

Toolkit toolkit = new Toolkit();
toolkit.registerTool(new Calculator());

ReActAgent agent = ReActAgent.builder()
    .name("MathAssistant")
    .sysPrompt("You are a math assistant. Use calculator tools.")
    .model(DashScopeChatModel.builder()
        .apiKey(System.getenv("DASHSCOPE_API_KEY"))
        .modelName("qwen-plus")
        .build())
    .toolkit(toolkit)
    .memory(new InMemoryMemory())
    .maxIters(5)
    .build();

Msg question = Msg.builder()
    .textContent("What is (15 + 7) * 3?")
    .build();

Msg response = agent.call(question).block();
System.out.println("Answer: " + response.getTextContent());
```

## Next Steps

- [Tool System](../task/tool.md) - Learn about tools in detail
- [Hook System](../task/hook.md) - Customize agent behavior
- [Pipeline](../task/pipeline.md) - Compose multiple agents

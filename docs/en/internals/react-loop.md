# ReAct Loop Internals

This document provides an in-depth look at the internal execution flow of ReActAgent, helping you understand the core runtime mechanism of the framework.

## Execution Flow Overview

The core of ReActAgent is the **ReAct Loop** (Reasoning + Acting). Each call to `call()` or `stream()` executes the following flow:

```
                                User Input
                                    │
                                    ▼
                           ┌────────────────┐
                           │   PreCallEvent │ (notification)
                           └────────────────┘
                                    │
         ┌──────────────────────────┼──────────────────────────┐
         │                          ▼                          │
         │                 ┌────────────────┐                  │
         │                 │ Check Interrupt │                  │
         │                 └────────────────┘                  │
         │                          │                          │
         │                          ▼                          │
         │                ┌─────────────────┐                  │
         │                │ PreReasoningEvent│ (modifiable)    │
         │                └─────────────────┘                  │
         │                          │                          │
         │                          ▼                          │
         │                ┌─────────────────┐                  │
         │                │   Call LLM      │ ← ReasoningChunkEvent (streaming)
         │                └─────────────────┘                  │
         │                          │                          │
         │                          ▼                          │
         │               ┌──────────────────┐                  │
         │               │PostReasoningEvent│ (modifiable)     │
         │               └──────────────────┘                  │
         │                          │                          │
         │                          ▼                          │
         │                  ┌──────────────┐                   │
         │                  │ Need tool call? │                │
         │                  └──────────────┘                   │
         │                    │          │                     │
         │                  Yes          No                    │
         │                    │          │                     │
         │                    ▼          └──────────┐          │
         │           ┌────────────────┐            │          │
         │           │ PreActingEvent │ (modifiable)│          │
         │           └────────────────┘            │          │
         │                    │                    │          │
         │                    ▼                    │          │
         │           ┌────────────────┐            │          │
         │           │  Execute Tool  │ ← ActingChunkEvent     │
         │           └────────────────┘            │          │
         │                    │                    │          │
         │                    ▼                    │          │
         │           ┌─────────────────┐           │          │
         │           │ PostActingEvent │ (modifiable)         │
         │           └─────────────────┘           │          │
         │                    │                    │          │
         │                    ▼                    │          │
         │           ┌────────────────┐            │          │
         │           │ Store in Memory │           │          │
         │           └────────────────┘            │          │
         │                    │                    │          │
         │                    ▼                    │          │
         │            ┌──────────────┐             │          │
         │            │Check Iteration│            │          │
         │            └──────────────┘             │          │
         │                    │                    │          │
         │        Not exceeded ─┴─ Exceeded        │          │
         │             │            │              │          │
         │      ┌──────┘            ▼              │          │
         │      │          Return Final Response ◀─┘          │
         │      │                   │                        │
         └──────┼───────────────────┼────────────────────────┘
                │                   │
                └───────┬───────────┘
                        ▼
               ┌────────────────┐
               │ PostCallEvent  │ (modifiable)
               └────────────────┘
                        │
                        ▼
                  Return Response
```

## Reasoning Phase

The reasoning phase is responsible for calling the LLM to generate the next action decision.

### Message Assembly

Before calling the LLM, the framework assembles messages in the following order:

```java
// Pseudocode showing message assembly logic
List<Msg> messages = new ArrayList<>();

// 1. System prompt (including tool Schema)
messages.add(buildSystemMessage());

// 2. Long-term memory recall (if STATIC_CONTROL mode is enabled)
if (longTermMemoryMode.includesStatic()) {
    String memories = longTermMemory.retrieve(userMsg).block();
    if (memories != null) {
        messages.add(wrapAsMemoryHint(memories));
    }
}

// 3. Historical messages from short-term memory
messages.addAll(memory.getMessages());

// 4. Current user input
messages.add(userMsg);
```

### Formatter Conversion

Different LLM providers have different API formats. The Formatter is responsible for converting unified `Msg` to specific formats:

| Provider | Formatter | Key Differences |
|----------|-----------|-----------------|
| DashScope | `DashScopeFormatter` | Uses `tool_calls` field, supports `thinking` output |
| OpenAI | `OpenAIFormatter` | Standard OpenAI message format |

### Iteration Termination Conditions

The reasoning loop terminates in the following situations:

1. **LLM did not request tool call** - Returns pure text response
2. **Maximum iterations reached** - Default `maxIters = 10`
3. **Interrupt signal received** - Triggered via `agent.interrupt()`
4. **Unrecoverable error occurred** - Exception thrown

## Acting Phase

When the LLM response contains a `ToolUseBlock`, it enters the acting phase.

### Tool Call Parsing

```java
// Tool call block from LLM response
ToolUseBlock toolUse = extractToolUse(response);
// Contains: name (tool name), id (call ID), input (parameter Map)
```

### Parameter Injection Mechanism

During tool execution, parameters come from three sources (in priority order):

1. **LLM-provided parameters** - From `ToolUseBlock.input`
2. **Preset Parameters** - Hidden parameters configured during registration
3. **ToolExecutionContext** - Objects auto-injected by type

```
LLM params → Preset params → Execution context
     ↓           ↓              ↓
High priority   Medium      Low priority
```

### Parallel Execution

When the LLM returns multiple tool calls, Toolkit can execute them in parallel:

```java
// ToolkitConfig configuration
ToolkitConfig config = ToolkitConfig.builder()
    .parallel(true)  // Enable parallel execution
    .build();
```

Parallel execution uses `Schedulers.boundedElastic()` scheduler to avoid blocking the main thread.

### Tool Result Storage

After execution completes, tool results are stored in Memory as `ToolResultBlock`:

```java
// Tool result message
Msg toolResultMsg = Msg.builder()
    .role(MsgRole.TOOL)
    .content(List.of(
        ToolResultBlock.builder()
            .toolUseId(toolUse.getId())
            .output(result)
            .build()
    ))
    .build();

memory.addMessage(toolResultMsg);
```

## Interruption Mechanism

AgentScope uses **cooperative interruption**, not forced termination.

### Interrupt Checkpoints

Interrupt status is checked at these points:

1. Before each reasoning loop iteration
2. After tool execution, before the next reasoning

### Interrupt Types

```java
// Interrupt without message - immediate stop
agent.interrupt();

// Interrupt with message - return specified message
agent.interrupt(Msg.builder()
    .textContent("Operation cancelled")
    .build());
```

### Design Rationale

Reasons for cooperative rather than forced interruption:

1. **State consistency** - Ensures Memory and Toolkit state integrity
2. **Resource cleanup** - Allows tools to complete cleanup operations
3. **Recoverability** - Can resume from breakpoint after interruption

## Hook Event Lifecycle

Hooks trigger at key nodes in the ReAct loop:

### Event Trigger Order

```
PreCallEvent → [Loop Start]
    → PreReasoningEvent
    → (LLM calling) → ReasoningChunkEvent (multiple)
    → PostReasoningEvent
    → [If tool call needed]
        → PreActingEvent
        → (Tool executing) → ActingChunkEvent (multiple)
        → PostActingEvent
    → [Loop continues or ends]
→ PostCallEvent
```

### Modifiable vs Notification Events

| Event Type | Modifiable | Description |
|------------|------------|-------------|
| PreCallEvent | No | Notification only, agent starting |
| PreReasoningEvent | Yes | Can modify input message list |
| PostReasoningEvent | Yes | Can modify LLM response |
| ReasoningChunkEvent | No | Streaming output notification |
| PreActingEvent | Yes | Can modify tool call parameters |
| PostActingEvent | Yes | Can modify tool execution result |
| ActingChunkEvent | No | Tool progress notification |
| PostCallEvent | Yes | Can modify final response |
| ErrorEvent | No | Error notification |

### Priority Execution

Multiple Hooks execute in priority order (lower value = higher priority):

```java
public class HighPriorityHook implements Hook {
    @Override
    public int priority() {
        return 10;  // Executes before default 100
    }
}
```

## State Management

ReActAgent is a stateful object containing the following serializable state:

### State Composition

```java
// State exported by saveState()
Map<String, Object> state = new HashMap<>();
state.put("memory", memory.saveState());      // Memory state
state.put("toolkit", toolkit.saveState());    // Toolkit state (tool group status, etc.)
state.put("interrupted", interrupted);         // Interrupt flag
```

### Concurrency Limitation

Due to stateful design, **the same Agent instance cannot be called concurrently**:

```java
// Wrong example
ReActAgent agent = ReActAgent.builder()...build();
executor.submit(() -> agent.call(msg1));  // Concurrency issue!
executor.submit(() -> agent.call(msg2));  // Concurrency issue!

// Correct example
executor.submit(() -> {
    ReActAgent agent = ReActAgent.builder()...build();
    agent.call(msg1);
});
```

## Reactive Execution Model

AgentScope is built on Project Reactor, all operations return `Mono` or `Flux`.

### Execution Chain

```java
// Simplified reactive implementation of call()
public Mono<Msg> call(Msg input) {
    return Mono.defer(() -> {
        // Fire PreCallEvent
        return hookManager.firePreCall(input)
            .flatMap(this::runReActLoop)
            .flatMap(hookManager::firePostCall);
    });
}

private Mono<Msg> runReActLoop(Msg input) {
    return Mono.defer(() -> {
        // Check interrupt
        if (interrupted) {
            return Mono.just(interruptMessage);
        }
        // Reasoning
        return reasoning()
            .flatMap(response -> {
                if (needsToolCall(response)) {
                    // Acting + recursion
                    return acting(response)
                        .then(runReActLoop(input));
                }
                return Mono.just(response);
            });
    });
}
```

### Backpressure Handling

Streaming output uses `Flux` with backpressure support:

```java
public Flux<Msg> stream(Msg input) {
    return Flux.create(sink -> {
        // Push incremental messages
        // sink automatically handles backpressure
    });
}
```

## Related Documentation

- [Message System Architecture](./message-system.md)
- [Tool Execution Engine](./tool-execution.md)
- [Hook Event System](./hook-event-system.md)

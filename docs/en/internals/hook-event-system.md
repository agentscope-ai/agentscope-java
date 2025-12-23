# Hook Event System

This document provides an in-depth look at the internal design of the AgentScope Hook system, including event types, execution flow, and extension mechanisms.

## Design Philosophy

The Hook system adopts an **event-driven architecture**, publishing events at key nodes in the ReAct loop to allow external logic to intervene.

### Core Principles

1. **Single Entry Point** - All events handled through `onEvent(HookEvent)`
2. **Type Safe** - Use Java pattern matching to distinguish event types
3. **Reactive** - Return `Mono<T>` to support async processing
4. **Composable** - Multiple Hooks execute in priority chain

## Event Hierarchy

```
HookEvent (interface)
├── PreCallEvent           // Before agent call
├── PostCallEvent          // After agent call
├── PreReasoningEvent      // Before LLM reasoning
├── PostReasoningEvent     // After LLM reasoning
├── ReasoningChunkEvent    // LLM streaming output
├── PreActingEvent         // Before tool execution
├── PostActingEvent        // After tool execution
├── ActingChunkEvent       // Tool streaming output
└── ErrorEvent             // When error occurs
```

## Event Details

### PreCallEvent

Triggered before the agent starts processing user input.

```java
public class PreCallEvent implements HookEvent {
    private final Agent agent;           // Current agent
    private final Msg inputMessage;      // User input message
    // Read-only, cannot modify
}
```

**Trigger Timing**: Immediately after `agent.call(msg)` or `agent.stream(msg)` is called

**Use Cases**:
- Call logging
- Monitoring statistics
- Input validation

### PostCallEvent

Triggered after the agent completes processing.

```java
public class PostCallEvent implements HookEvent {
    private final Agent agent;
    private Msg finalMessage;            // Modifiable final response

    public void setFinalMessage(Msg msg) { this.finalMessage = msg; }
}
```

**Trigger Timing**: After ReAct loop ends, before returning response

**Use Cases**:
- Response post-processing
- Content filtering
- Response enhancement

### PreReasoningEvent

Triggered before calling the LLM.

```java
public class PreReasoningEvent implements HookEvent {
    private final Agent agent;
    private List<Msg> inputMessages;     // Modifiable input message list

    public void setInputMessages(List<Msg> msgs) { this.inputMessages = msgs; }
}
```

**Trigger Timing**: Before each LLM call (including subsequent reasoning after tool calls)

**Use Cases**:
- Inject additional context
- Message preprocessing
- Dynamic prompts

### PostReasoningEvent

Triggered after LLM returns.

```java
public class PostReasoningEvent implements HookEvent {
    private final Agent agent;
    private Msg reasoningResult;         // Modifiable LLM response

    public void setReasoningResult(Msg msg) { this.reasoningResult = msg; }
}
```

**Trigger Timing**: After LLM response completes (in streaming scenarios, after all chunks merged)

**Use Cases**:
- Response post-processing
- Tool call interception
- Content moderation

### ReasoningChunkEvent

Triggered during LLM streaming output.

```java
public class ReasoningChunkEvent implements HookEvent {
    private final Agent agent;
    private final Msg incrementalChunk;  // Incremental content (read-only)
    private final Msg accumulatedMessage; // Accumulated content (read-only)
}
```

**Trigger Timing**: Once for each streaming chunk received

**Use Cases**:
- Real-time output display
- Streaming logs

### PreActingEvent

Triggered before tool execution.

```java
public class PreActingEvent implements HookEvent {
    private final Agent agent;
    private ToolUseBlock toolUse;        // Modifiable tool call

    public void setToolUse(ToolUseBlock toolUse) { this.toolUse = toolUse; }
}
```

**Trigger Timing**: Before each tool execution

**Use Cases**:
- Parameter validation
- Parameter modification
- Permission checking

### PostActingEvent

Triggered after tool execution.

```java
public class PostActingEvent implements HookEvent {
    private final Agent agent;
    private final ToolUseBlock toolUse;  // Original tool call (read-only)
    private ToolResultBlock toolResult;  // Modifiable execution result

    public void setToolResult(ToolResultBlock result) { this.toolResult = result; }
}
```

**Trigger Timing**: After tool execution completes

**Use Cases**:
- Result post-processing
- Result caching
- Audit logging

### ActingChunkEvent

Triggered during tool streaming output.

```java
public class ActingChunkEvent implements HookEvent {
    private final Agent agent;
    private final ToolUseBlock toolUse;
    private final ToolResultBlock chunk;  // Incremental result (read-only)
}
```

**Trigger Timing**: When tool sends progress via `ToolEmitter`

**Use Cases**:
- Progress display
- Streaming logs

### ErrorEvent

Triggered when an error occurs.

```java
public class ErrorEvent implements HookEvent {
    private final Agent agent;
    private final Throwable error;        // Error object (read-only)
    private final ErrorPhase phase;       // Error phase
}

public enum ErrorPhase {
    REASONING,  // LLM call phase
    ACTING      // Tool execution phase
}
```

**Trigger Timing**: When LLM call or tool execution fails

**Use Cases**:
- Error logging
- Monitoring alerts
- Error analysis

## Hook Execution Mechanism

### Priority Ordering

Multiple Hooks execute in ascending priority order (lower value = higher priority):

```java
public interface Hook {
    default int priority() {
        return 100;  // Default priority
    }

    <T extends HookEvent> Mono<T> onEvent(T event);
}
```

**Execution Order**:

```
Hook A (priority: 10)
    ↓
Hook B (priority: 50)
    ↓
Hook C (priority: 100)
    ↓
Hook D (priority: 200)
```

### Chain Processing

Events pass through the Hook chain, each Hook can modify the event:

```java
// HookManager internal implementation (simplified)
public <T extends HookEvent> Mono<T> fireEvent(T event) {
    Mono<T> chain = Mono.just(event);

    for (Hook hook : sortedHooks) {
        chain = chain.flatMap(e -> hook.onEvent(e));
    }

    return chain;
}
```

### Modifiable Event Processing

```java
// PreReasoningEvent modification example
Hook promptEnhancer = new Hook() {
    @Override
    public <T extends HookEvent> Mono<T> onEvent(T event) {
        if (event instanceof PreReasoningEvent e) {
            List<Msg> messages = new ArrayList<>(e.getInputMessages());
            messages.add(0, systemHint);  // Add system hint
            e.setInputMessages(messages);
        }
        return Mono.just(event);
    }
};
```

### Notification Event Processing

```java
// ReasoningChunkEvent processing example
Hook streamPrinter = new Hook() {
    @Override
    public <T extends HookEvent> Mono<T> onEvent(T event) {
        if (event instanceof ReasoningChunkEvent e) {
            System.out.print(e.getIncrementalChunk().getTextContent());
            // Cannot modify, only observe
        }
        return Mono.just(event);
    }
};
```

## Event Flow Diagram

Complete event trigger flow:

```
agent.call(msg)
        │
        ▼
┌───────────────────┐
│   PreCallEvent    │ ← Notification
└───────────────────┘
        │
        ▼
┌───────────────────┐
│ PreReasoningEvent │ ← Can modify inputMessages
└───────────────────┘
        │
        ▼
┌───────────────────┐
│    LLM Call       │ → ReasoningChunkEvent (multiple, notification)
└───────────────────┘
        │
        ▼
┌────────────────────┐
│ PostReasoningEvent │ ← Can modify reasoningResult
└────────────────────┘
        │
        ▼
    Need tool call?
        │
       Yes
        │
        ▼
┌───────────────────┐
│  PreActingEvent   │ ← Can modify toolUse
└───────────────────┘
        │
        ▼
┌───────────────────┐
│  Tool Execution   │ → ActingChunkEvent (multiple, notification)
└───────────────────┘
        │
        ▼
┌───────────────────┐
│  PostActingEvent  │ ← Can modify toolResult
└───────────────────┘
        │
        ▼
    Continue loop or end
        │
        ▼
┌───────────────────┐
│   PostCallEvent   │ ← Can modify finalMessage
└───────────────────┘
        │
        ▼
    Return response
```

## Implementation Patterns

### Pattern Matching Processing

Recommended to use Java pattern matching to handle different events:

```java
public class MultiEventHook implements Hook {
    @Override
    public <T extends HookEvent> Mono<T> onEvent(T event) {
        return switch (event) {
            case PreCallEvent e -> {
                log.info("Starting: {}", e.getInputMessage());
                yield Mono.just(event);
            }
            case PostCallEvent e -> {
                log.info("Completed: {}", e.getFinalMessage());
                yield Mono.just(event);
            }
            case ErrorEvent e -> {
                log.error("Error: {}", e.getError().getMessage());
                yield Mono.just(event);
            }
            default -> Mono.just(event);
        };
    }
}
```

### Async Processing

Hooks support async operations:

```java
public class AsyncHook implements Hook {
    @Override
    public <T extends HookEvent> Mono<T> onEvent(T event) {
        if (event instanceof PostActingEvent e) {
            // Async log to database
            return logToDatabase(e)
                .thenReturn(event);
        }
        return Mono.just(event);
    }

    private Mono<Void> logToDatabase(PostActingEvent event) {
        return Mono.fromRunnable(() -> {
            // Database operation
        }).subscribeOn(Schedulers.boundedElastic()).then();
    }
}
```

### Conditional Interception

```java
public class ConditionalHook implements Hook {
    @Override
    public <T extends HookEvent> Mono<T> onEvent(T event) {
        if (event instanceof PreActingEvent e) {
            String toolName = e.getToolUse().getName();

            // Intercept dangerous tools
            if ("delete_file".equals(toolName)) {
                return Mono.error(new SecurityException("File deletion not allowed"));
            }
        }
        return Mono.just(event);
    }
}
```

## Registration Methods

### Builder Configuration

```java
ReActAgent agent = ReActAgent.builder()
    .name("Assistant")
    .model(model)
    .hook(loggingHook)
    .hook(monitoringHook)
    .hooks(List.of(hook1, hook2))  // Batch add
    .build();
```

### Runtime Immutability

Hook list is immutable after Agent construction, ensuring thread safety.

## Related Documentation

- [ReAct Loop Internals](./react-loop.md)
- [Hook Usage Guide](../task/hook.md)

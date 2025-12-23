# Memory Architecture

This document provides an in-depth look at the internal design of the AgentScope memory system, including the collaboration mechanism between short-term memory, long-term memory, and state management.

## Dual-Layer Memory Architecture

AgentScope adopts a dual-layer memory architecture, separating short-term context from long-term knowledge:

```
┌─────────────────────────────────────────────────────────────────┐
│                         ReActAgent                               │
│  ┌────────────────────────────────────────────────────────────┐ │
│  │                Short-term Memory (Memory)                   │ │
│  │  ┌─────────────────────────────────────────────────────┐   │ │
│  │  │ Current session message list                         │   │ │
│  │  │ [System prompt, User msg, Asst reply, Tool call...]  │   │ │
│  │  └─────────────────────────────────────────────────────┘   │ │
│  │                           ↑ ↓                              │ │
│  │                    Read/Write each turn                    │ │
│  └────────────────────────────────────────────────────────────┘ │
│                              ↕                                  │
│  ┌────────────────────────────────────────────────────────────┐ │
│  │               Long-term Memory (LongTermMemory)             │ │
│  │  ┌─────────────────────────────────────────────────────┐   │ │
│  │  │ External Storage (Mem0 / ReMe / Custom)              │   │ │
│  │  │ - User preferences                                    │   │ │
│  │  │ - Historical knowledge                                │   │ │
│  │  │ - Cross-session information                           │   │ │
│  │  └─────────────────────────────────────────────────────┘   │ │
│  │                     ↑ retrieve    ↓ record                 │ │
│  └────────────────────────────────────────────────────────────┘ │
└─────────────────────────────────────────────────────────────────┘
```

## Short-term Memory (Memory)

### Interface Definition

```java
public interface Memory extends StateModule {
    void addMessage(Msg message);        // Add message
    List<Msg> getMessages();             // Get all messages
    void deleteMessage(int index);       // Delete specific message
    void clear();                        // Clear all messages
}
```

### InMemoryMemory

The simplest implementation, storing messages in an in-memory list:

```java
public class InMemoryMemory implements Memory {
    private final List<Msg> messages = new ArrayList<>();

    @Override
    public void addMessage(Msg message) {
        messages.add(message);
    }

    @Override
    public List<Msg> getMessages() {
        return Collections.unmodifiableList(messages);
    }

    // StateModule implementation
    @Override
    public Map<String, Object> saveState() {
        return Map.of("messages", serializeMessages(messages));
    }

    @Override
    public void loadState(Map<String, Object> state) {
        messages.clear();
        messages.addAll(deserializeMessages(state.get("messages")));
    }
}
```

**Characteristics**:
- Unlimited growth
- Requires Session for persistence
- Suitable for short conversations

### AutoContextMemory

Intelligent context management with automatic compression and summarization:

```java
AutoContextConfig config = AutoContextConfig.builder()
    .msgThreshold(30)      // Message count threshold
    .tokenRatio(0.3)       // Token usage ratio
    .lastKeep(10)          // Keep last N messages
    .build();

AutoContextMemory memory = new AutoContextMemory(config, model);
```

**Compression Strategy**:

```
Original messages (50)
        │
        ▼
┌───────────────────┐
│ Detect trigger    │ msgThreshold / tokenRatio
└───────────────────┘
        │
        ▼
┌───────────────────┐
│ Progressive       │
│ compression       │
│ 1. Merge adjacent │
│ 2. Summarize      │
│ 3. Offload large  │
└───────────────────┘
        │
        ▼
Compressed messages (15)
+ Summary message
+ Offload references
```

### Message Lifecycle

```
User input
    │
    ▼
┌─────────────────────┐
│ memory.addMessage() │ ← User message stored
└─────────────────────┘
    │
    ▼
┌─────────────────────┐
│   LLM reasoning     │ ← Reads memory.getMessages()
└─────────────────────┘
    │
    ▼
┌─────────────────────┐
│ memory.addMessage() │ ← Assistant response stored
└─────────────────────┘
    │
    ▼
If tool call needed
    │
    ▼
┌─────────────────────┐
│ memory.addMessage() │ ← Tool result stored
└─────────────────────┘
    │
    ▼
Continue reasoning...
```

## Long-term Memory (LongTermMemory)

### Interface Definition

```java
public interface LongTermMemory {
    // Record messages to long-term memory
    Mono<Void> record(List<Msg> msgs);

    // Retrieve relevant memories
    Mono<String> retrieve(Msg msg);
}
```

### Working Modes

Control long-term memory behavior via `LongTermMemoryMode`:

| Mode | Description |
|------|-------------|
| `STATIC_CONTROL` | Framework auto-manages: retrieve before reasoning, record after reply |
| `AGENT_CONTROL` | Agent self-manages: via tool calls |
| `BOTH` | Both modes enabled simultaneously |

### STATIC_CONTROL Mode

Framework automatically integrates long-term memory in the reasoning loop:

```
User input
    │
    ▼
┌──────────────────────┐
│ longTermMemory       │
│   .retrieve(msg)     │ ← Retrieve relevant memories
└──────────────────────┘
    │
    ▼
┌──────────────────────┐
│ Inject into prompt   │
│ <long_term_memory>   │
│   Relevant memories  │
│ </long_term_memory>  │
└──────────────────────┘
    │
    ▼
┌──────────────────────┐
│   LLM reasoning      │
└──────────────────────┘
    │
    ▼
┌──────────────────────┐
│ longTermMemory       │
│   .record(msgs)      │ ← Async record conversation
└──────────────────────┘
```

### AGENT_CONTROL Mode

Framework registers tool functions for agent to call autonomously:

```java
// Auto-registered tools
@Tool(name = "record_to_memory", description = "Record important info to long-term memory")
public Mono<String> recordToMemory(
    @ToolParam(name = "thinking") String thinking,
    @ToolParam(name = "content") List<String> content
);

@Tool(name = "retrieve_from_memory", description = "Retrieve info from long-term memory")
public Mono<String> retrieveFromMemory(
    @ToolParam(name = "keywords") List<String> keywords
);
```

### Mem0 Implementation

```java
public class Mem0LongTermMemory implements LongTermMemory {
    private final Mem0Client client;
    private final String agentId;
    private final String userId;

    @Override
    public Mono<Void> record(List<Msg> msgs) {
        // Extract user and assistant messages
        // Call Mem0 API to store
        return client.addMemory(agentId, userId, messages);
    }

    @Override
    public Mono<String> retrieve(Msg msg) {
        // Extract query keywords
        // Call Mem0 API to search
        return client.searchMemory(agentId, userId, query)
            .map(this::formatMemories);
    }
}
```

## State Persistence

### StateModule Interface

Memory inherits StateModule, supporting state export/import:

```java
public interface StateModule {
    // Export state
    Map<String, Object> saveState();

    // Import state
    void loadState(Map<String, Object> state);

    // Component name (for Session storage)
    default String getComponentName() {
        return getClass().getSimpleName();
    }
}
```

### Session Integration

```java
// Create components
InMemoryMemory memory = new InMemoryMemory();
ReActAgent agent = ReActAgent.builder()
    .memory(memory)
    .build();

// Configure Session
SessionManager sessionManager = SessionManager.forSessionId("user123")
    .withSession(new JsonSession(sessionPath))
    .addComponent(agent)
    .addComponent(memory);

// Load existing state
sessionManager.loadIfExists();

// ... conversation interaction ...

// Save state
sessionManager.saveSession();
```

### Storage Format

JsonSession stores state as JSON files:

```json
{
  "sessionId": "user123",
  "components": {
    "InMemoryMemory": {
      "messages": [
        {
          "role": "USER",
          "content": [{"type": "text", "text": "Hello"}]
        },
        {
          "role": "ASSISTANT",
          "content": [{"type": "text", "text": "Hello! How can I help?"}]
        }
      ]
    },
    "ReActAgent": {
      "memory": "InMemoryMemory",
      "interrupted": false
    }
  },
  "timestamp": "2024-01-15T10:30:00Z"
}
```

## Memory and ReAct Loop

### Message Flow

```
                    Memory
                      │
                      ▼
┌─────────────────────────────────────────┐
│           Message List                   │
│  [sys, user1, asst1, user2, asst2, ...] │
└─────────────────────────────────────────┘
                      │
                      ▼ getMessages()
┌─────────────────────────────────────────┐
│           Formatter                      │
│  Convert to LLM API format               │
└─────────────────────────────────────────┘
                      │
                      ▼
┌─────────────────────────────────────────┐
│           LLM API                        │
└─────────────────────────────────────────┘
                      │
                      ▼
┌─────────────────────────────────────────┐
│           Response                       │
└─────────────────────────────────────────┘
                      │
                      ▼ addMessage()
                    Memory
```

### Tool Call Memory

Tool calls produce two messages:

```java
// 1. Assistant message (contains tool call request)
Msg toolCallMsg = Msg.builder()
    .role(MsgRole.ASSISTANT)
    .content(List.of(
        ToolUseBlock.builder()
            .id("call_001")
            .name("get_weather")
            .input(Map.of("city", "Beijing"))
            .build()
    ))
    .build();

memory.addMessage(toolCallMsg);

// 2. Tool result message
Msg toolResultMsg = Msg.builder()
    .role(MsgRole.TOOL)
    .content(List.of(
        ToolResultBlock.builder()
            .toolUseId("call_001")
            .output(List.of(TextBlock.builder().text("Beijing: Sunny").build()))
            .build()
    ))
    .build();

memory.addMessage(toolResultMsg);
```

## Context Window Management

### Token Calculation

LLMs have context window limits, requiring token management:

```java
// Simplified token estimation
int estimateTokens(List<Msg> messages) {
    return messages.stream()
        .mapToInt(msg -> msg.getTextContent().length() / 4)
        .sum();
}
```

### Window Sliding Strategies

Handling strategies when messages exceed limits:

1. **Truncation** - Keep last N messages
2. **Summarization** - Use LLM to summarize history
3. **Offloading** - Store large content externally

```java
// AutoContextMemory processing flow
if (estimateTokens(messages) > tokenLimit) {
    // 1. Summarize historical messages
    String summary = summarize(messages.subList(0, cutoff));

    // 2. Build new message list
    List<Msg> newMessages = new ArrayList<>();
    newMessages.add(summaryMsg);           // Summary
    newMessages.addAll(recentMessages);    // Recent messages

    // 3. Replace original list
    this.messages = newMessages;
}
```

## Related Documentation

- [Memory Usage Guide](../task/memory.md)
- [State Management](../task/state.md)
- [ReAct Loop Internals](./react-loop.md)

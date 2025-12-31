# Memory

## Overview

Memory manages conversation history and context for agents in AgentScope. AgentScope provides two types of memory:

- **Short-term Memory**: Stores conversation history for the current session, requires Session for persistence and recovery
- **Long-term Memory**: Stores user preferences and knowledge across sessions, automatically persisted by external memory components (e.g., Mem0, ReMe)

## Memory Architecture

In ReActAgent, short-term memory and long-term memory work together:

```
┌────────────┐   ┌─────────────┐   ┌──────────┐   ┌─────────────┐   ┌────────────┐
│ User Input │──▶│ Short-term  │──▶│   LLM    │──▶│ Short-term  │──▶│ User Reply │
└────────────┘   │   Memory    │   │ (Reason) │   │   Memory    │   └────────────┘
                 └──────┬──────┘   └──────────┘   └──────┬──────┘
                        │                                │
                        │ Recall                         │ Async Store
                        ▼                                ▼
                 ┌───────────────────────────────────────────────┐
                 │        Long-term Memory (Independent)         │
                 └───────────────────────────────────────────────┘
```

**Division of Responsibilities**:

- **Short-term Memory**: Stores current session messages, provides context to LLM, supports reasoning loop

- **Long-term Memory** (Independent Component):
  - Internally integrates LLM (memory extraction/summarization) and vector database (storage/retrieval)
  - **Recall**: At conversation start, recalls relevant memories and injects into short-term memory
  - **Store**: After user reply, asynchronously stores to long-term memory for extraction and persistence

## Memory Interface

All short-term memory implementations extend the `Memory` interface:

```java
public interface Memory extends StateModule {
    void addMessage(Msg message);
    List<Msg> getMessages();
    void deleteMessage(int index);
    void clear();
}
```

`Memory` extends `StateModule`, supporting state serialization and deserialization, can be combined with `SessionManager` for persistence.

## Short-term Memory

### InMemoryMemory

The default short-term memory implementation, stores messages in memory.

**Characteristics**:
- Simple in-memory storage
- No context management capability, messages grow indefinitely
- Suitable for simple short conversations

**Usage Example**:

```java
import io.agentscope.core.ReActAgent;
import io.agentscope.core.memory.InMemoryMemory;

ReActAgent agent = ReActAgent.builder()
        .name("Assistant")
        .model(model)
        .memory(new InMemoryMemory())
        .build();

// Messages are automatically stored
agent.call(msg1).block();
agent.call(msg2).block();

// Access history
List<Msg> history = agent.getMemory().getMessages();
System.out.println("Total messages: " + history.size());
```

### AutoContextMemory

Intelligent context memory management system that automatically compresses, offloads, and summarizes conversation history.

**Characteristics**:
- Has context management capability, automatically controls token usage
- 6 progressive compression strategies
- Supports large message offloading and on-demand reload
- Suitable for long conversations, token cost optimization, complex Agent tasks

**Core Features**:
- Automatic compression: Triggers automatically when message count or token count exceeds thresholds
- Intelligent summarization: Uses LLM models for intelligent conversation summarization
- Content offloading: Offloads large content to external storage, reloads on-demand via UUID
- Dual storage mechanism: Working storage (compressed) and original storage (complete history)

**Usage Example**:

```java
import io.agentscope.core.ReActAgent;
import io.agentscope.core.memory.autocontext.AutoContextConfig;
import io.agentscope.core.memory.autocontext.AutoContextMemory;
import io.agentscope.core.memory.autocontext.ContextOffloadTool;
import io.agentscope.core.tool.Toolkit;

// Configuration
AutoContextConfig config = AutoContextConfig.builder()
        .msgThreshold(30)
        .lastKeep(10)
        .tokenRatio(0.3)
        .build();

// Create memory
AutoContextMemory memory = new AutoContextMemory(config, model);

// Register context reload tool
Toolkit toolkit = new Toolkit();
toolkit.registerTool(new ContextOffloadTool(memory));

// Create Agent
ReActAgent agent = ReActAgent.builder()
        .name("Assistant")
        .model(model)
        .memory(memory)
        .toolkit(toolkit)
        .build();
```

**Detailed Documentation**: [AutoContextMemory Documentation](https://github.com/agentscope-ai/agentscope-java/blob/main/agentscope-extensions/agentscope-extensions-autocontext-memory/README.md)

### Short-term Memory Persistence

Short-term memory requires `SessionManager` for persistence to support session recovery after restart.

```java
import io.agentscope.core.session.JsonSession;
import io.agentscope.core.session.SessionManager;

// Create Agent and Memory
InMemoryMemory memory = new InMemoryMemory();
ReActAgent agent = ReActAgent.builder()
        .name("Assistant")
        .model(model)
        .memory(memory)
        .build();

// Create SessionManager, register components to persist
SessionManager sessionManager = SessionManager.forSessionId(sessionId)
        .withSession(new JsonSession(sessionPath))
        .addComponent(agent)
        .addComponent(memory);

// Load existing session (if exists)
sessionManager.loadIfExists();

// ... conversation interactions ...

// Save session
sessionManager.saveSession();
```

**Complete Example**: `agentscope-examples/quickstart/src/main/java/io/agentscope/examples/quickstart/SessionExample.java`

## Long-term Memory

### LongTermMemory Interface

Long-term memory is used to store and recall user preferences and knowledge across sessions:

```java
public interface LongTermMemory {
    // Record messages to long-term memory (called by framework after Agent reply)
    Mono<Void> record(List<Msg> msgs);
    
    // Retrieve relevant memories based on input message (called by framework before reasoning)
    Mono<String> retrieve(Msg msg);
}
```

**Persistence Note**: Long-term memory relies on external memory components (e.g., Mem0, ReMe services), data is automatically persisted to external storage, no manual management required.

**LongTermMemoryMode**:

Configure long-term memory working mode in ReActAgent:

- `STATIC_CONTROL`: Static control mode, framework automatically recalls memories before reasoning and records after reply
- `AGENT_CONTROL`: Agent control mode, lets Agent decide when to record and recall through tools
- `BOTH`: Enable both modes simultaneously

### Mem0LongTermMemory

Long-term memory implementation based on [Mem0](https://mem0.ai/).

#### Background

The OpenAPI interfaces provided by self-hosted Mem0 and Platform Mem0 are inconsistent (different endpoint paths and response formats). `Mem0LongTermMemory` internally provides a compatibility adapter mechanism. By specifying the Mem0 deployment type through the `apiType` parameter, it automatically selects the correct API endpoints and response parsing methods.

#### Usage Examples

**Platform Mem0 (default)**:

```java
import io.agentscope.core.ReActAgent;
import io.agentscope.core.memory.LongTermMemoryMode;
import io.agentscope.core.memory.mem0.Mem0LongTermMemory;

// Using Platform Mem0 (default, no need to specify apiType)
Mem0LongTermMemory longTermMemory = Mem0LongTermMemory.builder()
        .agentName("SmartAssistant")
        .userId("user-001")
        .apiBaseUrl("https://api.mem0.ai")
        .apiKey(System.getenv("MEM0_API_KEY"))
        .build();

ReActAgent agent = ReActAgent.builder()
        .name("Assistant")
        .model(model)
        .longTermMemory(longTermMemory)
        .longTermMemoryMode(LongTermMemoryMode.STATIC_CONTROL)
        .build();
```

**Self-hosted Mem0**:

```java
import io.agentscope.core.ReActAgent;
import io.agentscope.core.memory.LongTermMemoryMode;
import io.agentscope.core.memory.mem0.Mem0ApiType;
import io.agentscope.core.memory.mem0.Mem0LongTermMemory;

// Using self-hosted Mem0, need to specify apiType as Mem0ApiType.SELF_HOSTED
Mem0LongTermMemory selfHostedMemory = Mem0LongTermMemory.builder()
        .agentName("SmartAssistant")
        .userId("user-001")
        .apiBaseUrl("http://localhost:8000")  // Self-hosted Mem0 service address
        .apiKey(System.getenv("MEM0_API_KEY"))  // Optional, depends on self-hosted service config
        .apiType(Mem0ApiType.SELF_HOSTED)  // Specify as self-hosted Mem0
        .build();

ReActAgent agent = ReActAgent.builder()
        .name("Assistant")
        .model(model)
        .longTermMemory(selfHostedMemory)
        .longTermMemoryMode(LongTermMemoryMode.STATIC_CONTROL)
        .build();
```

**Configuration Notes**:

- `apiType`: Optional parameter to specify Mem0 deployment type
  - `Mem0ApiType.PLATFORM` (default): Uses Platform Mem0 API endpoints
  - `Mem0ApiType.SELF_HOSTED`: Uses self-hosted Mem0 API endpoints
- `apiBaseUrl`: Base URL of the Mem0 service
  - Platform Mem0: Usually `https://api.mem0.ai`
  - Self-hosted Mem0: Usually `http://localhost:8000` or your server address
- `apiKey`: API key (optional)
  - Platform Mem0: Required
  - Self-hosted Mem0: Depends on your service configuration, may not be needed

**Complete Example**: `agentscope-examples/advanced/src/main/java/io/agentscope/examples/advanced/Mem0Example.java`

**Run Example**:

```bash
# Platform Mem0 (default)
export MEM0_API_KEY=your_api_key
export MEM0_API_BASE_URL=https://api.mem0.ai  # Optional, defaults to this value
cd agentscope-examples/advanced
mvn exec:java -Dexec.mainClass="io.agentscope.examples.advanced.Mem0Example"

# Self-hosted Mem0
export MEM0_API_KEY=your_api_key  # Optional, depends on service configuration
export MEM0_API_BASE_URL=http://localhost:8000
export MEM0_API_TYPE=self-hosted
cd agentscope-examples/advanced
mvn exec:java -Dexec.mainClass="io.agentscope.examples.advanced.Mem0Example"
```

### ReMeLongTermMemory

Long-term memory implementation based on [ReMe](https://github.com/agentscope-ai/ReMe).

**Usage Example**:

```java
import io.agentscope.core.ReActAgent;
import io.agentscope.core.memory.LongTermMemoryMode;
import io.agentscope.core.memory.reme.ReMeLongTermMemory;

ReMeLongTermMemory longTermMemory = ReMeLongTermMemory.builder()
        .userId("example_user")
        .apiBaseUrl("http://localhost:8002")
        .build();

ReActAgent agent = ReActAgent.builder()
        .name("Assistant")
        .model(model)
        .longTermMemory(longTermMemory)
        .longTermMemoryMode(LongTermMemoryMode.STATIC_CONTROL)
        .build();
```

**Complete Example**: `agentscope-examples/advanced/src/main/java/io/agentscope/examples/advanced/ReMeExample.java`

**Run Example**:

```bash
# Requires REME_API_BASE_URL environment variable (optional, defaults to http://localhost:8002)
cd examples/advanced
mvn exec:java -Dexec.mainClass="io.agentscope.examples.advanced.ReMeExample"
```

## Related Documentation

- [AutoContextMemory Documentation](https://github.com/agentscope-ai/agentscope-java/blob/main/agentscope-extensions/agentscope-extensions-autocontext-memory/README.md)
- [Session Management](./session.md)
- [ReActAgent Guide](./react-agent.md)

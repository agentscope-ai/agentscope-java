# Memory

Memory manages conversation history and context for agents in AgentScope.

## Memory Interface

All memory implementations extend the `Memory` interface:

```java
public interface Memory {
    void addMessage(Msg message);
    List<Msg> getAllMessages();
    void clear();
}
```

## InMemoryMemory

The default memory implementation stores messages in memory:

```java
import io.agentscope.core.memory.InMemoryMemory;
import io.agentscope.core.memory.Memory;

Memory memory = new InMemoryMemory();

// Automatically used by agents
ReActAgent agent = ReActAgent.builder()
        .name("Assistant")
        .model(model)
        .memory(memory)  // Agent stores messages here
        .build();
```

## Using Memory

### Automatic Management

Agents automatically manage memory:

```java
ReActAgent agent = ReActAgent.builder()
        .name("Assistant")
        .model(model)
        .memory(new InMemoryMemory())
        .build();

// Messages are automatically stored
agent.call(msg1).block();
agent.call(msg2).block();

// Access history
List<Msg> history = agent.getMemory().getAllMessages();
System.out.println("Total messages: " + history.size());
```

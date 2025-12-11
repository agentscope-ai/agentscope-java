# Memory

Memory manages conversation history and context for agents in AgentScope.

## Memory Interface

All memory implementations extend the `Memory` interface:

```java
public interface Memory extends StateModule {
    void addMessage(Msg message);
    List<Msg> getMessages();
    void deleteMessage(int index);
    void clear();
}
```

## InMemoryMemory

The default memory implementation: stores messages in memory

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

### Using Built-in InMemoryMemory Implementation

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

### Using mem0

```java
import io.agentscope.core.ReActAgent;
import io.agentscope.core.memory.LongTermMemoryMode;
import io.agentscope.core.memory.mem0.Mem0LongTermMemory;

Mem0LongTermMemory longTermMemory =
        Mem0LongTermMemory.builder()
                .agentName("SmartAssistant")
                .userId("static-control01126")
                .apiBaseUrl(mem0BaseUrl)
                .apiKey(System.getenv("MEM0_API_KEY"))
                .build();

        
ReActAgent agent =
        ReActAgent.builder()
                .name("Assistant")
                .model(
                        DashScopeChatModel.builder()
                                .apiKey(dashscopeApiKey)
                                .modelName("qwen-plus")
                                .build())
                .longTermMemory(longTermMemory)
                .longTermMemoryMode(LongTermMemoryMode.STATIC_CONTROL)
                .build();
```

See the complete Mem0 example:
- `examples/src/main/java/io/agentscope/examples/Mem0Example.java`

Run the example:

Requires MEM0_API_KEY environment variable
```bash
cd examples
mvn exec:java -Dexec.mainClass="io.agentscope.examples.Mem0Example"
```

### Using ReMe

```java
import io.agentscope.core.ReActAgent;
import io.agentscope.core.memory.LongTermMemoryMode;
import io.agentscope.core.memory.reme.ReMeLongTermMemory;

ReMeLongTermMemory longTermMemory =
        ReMeLongTermMemory.builder()
                .userId("example_user")
                .apiBaseUrl("http://localhost:8002")
                .build();

ReActAgent agent =
        ReActAgent.builder()
                .name("Assistant")
                .model(
                        DashScopeChatModel.builder()
                                .apiKey(dashscopeApiKey)
                                .modelName("qwen-plus")
                                .build())
                .longTermMemory(longTermMemory)
                .longTermMemoryMode(LongTermMemoryMode.STATIC_CONTROL)
                .build();
```

See the complete ReMe example:
- `examples/advanced/src/main/java/io/agentscope/examples/advanced/ReMeExample.java`

Run the example:

Requires REME_API_BASE_URL environment variable (optional, defaults to `http://localhost:8002`)
```bash
cd examples/advanced
mvn exec:java -Dexec.mainClass="io.agentscope.examples.advanced.ReMeExample"
```

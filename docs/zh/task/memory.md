# 记忆

记忆负责管理 AgentScope 中智能体的对话历史和上下文。

## Memory 接口

所有记忆实现都扩展 `Memory` 接口：

```java
public interface Memory extends StateModule {
    void addMessage(Msg message);
    List<Msg> getMessages();
    void deleteMessage(int index);
    void clear();
}
```

## InMemoryMemory

默认的内存实现：在内存中存储记忆

```java
import io.agentscope.core.memory.InMemoryMemory;
import io.agentscope.core.memory.Memory;

Memory memory = new InMemoryMemory();

// 智能体自动使用
ReActAgent agent = ReActAgent.builder()
        .name("Assistant")
        .model(model)
        .memory(memory)  // 智能体在此存储消息
        .build();
```

## 使用记忆

### 使用内置的 InMemoryMemory 实现

智能体自动管理内存：

```java
ReActAgent agent = ReActAgent.builder()
        .name("Assistant")
        .model(model)
        .memory(new InMemoryMemory())
        .build();

// 消息自动存储
agent.call(msg1).block();
agent.call(msg2).block();

// 访问历史
List<Msg> history = agent.getMemory().getAllMessages();
System.out.println("消息总数: " + history.size());
```

### 使用 mem0

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

查看完整的 Mem0 示例：
- `examples/src/main/java/io/agentscope/examples/Mem0Example.java`

运行示例：

需要配置 MEM0_API_KEY 环境变量
```bash
cd examples
mvn exec:java -Dexec.mainClass="io.agentscope.examples.Mem0Example"
```

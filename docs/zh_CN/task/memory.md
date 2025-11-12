# 内存

内存管理 AgentScope 中智能体的对话历史和上下文。

## Memory 接口

所有内存实现都扩展 `Memory` 接口：

```java
public interface Memory {
    void addMessage(Msg message);
    List<Msg> getAllMessages();
    void clear();
}
```

## InMemoryMemory

默认的内存实现在内存中存储消息：

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

## 使用内存

### 自动管理

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

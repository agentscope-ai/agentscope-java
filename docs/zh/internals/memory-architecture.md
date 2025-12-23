# 记忆架构

本文深入介绍 AgentScope 记忆系统的内部设计，包括短期记忆、长期记忆的协作机制和状态管理。

## 双层记忆架构

AgentScope 采用双层记忆架构，分离短期上下文和长期知识：

```
┌─────────────────────────────────────────────────────────────────┐
│                         ReActAgent                               │
│  ┌────────────────────────────────────────────────────────────┐ │
│  │                    短期记忆 (Memory)                        │ │
│  │  ┌─────────────────────────────────────────────────────┐   │ │
│  │  │ 当前会话消息列表                                      │   │ │
│  │  │ [系统提示, 用户消息, 助手响应, 工具调用, 工具结果...]  │   │ │
│  │  └─────────────────────────────────────────────────────┘   │ │
│  │                           ↑ ↓                              │ │
│  │                    每轮对话读写                              │ │
│  └────────────────────────────────────────────────────────────┘ │
│                              ↕                                  │
│  ┌────────────────────────────────────────────────────────────┐ │
│  │                  长期记忆 (LongTermMemory)                  │ │
│  │  ┌─────────────────────────────────────────────────────┐   │ │
│  │  │ 外部存储 (Mem0 / ReMe / 自定义)                       │   │ │
│  │  │ - 用户偏好                                            │   │ │
│  │  │ - 历史知识                                            │   │ │
│  │  │ - 跨会话信息                                          │   │ │
│  │  └─────────────────────────────────────────────────────┘   │ │
│  │                     ↑ retrieve    ↓ record                 │ │
│  └────────────────────────────────────────────────────────────┘ │
└─────────────────────────────────────────────────────────────────┘
```

## 短期记忆（Memory）

### 接口定义

```java
public interface Memory extends StateModule {
    void addMessage(Msg message);        // 添加消息
    List<Msg> getMessages();             // 获取所有消息
    void deleteMessage(int index);       // 删除指定消息
    void clear();                        // 清空所有消息
}
```

### InMemoryMemory

最简单的实现，将消息存储在内存列表中：

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

    // StateModule 实现
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

**特点**：
- 无上限增长
- 需结合 Session 持久化
- 适合短对话

### AutoContextMemory

智能上下文管理，自动压缩和摘要：

```java
AutoContextConfig config = AutoContextConfig.builder()
    .msgThreshold(30)      // 消息数量阈值
    .tokenRatio(0.3)       // token 使用比例
    .lastKeep(10)          // 保留最近 N 条
    .build();

AutoContextMemory memory = new AutoContextMemory(config, model);
```

**压缩策略**：

```
原始消息 (50 条)
        │
        ▼
┌───────────────────┐
│ 检测触发条件       │ msgThreshold / tokenRatio
└───────────────────┘
        │
        ▼
┌───────────────────┐
│ 渐进式压缩         │
│ 1. 合并相邻消息    │
│ 2. 摘要历史对话    │
│ 3. 卸载大内容      │
└───────────────────┘
        │
        ▼
压缩后消息 (15 条)
+ 摘要消息
+ 卸载引用
```

### 消息生命周期

```
用户输入
    │
    ▼
┌─────────────────────┐
│ memory.addMessage() │ ← 用户消息入库
└─────────────────────┘
    │
    ▼
┌─────────────────────┐
│   LLM 推理          │ ← 读取 memory.getMessages()
└─────────────────────┘
    │
    ▼
┌─────────────────────┐
│ memory.addMessage() │ ← 助手响应入库
└─────────────────────┘
    │
    ▼
如果有工具调用
    │
    ▼
┌─────────────────────┐
│ memory.addMessage() │ ← 工具结果入库
└─────────────────────┘
    │
    ▼
继续推理...
```

## 长期记忆（LongTermMemory）

### 接口定义

```java
public interface LongTermMemory {
    // 记录消息到长期记忆
    Mono<Void> record(List<Msg> msgs);

    // 检索相关记忆
    Mono<String> retrieve(Msg msg);
}
```

### 工作模式

通过 `LongTermMemoryMode` 控制长期记忆的工作方式：

| 模式 | 说明 |
|------|------|
| `STATIC_CONTROL` | 框架自动管理：推理前召回，回复后记录 |
| `AGENT_CONTROL` | 智能体自主管理：通过工具调用 |
| `BOTH` | 两种模式同时启用 |

### STATIC_CONTROL 模式

框架在推理循环中自动集成长期记忆：

```
用户输入
    │
    ▼
┌──────────────────────┐
│ longTermMemory       │
│   .retrieve(msg)     │ ← 检索相关记忆
└──────────────────────┘
    │
    ▼
┌──────────────────────┐
│ 注入到系统提示        │
│ <long_term_memory>   │
│   相关记忆内容        │
│ </long_term_memory>  │
└──────────────────────┘
    │
    ▼
┌──────────────────────┐
│   LLM 推理           │
└──────────────────────┘
    │
    ▼
┌──────────────────────┐
│ longTermMemory       │
│   .record(msgs)      │ ← 异步记录对话
└──────────────────────┘
```

### AGENT_CONTROL 模式

框架注册工具函数，由智能体自主调用：

```java
// 自动注册的工具
@Tool(name = "record_to_memory", description = "记录重要信息到长期记忆")
public Mono<String> recordToMemory(
    @ToolParam(name = "thinking") String thinking,
    @ToolParam(name = "content") List<String> content
);

@Tool(name = "retrieve_from_memory", description = "从长期记忆检索信息")
public Mono<String> retrieveFromMemory(
    @ToolParam(name = "keywords") List<String> keywords
);
```

### Mem0 实现

```java
public class Mem0LongTermMemory implements LongTermMemory {
    private final Mem0Client client;
    private final String agentId;
    private final String userId;

    @Override
    public Mono<Void> record(List<Msg> msgs) {
        // 提取用户和助手消息
        // 调用 Mem0 API 存储
        return client.addMemory(agentId, userId, messages);
    }

    @Override
    public Mono<String> retrieve(Msg msg) {
        // 提取查询关键词
        // 调用 Mem0 API 搜索
        return client.searchMemory(agentId, userId, query)
            .map(this::formatMemories);
    }
}
```

## 状态持久化

### StateModule 接口

Memory 继承 StateModule，支持状态导出/导入：

```java
public interface StateModule {
    // 导出状态
    Map<String, Object> saveState();

    // 导入状态
    void loadState(Map<String, Object> state);

    // 组件名称（用于 Session 存储）
    default String getComponentName() {
        return getClass().getSimpleName();
    }
}
```

### Session 集成

```java
// 创建组件
InMemoryMemory memory = new InMemoryMemory();
ReActAgent agent = ReActAgent.builder()
    .memory(memory)
    .build();

// 配置 Session
SessionManager sessionManager = SessionManager.forSessionId("user123")
    .withSession(new JsonSession(sessionPath))
    .addComponent(agent)
    .addComponent(memory);

// 加载已有状态
sessionManager.loadIfExists();

// ... 对话交互 ...

// 保存状态
sessionManager.saveSession();
```

### 存储格式

JsonSession 将状态存储为 JSON 文件：

```json
{
  "sessionId": "user123",
  "components": {
    "InMemoryMemory": {
      "messages": [
        {
          "role": "USER",
          "content": [{"type": "text", "text": "你好"}]
        },
        {
          "role": "ASSISTANT",
          "content": [{"type": "text", "text": "你好！有什么可以帮助你的？"}]
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

## 记忆与 ReAct 循环

### 消息流转

```
                    Memory
                      │
                      ▼
┌─────────────────────────────────────────┐
│           消息列表                       │
│  [sys, user1, asst1, user2, asst2, ...] │
└─────────────────────────────────────────┘
                      │
                      ▼ getMessages()
┌─────────────────────────────────────────┐
│           Formatter                      │
│  转换为 LLM API 格式                     │
└─────────────────────────────────────────┘
                      │
                      ▼
┌─────────────────────────────────────────┐
│           LLM API                        │
└─────────────────────────────────────────┘
                      │
                      ▼
┌─────────────────────────────────────────┐
│           响应                           │
└─────────────────────────────────────────┘
                      │
                      ▼ addMessage()
                    Memory
```

### 工具调用的记忆

工具调用产生两条消息：

```java
// 1. 助手消息（包含工具调用请求）
Msg toolCallMsg = Msg.builder()
    .role(MsgRole.ASSISTANT)
    .content(List.of(
        ToolUseBlock.builder()
            .id("call_001")
            .name("get_weather")
            .input(Map.of("city", "北京"))
            .build()
    ))
    .build();

memory.addMessage(toolCallMsg);

// 2. 工具结果消息
Msg toolResultMsg = Msg.builder()
    .role(MsgRole.TOOL)
    .content(List.of(
        ToolResultBlock.builder()
            .toolUseId("call_001")
            .output(List.of(TextBlock.builder().text("北京：晴").build()))
            .build()
    ))
    .build();

memory.addMessage(toolResultMsg);
```

## 上下文窗口管理

### Token 计算

LLM 有上下文窗口限制，需要管理 token 使用：

```java
// 简化的 token 估算
int estimateTokens(List<Msg> messages) {
    return messages.stream()
        .mapToInt(msg -> msg.getTextContent().length() / 4)
        .sum();
}
```

### 窗口滑动策略

当消息过多时的处理策略：

1. **截断** - 保留最近 N 条消息
2. **摘要** - 用 LLM 摘要历史对话
3. **卸载** - 将大内容存储到外部

```java
// AutoContextMemory 的处理流程
if (estimateTokens(messages) > tokenLimit) {
    // 1. 摘要历史消息
    String summary = summarize(messages.subList(0, cutoff));

    // 2. 构建新的消息列表
    List<Msg> newMessages = new ArrayList<>();
    newMessages.add(summaryMsg);           // 摘要
    newMessages.addAll(recentMessages);    // 最近消息

    // 3. 替换原列表
    this.messages = newMessages;
}
```

## 相关文档

- [记忆使用指南](../task/memory.md)
- [状态管理](../task/state.md)
- [ReAct 循环原理](./react-loop.md)

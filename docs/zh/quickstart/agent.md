# 智能体

ReActAgent 是使用 ReAct（推理 + 行动）算法的主要智能体实现。

## 如何使用 ReActAgent

使用 ReActAgent 包含三个核心步骤：

**1. 构建** - 配置智能体的模型、工具和内存：

```java
ReActAgent agent = ReActAgent.builder()
    .name("Assistant")
    .sysPrompt("你是一个有帮助的助手。")
    .model(model)                    // 用于推理的 LLM
    .toolkit(toolkit)                // 智能体可用的工具
    .memory(memory)                  // 对话历史
    .build();
```

**2. 调用** - 发送消息并获取响应：

```java
Msg response = agent.call(inputMsg).block();
```

**3. 管理状态** - 使用 Session 在请求间持久化对话：

```java
// 保存状态
SessionManager.forSessionId(userId)
    .withSession(new JsonSession(path))
    .addComponent(agent)
    .saveSession();

// 加载状态
SessionManager.forSessionId(userId)
    .withSession(new JsonSession(path))
    .addComponent(agent)
    .loadIfExists();
```

**推荐模式**（适用于 Web 应用）：

每次请求创建新的智能体实例，使用 Session 持久化状态。这确保线程安全，同时保持对话连续性：

```java
public Msg handleRequest(String userId, Msg inputMsg) {
    // 1. 构建新实例
    Toolkit toolkit = new Toolkit();
    toolkit.registerTool(new WeatherService());

    Memory memory = new InMemoryMemory();
    ReActAgent agent = ReActAgent.builder()
        .name("Assistant")
        .model(model)
        .toolkit(toolkit)
        .memory(memory)
        .build();

    // 2. 加载之前的状态
    SessionManager.forSessionId(userId)
        .withSession(new JsonSession(Path.of("sessions")))
        .addComponent(agent)
        .addComponent(memory)
        .loadIfExists();

    // 3. 处理请求
    Msg response = agent.call(inputMsg).block();

    // 4. 保存状态
    SessionManager.forSessionId(userId)
        .withSession(new JsonSession(Path.of("sessions")))
        .addComponent(agent)
        .addComponent(memory)
        .saveSession();

    return response;
}
```

下面逐个介绍各个功能。

---

## 基础用法

最简示例：

```java
ReActAgent agent = ReActAgent.builder()
    .name("Assistant")
    .sysPrompt("你是一个有帮助的助手。")
    .model(DashScopeChatModel.builder()
        .apiKey(System.getenv("DASHSCOPE_API_KEY"))
        .modelName("qwen-plus")
        .build())
    .build();

Msg response = agent.call(inputMsg).block();
```

## 构建器参数

必需：
- **name**：智能体标识符
- **sysPrompt**：系统提示词
- **model**：用于推理的 LLM 模型

可选：
- **toolkit**：智能体可用的工具（默认：空）
- **memory**：对话历史存储（默认：InMemoryMemory）
- **maxIters**：最大推理-行动迭代次数（默认：10）
- **hooks**：用于自定义的事件钩子（默认：空）
- **modelExecutionConfig**：模型调用的超时/重试
- **toolExecutionConfig**：工具调用的超时/重试

## 核心方法

### call()

处理消息并生成响应：

```java
// 单个消息
Mono<Msg> response = agent.call(inputMsg);

// 多个消息
Mono<Msg> response = agent.call(List.of(msg1, msg2));

// 从当前状态继续
Mono<Msg> response = agent.call();
```

### stream()

获取实时流式更新：

```java
Flux<Event> eventStream = agent.stream(inputMsg);

eventStream.subscribe(event -> {
    if (event.getEventType() == EventType.TEXT_CHUNK) {
        System.out.print(event.getChunk().getText());
    }
});
```

## 添加工具

```java
public class WeatherService {
    @Tool(description = "获取天气")
    public String getWeather(
        @ToolParam(name = "location", description = "城市") String location) {
        return location + " 晴天，25°C";
    }
}

Toolkit toolkit = new Toolkit();
toolkit.registerTool(new WeatherService());

ReActAgent agent = ReActAgent.builder()
    .name("Assistant")
    .sysPrompt("使用工具回答问题。")
    .model(model)
    .toolkit(toolkit)
    .build();
```

## 结构化输出

从智能体请求结构化数据：

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

## 内存管理

Memory 自动存储对话历史：

```java
Memory memory = new InMemoryMemory();

ReActAgent agent = ReActAgent.builder()
    .name("Assistant")
    .model(model)
    .memory(memory)
    .build();

// 内存自动存储所有消息
agent.call(msg1).block();
agent.call(msg2).block();

// 访问历史
List<Msg> history = memory.getAllMessages();
```

## 并发

> **重要**：Agent 对象**不是线程安全的**。不要从多个线程并发调用同一个智能体实例。

```java
// ❌ 错误 - 在同一个智能体上并发调用
Flux.merge(
    agent.call(msg1),
    agent.call(msg2),
    agent.call(msg3)
).subscribe();

// ✅ 正确 - 使用单独的智能体
ReActAgent agent1 = ReActAgent.builder()...build();
ReActAgent agent2 = ReActAgent.builder()...build();
ReActAgent agent3 = ReActAgent.builder()...build();

Flux.merge(
    agent1.call(msg1),
    agent2.call(msg2),
    agent3.call(msg3)
).subscribe();

// ✅ 正确 - 顺序执行
agent.call(msg1)
    .flatMap(r1 -> agent.call(msg2))
    .flatMap(r2 -> agent.call(msg3))
    .subscribe();
```

## 完整示例

```java
public class Calculator {
    @Tool(description = "加法")
    public int add(@ToolParam(name = "a") int a, @ToolParam(name = "b") int b) {
        return a + b;
    }
}

Toolkit toolkit = new Toolkit();
toolkit.registerTool(new Calculator());

ReActAgent agent = ReActAgent.builder()
    .name("MathAssistant")
    .sysPrompt("你是一个数学助手。使用计算器工具。")
    .model(DashScopeChatModel.builder()
        .apiKey(System.getenv("DASHSCOPE_API_KEY"))
        .modelName("qwen-plus")
        .build())
    .toolkit(toolkit)
    .memory(new InMemoryMemory())
    .maxIters(5)
    .build();

Msg question = Msg.builder()
    .textContent("(15 + 7) * 3 等于多少？")
    .build();

Msg response = agent.call(question).block();
System.out.println("答案: " + response.getTextContent());
```

## 下一步

- [工具系统](../task/tool.md) - 深入了解工具
- [钩子系统](../task/hook.md) - 自定义智能体行为
- [管道](../task/pipeline.md) - 组合多个智能体

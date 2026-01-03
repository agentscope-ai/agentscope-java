# 核心概念

本章从工程实践的角度介绍 AgentScope 中的核心概念，帮助你理解框架的设计理念。

> **注意**：本文的目标是阐明 AgentScope 在工程实践中解决的问题，以及为开发者提供的帮助，而非给出严谨的学术定义。

## 核心流程

在深入各个概念之前，让我们先理解智能体是如何工作的。AgentScope 的核心是 **ReAct 循环**（Reasoning + Acting）：

```
                              User Input (Message)
                                      |
                                      v
+-------------------------------------------------------------------------+
|                              ReActAgent                                 |
|                                                                         |
|    +------------------------+        +------------------------+         |
|    |        Memory          |        |        Toolkit         |         |
|    +------------------------+        +------------------------+         |
|              |                                  |                        |
|              v                                  |                        |
|    +------------------------------------------------------------+       |
|    |                    1. Reasoning                            |       |
|    |                                                            |       |
|    |    +-----------+     +-----------+     +-------------+     |       |
|    |    |  Memory   | --> | Formatter | --> |    Model    |     |       |
|    |    +-----------+     +-----------+     +-------------+     |       |
|    +------------------------------------------------------------+       |
|                                 |                                       |
|                                 v                                       |
|                          Need tool call?                                |
|                           /          \                                  |
|                         Yes           No                                |
|                         /              \                                |
|                        v                v                               |
|    +------------------------+    +------------------------+             |
|    |      2. Acting         |    |   Return final response |             |
|    |                        |    +------------------------+             |
|    |    +---------+         |               |                           |
|    |    | Toolkit |         |               |                           |
|    |    +---------+         |               |                           |
|    |         |              |               |                           |
|    |         v              |               |                           |
|    |   Store in Memory      |               |                           |
|    |         |              |               |                           |
|    |         v              |               |                           |
|    |   Back to step 1       |               |                           |
|    +------------------------+               |                           |
|                                             |                           |
+-------------------------------------------------------------------------+
                                              |
                                              v
                                    Agent Response (Message)
```

**流程说明**：
1. **Reasoning（推理）**：从 Memory 读取历史消息 → Formatter 转换格式 → Model 调用 LLM
2. **Acting（行动）**：Toolkit 执行工具 → 结果存入 Memory → 返回继续推理

理解了这个流程，下面我们逐一介绍各个概念。

---

## 消息（Message）

**解决的问题**：智能体需要一种统一的数据结构来承载各种类型的信息——文本、图像、工具调用等。

Message 是 AgentScope 最核心的数据结构，用于：
- 在智能体之间交换信息
- 在记忆中存储对话历史
- 作为与 LLM API 交互的统一媒介

**核心字段**：

| 字段 | 说明 |
|-----|------|
| `name` | 发送者名称，多智能体场景用于区分身份 |
| `role` | 角色：`USER`、`ASSISTANT`、`SYSTEM` 或 `TOOL` |
| `content` | 内容块列表，支持多种类型 |
| `metadata` | 可选的结构化数据 |

**内容类型**：

- `TextBlock` - 纯文本
- `ImageBlock` / `AudioBlock` / `VideoBlock` - 多模态内容
- `ThinkingBlock` - 推理过程（用于推理模型）
- `ToolUseBlock` - LLM 发起的工具调用
- `ToolResultBlock` - 工具执行结果

**响应元信息**：

Agent 返回的消息包含额外的元信息，帮助理解执行状态：

| 方法 | 说明 |
|-----|------|
| `getGenerateReason()` | 消息生成原因，用于判断后续操作 |
| `getChatUsage()` | Token 用量统计（输入/输出 Token 数、耗时） |

**GenerateReason 枚举值**：

| 值 | 说明 |
|----|------|
| `MODEL_STOP` | 任务正常完成 |
| `TOOL_SUSPENDED` | 工具需要外部执行，等待提供结果 |
| `REASONING_STOP_REQUESTED` | Reasoning 阶段被 Hook 暂停（HITL） |
| `ACTING_STOP_REQUESTED` | Acting 阶段被 Hook 暂停（HITL） |
| `INTERRUPTED` | Agent 被中断 |
| `MAX_ITERATIONS` | 达到最大迭代次数 |

**示例**：

```java
// 创建文本消息
Msg msg = Msg.builder()
    .name("user")
    .textContent("今天北京天气怎么样？")
    .build();

// 创建多模态消息
Msg imgMsg = Msg.builder()
    .name("user")
    .content(List.of(
        TextBlock.builder().text("这张图片是什么？").build(),
        ImageBlock.builder().source(new URLSource("https://example.com/photo.jpg")).build()
    ))
    .build();
```

---

## 智能体（Agent）

**解决的问题**：需要一个统一的抽象来封装"接收消息 → 处理 → 返回响应"的逻辑。

Agent 接口定义了智能体的核心契约：

```java
public interface Agent {
    Mono<Msg> call(Msg msg);      // 处理消息，返回响应
    Flux<Msg> stream(Msg msg);    // 流式返回响应
    void interrupt();             // 中断执行
}
```

### 有状态设计

AgentScope 中的 Agent 是**有状态的对象**。每个 Agent 实例持有自己的：
- **Memory**：对话历史
- **Toolkit**：工具集合及其状态
- **配置**：系统提示、模型设置等

> **重要**：由于 Agent 和 Toolkit 都是有状态的，**同一个实例不能被并发调用**。如果需要处理多个并发请求，应该为每个请求创建独立的 Agent 实例，或使用对象池管理。

```java
// ❌ 错误：多线程共享同一个 agent 实例
ReActAgent agent = ReActAgent.builder()...build();
executor.submit(() -> agent.call(msg1));  // 并发问题！
executor.submit(() -> agent.call(msg2));  // 并发问题！

// ✅ 正确：每个请求使用独立的 agent 实例
executor.submit(() -> {
    ReActAgent agent = ReActAgent.builder()...build();
    agent.call(msg1);
});
```

### ReActAgent

`ReActAgent` 是框架提供的主要实现，使用 ReAct 算法（推理 + 行动循环）：

```java
ReActAgent agent = ReActAgent.builder()
    .name("Assistant")
    .model(DashScopeChatModel.builder()
        .apiKey(System.getenv("DASHSCOPE_API_KEY"))
        .modelName("qwen3-max")
        .build())
    .sysPrompt("你是一个有帮助的助手。")
    .toolkit(toolkit)  // 可选：添加工具
    .build();

// 调用智能体
Msg response = agent.call(userMsg).block();
```

> 详细配置请参考 [创建 ReAct 智能体](agent.md)。

---

## 工具（Tool）

**解决的问题**：LLM 本身只能生成文本，无法执行实际操作。工具让智能体能够查询数据库、调用 API、执行计算等。

AgentScope 中的"工具"是带有 `@Tool` 注解的 Java 方法，支持：
- 实例方法、静态方法、类方法
- 同步或异步调用
- 流式或非流式返回

**示例**：

```java
public class WeatherService {
    @Tool(name = "get_weather", description = "获取指定城市的天气")
    public String getWeather(
            @ToolParam(name = "city", description = "城市名称") String city) {
        // 调用天气 API
        return "北京：晴，25°C";
    }
}

// 注册工具
Toolkit toolkit = new Toolkit();
toolkit.registerTool(new WeatherService());
```

> **重要**：`@ToolParam` 必须显式指定 `name` 属性，因为 Java 运行时不保留方法参数名。

---

## 记忆（Memory）

**解决的问题**：智能体需要记住对话历史，才能进行有上下文的对话。

Memory 管理对话历史，`ReActAgent` 会自动：
- 将用户消息加入记忆
- 将工具调用和结果加入记忆
- 将智能体响应加入记忆
- 在推理时读取记忆作为上下文

默认使用 `InMemoryMemory`（内存存储）。如需跨会话持久化，请参考 [状态管理](../task/state.md)。

---

## 格式化器（Formatter）

**解决的问题**：不同的 LLM 提供商有不同的 API 格式，需要一个适配层来屏蔽差异。

Formatter 负责将 AgentScope 的消息转换为特定 LLM API 所需的格式，包括：
- 提示词工程（添加系统提示、格式化多轮对话）
- 消息验证
- 多智能体场景的身份处理

**内置实现**：
- `DashScopeFormatter` - 阿里云百炼（通义千问系列）
- `OpenAIFormatter` - OpenAI 及兼容 API

> 格式化器根据 Model 类型自动选择，通常无需手动配置。

---

## 钩子（Hook）

**解决的问题**：需要在智能体执行的各个阶段插入自定义逻辑，如日志、监控、消息修改等。

Hook 通过事件机制在 ReAct 循环的关键节点提供扩展点：

| 事件类型 | 触发时机 | 可修改 |
|---------|---------|--------|
| `PreCallEvent` | 智能体开始处理前 | ✓ |
| `PostCallEvent` | 智能体处理完成后 | ✓ |
| `PreReasoningEvent` | 调用 LLM 前 | ✓ |
| `PostReasoningEvent` | LLM 返回后 | ✓ |
| `ReasoningChunkEvent` | LLM 流式输出时 | - |
| `PreActingEvent` | 执行工具前 | ✓ |
| `PostActingEvent` | 工具执行后 | ✓ |
| `ActingChunkEvent` | 工具流式输出时 | - |
| `ErrorEvent` | 发生错误时 | - |

**Hook 优先级**：Hook 按优先级执行，数值越小优先级越高，默认 100。

**示例**：

```java
Hook loggingHook = new Hook() {
    @Override
    public <T extends HookEvent> Mono<T> onEvent(T event) {
        return switch (event) {
            case PreCallEvent e -> {
                System.out.println("智能体开始处理...");
                yield Mono.just(event);
            }
            case ReasoningChunkEvent e -> {
                System.out.print(e.getIncrementalChunk().getTextContent());  // 打印流式输出
                yield Mono.just(event);
            }
            case PostCallEvent e -> {
                System.out.println("处理完成: " + e.getFinalMessage().getTextContent());
                yield Mono.just(event);
            }
            default -> Mono.just(event);
        };
    }

    @Override
    public int priority() {
        return 50;  // 高优先级
    }
};

ReActAgent agent = ReActAgent.builder()
    // ... 其他配置
    .hook(loggingHook)
    .build();
```

> 详细用法请参考 [Hook 系统](../task/hook.md)。

---

## 状态管理与会话

**解决的问题**：智能体的对话历史、配置等状态需要能够保存和恢复，以支持会话持久化。

AgentScope 将对象的"初始化"与"状态"分离：
- `saveState()` - 导出当前状态为可序列化的 Map
- `loadState()` - 从保存的状态恢复

**Session** 提供跨运行的持久化存储：

```java
// 保存会话
SessionManager.forSessionId("user123")
    .withSession(new JsonSession(Path.of("sessions")))
    .addComponent(agent)
    .saveSession();

// 恢复会话
SessionManager.forSessionId("user123")
    .withSession(new JsonSession(Path.of("sessions")))
    .addComponent(agent)
    .loadIfExists();
```

---

## 响应式编程

**解决的问题**：LLM 调用和工具执行通常涉及 I/O 操作，同步阻塞会浪费资源。

AgentScope 基于 [Project Reactor](https://projectreactor.io/) 构建，使用：
- `Mono<T>` - 返回 0 或 1 个结果
- `Flux<T>` - 返回 0 到 N 个结果（用于流式）

```java
// 非阻塞调用
Mono<Msg> responseMono = agent.call(msg);

// 需要结果时阻塞
Msg response = responseMono.block();

// 或异步处理
responseMono.subscribe(response ->
    System.out.println(response.getTextContent())
);
```

---

## 下一步

- [创建 ReAct 智能体](agent.md) - 完整的智能体创建教程
- [工具系统](../task/tool.md) - 深入了解工具的高级用法
- [Hook 系统](../task/hook.md) - 自定义智能体行为
- [模型集成](../task/model.md) - 接入不同的 LLM 提供商

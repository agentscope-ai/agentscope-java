# 核心概念

## 概览

AgentScope 围绕以下核心概念构建：

**数据流**：
- **Message（消息）**：基础数据结构 - 所有信息以消息形式流转
- **Tool（工具）**：智能体可调用的函数，用于与外部系统交互
- **Memory（内存）**：对话历史存储

**智能体系统**：
- **Agent（智能体）**：处理消息并生成响应
- **ReActAgent**：主要实现，使用推理 + 行动循环
- **Formatter（格式化器）**：将消息转换为特定 LLM 的格式

**执行控制**：
- **Hook（钩子）**：在特定阶段自定义行为的扩展点
- **Reactive Programming（响应式编程）**：使用 Reactor 的非阻塞异步操作

**状态与组合**：
- **State Management（状态管理）**：保存和恢复智能体状态
- **Session（会话）**：跨应用运行的持久化存储
- **Pipeline（管道）**：将多个智能体组合成工作流

**它们如何协同工作**：

```
用户输入（Message）
    ↓
智能体（ReActAgent）
    ├─→ Formatter → LLM API
    ├─→ Tool 执行
    ├─→ Memory 存储
    └─→ Hook 事件
    ↓
响应（Message）
```

下面逐个详细介绍。

---

## 消息（Message）

Message 是 AgentScope 的基础数据结构 - 用于智能体通信、内存存储和 LLM 输入输出。

结构：
- **name**：发送者身份（多智能体场景有用）
- **role**：`USER`、`ASSISTANT`、`SYSTEM` 或 `TOOL`
- **content**：内容块列表（文本、图像、工具调用等）
- **metadata**：可选的结构化数据

内容类型：
- **TextBlock**：纯文本
- **ImageBlock/AudioBlock/VideoBlock**：媒体（URL 或 Base64）
- **ThinkingBlock**：推理过程
- **ToolUseBlock**：工具调用（来自 LLM）
- **ToolResultBlock**：工具执行结果

示例：

```java
// 文本消息
Msg msg = Msg.builder()
    .name("Alice")
    .textContent("你好！")
    .build();

// 多模态
Msg imgMsg = Msg.builder()
    .name("Assistant")
    .content(List.of(
        TextBlock.builder().text("这是图表：").build(),
        ImageBlock.builder().source(URLSource.of("https://example.com/chart.png")).build()
    ))
    .build();
```

## 工具（Tool）

任何带 `@Tool` 注解的 Java 方法都可以成为工具。支持实例/静态方法、同步/异步、流式/非流式。

```java
public class WeatherService {
    @Tool(description = "获取天气")
    public String getWeather(
        @ToolParam(name = "location", description = "城市名") String location) {
        return "晴天，25°C";
    }
}
```

**注意**：`@ToolParam` 需要显式 `name` - Java 运行时不保留参数名。

## 智能体（Agent）

Agent 接口定义核心契约：

```java
public interface Agent {
    String getAgentId();
    String getName();
    Mono<Msg> call(Msg msg);
    // ... 其他方法
}
```

核心方法：
- **call()**：处理消息并生成响应
- **stream()**：实时流式响应
- **interrupt()**：停止执行

### ReActAgent

主要实现，使用 ReAct 算法（推理 + 行动）：

```java
ReActAgent agent = ReActAgent.builder()
    .name("Assistant")
    .model(DashScopeChatModel.builder()
        .apiKey(System.getenv("DASHSCOPE_API_KEY"))
        .modelName("qwen-plus")
        .build())
    .sysPrompt("你是一个有帮助的助手。")
    .build();
```

## 格式化器（Formatter）

将消息转换为特定 LLM 的 API 格式。处理提示词工程、验证和多智能体格式化。

提供商：
- **DashScopeFormatter**：阿里云百炼
- **OpenAIFormatter**：OpenAI 兼容 API

根据模型自动选择 - 无需手动配置。

## 钩子（Hook）

自定义智能体行为的扩展点。所有钩子用 pattern matching 实现 `onEvent()`。

事件类型：
- **可修改**：PreReasoningEvent, PostReasoningEvent, PreActingEvent, PostActingEvent, PostCallEvent
- **仅通知**：PreCallEvent, ReasoningChunkEvent, ActingChunkEvent, ErrorEvent

示例：

```java
Hook myHook = new Hook() {
    @Override
    public <T extends HookEvent> Mono<T> onEvent(T event) {
        return switch (event) {
            case PreReasoningEvent e -> {
                System.out.println("推理中: " + e.getModelName());
                yield Mono.just(event);
            }
            case ReasoningChunkEvent e -> {
                System.out.print(e.getChunk().getTextContent());
                yield Mono.just(event);
            }
            default -> Mono.just(event);
        };
    }
};
```

优先级：数值越小优先级越高（默认 100）。

## 内存（Memory）

管理对话历史。ReActAgent 自动存储所有交换的消息。

- **InMemoryMemory**：简单的内存历史
- 支持自定义实现满足高级需求

## 响应式编程

基于 Project Reactor 构建，使用 `Mono<T>`（0-1 项）和 `Flux<T>`（0-N 项）。

优势：非阻塞 I/O、高效资源利用、天然流式支持、可组合管道。

```java
// 非阻塞
Mono<Msg> responseMono = agent.call(msg);

// 需要时阻塞
Msg response = responseMono.block();

// 或异步
responseMono.subscribe(response ->
    System.out.println(response.getTextContent())
);
```

## 构建者模式

全面使用，提供类型安全、可读的配置：

```java
ReActAgent agent = ReActAgent.builder()
    .name("Assistant")
    .model(model)
    .sysPrompt("你是一个有帮助的助手。")
    .tools(List.of(weatherService))
    .maxIterations(10)
    .build();
```

## 状态管理

分离初始化和状态，允许恢复到不同状态：

- **saveState()**：保存为 JSON 可序列化的 map
- **loadState()**：从保存的状态恢复

支持对话持久化、检查点和状态迁移。

## 会话（Session）

跨运行持久化存储组件。统一管理多个组件。

```java
// 保存
SessionManager.forSessionId("user123")
    .withJsonSession(Path.of("sessions"))
    .addComponent(agent)
    .addComponent(memory)
    .saveSession();

// 加载
SessionManager.forSessionId("user123")
    .withJsonSession(Path.of("sessions"))
    .addComponent(agent)
    .addComponent(memory)
    .loadIfExists();
```

**JsonSession**：默认实现，使用 JSON 文件（`~/.agentscope/sessions/`）。

## 管道（Pipeline）

多智能体工作流的组合模式：

- **SequentialPipeline**：按顺序执行
- **FanoutPipeline**：并行执行

```java
Pipeline pipeline = Pipelines.sequential(agent1, agent2, agent3);
Msg result = pipeline.call(inputMsg).block();
```

## 下一步

- [构建第一个智能体](agent.md) - 创建可用的智能体
- [探索工具](../task/tool.md) - 为智能体添加工具
- [使用钩子](../task/hook.md) - 自定义智能体行为

# 核心概念

本章从工程实践的角度介绍 AgentScope Java 的核心概念。

> **注意**：介绍核心概念的目标是阐明 AgentScope 解决的实际问题以及如何支持开发者，而不是提供形式化的定义。

## 消息（Message）

在 AgentScope 中，**Message** 是基础数据结构，用于：

- 在智能体之间交换信息
- 在用户界面中显示信息
- 在内存中存储信息
- 作为 AgentScope 与不同 LLM API 之间的统一媒介

消息由四个字段组成：

- **name**：消息发送者的名称/身份
- **role**：发送者的角色（`USER`、`ASSISTANT`、`SYSTEM` 或 `TOOL`）
- **content**：内容块列表（文本、图像、音频、视频、工具调用等）
- **metadata**：可选的元数据，用于结构化输出或附加信息

### 内容块

AgentScope 通过各种块类型支持多模态内容：

- **TextBlock**：纯文本内容
- **ImageBlock**：图像数据（URL 或 Base64）
- **AudioBlock**：音频数据（URL 或 Base64）
- **VideoBlock**：视频数据（URL 或 Base64）
- **ThinkingBlock**：推理模型的思考内容
- **ToolUseBlock**：工具调用请求
- **ToolResultBlock**：工具执行结果

示例：

```java
import io.agentscope.core.message.Msg;
import io.agentscope.core.message.MsgRole;
import io.agentscope.core.message.TextBlock;

Msg msg = Msg.builder()
    .name("Alice")
    .role(MsgRole.USER)
    .content(List.of(TextBlock.builder().text("你好，AgentScope！").build()))
    .build();
```

## 工具（Tool）

AgentScope 中的**工具**是指任何使用 `@Tool` 注解标记的 Java 方法，无论是：

- 实例方法
- 静态方法
- 同步或异步
- 流式或非流式

工具使用基于注解的发现机制注册：

```java
import io.agentscope.core.tool.Tool;
import io.agentscope.core.tool.ToolParam;

public class WeatherService {

    @Tool(description = "获取指定地点的当前天气")
    public String getWeather(
            @ToolParam(name = "location", description = "城市名称") String location) {
        // 实现
        return "晴天，25°C";
    }
}
```

**重要提示**：`@ToolParam` 注解需要显式的 `name` 属性，因为 Java 默认不会在运行时保留参数名称。

## 智能体（Agent）

在 AgentScope 中，**Agent** 接口定义了所有智能体的核心契约：

```java
public interface Agent {
    String getAgentId();
    String getName();
    Mono<Msg> call(Msg msg);
    Mono<Msg> call(List<Msg> msgs);
    Mono<Msg> call();
    // ... 其他方法
}
```

### 核心方法

- **call()**：处理输入消息并生成响应
- **stream()**：流式响应，实时更新
- **interrupt()**：中断智能体的执行

### ReActAgent

最重要的智能体实现是 **ReActAgent**（`io.agentscope.core.ReActAgent`），它使用 ReAct（推理 + 行动）算法：

- **reasoning()**：思考并通过调用 LLM 生成工具调用
- **acting()**：执行工具函数并收集结果

示例：

```java
import io.agentscope.core.ReActAgent;
import io.agentscope.core.model.DashScopeChatModel;

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

**Formatter** 是 AgentScope 中 LLM 兼容性的核心组件，负责：

- 将消息对象转换为 LLM API 所需的格式
- 处理提示词工程
- 消息验证和截断
- 多智能体（多身份）消息格式化

AgentScope 为不同的 LLM 提供商提供格式化器：

- **DashScopeFormatter**：用于阿里云百炼模型
- **OpenAIFormatter**：用于 OpenAI 兼容 API

格式化器根据您选择的模型自动选择。您无需显式指定。

## 钩子（Hook）

**Hook** 是扩展点，允许您在特定执行阶段自定义智能体行为。所有钩子实现一个统一的 `onEvent()` 方法，并使用 pattern matching 处理特定事件类型。

### 事件类型

AgentScope 提供以下事件类型：

**可修改事件**（可以修改以影响执行）：
- **PreReasoningEvent**：LLM 推理前
- **PostReasoningEvent**：推理完成后
- **PreActingEvent**：工具执行前
- **PostActingEvent**：工具执行后
- **PostCallEvent**：智能体调用完成后

**通知事件**（只读）：
- **PreCallEvent**：智能体调用开始时
- **ReasoningChunkEvent**：推理流式响应期间
- **ActingChunkEvent**：工具执行流式响应期间
- **ErrorEvent**：发生错误时

### 创建钩子

通过实现 `Hook` 接口并使用 pattern matching 创建钩子：

```java
import io.agentscope.core.hook.Hook;
import io.agentscope.core.hook.HookEvent;
import io.agentscope.core.hook.PreReasoningEvent;
import io.agentscope.core.hook.ReasoningChunkEvent;

Hook myHook = new Hook() {
    @Override
    public <T extends HookEvent> Mono<T> onEvent(T event) {
        return switch (event) {
            case PreReasoningEvent e -> {
                System.out.println("正在推理: " + e.getModelName());
                yield Mono.just(e);
            }
            case ReasoningChunkEvent e -> {
                // 显示流式输出
                System.out.print(e.getChunk().getTextContent());
                yield Mono.just(e);
            }
            default -> Mono.just(event);
        };
    }
};

ReActAgent agent = ReActAgent.builder()
    .name("Assistant")
    .model(model)
    .hooks(List.of(myHook))
    .build();
```

### 钩子优先级

钩子按优先级顺序执行（较小的值 = 更高的优先级）。默认为 100：

```java
Hook highPriorityHook = new Hook() {
    @Override
    public int priority() {
        return 10;  // 在默认钩子之前执行
    }

    @Override
    public <T extends HookEvent> Mono<T> onEvent(T event) {
        // 处理事件...
        return Mono.just(event);
    }
};
```

## 内存（Memory）

**Memory** 管理智能体的对话历史。AgentScope 提供：

- **InMemoryMemory**：简单的内存对话历史
- 自定义内存实现，用于高级场景

内存由 ReActAgent 等智能体自动管理，存储对话期间交换的所有消息。

## 响应式编程

AgentScope Java 基于 **Project Reactor** 构建，使用响应式类型进行异步操作：

- **Mono<T>**：发出 0 或 1 个项目的发布者
- **Flux<T>**：发出 0 到 N 个项目的发布者

这种设计支持：

- 非阻塞 I/O 操作
- 高效的资源利用
- 对流式响应的天然支持
- 可组合的异步管道

示例：

```java
// 非阻塞智能体调用
Mono<Msg> responseMono = agent.call(msg);

// 阻塞获取结果（用于测试或简单场景）
Msg response = responseMono.block();

// 或异步处理
responseMono.subscribe(response -> {
    System.out.println(response.getTextContent());
});
```

## 构建者模式

AgentScope 广泛使用**构建者模式**进行对象构造，提供：

- 类型安全的配置
- 可读和可维护的代码
- 带默认值的可选参数
- 构造后的不可变对象

示例：

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

AgentScope 将对象初始化与状态管理分离，允许对象恢复到不同状态：

- **saveState()**：将当前状态保存到 JSON 可序列化的映射
- **loadState()**：从保存的状态恢复对象

这支持：

- 对话持久化
- 智能体检查点
- 环境间的状态迁移

## 会话（Session）

**Session** 为状态组件提供跨应用运行的持久化存储。它允许您：

- 保存和恢复智能体状态、内存和其他组件
- 从中断处继续对话
- 在不同环境间迁移应用状态

Session 构建在状态管理之上，为统一管理多个组件提供更高层的 API。

### SessionManager

**SessionManager** 提供流式 API 进行会话操作：

- **forSessionId()**：为特定会话 ID 创建管理器
- **withJsonSession()**：配置 JSON 文件存储（默认实现）
- **addComponent()**：添加要管理的 StateModule 组件
- **saveSession()**：保存所有组件的当前状态
- **loadIfExists()**：如果会话存在则加载状态
- **sessionExists()**：检查会话是否已存储

示例：

```java
import io.agentscope.core.session.SessionManager;
import java.nio.file.Path;

// 保存会话
SessionManager.forSessionId("user123")
    .withJsonSession(Path.of("sessions"))
    .addComponent(agent)
    .addComponent(memory)
    .saveSession();

// 加载会话
SessionManager.forSessionId("user123")
    .withJsonSession(Path.of("sessions"))
    .addComponent(agent)
    .addComponent(memory)
    .loadIfExists();
```

### 多组件会话

会话可以同时管理多个组件，保留智能体、内存和其他状态对象之间的关系：

```java
ReActAgent agent = ReActAgent.builder()
    .name("Assistant")
    .model(model)
    .build();

InMemoryMemory memory = new InMemoryMemory();

// 智能体和内存一起保存
SessionManager.forSessionId("conversation-001")
    .withJsonSession(Path.of("./sessions"))
    .addComponent(agent)
    .addComponent(memory)
    .saveSession();
```

### JsonSession

**JsonSession** 是默认的会话实现，将状态作为 JSON 文件存储在文件系统中：

- 默认存储位置：`~/.agentscope/sessions/`
- 每个会话是一个以会话 ID 命名的 JSON 文件
- 自动创建目录
- UTF-8 编码，格式化输出

您也可以通过扩展 `SessionBase` 实现自定义会话后端（例如，数据库存储、云存储）。

## 管道（Pipeline）

**Pipeline** 提供多智能体工作流的组合模式：

- **SequentialPipeline**：智能体按顺序执行
- **FanoutPipeline**：多个智能体并行处理

示例：

```java
import io.agentscope.core.pipeline.Pipeline;
import io.agentscope.core.pipeline.Pipelines;

Pipeline pipeline = Pipelines.sequential(agent1, agent2, agent3);
Msg result = pipeline.call(inputMsg).block();
```

## 下一步

现在您已了解核心概念，可以：

- [学习消息](message.md) - 深入了解消息构造
- [构建您的第一个智能体](agent.md) - 创建一个可工作的智能体
- [探索工具](../task/tool.md) - 为智能体添加工具
- [使用钩子](../task/hook.md) - 自定义智能体行为

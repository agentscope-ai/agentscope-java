# Agent as Tool（子智能体工具）

```{admonition} 实验性功能
:class: warning

此功能目前处于实验阶段，API 可能会发生变化。如果您在使用过程中遇到问题，欢迎通过 [GitHub Issues](https://github.com/agentscope-ai/agentscope-java/issues) 反馈。
```

## 概述

Agent as Tool 允许将一个智能体注册为工具，供其他智能体调用。这种模式适用于构建层级式或协作式的多智能体系统：

- **专家分工**：主智能体根据任务类型调用不同的专家智能体
- **任务委托**：将复杂子任务委托给专门的智能体处理
- **多轮对话**：子智能体可以维护对话状态，支持连续交互

## 工作原理

当父智能体调用子智能体工具时，系统会：

1. **创建子智能体实例**：通过 Provider 工厂创建新的智能体实例
2. **恢复对话状态**：如果提供了 `session_id`，从 Session 中恢复之前的状态
3. **执行对话**：子智能体处理消息并生成回复
4. **保存状态**：将子智能体的状态保存到 Session，供后续调用恢复
5. **返回结果**：将回复和 `session_id` 返回给父智能体

```
父智能体 ──调用──→ SubAgentTool ──创建──→ 子智能体
                      │                    │
                      │←──── 返回结果 ─────┘
                      │
                   Session（状态持久化）
```

## 快速开始

```java
import io.agentscope.core.ReActAgent;
import io.agentscope.core.tool.Toolkit;
import io.agentscope.core.model.DashScopeChatModel;

// 创建模型
DashScopeChatModel model = DashScopeChatModel.builder()
        .apiKey(System.getenv("DASHSCOPE_API_KEY"))
        .modelName("qwen-plus")
        .build();

// 创建子智能体的 Provider（工厂）
// 注意：必须使用 lambda 表达式，确保每次调用创建新实例
Toolkit toolkit = new Toolkit();
toolkit.registration()
        .subAgent(() -> ReActAgent.builder()
                .name("Expert")
                .sysPrompt("你是一个领域专家，负责回答专业问题。")
                .model(model)
                .build())
        .apply();

// 创建主智能体，配置工具
ReActAgent mainAgent = ReActAgent.builder()
        .name("Coordinator")
        .sysPrompt("你是一个协调员。当遇到专业问题时，调用 call_expert 工具咨询专家。")
        .model(model)
        .toolkit(toolkit)
        .build();

// 主智能体会在需要时自动调用专家智能体
Msg response = mainAgent.call(userMsg).block();
```

## 配置选项

通过 `SubAgentConfig` 自定义子智能体工具的行为：

```java
import io.agentscope.core.tool.subagent.SubAgentConfig;
import io.agentscope.core.session.JsonSession;
import java.nio.file.Path;

SubAgentConfig config = SubAgentConfig.builder()
        .toolName("ask_expert")                    // 自定义工具名称
        .description("向专家咨询问题")              // 自定义描述
        .forwardEvents(true)                       // 转发子智能体事件
        .session(new JsonSession(Path.of("sessions")))  // 持久化会话
        .build();

toolkit.registration()
        .subAgent(() -> createExpertAgent(), config)
        .apply();
```

| 配置项 | 说明 | 默认值 |
|--------|------|--------|
| `toolName` | 工具名称 | 从智能体名称生成，如 `call_expert` |
| `description` | 工具描述 | 使用智能体的 description |
| `forwardEvents` | 是否转发子智能体的流式事件 | `true` |
| `session` | 会话存储实现 | `InMemorySession`（内存存储） |

## 多轮对话

子智能体支持多轮对话，通过 `session_id` 参数维护对话状态：

```java
// 第一次调用：不传 session_id，开始新会话
// 工具返回格式：
// session_id: abc-123-def
//
// 专家回复内容...

// 后续调用：传入 session_id，继续之前的对话
// 父智能体会自动从上次返回中提取 session_id 并传入
```

子智能体工具暴露两个参数：
- `message`（必填）：发送给子智能体的消息
- `session_id`（可选）：会话 ID，省略则开始新会话，提供则继续已有会话

## 持久化会话

默认使用内存存储（`InMemorySession`），进程重启后状态丢失。使用 `JsonSession` 可将状态持久化到文件：

```java
import io.agentscope.core.session.JsonSession;
import java.nio.file.Path;

SubAgentConfig config = SubAgentConfig.builder()
        .session(new JsonSession(Path.of("./agent-sessions")))
        .build();

toolkit.registration()
        .subAgent(() -> createAgent(), config)
        .apply();

// 状态将保存在 ./agent-sessions/{session_id}.json
```

## 工具组支持

子智能体工具可以像普通工具一样加入工具组：

```java
toolkit.createToolGroup("experts", "专家智能体", true);

toolkit.registration()
        .subAgent(() -> createLegalExpert())
        .group("experts")
        .apply();

toolkit.registration()
        .subAgent(() -> createTechExpert())
        .group("experts")
        .apply();
```

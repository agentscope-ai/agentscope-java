---
title: "Context"
description: "管理 agent 的工作记忆与会话状态"
---

## 概述

Context 是 agent 的工作记忆 —— LLM 在每一步推理时看到的全部消息（用户输入、assistant 回复、工具调用、工具结果）。AgentScope Java 通过以下三套机制让 agent 在长任务中持续运行：

- **`AgentState.getContext()`** —— agent 的对话历史，按消息列表存放，框架自动追加新轮次。
- **`Session` + `SessionKey`** —— 把整份 `AgentState` 持久化到外部存储（内置 `InMemorySession`、`JsonSession`），让 agent 跨进程恢复执行。
- **`RuntimeContext`** —— per-call 元数据：`userId`、`sessionId`、任意 string-keyed 或 typed 属性，hook 与 tool 在本次调用期间可读写同一上下文，**调用结束后不持久化**。

每次模型调用前，agent 会把以下结构拼成单次 API 输入：

```text
Model API Input/
├── 基础 system prompt（Builder 上的 sysPrompt）
├── Skill 指令（来自 Toolkit）
└── onSystemPrompt middleware 转换
```

每一层的构成方式：

1. **System prompt** —— 以创建 agent 时传入的 `sysPrompt` 为起点，拼接 skill 指令（每个 skill 的名称与描述，来自 toolkit），再依次执行所有 `onSystemPrompt` [middleware](/zh/v2/building-blocks/middleware) 钩子。
2. **Context** —— `AgentState.getContext()` 持有的消息列表，覆盖用户输入、assistant 回复、工具调用、工具结果。

:::{tip}
通过 `onSystemPrompt` middleware 钩子注入动态上下文 —— 工作目录指令、时效性信息、环境细节 —— 无需改写基础 prompt。完整示例见 `agentscope-examples/documentation/.../middleware/SystemPromptMiddlewareExample.java`。
:::

:::{note}
Python 端的 `ContextConfig` 自动压缩、`Offloader` 工具结果卸载等机制在 Java 2.0 中尚未实现；`maxIters` 与 `reactConfig` 提供了基础的循环上限，避免长任务无界扩张。
:::

## 使用 RuntimeContext 传递 per-call 元数据

`RuntimeContext`（位于 `io.agentscope.core.agent`）是一个轻量的元数据容器，在 `agent.call(msgs, runtimeContext)` 中传入，hook 与 tool 在本次调用期间共享。

```java
import io.agentscope.core.agent.RuntimeContext;
import io.agentscope.core.message.Msg;
import io.agentscope.core.message.UserMessage;
import java.util.List;

RuntimeContext ctx =
        RuntimeContext.builder()
                .userId("alice")
                .sessionId("s-001")
                .put("request_id", "req-2026-06-01-abc")
                .put(MyTenantInfo.class, new MyTenantInfo("tenant-7"))
                .build();

Msg result = agent.call(List.of(new UserMessage("Hi")), ctx).block();

// 访问：
String reqId = ctx.get("request_id");
MyTenantInfo info = ctx.get(MyTenantInfo.class);
```

可用的存取方法：

| 方法 | 说明 |
|------|------|
| `getSessionId()` / `getUserId()` / `getSession()` / `getSessionKey()` | 内置字段 |
| `get(String)` / `put(String, Object)` | 字符串键存取（legacy 风格） |
| `get(Class<T>)` / `put(Class<T>, T)` | 按类型存取（typed singleton） |
| `get(String, Class<T>)` / `put(String, Class<T>, T)` | 同时按 key + 类型隔离 |
| `getExtra()` | 直接拿到字符串属性 map（可变视图） |
| `RuntimeContext.empty()` | 空上下文（不持久化、所有字段为空） |

`RuntimeContext` 不参与持久化；要在调用之间留存数据，请用下文的 `Session`。

## 使用 Session 持久化对话历史

`Session` 接口（位于 `io.agentscope.core.session`）抽象了状态存储，按 `SessionKey` 分桶。AgentScope 内置：

| 实现 | 说明 |
|------|------|
| `InMemorySession` | 进程内 Map，适合单元测试 / 临时调试 |
| `JsonSession` | 文件系统 JSON 持久化，按 key 分目录 |

把 `Session` 与 `SessionKey` 配置到 builder 后，agent 在每次 `call` 完成后会把 `AgentState`（含 `context`、`tasksContext`、`permissionContext` 等）写回；启动时如果对应 key 已有数据，则自动加载。

```java
import io.agentscope.core.ReActAgent;
import io.agentscope.core.formatter.dashscope.DashScopeChatFormatter;
import io.agentscope.core.message.Msg;
import io.agentscope.core.message.UserMessage;
import io.agentscope.core.model.DashScopeChatModel;
import io.agentscope.core.session.JsonSession;
import io.agentscope.core.session.Session;
import io.agentscope.core.state.SimpleSessionKey;
import io.agentscope.core.tool.Toolkit;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.List;

Path sessionDir = Paths.get(System.getProperty("user.home"), ".agentscope", "sessions");
Session session = new JsonSession(sessionDir);

ReActAgent agent =
        ReActAgent.builder()
                .name("my_agent")
                .sysPrompt("...")
                .model(
                        DashScopeChatModel.builder()
                                .apiKey(System.getenv("DASHSCOPE_API_KEY"))
                                .modelName("qwen-max")
                                .stream(true)
                                .formatter(new DashScopeChatFormatter())
                                .build())
                .toolkit(new Toolkit())
                .session(session)
                .sessionKey(SimpleSessionKey.of("user-alice:demo"))
                .build();

// 启动时若 key 已存在，会自动加载到 AgentState
int loaded = agent.getState().getContext().size();
System.out.println("loaded " + loaded + " message(s)");

Msg result = agent.call(List.of(new UserMessage("继续之前的任务。"))).block();
// 调用完成时自动持久化
```

`SimpleSessionKey` 接受单一 ID 字符串；如需 `(userId, agentId, sessionId)` 这样的多维分桶，自定义实现 `SessionKey` 接口即可（参考 `SessionKey.java` javadoc 示例）。完整运行示例：`agentscope-examples/documentation/.../session/SessionExample.java`、`session/SessionAutoSaveExample.java`、`context/RuntimeContextExample.java`。

## 直接读写 AgentState

`AgentState`（位于 `io.agentscope.core.state`）持有 agent 的全部可恢复状态。常用入口：

| 方法 | 说明 |
|------|------|
| `getContext()` | 当前对话历史（不可变视图） |
| `contextMutable()` | 可写入的视图，谨慎使用 |
| `setSummary(...)` / `getSummary()` | 自定义压缩摘要（如果你自己实现压缩 middleware） |
| `getPermissionContext()` | 权限上下文，参见 [权限系统](/zh/v2/building-blocks/permission-system) |
| `getTasksContext()` | TodoTools 维护的任务列表 |
| `toJson()` / `fromJsonString(String)` | JSON 序列化与反序列化 |

```java
import io.agentscope.core.state.AgentState;

AgentState state = agent.getState();
System.out.println("messages: " + state.getContext().size());
String json = state.toJson();
AgentState restored = AgentState.fromJsonString(json);
```

:::{note}
1.0 中的 `Memory` 接口（`InMemoryMemory` / `LongTermMemory` 等）在 2.0 已 `@Deprecated(forRemoval = true)`。新代码请使用 `AgentState.getContext()` + `Session` 组合 —— `Memory` 仅作为源代码兼容层保留。
:::

## 延伸阅读

::::{grid} 2

:::{grid-item-card} Agent
:link: /zh/v2/building-blocks/agent

ReAct 循环以及上下文如何在推理步骤间流转
:::
  :::{grid-item-card} Middleware
:link: /zh/v2/building-blocks/middleware

通过 middleware 钩子拦截模型调用与 system prompt 组装
:::
  :::{grid-item-card} Tool
:link: /zh/v2/building-blocks/tool

会写入上下文的工具调用与工具结果
:::
  :::{grid-item-card} Permission System
:link: /zh/v2/building-blocks/permission-system

AgentState.permissionContext 中保存的运行时规则
:::

::::

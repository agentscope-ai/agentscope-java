---
title: "上下文与会话"
description: "管理 agent 跨调用、跨进程、跨用户的工作记忆"
---

Context 是 agent 的工作记忆 —— LLM 在每一步推理时看到的全部消息（用户输入、assistant 回复、工具调用、工具结果）。AgentScope Java 通过以下三套机制让 agent 在长任务中持续运行：

- **`AgentState.getContext()`** —— agent 的对话历史，按消息列表存放，框架自动追加新轮次。
- **`Session` + `SessionKey`** —— 把整份 `AgentState` 持久化到外部存储（内置 `InMemorySession`、`JsonSession`），让 agent 跨进程恢复执行。
- **`RuntimeContext`** —— per-call 元数据：`userId`、`sessionId`、任意 string-keyed 或 typed 属性，hook 与 tool 在本次调用期间可读写同一上下文，**调用结束后不持久化**。

## 模型 API 输入怎么拼

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

## RuntimeContext：per-call 元数据

`RuntimeContext`（位于 `io.agentscope.core.agent`）是一个轻量容器，在 `agent.call(msgs, runtimeContext)` 中传入，hook 与 tool 在本次调用期间共享。

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
| `get(String)` / `put(String, Object)` | 字符串键存取 |
| `get(Class<T>)` / `put(Class<T>, T)` | 按类型存取（typed singleton） |
| `get(String, Class<T>)` / `put(String, Class<T>, T)` | 同时按 key + 类型隔离 |
| `getExtra()` | 直接拿到字符串属性 map（可变视图） |
| `RuntimeContext.empty()` | 空上下文（不持久化、所有字段为空） |

`RuntimeContext` 不参与持久化；要在调用之间留存数据，请用 `Session`。

## Session：跨进程持久化

每次 `call()` 时传一个稳定的 `sessionId`，agent 就能在重启、节点切换、不同进程里接着之前的对话往下走。**默认就开着**。

```java
RuntimeContext ctx = RuntimeContext.builder()
    .sessionId("alice-2026-06-02-001")
    .userId("alice")
    .build();

agent.call(msg,  ctx).block();   // 第一次：从零开始
agent.call(msg2, ctx).block();   // 第二次（无论是不是同一进程）：自动接着第一次
```

### 一次 `call()` 会落盘什么

每次 `call()` 结束自动产生两路输出：

1. **运行时快照** —— agent 的所有可恢复状态（对话上下文、权限规则、Plan Mode 状态、工具状态等）整体序列化到 `agents/<agentId>/context/<sessionId>/`。下次同 `sessionId` 的 `call()` 自动加载回去。
2. **完整对话日志** —— 永不压缩、append-only 的 `agents/<agentId>/sessions/<sessionId>.log.jsonl`，给审计与 `session_search` 用。

两条独立路径，互不覆盖。

### 默认布局

```
workspace/agents/<agentId>/
├── context/<sessionId>/         运行时快照（agent 自动写）
│   ├── agent.json
│   └── *.json
└── sessions/
    ├── sessions.json            会话索引
    ├── <sessionId>.jsonl        LLM 可见的压缩上下文
    └── <sessionId>.log.jsonl    完整对话日志（永不压缩）
```

如果你不传任何 `session(...)` 配置，框架默认用工作区里的这个布局。

### 配置 Session

内置实现：

| 实现 | 说明 |
|---|---|
| `InMemorySession` | 进程内 Map，适合单元测试 / 临时调试 |
| `JsonSession` | 文件系统 JSON 持久化，按 key 分目录 |

只想换个目录：

```java
HarnessAgent.builder()
    .session(new JsonSession(Path.of("/custom/sessions")))
    .build();
```

需要分布式（多 pod 共享）—— 换成 Redis 等分布式实现：

```java
HarnessAgent.builder()
    .session(myRedisSession)
    .build();
```

调用时临时覆盖：

```java
agent.call(msg, RuntimeContext.builder()
    .sessionId("sess-001")
    .session(customSession)
    .build()).block();
```

非 `HarnessAgent` 场景下也可以直接在 `ReActAgent.Builder` 上配 `.session(session).sessionKey(SimpleSessionKey.of("user-alice:demo"))`。`SimpleSessionKey` 接受单一 ID 字符串；如需 `(userId, agentId, sessionId)` 这样的多维分桶，自定义实现 `SessionKey` 接口。

### 多用户隔离

`sessionId` 和 `userId` 解决的不是同一件事：

- **`sessionId`** —— 决定哪段对话是哪段，独立的运行时快照
- **`userId`** —— 决定文件落到谁的命名空间下（详见 [文件系统](./filesystem)）

```java
agent.call(msg, RuntimeContext.builder()
    .sessionId("alice-1").userId("alice").build()).block();

agent.call(msg, RuntimeContext.builder()
    .sessionId("bob-1").userId("bob").build()).block();
```

两个用户的对话状态、文件路径互不干扰。

:::{warning}
**已知限制**：默认的工作区 Session 实现，运行时快照的目录不会按 `userId` 自动分桶。多租户部署如需 `AgentState` 级的用户隔离，请使用支持分布式键的 Session 实现（例如 Redis），把 `userId` 编码进 key；或者自定义一个不依赖运行时上下文的命名空间策略。
:::

### 关掉自动持久化

`disableSessionPersistence()` 在 2.0 里是个 **no-op**（不再生效）。想真正不持久化：构建时不传 `session(...)`，或者每次 `call()` 时不传 `sessionId`——没有 sessionId 框架就不知道要写到哪里。

### 用 agent 自己查历史会话

启用会话能力时（默认开），三个查询工具会自动注册：

- `session_list agentId="..."` —— 列出某个 agent 的历史会话
- `session_history agentId="..." sessionId="..." lastN=20` —— 看某次会话最近 N 条消息
- `session_search query="..." agentId="..."` —— 在历史会话里关键词搜索

## 直接读写 AgentState

`AgentState`（位于 `io.agentscope.core.state`）持有 agent 的全部可恢复状态。

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

## 相关文档

- [架构](./architecture) — Context、Session、工作区在一次 call 内如何协作
- [记忆](./memory) — 长期记忆、对话压缩、大结果卸载
- [文件系统](./filesystem) — `userId` 多租户路径隔离
- [子 Agent](./subagent) — 子 agent 会话与父 agent 如何区分

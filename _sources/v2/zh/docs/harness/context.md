---
title: "上下文与会话"
description: "Agent 运行时状态的持久化、跨机恢复,以及上下文长度的多重压缩策略"
---

HarnessAgent 在两条互相配合的链路上管理工作记忆:

1. **Session — 把 agent 的运行时状态持久化下来**,让同一个 `sessionId` 能在不同进程、不同机器上接着上次执行。
2. **Context 压缩 — 让对话上下文不至于把模型的 token 预算吃光**,在不丢失关键信息的前提下尽量延长一次会话的有效寿命。

两条链路通过同一个数据结构 `AgentState` 串起来:压缩在内存里更新它,Session 把它落到外部存储。下面分两部分展开。

---

## 一、Session:Agent 运行时状态的持久化

### Session 装的是什么 —— AgentState

Session 持久化的是一份 **`AgentState`**(`io.agentscope.core.state.AgentState`),它是 agent 当前"瞬时"运行状态的完整快照:

| `AgentState` 字段 | 内容 |
|---|---|
| `getContext()` / `contextMutable()` | 当前对话历史(用户输入、assistant 回复、工具调用、工具结果) |
| `getSummary()` | 压缩后的摘要(如果开了压缩) |
| `getPermissionContext()` | 工具权限规则,见[权限系统](../building-blocks/permission-system.md) |
| `getPlanModeContext()` | Plan Mode 当前是否激活、计划文件路径 |
| `getTasksContext()` | `todo_write` 维护的任务清单 |
| `getToolContext()` | 工具组激活状态(`activatedGroups`) |

一次 `call()` 结束,框架自动把整份 `AgentState` 以 `agent_state` 这个键写进 Session。下次同 `sessionId` 的 `call()` 在构造 agent 时会优先从 Session 读回——**只要 Session 后端是分布式的(例如 Redis),不同进程、不同物理机上的 agent 实例都能拿到完全一致的状态**。

### 自动持久化与恢复链路

```
agent 启动 ─► loadOrCreateAgentState(session, sessionKey)
              │
              ├─ Session 里有 agent_state ─► 直接还原
              └─ 没有 ─► 构造一份空 AgentState

agent.call() 进行中 ─► 中间件就地改写 AgentState.contextMutable()
                       (压缩、Plan、todo_write、权限调整……都在改它)

进程退出 / interrupt ─► shutdownManager 触发
                       session.save(sessionKey, "agent_state", state)
```

这套机制是 **`ReActAgent` 自带**的(`ReActAgent.java` 构造函数与 `shutdownManager.bindStateSaver`),`HarnessAgent` 直接继承,无需额外配置。

> 单次 `call()` 期间的中间状态变更靠的是内存里的 `AgentState` 对象。**Session 不在每条消息后落盘,而是在 call 结束 / shutdown 时整体写入**——所以对 Session 后端的吞吐压力很低。

### 内置与扩展实现

只要实现 `io.agentscope.core.session.Session` 接口,任何后端都能接进来。选择哪一种,取决于你的部署形态:

| 实现 | 模块 | 适用场景 |
|---|---|---|
| `InMemorySession` | `agentscope-core` | 单元测试 / 单进程演示;进程退出全部丢失 |
| `JsonSession` | `agentscope-core` | 单机开发、文件落盘即可恢复;不能跨节点共享 |
| `WorkspaceSession` | `agentscope-harness` | `HarnessAgent` 默认值,基于 `JsonSession`,把状态写到 `<workspace>/agents/<agentId>/context/<sessionId>/`;**单机单租户** |
| `RedisSession` | `agentscope-extensions-session-redis` | **生产首选**,多副本共享;支持 Jedis / Lettuce / Redisson(Standalone / Cluster / Sentinel) |
| `MysqlSession` | `agentscope-extensions-session-mysql` | 需要把会话数据沉淀进关系型库(审计、报表)时使用 |

切换非常简单——只在构造期 `.session(...)` 一次:

```java
// 默认(单机):省略 .session(...) 即可,自动用 WorkspaceSession
HarnessAgent agent = HarnessAgent.builder()
    .name("MyAgent")
    .model(model)
    .workspace(workspace)
    .build();

// 多副本生产:换成 RedisSession
RedisClient client = RedisClient.create("redis://redis.prod:6379");
HarnessAgent agent = HarnessAgent.builder()
    .name("MyAgent")
    .model(model)
    .workspace(workspace)
    .session(RedisSession.builder().lettuceClient(client).build())
    .build();
```

:::{warning}
`WorkspaceSession` 仅适合单机。如果你已经在用 `filesystem(SandboxFilesystemSpec)` 或 `filesystem(RemoteFilesystemSpec)`(分布式工作区),HarnessAgent 会**强制要求** Session 也换成分布式后端,否则 `build()` 直接抛 `IllegalStateException`——因为 sandbox 状态必须跨副本共享。
:::

### 同 sessionId 跨进程、跨机器实时恢复

只要 Session 后端是分布式的(例如 Redis),这一切就是**自动**的:

```java
// 节点 A:开了一段对话
HarnessAgent agentA = HarnessAgent.builder()
    .session(redisSession)
    /* ... */ .build();
agentA.call(msg, RuntimeContext.builder()
    .sessionId("alice-2026-06-02-001")
    .userId("alice")
    .build()).block();

// 节点 B:不同物理机,完全独立的 JVM
HarnessAgent agentB = HarnessAgent.builder()
    .session(redisSession)
    /* 同一份 sessionId,同一份 sessionKey */ .build();

// 节点 B 第一次 call() 会自动从 Redis 拉到节点 A 之前留下的 AgentState
agentB.call(nextMsg, RuntimeContext.builder()
    .sessionId("alice-2026-06-02-001")
    .userId("alice")
    .build()).block();
```

这意味着:

- **故障转移**:节点崩了,会话漂到另一个节点,用户感知不到。
- **滚动发布**:旧 pod 退出前 `shutdownManager` 自动保存,新 pod 接到流量时自动从 Session 还原,**对话不会断**。
- **跨场景接续**:在 Web UI 里和 agent 聊到一半,切换到 CLI 工具继续聊——只要 `sessionId` 一致,记忆都在。

`SessionKey` 接口决定写入键的命名空间。默认 `SimpleSessionKey.of(sessionId)` 就够用;需要 `(tenantId, userId, agentId, sessionId)` 这种多维分桶时,实现自己的 `SessionKey`。

### 多用户隔离

`sessionId` 和 `userId` 解决的不是同一件事:

- **`sessionId`** —— 决定哪段对话是哪段,独立的 `AgentState` 快照。
- **`userId`** —— 决定文件落到谁的命名空间下,详见[文件系统](./filesystem)。

```java
agent.call(msg, RuntimeContext.builder()
    .sessionId("alice-1").userId("alice").build()).block();

agent.call(msg, RuntimeContext.builder()
    .sessionId("bob-1").userId("bob").build()).block();
```

两个用户的对话状态与文件路径互不干扰。生产部署如果想做 `AgentState` 级别的用户隔离,首选把 `userId` 编码进 `SessionKey`(配合 `RedisSession` 时它就是 Redis key 的一部分),而不是依赖文件路径分桶。

### 直接读写 AgentState

需要旁路操作(例如管理台、审计、批量迁移)时,可以直接拿:

```java
import io.agentscope.core.state.AgentState;

AgentState state = agent.getAgentState();
System.out.println("messages: " + state.getContext().size());

String json = state.toJson();
AgentState restored = AgentState.fromJsonString(json);
```

| 方法 | 说明 |
|------|------|
| `getContext()` | 当前对话历史(不可变视图) |
| `contextMutable()` | 可写入视图,谨慎使用 |
| `setSummary(...)` / `getSummary()` | 自定义压缩摘要(自行实现压缩 middleware 时用) |
| `toJson()` / `fromJsonString(String)` | 序列化与反序列化 |

:::{note}
1.0 中的 `Memory` 接口(`InMemoryMemory` / `LongTermMemory` 等)在 2.0 已 `@Deprecated(forRemoval = true)`。新代码请使用 `AgentState.getContext()` + `Session` —— `Memory` 仅作为源代码兼容层保留。
:::

### 用 agent 自己查历史会话

启用会话能力时(默认开),三个查询工具会自动注册,agent 自己就能调:

- `session_list agentId="..."` —— 列出某个 agent 的历史会话。
- `session_history agentId="..." sessionId="..." lastN=20` —— 看某次会话最近 N 条消息。
- `session_search query="..." agentId="..."` —— 在历史会话里关键词搜索。

这些工具读的是**永不压缩的对话日志**(`<workspace>/agents/<agentId>/sessions/<sessionId>.log.jsonl`),所以即使上下文已经被压缩成摘要,agent 也能查到原始消息。

---

## 二、Context 上下文压缩策略

LLM 的 token 预算是有限的。一段对话越跑越长,要么主动压缩、要么撞到模型的硬上限报错。`HarnessAgent` 内置了一整套压缩链路,默认是关的,按需 `.compaction(...)` 或 `.toolResultEviction(...)` 开启。

**核心规则**:**压缩在内存里更新 `AgentState`,Session 在 call 结束时把更新后的 `AgentState` 整体落盘**——也就是说,压缩与持久化是两条独立但互相支撑的路径。压缩永远先于落盘,Session 永远拿到的是压缩后的版本。

### HarnessAgent 内置的几种策略

| 策略 | 解决的问题 | 触发时机 | 中间件 |
|------|----------|----------|--------|
| **对话摘要压缩** | 上下文太"深"——消息条数 / token 累计太多 | 每次模型推理前 | `CompactionMiddleware` |
| **大工具结果卸载** | 上下文太"宽"——单条工具结果体量过大 | 工具执行后 | `ToolResultEvictionMiddleware` |
| **上下文溢出兜底** | 真的撞到模型 `context_length_exceeded` | `call()` 抛错时 | `HarnessAgent.recoverFromOverflow` |
| **预压缩参数截断** | 工具调用参数(write_file 的内容)体量大但后期没人看 | 摘要之前的轻量预处理 | `CompactionConfig.TruncateArgsConfig` |

四套策略**正交,可以任意组合**,默认全部不开。

### 1. 对话摘要压缩 (`CompactionMiddleware`)

按消息条数或估算 token 触发,把对话**前缀**用一次 LLM 调用压成结构化摘要,**保留尾部 N 条最近消息原文**,然后把 `[summary] + [recent tail]` 写回 `AgentState.contextMutable()`。

```java
HarnessAgent.builder()
    .compaction(CompactionConfig.builder()
        .triggerMessages(30)     // 30 条触发
        .keepMessages(10)        // 压缩后保留最近 10 条原文
        .build())
    .build();
```

默认摘要 prompt 会把内容组织成 `SESSION INTENT / SUMMARY / ARTIFACTS / NEXT STEPS` 四个小节,适合工程/编排类 agent。完整字段表(`triggerTokens`、`keepTokens`、`flushBeforeCompact`、`offloadBeforeCompact`、`TruncateArgsConfig`)与摘要 prompt 模板在[记忆](./memory#开启压缩)文档里有详细列表,这里不重复。

### 2. 大工具结果卸载 (`ToolResultEvictionMiddleware`)

跟摘要压缩独立。当某条工具结果文本超过阈值(默认 80K 字符 ≈ 20K tokens),把全文写到工作区某个目录,上下文里**只保留首尾各约 2K 字符 + 一个 `read_file` 路径提示符**。agent 想看全文就自己 `read_file`。

```java
HarnessAgent.builder()
    .toolResultEviction(ToolResultEvictionConfig.defaults())
    .build();
```

默认排除 `read_file` / `write_file` / `edit_file` / `grep_files` / `glob_files` / `list_files` / `memory_*` / `session_search`——这些工具要么自带分页、要么返回值很小。**Shell `execute` 默认不排除**,因为命令输出可能非常大。

详情见[记忆 - 大工具结果卸载](./memory#大工具结果卸载)。

### 3. 上下文溢出兜底

如果模型直接返回 `context_length_exceeded` / `maximum context` / `token limit` 等错误,`HarnessAgent.recoverFromOverflow()` 会强制走一次 `triggerMessages=1` 的极端压缩,然后**自动重试一次**。前提是构造 agent 时配了 `.compaction(...)`,否则错误原样抛回上层。

这条兜底链路无需额外配置:只要 `compaction` 开了,溢出恢复就自动开。

### 4. 预压缩参数截断 (可选)

在 LLM 摘要前,先做一遍**不走 LLM** 的字符串截断——`write_file`、`edit_file` 这类工具的入参体量大但事后没人再看:

```java
CompactionConfig.builder()
    .triggerMessages(80)
    .truncateArgs(CompactionConfig.TruncateArgsConfig.builder()
        .maxArgLength(2000)
        .truncationText("... [truncated] ...")
        .build())
    .build();
```

很多场景下,光这一步就能把触发摘要的频率压下来一大截,几乎零成本。

### 压缩与 Memory 的联动

`CompactionConfig.flushBeforeCompact`(默认 `true`)决定**摘要发生前是否先把对话前缀里的事实抽取到长期记忆(Memory)中**——这一步由 `MemoryFlushMiddleware` + `MemoryFlushManager` 完成,会读 `<workspace>/MEMORY.md` 与 `memory/*.md`,把新事实增量写进去。等会儿摘要丢掉前缀消息时,信息不会随之消失——agent 仍可以通过 `memory_search` / `memory_get` 工具回头查。

类似地,`offloadBeforeCompact`(默认 `true`)在摘要前把**原始消息**整段写到永不压缩的 `*.log.jsonl`,供 `session_search` 检索。

> Memory 子系统的完整工作机制——双层结构、后台维护任务(归档、合并)、记忆工具——见 [记忆](./memory) 文档。压缩与 memory 是一对常常一起用的组件,但有各自独立的开关。

### 压缩不会触碰的内容

`ConversationCompactor` 只处理 `AgentState.contextMutable()` 里的**对话消息列表**。下面这些活在 `AgentState` 其他字段里,**完全不会被摘要压缩波及**:

- **Plan Mode 状态**(`AgentState.getPlanModeContext()`):是否在 plan 阶段、当前计划文件路径。计划文件本身在工作区 `plans/` 下,生命周期由 Plan Mode 自己管理。详见 [Plan Mode](./plan-mode)。
- **子 agent 后台任务**(`task_id`、状态、结果):住在 `<workspace>/agents/<parentAgentId>/tasks/<sessionId>.json` 里,由 `TaskRepository` 单独维护;主 agent 下一轮推理前通过 system reminder 反向注入完成结果,**不进入对话消息流**,所以摘要也无从压缩。详见 [子 Agent - 异步任务的存储位置](./subagent#异步任务的存储位置)。
- **`todo_write` 任务清单**(`AgentState.getTasksContext()`):独立字段,跟着 `AgentState` 一起持久化,但不参与对话压缩。详见 [Plan Mode - 与 `todo_write` 的协作](./plan-mode#与-todo_write-的协作)。
- **权限规则**(`getPermissionContext()`):独立字段,自带持久化。

这些组件各有自己的状态机和恢复机制,压缩通路对它们是透明的——你可以放心开启 `.compaction(...)` 而不用担心丢 plan / 丢未完成的后台 task。

---

## 附:`RuntimeContext` —— per-call 元数据

`RuntimeContext`(位于 `io.agentscope.core.agent`)是一个轻量容器,在 `agent.call(msgs, ctx)` 中传入,hook 与 tool 在本次调用期间共享。**不持久化、不参与 Session**。

```java
import io.agentscope.core.agent.RuntimeContext;

RuntimeContext ctx = RuntimeContext.builder()
        .userId("alice")
        .sessionId("s-001")
        .put("request_id", "req-2026-06-01-abc")
        .put(MyTenantInfo.class, new MyTenantInfo("tenant-7"))
        .build();

Msg result = agent.call(List.of(new UserMessage("Hi")), ctx).block();
```

可用字段:

| 方法 | 说明 |
|------|------|
| `getSessionId()` / `getUserId()` / `getSessionKey()` | 内置字段,用于路由会话与租户 |
| `get(String)` / `put(String, Object)` | 字符串键存取 |
| `get(Class<T>)` / `put(Class<T>, T)` | 按类型存取(typed singleton) |
| `getExtra()` | 直接拿到字符串属性 map(可变视图) |
| `RuntimeContext.empty()` | 空上下文 |

:::{tip}
**Session 后端在 builder 时绑定,不能通过 RuntimeContext per-call 切换**。要按用户隔离 Session,用 `userId` + `SessionKey`(或自定义 `keyPrefix`),不要试图给每次 call 传不同的 Session 实例。
:::

---

## 相关文档

- [架构](./architecture) —— Context、Session、工作区在一次 call 内如何协作
- [记忆](./memory) —— 长期记忆、对话压缩的详细配置、大工具结果卸载、后台维护
- [Plan Mode](./plan-mode) —— plan 状态的独立持久化与恢复
- [子 Agent](./subagent) —— 后台任务的存储位置与跨节点恢复
- [文件系统](./filesystem) —— `userId` 多租户路径隔离
- [权限系统](../building-blocks/permission-system.md) —— 权限规则的持久化

---
title: "上下文与 AgentState"
description: "无状态 Agent 引擎、AgentState 生命周期、状态持久化与 RuntimeContext"
---

## 无状态 Agent 引擎

`ReActAgent`(以及封装它的 `HarnessAgent`)采用**无状态引擎**设计:agent 实例本身只持有不可变的配置——system prompt、模型、工具集、中间件链——而所有 per-session 的可变数据都放在 `AgentState` 里,以 `(userId, sessionId)` 为索引。一个 agent 实例可以同时服务多个用户和会话,调用方只需在每次 `call()` 时传入不同的 `RuntimeContext`。

```
┌──────────────────────────────────────────────────────────────────┐
│                     HarnessAgent (单例)                          │
│  不可变配置: sysPrompt, model, toolkit, middlewares               │
│                                                                  │
│  ┌─ state cache ─────────────────────────────────────────────┐   │
│  │  ("alice","s1") → AgentState  ← call(…, RC(alice,s1))       │
│  │  ("bob","s2")   → AgentState  ← call(…, RC(bob,s2))        │
│  └───────────────────────────────────────────────────────────┘   │
│                                                                  │
│  per-session 门: 同 (uid,sid) 串行, 不同 (uid,sid) 并行           │
└──────────────────────────────────────────────────────────────────┘
```

### 这意味着什么

- **不需要 agent-per-user 注册表。** 一个 `HarnessAgent` 实例就能服务全部用户——每次请求只需传入不同的 `RuntimeContext.userId` 和 `RuntimeContext.sessionId`。
- **并发天然支持。** 不同 `(userId, sessionId)` 的请求完全并行;相同 `(userId, sessionId)` 的请求自动串行,确保对话一致性。
- **状态完全内部化。** Agent 在 call 入口从存储加载 `AgentState`,call 退出时自动保存——调用方不需要直接管理 state 对象。
- **per-call 隔离。** 每次 `call()` 使用自己的 `AgentState` 快照。中间件和工具通过 `RuntimeContext.getAgentState()`(由框架在 call 入口注入)访问本次调用的状态,并发 call 之间互不可见。

---

## AgentState

[`AgentStateStore`](../../integration/session/index.md) 持久化的是一份 **`AgentState`**(`io.agentscope.core.state.AgentState`),它是 agent 当前"瞬时"运行状态的完整快照:

| `AgentState` 字段 | 内容 |
|---|---|
| `getSessionId()` | 本份状态所属的会话标识 |
| `getUserId()` | 所属用户标识(匿名会话为 null) |
| `getContext()` / `contextMutable()` | 当前对话历史(用户输入、assistant 回复、工具调用、工具结果) |
| `getSummary()` | 压缩后的摘要(如果开了压缩) |
| `getPermissionContext()` | 工具权限规则,见[权限系统](./permission-system.md) |
| `getPlanModeContext()` | Plan Mode 当前是否激活、计划文件路径 |
| `getTasksContext()` | `todo_write` 维护的任务清单 |
| `getToolContext()` | 工具组激活状态(`activatedGroups`) |

`AgentState` 还携带一个瞬态的、不序列化的 `InterruptControl`,用于 per-session 中断信号——详见下方[Per-session 中断](#per-session-中断)。

一次 `call()` 结束,框架自动把整份 `AgentState` 以 `agent_state` 这个键写进状态存储,按该次调用的 `(userId, sessionId)` 寻址。下次同 `(userId, sessionId)` 的 `call()` 会自动从存储读回——**只要状态存储是分布式的(例如 Redis),不同进程、不同物理机上的 agent 实例都能拿到完全一致的状态**。

### 自动持久化与恢复链路

```
call(msgs, RuntimeContext(userId, sessionId))
  │
  ├─ per-session 门: 相同 (uid, sid) 串行, 不同会话并行
  │
  ▼
  从缓存或 stateStore 加载 AgentState
  │   注入到 RuntimeContext: rc.setAgentState(state)
  │
  ▼
  推理循环
  │   中间件就地改写 state.contextMutable()
  │   (压缩、Plan、todo_write、权限调整……都在改它)
  │
  ▼
  保存 AgentState
  │   stateStore.save(userId, sessionId, "agent_state", state)
  │
  ▼
  返回结果
```

这套机制是 **`ReActAgent` 自带**的,`HarnessAgent` 直接继承,无需额外配置。Agent 实例不绑定固定 session——每次调用读写的是其 `RuntimeContext` 指定的槽位(缺省回退到 builder 上的 `defaultSessionId`)。

> 单次 `call()` 期间的中间状态变更靠的是内存里的 `AgentState` 对象。**状态存储不在每条消息后落盘,而是在 call 结束 / shutdown 时整体写入**——所以对后端的吞吐压力很低。

### 内置与扩展实现

只要实现 `io.agentscope.core.state.AgentStateStore` 接口,任何后端都能接进来。选择哪一种,取决于你的部署形态:

| 实现 | 模块 | 适用场景 |
|---|---|---|
| `InMemoryAgentStateStore` | `agentscope-core` | 单元测试 / 单进程演示;进程退出全部丢失 |
| `JsonFileAgentStateStore` | `agentscope-core` | 单机开发、文件落盘即可恢复;不能跨节点共享。**`HarnessAgent` 默认值**,落在 `~/.agentscope/state/<agentId>/`(可通过 `agentscope.state.home` 系统属性改根目录);**单机** |
| `RedisAgentStateStore` | `agentscope-extensions-redis` | **生产首选**,多副本共享;支持 Jedis / Lettuce / Redisson(Standalone / Cluster / Sentinel) |
| `MysqlAgentStateStore` | `agentscope-extensions-mysql` | 需要把状态沉淀进关系型库(审计、报表)时使用 |

切换非常简单——只在构造期 `.stateStore(...)` 一次:

```java
// 默认(单机):省略 .stateStore(...) 即可,自动用本地 JsonFileAgentStateStore
HarnessAgent agent = HarnessAgent.builder()
    .name("MyAgent")
    .model(model)
    .workspace(workspace)
    .build();

// 多副本生产:使用 DistributedStore
JedisPooled jedis = new JedisPooled("redis://redis.prod:6379");
HarnessAgent agent = HarnessAgent.builder()
        .name("MyAgent")
        .model(model)
        .workspace(workspace)
        .stateStore(new RedisAgentStateStore(jedis))
        .distributedStore(RedisDistributedStore.fromJedis(jedis))
        .build();
```

:::{warning}
内置的 `JsonFileAgentStateStore` / `InMemoryAgentStateStore` 仅适合单机。如果你已经在用 `filesystem(SandboxFilesystemSpec)` 或 `filesystem(RemoteFilesystemSpec)`(分布式工作区),HarnessAgent 会**强制要求**状态存储也换成分布式后端,否则 `build()` 直接抛 `IllegalStateException`——因为 sandbox 状态必须跨副本共享。请通过 `.distributedStore(...)` 或 `.stateStore(...)` 配置分布式后端(例如 `RedisDistributedStore`)。
:::

### 同 (userId, sessionId) 跨进程、跨机器实时恢复

只要状态存储是分布式的(例如 Redis),这一切就是**自动**的:

```java
// 节点 A:开了一段对话
HarnessAgent agentA = HarnessAgent.builder()
    .stateStore(redisStore)
    /* ... */ .build();
agentA.call(msg, RuntimeContext.builder()
    .sessionId("alice-2026-06-02-001")
    .userId("alice")
    .build()).block();

// 节点 B:不同物理机,完全独立的 JVM
HarnessAgent agentB = HarnessAgent.builder()
    .stateStore(redisStore)
    /* 同一份存储后端 */ .build();

// 节点 B 第一次用相同 (userId, sessionId) 的 call() 会自动从 Redis 拉到节点 A 之前留下的 AgentState
agentB.call(nextMsg, RuntimeContext.builder()
    .sessionId("alice-2026-06-02-001")
    .userId("alice")
    .build()).block();
```

这意味着:

- **故障转移**:节点崩了,会话漂到另一个节点,用户感知不到。
- **滚动发布**:旧 pod 退出前 `shutdownManager` 自动保存,新 pod 接到流量时自动从存储还原,**对话不会断**。
- **跨场景接续**:在 Web UI 里和 agent 聊到一半,切换到 CLI 工具继续聊——只要 `(userId, sessionId)` 一致,记忆都在。

`(userId, sessionId)` 二元组决定命名空间:大多数场景只用 `sessionId` 就够;需要按用户分桶时再加上 `userId`。

### 多用户隔离

`sessionId` 和 `userId` 解决的不是同一件事:

- **`sessionId`** —— 决定哪段对话是哪段,独立的 `AgentState` 快照。
- **`userId`** —— 决定这段对话归谁,也决定文件落到谁的命名空间下,详见[文件系统](../harness/filesystem.md)。

```java
agent.call(msg, RuntimeContext.builder()
    .sessionId("alice-1").userId("alice").build()).block();

agent.call(msg, RuntimeContext.builder()
    .sessionId("bob-1").userId("bob").build()).block();
```

两个用户的对话状态与文件路径互不干扰。生产部署如果想做 `AgentState` 级别的用户隔离,在 `RuntimeContext` 上设置 `userId` 即可:存储会按 `(userId, sessionId)` 寻址每个槽位(配合 `RedisAgentStateStore` 时 `userId` 就是 Redis key 的一部分),而不是依赖文件路径分桶。

### 直接读写 AgentState

需要旁路操作(例如管理台、审计、批量迁移)时,可以直接拿:

```java
import io.agentscope.core.state.AgentState;

AgentState state = agent.getAgentState("alice", "session-001");
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
1.0 中的 `Memory` 接口(`InMemoryMemory` / `LongTermMemory` 等)在 2.0 已 `@Deprecated(forRemoval = true)`。新代码请使用 `AgentState.getContext()` + `AgentStateStore` —— `Memory` 仅作为源代码兼容层保留。
:::

### Per-session 中断

每份 `AgentState` 都携带一个瞬态的 `InterruptControl`(`io.agentscope.core.interruption.InterruptControl`)——per-session 的中断信号,**永远不会被序列化**到状态存储(`AgentState` 上标记为 `@JsonIgnore transient`)。这使得可以精确中断某个 session 正在进行的 call,而不影响同一 agent 实例上的其他并发 call。

```java
// 中断指定 session —— 只有该 session 的 call 会收到信号
agent.interrupt("alice", "session-001");

// 带注入用户消息的中断
agent.interrupt("alice", "session-001", Msg.userMsg("请停下来做个总结。"));
```

推理循环在每次迭代前检查 `state.interruptControl().isInterrupted()`。被触发后,循环进入 `handleInterrupt` 路径,保存状态并返回部分结果。

旧的无参 `interrupt()` 在单 session 场景下仍然有效——它会路由到当前活跃会话的 `InterruptControl`。

:::{note}
`InterruptControl` 是纯运行时信号,不会被持久化。如果某个 session 在故障转移后恢复到另一台机器,中断标志从清零状态开始。另一个 `AgentState.shutdownInterrupted` 标志(是**会被持久化**的)记录了该 session 是否被优雅停机中断——agent 可以在下次加载时检测并恢复。
:::

### 并发使用

由于 agent 是无状态引擎,单个实例天然支持并发请求:

```java
HarnessAgent agent = HarnessAgent.builder()
    .name("SharedAssistant")
    .model(model)
    .workspace(workspace)
    .stateStore(redisStore)
    .build();

// 不同用户 —— 完全并行,没有竞争
Mono<Msg> aliceCall = agent.call(aliceMsg, RuntimeContext.builder()
    .userId("alice").sessionId("s1").build());
Mono<Msg> bobCall = agent.call(bobMsg, RuntimeContext.builder()
    .userId("bob").sessionId("s2").build());

Mono.zip(aliceCall, bobCall).block();  // 并行执行

// 同一用户、同一 session —— 自动串行
Mono<Msg> call1 = agent.call(msg1, RuntimeContext.builder()
    .userId("alice").sessionId("s1").build());
Mono<Msg> call2 = agent.call(msg2, RuntimeContext.builder()
    .userId("alice").sessionId("s1").build());

// call2 排在 call1 后面 —— 对话历史始终一致
Flux.merge(call1, call2).collectList().block();
```

**并发规则:**
- **不同 `(userId, sessionId)`** → 完全并行,每次 call 使用各自独立的 `AgentState`。
- **相同 `(userId, sessionId)`** → per-session 异步门按 FIFO 顺序串行化——无需外部锁即保证状态一致性。
- **`interrupt(userId, sessionId)`** → 精确命中单个 session,其他在飞 call 不受影响。

:::{tip}
内存中的状态缓存会随单个 agent 实例服务过的不同 session 数量增长。大多数部署场景(几百个 session)的开销可以忽略。对于超大规模场景(单进程百万级 session),可以考虑 agent factory + 有界实例池——但由于 `AgentState` 对象本身很轻量,这种情况很少出现。
:::

---

## `RuntimeContext` —— per-call 元数据

`RuntimeContext`(位于 `io.agentscope.core.agent`)是一个轻量容器,在 `agent.call(msgs, ctx)` 中传入,hook 与 tool 在本次调用期间共享。其自由 / 类型属性**不持久化**;而 `sessionId` / `userId` 字段决定本次调用状态存储读写哪个 `AgentState` 槽位。在 call 入口,框架会把 call-scoped 的 `AgentState` 注入到 `RuntimeContext` 上,中间件和工具通过 `ctx.getAgentState()` 获取正确的 per-call 状态。

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
| `getSessionId()` / `getUserId()` | 内置字段,用于路由状态槽位与租户 |
| `getAgentState()` / `setAgentState(AgentState)` | call-scoped 的 `AgentState`,由框架在 call 入口注入。中间件和工具应从这里读状态,而非 `agent.getAgentState()` |
| `resolveAgentState(ctx, agent)` | 静态辅助方法:优先返回 `ctx.getAgentState()`,回退到 `agent.getAgentState()`。中间件/工具中使用此方法保证并发安全 |
| `get(String)` / `put(String, Object)` | 字符串键存取 |
| `get(Class<T>)` / `put(Class<T>, T)` | 按类型存取(typed singleton) |
| `getExtra()` | 直接拿到字符串属性 map(可变视图) |
| `RuntimeContext.empty()` | 空上下文 |

:::{tip}
**`AgentStateStore` 后端在 builder 时绑定,不能通过 RuntimeContext per-call 切换**。per-call 变化的是它寻址的 `(userId, sessionId)` 槽位——按用户隔离时设置 `userId`(或在存储上自定义 `keyPrefix`),不要试图给每次 call 传不同的存储实例。
:::

:::{tip}
**在中间件和工具中访问 `AgentState`:** 在 call 执行期间,始终使用 `RuntimeContext.resolveAgentState(ctx, agent)` 而非 `agent.getAgentState()`。并发场景下,`agent.getAgentState()` 返回的是最后一次活跃 session 的状态(多个 call 同时在飞时结果不确定),而 `ctx.getAgentState()` 返回的是**本次 call 的** session 状态——这才是你需要的。
:::

---

## 相关文档

- [智能体（Agent）](./agent.md) —— `ReActAgent` 完整接口与 Builder 参数
- [上下文压缩](../harness/compaction.md) —— 对话摘要、工具结果卸载、溢出恢复(建立在本页描述的 AgentState 基础之上)
- [记忆](../harness/memory.md) —— 长期记忆与后台维护
- [权限系统](./permission-system.md) —— 权限规则的持久化

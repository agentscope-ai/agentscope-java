# Agent 状态存储（AgentStateStore）

```{note}
**推荐使用 [DistributedStore](../distributed/index.md) 一键配置**——它同时覆盖 AgentStateStore、BaseStore、SandboxSnapshotSpec、SandboxExecutionGuard。如果只需要单独配置 AgentStateStore，继续阅读本页。
```

`io.agentscope.core.state.AgentStateStore` 是 AgentScope 用来持久化 Agent 状态的接口——比如 Memory、Workspace、Plan 等组件都会被序列化为 `State` 后由 `AgentStateStore` 落盘，从而支持重启恢复、跨节点共享。

状态通过 `(userId, sessionId)` 二元组寻址：

- `sessionId`——非空、非空白，标识一次会话 / session。
- `userId`——可空。`null` 表示匿名 / 单租户调用方（CLI、测试等）。

## 可用实现

| 实现 | 模块 | 适合场景 |
| --- | --- | --- |
| `InMemoryAgentStateStore` | `agentscope-core` | 单元测试 |
| `JsonFileAgentStateStore` | `agentscope-core` | 单机开发（**HarnessAgent 默认**） |
| `RedisAgentStateStore` | `agentscope-extensions-redis` | [多副本生产首选](../distributed/redis.md) |
| `MysqlAgentStateStore` | `agentscope-extensions-mysql` | [已有数据库的场景](../distributed/mysql.md) |
| `OssAgentStateStore` | `agentscope-extensions-oss` | [阿里云生态](../distributed/oss.md) |

## 单独配置

```java
ReActAgent agent = ReActAgent.builder()
    .name("assistant")
    .model(model)
    .stateStore(stateStore)   // 任选一种 AgentStateStore 实现
    .build();
```

## 限制持久化的会话历史

默认情况下，每次保存 `agent_state` 都会包含完整的会话上下文。对于长期运行的会话，可以设置仅持久化最近多少条消息，避免存储记录无限增长。该配置同时适用于单独使用 `AgentStateStore` 和通过 `DistributedStore` 一键配置的场景：

```java
HarnessAgent agent = HarnessAgent.builder()
    .name("assistant")
    .model(model)
    .distributedStore(distributedStore)
    .maxPersistedContextMessages(200)
    .build();
```

为保持向后兼容，默认不限制。设置为 `0` 时仍会保存其他 Agent 状态，但不会保存会话消息。裁剪只作用于持久化快照，不会修改当前调用正在使用的运行态。

接入系统也可以自行决定何时删除整个会话：

```java
agent.deleteSessionState(userId, sessionId);
// 或：agent.deleteSessionState(runtimeContext);
```

该方法会删除存储中的会话并清理本地状态缓存。请在该会话没有正在执行的调用时使用；否则进行中的调用仍可能在结束时再次保存会话。

详细用法和代码示例请参阅各后端的文档：

- [Redis](../distributed/redis.md#1-redisagentstatestore)
- [MySQL](../distributed/mysql.md#1-mysqlagentstatestore)
- [OSS](../distributed/oss.md#1-ossagentstatestore)

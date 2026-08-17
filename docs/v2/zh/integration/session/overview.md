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

详细用法和代码示例请参阅各后端的文档：

- [Redis](../distributed/redis.md#1-redisagentstatestore)
- [MySQL](../distributed/mysql.md#1-mysqlagentstatestore)
- [OSS](../distributed/oss.md#1-ossagentstatestore)

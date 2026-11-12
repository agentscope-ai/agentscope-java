---
title: 概览
en_link: /dev/en/integration/session/overview
---

<Note>

**推荐使用 [DistributedStore](/dev/zh/integration/distributed/index) 一键配置**——它同时覆盖 AgentStateStore、BaseStore、SandboxSnapshotSpec、SandboxExecutionGuard。如果只需要单独配置 AgentStateStore，继续阅读本页。

</Note>


`io.agentscope.core.state.AgentStateStore` 是 AgentScope 用来持久化 Agent 状态的接口——比如 Memory、Workspace、Plan 等组件都会被序列化为 `State` 后由 `AgentStateStore` 落盘，从而支持重启恢复、跨节点共享。

状态通过 `(userId, sessionId)` 二元组寻址：

- `sessionId`——非空、非空白，标识一次会话 / session。
- `userId`——可空。`null` 表示匿名 / 单租户调用方（CLI、测试等）。

## AgentStateStore 与 Thread 日志

本页是 `AgentStateStore` 接口参考。启用日志时，`HarnessAgent` 通过
`ThreadJournalStore` 恢复；只替换 `stateStore` 不会迁移或共享 Thread 日志。主 Thread
会读取 AgentScope 2.0 写入的状态，并在 checkpoint 提交后将最新状态镜像回 2.0 的
`agent_state` 位置，以支持回滚读取；命名 Thread 使用独立状态项。Workspace 的
BaseStore 能保存文件，也不等于具备日志要求的 CAS 和 writer fencing。详见
[Thread 执行日志与恢复](/dev/zh/docs/harness/session-log)。

## 可用实现

| 实现 | 模块 | 适合场景 |
| --- | --- | --- |
| `InMemoryAgentStateStore` | `agentscope-core` | 单元测试 |
| `JsonFileAgentStateStore` | `agentscope-core` | 单机开发（**HarnessAgent 默认**） |
| `RedisAgentStateStore` | `agentscope-extensions-redis` | [多副本生产首选](/dev/zh/integration/distributed/redis) |
| `JdbcAgentStateStore` | `agentscope-extensions-jdbc` | [已有数据库的场景](/dev/zh/integration/distributed/jdbc) |
| `OssAgentStateStore` | `agentscope-extensions-oss` | [阿里云生态](/dev/zh/integration/distributed/oss) |

## 单独配置

```java
ReActAgent agent = ReActAgent.builder()
    .name("assistant")
    .model(model)
    .stateStore(stateStore)   // 任选一种 AgentStateStore 实现
    .build();
```

详细用法和代码示例请参阅各后端的文档：

- [Redis](/dev/zh/integration/distributed/redis#1-redisagentstatestore)
- [JDBC](/dev/zh/integration/distributed/jdbc#1-jdbcagentstatestore)
- [OSS](/dev/zh/integration/distributed/oss#1-ossagentstatestore)

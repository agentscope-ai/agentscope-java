---
title: 概览
en_link: /v2/en/integration/session/overview
---

<Note>

**推荐使用 [DistributedStore](/v2/zh/integration/distributed/index) 一键配置**——它同时覆盖 AgentStateStore、BaseStore、SandboxSnapshotSpec、SandboxExecutionGuard。如果只需要单独配置 AgentStateStore，继续阅读本页。

</Note>


`io.agentscope.core.state.AgentStateStore` 是 AgentScope 用来持久化 Agent 状态的接口——比如 Memory、Workspace、Plan 等组件都会被序列化为 `State` 后由 `AgentStateStore` 落盘，从而支持重启恢复、跨节点共享。

状态通过 `(userId, sessionId)` 二元组寻址：

- `sessionId`——非空、非空白，标识一次会话 / session。
- `userId`——可空。`null` 表示匿名 / 单租户调用方（CLI、测试等）；空白值和保留值 `__anon__` 使用相同匿名命名空间。

每次调用保存时使用与加载相同的调用方身份。含 `/`（包括末尾斜杠）的 sessionId 不会被拆分或改写。旧版本写到错误身份下的记录不会自动迁移。内部槽位 key 保持不变；同一 agent 实例应避免 `(u1/a, b)` 与 `(u1, a/b)` 这类歧义身份组合。

## 可用实现

| 实现 | 模块 | 适合场景 |
| --- | --- | --- |
| `InMemoryAgentStateStore` | `agentscope-core` | 单元测试 |
| `JsonFileAgentStateStore` | `agentscope-core` | 单机开发（**HarnessAgent 默认**） |
| `RedisAgentStateStore` | `agentscope-extensions-redis` | [多副本生产首选](/v2/zh/integration/distributed/redis) |
| `MysqlAgentStateStore` | `agentscope-extensions-mysql` | [已有数据库的场景](/v2/zh/integration/distributed/mysql) |
| `OssAgentStateStore` | `agentscope-extensions-oss` | [阿里云生态](/v2/zh/integration/distributed/oss) |

## 文件存储并发

`JsonFileAgentStateStore` 使用唯一临时同级文件并清理残留，同时使用有界的 256 个 JVM 级锁条带，按规范化绝对会话目录的哈希选择；不同存储实例和根目录别名共享这些锁条带。同会话的读取、保存和删除会串行执行，不同会话也可能因哈希碰撞而串行执行。这不是跨进程锁：每个会话必须由单个进程负责；多进程共享会话请使用数据库后端。

分开的读改写调用仍采用最后写入者胜出语义。列表数据与哈希文件不是双文件事务，哈希写入失败可能留下旧哈希，直到下次保存。会话列举和清理也不是全局事务。

## 单独配置

```java
ReActAgent agent = ReActAgent.builder()
    .name("assistant")
    .model(model)
    .stateStore(stateStore)   // 任选一种 AgentStateStore 实现
    .build();
```

详细用法和代码示例请参阅各后端的文档：

- [Redis](/v2/zh/integration/distributed/redis#1-redisagentstatestore)
- [MySQL](/v2/zh/integration/distributed/mysql#1-mysqlagentstatestore)
- [OSS](/v2/zh/integration/distributed/oss#1-ossagentstatestore)

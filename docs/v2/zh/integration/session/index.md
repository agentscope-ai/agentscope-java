# Agent 状态存储（AgentStateStore）

`io.agentscope.core.state.AgentStateStore` 是 AgentScope 用来持久化 Agent 状态的接口——比如 Memory、Workspace、Plan 等组件都会被序列化为 `State` 后由 `AgentStateStore` 落盘，从而支持重启恢复、跨节点共享。

状态通过 `(userId, sessionId)` 二元组寻址，不再使用包装的 key 对象：

- `sessionId`——非空、非空白，标识一次会话 / session。
- `userId`——可空。`null` 表示匿名 / 单租户调用方（CLI、测试等）。各实现会把所有匿名 session 归入同一命名空间。

`agentscope-extensions-*` 仓库下提供两种生产级实现：

| 扩展 | 后端 | 适合场景 |
| --- | --- | --- |
| [MySQL 状态存储](mysql.md) | MySQL / 兼容协议数据库 | 已有数据库的应用、要求事务/审计/SQL 查询 |
| [Redis 状态存储](redis.md) | Jedis / Lettuce / Redisson | 高并发、低延迟，多节点共享状态 |

两者都实现了同一个 `AgentStateStore` 接口，可在构建期直接挂载到 Agent 上：

```java
ReActAgent agent = ReActAgent.builder()
    .name("assistant")
    .model(model)
    .stateStore(stateStore)   // 任选一种 AgentStateStore 实现
    .build();
```

Agent 实例本身对 session 是无状态的：每次调用读写哪个 `(userId, sessionId)` 槽位，由该次调用的 `RuntimeContext` 决定：

```java
RuntimeContext rc = RuntimeContext.builder()
    .userId("alice")          // 可选；null 表示匿名
    .sessionId("session-1")   // 必填
    .build();

agent.call(msg, rc).block();  // 读写 (alice, session-1) 的状态
```

## 公共特性

- **单值与列表混合存储**：`save(userId, sessionId, key, State)` 写单值，`save(userId, sessionId, key, List<State>)` 写列表。
- **增量写入**：列表写入采用 hash 摘要 + 计数比较，仅 append 新增项；只有列表被改动或截断时才整体重写。
- **JSON 序列化**：内部统一用 `JsonUtils.getJsonCodec()` 做 `State` ↔ JSON 转换，跨语言、跨版本可读。

## 选型建议

| 场景 | 建议 |
| --- | --- |
| 单机部署或希望最少基础设施 | MySQL（甚至直接用 H2/SQLite 替代） |
| 已有 Redis 集群、追求低延迟 | Redis |
| 需要 SQL 报表 / 审计 / 事务一致 | MySQL |
| 同一会话需要被多个服务共享 | Redis（特别是 Redisson，支持分布式锁） |

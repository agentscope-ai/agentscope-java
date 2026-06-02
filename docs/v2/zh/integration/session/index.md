# 会话（Session）

`io.agentscope.core.session.Session` 是 AgentScope 用来持久化"会话状态"的接口——比如 Memory、Workspace、Plan 等组件都会被序列化为 `State` 后由 `Session` 落盘，从而支持重启恢复、跨节点共享。

`agentscope-extensions-*` 仓库下提供两种生产级实现：

| 扩展 | 后端 | 适合场景 |
| --- | --- | --- |
| [MySQL Session](mysql.md) | MySQL / 兼容协议数据库 | 已有数据库的应用、要求事务/审计/SQL 查询 |
| [Redis Session](redis.md) | Jedis / Lettuce / Redisson | 高并发、低延迟，多节点共享状态 |

两者都实现了同一个 `Session` 接口，可以直接挂载到 `SessionManager`：

```java
SessionManager.builder()
    .session(session)   // 任选一种实现
    .build();
```

## 公共特性

- **单值与列表混合存储**：`save(sessionKey, key, State)` 写单值，`save(sessionKey, key, List<State>)` 写列表。
- **增量写入**：列表写入采用 hash 摘要 + 计数比较，仅 append 新增项；只有列表被改动或截断时才整体重写。
- **JSON 序列化**：内部统一用 `JsonUtils.getJsonCodec()` 做 `State` ↔ JSON 转换，跨语言、跨版本可读。

## 选型建议

| 场景 | 建议 |
| --- | --- |
| 单机部署或希望最少基础设施 | MySQL（甚至直接用 H2/SQLite 替代） |
| 已有 Redis 集群、追求低延迟 | Redis |
| 需要 SQL 报表 / 审计 / 事务一致 | MySQL |
| 同一会话需要被多个服务共享 | Redis（特别是 Redisson，支持分布式锁） |

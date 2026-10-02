# 会话读路径：历史、实时流与恢复

> 状态：已实施
> 问题域：会话历史与实时事件如何低成本、无丢失地合并
> 关联文档：[unified-conversation-contract.md](./unified-conversation-contract.md)、[session-storage-topology.md](./session-storage-topology.md)

## 1. 最终决策

会话页面采用同一个读模型：

1. 打开页面时读取事件历史尾页；
2. 以最后一个持久化 `seq` 订阅可续传 SSE；
3. 重连时携带 `after`/`Last-Event-ID`；
4. 收到跳号事件时先缓存，再通过 `GET .../events?after=<seq>` 补洞；
5. 按事件 ID 和序号幂等合并，最后投影为统一 Message/Event UI。

浏览器不轮询 Messages 或 Events。`message-query` 只保留为旧运行时兼容接口，不是 Session
详情、Chat 或 Playground 的前置能力。

## 2. 两条持久事件路径

| 来源 | 历史接口 | 实时接口 | 唤醒机制 |
|---|---|---|---|
| External Application / Runtime Host | `/api/v1/sessions/{id}/events` | `/api/v1/sessions/{id}/events/stream` | PostgreSQL `LISTEN/NOTIFY` 或内存仓库 channel |
| Managed Harness | `/api/sessions/{id}/events` | `/api/sessions/{id}/events/stream` | 本进程 signal；PostgreSQL `LISTEN/NOTIFY` 跨副本 |

两条路径保留各自原生事件 schema，由前端 `agentscope.conversation.v1` adapter 统一投影，不把不同
runtime 的语义强行改写成一套持久化协议。

### Managed Harness 的恢复兜底

Managed Harness 每次事件提交后发送通知，SSE subscriber 收到通知后按自己的 durable cursor 查询。
通知只表示“可能有新数据”，事件正文始终从数据库读取，因此重复、合并通知不会产生重复展示。

PostgreSQL listener 断开会自动重连。为了覆盖通知连接切换、队列溢出以及 H2/MySQL 等没有
`LISTEN/NOTIFY` 的部署，subscriber 默认每 30 秒执行一次低频恢复读：

```yaml
builder:
  session-event:
    recovery-interval-ms: 30000
    notification-reconnect-ms: 1000
```

这个恢复读不是 UI 刷新机制，也不是 500ms 持续轮询；正常实时性由提交通知提供。

## 3. 历史与实时交接

REST history 和 SSE 可能在交接窗口看到相同事件，也可能由负载均衡落到不同实例，因此消费者必须
遵守以下规则：

- `after=N` 表示只返回 `seq > N`；
- 历史尾页按时间顺序返回，向前翻页使用 `before`；
- SSE 的 event id 等于持久化 `seq`；
- 游标只在事件成功解析并合并后前移；
- `seq > cursor + 1` 时不直接展示该事件，先做权威的 forward read；
- 页面或网络关闭不改变服务端 session 状态，重开页面重新执行 history + live 交接。

## 4. 写入与 ACK

External Application 的 Java/Python SDK 先把完整事件写入本地 journal 并 `fsync`，再通过 ASDP
发送。控制面持久化连续序列后返回 ACK 水位，SDK 只删除已确认记录。该链路是至少一次传输、按
`(session, seq)` 幂等落库。

Managed Harness 直接写共享的 `builder_session_event`，用 `(session_id, seq)` 唯一约束和冲突重试
保证单调序列；事务提交以后才发布唤醒通知。

## 5. 当前容量策略

当前优先保证完整性：控制面 `session_events` 默认保留窗口为 `0`（不按时间清理），Managed
`builder_session_event` 同样随 Session 生命周期保存。对象存储归档、冷热分层和大事件外置属于以后
的容量优化，实施时不得改变现有 seq 游标和 history + live 恢复契约。

## 6. 验收结论

- 页面刷新、关闭后重开、SSE 断线重连均从持久游标恢复；
- 历史加载与实时事件重复时只展示一次；
- 实时流跳号会自动补洞，不静默前移游标；
- External SDK 在 ACK 丢失或重启后重放，控制面幂等处理；
- Managed SSE 正常路径不再每 500ms 查询数据库；
- Endpoint Playground 的 conversation 与 job SSE 都由持久仓库通知唤醒，不再使用 500ms ticker；
- 没有 `message-query` capability 的 agent 仍可展示完整事件会话。

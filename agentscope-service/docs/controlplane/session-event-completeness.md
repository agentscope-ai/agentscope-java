# 会话事件记录完整性

> 状态：已实施
> 问题域：持久事件记录什么，以及刷新后能够恢复什么
> 关联文档：[unified-conversation-contract.md](./unified-conversation-contract.md)、[session-readpath-cost.md](./session-readpath-cost.md)

## 1. 持久化粒度

事件日志记录完整语义事件，不记录逐 token delta：

| 语义 | 持久内容 |
|---|---|
| 用户/Agent 消息 | role、完整文本、原始 framework metadata |
| 工具调用 | 稳定 call id、工具名、完整结构化 input |
| 工具结果 | 对应 call id、状态、完整 output |
| 模型调用 | start/end、usage、duration 和 provider metadata |
| 生命周期与错误 | 原始类型、状态、错误详情与时间 |

Managed Harness 的 `SessionEventMapper` 会累积工具参数和结果 delta，在语义边界形成完整的
`agent.tool_use` / `agent.tool_result`。External Application SDK 的 `SessionEventMsg` 同样不再截断
message、tool input 或 tool output。

未知的 runtime 事件不丢弃：原始 payload 保留，由统一前端归入 `other` category 展示。

## 2. Preview 与持久事件

`event_start` / `event_delta` 只用于当前连接中的打字机预览，`seq=-1`，不作为恢复事实源。最终语义
事件使用相同稳定 event id，前端收到后替换预览内容。

因此刷新期间最多暂时看不到尚未形成最终语义事件的半截 token；已经提交的消息、工具调用和结果
不会丢失，重开页面后从 durable event log 恢复。

## 3. 交付保证

- Java/Python External SDK：本地 journal `fsync` 后发送，收到控制面持久化 ACK 才清理；
- 控制面：只 ACK 每个 Session 的连续提交水位，重复 `(session, seq)` 幂等；
- Managed Harness：事件事务提交后才发布 SSE 唤醒通知；
- 浏览器：history tail + resumable SSE，跳号时 forward-read 补洞，按 ID/seq 去重。

该组合提供至少一次传输和一次可见的幂等结果。进程退出、网络断开、页面刷新或 ACK 丢失不会让已
持久化事件从会话时间线消失。

## 4. 当前保留策略

当前阶段优先保证完整性：`builder_session_event` 随 Session 生命周期保存；aistio runtime
`session_events` 的默认保留窗口为 `0`，即不按时间清理。

对象存储归档、冷热分层和大 payload 外置暂不实施。以后引入容量治理时，只有在冷存储确认落盘并且
读 API 能跨层维持连续 seq 后才允许清理热表；合规删除必须同时覆盖所有存储层。

## 5. 验收结论

- [x] 刷新后仍能看到完整消息和工具 input/output
- [x] 工具调用与结果通过稳定 call id 配对
- [x] SDK 重启和 ACK 丢失会重放未确认事件
- [x] 历史与实时流重复不会重复渲染
- [x] 实时跳号会自动补洞
- [x] Managed 与 runtime Session 都通过同一 Conversation/Event 展示契约
- [x] 当前保留策略明确为随 Session 保存，不按时间清理

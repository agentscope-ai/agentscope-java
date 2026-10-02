# 会话事件记录与恢复边界

Managed Agent 的执行事实由原生 Session Log 提交，再导出为公共 Agent API 事件。原生层保存请求、模型片段、消息、工具执行、交互与 checkpoint；公共层只保存客户端需要的事实。参见[原生和公共日志指南](../agent-api/session-event-log.md)。

## 单一执行记录与兼容读取

`SessionEventMapper` 和旧 thinking/tool/model 累积写入已经删除。Managed 执行不再把同一事实同时写为旧 agent.message/tool 事件和新公共 item 事件。旧 `/api/sessions`、Chat、消息与 Context 接口通过 `LegacySessionEventAdapter` 在读取时转换公共记录，保持原始 ID/seq，未知记录仍能推进读取水位。历史存量记录保持可读，不进行自动删除。

用户消息提交保留不含正文的 `session.input_accepted` 命令记录，用于 Endpoint invocation/turn 关联；工具结果接收通知保持瞬时。真正持久用户消息来自已提交的原生事实。最终消息根据 message_id/replaces_preview_id 替换预览。实时 preview 不带持久游标，不能重放；刷新后使用持久公共历史恢复界面。

## 继续保留的独立记录

- 平台调度、命令、确认、审批、工单与编排状态具有独立业务语义。
- External SDK、BYO 与 Endpoint provider 的来源协议继续由各自 adapter 接入。
- Java/Python External SDK 的本地 outbox 先持久化后发送，收到服务端 ACK 才清理；它承担可靠投递，不能作为 checkpoint 恢复来源。
- Service 的公共事件和原生事件职责不同；原生 seq 与公共 SSE cursor 不能互换。

## 后续集中回归

重点覆盖多副本重启补投、SSE 断连重放/去重、旧 Chat 快照和流的一致性、preview 替换、工具暂停和外部结果续跑、管理员清空上下文后的历史保留，以及分布式后端发现与长历史分页。断线续传只补消息；恢复执行必须先检查未决工具和交互，不能通过重放工具副作用重建状态。

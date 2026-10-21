---
title: "Session 历史与实时事件"
description: "先打开并缓冲实时流，再读取历史，最后按稳定 Event ID 去重。"
en_link: /v2/en/service/sse-events
---

本页适用于 Managed Draft 3 首版，参见 [Session 事件 API](/v2/zh/service/claude-session-api)。当前仅启用普通实时 SSE；可靠续传是单独的后续能力。

`GET /v1/sessions/{session}/events/stream` 跟踪连接边界之后的事件。服务先记录持久位置，再发送 connected 注释，并从持久日志读取随后提交的事实；通知不是唯一事实来源。

```text
event: agent.message
data: {"id":"evt_answer","type":"agent.message","content":[{"type":"text","text":"完成。"}],"processed_at":"2026-10-19T07:01:36Z"}

: keepalive
```

首版拒绝 after、Last-Event-ID、preview 和 event_deltas。允许官方 SDK 的 beta=true 传输参数。Turn 结束后流继续保持，断线不会取消执行。

`AgentScope-Extensions: turn-inputs-v1` 增加平台字段与 Turn/Input 通知。标准流只包含标准类型。checkpoint、私有思考、租约和凭据不公开。

恢复顺序：

1. 打开新的实时流并缓冲事件。
2. 读取 `/events?limit=100&order=asc`，跟随 next_page 翻页。
3. 应用历史和缓冲事件，按稳定 Event ID 去重。
4. 继续处理实时流；再次断线后重复上述过程。

同一个输入 Event 可以先以 `processed_at=null` 出现在历史中，处理完成后在实时流中出现相同 ID。合并时更新原记录，不要丢弃处理后的状态或重复插入消息。

Turn 最终结果由 `/turns/{turn}/output` 独立提供，不应将 keepalive、断线或普通消息直接当成 Turn 已完成。使用 Turn 状态和结果接口判断完成；取消操作通过事件 API 受理，不能用关闭流代替。

权限在订阅期间持续检查。权限撤销时停止流；查询历史和重新订阅仍需保留相同调用身份、Session 和作用域。生产环境使用应用凭据或平台 Bearer 令牌，本地开发模式需要显式开启。

可靠投递、snapshot、preview 和新版 Managed Webhook 协议仍未启用。其他后端保留各自的观察契约，其游标不能作为 Managed 历史分页参数。

### Managed 工具输出与操作进度

通过 `agent.tool_use`、`agent.mcp_tool_use` 及对应结果事件展示工具活动。按稳定 Event 身份合并更新，并查询指定 Turn/Input 的执行与处理状态。工具输出本身不表示 Turn 已完成，最终结果仍需独立查询。私有 checkpoint 和执行租约不作为公开进度字段。

## Webhooks

新版 Managed Draft 3 Webhook 协议尚未启用，以 `/capabilities` 为准；此前原生 `/v1/sessions/{id}/webhooks` 注册示例不适用于本版本。当前通过 Session 事件流或 Turn/output 查询观察执行。Hosted 和 External 后端继续使用独立的 `/api/v1/agent-sessions/{id}/webhooks` 契约及原有投递行为。Automation 入站 Webhook 仍是独立触发机制，参见 [Automation](/v2/zh/service/automation)。

{/* Preserve earlier anchors; current capability limits supersede the old examples. */}
<span id="先恢复快照再接收增量"></span>
<span id="理解事件与状态"></span>
<span id="分页断线与过期"></span>
<span id="用户离线后的-webhook-通知"></span>
<span id="managed-运行时协议"></span>

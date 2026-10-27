---
title: "SSE 与事件续传"
description: "通过快照与 SSE 观察任务、恢复断线后的进度，并通过 Webhook 接收后台通知。"
en_link: /v2/en/service/sse-events
---

任务提交后，应用需要把后台的执行进度展示给用户。Session API 会保存快照与事件日志：快照用于恢复已经发生的工作，后续事件用于更新页面上的消息、工具调用和待办。应用通过 SSE 持续接收这些事件，因此用户刷新页面或网络断开后，仍可以继续观察同一项任务；关闭连接不会取消执行。

首次创建 Session 和提交 Turn 的流程见[通过 Session API 接入应用](/v2/zh/service/service-api)。下面沿用该页的 `SESSION_URL` 和应用凭据。如果业务后端需要在用户离线后收到通知，可以直接阅读本页的[Webhook 部分](#webhooks)，它与页面上的 SSE 订阅可以同时使用。

## 先恢复快照，再接收增量

应用第一次打开页面时，应先读取 Session 的 `/snapshot`，用返回内容恢复消息、工具和待办，然后把其中的 `as_of` 作为 `/events/stream` 的 `after` 参数。每处理完一个事件，再保存它的 cursor。网络重连时从最后成功应用的 cursor 继续；如果页面状态也丢失了，则重新加载快照。

下面使用应用凭据。平台用户也可以用有权访问该 Session 的 Bearer token 完成相同操作。

```bash
SNAPSHOT=$(
  curl -sS --fail-with-body "$SESSION_URL/snapshot" \
    -H "X-API-Key: $AGENTSCOPE_API_KEY"
)
CURSOR=$(jq -er '.as_of' <<< "$SNAPSHOT")
```

```bash
curl -sS --fail-with-body -N -G "$SESSION_URL/events/stream" \
  -H "X-API-Key: $AGENTSCOPE_API_KEY" \
  -H "Accept: text/event-stream" \
  --data-urlencode "after=$CURSOR"
```

若只显示一个任务，则使用 `/turns/{turnId}/snapshot` 与 `/turns/{turnId}/events/stream`。Session、Turn 和子 Agent 各有自己的事件日志，游标只能用在产生它的资源上。它不是时间戳，也不能通过解析其中的字符推断事件序号。服务支持 `Last-Event-ID`；这个请求头存在时优先于查询参数。

<Accordion title="单个 Turn 订阅与断线续传">

只观察一个 Turn 时，使用这一对接口：

```bash
TURN_URL="$SESSION_URL/turns/$TURN_ID"
TURN_SNAPSHOT=$(
  curl -sS --fail-with-body "$TURN_URL/snapshot" \
    -H "X-API-Key: $AGENTSCOPE_API_KEY"
)
TURN_CURSOR=$(jq -er '.as_of' <<< "$TURN_SNAPSHOT")
```

```bash
curl -sS --fail-with-body -N -G "$TURN_URL/events/stream" \
  -H "X-API-Key: $AGENTSCOPE_API_KEY" \
  -H "Accept: text/event-stream" \
  --data-urlencode "after=$TURN_CURSOR"
```

重连 Session 流时，将 `LAST_APPLIED_CURSOR` 替换为最后成功应用的 Session 事件游标。本例使用请求头续传，无需同时传 `after`：

```bash
LAST_APPLIED_CURSOR="LAST_APPLIED_SESSION_CURSOR"
curl -sS --fail-with-body -N "$SESSION_URL/events/stream" \
  -H "X-API-Key: $AGENTSCOPE_API_KEY" \
  -H "Accept: text/event-stream" \
  -H "Last-Event-ID: $LAST_APPLIED_CURSOR"
```

</Accordion>

## 理解事件与状态

SSE 的 `id` 字段是续传游标，`event` 是事件类型，`data` 是 JSON 事件。公共事件包含 `schema_version`、`id`、`type`、`session_id`、`created_at`、`cursor` 和 `data`；属于某个任务的事件还会关联 `turn_id`。应用应按事件 ID 去重，并按资源 ID 更新已有内容，不能每收到一次通知就添加一张新卡片。

`item.delta` 用于追加消息增量，`item.completed` 提供该消息的累计内容。处理完成事件时应替换累计内容，避免把同一段文字追加两次。工具事件描述单次工具调用，`required_action.*` 描述待办和答复，`step.*` 描述协作或流程步骤。只有目标 Turn 的明确状态才能判断任务是否结束，单个工具或子任务完成不代表整个任务完成。

Managed Session 快照使用运行时的资源数组，可以展示输入、子 Agent 和运行尝试；通用 Turn 与 Team、Workflow 快照使用按 ID 索引的集合。前端可复用 `agentSessions.ts` 与 `agentSessionView.ts` 处理 Managed 执行视图，或用 `serviceSessions.ts` 处理通用 Turn 视图。不要把这两种快照形状当成完全相同的数据结构。

## 分页、断线与过期

无需持续连接时，可以使用 `GET /events?after=...&limit=100` 分页读取。响应中的 `next_cursor` 用于下一页，`has_more` 表示当前还有可读事件。空页并不表示任务已完成；应用仍应读取 Turn 状态，或继续等待后续事件。

```bash
EVENT_PAGE=$(
  curl -sS --fail-with-body -G "$SESSION_URL/events" \
    -H "X-API-Key: $AGENTSCOPE_API_KEY" \
    --data-urlencode "after=$CURSOR" \
    --data-urlencode "limit=100"
)
jq . <<< "$EVENT_PAGE"
```

仅当 `has_more` 为 true 时立即读取下一页；处理完当前页后再推进游标。

```bash
if jq -e '.has_more == true' <<< "$EVENT_PAGE" > /dev/null; then
  NEXT_CURSOR=$(jq -er '.next_cursor' <<< "$EVENT_PAGE")
  curl -sS --fail-with-body -G "$SESSION_URL/events" \
    -H "X-API-Key: $AGENTSCOPE_API_KEY" \
    --data-urlencode "after=$NEXT_CURSOR" \
    --data-urlencode "limit=100"
fi
```

如果请求返回 `410` 和 `cursor_expired`，说明该资源的增量历史已超过保留范围。此时读取新的快照，并从新的 `as_of` 恢复订阅。不要为了恢复观察而重新提交任务。不同资源的游标不可交换，错误的游标会被拒绝。

<span id="webhooks"></span>

## 用户离线后的 Webhook 通知

业务后端可以在 Session 的 `/webhooks` 注册通知，覆盖该会话中的后续任务；如果只关心一个任务，则在对应 Turn 的 `/webhooks` 注册。应用凭据需要 `webhooks:write`。下面的目标地址应替换为你实际部署的 HTTPS 接收端：

```bash
WEBHOOKS_URL="$SESSION_URL/webhooks"
WEBHOOK_JSON=$(
  curl -sS --fail-with-body "$WEBHOOKS_URL" \
    -H "X-API-Key: $AGENTSCOPE_API_KEY" \
    -H "Content-Type: application/json" \
    -H "Idempotency-Key: notifications-001" \
    --data-binary @- <<'JSON'
{
  "url": "https://your-app.example/agent-events",
  "event_types": [
    "turn.completed",
    "turn.failed",
    "required_action.created"
  ]
}
JSON
)
WEBHOOK_ID=$(jq -er '.webhook.id' <<< "$WEBHOOK_JSON")
SIGNING_SECRET=$(jq -er '.signing_secret' <<< "$WEBHOOK_JSON")
```

保存返回的 `signing_secret`，不要将它暴露给浏览器。接收端使用这个 secret，对 `时间戳 + '.' + 原始请求体` 计算 HMAC-SHA256，并与 `X-AgentScope-Signature: t=<秒>,v1=<hex>` 比较。同时检查时间窗口，并按事件 ID 去重。验签必须使用原始请求字节，不能先解析 JSON 再序列化。

如果只通知一个 Turn，在注册前改用 `WEBHOOKS_URL="$SESSION_URL/turns/$TURN_ID/webhooks"`。下面的管理请求继续使用注册时的 `WEBHOOKS_URL` 和返回的 `WEBHOOK_ID`。

Webhook 是至少一次投递，因此同一个事件可能收到多次。接收端应先可靠保存通知，再返回成功，并按需要读取 Session 或 Turn 的最新状态。`GET /webhooks` 可检查投递状态；失败后可以调用 `POST /webhooks/{webhookId}/retry` 重试，或者通过 `DELETE` 撤销订阅。Webhook 不会替业务系统完成幂等处理，也不表示外部业务事务已经提交。

<Accordion title="查看、重试与停用 Webhook">

查看订阅及投递状态：

```bash
curl -sS --fail-with-body "$WEBHOOKS_URL" \
  -H "X-API-Key: $AGENTSCOPE_API_KEY"
```

接收端恢复后，按需重试失败投递：

```bash
curl -sS --fail-with-body -X POST "$WEBHOOKS_URL/$WEBHOOK_ID/retry" \
  -H "X-API-Key: $AGENTSCOPE_API_KEY"
```

不再需要通知时停用订阅：

```bash
curl -sS --fail-with-body -X DELETE "$WEBHOOKS_URL/$WEBHOOK_ID" \
  -H "X-API-Key: $AGENTSCOPE_API_KEY"
```

</Accordion>

自动化的入站 Webhook 用于让外部系统触发工作，与这里用于通知结果的出站 Webhook 用途不同。配置入口见[自动化](/v2/zh/service/automation)，保留和恢复策略见[运维](/v2/zh/service/operations)。

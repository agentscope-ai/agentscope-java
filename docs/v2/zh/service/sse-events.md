---
title: "SSE 格式与任务反馈"
---

<Note>
此为预览文档，正式版本尚未发布。
</Note>

应用通过 Endpoint 提交工作后，使用返回的 `eventsUrl` 接收 SSE 事件，通过 `statusUrl` 查询结果。先按[Endpoint 接入](/v2/zh/service/endpoints)完成发布和请求提交；控制台用户通过[信箱](/v2/zh/service/inbox)处理通知、审批与验收。

## 提交、订阅和查询

1. 向 Conversation 或 Job Endpoint 提交请求，保存 `invocationId`、`eventsUrl` 和 `statusUrl`；Conversation 还会返回 `conversationId`、`turnId` 等会话标识。
2. 用同一个调用凭据向 `eventsUrl` 发起 GET，请求 `Accept: text/event-stream`。
3. 按 SSE 帧解析事件，保存已处理的游标，并按事件类型更新界面。
4. 流结束或连接中断时查询 `statusUrl`，确认本次调用的状态与结果。

`202 Accepted` 表示已接受请求。SSE 是事件传输方式，接到一个事件或连接关闭都不能单独作为工作成功的依据。

## SSE 帧格式

每条业务事件包含 `id`、`event` 和 JSON `data`，以空行结束。下面是一条会话事件的示例，ID、时间和内容均为演示值：

```text
id: 7
event: assistant.message
data: {"id":145,"sessionFk":"11111111-1111-4111-8111-111111111111","seq":7,"eventType":"assistant.message","role":"assistant","content":"已整理待办清单。","occurredAt":"2026-09-10T09:00:00Z"}

```

| 字段 | 处理方式 |
| --- | --- |
| SSE `id` | 该流的顺序游标，用于断线续传；不是调用 ID |
| SSE `event` | 事件类型；按类型分派处理，并容忍未知类型 |
| SSE `data` | 一个 JSON 事件对象；按 Conversation/Job 结构分别解析 |
| 空行 | 一帧结束；网络读取的一块数据不一定对应完整一帧 |

服务等待新事件时可能发送 `: heartbeat` 注释行。忽略此注释，不把它当成 JSON 或工作进展。

```text
: heartbeat

```

这里使用标准 SSE 帧封装。业务事件是 Service 的会话或编排事件，不能假设 `data` 是某个模型厂商的 token 增量协议，也不能依赖固定的 `[DONE]` 标记。

## Conversation 与 Job 的事件内容

| | Conversation | Job |
| --- | --- | --- |
| 事件来源 | 运行时 Session 事件 | Run 编排事件 |
| SSE `id` 对应字段 | `seq` | `sequence` |
| 类型字段 | `eventType` | `type` |
| 关联标识 | `sessionFk`；运行时可能提供 `frameworkMeta` | `runId`，以及可选 `nodeId`、`agentTaskId`、`attemptId` |
| 常用内容 | `role`、`content`、`toolName`、`toolInput`、`toolOutput` | `actor`、`payload`、`occurredAt` |
| 类型示例 | `assistant.message`、`turn.completed`、`turn.failed` | `run.started`、`node.succeeded`、`node.failed` |

字段和事件类型取决于实际执行路径，不保证每个 provider 都发送相同种类或粒度的事件。可选字段可能省略。Conversation 的 JSON `id` 是存储记录标识，续传应使用 SSE `id` / `seq`；Job JSON 的 `id` 也不能代替 `sequence`。

下面是一条 Job 事件的字段示例：

```text
id: 1
event: run.started
data: {"id":"22222222-2222-4222-8222-222222222222","runId":"33333333-3333-4333-8333-333333333333","tenant":"default","namespace":"default","sequence":1,"type":"run.started","actor":{"type":"system","ref":"endpoint:example"},"occurredAt":"2026-09-10T09:00:00Z"}

```

Conversation 事件按 Session 游标读取。返回 URL 中的 `invocationId` 关联当前调用的终止判断，并不把 Session 历史过滤成仅当前一轮；从游标 0 订阅可能收到早先会话事件。保留已处理游标，按实际提供的 `frameworkMeta.turnId` 等关联信息区分轮次，不把历史输出重复显示为新回复。

## 订阅与断线续传

将 `BASE_URL` 设置为 Gateway origin，`ENDPOINT_TOKEN` 设置为提交请求时的调用凭据，`EVENTS_PATH` 填完整返回的相对 `eventsUrl`，包括其查询参数：

```bash
curl -N --fail-with-body "$BASE_URL$EVENTS_PATH" \
  -H "X-API-Key: $ENDPOINT_TOKEN" \
  -H 'Accept: text/event-stream'
```

Endpoint 使用 `platform` 认证时，将认证头替换为 `Authorization: Bearer $ENDPOINT_TOKEN`。若返回绝对 URL，直接使用该 URL，不再拼接 BASE_URL。

应用成功处理一帧后保存其 SSE `id`。断线时先查询状态；仍需接收事件则使用同一 URL 和凭据重新订阅，`LAST_EVENT_ID` 为最后成功处理的游标：

```bash
curl -N --fail-with-body "$BASE_URL$EVENTS_PATH" \
  -H "X-API-Key: $ENDPOINT_TOKEN" \
  -H 'Accept: text/event-stream' \
  -H "Last-Event-ID: $LAST_EVENT_ID"
```

两个接口也接受 `after` 查询参数；同时提供时采用它与 `Last-Event-ID` 中较大的有效数值，读取其后的事件。按对应 Session 或 Run 保存游标，不在无关流之间复用。客户端可能在处理后、保存游标前断线，因此应按“流标识 + SSE id”去重，避免重复通知或重复业务操作。

重新订阅不会重新提交工作；提交重试才使用原 Idempotency-Key。遇到 401/403 先修复认证或授权，不能仅靠重连解决。代理需要及时转发事件、关闭事件流缓冲并设置足够长的读取超时。

## 读取最终结果与文件

将提交响应中的 `statusUrl` 填入 `STATUS_PATH`：

```bash
curl --fail-with-body "$BASE_URL$STATUS_PATH" \
  -H "X-API-Key: $ENDPOINT_TOKEN"
```

- **Conversation**：状态响应包含 `conversation` 和 `turns`。在返回的 `turns` 中按提交时的 `invocationId` 匹配 `id`，查看这一轮的状态与错误；回复内容由会话事件提供。
- **Job**：读取 `invocation.status`。`completed` 后使用 `invocation.result`，失败时查看 `errorCode`、`errorMessage`。状态响应还可能包含 `run`、`issue` 的摘要。
- **交付文件**：Job 使用 `GET /invoke/v1/jobs/{invocationId}/artifacts` 获取列表，再用返回的 `downloadUrl` 和同一凭据下载。

`accepted`、`dispatching`、`running`、`waiting` 都不是终态。`completed` 表示调用完成；`failed`、`cancelled`、`timed_out` 是未成功的终态。单个节点成功不代表整个 Run 成功；Job 结果仍需按发布的 output schema 和业务标准检查，部分成功是否足够由业务决定。

人工审批或交付验收按工作策略在[控制台信箱](/v2/zh/service/inbox)处理，读取 SSE 不会自动批准操作或接受交付。

可用[订单履约案例的 Job 调用](/v2/zh/service/cases/order-fulfillment)练习订阅进度、保存游标和查询最终处置结果。游标来自对应运行，断线后继续观察原调用。

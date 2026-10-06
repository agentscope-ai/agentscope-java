---
title: "Managed 原生 API：会话与任务"
description: 通过 HTTP 创建 Managed Agent 会话、提交后台任务、读取结果，并处理人工交互、取消和恢复。
en_link: /v2/en/service/session-event-log
---

Managed 原生会话 API 是平台 Agent API 的一部分，提供托管运行时的会话控制：服务负责运行 Agent、保存会话和调度任务，应用负责提交输入、展示结果和处理用户交互。Agent 在后台运行，关闭页面或断开 SSE 不会取消任务。

入口是 Gateway 的 `/api/v1/agent-sessions`。先完成 [Managed Agent 创建](/v2/zh/service/create-managed-agent)，再按下面的步骤接入。如果在自己的 Java 进程内运行 Agent，阅读 [AgentSession 指南](/v2/zh/docs/harness/session-log)。


向业务应用提供 Agent、Team 或 Workflow 服务时，通常从[统一服务 API](/v2/zh/service/service-api)开始。本页介绍直接管理 Managed 会话时的原生能力。

## 按使用场景选择能力

| 业务场景 | 使用的 API | 观察的结果 |
| --- | --- | --- |
| 多轮聊天、后台助手 | 创建 session；POST turns | 消息、工具与目标 turn 状态 |
| 刷新页面、恢复生成中的内容 | GET snapshot；GET events/stream?after=as_of | 历史消息、工具卡和后续增量 |
| 执行中纠正方向或补充资料 | POST turns/{turn}/steer 或 inputs/inject | input.accepted → applied / rejected |
| 工具审批、外部执行结果 | POST turns/{turn}/actions | required_action 的接收、解决或投递失败 |
| 停止当前任务、继续中断任务 | POST turns/{turn}/cancel 或 resume | 明确的 turn 状态；resume 沿用 turn |
| 文件输入和交付 | files、artifacts | 文件引用、产物发布与下载 |
| 追踪委派和控制消耗 | subagents、usage、budget | 子会话、模型用量与预算事件 |
| 从旧上下文另行试验、导出审计 | checkpoints、fork、export | 恢复事实、新会话或公共 JSONL |
| 用户离线后通知业务后端 | webhooks | 签名通知，再读取事件详情 |

第一次接入可以直接运行[聊天示例](/v2/zh/service/agent-api-chat)。本页按操作解释请求与边界；[SSE 文档](/v2/zh/service/sse-events)集中说明事件字段和前端更新规则。

## 先跑通一个任务

1. 创建一次 session，保存它的 ID。
2. POST turns 提交任务，保存返回的 turn ID。
3. GET snapshot 恢复界面，再从 as_of 订阅 SSE。
4. 收到 required action 时提交答复；以目标 turn 的明确结果判断是否完成。

## 执行身份与逻辑 turn 状态

`sessionId` 是持续会话；`turnId` 是一次逻辑任务；`run_id` 是实际执行的一次尝试。新 POST turns 创建新 turn。resume 和人工答复继续原 turn，必要时新建 run；页面刷新和 SSE 重连都不创建它们。steer 修改当前任务的后续要求；inject 只添加供后续步骤使用的上下文。

例如 S1 中提交 T1，开始 R1，等待审批后 R1 挂起；用户答复后 T1 继续为 R2。刷新页面只读取 S1 的快照和增量。完成后再提新问题，才创建 T2。

## 创建 session 并提交第一轮

前提：已有可用的 Managed Agent 和 Environment，以及该 session 所有者的用户 Bearer token。`TOKEN` 是平台用户令牌，不能用 Endpoint 的 `X-API-Key` 替代。登录方式见 [API 认证](/v2/zh/service/api-reference)。以下 shell 示例使用 `curl` 和 `jq`；`BASE_URL` 为不带末尾斜杠的 Gateway origin。

```bash
export BASE_URL='http://localhost:18080'
export TOKEN='YOUR_USER_TOKEN'
export AGENT_ID='YOUR_MANAGED_AGENT_ID'
export ENVIRONMENT_ID='YOUR_ENVIRONMENT_ID'

SESSION_JSON=$(curl --fail-with-body -sS "$BASE_URL/api/v1/agent-sessions" \
  -H "Authorization: Bearer $TOKEN" -H 'Content-Type: application/json' \
  -d "$(jq -n --arg agent "$AGENT_ID" --arg env "$ENVIRONMENT_ID" \
        '{agent:$agent, environmentId:$env}')")
SESSION_ID=$(printf '%s' "$SESSION_JSON" | jq -er '.id')
SESSION_URL="$BASE_URL/api/v1/agent-sessions/$SESSION_ID"
```

session 创建请求使用 `agent`、`environmentId` 等 camelCase 字段；Agent 已配置默认 Environment 时可省略 environmentId。创建 session 不会自动提交消息。保留返回的 session ID，不要在每次页面刷新时新建 session。

```bash
# 每个逻辑提交使用一个稳定 key；网络重试必须沿用相同 key 和 message。
TURN_KEY='research-request-20260930-001'
TURN_JSON=$(curl --fail-with-body -sS "$SESSION_URL/turns" \
  -H "Authorization: Bearer $TOKEN" -H 'Content-Type: application/json' \
  -H "Idempotency-Key: $TURN_KEY" \
  -d '{"message":"请整理这份材料，并列出需要核实的问题。"}')
TURN_ID=$(printf '%s' "$TURN_JSON" | jq -er '.id')
```

成功返回 `202 Accepted`，例如：

```json
{"id":"<turn-id>","sessionId":"<session-id>","status":"queued","createdAt":1790726400000,"errorCode":null}
```

202 表示命令已持久接收，尚不表示输入已进入模型。相同 user/session/key 与输入返回同一个 turn；同 key 改变输入返回 409。key 必须非空且不超过 256 字符，message 必须非空。不要对重试生成新 key。

同一 session 的 turn 按接收顺序执行；较早 turn 等待操作或中断时，后续输入可排队，但不会直接插入正在执行的模型请求。不同会话可以独立执行。

## 读取回复与执行进度

```bash
SNAPSHOT=$(curl --fail-with-body -sS "$SESSION_URL/snapshot" -H "Authorization: Bearer $TOKEN")
CURSOR=$(printf '%s' "$SNAPSHOT" | jq -er '.as_of')
curl --fail-with-body -N -G "$SESSION_URL/events/stream" \
  -H "Authorization: Bearer $TOKEN" --data-urlencode "after=$CURSOR"
```

快照包含 `items`、`tools`、`turns`、`runs`、`required_actions`、`action_commands`、`inputs`、`artifacts`、`subagents` 和 `usage`。这些资源对应同一事件前缀 `as_of`；`session` 是当前控制面元数据。消息内容在 `items[].data.item.content`，工具卡包含参数、进度、结果和状态。正在生成的内容也有已提交前缀，刷新后从该前缀继续更新。

按 `item_id` 更新消息、按 `(turn_id, tool_call_id)` 更新工具。`item.completed` 替换该 item 的累计内容，不再追加一次。SSE 断开不取消任务。以目标 `turn_id` 的 `turn.completed` 判断成功；单个 run、消息、工具或子 Agent 完成都不是根任务完成。

需要逐条审计时使用 `GET /events?after=…&limit=100`；需要较长列表时用 `GET /resources/items?limit=100`，之后传它返回的 `next_cursor`。资源分页在 15 分钟内保持原来的快照和 `as_of`，过期返回 410，重新开始分页。**资源分页 cursor 不能用于 SSE**，SSE 使用 `as_of` 或事件 cursor。完整事件类型和可复用前端代码见 [SSE 文档](/v2/zh/service/sse-events)。

## 运行中补充要求和结构化输入

| 目的 | 操作 | 何时生效 |
| --- | --- | --- |
| 提交新的独立任务 | POST `/turns` | 按顺序开始新的 turn |
| 纠正正在执行的任务 | POST `/turns/{turn}/steer` | 当前 turn 的下一个执行步骤；任务已关闭接收时返回 409 |
| 补充背景，不启动推理 | POST `/inputs/inject` | 下一次执行步骤读取；空闲时保存等待后续执行 |

三个操作均需要稳定 `Idempotency-Key`，均接受下面两种输入形式之一。steer/inject 返回 `input_id`；`input.accepted` 表示已保存，`input.applied` 表示已进入运行时上下文。steer 不会改写已经发送给模型的请求。

```bash
curl --fail-with-body -sS "$SESSION_URL/turns/$TURN_ID/steer" \
  -H "Authorization: Bearer $TOKEN" -H 'Content-Type: application/json' \
  -H 'Idempotency-Key: correction-001' -d '{"message":"优先核实预算，暂时不要发送邮件。"}'
```

```json
{"input":[{"role":"user","content":[{"type":"text","text":"分析附件"},{"type":"file","file_id":"file_..."}]}]}
```

`message` 与 `input` 二选一。input 接受 1..100 条 user 消息，内容可为 text、image、audio、video、data 或已上传的 file 引用。媒体 source 使用 Core ContentBlock 格式，例如 `{"type":"url","url":"https://example.com/image.png"}` 或 `{"type":"base64","media_type":"image/png","data":"..."}`。实际可处理的媒体类型和大小取决于所选模型。确认和工具结果应通过 actions 提交，不能伪装成 system/assistant/tool 输入。

## 确认、取消与恢复

### 回答 required action

保存 `required_action.created` 或快照待办中的 request_id、turn_id、kind 和 tool_call。confirmation 答复如下；external_execution 使用 `output` 字符串及可选 `is_error`。

```bash
curl --fail-with-body -sS "$SESSION_URL/turns/$TURN_ID/actions" \
  -H "Authorization: Bearer $TOKEN" -H 'Content-Type: application/json' \
  -H 'Idempotency-Key: approval-001' \
  -d '{"answers":[{"request_id":"YOUR_REQUEST_ID","allow":true,"reason":"用户已确认"}]}'
```

每次接受 1..100 个真实待办答复。`required_action.accepted` 是持久接收，`resolved` 是运行时处理完成。`rejected` 是答复投递失败，不等于用户拒绝授权：仍待处理的请求会带着 kind/tool_call 回到待办视图；已解决的请求不会重新出现。`GET /turns/{turn}/actions` 查询答复命令的 accepted/resolved/rejected 状态。相同提交重试沿用同一个 key；修正被拒绝的答复使用新 key，并先核对当前待办。

### 取消执行

```bash
curl --fail-with-body -sS -X POST "$SESSION_URL/turns/$TURN_ID/cancel" -H "Authorization: Bearer $TOKEN"
```

取消请求与实际停止分开。等待明确 turn 结果；已经发出的外部操作不能保证撤销。取消一个空闲、中断或等待输入的任务会关闭其未决交互和未应用的 steering。结果未知的工具仍须先核对。

### 执行中断后恢复

```bash
curl --fail-with-body -sS -X POST "$SESSION_URL/turns/$TURN_ID/resume" \
  -H "Authorization: Bearer $TOKEN" -H 'Idempotency-Key: resume-001'
```

resume 保留 turn ID，恢复已提交状态并开始新的执行。它适用于 failed/interrupted，或没有未答交互的 requires_action；有待办先答复，有未知工具结果先核对。它不恢复线程或撤销外部副作用。建议携带稳定的 `Idempotency-Key`：响应丢失时复用该 key，只返回当前状态，不重复恢复。再次主动恢复使用新 key；正在运行或排队的任务不能发起新的 resume。

### 管理员检查 checkpoint 与工具结果

配置 `builder.agent-api.trace-enabled=true` 后，trace 仍要求 session 所有者校验及 `ROLE_ADMIN` 或 `ROLE_SESSION_TRACE`：

| 接口（相对 session URL） | 用途 |
| --- | --- |
| `GET /trace?after=0&limit=100` | 原生事件分页；after 是数字 native seq，limit 为 1..500，返回 data/as_of_seq/next_seq |
| `GET /trace/recovery` | asOfSeq、stateJson、uncertainToolCalls、activeRuns；只读检查 |
| `POST /trace/reconcile` | `{reason, outcomes}`，outcomes 为 toolCallId → ToolResultBlock；核对并记录未知工具结果 |
| `GET /trace/subagents/{child}?after=0&limit=100` | 读取父日志直接关联的 child session；不能访问任意 child |

以下管理示例假设已核实唯一未决调用 call-42 的结果，且当前 token 满足 trace 权限。先读取检查结果，再提交真实结果；不要直接复用示例 ID：

```bash
curl --fail-with-body -sS "$SESSION_URL/trace/recovery" \
  -H "Authorization: Bearer $TOKEN"
curl --fail-with-body -sS "$SESSION_URL/trace/reconcile" \
  -H "Authorization: Bearer $TOKEN" -H 'Content-Type: application/json' \
  -d '{"reason":"Verified order ORD-42 by business idempotency key","outcomes":{"call-42":{"type":"tool_result","id":"call-42","name":"create_order","state":"SUCCESS","output":[{"type":"text","text":"Order ORD-42 exists"}]}}}'
```

核对必须恰好覆盖全部未知 toolCallId，结果 ID 匹配且不是 suspended。确认外部结果后提交修复，再显式 resume。普通 checkpoint 操作见下文；它不能跳过未知工具结果的核对。

## 文件与产物

先上传不可变文件，再用 file_id 作为输入或发布为产物。上传、重试必须使用相同 key 和内容。

```bash
FILE_JSON=$(curl --fail-with-body -sS "$SESSION_URL/files" \
  -H "Authorization: Bearer $TOKEN" -H 'Idempotency-Key: report-file-001' \
  -H 'X-File-Name: report.pdf' -H 'Content-Type: application/pdf' --data-binary @report.pdf)
FILE_ID=$(printf '%s' "$FILE_JSON" | jq -er '.file_id')
curl --fail-with-body -sS "$SESSION_URL/artifacts" \
  -H "Authorization: Bearer $TOKEN" -H 'Content-Type: application/json' \
  -H 'Idempotency-Key: publish-report-001' -d "$(jq -n --arg id "$FILE_ID" '{file_id:$id}')"
curl --fail-with-body -sS "$SESSION_URL/files/$FILE_ID/content" \
  -H "Authorization: Bearer $TOKEN" -o downloaded-report.pdf
```

`X-File-Name` 中的非 ASCII 字符应使用 UTF-8 百分号编码。默认单文件上传上限为 16 MiB，模型输入、累计上下文及日志提交另有容量限制。下载仍要求 session 所有者身份，不是公开链接。也可发布外部 HTTPS 引用 `{name,uri,media_type?,sha256?}`。`DELETE /artifacts/{id}` 只撤销发布，不删除文件或历史。

## 子 Agent 和用量预算

`GET /subagents` 返回关联子会话，从事件中的 `childSessionId` 进入 `GET /subagents/{child}/snapshot`、`/events` 或 `/events/stream`。这些读取沿用父会话所有者校验，并验证真实父子关联。每个子会话有独立 item、turn、run 和 cursor；不要混用父子 cursor。异步子任务的最终状态以子日志为准。

`GET /usage` 统计当前 session 的已报告用量；加 `?include_children=true` 汇总关联子树，并返回各会话的 `session_watermarks`，它不是整棵树同时刻的原子快照。usage 按模型调用去重。配置计价后返回 `estimated_cost`、`currency`、`cost_complete` 和 `unpriced_calls`；未配置的价格不会假装为零费用。

```bash
curl --fail-with-body -sS -X PUT "$SESSION_URL/budget" \
  -H "Authorization: Bearer $TOKEN" -H 'Content-Type: application/json' \
  -d '{"max_model_calls":30,"max_total_tokens":100000}'
```

预算覆盖本会话及通过它派生的子 Agent 调用。`max_model_calls` 使用原子预留限制调用次数；`max_total_tokens`、`max_cost` 在下一次模型调用前依据已报告用量检查，不能硬截断正在进行或并行调用的 token/费用。费用限制须同时提供 currency 并配置计价。缺失用量/价格时相应限制拒绝继续；收到 `budget.exceeded` 后可调整预算并显式恢复失败任务。`GET /budget` 查看限制和核算状态；PUT `{}` 清除限制。该估算不是供应商账单。

## 从 checkpoint 继续试验或恢复上下文

`GET /checkpoints` 返回不透明 checkpoint_id、时间和原因，不返回原始 prompt/state。选择实际返回的 ID：

```bash
curl --fail-with-body -sS "$SESSION_URL/checkpoints" -H "Authorization: Bearer $TOKEN"
curl --fail-with-body -sS "$SESSION_URL/checkpoints/restore" \
  -H "Authorization: Bearer $TOKEN" -H 'Content-Type: application/json' \
  -H 'Idempotency-Key: restore-001' \
  -d '{"checkpoint_id":"YOUR_CHECKPOINT_ID","reason":"从核实过的上下文重新开始"}'
```

原地恢复保留审计历史，在日志中追加恢复事实，之后提交新 turn；它不把旧任务重新排队。需要保留原会话并另行试验时，先创建同一个 Agent 的空目标 session，再 POST `/fork`，请求为 `{target_session_id,checkpoint_id,reason}`，并带幂等键。目标的环境、凭据和工作文件需自行配置；fork 复制 Agent 状态，不克隆环境、子会话、托管文件或预算。

恢复/fork 要求相关 session 没有运行中、排队、未关闭任务、未答交互、未知工具结果或待消费输入。先完成、取消或核对它们。回到旧 checkpoint 不会撤销已经发生的工具副作用。`POST /restore` 仅解除 session 归档，与 `/checkpoints/restore` 不同。

需要导出业务审计记录时，GET `/export` 下载当前公共事件前缀的 JSONL；不包含私有 prompt、原始模型推理、凭据或完整 checkpoint。

## 页面离线时通过 Webhook 获取通知

业务后端可以注册 session webhook；不需要保持 SSE 长连接。目的地址必须是部署方允许的 HTTPS 主机（443 端口）。

```bash
curl --fail-with-body -sS "$SESSION_URL/webhooks" \
  -H "Authorization: Bearer $TOKEN" -H 'Content-Type: application/json' \
  -H 'Idempotency-Key: task-notifications-001' \
  -d '{"url":"https://notify.example.com/agent-events","event_types":["turn.completed","turn.failed","turn.requires_action"]}'
```

注册从当前事件水位开始，不回推旧历史。保存返回的 signing_secret；GET 列表不返回密钥。通知 body 包含 id、type、session_id、cursor、created_at 和 event_url，后端再带身份读取该事件。验证 `X-AgentScope-Signature`：用 signing_secret 的 UTF-8 字节作为 HMAC-SHA256 key，对 `X-AgentScope-Timestamp + "." + 原始请求体` 签名，结果为 `v1=<hex>`。使用恒定时间比较，并校验时间窗口及事件 ID 去重。

投递至少一次，收到通知后返回 2xx。失败指数退避，连续 8 次后暂停；GET `/webhooks/{id}/deliveries` 查看尝试，POST `/webhooks/{id}/retry` 继续，DELETE `/webhooks/{id}` 停用。正在投递时修改返回 409，稍后重试。网络异常可能造成重复通知，不应重复执行业务副作用。

## 存储分层与记录位置

| 内容 | 默认位置 | 用途 |
| --- | --- | --- |
| 工作文件 | Environment 的工作目录或所配置的 Filesystem 后端 | Agent 读写业务文件 |
| 原生日志与 checkpoint | 共享 BaseStore 的 runtime/sessions namespace | 执行恢复和工具结果核对 |
| 公共事件、turn/action 命令 | Data Plane 数据库 | HTTP 状态、历史、SSE 和持久调度 |
| 文件、预算、Webhook、资源投影 | 共享 BaseStore 的 runtime/agent-api namespace | 跨副本资源与恢复；投影可从事件重建 |

Filesystem 和 BaseStore 都可以使用分布式后端；多副本应指向同一个支持条件写入的持久存储。业务客户端无需依赖内部路径。部署方通过 `builder.agent-api.files.max-bytes`、`webhooks.allowed-hosts` 配置上传大小和通知地址；通过 `pricing.models` JSON 配置每百万输入/输出 token 单价及 `pricing.currency`，或实现 `SessionUsagePricer` Bean 接入自己的计价逻辑。

## 接入已有应用

Console 的 Session **Execution** 页签展示文字、多个工具调用、文件输入、steer/inject 和刷新恢复。可复用 `frontend/src/api/agentSessions.ts` 的请求/SSE 客户端与 `agentSessionView.ts` 的状态更新器。消息生成途中刷新、工具执行途中离开后返回，均按 snapshot + 增量更新同一组资源。

旧 `/api/sessions`、Chat 与 Endpoint 的协议仍用于各自入口，不应混用 cursor 或事件 DTO。机器可读契约在 `agentscope-service/docs/agent-api/openapi-v1.json` 和 `public-event-v1.schema.json`；路由索引见 [API 参考](/v2/zh/service/api-reference)。

部署方的文件大小、Webhook 主机和模型计价选项见[配置参考](/v2/zh/service/configuration#agent-api-配置)。

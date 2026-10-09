---
title: "会话、任务与预算"
description: "在提交任务后补充要求、回答待办、取消或恢复执行，并管理会话用量与预算。"
en_link: /v2/en/service/session-event-log
---

Session 保存应用与执行目标之间的一段工作记录，Turn 表示其中一次提交的任务。Agent、Team 和 Workflow 都通过同一套 Session API 接收工作；Managed Agent 在这个入口上进一步提供连续对话、运行中补充输入、上下文恢复和子 Agent 等能力。关闭页面或断开事件连接不会取消后台任务。

首次接入请先完成[通过 Session API 接入应用](/v2/zh/service/service-api)，取得 `SESSION_URL`、`TURN_ID` 和应用凭据 `AGENTSCOPE_API_KEY`。提交任务后，用户可能需要调整要求、回答 Agent 的询问，或者停止并恢复执行，本页说明这些操作如何作用于已有的 Session 和 Turn。请求也可以使用有权访问该 Session 的平台用户 Bearer token；应用凭据必须同时具有相应 scope 和目标资源授权。

## 理解 Session 与 Turn 的关系

创建 Session 时，Service 会保存目标和当时的配置。随后提交的新 Turn 沿用这份配置，因此修改 Agent 定义不会悄悄改变已有会话。需要使用新配置时应创建新的 Session。Workflow 会话绑定的是已发布的 revision；可以显式选择版本，也可以在创建时使用最新的已发布版本。

同一个 Session 中的 Turn 按接收顺序执行。如果前一个任务正在等待审批，后续提交会进入队列，直到前面的任务结束。Managed Agent 会在任务之间保留对话上下文；Team 和 Workflow 的每个 Turn 启动一项独立工作，Session 将它们的进度与结果组织在一起。这种统一调用方式不意味着所有执行目标都有相同的记忆和交互能力，应用应先读取 `/capabilities` 再显示相应操作。

如果提交请求返回 `202 Accepted`，且状态为 `queued`，表示任务已经接收，但尚未完成。网络重试必须带上同一个 `Idempotency-Key` 和相同的请求内容，这样服务才会返回原来的 Turn。只有用户发起新任务时才使用新的 key。刷新页面时读取已有 Session 的快照和事件，不要重新发送输入。

## 读取结果与恢复页面

应用可以通过 `GET /turns/{turnId}` 查询任务状态，也可以读取 Session 的 `/snapshot` 恢复界面，再从快照返回的 `as_of` 连接 `/events/stream`。如果只显示某个任务，则成对使用这个 Turn 的 `/snapshot` 和 `/events/stream`，不能混用 Session 与 Turn 的游标。

Managed Session 的快照保留消息、工具调用、输入、待办和子 Agent 等运行时资源。Team、Workflow 与通用 Turn 快照使用按 ID 索引的集合，并包含执行步骤。客户端需要根据快照类型读取这些字段；统一的是会话和任务的身份、控制路径以及事件续传方式。事件字段和累计更新规则见 [SSE 与事件续传](/v2/zh/service/sse-events)。

## 补充要求与背景材料

用户想继续提出一个新的问题时，应提交新的 Turn。如果用户要修改当前任务的后续要求，则可以对支持此能力的 Managed Turn 调用 `steer`。修改会在后续执行步骤生效，不能改变已经发送给模型的请求。只希望补充背景而不立即启动推理时，可以调用 Session 的 `/inputs/inject`。

```bash
curl --fail-with-body -sS "$SESSION_URL/turns/$TURN_ID/steer" \
  -H "X-API-Key: $AGENTSCOPE_API_KEY" -H 'Content-Type: application/json' \
  -H 'Idempotency-Key: correction-001' \
  -d '{"message":"先核实预算，暂时不要发送邮件。"}'
```

Managed 输入可以使用非空 `message`，也可以使用包含用户消息的 `input` 数组，二者只能选一个。结构化输入用于传递文本和文件等内容，上传与引用方式见[文件与产物](/v2/zh/service/files)。`input.accepted` 表示输入已保存，`input.applied` 才表示运行时已使用它。应用不要把“接收成功”显示成“Agent 已处理”。

## 回答 required action

Agent 需要用户确认或等待外部执行结果时，会生成 required action。应用应保存快照或事件中的 `request_id`，向用户说明所请求的操作，再将真实决定提交到对应 Turn。下面是一条工具确认答复，`request_id` 必须使用实际收到的值：

```bash
curl --fail-with-body -sS "$SESSION_URL/turns/$TURN_ID/actions" \
  -H "Authorization: Bearer $TOKEN" -H 'Content-Type: application/json' \
  -H 'Idempotency-Key: approval-001' \
  -d '{"request_id":"RETURNED_REQUEST_ID","payload":{"allow":true,"reason":"用户已确认"}}'
```

接口返回命令回执和状态地址。应用继续查询回执，直到命令执行完毕，同时等待任务状态变化；提交答复成功不代表整个任务已经完成。相同答复重试时沿用原来的 key，修改答复时先重新读取待办。`interact` scope 只允许调用交互接口，不会把应用密钥变成指定的人工审批人；涉及身份约束的业务审批仍需使用被授权的用户身份。

## 取消与恢复任务

取消和恢复都作用于已有 Turn，并要求稳定的幂等键。收到取消回执后，应用仍要等待任务进入明确终态。取消无法保证撤销已经发送给外部系统的操作，因此再次执行之前，应核实这些操作的结果。

```bash
curl --fail-with-body -sS -X POST "$SESSION_URL/turns/$TURN_ID/cancel" \
  -H "X-API-Key: $AGENTSCOPE_API_KEY" -H 'Idempotency-Key: cancel-001'
```

当 `/turns/{turnId}/capabilities` 的 `available_commands` 包含 `resume` 时，可以向同一路径下的 `/resume` 提交恢复命令。恢复保留原 Turn ID，并从已保存的状态继续。若任务仍在等待用户答复，应先处理待办；若工具调用结果未知，应先核实结果。不要把恢复当成重新提交任务的通用替代品。

<span id="budgets"></span>
<span id="选择限制层次"></span>
<span id="原生会话与子-agent-用量"></span>
<span id="发布服务的配额"></span>
<span id="验证预算生效"></span>

## 用量、子 Agent 与预算

Managed Session 的 `/subagents` 可以列出它派生的子会话。应用只需沿返回的关联读取子会话快照和事件；子会话有自己的事件游标，不能与父会话混用。`GET /usage?include_children=true` 可以汇总已报告的子树用量，但这不是所有子任务同一时刻的原子快照。

如果需要限制 Managed 运行时的消耗，可以通过 `/budget` 设置模型调用次数或累计用量限制。下面的配置允许最多 30 次模型调用，并在继续执行前检查累计 token 是否已达到 100000：

```bash
curl --fail-with-body -sS -X PUT "$SESSION_URL/budget" \
  -H "X-API-Key: $AGENTSCOPE_API_KEY" -H 'Content-Type: application/json' \
  -d '{"max_model_calls":30,"max_total_tokens":100000}'
```

Token 与费用预算依据已报告用量检查，无法硬截断已经开始的并行调用。费用限制还需要配置模型计价。Application 的并发和 token 预算则用于管理整个应用的调用额度，与单个 Managed Session 的运行时预算分别生效；相关字段见[服务 API](/v2/zh/service/service-api)。

## 从 checkpoint 继续试验

Managed Session 的 `/checkpoints` 返回可恢复的上下文记录。选择实际返回的 `checkpoint_id` 后，可以调用 `/checkpoints/restore` 恢复上下文，再提交新的 Turn。恢复会追加审计记录，不会删除旧历史，也不会撤销已经发生的外部操作。

需要保留原会话并另行试验时，先创建同一 Agent 的空 Session，再对原 Session 调用 `/fork`，提交 `{target_session_id, checkpoint_id, reason}` 和幂等键。相关会话需要处于可以恢复的空闲状态，不应存在排队任务、未答交互或未知工具结果。Fork 复制 Agent 状态，不复制目标环境、凭据、工作文件或预算。

Session 的 `/archive` 暂停新的提交，`/restore` 解除归档；它们与 checkpoint 恢复不同。删除 Session 会使它不再能通过应用接口访问，并不等同于立即擦除底层审计记录。备份和保留策略见[运维指南](/v2/zh/service/operations)。

## 后台通知与客户端复用

用户离开页面后，业务后端可以使用 Session 或 Turn 的 Webhook 接收结果通知，具体签名和重试方式见 [Webhook](/v2/zh/service/sse-events#webhooks)。需要导出 Managed 运行记录时，可以使用 `/export` 获取公共事件的 JSONL。

控制台使用 `frontend/src/api/agentSessions.ts` 展示 Managed 的完整执行资源，通用应用调用使用 `serviceSessions.ts`。这两种界面都访问同一套 Session API。自行开发前端时，请保持快照与事件配对，并将服务返回的 ID 当作不透明值保存。

如果要把这些操作接入自己的聊天页面，可以继续阅读[接入示例：可恢复的聊天应用](/v2/zh/service/agent-api-chat)。示例把任务提交、进度展示和用户操作连在一起，并说明用户离开或刷新页面后如何继续同一段工作。

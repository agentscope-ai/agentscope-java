---
title: "统一服务 API：Agent、Team 与 Workflow"
description: 发布 Agent 服务，提交工作，通过统一事件流恢复页面、处理交互并取得结果。
en_link: /v2/en/service/service-api
---

要把 Agent 能力接入自己的业务系统，先发布一个 Endpoint。目标可以是单个 Agent、Team，或已发布的 Workflow。客户端使用相同的 Invocation API 提交、观察和控制工作；Team 内部的 Issue、成员分工和协作消息由服务管理。

直接开发 Java Agent 时，从 `agent.call` 或 `agent.streamEvents` 开始；需要管理本地多轮会话时使用 [AgentSession](/v2/zh/docs/harness/session-log)。本页面向通过 HTTP 调用已发布服务的业务应用。需要直接操作 Managed Agent 的文件、子会话或 checkpoint 时，查看 [Managed Agent API](/v2/zh/service/session-event-log)。

## 先认识三个资源

| 资源 | 业务含义 |
| --- | --- |
| Endpoint | 稳定服务地址及发布契约，包括输入输出 schema、运行目标和调用策略 |
| Invocation | 一次逻辑调用；保存其 ID 即可查询状态、事件、待办和结果 |
| Conversation | 支持多轮交互的 Agent 会话；每次提交生成一个独立 Invocation |

Agent、Team、Workflow 都可以提供 Job。Conversation 要求目标具有会话能力；Team 和 Workflow 使用 Job。先读取 `GET /invoke/v1/endpoints/{slug}/capabilities`，确认当前目标与支持的操作。重连事件流只是恢复观察，不会创建新 Invocation，也不等于从 checkpoint 恢复执行。

## 提交工作并显示进展

按 [Endpoint 发布指南](/v2/zh/service/endpoints)创建并发布入口。下面假定该入口的 input schema 接受 `request`：

```bash
curl --fail-with-body "$BASE_URL/invoke/v1/endpoints/report/jobs" \
  -H "X-API-Key: $ENDPOINT_KEY" -H 'Content-Type: application/json' \
  -H 'Idempotency-Key: order-001' \
  --data '{"title":"调查订单","input":{"request":"核对交付进展"}}'
```

保存响应中的 `invocationId`、`statusUrl`、`snapshotUrl`、`eventsUrl`。`202` 表示请求已持久接收；后台建立执行，即使提交请求的连接已关闭也会继续处理。相同 key 和相同内容返回原调用；改变内容但复用 key 会得到 `409`。

`GET statusUrl` 返回 `invocation.status` 与最终 `invocation.result`。状态可能是 accepted、dispatching、running、waiting、cancel_requested；completed、partial_succeeded、failed、cancelled、timed_out 是终态。`partial_succeeded` 表示仍有可用输出但存在失败分支，应同时检查 `steps`，不能视为完整成功。以 Invocation 的终态判断整体结果，不能把某个成员的消息或工具完成当作整个 Team 完成。

## 刷新页面、离开后回来

1. `GET snapshotUrl`，渲染 `items`、`tools`、`required_actions`、`steps`、`artifacts` 和 `usage`。
2. 使用快照的 `as_of` 请求 `eventsUrl?after=...`，继续应用增量。
3. 临时断线且本地视图仍在时，从最后成功应用的 cursor 续传；页面状态丢失时重新读快照。

这些快照字段是按标识索引的对象，值为累计数据。`items[item_id].item.content` 包括进行中消息已持久提交的文本；工具卡按 `execution_id + ':' + tool_call_id` 索引。多个成员、多个助手消息和离开期间完成的工具结果都会保留。

```text
id: <opaque-cursor>
event: item.delta
data: {"schema_version":1,"id":"event-id","invocation_id":"invocation-id","type":"item.delta","created_at":1790928000000,"cursor":"<opaque-cursor>","data":{"execution_id":"execution-id","item_id":"message-id","content":[{"type":"text","text":"正在核对"}]}}
```

cursor 不透明，只属于当前 Invocation。`Last-Event-ID` 优先于 `after`。JSON 历史分页使用 `GET /invoke/v1/invocations/{id}/events?after=...&limit=100`，返回 `data`、`next_cursor`、`has_more`。忽略未知事件类型，但仍更新 cursor。心跳是 SSE 注释，不是业务事件。

| 事件 | 页面处理 |
| --- | --- |
| invocation.accepted / dispatching / running / waiting | 更新整体工作状态 |
| invocation.cancel_requested / completed / partial_succeeded / failed / cancelled / timed_out | 更新停止进度或最终结果 |
| item.started / delta / completed | 按 item_id 创建、累积和替换消息 |
| tool.requested / dispatched / delta / completed | 按执行和工具调用 ID 更新参数、进度和结果 |
| execution.ended | 将未完成消息标为 incomplete；未确认结束的工具标为 unknown |
| step.updated / step.failed | 展示成员任务或执行步骤的状态 |
| required_action.created / resolved | 创建或移除当前待办 |
| command.accepted / completed / failed | 区分交互命令已保存、已投递和失败 |
| artifact.published / deleted | 更新交付物；使用返回的 download_url |
| usage.recorded / model.completed | 按执行及模型调用 ID 更新用量，避免重复累加 |
| budget.exceeded | 用量达到配置预算，服务开始取消执行 |

不同运行时可提供不同事件粒度。只上报完整消息的运行时不会被伪造成逐 token 输出。原始模型思考内容和内部协作 DTO 不属于此公共协议。

## 回答待办、补充输入与取消

以下路径都相对 `/invoke/v1/invocations/{id}`，POST 命令要求 `Idempotency-Key`。命令返回 `command` 及 `status_url`；等待命令状态和后续业务事件确认实际结果。

| 操作 | API 与请求 |
| --- | --- |
| 读取当前待办 | `GET /actions` |
| 回答 Workflow signal | `POST /actions`，`{"request_id":"返回的 ID","expected_version":3,"payload":{"answer":"..."}}` |
| 审批 | `POST /actions`，`{"request_id":"返回的 ID","expected_version":3,"decision":"approved","payload":{"reason":"已核实"}}` |
| 补充业务要求 | `POST /inputs`，`{"message":"优先核对已付款部分"}` |
| 停止工作 | `POST /cancel`，`{}`；随后等待 cancelled 或其他已确认终态 |
| 继续暂停的 Workflow / 中断的 Managed turn | `POST /resume`，`{}`；等待交互的工作应回答对应 action |
| 查询某条命令 | `GET /commands/{commandId}` |

审批只能由指定审批人完成。Managed 工具确认要求所有者的平台身份，或 Application 中显式授权的 approver；API key 的 `interact` scope 本身不授予工具审批权。Managed 原生待办的 ID 以 `native:` 开头，答复放在 `payload`：确认使用 `{"allow":true,"reason":"..."}`，外部工具执行使用 `{"output":"...","is_error":false}`。

Job 的补充输入通过原有协作输入机制交付，Agent 在安全边界重新读取任务上下文；Managed Conversation 使用原生 steer。命令投递成功不表示模型已消费输入。External Conversation 是否接受执行中输入取决于其运行时，目前应在当前轮结束后提交下一轮。

取消会覆盖 Invocation 归属的子执行，先进入 cancel_requested，等待实际尝试停止。已经结束的 Invocation 不会被 resume 重新打开；需要重新执行时提交新的业务请求。恢复 Managed checkpoint 是独立能力，使用原生 Managed API，并遵守其运行状态要求。

## 多轮会话与版本

`POST /invoke/v1/endpoints/{slug}/conversations` 的 body 为 `{"message":"..."}`。保存 conversationId，下一轮使用 `POST /invoke/v1/conversations/{conversationId}/turns` 和新的幂等键。一个 Conversation 同时只接受一个活动 Invocation。

发布 release 时保存输入输出契约、目标及显式声明的 Team/Agent 运行配置；Invocation 与 Conversation 绑定该 release。后续修改或回滚入口不会改写已有调用。输出违反发布的 JSON Schema 时，调用以 `failed` / `output_schema_violation` 结束。

运行时实例健康、凭据撤销和权限仍按当前状态检查。显式引用的嵌套 Workflow 随 release 递归冻结，运行中创建的子 Workflow 不能通过未声明定义绕过发布契约。动态 Agent/Team 委派遵守发布的委派策略。JSON Schema 支持嵌套约束及本地 `$defs` 引用，不加载远程 `$ref`。

## 凭据、预算和后台通知

先通过 `POST /api/v1/applications` 创建 Application，传入 `tenant`、`namespace`、`name`。创建 Endpoint 不再自动生成凭据；调用 `POST /api/v1/endpoints/{endpointId}/credentials`，显式传入 `applicationId`、`name` 和非空 `scopes`。支持 `invoke` 提交、`read` 查询、`cancel` 取消、`interact` 输入和交互、`webhooks:write` 回调管理，不隐式授予默认 scope。

同一 Application 的多把 key 共享调用归属，轮换 key 后仍能查询原调用。轮换会创建替代凭据，迁移期间旧凭据仍然有效；先迁移调用方，再显式撤销旧凭据。只有 Application owner 能管理成员和凭据。`members: [{userId, roles: ["viewer", "operator", "approver"]}]` 允许对应平台用户查询、操作或参与审批；实际批准仍须是待办指定审批人。PATCH 必须携带 `version`，停用 Application 后禁止新调用及交互；已获授权的人类平台用户仍可查询和取消已有工作。长期 key 保存在业务后端。

Application 的 `maxConcurrent` 控制跨 Endpoint、多凭据的活动调用总数；`tokenBudget` 是应用累计 Token 预算，`tokensUsed` 为只读已报告用量，0 表示不限。它们与 Endpoint 限制分层生效。

Endpoint 的 `rateLimit` 可配置 `requests` / `windowSeconds`、`maxConcurrent`、`maxInvocationTokens`。前两项控制调用频率，并发上限控制活动调用数；Token 预算根据已上报用量取消执行，存在上报延迟，不是预付费硬限额。配置了预算的运行时应上报用量。

`POST /webhooks`：`{"url":"https://your-app.example/events","event_types":["invocation.completed","required_action.created"]}`。保存返回的 signing_secret。服务按事件 ID 投递、失败退避重试；接收方按事件 ID 去重。签名头 `X-AgentScope-Signature` 为 `t=<秒>,v1=<hex>`，校验 `HMAC-SHA256(secret, t + '.' + 原始请求体)`，并检查时间窗口。

`GET /webhooks` 查询状态。失败采用指数退避，连续 12 次失败后订阅进入 `failed` 并停止自动投递；`POST /webhooks/{id}/retry` 清空失败计数并重投尚未确认的事件，`DELETE /webhooks/{id}` 停用。回调只支持公网 HTTPS，不跟随重定向；请求凭据与签名密钥不会写进事件。回调通知不替代查询最终结果。

## 输出映射与运行能力

Endpoint 的 `resultMapping` 把对外字段名映射到原始完整结果中的 RFC 6901 JSON Pointer，例如 `{"answer":"/report/text","sources":"/report/sources"}`。先映射，再校验发布时的 outputSchema。缺失或无效路径以 `output_mapping_failed` 失败，schema 不符以 `output_schema_violation` 失败；省略映射时保留原始结果。映射随 release 冻结。

Endpoint capabilities 表示发布候选运行时共同保证的能力（`capability_basis: all_published_candidates`）。提交后查询 `GET /invoke/v1/invocations/{id}/capabilities`，它反映实际选定 binding，且 `available_commands` 按当前状态过滤。取消、输入、恢复按钮应依据这个结果展示。公共 `checkpoint_restore` 仍为 false，checkpoint 使用原生 Managed API。

## 保留期限与过期游标

`aistiod --service-event-retention` 默认 720h，0 关闭清理。仅超过保留时间的终态调用清理历史增量；完整累计 snapshot 和来源事件去重记录仍保留。过期 cursor 返回 HTTP 410，body 包含 `error: cursor_expired` 和 `snapshot_url`。重新读取并替换快照，再从新的 `as_of` 续订，不要反复重试旧 cursor。

## 可运行示例与客户端

仓库 `agentscope-service/aistio/examples/service-api` 提供 `bootstrap.py`、`worker.py`、`client.py`。Bootstrap 完成创建 Application、注册两类可执行 Agent、配置策略、创建 Team/Workflow、发布三类 Endpoint 和签发凭据，全程无需 Console。默认不产生模型费用，可显式加入真实 Managed 成员和指定审批人。README 包含断线恢复、取消、补充输入、审批、命令查询和结果读取的完整命令。

Python 的 `aistio.ServiceClient` 提供调用操作；`aistio.ManagementClient` 提供 Application、Agent、Team、Workflow、策略、Endpoint、release 和凭据管理；TypeScript 客户端和快照 reducer 位于 `agentscope-service/frontend/src/api/serviceInvocations.ts`。浏览器通过业务后端代理访问，把长期 key 保存在后端。公共 OpenAPI 与事件 schema 位于 `agentscope-service/docs/service-api/`。

```ts
const snapshot = await api.snapshot(invocationId);
const view = new ServiceView(snapshot);
render(view.snapshot());
for await (const event of api.stream(invocationId, snapshot.as_of, signal)) {
  view.apply(event);
  render(view.snapshot());
}
```

公共 Invocation 日志、快照、命令和回调游标保存在 Service 的持久 Store；使用 PostgreSQL 部署可跨进程、跨副本恢复。Agent 自己的 session log 和 checkpoint 仍由其 Workspace/Filesystem 存储，可能使用分布式后端。这两层分别服务于业务调用追踪与原生执行恢复。

Python worker 默认使用 `instrument(..., control_plane_http=base, transport="http")`：通过出站 `/api/v1/agent-runtime/exchange` 交换带执行归属校验的命令和报告，命令持久保存到 worker 接收确认，无需 worker 入站端口或对外暴露的 gRPC 端口。启用 ASDP 时可选 `transport="grpc"`。`AsyncInvokeAdapter` 执行每任务新建的 `ainvoke` 实例；`AgentScopeRunnerAdapter` 执行每任务新建的异步 AgentScope Agent 并挂载原生观测 hook；都要求显式任务输入映射。只有观测能力的 adapter 不会自动执行平台任务。

当前服务端仍需保持 `--enable-asdp=true`，因为 HTTP 执行复用其处理器；服务会初始化本地 gRPC listener，但 HTTP worker 不需要访问该端口。

External SDK 单条事件限制 16 MiB；HTTP 按约 16 MiB 分批、单次请求上限 32 MiB，gRPC 消息上限 32 MiB。超限记录明确失败，不截断内容；大工具结果应发布为 artifact，事件中保存引用。

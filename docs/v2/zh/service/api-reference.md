---
title: "API 参考：认证、资源与调用"
en_link: /v2/en/service/api-reference
---

<Note>
此为预览文档，正式版本尚未发布。
</Note>

使用 Gateway 作为 API base URL。平台 Agent API 覆盖管理、发布、调用、反馈和编排；面向业务的统一入口是 Endpoint / Invocation，Managed 原生会话是其中一组运行时扩展。按场景上手见[API 快速开始](/v2/zh/service/first-session)，本页集中列出路径、参数和认证边界。

## 认证与范围

| 身份 | 使用位置 |
| --- | --- |
| 用户 Bearer token | Agent API、控制台管理、Chat、Issue、Workflow 等产品 API |
| Endpoint API key | `X-API-Key`，仅用于对应 Endpoint 调用 |
| Runtime Host credential | Host 注册、心跳与领取执行 |
| Task / Attempt token | 注入任务的有限协作或回报操作 |
| Environment key | `X-Builder-Environment-Key`，Worker 工具执行协议 |
| 内部服务令牌 | 受信任组件间调用，不能代替普通用户身份 |

`POST /api/auth/login` 接受 `{"username":"...","password":"..."}`，响应包含 `token`。随后用 `Authorization: Bearer TOKEN`。`GET /api/auth/me` 检查身份，`POST /api/auth/logout` 退出。

空间请求可携带 `X-AgentScope-Tenant`、`X-AgentScope-Namespace`；有 tenant/namespace 请求字段时保持一致。单空间安装由服务器确定权威范围；多空间安装使用当前账号已授权的值，不假定存在名为 default 的共享空间。

## 常用资源

以下路径均相对 Gateway，`{id}` 为响应中的资源 ID，而不是显示名称。

| 操作 | 方法和路径 |
| --- | --- |
| Agent 目录、创建 | `GET /api/v1/agents`、`POST /api/v1/agents` |
| 更新定义 | `PATCH /api/v1/agents/{id}/definition` |
| 可对话 Agent | `GET /api/v1/chat-agents` |
| Chat 列表、创建 | `GET /api/v1/chats`、`POST /api/v1/chats` |
| 发送 Chat 消息 | `POST /api/v1/chats/{id}/turns` |
| Issue 列表、创建 | `GET /api/v1/issues`、`POST /api/v1/issues` |
| Issue 汇总、评论 | `GET /api/v1/issues/{id}/summary`、`GET/POST /api/v1/issues/{id}/comments` |
| 验收、退回 | `POST /api/v1/issues/{id}/accept`、`POST /api/v1/issues/{id}/reject` |
| Inbox、审批 | `GET /api/v1/inbox`、`POST /api/v1/approvals/{id}/decide` |
| Team | `GET/POST /api/v1/teams` |
| Workflow | `GET/POST /api/v1/orchestration-definitions` |
| Workflow 发布 | `POST /api/v1/orchestration-definitions/{id}/publish` |
| 执行图、事件 | `GET /api/v1/orchestration-runs/{id}/graph`、`GET /api/v1/orchestration-runs/{id}/events` |
| Automation | `GET/POST /api/v1/automations` |
| Endpoint 管理 | `GET/POST /api/v1/endpoints` |

## 统一 Agent 目录与运行绑定

以下接口使用平台身份；创建和列表的 `tenant`、`namespace` 与请求头范围保持一致。Agent 的 `id`、`agentKey` 和展示名称不是同一个字段。创建/注册的操作示例见[Agent 管理](/v2/zh/service/agents)。

| 方法和路径 | 请求参数 | 响应与用途 |
| --- | --- | --- |
| `POST /api/v1/agents` | `agentKey`；`tenant`、`namespace`、`displayName`、`description`；可带 `binding`、`definition` | `{agent,binding,policy,definition}`；Managed/Hosted 一次准备运行绑定与行为定义；没有绑定只创建目录记录 |
| `GET /api/v1/agents` | 查询 `tenant`、`namespace`、`status`、`includeArchived`、`limit` | `{items:[Agent]}` |
| `GET/PATCH /api/v1/agents/{id}` | PATCH：`version`，及 `displayName`、`description`、`status` 等修改项 | `{agent}`；归档使用 `status:"archived"`，不是删除运行历史 |
| `GET/PATCH /api/v1/agents/{id}/definition` | PATCH：`name`、定义 `version` 和完整保留后的行为字段 | `{definition}`，更新响应还包含 `agent`；[定义参数](/v2/zh/service/managed-agent-configuration) |
| `GET /api/v1/agents/{id}/versions` / `/{version}` | Agent ID；单版查询用定义版本号 | 定义历史，不等于 Endpoint release |
| `GET/POST /api/v1/agents/{id}/bindings` | 创建使用 `kind`、`configuration`、`priority`、`enabled` | 从列表读取绑定；修改使用 `PATCH .../bindings/{bindingId}` 和 `version`，完整提交 `configuration`、`priority`、`enabled` |
| `GET /api/v1/agents/{id}/instances` / `/runtime-inventory` / `/overview` | Agent ID | 实例、External 运行报告、概览；无报告不能推断为执行就绪 |
| `GET/PUT /api/v1/agent-runtime-policies/{id}` | PUT：`tenant`、`namespace`、`agentId`、`selectionMode`、`fallbackMode`、`candidates` 等 | 选择运行候选；参数见[执行与运行策略](/v2/zh/service/team-configuration) |
| `GET /api/v1/agents/runtime-options` | 查询 `tenant`、`namespace` | `{runtimes,profiles,pools}`，供 Hosted 选择 Runtime |

`binding.kind` 使用 `managed`、`hosted-runtime` 或 `external-application`。Managed 创建时由平台生成绑定配置；Hosted 配置使用 `runtimeProfileId`、`runtimePoolId`。External 使用独立注册流程，不通过普通创建接口直接伪造一个在线实例。

`POST /api/v1/agent-registrations` 接受 `agentKey`、`instanceKey`、范围、`framework`、`routingKey`、`capabilities` 等，返回 `agent`、`binding`、`instance`、`registrationCredential`。当前注册入口不校验调用身份，需由部署方限制受信任接入范围；返回注册凭据不代表首次注册已经通过身份认证。字段和实际传输接入见[External 参数参考](/v2/zh/service/external-agent-configuration)。

## Application、Endpoint 与发布参数

管理操作使用平台身份。Application 的管理由其 owner 完成；运行调用由 Endpoint 的认证策略、Application 和凭据 scope 共同约束。

| 方法和路径 | 关键参数 | 返回或行为 |
| --- | --- | --- |
| `POST /api/v1/applications` | `tenant`、`namespace`、`name`，可选 `description`、`members`、`maxConcurrent`、`tokenBudget` | `{application}`；保存 `application.id` |
| `GET /api/v1/applications` / `/{id}` | 列表按空间查询 | Application 列表或详情 |
| `PATCH /api/v1/applications/{id}` | `version`；可改 `name`、`description`、`status`、`members`、`maxConcurrent`、`tokenBudget` | `members` 中使用 `userId` 与 `roles`；角色为 `viewer`、`operator`、`approver` |
| `POST /api/v1/endpoints` | `tenant`、`namespace`、`name`、`slug`、`targetType`、`targetRef`、`invocationMode`、`authPolicy` | `{endpoint}`，初始 draft；不自动签发 key |
| `GET /api/v1/endpoints` / `/{id}` / `/{id}/readiness` | Endpoint ID；列表按空间查询 | 定义或目标就绪信息 |
| `PATCH /api/v1/endpoints/{id}` | `version`；可改 `name`、`description`、schema、`resultMapping`、`rateLimit`、`timeoutSeconds`、`maxPayloadBytes` | 已发布 schema/resultMapping 不允许通过普通 PATCH 原地改变 |
| `POST /api/v1/endpoints/{id}/publish` / `/disable` | `version` | 发布/停止新调用；停用不等于取消已有工作 |
| `GET/POST /api/v1/endpoints/{id}/releases` | POST：`version`、`targetRef`、可选 `reason` | 发布固定目标与契约的 release |
| `POST /api/v1/endpoints/{id}/releases/{releaseId}/rollback` | `version` | 选择旧 release；不回滚外部副作用 |
| `POST /api/v1/endpoints/{id}/credentials` | `applicationId`、`name`、非空 `scopes`，可选 `expiresAt` | `{credential,secret}`；保存 key 到调用方后端 |
| `GET /api/v1/endpoints/{id}/credentials` | Endpoint ID | 凭据列表 |
| `POST /api/v1/endpoints/{id}/credentials/{credentialId}/rotate` | 目标凭据 ID | 新凭据与 secret；旧 key 仍有效直到显式撤销 |
| `DELETE /api/v1/endpoints/{id}/credentials/{credentialId}` | 目标凭据 ID | 撤销凭据 |
| `DELETE /api/v1/endpoints/{id}` | `version` | 归档 Endpoint |

`targetType` 为 `agent`、`team`、`orchestration_revision`；最后一种要求已发布 revision ID。`invocationMode` 为 `job` 或 `conversation`，Team/Workflow 当前仅支持 job。`authPolicy.type` 为 `api_key` 或 `platform`。

输入、输出分别由 `inputSchema`、`outputSchema` 校验；`resultMapping` 将字段名映射到完整结果中的 JSON Pointer。`rateLimit` 可含 `requests`、`windowSeconds`、`maxConcurrent`、`maxInvocationTokens`。Application 的 `maxConcurrent` 跨 Endpoint/key 生效，`tokenBudget` 是按已上报用量控制的累计额度，0 表示不限，不是预付费硬限额。

凭据 scope 为 `invoke`、`read`、`cancel`、`interact`、`webhooks:write`。`interact` 不自动授予工具审批人的资格；仍需检查待办指定身份。完整发布示例见[Endpoint](/v2/zh/service/endpoints)。

<span id="endpoint-调用"></span>

## 统一 Invocation API 与参数

以下资源适用于通过 Endpoint 调用的 Agent、Team 和 Workflow。调用认证遵循 Endpoint 策略；管理 token、Endpoint key 与执行器凭据不可互换。

| 操作 | 方法和路径 | 请求参数 / 返回重点 |
| --- | --- | --- |
| 读取发布能力 | `GET /invoke/v1/endpoints/{slug}/capabilities` | 发布候选共同保证的能力 |
| 提交工作 | `POST /invoke/v1/endpoints/{slug}/jobs` | `title`、可选 `description`、符合 inputSchema 的 `input`；要求 `Idempotency-Key` |
| 创建多轮会话 | `POST /invoke/v1/endpoints/{slug}/conversations` | `message`，要求幂等键；返回 conversationId 与首轮 invocationId |
| 后续轮次 | `POST /invoke/v1/conversations/{id}/turns` | `message`，新工作使用新幂等键；同时只允许一个活动 Invocation |
| 状态与能力 | `GET /invoke/v1/invocations/{id}` / `/capabilities` | `invocation.status`、`result`；`available_commands` 按当前状态过滤 |
| 页面快照 | `GET /invoke/v1/invocations/{id}/snapshot` | `as_of`、`invocation` 状态对象，以及按 ID 索引的 `items/tools/required_actions/steps/artifacts/usage` |
| 历史分页 / SSE | `GET .../{id}/events` / `/events/stream` | `after`、历史 `limit`；SSE 可用 `Last-Event-ID`，优先于 after |
| 读取待办 / 回答 | `GET/POST .../{id}/actions` | POST：`request_id`、必要的 `expected_version`，按待办提交 `decision` 或 `payload` |
| 补充输入 | `POST .../{id}/inputs` | `message`；目标必须支持 inputs |
| 取消 / 恢复 | `POST .../{id}/cancel` / `/resume` | `{}`；按 capabilities 和当前状态判断是否支持 |
| 查询命令 | `GET .../{id}/commands/{commandId}` | 区分命令接收、完成与失败 |
| 用量 / 产物 | `GET .../{id}/usage` / `/artifacts` | 已上报用量、可下载产物引用 |
| Webhook 注册 / 查询 | `POST/GET .../{id}/webhooks` | POST：`url`、`event_types`，返回签名 secret；要求 `webhooks:write` |
| 重试 / 停用通知 | `POST .../{id}/webhooks/{webhookId}/retry`；`DELETE .../{id}/webhooks/{webhookId}` | 对未确认投递重试，或停用订阅 |

表中 `.../{id}` 表示 `/invoke/v1/invocations/{id}`。提交与交互命令使用逻辑请求级 `Idempotency-Key`。普通提交返回 `202`，命令返回回执及状态地址；接收成功不等于模型已处理或操作已完成。

统一接口不抹平能力差异：Job 支持任务层输入、审批和取消，Workflow Job 可按状态恢复；Managed Conversation 支持原生交互，Hosted Conversation 当前支持取消，External 取消依赖 `session-abort`。公共 checkpoint_restore 当前为 false。完整请求体与前端处理见[统一服务 API](/v2/zh/service/service-api)。

## 任务、编排、自动化与资源参数

以下参考覆盖创建、更新和反馈参数；各页同时给出 API 操作与返回值衔接，Console 操作另列在[控制台模块](/v2/zh/service/console/index)。

| 资源 | API 起点 | 参数与使用参考 |
| --- | --- | --- |
| Issue / AgentTask / Inbox / Approval | `/api/v1/issues`、`/agent-tasks`、`/inbox`、`/approvals` | [任务分派](/v2/zh/service/issues)、[任务反馈](/v2/zh/service/inbox)、[执行记录](/v2/zh/service/sessions) |
| Team 与成员 | `/api/v1/teams` | [Team 参数](/v2/zh/service/team-configuration)、[协作协议](/v2/zh/service/team-collaboration) |
| Workflow definition / revision / run | `/api/v1/orchestration-definitions`、`/orchestration-runs` | [节点、发布、启动与信号](/v2/zh/service/workflows) |
| Automation 与投递 | `/api/v1/automations` | [触发器、action、运行和反馈参数](/v2/zh/service/automation) |
| Channel 与工作接入 | `/api/channels` | [渠道配置、路由和工作回传](/v2/zh/service/channels) |
| Workspace | `/api/workspaces` | [文件、版本、发布与绑定](/v2/zh/service/workspaces) |
| Environment | `/api/environments` | [type、config 与 Worker](/v2/zh/service/environments) |
| Memory | `/api/memory-stores` | [文档、版本和访问策略](/v2/zh/service/memory) |
| Vault | `/api/vaults` | [secret、作用范围与引用](/v2/zh/service/vault) |
| Namespace 与权限 | 见授权资源的具体接口 | [访问控制参考](/v2/zh/service/access) |

路径前缀并非全部相同：资源管理仍有 `/api/...` 路径，不要自动补成 `/api/v1/...`。`/api/v1/events` 是用于界面刷新的尽力投递 WebSocket，没有持久续传 cursor；可靠调用追踪使用 Invocation SSE，Managed 原生会话使用自己的 SSE。

## Managed Agent 推理 API

使用平台用户 Bearer token 和 session 所有者身份访问，无需先发布 Endpoint。Endpoint API key 不适用。按流程学习可先读[聊天示例](/v2/zh/service/agent-api-chat)，再查[操作指南](/v2/zh/service/session-event-log)和 [SSE 事件](/v2/zh/service/sse-events)。

以下表格用 `{S}` 代表 `/api/v1/agent-sessions/{session}`，`{T}` 代表 `{S}/turns/{turn}`，`{C}` 代表 `{S}/subagents/{child}`。实际请求必须展开为完整路径。

### 会话生命周期

| 操作 | 方法和路径 |
| --- | --- |
| 创建 / 列出会话 | `POST /api/v1/agent-sessions`<br />`GET /api/v1/agent-sessions` |
| 获取 / 更新 / 删除 | `GET {S}`<br />`PATCH {S}`<br />`DELETE {S}` |
| 归档 / 解除归档 | `POST {S}/archive`<br />`POST {S}/restore` |

### 任务提交与交互

| 操作 | 方法和路径 |
| --- | --- |
| 提交 / 列出 / 获取 turn | `POST {S}/turns`<br />`GET {S}/turns`<br />`GET {T}` |
| 引导 / 注入上下文 | `POST {T}/steer`<br />`POST {S}/inputs/inject` |
| 答复待办 / 查询答复命令 | `POST {T}/actions`<br />`GET {T}/actions` |
| 取消 / 继续任务 | `POST {T}/cancel`<br />`POST {T}/resume` |

### 展示、订阅与追踪

| 操作 | 方法和路径 |
| --- | --- |
| 快照 / SSE | `GET {S}/snapshot`<br />`GET {S}/events/stream` |
| 分页历史 / 单条事件 / 导出 | `GET {S}/events`<br />`GET {S}/events/{event}`<br />`GET {S}/export` |
| 消息 / 工具 / 待办 / 输入 | `GET {S}/items`<br />`GET {S}/tools`<br />`GET {S}/required-actions`<br />`GET {S}/inputs` |
| 固定快照资源分页 | `GET {S}/resources/{resource}` |
| 子会话列表 / 详情 / 快照 | `GET {S}/subagents`<br />`GET {C}`<br />`GET {C}/snapshot` |
| 子会话历史 / SSE | `GET {C}/events`<br />`GET {C}/events/stream` |
| 子会话资源 | `GET {C}/items`<br />`GET {C}/tools`<br />`GET {C}/turns`<br />`GET {C}/runs`<br />`GET {C}/required-actions`<br />`GET {C}/usage`<br />`GET {C}/subagents` |

### 文件、恢复与运营

| 操作 | 方法和路径 |
| --- | --- |
| 上传 / 列出 / 文件元数据 / 下载 | `POST {S}/files`<br />`GET {S}/files`<br />`GET {S}/files/{file}`<br />`GET {S}/files/{file}/content` |
| 发布 / 列出 / 撤销产物 | `POST {S}/artifacts`<br />`GET {S}/artifacts`<br />`DELETE {S}/artifacts/{artifact}` |
| 列出 checkpoint / 恢复上下文 / 分支 | `GET {S}/checkpoints`<br />`POST {S}/checkpoints/restore`<br />`POST {S}/fork` |
| 用量 / 预算 | `GET {S}/usage`<br />`GET {S}/budget`<br />`PUT {S}/budget` |
| 注册 / 列出 / 删除 Webhook | `POST {S}/webhooks`<br />`GET {S}/webhooks`<br />`DELETE {S}/webhooks/{webhook}` |
| Webhook 重试 / 投递记录 | `POST {S}/webhooks/{webhook}/retry`<br />`GET {S}/webhooks/{webhook}/deliveries` |

turn、action、steer、inject、文件上传、产物发布、checkpoint restore/fork 和 Webhook 注册使用 Idempotency-Key。相同逻辑提交重试保持 key 与请求内容；新提交使用新 key。202 只表示持久接收。创建/任务状态响应中的 sessionId 等字段使用 camelCase；公共事件 envelope 使用 snake_case。

SSE 和历史事件使用不透明的 session cursor；资源分页 cursor、checkpoint_id 和其他会话的 cursor 不可混用。`POST {S}/restore` 解除归档；`POST {S}/checkpoints/restore` 恢复 Agent 上下文；`POST {T}/resume` 继续原任务，三者用途不同。

管理员诊断接口为 `GET {S}/trace`、`GET {S}/trace/recovery`、`GET {S}/trace/subagents/{child}` 和 `POST {S}/trace/reconcile`。需要部署方启用 trace、session 所有者身份和相应管理员角色；普通聊天页面无需接入。请求体与权限见[诊断与工具核对](/v2/zh/service/session-event-log#管理员检查-checkpoint-与工具结果)。

完整机器契约位于仓库 `agentscope-service/docs/agent-api/openapi-v1.json`，事件 schema 在同目录 `public-event-v1.schema.json`。

## Chat 请求示例

使用已有 Agent ID 和已授权空间创建，响应的 `chat.id` 用于后续消息：

```json
{
  "tenant": "YOUR_TENANT",
  "namespace": "YOUR_NAMESPACE",
  "agentId": "AGENT_ID",
  "title": "Notes review"
}
```

发送消息的 body 是 `{"message":"请概括这段材料"}`。该产品 API 与运行时 `/api/sessions` 事件协议不同，不要混用请求格式。

## Issue 验收与并发编辑

先 `GET /api/v1/issues/{id}`，核对结果与 `issue.version`。accept body 为 `{"expectedVersion":7}`；reject body 为 `{"expectedVersion":7,"reason":"缺少来源证据"}`，将 7 替换为刚读取的版本。

接口不统一使用同一个版本字段：Chat patch 使用 `version`，Issue 决策和 Workflow 发布使用 `expectedVersion`，Endpoint 发布使用 `version`。409 后重新读取和审阅，不自动用新版本重放旧决策。

## 错误处理

| 响应 | 应对 |
| --- | --- |
| 400 / 输入校验失败 | 检查 JSON、必填字段、schema 与目标能力 |
| 401 | 检查凭据类型、有效期和身份 |
| 403 | 检查空间角色和工作自身权限 |
| 404 | 检查 ID、范围和资源状态；不推断其他用户资源是否存在 |
| 409 | 重新读取版本或检查幂等请求是否改变 |
| 429 | 按服务响应退避，保留同一逻辑请求的 key |
| 5xx / 网络超时 | 先查询已提交工作的状态，再决定重试 |

记录请求关联 ID、资源 ID、发生时间、状态码和脱敏错误。SDK 自定义适配器参考 [External Agent](/v2/zh/service/external-agent)，执行状态见 [Sessions 与 Runs](/v2/zh/service/sessions)。

## SDK 单次执行取消

以下属于已有运行时 Session 控制协议。直接使用 Managed 原生会话 API 时，通过 `POST /api/v1/agent-sessions/{session}/turns/{turn}/cancel` 停止目标任务。

数据面 session 事件 API 的 `session.run_started` 事件包含 `run_id`，标识一次 SDK 调用。它不同于编排 Run 或托管 Attempt 的 ID。对同一个已授权 session 发起精确取消：

```http
POST /api/sessions/{id}/events
Authorization: Bearer TOKEN
Content-Type: application/json

{"events":[{"type":"user.interrupt","payload":{"run_id":"<session.run_started 中的 ID>"}}]}
```

服务会检查 session 访问权限和本地执行归属。跨实例请求携带该 run ID 作为匹配条件；执行已结束或被替换时，不会取消更新的执行。不提供 `run_id` 时仍表示“中断 session 当前 turn”。本地执行登记在完成、失败和取消时清理，诊断历史保留在 session 事件中。

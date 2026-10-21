---
title: "API、SDK 与目录管理"
en_link: /v2/en/service/api-reference
---

业务应用统一通过 Session API 调用 Agent、Team 和 Workflow。管理 API 用于准备这些资源、配置权限和发布 Workflow revision；应用调用时直接选择目标创建 Session，不需要再发布一层服务入口。完整接入流程见[通过 API 使用 Agent](/v2/zh/service/service-api)，本页用于查找路径和参数。

## 认证与空间

平台用户通过 `POST /api/auth/login` 登录，请求体为 `{"username":"...","password":"..."}`，将返回的 `token` 放入 `Authorization: Bearer TOKEN`。管理 Agent、Team、Workflow 和 Application 时使用这个身份。应用后端则使用 Application 下签发的 `X-API-Key`，只能访问凭据明确授权的目标和操作。

空间请求使用 `X-AgentScope-Tenant`、`X-AgentScope-Namespace`。如果请求体或查询参数中也包含空间字段，它们必须一致。Runtime Host、Task/Attempt 和 Environment 的执行凭据只用于相应运行协议，不能拿来调用业务 Session API。

## Application 与调用凭据

Application 保存调用方身份、成员和额度。创建凭据时需要显式给出 `targets` 和 `scopes`，服务只返回一次完整的 `apiKey`；列表接口不会返回明文。应用凭据不会自动获得管理资源或替指定人员审批的权限。

| API | 参数与行为 |
| --- | --- |
| `POST /api/v1/applications` | `name`, `tenant`, `namespace`; `description`, `members`, `maxConcurrent`, `tokenBudget` |
| `GET /api/v1/applications` / `/{id}` | 列表或详情 |
| `PATCH /api/v1/applications/{id}` | `version`, `name`, `description`, `status`, `members`, `maxConcurrent`, `tokenBudget` |
| `POST /api/v1/applications/{id}/credentials` | `name`, `targets:[{type,id}]`, `scopes`; `expiresAt` |
| `GET /api/v1/applications/{id}/credentials` | 凭据元数据 |
| `DELETE /api/v1/applications/{id}/credentials/{credentialId}` | 撤销凭据 |

凭据 scope 包括 `invoke`、`read`、`interact`、`cancel` 和 `webhooks:write`。Application 成员角色为 `viewer`、`operator`、`approver`。并发限制用于控制应用待处理的工作数量；tokenBudget 依据已报告用量限制后续调用，不是供应商的预付费硬额度。

## Session 与 Turn API

以下路径以 `/api/v1/agent-sessions` 为前缀。创建 Session 时，`target.type` 为 `agent`、`team` 或 `workflow`，`target.id` 为相应资源 ID。Agent 可指定 `version`，Workflow 可指定 `revisionId`。省略版本时，Service 在创建会话时解析并固定版本，后续 Turn 沿用该配置。

Managed Session 还可指定 `environmentId`、`memoryStoreIds`、`vaultIds`、`agentOverrides` 和 `resources`。通用参数包括 `timeoutSeconds`、`budget.maxTokens`、`inputSchema`、`outputSchema` 和 `resultMapping`；需要约束业务输入和输出时，应在创建 Session 时提供这些契约。

| API | 用途 |
| --- | --- |
| `POST /` | 创建会话；可携带幂等键 |
| `GET /` | 列表：agentId、status、limit、offset |
| `GET /{session}` | 读取会话及固定目标 |
| `PATCH /{session}` | 更新 Managed 的环境与资源设置 |
| `POST /{session}/archive, /restore` | 归档与解除归档 |
| `DELETE /{session}` | 停止应用访问该会话 |
| `GET /{session}/capabilities` | 目标能力与上下文语义 |
| `POST /{session}/turns` | message 或 input；必须携带幂等键 |
| `GET /{session}/turns, /turns/{turn}` | 任务列表与状态 |
| `GET /{session}/snapshot, /events, /events/stream` | 会话快照和持久事件 |
| `GET /{session}/turns/{turn}/snapshot, /events, /events/stream` | 单个任务的快照和事件 |
| `GET /{session}/turns/{turn}/capabilities` | 读取 available_commands |
| `POST /{session}/turns/{turn}/actions, /inputs, /cancel, /resume` | 交互命令；必须携带幂等键 |
| `GET /{session}/turns/{turn}/commands/{command}` | 命令回执状态 |
| `POST/GET /{session}/files` | 上传字节或列出会话文件 |
| `GET /{session}/files/{file}/content` | 读取已授权的文件内容 |
| `POST/GET /{session}/webhooks` | 注册或查看通知订阅 |
表中逗号后的路径与同一行第一个路径共享前缀。Turn 下也提供 `/actions`、`/usage`、`/artifacts` 和 `/webhooks` 查询；Webhook 撤销与重试分别使用 `DELETE /webhooks/{id}` 和 `POST /webhooks/{id}/retry`。

Managed Agent 的 `/budget`、`/checkpoints`、`/fork`、`/inputs/inject`、`/subagents` 与 `/export` 是同一套 API 的能力扩展。并非每个目标都支持这些操作，请按[会话指南](/v2/zh/service/session-event-log)与 capabilities 使用。文件格式见[文件与产物](/v2/zh/service/files)，事件与签名见 [SSE](/v2/zh/service/sse-events)。

<span id="agents"></span>
## 统一 Agent 目录与运行绑定

以下接口使用平台身份；创建和列表的 `tenant`、`namespace` 与请求头范围保持一致。Agent 的 `id`、`agentKey` 和展示名称不是同一个字段。创建/注册的操作示例见[Agent 管理](/v2/zh/service/api-reference#agents)。

| 方法和路径 | 请求参数 | 响应与用途 |
| --- | --- | --- |
| `POST /api/v1/agents` | `agentKey`；`tenant`、`namespace`、`displayName`、`description`；可带 `binding`、`definition` | `{agent,binding,policy,definition}`；Managed/Hosted 一次准备运行绑定与行为定义；没有绑定只创建目录记录 |
| `GET /api/v1/agents` | 查询 `tenant`、`namespace`、`status`、`includeArchived`、`limit` | `{items:[Agent]}` |
| `GET/PATCH /api/v1/agents/{id}` | PATCH：`version`，及 `displayName`、`description`、`status` 等修改项 | `{agent}`；归档使用 `status:"archived"`，不是删除运行历史 |
| `GET/PATCH /api/v1/agents/{id}/definition` | PATCH：`name`、定义 `version` 和完整保留后的行为字段 | `{definition}`，更新响应还包含 `agent`；[定义参数](/v2/zh/service/managed-agent-configuration) |
| `GET /api/v1/agents/{id}/versions` / `/{version}` | Agent ID；单版查询用定义版本号 | 定义历史；创建 Session 时可指定版本 |
| `GET/POST /api/v1/agents/{id}/bindings` | 创建使用 `kind`、`configuration`、`priority`、`enabled` | 从列表读取绑定；修改使用 `PATCH .../bindings/{bindingId}` 和 `version`，完整提交 `configuration`、`priority`、`enabled` |
| `GET /api/v1/agents/{id}/instances` / `/runtime-inventory` / `/overview` | Agent ID | 实例、External 运行报告、概览；无报告不能推断为执行就绪 |
| `GET/PUT /api/v1/agent-runtime-policies/{id}` | PUT：`tenant`、`namespace`、`agentId`、`selectionMode`、`fallbackMode`、`candidates` 等 | 选择运行候选；参数见[执行与运行策略](/v2/zh/service/team-configuration) |
| `GET /api/v1/agents/runtime-options` | 查询 `tenant`、`namespace` | `{runtimes,profiles,pools}`，供 Hosted 选择 Runtime |

`binding.kind` 使用 `managed`、`hosted-runtime` 或 `external-application`。Managed 创建时由平台生成绑定配置；Hosted 配置使用 `runtimeProfileId`、`runtimePoolId`。External 使用独立注册流程，不通过普通创建接口直接伪造一个在线实例。

`POST /api/v1/agent-registrations` 接受 `agentKey`、`instanceKey`、范围、`framework`、`routingKey`、`capabilities` 等，返回 `agent`、`binding`、`instance`、`registrationCredential`。当前注册入口不校验调用身份，需由部署方限制受信任接入范围；返回注册凭据不代表首次注册已经通过身份认证。字段和实际传输接入见[External 参数参考](/v2/zh/service/external-agent#external-agent-configuration)。

## 任务、编排、自动化与资源参数

以下参考覆盖创建、更新和反馈参数；各页同时给出 API 操作与返回值衔接，Console 操作另列在[控制台模块](/v2/zh/service/console/index)。

| 资源 | API 起点 | 参数与使用参考 |
| --- | --- | --- |
| Issue / AgentTask / Inbox / Approval | `/api/v1/issues`、`/agent-tasks`、`/inbox`、`/approvals` | [任务分派](/v2/zh/service/issues)、[任务反馈](/v2/zh/service/issues#inbox)、[执行记录](/v2/zh/service/sessions) |
| Team 与成员 | `/api/v1/teams` | [Team 参数](/v2/zh/service/team-configuration)、[协作协议](/v2/zh/service/create-team#team-collaboration) |
| Workflow definition / revision / run | `/api/v1/orchestration-definitions`、`/orchestration-runs` | [节点、发布、启动与信号](/v2/zh/service/workflows) |
| Automation 与投递 | `/api/v1/automations` | [触发器、action、运行和反馈参数](/v2/zh/service/automation) |
| Channel 与工作接入 | `/api/channels` | [渠道配置、路由和工作回传](/v2/zh/service/channels) |
| Workspace | `/api/workspaces` | [文件、版本、发布与绑定](/v2/zh/service/workspaces) |
| Environment | `/api/environments` | [type、config 与 Worker](/v2/zh/service/environments) |
| Memory | `/api/memory-stores` | [文档、版本和访问策略](/v2/zh/service/memory) |
| Vault | `/api/vaults` | [secret、作用范围与引用](/v2/zh/service/vault) |
| Namespace 与权限 | 见授权资源的具体接口 | [访问控制参考](/v2/zh/service/access) |



<span id="integrations"></span>

## SDK 与可运行示例

Python 的 `ManagementClient` 用于准备资源与凭据，`ServiceClient` 用于创建 Session、提交 Turn 和读取结果。前端 TypeScript 客户端位于 `frontend/src/api/serviceSessions.ts`。运行时注册和任务回报 SDK 则用于接入执行器，与应用调用客户端的职责不同。

```python
import os
from agentscope_service import ServiceClient

client = ServiceClient(os.environ["BASE_URL"], api_key=os.environ["AGENTSCOPE_API_KEY"])
session = client.create_session(
    {"type": "agent", "id": os.environ["AGENT_ID"]},
    idempotency_key="review-session-001",
)
turn = client.submit(session["id"], message="Review the notes", idempotency_key="review-001")
print(client.turn(session["id"], turn["id"]))
print(client.snapshot(session["id"]))
```

完整示例位于 `service-controlplane/examples/service-api`，可以分别对 Agent、Team 和 Workflow 创建 Session。HTTP 客户端不会替应用批准工具操作，也不会在网络失败时擅自创建新任务；应用应保存 Session ID、Turn ID 和幂等键，按[服务 API](/v2/zh/service/service-api#使用-sdk-与可运行示例)处理恢复。

## 错误与版本冲突

遇到 `400` 时先检查请求内容，`401` 表示凭据无效，`403` 表示当前身份没有相应权限。`404` 可能表示资源不存在，也可能表示当前身份无权访问，不应据此推断其他用户的数据。`409` 需要检查资源状态、版本或幂等请求是否改变；事件 `410` 则需要重新加载快照。限流和临时服务错误应退避重试，并保留同一个逻辑请求的幂等键。

管理资源更新前先读取当前版本。Issue 决策与 Workflow 发布使用 `expectedVersion`，Agent 定义等更新使用各自的 `version` 字段，不能自动用新版本重放已经过期的人工决定。

机器可读契约位于 `agentscope-service/docs/service-api/openapi-v1.json`，其中列出当前 Session API 和应用凭据路径。旧调用入口的迁移说明位于同目录的 `session-api-migration.md`。

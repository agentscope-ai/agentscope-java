---
title: "API 参考：认证、资源与调用"
en_link: /v2/en/service/api-reference
---

<Note>
此为预览文档，正式版本尚未发布。
</Note>

使用 Gateway 作为 API base URL。托管会话和推理控制使用 [Agent API](/v2/zh/service/session-event-log)；发布带 schema、API key 和版本的服务使用 [Endpoint](/v2/zh/service/endpoints)。

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

## Endpoint 调用

job 和 conversation 的详细请求、凭据、状态与 SSE 示例见 [Endpoint 指南](/v2/zh/service/endpoints)。创建请求必须使用逻辑请求级 Idempotency-Key；使用返回的 statusUrl/eventsUrl 跟踪，不绕过 Endpoint 直接操作内部任务。

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

以下属于已有运行时 Session 控制协议。使用 Agent API 开发应用时，通过 `POST /api/v1/agent-sessions/{session}/turns/{turn}/cancel` 停止目标任务。

数据面 session 事件 API 的 `session.run_started` 事件包含 `run_id`，标识一次 SDK 调用。它不同于编排 Run 或托管 Attempt 的 ID。对同一个已授权 session 发起精确取消：

```http
POST /api/sessions/{id}/events
Authorization: Bearer TOKEN
Content-Type: application/json

{"events":[{"type":"user.interrupt","payload":{"run_id":"<session.run_started 中的 ID>"}}]}
```

服务会检查 session 访问权限和本地执行归属。跨实例请求携带该 run ID 作为匹配条件；执行已结束或被替换时，不会取消更新的执行。不提供 `run_id` 时仍表示“中断 session 当前 turn”。本地执行登记在完成、失败和取消时清理，诊断历史保留在 session 事件中。

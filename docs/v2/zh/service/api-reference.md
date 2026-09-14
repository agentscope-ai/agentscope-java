---
title: "API 参考：认证、资源与调用"
---

[English](/v2/en/service/api-reference)

使用 Gateway 作为 API base URL。产品管理调用使用用户身份；业务系统调用已发布能力优先使用 [Endpoint](/v2/zh/service/endpoints)。

## 认证与范围

| 身份 | 使用位置 |
| --- | --- |
| 用户 Bearer token | 控制台管理、Chat、Issue、Workflow 等产品 API |
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

---
title: "API、SDK 与目录管理"
en_link: /v2/en/service/api-reference
---

应用围绕 Session、Turn 和 Events 接入。当前 MQ / Redis Managed Agent 使用[原生 Managed 契约](/v2/zh/service/claude-session-api)，管理 API 准备资源与定义。其他后端保留 [API 指南](/v2/zh/service/service-api)中的兼容路径，其请求体、事件游标与支持的控制不同。

<span id="认证与空间"></span>

## 本地 API 准备

先完成[本地部署](/v2/zh/service/quickstart)，按[API 准备](/v2/zh/service/create-managed-agent#api-setup)设置 `BASE_URL` 和默认空间。本地模式无需凭据或资源授权；下文管理与 Session API 示例可以直接执行。账号、空间权限、应用 key 及对应请求头见[生产部署](/v2/zh/service/kubernetes#production-api-access)。运行时自动下发的 Task/Attempt 凭据用于识别当前执行，继续按相应协议使用。

## Application 与调用凭据

Application 保存调用方身份、成员和额度。创建凭据时需要显式给出 `targets` 和 `scopes`，服务只返回一次完整的 `apiKey`；列表接口不会返回明文。应用凭据不会自动获得管理资源或替指定人员审批的权限。

## Managed 原生 Session API

当前 Managed 使用 `/v1/sessions`。其他后端保留 `/api/v1/agent-sessions` 兼容路径，请求体与能力
不同，不能混用 ID 或游标。下表路径均相对于原生入口：

| API | 用途 |
| --- | --- |
| `POST /` | 用 `agent`、可选 `environment_id`、`title`、`metadata` 创建，保留稳定幂等键 |
| `GET /{session}` | 读取冻结 Agent 引用与当前 Session 状态 |
| `GET /{session}/capabilities` | 查询当前协议支持的能力子集 |
| `POST /{session}/events` | 提交 `user.message` / `user.interrupt`；显式 `new_turn`、`steer`、`inject`、`cancel` 使用稳定 key |
| `GET /{session}/events` | 使用 `page`、`limit`、`order` 与类型/时间过滤分页读取历史 |
| `GET /{session}/events/stream` | 接收连接后处理的事件，不支持游标重放 |
| `GET /{session}/turns` 或 `/turns/{turn}` | 通过平台扩展读取 Turn 身份与状态 |
| `GET /{session}/turns/{turn}/output` | 独立分页读取最终输出 |
| `GET /{session}/turns/{turn}/inputs` 或 `/inputs/{input}` | 读取 Input 受理与应用状态 |

使用 `AgentScope-Extensions: turn-inputs-v1` 在回执和历史中选择平台关联信息。新 Turn 通过 Events
创建，不能 POST 到 `/turns`。客户端工具确认、Session 列表/更新/生命周期、快照、文件/Artifact/
预算/checkpoint API 与 SSE 重放不属于首版原生协议。内部 checkpoint 与自动恢复仍正常运行，
公开能力以 capabilities 为准。可运行请求见[当前原生契约](/v2/zh/service/claude-session-api)与
[第一个 Managed Agent](/v2/zh/service/create-managed-agent)。

<span id="agents"></span>
## 统一 Agent 目录与运行绑定

以下接口使用平台身份；创建和列表的 `tenant`、`namespace` 与请求头范围保持一致。Agent 的 `id`、`agentKey` 和展示名称不是同一个字段。创建示例见[Managed Agent](/v2/zh/service/create-managed-agent)、[Hosted Agent](/v2/zh/service/connect-hosted-agent)与[External 注册](/v2/zh/service/register-agentscope-agent)。

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

沿用[API 身份准备](/v2/zh/service/create-managed-agent#api-setup)的本地地址和默认空间变量。

```bash
curl -sS --fail-with-body -G "$BASE_URL/api/v1/agents" \
  --data-urlencode "tenant=$TENANT" \
  --data-urlencode "namespace=$NAMESPACE" \
  --data-urlencode "status=active" \
  --data-urlencode "limit=25"
```

```bash
curl -sS --fail-with-body "$BASE_URL/api/v1/agents/$AGENT_ID/bindings"
```

```bash
curl -sS --fail-with-body -G "$BASE_URL/api/v1/agent-runtime-policies/$AGENT_ID" \
    --data-urlencode "tenant=$TENANT" \
    --data-urlencode "namespace=$NAMESPACE"
```

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

Python 的 `ManagementClient` 用于准备资源与凭据，`ServiceClient` 用于创建 Session、提交 Turn 和读取结果。应用侧另有 Java `io.agentscope.service.client.ServiceClient`（`agentscope-service/service-client`）及 DSH 包的 `ServiceClient`。控制台 TypeScript 客户端位于 `frontend/src/api/serviceSessions.ts`。运行时注册和任务回报 SDK 则用于接入执行器，与应用调用客户端的职责不同。

```python
import os
from agentscope_service import ServiceClient

client = ServiceClient(os.environ["BASE_URL"])
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

当前 Portal 部署采用上述资源快照和回执格式。通过 Service 发布清单选择配套 SDK 版本；旧 Service 数据格式与命令路径不能混用。

Java 客户端要求 Java 17 与 Jackson，不依赖服务端模块。调用会阻塞，应在应用线程执行；
默认 30 秒总超时包含响应体和 SSE 连接。停止消费时关闭流，从最后已应用游标重新连接。
Java、Python 和 DSH 的 JSON 响应及上传限制为 16 MiB，缓冲下载为 100 MiB；文件输入前
读取 Session capabilities。构建制品和 SDK 版本元数据不代表 Maven 仓库已经发布。

## Managed 运行时契约

原生交互统一写入 `POST /v1/sessions/{session}/events`。新任务使用 `user.message` 和
`agentscope_service.intent:new_turn`；`steer` / `inject` 指定已有 `turn_id`。取消使用
`user.interrupt`、`intent:cancel` 与明确的 Turn ID。结果不确定时保留原请求体和幂等键重试。

扩展回执关联 Event、Turn/Input 身份。通过对应查询接口观察 Input 应用/拒绝及 Turn 实际终态；
受理或应用取消并不能证明外部工具已停止。SSE 断开不会取消工作，重连使用公开历史和当前状态
查询，不使用重放游标。客户端没有独立 `resume` 操作。当前能力边界见
[Managed Session API](/v2/zh/service/claude-session-api)。

---
title: "通过 API 发布与调用 Endpoint"
description: 为 Agent、Team 或 Workflow 发布稳定契约，配置调用凭据，再提交和观察工作。
en_link: /v2/en/service/endpoints
---

Endpoint 将 Agent、Team 或已发布的 Workflow revision 提供为稳定的业务 API。调用方只需知道服务地址、输入输出约定和凭据，无需了解成员 Agent 在哪里运行或内部如何调度。

本页用 HTTP 完成创建、发布、签发调用凭据和第一次调用。任务进行中的事件、人工交互、输入、取消和恢复，继续阅读[统一服务 API](/v2/zh/service/service-api)。页面发布入口见[控制台编排与发布](/v2/zh/service/console/orchestration)。

## 选择调用形式

| 形式 | 目标与用途 | 提交入口 |
| --- | --- | --- |
| `job` | Agent、Team 或 Workflow，完成一次有结果的工作 | `/invoke/v1/endpoints/{slug}/jobs` |
| `conversation` | 具有会话能力的单个 Agent，多轮交互 | `/invoke/v1/endpoints/{slug}/conversations` |

Managed、External、Hosted 都可以作为 Agent 目标，但必须具备对应执行能力。Team 和 Workflow 当前使用 Job。发布前检查 readiness，发布后读取 capabilities；目录中存在该目标，不代表所有交互操作都可用。

## 创建一个服务入口

先准备一个可用 Agent。下面用 Bash、`curl` 和 `jq`，平台用户需拥有目标空间和发布资源的相应权限。`platform_api` 仅用于管理请求，调用凭据稍后单独创建。

```bash
export BASE_URL='https://YOUR_SERVICE_HOST'
export TOKEN='YOUR_PLATFORM_USER_TOKEN'
export TENANT='YOUR_TENANT'
export NAMESPACE='YOUR_NAMESPACE'
export AGENT_ID='YOUR_AGENT_ID'

platform_api() {
  local api_path="$1"
  shift
  curl --fail-with-body -sS "$BASE_URL$api_path" \
    -H "Authorization: Bearer $TOKEN" -H 'Content-Type: application/json' \
    -H "X-AgentScope-Tenant: $TENANT" -H "X-AgentScope-Namespace: $NAMESPACE" "$@"
}
```

本例发布一个接受 `request` 字段的 Job：

```bash
ENDPOINT_JSON=$(platform_api /api/v1/endpoints \
  --data "$(jq -n --arg tenant "$TENANT" --arg namespace "$NAMESPACE" \
    --arg agent "$AGENT_ID" '{
      tenant:$tenant, namespace:$namespace, name:"Notes service", slug:"notes-service",
      targetType:"agent", targetRef:$agent, invocationMode:"job",
      authPolicy:{type:"api_key"},
      inputSchema:{type:"object", required:["request"],
        properties:{request:{type:"string"}}}
    }')")
ENDPOINT_ID=$(printf '%s' "$ENDPOINT_JSON" | jq -er '.endpoint.id')
ENDPOINT_VERSION=$(printf '%s' "$ENDPOINT_JSON" | jq -er '.endpoint.version')
platform_api "/api/v1/endpoints/$ENDPOINT_ID/readiness"
```

创建返回 `endpoint`，状态为 draft；此时不接收正式调用。`slug` 决定公开 URL，名称和 slug 冲突会返回 `409`。发布 Team 时设置 `targetType:"team"` 和 Team ID；发布 Workflow 时设置 `targetType:"orchestration_revision"`，`targetRef` 必须是已发布 revision ID，而不是草稿定义 ID。

除了输入 schema，还可以配置 `outputSchema`、`resultMapping`、`timeoutSeconds`、`maxPayloadBytes` 与 `rateLimit`。字段含义见 [API 参数参考](/v2/zh/service/api-reference)；先验证最小契约，再逐步增加约束。

<span id="发布与凭据" />

## 校验并发布版本

检查 readiness 是否允许当前目标与模式组合，并验证目标运行资源就绪。确认后，携带刚读取的版本发布：

```bash
platform_api "/api/v1/endpoints/$ENDPOINT_ID/publish" \
  --data "$(jq -n --argjson version "$ENDPOINT_VERSION" '{version:$version}')"
```

发布会建立 release，固定本次对外契约及目标配置。响应中的 `endpoint.status` 应为 `published`。遇到版本冲突时重新读取并核对修改，不要直接换成新版本号重放旧的发布决定。

## 给调用应用签发凭据

本例使用 `api_key` 认证。先创建代表业务调用方的 Application，再为这个 Endpoint 签发该 Application 的凭据：

```bash
APPLICATION_JSON=$(platform_api /api/v1/applications \
  --data "$(jq -n --arg tenant "$TENANT" --arg namespace "$NAMESPACE" \
    '{tenant:$tenant, namespace:$namespace, name:"Notes application"}')")
APPLICATION_ID=$(printf '%s' "$APPLICATION_JSON" | jq -er '.application.id')

CREDENTIAL_JSON=$(platform_api "/api/v1/endpoints/$ENDPOINT_ID/credentials" \
  --data "$(jq -n --arg app "$APPLICATION_ID" \
    '{applicationId:$app, name:"backend", scopes:["invoke","read","cancel","interact"]}')")
export ENDPOINT_KEY=$(printf '%s' "$CREDENTIAL_JSON" | jq -er '.secret')
```

保存响应中的 `secret` 到业务后端的凭据存储，调用时用 `X-API-Key`。创建或发布 Endpoint **不会自动生成 API key**；`applicationId` 和非空 `scopes` 必须明确指定。这里授予提交、读取、取消和交互；管理 Webhook 还需要 `webhooks:write`。

Application 让同一业务方的多把 key 共享调用归属，便于轮换后继续读取原调用。轮换不会立即撤销旧 key：先迁移调用方，再撤销旧凭据。浏览器通过你的业务后端调用，不应内置长期 key。

如果调用方本身是已授权的平台用户，可以在创建 Endpoint 时选择 `authPolicy:{type:"platform"}`，直接用用户 Bearer token，不需要这一步签发 key。[快速开始](/v2/zh/service/first-session)采用这种方式。两种认证策略不能随意混用。

<span id="发起一个-job" />

## 提交工作并观察同一次调用

```bash
curl --fail-with-body -sS "$BASE_URL/invoke/v1/endpoints/notes-service/capabilities" \
  -H "X-API-Key: $ENDPOINT_KEY"

RECEIPT=$(curl --fail-with-body -sS "$BASE_URL/invoke/v1/endpoints/notes-service/jobs" \
  -H "X-API-Key: $ENDPOINT_KEY" -H 'Content-Type: application/json' \
  -H 'Idempotency-Key: notes-job-001' \
  --data '{"title":"Prepare notes","input":{"request":"Lee owns the Friday installation notes. Review is Monday, time unconfirmed. Extract the action items."}}')
INVOCATION_ID=$(printf '%s' "$RECEIPT" | jq -er '.invocationId')
INVOCATION_URL="$BASE_URL/invoke/v1/invocations/$INVOCATION_ID"

SNAPSHOT=$(curl --fail-with-body -sS "$INVOCATION_URL/snapshot" -H "X-API-Key: $ENDPOINT_KEY")
CURSOR=$(printf '%s' "$SNAPSHOT" | jq -er '.as_of')
curl --fail-with-body -N -G "$INVOCATION_URL/events/stream" \
  -H "X-API-Key: $ENDPOINT_KEY" --data-urlencode "after=$CURSOR"
```

提交返回 `202`、`invocationId` 及状态、快照、事件 URL。保存响应；先渲染 snapshot，再用 `as_of` 续读事件，就能包含订阅前已经生成的消息和工具结果。上面为便于复制使用标准 Invocation 路径，也可以直接采用响应返回的 URL。

SSE 连接可以在客户端停止观察，后台工作仍继续。用另一个请求 `GET /invoke/v1/invocations/{id}` 查询 `invocation.status` 和最终 `invocation.result`。只有整个 Invocation 的终态代表调用结果；某个成员或工具完成并不代表 Team 已完成。`partial_succeeded` 表示有部分输出但存在失败，应查看步骤详情。

## 多轮 conversation

将具有会话能力的 Agent 发布为 `conversation` 后，首次提交 `{"message":"请整理会议记录"}`。保存返回的 `conversationId`；后续轮次向 `POST /invoke/v1/conversations/{conversationId}/turns` 发送新的 message 和幂等键，每轮仍有独立的 Invocation。

一个 Conversation 同时只接受一个活动 Invocation。不同运行时的执行中输入、审批或恢复支持不同，通过 capabilities 决定展示哪些交互。需要直接操作 Managed 文件、子会话和 checkpoint 时，使用 [Managed 原生会话 API](/v2/zh/service/session-event-log)。

## 重试和状态

同一业务请求因网络错误重传时，沿用原 Idempotency-Key 和请求体；用户发起新的工作才使用新 key。accepted、dispatching、running、waiting 都不是完成；取消先进入 cancel_requested，最终状态以服务确认结果为准。

断线后重新读取快照或从最后已处理 cursor 续读，不重新提交任务。处理流程和事件字段见[统一服务 API](/v2/zh/service/service-api)；[SSE 文档](/v2/zh/service/sse-events)区分统一调用与 Managed 原生会话两套事件资源。

## 更新版本与停用入口

| 操作 | API 与参数 |
| --- | --- |
| 修改草稿配置或运行限制 | `PATCH /api/v1/endpoints/{id}`，携带 `version` 与修改字段 |
| 查看发布记录 | `GET /api/v1/endpoints/{id}/releases` |
| 发布新的目标版本 | `POST /api/v1/endpoints/{id}/releases`，`version`、`targetRef`、可选 `reason` |
| 回到已有 release | `POST /api/v1/endpoints/{id}/releases/{releaseId}/rollback`，`version` |
| 停用新调用 | `POST /api/v1/endpoints/{id}/disable`，`version` |
| 轮换 / 撤销凭据 | `POST /api/v1/endpoints/{id}/credentials/{credentialId}/rotate`；`DELETE /api/v1/endpoints/{id}/credentials/{credentialId}` |

已发布的 schema 不通过普通 PATCH 原地改变。更新发布入口不会改写已有 Invocation 的契约，也不会撤销已经发生的外部操作。停用服务不等于取消现有任务；需要停止某次工作时，对它的 Invocation 发出取消命令。

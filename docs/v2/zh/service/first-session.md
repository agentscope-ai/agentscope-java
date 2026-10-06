---
title: "API 快速开始"
description: 通过 API 创建 Agent、发布服务、提交任务，并使用快照和 SSE 获取结果。
en_link: /v2/en/service/first-session
---

本教程用“整理会议待办”完成一次 API 接入：创建 Agent，发布 Endpoint，提交工作，再读取结果和持续事件。示例选择由 Service 运行的 Managed Agent 起步；Agent API 同样用于接入 External、Hosted Agent，并将 Agent、Team 或 Workflow 发布为服务。

你需要已经部署的 Service、具有当前空间资源创建与使用权限的平台用户 token，以及 `curl` 和 `jq`。管理员应已配置可用模型与 Environment。安装见[本地安装](/v2/zh/service/quickstart)，页面操作见 [Console](/v2/zh/service/console/index)。下面的创建与发布请求不启动模型，提交 Job 后会执行实际推理。

## 1. 准备身份与运行环境

`BASE_URL` 使用 Gateway 的完整 origin，末尾不带斜杠。平台身份与获取方式见[账号与权限](/v2/zh/service/access)。在同一个终端按顺序执行：

```bash
export BASE_URL='https://YOUR_SERVICE_HOST'
export TOKEN='YOUR_PLATFORM_USER_TOKEN'
export TENANT='YOUR_TENANT'
export NAMESPACE='YOUR_NAMESPACE'

curl --fail-with-body -sS "$BASE_URL/api/environments" \
  -H "Authorization: Bearer $TOKEN" \
  -H "X-AgentScope-Tenant: $TENANT" \
  -H "X-AgentScope-Namespace: $NAMESPACE" | jq '.[] | {id, name, type}'

export ENVIRONMENT_ID='CHOSEN_ENVIRONMENT_ID'
```

选择可用 Environment 的 `id` 填入变量。Environment 决定工具的执行位置；模型来自部署配置。未列出可用环境时，先按 [Environment](/v2/zh/service/environments)准备资源。

## 2. 创建资料助手

下面同时创建统一 Agent 身份、Managed 运行绑定和行为定义：

```bash
AGENT_JSON=$(curl --fail-with-body -sS "$BASE_URL/api/v1/agents" \
  -H "Authorization: Bearer $TOKEN" -H 'Content-Type: application/json' \
  -H "X-AgentScope-Tenant: $TENANT" -H "X-AgentScope-Namespace: $NAMESPACE" \
  --data "$(jq -n --arg tenant "$TENANT" --arg namespace "$NAMESPACE" \
    --arg env "$ENVIRONMENT_ID" '{
      tenant:$tenant, namespace:$namespace,
      agentKey:"notes-assistant", displayName:"资料助手",
      binding:{kind:"managed"},
      definition:{name:"资料助手", maxIters:20, defaultEnvironmentId:$env,
        system:"根据材料整理任务、负责人、期限和待确认事项。缺失的信息标为待确认，不虚构事实。"}
    }')")
AGENT_ID=$(printf '%s' "$AGENT_JSON" | jq -er '.agent.id')
printf '%s' "$AGENT_JSON" | jq '{agent, binding}'
```

保存返回的 `agent.id`。`agentKey` 是稳定业务标识，这里省略模型字段以使用默认模型。重复练习时，复用已创建的 Agent 或改用新的 key；创建接口不用于覆盖已有定义。工具、Workspace 和模型配置见[创建 Managed Agent](/v2/zh/service/create-managed-agent)。

## 3. 发布 Job Endpoint

Endpoint 给业务应用提供稳定地址和调用契约。本例采用 Job 模式，并用平台身份认证，后续查询仍使用同一个 `TOKEN`。为方便首次体验，输入输出不附加 schema；正式业务可按 [Endpoint](/v2/zh/service/endpoints)配置约束与应用凭据。

```bash
export ENDPOINT_SLUG='notes-assistant-job'
ENDPOINT_JSON=$(curl --fail-with-body -sS "$BASE_URL/api/v1/endpoints" \
  -H "Authorization: Bearer $TOKEN" -H 'Content-Type: application/json' \
  -H "X-AgentScope-Tenant: $TENANT" -H "X-AgentScope-Namespace: $NAMESPACE" \
  --data "$(jq -n --arg tenant "$TENANT" --arg namespace "$NAMESPACE" \
    --arg agent "$AGENT_ID" --arg slug "$ENDPOINT_SLUG" '{
      tenant:$tenant, namespace:$namespace,
      name:"资料助手 API", slug:$slug,
      targetType:"agent", targetRef:$agent, invocationMode:"job",
      authPolicy:{type:"platform"}, timeoutSeconds:300
    }')")
ENDPOINT_ID=$(printf '%s' "$ENDPOINT_JSON" | jq -er '.endpoint.id')
ENDPOINT_VERSION=$(printf '%s' "$ENDPOINT_JSON" | jq -er '.endpoint.version')

curl --fail-with-body -sS "$BASE_URL/api/v1/endpoints/$ENDPOINT_ID/readiness" \
  -H "Authorization: Bearer $TOKEN" \
  -H "X-AgentScope-Tenant: $TENANT" -H "X-AgentScope-Namespace: $NAMESPACE" | jq
```

检查 `readiness.state`、`reason` 和 `compatible`。目标不兼容或运行时不可用时，先处理原因；创建成功只说明草稿已经保存。Slug 是对外地址的一部分，须使用尚未被占用的值。

目标就绪后，带上创建响应中的版本发布：

```bash
curl --fail-with-body -sS "$BASE_URL/api/v1/endpoints/$ENDPOINT_ID/publish" \
  -H "Authorization: Bearer $TOKEN" -H 'Content-Type: application/json' \
  -H "X-AgentScope-Tenant: $TENANT" -H "X-AgentScope-Namespace: $NAMESPACE" \
  --data "$(jq -n --argjson version "$ENDPOINT_VERSION" '{version:$version}')" \
  | jq '{endpoint, readiness}'

curl --fail-with-body -sS "$BASE_URL/invoke/v1/endpoints/$ENDPOINT_SLUG/capabilities" \
  -H "Authorization: Bearer $TOKEN" \
  -H "X-AgentScope-Tenant: $TENANT" -H "X-AgentScope-Namespace: $NAMESPACE" | jq
```

确认 Endpoint 为 `published`。发布生成的 release 固定本次对外契约；capabilities 返回这个发布版本保证的能力。如果发布遇到版本冲突，重新读取 Endpoint 并核对配置后再提交当前版本。

## 4. 提交一次工作

```bash
INVOCATION_JSON=$(curl --fail-with-body -sS \
  "$BASE_URL/invoke/v1/endpoints/$ENDPOINT_SLUG/jobs" \
  -H "Authorization: Bearer $TOKEN" -H 'Content-Type: application/json' \
  -H "X-AgentScope-Tenant: $TENANT" -H "X-AgentScope-Namespace: $NAMESPACE" \
  -H 'Idempotency-Key: notes-job-001' \
  --data '{
    "title":"整理会议待办",
    "description":"小李周五完成安装说明，下周一评审，时间待确认。请整理任务、负责人、期限与待确认事项。",
    "input":{}
  }')
printf '%s' "$INVOCATION_JSON" | jq
```

接口返回 `202 Accepted`、`invocationId`、`statusUrl`、`snapshotUrl` 和 `eventsUrl`。这表示工作已接收，执行可能仍在排队或运行。保存这些值；业务请求超时重传时复用相同 `Idempotency-Key` 和内容，真正的新任务才换新 key。

## 5. 先读快照，再订阅后续事件

三个返回地址分别用于查询状态、加载已有内容、订阅后续变化。下面兼容相对 URL 与绝对 URL，避免自己拼接内部执行地址：

```bash
service_url() {
  case "$1" in
    http://*|https://*) printf '%s' "$1" ;;
    *) printf '%s%s' "$BASE_URL" "$1" ;;
  esac
}
STATUS_URL=$(service_url "$(printf '%s' "$INVOCATION_JSON" | jq -er '.statusUrl')")
SNAPSHOT_URL=$(service_url "$(printf '%s' "$INVOCATION_JSON" | jq -er '.snapshotUrl')")
EVENTS_URL=$(service_url "$(printf '%s' "$INVOCATION_JSON" | jq -er '.eventsUrl')")

SNAPSHOT_JSON=$(curl --fail-with-body -sS "$SNAPSHOT_URL" \
  -H "Authorization: Bearer $TOKEN" \
  -H "X-AgentScope-Tenant: $TENANT" -H "X-AgentScope-Namespace: $NAMESPACE")
printf '%s' "$SNAPSHOT_JSON" | jq
CURSOR=$(printf '%s' "$SNAPSHOT_JSON" | jq -er '.as_of')

curl -N --fail-with-body "$EVENTS_URL" \
  -H "Authorization: Bearer $TOKEN" -H 'Accept: text/event-stream' \
  -H "X-AgentScope-Tenant: $TENANT" -H "X-AgentScope-Namespace: $NAMESPACE" \
  -H "Last-Event-ID: $CURSOR"
```

快照中的 `items`、`tools`、`steps` 和 `artifacts` 包含已经产生的消息、工具进度、步骤和产物。`as_of` 标记快照包含到哪里；SSE 从它之后继续，所以读快照与建立连接之间发生的事件也能补回。如果快照已经包含终态，直接读取结果即可。

实际前端先呈现快照，再按事件 ID 去重更新视图，并保存最后成功应用的 SSE `id`。连接断开后从该游标续订；页面重新加载时，也可以重新读取完整快照再续订。不要仅恢复游标却丢弃之前的内容。事件格式、工具展示与过期游标处理见 [SSE 指南](/v2/zh/service/sse-events)。

## 6. 核对最终结果

SSE 结束或网络断开后，都可以用状态接口核对执行：

```bash
curl --fail-with-body -sS "$STATUS_URL" \
  -H "Authorization: Bearer $TOKEN" \
  -H "X-AgentScope-Tenant: $TENANT" -H "X-AgentScope-Namespace: $NAMESPACE" \
  | jq '.invocation | {id, status, result, error}'
```

`completed` 表示成功，结果在 `invocation.result` 中；检查输出是否保留了“评审时间待确认”。`partial_succeeded` 是部分成功，需要按业务要求检查缺失结果；`failed`、`cancelled`、`timed_out` 是其他终态。`accepted`、`dispatching`、`running`、`waiting` 和 `cancel_requested` 都还没有结束。单个工具或成员完成，以及 SSE 连接关闭，都不能代替 Invocation 的整体终态。

需要人工输入时，根据快照中的 `required_actions` 和调用的 capabilities 展示相应操作。补充信息、审批、取消与恢复的完整契约见[统一调用 API](/v2/zh/service/service-api)。

## 接下来

将目标换成已就绪的 External 或 Hosted Agent，可以继续使用同一套 Job 提交、快照、SSE 与结果读取方式；事件的细节粒度取决于运行时。Team 和 Workflow 同样发布为 Job Endpoint，其中 Workflow 的目标是已发布 revision。按[注册应用](/v2/zh/service/register-agentscope-agent)、[连接 Hosted Agent](/v2/zh/service/connect-hosted-agent)、[创建 Team](/v2/zh/service/create-team)或 [Workflow](/v2/zh/service/workflows)准备目标，再按 [Endpoint](/v2/zh/service/endpoints)发布。

支持会话的 Agent 还可以发布 Conversation Endpoint；需要 Managed 原生 steer、会话日志与 checkpoint 时，使用[原生会话 API](/v2/zh/service/session-event-log)。需要负责人、协作讨论和人工验收的业务工作，则继续使用 [Issue API](/v2/zh/service/issues)。

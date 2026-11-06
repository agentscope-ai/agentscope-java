---
title: "通过 Session API 接入应用"
description: "通过 Session API 把任务交给 Agent，观察执行进度，并在执行过程中参与交互。"
en_link: /v2/en/service/service-api
---

配置好 Agent 后，业务应用就可以通过 Session API 把工作交给它。应用先创建一个 Session，确定这段工作使用的 Agent 和配置，再向这个 Session 提交任务。每次提交都会创建一个 Turn，用来跟踪这一轮执行的进度和结果。Service 会在后台执行并保存工作记录，因此应用不需要保持提交请求一直连接，也可以在用户返回页面时继续查看同一项任务。

本页从调用凭据开始，说明如何创建 Session、提交任务并处理结果。首次接入可以沿用[创建 Managed Agent](/v2/zh/service/create-managed-agent)时已经验证过的 Agent。以后需要调用 Team 或 Workflow 时，仍然使用同一套 Session API，只需在创建 Session 时选择相应目标，并按它的能力处理输入和交互。

## 为应用准备调用凭据

在开发和调试阶段，可以使用登录得到的用户 Bearer token。接入业务后端时，建议创建 Application，为它签发独立的 API key，并明确授予它可以使用的 Agent、Team 或 Workflow。凭据属于 Application，因此同一应用更换 key 后仍能访问自己的 Session；其他应用即使使用同一个 Agent，也不能读取这些记录。API key 应保存在业务后端，由后端检查最终用户的业务权限。

下面的命令假定已经完成[部署](/v2/zh/service/quickstart)，并[创建了一个可以正常执行的 Managed Agent 并准备 API 凭据](/v2/zh/service/create-managed-agent#api-setup)。请保留这些步骤中的 `BASE_URL`、`TOKEN`、`TENANT`、`NAMESPACE` 和 `AGENT_ID`。创建 Application 和签发凭据属于管理操作，需要使用该 Application 所有者的用户身份。

```bash
set -euo pipefail

APPLICATION_JSON=$(
  curl -sS --fail-with-body "$BASE_URL/api/v1/applications" \
    -H "Authorization: Bearer $TOKEN" \
    -H "X-AgentScope-Tenant: $TENANT" \
    -H "X-AgentScope-Namespace: $NAMESPACE" \
    -H "Content-Type: application/json" \
    --data-binary @- <<JSON
{
  "tenant": "$TENANT",
  "namespace": "$NAMESPACE",
  "name": "report-application"
}
JSON
)
APPLICATION_ID=$(jq -er '.application.id' <<< "$APPLICATION_JSON")
```

为应用签发能够调用这个 Agent 的凭据：

```bash
KEY_JSON=$(
  curl -sS --fail-with-body "$BASE_URL/api/v1/applications/$APPLICATION_ID/credentials" \
    -H "Authorization: Bearer $TOKEN" \
    -H "X-AgentScope-Tenant: $TENANT" \
    -H "X-AgentScope-Namespace: $NAMESPACE" \
    -H "Content-Type: application/json" \
    --data-binary @- <<JSON
{
  "name": "backend",
  "scopes": [
    "invoke",
    "read",
    "interact",
    "cancel",
    "webhooks:write"
  ],
  "targets": [
    {
      "type": "agent",
      "id": "$AGENT_ID"
    }
  ]
}
JSON
)
AGENTSCOPE_API_KEY=$(jq -er '.apiKey' <<< "$KEY_JSON")
CREDENTIAL_ID=$(jq -er '.credential.id' <<< "$KEY_JSON")
```

服务只在创建凭据时返回明文 key。轮换时先签发新 key，更新应用配置并确认调用成功，再撤销旧凭据。`invoke` 允许提交工作，`read` 允许读取记录，`interact` 允许补充输入，`cancel` 允许取消；注册和管理回调还需要 `webhooks:write`。这些 scope 不会把应用变成人工审批人，指定人员的确认仍需由有权限的用户完成。

<Accordion title="列出与撤销旧凭据">

```bash
curl -sS --fail-with-body "$BASE_URL/api/v1/applications/$APPLICATION_ID/credentials" \
  -H "Authorization: Bearer $TOKEN" \
  -H "X-AgentScope-Tenant: $TENANT" \
  -H "X-AgentScope-Namespace: $NAMESPACE"
```

先使用上面的签发接口创建并验证新凭据。确认后，将 `OLD_CREDENTIAL_ID` 替换为列表中需要撤销的旧凭据 ID；此操作会停用旧 key。

```bash
OLD_CREDENTIAL_ID="OLD_CREDENTIAL_ID_FROM_LIST"
curl -sS --fail-with-body -X DELETE "$BASE_URL/api/v1/applications/$APPLICATION_ID/credentials/$OLD_CREDENTIAL_ID" \
  -H "Authorization: Bearer $TOKEN" \
  -H "X-AgentScope-Tenant: $TENANT" \
  -H "X-AgentScope-Namespace: $NAMESPACE"
```

</Accordion>

## 创建 Session，直接选择执行目标

下面的请求为资料助手创建 Session。Session 保存本次工作选择的配置和后续执行记录；创建成功并不代表已经提交了一项任务。建议对创建请求也提供稳定的幂等键，并保存返回的 `id`，避免网络重试时建立重复会话。

```bash
SESSION_JSON=$(
  curl -sS --fail-with-body "$BASE_URL/api/v1/agent-sessions" \
    -H "X-API-Key: $AGENTSCOPE_API_KEY" \
    -H "Content-Type: application/json" \
    -H "Idempotency-Key: report-session-001" \
    --data-binary @- <<JSON
{
  "target": {
    "type": "agent",
    "id": "$AGENT_ID"
  }
}
JSON
)
SESSION_ID=$(jq -er '.id' <<< "$SESSION_JSON")
SESSION_URL="$BASE_URL/api/v1/agent-sessions/$SESSION_ID"
```

`target.type` 可以是 `agent`、`team` 或 `workflow`。Agent 目标使用统一目录中的 ID，因此 Managed、External 和 Hosted 都使用 `agent`；它们的区别来自运行绑定。Team 使用 Team ID。Workflow 使用定义 ID，可以通过 `revisionId` 指定已发布版本；没有指定时，Service 选择当前最新的已发布版本。Workflow 的发布用于固定流程内容，与应用调用入口是两个不同的问题。

<Accordion title="使用 Team 或已发布的 Workflow">

先在签发凭据的 `targets` 中授权对应 Team 或 Workflow，并填入真实 ID。以下请求是创建 Agent Session 的替代选项；Workflow 的 `revisionId` 必须指向已发布版本。

<Tabs>
<Tab title="Team">

```bash
TEAM_ID="YOUR_TEAM_ID"
SESSION_JSON=$(
  curl -sS --fail-with-body "$BASE_URL/api/v1/agent-sessions" \
    -H "X-API-Key: $AGENTSCOPE_API_KEY" \
    -H "Content-Type: application/json" \
    -H "Idempotency-Key: team-session-001" \
    --data-binary @- <<JSON
{
  "target": {
    "type": "team",
    "id": "$TEAM_ID"
  }
}
JSON
)
SESSION_ID=$(jq -er '.id' <<< "$SESSION_JSON")
SESSION_URL="$BASE_URL/api/v1/agent-sessions/$SESSION_ID"
```

</Tab>
<Tab title="Workflow">

```bash
WORKFLOW_ID="YOUR_WORKFLOW_ID"
REVISION_ID="YOUR_PUBLISHED_REVISION_ID"
SESSION_JSON=$(
  curl -sS --fail-with-body "$BASE_URL/api/v1/agent-sessions" \
    -H "X-API-Key: $AGENTSCOPE_API_KEY" \
    -H "Content-Type: application/json" \
    -H "Idempotency-Key: workflow-session-001" \
    --data-binary @- <<JSON
{
  "target": {
    "type": "workflow",
    "id": "$WORKFLOW_ID",
    "revisionId": "$REVISION_ID"
  }
}
JSON
)
SESSION_ID=$(jq -er '.id' <<< "$SESSION_JSON")
SESSION_URL="$BASE_URL/api/v1/agent-sessions/$SESSION_ID"
```

</Tab>
</Tabs>

</Accordion>

Session 创建时会固定目标配置及其依赖。Managed Agent 可以通过 `target.version` 选择定义版本。更新 Agent、Team 或 Workflow 后，新 Session 可以使用新配置，已有 Session 继续沿用自己的配置。Environment、Memory、Vault 等运行资源有各自的访问与更新规则；保存配置版本并不意味着外部业务数据永远不变。

如果 Agent 访问外部工具时需要使用特定凭据，应在创建 Session 时选择相应的 Vault，也可以继承 Agent 的默认 Vault。应用调用 Service 所使用的 API key 不会自动成为工具访问外部系统的凭据。配置方法和 `vaultIds` 的使用方式见[在 Session 中选择 Vault](/v2/zh/service/vault#在-session-中选择-vault)。

## 提交一轮任务

向 Session 的 `/turns` 提交请求就会创建一个 Turn。Managed Agent 支持文本 `message`，也支持由用户消息和内容块组成的 `input`。Team 和 Workflow 可以使用结构化业务 `input`，让平台将其交给协作或流程执行器；具体输入应与目标实际处理的内容一致。

```bash
TURN_JSON=$(
  curl -sS --fail-with-body "$SESSION_URL/turns" \
    -H "X-API-Key: $AGENTSCOPE_API_KEY" \
    -H "Content-Type: application/json" \
    -H "Idempotency-Key: report-task-001" \
    --data-binary @- <<'JSON'
{
  "message": "根据已授权的资料整理报告，并说明来源和待确认事项。"
}
JSON
)
TURN_ID=$(jq -er '.id' <<< "$TURN_JSON")
```

查询刚提交的任务：

```bash
curl -sS --fail-with-body "$SESSION_URL/turns/$TURN_ID" \
  -H "X-API-Key: $AGENTSCOPE_API_KEY"
```

如果请求返回 `202 Accepted`，表示任务已经接收，执行可能仍在排队或进行中。网络超时后重试时，应使用同一个 Session、同一个幂等键和完全相同的请求内容。服务会返回原来的 Turn；如果沿用 key 却修改内容，则返回 `409 Conflict`。只有确实提交下一轮任务时，才应换一个新 key。

同一个 Session 中的 Turn 按顺序执行。Managed Agent 会保留会话上下文；Team 和 Workflow 的每个 Turn 会启动独立任务，历史记录集中保存在 Session 中，但不承诺把前一个任务的内部执行上下文直接传给下一次任务。需要沿用上次结果时，应用应在输入中明确提供所需资料。对于互不相关的对象或并行批次，通常应创建不同 Session。

## 恢复页面并观察执行

应用应保存业务对象、Session ID 和 Turn ID 的关联。用户刷新页面时，先读取 Session 的 `/snapshot` 恢复已有内容，再从其中的 `as_of` 游标订阅 `/events/stream`。刷新只是在恢复显示，不应重新发送任务输入。如果页面仍保留之前的状态，则可以从最后已经成功处理的事件游标继续读取。

```bash
SNAPSHOT=$(
  curl -sS --fail-with-body "$SESSION_URL/snapshot" \
    -H "X-API-Key: $AGENTSCOPE_API_KEY"
)
CURSOR=$(jq -er '.as_of' <<< "$SNAPSHOT")
```

```bash
curl -sS --fail-with-body -N -G "$SESSION_URL/events/stream" \
  -H "X-API-Key: $AGENTSCOPE_API_KEY" \
  -H "Accept: text/event-stream" \
  --data-urlencode "after=$CURSOR"
```

需要只观察某一轮任务时，可以使用 `/turns/{turnId}/snapshot` 和 `/turns/{turnId}/events/stream`。Session 游标与 Turn 游标分别属于各自的事件记录，不能交叉使用。Session 事件流会继续等待后续 Turn，因此连接关闭不表示业务完成。完成判断应读取对应 Turn 的 `status`：`completed` 表示成功，`partial_succeeded` 表示部分成功，`failed`、`cancelled` 和 `timed_out` 表示其他终态。

单轮快照中的 `items`、`tools`、`required_actions`、`steps`、`artifacts` 和 `usage` 按标识组织，便于应用更新界面。Managed Session 快照同时保留完整的会话消息、子 Agent 和原生交互记录。具体接口见 [API 参考](/v2/zh/service/api-reference)；事件续传和回调验证见[事件与通知](/v2/zh/service/sse-events)。

## 在执行中参与交互

Agent 需要用户确认、补充资料或提供外部工具结果时，应用从快照中的 `required_actions` 读取待办，再向对应 Turn 的 `/actions` 提交答复。请求应包含待办的 `request_id`，需要版本校验的待办还应包含 `expected_version`。Managed Agent 的确认答复放在 `payload` 中，例如 `{"allow":true}`。涉及指定审批人的请求，应改用该用户的登录身份提交，应用 key 本身不能替他作出决定。

先读取当前 Turn 的待办。没有待办时不需要调用 `/actions`：

```bash
ACTIONS_JSON=$(
  curl -sS --fail-with-body "$SESSION_URL/turns/$TURN_ID/actions" \
    -H "X-API-Key: $AGENTSCOPE_API_KEY"
)
jq '.required_actions' <<< "$ACTIONS_JSON"
```

检查所选待办的操作说明、`kind` 和请求内容，将真实 `request_id` 原样填入下面的变量，保留服务返回的 `native:` 等前缀。一次只答复用户选择的待办，不要自动允许列表中的所有操作。

```bash
REQUEST_ID="REQUEST_ID_FROM_PENDING_ACTION"
```

按待办类型选择一种答复。工具确认和业务审批使用有权限的用户 `TOKEN`；外部工具结果可使用具有 `interact` scope 的应用 key。

<Tabs>
<Tab title="工具确认">

仅适用于 Managed 的 `confirmation` 待办。用户检查工具及参数后，允许时传 `allow: true`，拒绝时改为 `false` 并说明原因。用户必须是指定确认人，或由 Managed Agent 所有者明确授权。

```bash
COMMAND_JSON=$(
  curl -sS --fail-with-body "$SESSION_URL/turns/$TURN_ID/actions" \
    -H "Authorization: Bearer $TOKEN" \
    -H "X-AgentScope-Tenant: $TENANT" \
    -H "X-AgentScope-Namespace: $NAMESPACE" \
    -H "Content-Type: application/json" \
    -H "Idempotency-Key: report-confirmation-001" \
    --data-binary @- <<JSON
{
  "request_id": "$REQUEST_ID",
  "payload": {
    "allow": true,
    "reason": "用户已核对工具参数并确认执行。"
  }
}
JSON
)
COMMAND_ID=$(jq -er '.command.id' <<< "$COMMAND_JSON")
```

</Tab>
<Tab title="外部工具结果">

仅适用于 Managed 的 `external_execution` 待办。将 `output` 替换为实际执行结果；失败时使用 `is_error: true` 并提供真实错误信息。

```bash
COMMAND_JSON=$(
  curl -sS --fail-with-body "$SESSION_URL/turns/$TURN_ID/actions" \
    -H "X-API-Key: $AGENTSCOPE_API_KEY" \
    -H "Content-Type: application/json" \
    -H "Idempotency-Key: report-tool-result-001" \
    --data-binary @- <<JSON
{
  "request_id": "$REQUEST_ID",
  "payload": {
    "output": "替换为真实工具执行结果",
    "is_error": false
  }
}
JSON
)
COMMAND_ID=$(jq -er '.command.id' <<< "$COMMAND_JSON")
```

</Tab>
<Tab title="业务审批">

从待办读取当前版本号，将下面的 `1` 替换为实际数字，并使用指定审批人的 `TOKEN`。同意使用 `approved`，拒绝使用 `rejected`；此类答复不使用 `payload.allow`。

```bash
EXPECTED_VERSION=1
COMMAND_JSON=$(
  curl -sS --fail-with-body "$SESSION_URL/turns/$TURN_ID/actions" \
    -H "Authorization: Bearer $TOKEN" \
    -H "X-AgentScope-Tenant: $TENANT" \
    -H "X-AgentScope-Namespace: $NAMESPACE" \
    -H "Content-Type: application/json" \
    -H "Idempotency-Key: report-approval-001" \
    --data-binary @- <<JSON
{
  "request_id": "$REQUEST_ID",
  "expected_version": $EXPECTED_VERSION,
  "decision": "approved"
}
JSON
)
COMMAND_ID=$(jq -er '.command.id' <<< "$COMMAND_JSON")
```

</Tab>
</Tabs>

请求返回 `202 Accepted` 只表示答复已接收。使用返回的命令 ID 查询回执，直到 `command.status` 为 `completed` 或 `failed`；失败时检查 `command.error` 并重新读取待办。网络重试沿用同一个幂等键和请求体。

```bash
curl -sS --fail-with-body "$SESSION_URL/turns/$TURN_ID/commands/$COMMAND_ID" \
  -H "X-API-Key: $AGENTSCOPE_API_KEY"
```

命令完成表示该答复已处理，整个任务仍可能继续执行。继续观察事件或读取 Turn 状态；补充背景、调整要求等其他输入方式见[会话、任务与预算](/v2/zh/service/session-event-log)。

```bash
curl -sS --fail-with-body "$SESSION_URL/turns/$TURN_ID" \
  -H "X-API-Key: $AGENTSCOPE_API_KEY"
```

每种运行绑定能提供的交互不同。先读取 Session 的 `/capabilities` 了解目标支持范围，在显示取消、补充输入或恢复按钮前，再读取 Turn 的 `/capabilities` 中的 `available_commands`。取消请求被接受后，仍需继续观察状态，直到确认执行已经停止。需要进一步了解如何补充要求、回答待办或恢复执行时，可以继续阅读[会话、任务与预算](/v2/zh/service/session-event-log)。

```bash
curl -sS --fail-with-body "$SESSION_URL/capabilities" \
  -H "X-API-Key: $AGENTSCOPE_API_KEY"
```

```bash
curl -sS --fail-with-body "$SESSION_URL/turns/$TURN_ID/capabilities" \
  -H "X-API-Key: $AGENTSCOPE_API_KEY"
```

## 凭据、预算和后台通知

Application 的 `maxConcurrent` 和 `tokenBudget` 约束该应用通过不同 key、不同 Session 提交的工作。Token 用量依据运行时报告累计，预算耗尽时会拒绝后续提交，因此它是执行治理能力，不是外部模型账单的实时硬上限。创建 Session 时，还可以设置 `timeoutSeconds` 和 `budget.maxTokens`，约束该 Session 中每个 Turn 的执行。Managed 会话自己的预算则通过 `/budget` 管理，具体配置与用量查询见[用量、子 Agent 与预算](/v2/zh/service/session-event-log#budgets)。

<Accordion title="设置应用限额与任务预算">

应用所有者先读取当前版本，再更新限额；版本冲突时重新读取，勿覆盖其他人的更新。

```bash
APPLICATION_JSON=$(
  curl -sS --fail-with-body "$BASE_URL/api/v1/applications/$APPLICATION_ID" \
    -H "Authorization: Bearer $TOKEN" \
    -H "X-AgentScope-Tenant: $TENANT" \
    -H "X-AgentScope-Namespace: $NAMESPACE"
)
APPLICATION_VERSION=$(jq -er '.application.version' <<< "$APPLICATION_JSON")

curl -sS --fail-with-body -X PATCH "$BASE_URL/api/v1/applications/$APPLICATION_ID" \
  -H "Authorization: Bearer $TOKEN" \
  -H "X-AgentScope-Tenant: $TENANT" \
  -H "X-AgentScope-Namespace: $NAMESPACE" \
  -H "Content-Type: application/json" \
  --data-binary @- <<JSON
{
  "version": $APPLICATION_VERSION,
  "maxConcurrent": 5,
  "tokenBudget": 1000000
}
JSON
```

创建新 Session 时可同时设置每个 Turn 的超时秒数与 token 预算：

```bash
SESSION_JSON=$(
  curl -sS --fail-with-body "$BASE_URL/api/v1/agent-sessions" \
    -H "X-API-Key: $AGENTSCOPE_API_KEY" \
    -H "Content-Type: application/json" \
    -H "Idempotency-Key: limited-session-001" \
    --data-binary @- <<JSON
{
  "target": {
    "type": "agent",
    "id": "$AGENT_ID"
  },
  "timeoutSeconds": 300,
  "budget": {
    "maxTokens": 10000
  }
}
JSON
)
SESSION_ID=$(jq -er '.id' <<< "$SESSION_JSON")
SESSION_URL="$BASE_URL/api/v1/agent-sessions/$SESSION_ID"
```

</Accordion>

后台应用可以为 Session 注册 Webhook，在任务完成、失败或等待交互时收到通知。回调可能重复投递，应用应验证签名、按事件 ID 去重，再读取 Session 或 Turn 确认最新状态。完整协议和重试方式见[Webhook 后台通知](/v2/zh/service/sse-events#webhooks)。

## 检查交付结果

Turn 的 `completed` 表示这轮执行成功结束。应用还需要检查实际交付是否符合业务要求，例如报告是否包含所需来源、文件是否能够下载，以及结构化结果是否包含后续处理所需的数据。返回的是文件时，应通过[文件与产物](/v2/zh/service/files)中的接口读取实际产物，不能仅凭 Agent 回复中的文件名判断交付已经完成。

如果任务需要严格的业务输入和输出，可以在创建 Session 时提供 `inputSchema`、`outputSchema` 和 `resultMapping`。这些设置固定在当前 Session 的执行配置中。应先验证目标真实返回的数据，再设置输出映射和校验规则；Agent 在文字中写出 JSON，不等于平台已把这段文字转换为业务对象。这些规则用于检查数据格式，不能代替对内容质量的验收，也不会自动驱动 Agent 反复修改结果。

<Accordion title="校验结构化输入与交付结果">

下面假定已发布的 Workflow 接收 `{topic}` 并实际返回 `{answer}`。请按目标真实数据修改 schema 和 JSON Pointer；示例中的 `/answer` 取自结构化结果字段。先为应用凭据授权该 Workflow。

```bash
WORKFLOW_ID="YOUR_WORKFLOW_ID"
SESSION_JSON=$(
  curl -sS --fail-with-body "$BASE_URL/api/v1/agent-sessions" \
    -H "X-API-Key: $AGENTSCOPE_API_KEY" \
    -H "Content-Type: application/json" \
    -H "Idempotency-Key: schema-session-001" \
    --data-binary @- <<JSON
{
  "target": {
    "type": "workflow",
    "id": "$WORKFLOW_ID"
  },
  "inputSchema": {
    "type": "object",
    "required": [
      "topic"
    ],
    "properties": {
      "topic": {
        "type": "string"
      }
    }
  },
  "outputSchema": {
    "type": "object",
    "required": [
      "answer"
    ],
    "properties": {
      "answer": {
        "type": "string"
      }
    }
  },
  "resultMapping": {
    "answer": "/answer"
  }
}
JSON
)
SESSION_ID=$(jq -er '.id' <<< "$SESSION_JSON")
SESSION_URL="$BASE_URL/api/v1/agent-sessions/$SESSION_ID"
```

```bash
curl -sS --fail-with-body "$SESSION_URL/turns" \
  -H "X-API-Key: $AGENTSCOPE_API_KEY" \
  -H "Content-Type: application/json" \
  -H "Idempotency-Key: schema-task-001" \
  --data-binary @- <<'JSON'
{
  "input": {
    "topic": "安装说明"
  }
}
JSON
```

</Accordion>

如果业务还需要保存负责人、讨论和人工验收记录，可以使用[工作分派、审批与验收](/v2/zh/service/issues)中的 Issue 来组织交付。Issue 的验收与 Session 中一次执行的完成是两个不同的判断：前者确认业务工作是否被接受，后者记录这轮执行是否结束。

## 使用 SDK 与可运行示例

Python `ServiceClient` 封装了相同的 Session API。下面的代码使用已经授予目标权限的应用 key；这里的 `AGENT_ID` 是平台 Agent ID。

```bash
export BASE_URL AGENTSCOPE_API_KEY AGENT_ID
```

```python
import os
from agentscope_service import ServiceClient

api = ServiceClient(os.environ["BASE_URL"], os.environ["AGENTSCOPE_API_KEY"])
session = api.create_session({"type": "agent", "id": os.environ["AGENT_ID"]},
                             idempotency_key="sdk-report-session-001")
turn = api.submit(session["id"], message="整理报告并给出来源。",
                  idempotency_key="sdk-report-task-001")
print(api.turn(session["id"], turn["id"]))
```

仓库中的 `agentscope-service/service-controlplane/examples/service-api` 提供 Agent、Team 和 Workflow 的注册与调用示例。管理对象使用 `ManagementClient`，应用执行使用 `ServiceClient`。接口入口见 [API 参考](/v2/zh/service/api-reference)。

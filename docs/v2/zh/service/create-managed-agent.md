---
title: "运行第一个托管 Agent"
description: "用 curl 创建托管 Agent、启动会话、提交任务并查看执行结果。"
en_link: /v2/en/service/create-managed-agent
---

用 curl 创建一个基于 HarnessAgent 的托管 Agent，让它整理会议记录、写入文件并读取核对。Service 负责运行 Agent，你只需配置它并通过 Session API 提交任务。

<span id="1-准备平台与执行资源"></span>

<span id="登录并选择空间"></span>

## 准备

先完成[本地 Docker Compose 部署](/v2/zh/service/quickstart)，配置模型凭据。终端需要 Bash 或 zsh、curl 和 jq；请在同一个终端中依次执行下面的命令。

<span id="api-setup"></span>

本地开发模式无需登录、用户 token 或应用 API key，所有请求使用默认空间。设置 Service 地址和默认空间变量；生产身份配置见[生产部署指南](/v2/zh/service/kubernetes#production-api-access)。

```bash
set -euo pipefail
export BASE_URL="http://localhost:18080"
export TENANT="default"
export NAMESPACE="default"
```


<span id="准备执行资源"></span>

<span id="选择工具执行环境"></span>

## 1. 创建执行环境

创建一个 Local Environment，文件工具将在 Dataplane 容器内执行。如果已有可用环境，直接设置 `ENVIRONMENT_ID` 并跳过此请求；其他类型见 [Environment 指南](/v2/zh/service/environments)。

```bash
ENVIRONMENT_JSON=$(
  curl -sS --fail-with-body "$BASE_URL/api/environments" \
    -H "Content-Type: application/json" \
    --data-binary @- <<'JSON'
{
  "name": "Quickstart local",
  "type": "local",
  "config": {}
}
JSON
)
ENVIRONMENT_ID=$(jq -er '.id' <<< "$ENVIRONMENT_JSON")
```

<span id="创建统一-agent-身份与托管定义"></span>

<span id="2-创建资料助手"></span>

## 2. 创建 Agent

创建“资料助手”，使用部署的默认模型，只启用文件读取和写入工具。`binding.kind` 为 `managed`，执行环境由 `defaultEnvironmentId` 指定。

```bash
AGENT_JSON=$(
  curl -sS --fail-with-body "$BASE_URL/api/v1/agents" \
    -H "Content-Type: application/json" \
    --data-binary @- <<JSON
{
  "tenant": "$TENANT",
  "namespace": "$NAMESPACE",
  "agentKey": "notes-assistant-api",
  "displayName": "资料助手",
  "binding": { "kind": "managed" },
  "definition": {
    "name": "资料助手",
    "system": "根据会议记录整理待办，保留事实，缺失信息标为待确认。写入文件后读取核对，并在最终回复中给出文件内容。",
    "defaultEnvironmentId": "$ENVIRONMENT_ID",
    "tools": [{
      "type": "agent_toolset",
      "defaultConfig": { "enabled": false },
      "configs": [
        { "name": "read", "enabled": true, "permissionPolicy": { "type": "always_allow" } },
        { "name": "write", "enabled": true, "permissionPolicy": { "type": "always_allow" } }
      ]
    }]
  }
}
JSON
)
AGENT_ID=$(jq -er '.agent.id' <<< "$AGENT_JSON")
printf 'Agent ID: %s\n' "$AGENT_ID"
```

保存返回的 `AGENT_ID`，后续会话都可以复用它。再次创建不同 Agent 时，请更换 `agentKey`。示例允许直接读写文件，工具权限配置见[工具指南](/v2/zh/service/tools#tool-permissions)。

<span id="创建会话并提交一轮任务"></span>

<span id="3-创建会话并提交任务"></span>

## 3. 创建会话

创建一个引用该 Agent 的 Session，用来保存消息与执行记录。会话创建后还需提交任务，Agent 才会开始工作。

```bash
SESSION_JSON=$(
  curl -sS --fail-with-body "$BASE_URL/api/v1/agent-sessions" \
    -H "Content-Type: application/json" \
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
export SESSION_URL="$BASE_URL/api/v1/agent-sessions/$SESSION_ID"
printf 'Session ID: %s\n' "$SESSION_ID"
```

## 4. 提交任务

向会话提交消息，创建一个 Turn。

```bash
TURN_JSON=$(
  curl -sS --fail-with-body "$SESSION_URL/turns" \
    -H "Content-Type: application/json" \
    -H "Idempotency-Key: notes-check-001" \
    --data-binary @- <<'JSON'
{
  "message": "小李周五完成安装说明，下周一评审，具体时间待确认。请整理待办，写入 meeting-actions.md，再读取核对，并给出文件内容。"
}
JSON
)
TURN_ID=$(jq -er '.id' <<< "$TURN_JSON")
jq '{id, status}' <<< "$TURN_JSON"
```

请求返回 `202 Accepted` 表示任务已接收。网络重试时保留同一 `Idempotency-Key` 和消息；新的任务使用新 key。

<span id="4-读取进度与工具结果"></span>

<span id="5-核对交付"></span>

## 5. 查看进度和结果

先读取快照，查看已有消息、工具结果和任务状态：

```bash
SNAPSHOT=$(
  curl -sS --fail-with-body "$SESSION_URL/snapshot"
)
jq '{items, tools, turns, required_actions}' <<< "$SNAPSHOT"
CURSOR=$(jq -er '.as_of' <<< "$SNAPSHOT")
```

如果任务仍在执行，从快照的 `as_of` 继续接收 SSE 事件：

```bash
curl -sS --fail-with-body -N -G "$SESSION_URL/events/stream" \
  --data-urlencode "after=$CURSOR"
```

看到对应 `TURN_ID` 的 `turn.completed` 后，按 Ctrl-C 退出事件流，再查询最终结果。关闭事件流不会取消任务。

```bash
curl -sS --fail-with-body "$SESSION_URL/turns/$TURN_ID" \
  | jq '{id, status, error}'

curl -sS --fail-with-body "$SESSION_URL/snapshot" \
  | jq '{items, tools, required_actions}'
```

确认 Turn 的 `status` 为 `completed`，工具记录包含 `meeting-actions.md` 的写入和读取，最终回复保留周五完成、下周一评审以及“具体时间待确认”。文件保存在执行环境中；下载与 Artifact 发布见[文件与产物](/v2/zh/service/files)。

若状态为 `failed`，检查 `error`；若为 `requires_action`，查看快照中的 `required_actions`。处理方式见[会话与任务](/v2/zh/service/session-event-log)。

<span id="增加能力与发布"></span>

## 下一步

保留 `AGENT_ID`，继续[通过 Session API 接入应用](/v2/zh/service/service-api)。需要扩展能力时，参阅[Agent 配置](/v2/zh/service/managed-agent-configuration)、[工具与 MCP](/v2/zh/service/tools)和 [SSE 事件](/v2/zh/service/sse-events)。

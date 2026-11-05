---
title: "运行第一个托管 Agent"
description: "用 curl 创建托管 Agent、启动会话、提交任务并查看执行结果。"
en_link: /v2/en/service/create-managed-agent
---

用 curl 创建一个基于 HarnessAgent 的托管 Agent，让它整理会议记录、写入文件并读取核对。Service 负责运行 Agent，你只需配置它并通过 Session API 提交任务。

<span id="1-准备平台与执行资源"></span>

<span id="登录并选择空间"></span>

本例使用文件工具，需要启用用户文件（源码 Compose 默认开启）。生产安装若设置了
`userFilesEnabled=false`，先使用[无工具原生 API 示例](https://github.com/agentscope-ai/agentscope-java/blob/main/agentscope-service/docs/api-quickstart.md)，或配置共享存储并启用用户文件后再运行本例。

## 准备

先完成[本地 Docker Compose 部署](/v2/zh/service/quickstart)，配置模型凭据。终端需要 Bash 或 zsh、curl 和 jq；请在同一个终端中依次执行下面的命令。

<span id="api-setup"></span>

本地开发模式无需登录、用户 token 或应用 API key，所有请求使用默认空间。设置 Service 地址和默认空间变量；生产身份配置见[生产部署指南](/v2/zh/service/kubernetes#production-api-access)。

```bash
set -euo pipefail
export BASE_URL="http://localhost:18080"
export TENANT="default"
export NAMESPACE="default"

native_api() {
  curl -sS --fail-with-body -H 'AgentScope-Extensions: turn-inputs-v1' \
    -H "X-AgentScope-Tenant: $TENANT" -H "X-AgentScope-Namespace: $NAMESPACE" "$@"
}
```


<span id="准备执行资源"></span>

<span id="选择工具执行环境"></span>

## 1. 创建执行环境

创建一个 Local Environment，文件工具通过 Hands 执行。如果已有可用环境，直接设置 `ENVIRONMENT_ID` 并跳过此请求；其他类型见 [Environment 指南](/v2/zh/service/environments)。

```bash
ENVIRONMENT_JSON=$(
  native_api "$BASE_URL/api/environments" \
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

Local 工具通过随 Service 安装的平台 Hands 执行。发送任务前，按[环境指南](/v2/zh/service/environments#2-启动-hands)确认 Hands 健康。创建 Environment 资源本身不代表执行环境已就绪；平台服务无需额外 Hands profile 或固定 Environment ID。

## 2. 创建 Agent

创建“资料助手”，使用部署的默认模型，只启用文件读取和写入工具。`binding.kind` 为 `managed`，执行环境由 `defaultEnvironmentId` 指定。

```bash
AGENT_JSON=$(
  native_api "$BASE_URL/api/v1/agents" \
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
  native_api "$BASE_URL/v1/sessions" \
    -H "Content-Type: application/json" \
    -H "Idempotency-Key: notes-session-001" \
    --data-binary @- <<JSON
{
  "agent": "$AGENT_ID",
  "environment_id": "$ENVIRONMENT_ID"
}
JSON
)
SESSION_ID=$(jq -er '.id' <<< "$SESSION_JSON")
export SESSION_URL="$BASE_URL/v1/sessions/$SESSION_ID"
printf 'Session ID: %s\n' "$SESSION_ID"
```

## 4. 提交任务

通过 `/events` 提交带 `intent:new_turn` 的 `user.message` 事件，创建 Turn；`/turns` 为查询扩展，不能用于提交。

```bash
TURN_JSON=$(
  native_api "$SESSION_URL/events" \
    -H "Content-Type: application/json" \
    -H "Idempotency-Key: notes-check-001" \
    --data-binary @- <<'JSON'
{
  "events": [{
    "type": "user.message",
    "content": [{"type": "text", "text": "小李周五完成安装说明，下周一评审，具体时间待确认。请整理待办，写入 meeting-actions.md，再读取核对，并给出文件内容。"}],
    "agentscope_service": {"intent": "new_turn"}
  }]
}
JSON
)
TURN_ID=$(jq -er '.data[0].agentscope_service.turn_id' <<< "$TURN_JSON")
jq . <<< "$TURN_JSON"
```

HTTP 200 与 Event/Turn ID 表示任务已受理，后续在后台执行。网络重试时保留同一 `Idempotency-Key` 和消息；新的任务使用新 key。

<span id="4-读取进度与工具结果"></span>

<span id="5-核对交付"></span>

## 5. 查看进度和结果

保留相同 Session 和 Turn ID，轮询直到本轮结束：

```bash
for attempt in {1..90}; do
  TURN=$(native_api "$SESSION_URL/turns/$TURN_ID")
  STATUS=$(jq -er '.status' <<< "$TURN")
  case "$STATUS" in
    completed) break ;;
    failed|cancelled) jq . <<< "$TURN"; exit 1 ;;
  esac
  sleep 2
done
jq '{id, status, error}' <<< "$TURN"
```

读取公开历史与最终输出。若本轮仍在运行，保留 ID 后继续查询；上述轮询超时不会取消执行。

```bash
native_api "$SESSION_URL/events?limit=100" | jq .
native_api "$SESSION_URL/turns/$TURN_ID/output?limit=100" | jq .
```

确认 `status` 为 `completed`，执行历史包含文件操作，最终回复保留周五完成、下周一评审和“具体
时间待确认”。文件保存在所选执行环境中。首版原生协议不开放公开文件/Artifact 路由，可通过该 Environment 自身的访问方式检查文件。

当前原生契约不提供快照或 SSE 重放游标。实时 SSE 只接收连接后的事件，断线后用分页历史与
Turn/output 查询恢复状态。失败或等待输入时检查 Turn 错误与公开历史；支持的控制及限制见
[当前 Managed Session 契约](/v2/zh/service/claude-session-api)。

<span id="增加能力与发布"></span>

## 下一步

保留 `AGENT_ID`，继续[通过 Session API 接入应用](/v2/zh/service/claude-session-api)。需要扩展能力时，参阅[Agent 配置](/v2/zh/service/managed-agent-configuration)、[工具与 MCP](/v2/zh/service/tools)和 [SSE 事件](/v2/zh/service/sse-events)。

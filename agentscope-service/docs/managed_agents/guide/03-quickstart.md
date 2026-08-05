# Quickstart · 第一个 Agent 与会话

[← Architecture](02-architecture.md) · [回目录](README.md) · [下一页：Agents →](04-agents.md)

---

用 curl 在约一分钟内创建 Agent、Environment、Session，并完成一轮对话。

## 硬前提

1. **服务已启动**（默认 `http://localhost:8080`）。构建与启动见仓库根目录 [README](../../README.md) Quick Start。
2. **模型 API Key**（例如 DashScope）：

```bash
export DASHSCOPE_API_KEY=sk-xxx
```

3. **默认账号**：首次启动会种子用户 `admin` / 密码 `admin`（生产务必改密与 JWT secret）。

下文假设：

```bash
export BASE=http://localhost:8080
```

## 1. 登录拿 JWT

```bash
TOKEN=$(curl -s -X POST "$BASE/api/auth/login" \
  -H 'Content-Type: application/json' \
  -d '{"username":"admin","password":"admin"}' \
  | jq -r .token)
echo "$TOKEN"
```

响应字段：`token`、`userId`、`username`、`roles`。后续请求带：

```bash
-H "Authorization: Bearer $TOKEN"
```

## 2. 创建 Agent

```bash
AGENT=$(curl -s -X POST "$BASE/api/agents" \
  -H "Authorization: Bearer $TOKEN" \
  -H 'Content-Type: application/json' \
  -d '{
    "name": "Hello Agent",
    "description": "quickstart",
    "system": "You are a helpful assistant. Keep answers short.",
    "tools": [{
      "type": "agent_toolset",
      "defaultConfig": {
        "enabled": true,
        "permissionPolicy": { "type": "always_allow" }
      },
      "configs": [
        { "name": "read_file", "enabled": true },
        { "name": "list_files", "enabled": true }
      ]
    }]
  }')
AGENT_ID=$(echo "$AGENT" | jq -r .id)
echo "$AGENT_ID"
```

未传 `tools` 时服务端会填默认 toolset（全部内置工具 `always_allow`）。字段说明见 [Agents](04-agents.md)。

## 3. 创建 Environment

Session **必须**带 `environmentId`（API 不自动省略）。本地开发用 `local`：

```bash
ENV=$(curl -s -X POST "$BASE/api/environments" \
  -H "Authorization: Bearer $TOKEN" \
  -H 'Content-Type: application/json' \
  -d '{"name":"qs-local1","type":"local","config":{}}')
ENV_ID=$(echo "$ENV" | jq -r .id)
echo "$ENV_ID"
```

## 4. 创建 Session

```bash
SESSION=$(curl -s -X POST "$BASE/api/sessions" \
  -H "Authorization: Bearer $TOKEN" \
  -H 'Content-Type: application/json' \
  -d "{\"agent\":\"$AGENT_ID\",\"environmentId\":\"$ENV_ID\"}")
SESSION_ID=$(echo "$SESSION" | jq -r .id)
echo "$SESSION_ID"
```

`agent` 也可写成对象以 pin 版本，例如 `{"id":"…","version":1}`。见 [Sessions](06-sessions.md)。

## 5. 打开事件流（可选打字机）

另开终端：

```bash
curl -N "$BASE/api/sessions/$SESSION_ID/events/stream?event_deltas=agent.message&event_deltas=agent.thinking" \
  -H "Authorization: Bearer $TOKEN"
```

不带 `event_deltas` 时只推送已落库事件。说明见 [Events](07-events.md)。

## 6. 发送用户消息

```bash
curl -s -X POST "$BASE/api/sessions/$SESSION_ID/events" \
  -H "Authorization: Bearer $TOKEN" \
  -H 'Content-Type: application/json' \
  -d '{
    "events": [{
      "type": "user.message",
      "payload": { "text": "Say hello in one sentence." }
    }]
  }' | jq .
```

观察 SSE：先有 `session.status_running`，再有 `agent.message`（及可选 `event_start` / `event_delta`），最后 `session.status_idle`。

拉历史：

```bash
curl -s "$BASE/api/sessions/$SESSION_ID/events" \
  -H "Authorization: Bearer $TOKEN" | jq .
```

## UI 路径

浏览器打开 AgentScope Service 前端后，Chat 默认走 **managed session**（创建会话 → 发 `user.message` → 流事件）。旧 `/api/agents/{id}/chat/*` 已随四层拆分移除。

## 可选进阶

- **产品验收三路径**（local / E2B / self_hosted Worker）：[14-validation.md](14-validation.md)
- 换 `sandbox`（E2B）/ `self_hosted`：[Environments](05-environments.md)、[Hands / Worker](08-hands-worker.md)
- HITL：工具 `permissionPolicy.type=always_ask`，再发 `user.tool_confirmation`
- 挂 Memory / Vault：创建 Session 时传 `memoryStoreIds` / `vaultIds`
- 上线部署：[部署运维](13-operations.md)

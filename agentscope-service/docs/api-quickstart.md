# API 快速体验：通过 Session 和事件完成对话

[English](api-quickstart.en.md) · [项目介绍](../../README_zh.md) · [API Reference](api-reference.md) · [事件与流式交互](events-and-streaming.md)

创建 Agent 和 Session，打开事件流，发送普通 `user.message`，接收 `agent.message`，在 `session.status_idle` 时结束本次观察。本文按 [Claude Managed Agents quickstart](https://platform.claude.com/docs/en/managed-agents/quickstart) 和[事件指南](https://platform.claude.com/docs/en/managed-agents/events-and-streaming)的 Session/Events 模式组织，使用本项目已经实现的兼容子集。

对话只使用以下 Session 接口：

```text
POST /v1/sessions
GET  /v1/sessions/{session_id}
GET  /v1/sessions/{session_id}/events/stream
POST /v1/sessions/{session_id}/events
GET  /v1/sessions/{session_id}/events
```

Agent/Environment 准备仍使用本项目控制面接口。下方 `POST /api/v1/agents` 是平台配置步骤，不是 Claude 兼容的 `/v1/agents` 接口。Turn/Input 查询、独立最终输出、`agentscope_service` 意图及能力查询是可选平台扩展，见 [Reference](api-reference.md#api-layers)。

## 开始之前

准备当前版本的部署，应用数据库迁移，启动 Runtime、Hands，并配置有效的服务端模型。PostgreSQL、Redis、MQ 的准备见[部署指南](../deploy/README.md)。示例关闭工具，不请求文件或 Sandbox 操作。

- **本地开发**：启用 `BUILDER_LOCAL_DEV=true`，使用 `http://localhost:18080`，`TOKEN` 留空，允许默认本地 Environment。
- **认证部署**：设置公网地址、用户 JWT `TOKEN`、所属 `TENANT`/`NAMESPACE` 和有权使用的可运行 `ENVIRONMENT_ID`。登录使用 `POST /api/auth/login`；创建 Agent 需要管理身份和资源权限。

cURL 流程用同一个 JWT 完成管理与 Session 调用；Application key 也可通过 `X-API-Key` 访问 Session API，但不能替代创建 Agent 所需的管理身份。后面的 SDK 示例支持两种认证方式。这里使用本部署的凭据，不是 Anthropic 或模型供应商密钥。

需要 Bash、支持 `--fail-with-body` 的 `curl` 和 `jq`。使用独立 Session，并在体验期间避免其他调用者同时向该 Session 写入。

## 1. 准备 API 函数（终端 A）

在同一个 Bash 终端运行准备步骤，响应保存在仓库外的临时目录。

```bash
# Bash
set -euo pipefail
export BASE_URL="${BASE_URL:-http://localhost:18080}"
export TENANT="${TENANT:-default}"
export NAMESPACE="${NAMESPACE:-default}"
export TOKEN="${TOKEN:-}"
export ENVIRONMENT_ID="${ENVIRONMENT_ID:-}"
export RUN_ID="quickstart-$(date +%s)-$$"
export D=$(mktemp -d "${TMPDIR:-/tmp}/agentscope-service-api.XXXXXX")

api() {
  local path="$1"
  shift
  local auth=()
  if [[ -n "${TOKEN:-}" ]]; then
    auth=(-H "Authorization: Bearer $TOKEN")
  fi
  curl --fail-with-body -sS "$BASE_URL$path" \
    "${auth[@]}" \
    -H "X-AgentScope-Tenant: $TENANT" \
    -H "X-AgentScope-Namespace: $NAMESPACE" \
    -H 'Content-Type: application/json' \
    -H 'anthropic-version: 2023-06-01' \
    -H 'anthropic-beta: managed-agents-2026-04-01' \
    "$@"
}

api /api/v1/version | jq .
```

## 2. 创建 Agent（平台准备）

Managed binding 和 definition 组成可运行 Agent。`ENVIRONMENT_ID` 指定所选环境，本地开发可准备默认环境。模型使用部署配置，工具显式关闭。

```bash
jq -n --arg tenant "$TENANT" --arg namespace "$NAMESPACE" \
  --arg key "$RUN_ID" --arg env "$ENVIRONMENT_ID" '
{
  tenant: $tenant,
  namespace: $namespace,
  agentKey: $key,
  displayName: "Quickstart Assistant",
  description: "A small conversational Agent for API integration",
  binding: {kind: "managed"},
  definition: {
    name: "Quickstart Assistant",
    system: "You are a helpful assistant. Answer clearly and remember this conversation.",
    maxIters: 8,
    tools: [{type: "agent_toolset", defaultConfig: {enabled: false}}]
  }
}
| if $env != "" then .definition.defaultEnvironmentId = $env else . end
' > "$D/agent-request.json"

api /api/v1/agents -X POST --data-binary "@$D/agent-request.json" \
  > "$D/agent.json"
export AGENT_ID=$(jq -er '.agent.id' "$D/agent.json")
printf 'Agent: %s\n' "$AGENT_ID"
```

管理响应的 ID 位于 `agent.id`，Session 响应直接返回资源。

## 3. 创建 Session

保留 ID，后续消息使用同一 Session。查询初始 `idle` 状态，并保存公开参数和函数供终端 B 使用；保存的文件不包含 token，认证环境需在终端 B 重新设置凭据。

```bash
jq -n --arg agent "$AGENT_ID" --arg env "$ENVIRONMENT_ID" '
{
  agent: $agent,
  title: "My first API conversation",
  metadata: {source: "api-quickstart"}
}
| if $env != "" then .environment_id = $env else . end
' > "$D/session-request.json"

api /v1/sessions -X POST \
  -H "Idempotency-Key: $RUN_ID-session" \
  --data-binary "@$D/session-request.json" > "$D/session.json"
export SESSION_ID=$(jq -er '.id' "$D/session.json")
printf 'Session: %s\n' "$SESSION_ID"

api "/v1/sessions/$SESSION_ID" | jq '{id, status, agent}'

{
  printf 'export BASE_URL=%q\n' "$BASE_URL"
  printf 'export TENANT=%q\n' "$TENANT"
  printf 'export NAMESPACE=%q\n' "$NAMESPACE"
  printf 'export SESSION_ID=%q\n' "$SESSION_ID"
  printf 'export RUN_ID=%q\n' "$RUN_ID"
  printf 'export D=%q\n' "$D"
  declare -f api
} > "$D/client.sh"
printf 'In terminal B, run: source %q\n' "$D/client.sh"
```

## 4. 先打开事件流（终端 A）

```bash
api "/v1/sessions/$SESSION_ID/events/stream" -N
```

看到 `:connected` 注释后再发送消息。该帧表示本实现已经捕获事件流边界。保持终端 A 打开。

## 5. 发送 user.message（终端 B）

另开 Bash 终端，执行第 3 步打印的完整 `source .../client.sh` 命令。认证环境还需先重新导出同一个 `TOKEN`，再调用函数。然后发送：

```bash
cat > "$D/message-request.json" <<'JSON'
{
  "events": [{
    "type": "user.message",
    "content": [{"type": "text", "text": "My name is Lin. Introduce yourself in one sentence."}]
  }]
}
JSON

api "/v1/sessions/$SESSION_ID/events" -X POST \
  -H "Idempotency-Key: $RUN_ID-message-1" \
  --data-binary "@$D/message-request.json" > "$D/message.json"
jq . "$D/message.json"
EVENT_ID=$(jq -er '.data[0].id' "$D/message.json")
printf 'Accepted Event: %s\n' "$EVENT_ID"
```

正文是普通用户事件，没有平台意图或输出选择 Header。HTTP 200 和 Event ID 表示已受理；`processed_at:null` 表示尚未处理完成。HTTP 响应不确定时使用原正文和原键重试，新键会创建新工作。

在终端 A 处理以下事件：

| 事件 | 处理方式 |
| --- | --- |
| `user.message` | 已受理输入处理完成，保持同一个 Event ID |
| `agent.message` | 展示 `content` 中的文本块 |
| `agent.tool_use` / `agent.mcp_tool_use` | 配置工具时展示工具活动 |
| `session.error` | 检查错误；单独收到 idle 不代表执行成功 |
| `session.status_idle` 且 `stop_reason.type:end_turn` | 本次交互停止，可以发送下一条消息 |

本无工具示例应先收到助手消息，再收到 idle。助手文本在模型请求产生已提交内容后到达，不是 token deltas。idle 后 SSE 仍保持连接；Ctrl+C 只停止观察，不取消 Session 中的工作。

## 6. 从事件历史读取答复

交互进入 idle 后，在终端 B 执行：

```bash
api "/v1/sessions/$SESSION_ID/events" -G \
  --data-urlencode 'limit=100' --data-urlencode 'order=asc' \
  > "$D/history.json"
jq . "$D/history.json"
jq -r '.data[] | select(.type == "agent.message") | .content[]? |
       select(.type == "text") | .text' "$D/history.json"
```

这会打印当前页的全部助手消息，不是独立最终输出集合。`next_page` 非 null 时，将它作为 `page` 继续读取，保持相同过滤条件和顺序，直到 null。每次遍历固定读取上界，后续处理状态更新需重新发起遍历。下面的 SDK 示例会自动遍历分页。

事件流只包含未来事件。断开后先开新流并缓冲，再读历史，按 Event ID 合并并允许 `processed_at` 更新；当前没有 `Last-Event-ID`/`after` 续传。刚受理后立刻 GET Session 仍为 idle，不能据此判断消息已经完成，应跟随其事件。

## 7. 继续同一段对话

写入前保持或重新打开事件流，并等待上一段交互进入 idle：

```bash
jq -n '{events: [{
  type: "user.message",
  content: [{type: "text", text: "What name did I tell you?"}]
}]}' > "$D/message-2-request.json"

api "/v1/sessions/$SESSION_ID/events" -X POST \
  -H "Idempotency-Key: $RUN_ID-message-2" \
  --data-binary "@$D/message-2-request.json" | jq .
```

在终端 A 或历史中读取答复。Agent 应记得“Lin”，具体措辞取决于模型。当前兼容子集的普通消息需要 Session 空闲。重定向活动工作可使用受支持的普通批次 `[{"type":"user.interrupt"}, {"type":"user.message","content":[{"type":"text","text":"Focus on the summary instead."}]}]`。显式排队工作和定向交互属于独立的平台扩展。

## 官方 Python SDK 示例

以下是另一种体验方式：复用终端 A 的 Agent/environment，创建**新的** Session，依次进行两次交互，再读取历史。使用仓库固定的兼容基线，不假设所有新版 SDK 功能均支持：

```bash
python3 -m venv "$D/sdk-venv"
"$D/sdk-venv/bin/python" -m pip install 'anthropic==1.15.0'
```

将以下代码保存为 `$D/session-events.py`，在已有 `AGENT_ID`、`BASE_URL`、`RUN_ID` 及环境/凭据的终端 A 运行：

```python
import os
from anthropic import Anthropic, omit

# These are this deployment's credentials, not a model-provider API key.
token = os.environ.get("TOKEN", "")
api_key = os.environ.get("SESSION_API_KEY", "") or "local-development"
if token and os.environ.get("SESSION_API_KEY"):
    raise ValueError("Choose TOKEN or SESSION_API_KEY for Session calls")

with Anthropic(
    base_url=os.environ["BASE_URL"],
    api_key=api_key,
    auth_token="",
    max_retries=0,
    timeout=180.0,
    default_headers={
        "X-Api-Key": omit if token else api_key,
        "Authorization": f"Bearer {token}" if token else omit,
        "X-AgentScope-Tenant": os.environ.get("TENANT", "default"),
        "X-AgentScope-Namespace": os.environ.get("NAMESPACE", "default"),
    },
) as client:
    request_key = os.environ["RUN_ID"] + "-sdk"
    create = {
        "agent": os.environ["AGENT_ID"],
        "title": "Event API quickstart",
        "extra_headers": {"Idempotency-Key": request_key + "-session"},
    }
    if os.environ.get("ENVIRONMENT_ID"):
        create["environment_id"] = os.environ["ENVIRONMENT_ID"]
    session = client.beta.sessions.create(**create)
    print("Session:", session.id, flush=True)

    def converse(text, key):
        # Entering the context opens the HTTP stream before the write.
        with client.beta.sessions.events.stream(session.id) as stream:
            accepted = client.beta.sessions.events.send(
                session.id,
                events=[{
                    "type": "user.message",
                    "content": [{"type": "text", "text": text}],
                }],
                extra_headers={"Idempotency-Key": key},
            )
            print("Accepted:", accepted.data[0].id, flush=True)
            for event in stream:
                if event.type == "agent.message":
                    for block in event.content:
                        if block.type == "text":
                            print(block.text, flush=True)
                elif event.type == "session.error":
                    detail = getattr(event, "error", None)
                    raise RuntimeError(getattr(detail, "message", "Session execution failed"))
                elif event.type == "session.status_idle":
                    reason = getattr(getattr(event, "stop_reason", None), "type", None)
                    if reason != "end_turn":
                        raise RuntimeError(f"Session needs attention: {reason}")
                    return
            raise RuntimeError("Stream closed before idle; inspect Session event history")

    converse("My name is Lin. Introduce yourself in one sentence.", request_key + "-message-1")
    converse("What name did I tell you?", request_key + "-message-2")

    # The SDK follows the history pages; no Turn ID is needed.
    for event in client.beta.sessions.events.list(session.id, limit=100, order="asc"):
        print(event.id, event.type, event.processed_at)
```
```bash
"$D/sdk-venv/bin/python" "$D/session-events.py"
```

用户 JWT 使用 `TOKEN`，有授权的 Application key 使用 `SESSION_API_KEY`，二选一。两者均为空时，`local-development` 只是显式开发模式接受的占位凭据。`omit` 确保请求不会同时带两种认证 Header，即使终端还有其他 SDK 凭据环境变量。脚本只使用官方 Session/Events SDK 接口，并指定本平台 Base URL。

事件流失败或未到 idle 就关闭时，先检查历史和 Session，再决定是否重试已受理写入。冻结回执不会把旧响应事件重放到新开的流中。仓库现有官方 SDK 契约测试验证固定子集，不等同于完整 Claude Managed Agents 兼容。

## 兼容边界与排查

| 现象 | 检查项 |
| --- | --- |
| 401 / 403 | 本部署凭据、Namespace 和 Agent/Environment/Session 权限 |
| 创建 Session 被拒绝 | 模型/环境配置，以及不支持的 Skill/多 Agent/客户端审批 |
| 忙碌时普通消息被拒绝 | 等待 idle，或使用支持的 interrupt-and-message 序列 |
| SSE 没有响应 | 确认发送前已看到 `:connected`；检查历史、Runtime、MQ、Redis |
| `session.error` | 读取错误，不根据之后的 idle 推断成功 |
| 事件流断开 | 读取历史并重建观察；不要为传输重试创建新的逻辑请求 |
| 上游文档中的 `requires_action` / budget / delta 示例 | 超出本版本范围，使用当前部署支持的子集 |

参见 [API 层级与扩展](api-reference.md#api-layers)、[HTTP MCP 示例](managed-mcp-long-tool-cluster-walkthrough.md)、[协议验证](../tests/session-protocol/README.md)。源码检查覆盖语法及固定子集，真实模型部署仍需单独验收。

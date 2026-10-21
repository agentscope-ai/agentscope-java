# POC 第 1 篇：API 快速体验

[English](api-quickstart.en.md) · [POC 阅读顺序](README.md) · 下一篇：[事件与流式交互](events-and-streaming.md) · [API Reference](api-reference.md)

本篇是连续体验的起点，统一配置后续各篇共用的变量和 `api` 函数。

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

准备当前版本的部署，应用数据库迁移，启动 Runtime、Hands，并配置有效的服务端模型。PostgreSQL、Redis、MQ 的准备见[部署指南](../../agentscope-service/deploy/README.md)。示例关闭工具，不请求文件或 Sandbox 操作。

POC 集群 `http://192.0.2.10` 已开启开发身份模式，统一使用 `local-developer`，无需登录或获取 Token。下方已选用集群的文本验收 Environment；按顺序执行即可，也可通过 `ENVIRONMENT_ID` 选择其他环境。

支持 Bash 和 zsh，需要带 `--fail-with-body` 的 `curl` 和 `jq`。使用独立 Session，并在体验期间避免其他调用者同时向该 Session 写入。

## 1. 准备 API 函数（终端 A）

在同一个 Bash 或 zsh 终端运行准备步骤，响应保存在仓库外的临时目录。`BASE_URL`、`TENANT`、`NAMESPACE`、`ENVIRONMENT_ID`、`RUN_ID`、`D` 和 `api` 只在此初始化一次，后续篇章继续沿用。

```bash
set +e
set -uo pipefail
export BASE_URL="${BASE_URL:-http://192.0.2.10}"
export TENANT="${TENANT:-default}"
export NAMESPACE="${NAMESPACE:-default}"
export ENVIRONMENT_ID="${ENVIRONMENT_ID:-env_edbcd65c892f}"
export RUN_ID="quickstart-$(date +%s)-$$"
export D=$(mktemp -d "${TMPDIR:-/tmp}/agentscope-service-api.XXXXXX")

api() {
  local request_path="$1"
  shift
  curl --fail-with-body -sS "$BASE_URL$request_path" \
    -H "X-AgentScope-Tenant: $TENANT" \
    -H "X-AgentScope-Namespace: $NAMESPACE" \
    -H 'Content-Type: application/json' \
    -H 'anthropic-version: 2023-06-01' \
    -H 'anthropic-beta: managed-agents-2026-04-01' \
    "$@"
}

api /api/v1/version | jq .
```

`request_path` 是普通局部变量；zsh 的 `path` 与 `PATH` 绑定，因此不要把函数里的变量名改成 `path`。示例代码没有独立的 `#` 注释行，可直接粘贴到默认交互式 zsh。交互式示例显式关闭 `-e`，并保留未定义变量与管道失败检测，避免请求报错或停止事件流时丢失终端上下文。

如果已运行旧版示例，只重新执行上面的 `api() { ... }` 函数定义，再执行第 3 步的“保存当前上下文”代码块。保存后当前终端会自动加载新脚本；其他已打开的终端也需重新加载它。保留现有的 `D`、`RUN_ID`、`AGENT_ID` 和 `SESSION_ID`，无需重新创建资源。

## 2. 创建 Agent（平台准备）

Managed binding 和 definition 组成可运行 Agent。`ENVIRONMENT_ID` 使用上一步选定的集群环境。模型使用部署配置，工具显式关闭。

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

保留 ID，后续消息使用同一 Session。查询初始 `idle` 状态，并保存参数和函数供终端 B 使用。

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
```

### 保存当前上下文

下面只生成本地 `client.sh`，可以重复执行，不会创建新的 Agent 或 Session。脚本可在 Bash 和 zsh 中加载。

```bash
{
  printf '%s\n' 'set +e' 'set -uo pipefail'
  printf 'export BASE_URL=%q\n' "$BASE_URL"
  printf 'export TENANT=%q\n' "$TENANT"
  printf 'export NAMESPACE=%q\n' "$NAMESPACE"
  printf 'export ENVIRONMENT_ID=%q\n' "$ENVIRONMENT_ID"
  printf 'export AGENT_ID=%q\n' "$AGENT_ID"
  printf 'export SESSION_ID=%q\n' "$SESSION_ID"
  printf 'export RUN_ID=%q\n' "$RUN_ID"
  printf 'export D=%q\n' "$D"
  typeset -f api
} > "$D/client.sh"
source "$D/client.sh"
printf 'In terminal B, run: source %q\n' "$D/client.sh"
```

## 4. 先打开事件流（终端 A）

```bash
api "/v1/sessions/$SESSION_ID/events/stream" -N
```

看到 `:connected` 注释后再发送消息。该帧表示本实现已经捕获事件流边界。保持终端 A 打开。开头的 `set +e` 让 Ctrl+C 只停止观察，不因 `-e` 退出交互式 Shell；若流因网络错误退出，应按排查说明检查并重连。

## 5. 发送 user.message（终端 B）

另开 Bash 或 zsh 终端，执行第 3 步打印的完整 `source .../client.sh` 命令，然后发送：

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

## 8. 继续下一篇

等第二条消息也收到答复及 idle 后，打开[第 2 篇：事件与流式交互](events-and-streaming.md)。保持两个终端和当前 `SESSION_ID`，继续使用同一个 `api`、`RUN_ID` 和 `D`，不再创建 Agent/Session 或重新配置环境。

`$D/client.sh` 已保存全部共享变量和请求函数。使用新终端时，复制第 3 步打印的完整 `source /实际路径/client.sh` 命令恢复上下文。新终端尚未设置 `D`，不要直接执行 `source "$D/client.sh"`。

## 兼容边界与排查

| 现象 | 检查项 |
| --- | --- |
| 401 / 403 | 确认 Base URL 指向本 POC 集群，且集群仍启用开发身份模式 |
| 创建 Session 被拒绝 | 模型/环境配置，以及不支持的 Skill/多 Agent/客户端审批 |
| 忙碌时普通消息被拒绝 | 等待 idle，或使用支持的 interrupt-and-message 序列 |
| SSE 没有响应 | 确认发送前已看到 `:connected`；检查历史、Runtime、MQ、Redis |
| `session.error` | 读取错误，不根据之后的 idle 推断成功 |
| 事件流断开 | 读取历史并重建观察；不要为传输重试创建新的逻辑请求 |
| 上游文档中的 `requires_action` / budget / delta 示例 | 超出本版本范围，使用当前部署支持的子集 |

参见 [API 层级与扩展](api-reference.md#api-layers)、[HTTP MCP 示例](../../agentscope-service/docs/managed-mcp-long-tool-cluster-walkthrough.md)、[协议验证](../../agentscope-service/tests/session-protocol/README.md)。源码检查覆盖语法及固定子集，真实模型部署仍需单独验收。

# Session 事件与流式交互

[English](events-and-streaming.en.md) · [API 快速体验](api-quickstart.md) · [API Reference](api-reference.md)

本文对齐 [Claude 文档：Session event stream](https://platform.claude.com/docs/en/managed-agents/events-and-streaming#send-events) 的事件流程，使用与 API quickstart 一致的 **cURL + jq、双终端**方式：先开流，再发送 `user.message`，接收答复，继续对话或中断重定向。

示例面向已有 Managed Session。Agent/Environment/Session 的创建见快速体验；本文不需要 SDK、Turn ID 或平台扩展 Header。业务请求只使用 Session 查询、Events send/list/stream。

## 1. 准备请求函数（终端 A）

需要 Bash、支持 `--fail-with-body` 的 `curl` 和 `jq`。沿用快速体验导出的 `SESSION_ID`，或先设置已有会话 ID。准备可访问该 Session 的用户 JWT `TOKEN` 或 Application key `SESSION_API_KEY`，二选一；显式本地开发模式可均留空。

在终端 A 执行：

```bash
# Bash: reuse SESSION_ID from the API quickstart, or export an existing ID first.
set -euo pipefail
export BASE_URL="${BASE_URL:-http://localhost:18080}"
export SESSION_ID="${SESSION_ID:?Set SESSION_ID to an existing Managed Session ID}"
export TENANT="${TENANT:-default}"
export NAMESPACE="${NAMESPACE:-default}"
export TOKEN="${TOKEN:-}"
export SESSION_API_KEY="${SESSION_API_KEY:-}"
export RUN_ID="events-$(date +%s)-$$"
export D=$(mktemp -d "${TMPDIR:-/tmp}/agentscope-service-events.XXXXXX")

api() {
  local path="$1"
  shift
  local auth=()
  if [[ -n "${TOKEN:-}" && -n "${SESSION_API_KEY:-}" ]]; then
    echo "Choose TOKEN or SESSION_API_KEY, not both." >&2
    return 1
  elif [[ -n "${TOKEN:-}" ]]; then
    auth=(-H "Authorization: Bearer $TOKEN")
  elif [[ -n "${SESSION_API_KEY:-}" ]]; then
    auth=(-H "X-API-Key: $SESSION_API_KEY")
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

api "/v1/sessions/$SESSION_ID" | jq '{id, status, agent}'

{
  printf 'export BASE_URL=%q\n' "$BASE_URL"
  printf 'export SESSION_ID=%q\n' "$SESSION_ID"
  printf 'export TENANT=%q\n' "$TENANT"
  printf 'export NAMESPACE=%q\n' "$NAMESPACE"
  printf 'export RUN_ID=%q\n' "$RUN_ID"
  printf 'export D=%q\n' "$D"
  declare -f api
} > "$D/client.sh"
printf 'In terminal B, run: source %q\n' "$D/client.sh"
```

这里的 `api` 是上方定义的 cURL 调用函数。`RUN_ID` 在本次体验中保持不变，请求与响应保存在仓库外的临时目录。查询 Session 不会启动工作；使用独立测试 Session，避免其他调用者同时写入。

准备的 `client.sh` 只包含公开参数和函数，不保存凭据。稍后在终端 B 执行打印出的完整 `source .../client.sh` 命令；认证部署还需在终端 B 重新导出相同的 `TOKEN` 或 `SESSION_API_KEY`。

## 2. 先打开事件流（终端 A）

```bash
api "/v1/sessions/$SESSION_ID/events/stream" -N | tee "$D/events.sse"
```

看到 `:connected` 注释后再发送消息，它表示本实现已经捕获事件流边界。保持终端 A 打开；`tee` 同时把观察到的原始 SSE 保存到 `$D/events.sse`。

流只观察建连后的已处理事件，不自动补历史。每条消息一般包含 `event: <类型>` 和 JSON `data: ...`，心跳以注释出现。不能把原始 SSE 直接当作普通 JSON 传给 jq；查询历史时再使用下面的 jq 命令提取文本。

## 3. 发送 user.message（终端 B）

在终端 B 加载第 1 步打印的 `client.sh` 并设置凭据后，执行：

```bash
cat > "$D/message-request.json" <<'JSON'
{
  "events": [{
    "type": "user.message",
    "content": [{"type": "text", "text": "Explain what this Agent can help me with."}]
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

HTTP 200 和 Event ID 表示已经持久受理，`processed_at:null` 表示尚未处理完成。正文是普通文本事件，没有 `agentscope_service` 意图；普通消息需要 Session 空闲且没有活动或排队工作。

HTTP 响应不确定时，用原 JSON 文件和同一个 `$RUN_ID-message-1` 重试这条 cURL 命令，不要重新生成 RUN_ID 或换键。冻结回执不随执行更新，也不会在新开的流中重放旧答复；当前状态通过事件观察。

当前支持每次 1–50 个事件、正文最大 1 MiB，但普通批次只支持单条 message、单条 interrupt，或按顺序的 `[interrupt, message]`，不能任意批量提交多条普通消息。

## 4. 观察答复与空闲边界（终端 A）

| 事件 | 处理方式 |
| --- | --- |
| `user.message` | 输入已处理，Event ID 与受理回执相同，processed_at 更新 |
| `agent.message` | 读取 content 中 type=text 的文本块 |
| `agent.tool_use` / `agent.mcp_tool_use` | 展示实际工具调用的 name/input |
| `agent.tool_result` / `agent.mcp_tool_result` | 通过 tool_use_id / mcp_tool_use_id 关联结果 |
| `span.model_request_start` / `span.model_request_end` | 有真实计量时的模型请求边界和用量 |
| `session.status_running` | 正在执行 |
| `session.error` | 检查错误，不能根据后续 idle 推断成功 |
| `session.status_idle` | 查看 stop_reason，判断本次交互为何停止 |

`stop_reason.type:end_turn` 表示工作停止，正常结束和中断都可能产生此值。助手消息是已提交文本，本版本没有 token deltas。SSE 在 idle 后继续保持连接；看到答复与 idle 后，可以保留此流观察下一条消息。

Ctrl+C 只停止观察，不取消工作。刚提交后立即 GET Session 仍为 idle，也不能证明新消息已经完成；应跟随消息处理事件及后续状态。

## 5. 查询历史与答复（终端 B）

```bash
api "/v1/sessions/$SESSION_ID/events" -G \
  --data-urlencode 'limit=100' \
  --data-urlencode 'order=asc' > "$D/history-page.json"
jq . "$D/history-page.json"
jq -r '.data[] | select(.type == "agent.message") | .content[]? |
       select(.type == "text") | .text' "$D/history-page.json"
```

这会打印当前页的助手文本，可能包括中间答复，不是独立最终输出集合。事件历史还保留工具调用、结果和状态，重建 UI 时应保留这些关联。

`next_page` 非 null 时还有后续页。下面从第一页开始完整遍历，并保存为 JSONL：

```bash
PAGE=""
: > "$D/history.jsonl"
while true; do
  query=(-G --data-urlencode 'limit=100' --data-urlencode 'order=asc')
  if [[ -n "$PAGE" ]]; then
    query+=(--data-urlencode "page=$PAGE")
  fi
  api "/v1/sessions/$SESSION_ID/events" "${query[@]}" > "$D/history-page.json"
  jq -c '.data[]' "$D/history-page.json" >> "$D/history.jsonl"
  PAGE=$(jq -r '.next_page // empty' "$D/history-page.json")
  if [[ -z "$PAGE" ]]; then break; fi
done
jq -s '.' "$D/history.jsonl"
```

保持相同的过滤条件和顺序，用 `page/next_page` 分页，不能混用其他接口游标。每次遍历固定读取上界与投影版本，重新执行完整查询才能看到后续变化。

只查询特定事件类型：

```bash
api "/v1/sessions/$SESSION_ID/events" -G \
  --data-urlencode 'types[]=agent.message' \
  --data-urlencode 'types[]=session.error' \
  --data-urlencode 'types[]=session.status_idle' \
  --data-urlencode 'limit=100' \
  --data-urlencode 'order=asc' | jq .
```

`types[]` 可以重复，通过 `--data-urlencode` 构造查询参数，避免手工拼接 URL；过滤结果也需按 next_page 继续读取。不要把未过滤列表的游标拿来读取过滤列表。created_at[...] 时间过滤实际比较 processed_at，更多字段见 Reference。

## 6. 继续空闲 Session（终端 B）

保持终端 A 的事件流，或先重新开流；等上一段交互进入 idle 后，用同一 Session、新请求键继续：

```bash
cat > "$D/follow-up-request.json" <<'JSON'
{
  "events": [{
    "type": "user.message",
    "content": [{"type": "text", "text": "Summarize the last answer in three points."}]
  }]
}
JSON

api "/v1/sessions/$SESSION_ID/events" -X POST \
  -H "Idempotency-Key: $RUN_ID-message-2" \
  --data-binary "@$D/follow-up-request.json" > "$D/follow-up.json"
jq . "$D/follow-up.json"
```

在终端 A 接收新的 agent.message 和后续 idle，也可以重新查询历史。不需要创建新 Session 或调用 resume 接口；对话上下文由原 Session 保留。

## 7. 中断与重定向（终端 B）

以下是对运行中工作的独立操作场景，保持终端 A 的观察流。简单文本答复可能很快完成，空闲时 interrupt 可能没有需要中断的工作。

只请求中断时执行：

```bash
cat > "$D/interrupt-request.json" <<'JSON'
{"events": [{"type": "user.interrupt"}]}
JSON

api "/v1/sessions/$SESSION_ID/events" -X POST \
  -H "Idempotency-Key: $RUN_ID-interrupt" \
  --data-binary "@$D/interrupt-request.json" > "$D/interrupt.json"
jq . "$D/interrupt.json"
```

中断回执不代表物理工具立即停止，继续观察实际结果。需要同时给出新方向时，使用下面的**另一个场景**：在一个有序批次中发送 interrupt 和 message。

```bash
cat > "$D/redirect-request.json" <<'JSON'
{
  "events": [
    {"type": "user.interrupt"},
    {
      "type": "user.message",
      "content": [{"type": "text", "text": "Change direction: focus only on the risks."}]
    }
  ]
}
JSON

api "/v1/sessions/$SESSION_ID/events" -X POST \
  -H "Idempotency-Key: $RUN_ID-redirect" \
  --data-binary "@$D/redirect-request.json" > "$D/redirect.json"
jq . "$D/redirect.json"
REPLACEMENT_EVENT_ID=$(jq -er '.data[1].id' "$D/redirect.json")
printf 'Replacement message Event: %s\n' "$REPLACEMENT_EVENT_ID"
```

中断旧工作时可能先出现一个 idle，随后新消息才执行。不要把第一个 idle 当成重定向完成：先在终端 A 找到 `id` 等于 `REPLACEMENT_EVENT_ID` 且 processed_at 非 null 的 user.message，再观察它之后的答复和 idle；断线时可从历史补读。

保留工具调用和结果的真实关联。中断不代表可以重放结果未知的外部操作。

## 8. 重连时补历史

1. 在终端 A 停止旧观察，再次运行第 2 步的 cURL 流命令。
2. 看到新的 `:connected` 后保持流打开；终端 B 运行第 5 步的完整历史分页命令。
3. 结合新流与历史恢复视图，不把历史里的旧 idle 当成新消息已经结束。

流不支持 `Last-Event-ID`、after 或单独 durable cursor，不能依赖 SSE 续传。第 2 步的 tee 会重写当前观察日志，历史仍通过接口查询。

同一 Event ID 可能从 pending 更新为 processed：只按 ID 丢弃重复项会漏掉状态变化。应用集成时应按 ID 合并公开表示，允许 processed_at 更新，仅跳过相同的表示。上面的 cURL 命令用于人工观察与查询，没有实现自动合并脚本。

## 与上游完整能力的边界

| 上游流程 | 本版本状态 |
| --- | --- |
| 普通文本、interrupt、SSE、历史 | 按本文命令使用 |
| requires_action、user.tool_confirmation | 未开放客户端工具确认 |
| user.custom_tool_result / user.tool_result | 未开放客户端回填外部结果 |
| budget_reached / Budget | 未开放预算暂停与恢复 API |
| system.message | 当前入口拒绝；系统指令通过 Agent 定义配置 |
| Threads/Workflow、preview/deltas | 不属于当前 Session 兼容子集 |

本项目的服务端工具由 Hands 执行，不使用客户端工具确认/结果回填流程。已有 Session 固定其 Agent 定义；系统指令变化需配置新版本并按需新建 Session。平台 Turn/Input 与独立最终输出是可选增强，见 [API 层级](api-reference.md#api-layers)，不影响本文事件流程。

实际契约由 [Session 校验与 SSE](../service-session-portal/src/main/java/io/agentscope/builder/portal/session/ManagedSessionTurnsApi.java)、[持久事件投影](../service-common/src/main/java/io/agentscope/builder/runtime/managed/ManagedSessionProtocol.java)和[入口过滤器](../service-session-portal/src/main/java/io/agentscope/builder/portal/config/SessionApiErrors.java)决定。本文只采用当前已支持的上游交互模式，不将章节对齐等同于全部能力兼容。

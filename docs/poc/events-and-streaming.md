# POC 第 2 篇：事件与流式交互

[English](events-and-streaming.en.md) · [POC 阅读顺序](README.md) · 上一篇：[API 快速体验](api-quickstart.md) · 下一篇：[工具调用与执行中交互](tool-collaboration.md)

本文对齐 [Claude 文档：Session event stream](https://platform.claude.com/docs/en/managed-agents/events-and-streaming#send-events) 的事件流程，使用与 API quickstart 一致的 **cURL + jq、双终端**方式：先开流，再发送 `user.message`，接收答复，继续对话或中断重定向。

本文接着 Quickstart 的第二轮对话继续，沿用其中的 Agent、Session、共享变量与 `api` 函数。业务请求只使用 Session 查询、Events send/list/stream。

## 1. 沿用 Quickstart 上下文

先完成[第 1 篇](api-quickstart.md)，并等待最后一条消息的答复及 idle。以下命令直接使用已有的 `BASE_URL`、`TENANT`、`NAMESPACE`、`AGENT_ID`、`ENVIRONMENT_ID`、`SESSION_ID`、`RUN_ID`、`D` 和 `api`，无需再次配置。

终端 B 沿用 Quickstart 已加载的 `client.sh`。如果换了新终端，执行 Quickstart 第 3 步打印的完整 `source /实际路径/client.sh` 命令，恢复同一份上下文。

终端 A 若仍在观察 Quickstart 的流，先按 Ctrl+C 停止观察，再执行下面的查询。Ctrl+C 不取消 Session 中的工作。

```bash
mkdir -p "$D/events"
until api "/v1/sessions/$SESSION_ID" | jq -e '.status == "idle"' >/dev/null; do
  sleep 1
done
api "/v1/sessions/$SESSION_ID" | jq '{id, status, agent}'
```

上面的等待应在 Quickstart 最后一条消息已收到答复及 idle 事件后执行，确保查询状态也已进入 idle，再发送新消息。本篇文件保存在 `$D/events/`，幂等键使用 `$RUN_ID-events-...`，与 Quickstart 的请求和响应分别保存。重试时保持原正文和原键。

## 2. 先打开事件流（终端 A）

```bash
api "/v1/sessions/$SESSION_ID/events/stream" -N | tee "$D/events/events.sse"
```

看到 `:connected` 注释后再发送消息，它表示本实现已经捕获事件流边界。保持终端 A 打开；`tee` 同时把观察到的原始 SSE 保存到 `$D/events/events.sse`。

流只观察建连后的已处理事件，不自动补历史。每条消息一般包含 `event: <类型>` 和 JSON `data: ...`，心跳以注释出现。不能把原始 SSE 直接当作普通 JSON 传给 jq；查询历史时再使用下面的 jq 命令提取文本。

## 3. 发送 user.message（终端 B）

在沿用 Quickstart 上下文的终端 B 执行：

```bash
cat > "$D/events/message-request.json" <<'JSON'
{
  "events": [{
    "type": "user.message",
    "content": [{"type": "text", "text": "Explain what this Agent can help me with."}]
  }]
}
JSON

api "/v1/sessions/$SESSION_ID/events" -X POST \
  -H "Idempotency-Key: $RUN_ID-events-message-1" \
  --data-binary "@$D/events/message-request.json" > "$D/events/message.json"
jq . "$D/events/message.json"
EVENT_ID=$(jq -er '.data[0].id' "$D/events/message.json")
printf 'Accepted Event: %s\n' "$EVENT_ID"
```

HTTP 200 和 Event ID 表示已经持久受理，`processed_at:null` 表示尚未处理完成。正文是普通文本事件，没有 `agentscope_service` 意图；普通消息需要 Session 空闲且没有活动或排队工作。

HTTP 响应不确定时，用原 JSON 文件和同一个 `$RUN_ID-events-message-1` 重试这条 cURL 命令，不要重新生成 RUN_ID 或换键。冻结回执不随执行更新，也不会在新开的流中重放旧答复；当前状态通过事件观察。

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

Quickstart 和共享脚本中的 `set +e` 让 Ctrl+C 保留交互式 Shell 和共享上下文。Ctrl+C 只停止观察，不取消工作。刚提交后立即 GET Session 仍为 idle，也不能证明新消息已经完成；应跟随消息处理事件及后续状态。

## 5. 查询历史与答复（终端 B）

```bash
api "/v1/sessions/$SESSION_ID/events" -G \
  --data-urlencode 'limit=100' \
  --data-urlencode 'order=asc' > "$D/events/history-page.json"
jq . "$D/events/history-page.json"
jq -r '.data[] | select(.type == "agent.message") | .content[]? |
       select(.type == "text") | .text' "$D/events/history-page.json"
```

这会打印当前页的助手文本，可能包括中间答复，不是独立最终输出集合。事件历史还保留工具调用、结果和状态，重建 UI 时应保留这些关联。

`next_page` 非 null 时还有后续页。下面从第一页开始完整遍历，并保存为 JSONL：

```bash
PAGE=""
: > "$D/events/history.jsonl"
while true; do
  query=(-G --data-urlencode 'limit=100' --data-urlencode 'order=asc')
  if [[ -n "$PAGE" ]]; then
    query+=(--data-urlencode "page=$PAGE")
  fi
  api "/v1/sessions/$SESSION_ID/events" "${query[@]}" > "$D/events/history-page.json"
  jq -c '.data[]' "$D/events/history-page.json" >> "$D/events/history.jsonl"
  PAGE=$(jq -r '.next_page // empty' "$D/events/history-page.json")
  if [[ -z "$PAGE" ]]; then break; fi
done
jq -s '.' "$D/events/history.jsonl"
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

保持终端 A 的事件流，或先重新开流；收到上一段交互的答复和 idle 事件后，再执行第 1 步的等待与查询命令，确认 Session 查询状态也为 idle，然后用同一 Session、新请求键继续：

```bash
cat > "$D/events/follow-up-request.json" <<'JSON'
{
  "events": [{
    "type": "user.message",
    "content": [{"type": "text", "text": "Summarize the last answer in three points."}]
  }]
}
JSON

api "/v1/sessions/$SESSION_ID/events" -X POST \
  -H "Idempotency-Key: $RUN_ID-events-message-2" \
  --data-binary "@$D/events/follow-up-request.json" > "$D/events/follow-up.json"
jq . "$D/events/follow-up.json"
```

在终端 A 接收新的 agent.message 和后续 idle，也可以重新查询历史。不需要创建新 Session 或调用 resume 接口；对话上下文由原 Session 保留。

## 7. 中断与重定向（终端 B）

以下是对运行中工作的独立操作场景，保持终端 A 的观察流。简单文本答复可能很快完成，空闲时 interrupt 可能没有需要中断的工作。

只请求中断时执行：

```bash
cat > "$D/events/interrupt-request.json" <<'JSON'
{"events": [{"type": "user.interrupt"}]}
JSON

api "/v1/sessions/$SESSION_ID/events" -X POST \
  -H "Idempotency-Key: $RUN_ID-events-interrupt" \
  --data-binary "@$D/events/interrupt-request.json" > "$D/events/interrupt.json"
jq . "$D/events/interrupt.json"
```

中断回执不代表物理工具立即停止，继续观察实际结果。需要同时给出新方向时，使用下面的**另一个场景**：在一个有序批次中发送 interrupt 和 message。

```bash
cat > "$D/events/redirect-request.json" <<'JSON'
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
  -H "Idempotency-Key: $RUN_ID-events-redirect" \
  --data-binary "@$D/events/redirect-request.json" > "$D/events/redirect.json"
jq . "$D/events/redirect.json"
REPLACEMENT_EVENT_ID=$(jq -er '.data[1].id' "$D/events/redirect.json")
printf 'Replacement message Event: %s\n' "$REPLACEMENT_EVENT_ID"
```

中断旧工作时可能先出现一个 idle，随后新消息才执行。不要把第一个 idle 当成重定向完成：先在终端 A 找到 `id` 等于 `REPLACEMENT_EVENT_ID` 且 processed_at 非 null 的 user.message，再观察它之后的答复和 idle；断线时可从历史补读。

保留工具调用和结果的真实关联。中断不代表可以重放结果未知的外部操作。

## 8. 重连时补历史

1. 在终端 A 停止旧观察，再次运行第 2 步的 cURL 流命令。
2. 看到新的 `:connected` 后保持流打开；终端 B 运行第 5 步的完整历史分页命令。
3. 结合新流与历史恢复视图，不把历史里的旧 idle 当成新消息已经结束。

流不支持 `Last-Event-ID`、after 或单独 durable cursor，不能依赖 SSE 续传。第 2 步的 tee 会重写当前观察日志，历史仍通过接口查询。

同一 Event ID 可能从 pending 更新为 processed。：只按 ID 丢弃重复项会漏掉状态变化。应用集成时应按 ID 合并公开表示，允许 processed_at 更新，仅跳过相同的表示。上面的 cURL 命令用于人工观察与查询，没有实现自动合并脚本

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

实际契约由 [Session 校验与 SSE](../../agentscope-service/service-session-portal/src/main/java/io/agentscope/builder/portal/session/ManagedSessionTurnsApi.java)、[持久事件投影](../../agentscope-service/service-common/src/main/java/io/agentscope/builder/runtime/managed/ManagedSessionProtocol.java)和[入口过滤器](../../agentscope-service/service-session-portal/src/main/java/io/agentscope/builder/portal/config/SessionApiErrors.java)决定。本文只采用当前已支持的上游交互模式，不将章节对齐等同于全部能力兼容。

下一篇：[第 3 篇：工具调用与执行中交互](tool-collaboration.md)，复用配置，测试真实 MCP 调用、Brain/Hands、插话、排队和中断重定向。

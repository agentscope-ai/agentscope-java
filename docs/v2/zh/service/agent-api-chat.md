---
title: "接入示例：可恢复的聊天应用"
description: 用 Session API 将 Managed Agent 接入聊天页面，串起任务提交、进度展示、刷新恢复与人工交互。
en_link: /v2/en/service/agent-api-chat
---

本例把一个 Managed“资料助手”接到业务聊天页面。用户提交整理资料的任务后，页面持续展示 Agent 的回复和工具调用；如果执行需要确认，页面让用户检查请求并作出决定。用户离开后再返回同一会话，应用会恢复已经保存的消息、工具结果和待办，再继续接收后续进度。

本例使用 Managed 原生 `/v1/sessions` API，固定一个 Managed Agent 及其 Environment；完整契约见 [Managed Session、Turn 与 Input API](/v2/zh/service/claude-session-api)。其他执行后端使用各自的受理与游标协议。

开始前，先按[创建 Managed Agent](/v2/zh/service/create-managed-agent)配置模型和 Environment，并验证 Agent 能正常回答。要体验工具调用，需要按[工具指南](/v2/zh/service/tools)绑定可用工具；要体验用户确认，还应将对应工具的 `permissionPolicy.type` 设置为 `always_ask`。尚未配置工具时，也可以先完成文字对话和刷新恢复部分。

## 1. 创建会话，提交任务

下面使用 curl 和 jq 在本地开发模式验证交互，无需认证请求头。将示例中的 ID 替换为实际资源 ID；如果 Agent 已配置默认 Environment，可以省略创建请求中的 `environment_id`。本地 Gateway 默认端口为 18080；生产身份配置见[生产部署](/v2/zh/service/kubernetes#production-api-access)。

```bash
set -euo pipefail
export BASE_URL="http://localhost:18080"
export AGENT_ID="YOUR_MANAGED_AGENT_ID"
export ENVIRONMENT_ID="YOUR_ENVIRONMENT_ID"
```

创建这段对话的 Session：

```bash
SESSION_JSON=$(
  curl -sS --fail-with-body "$BASE_URL/v1/sessions" \
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
SESSION_URL="$BASE_URL/v1/sessions/$SESSION_ID"
```

提交第一个问题：

```bash
TURN_KEY="notes-chat-001"
TURN_JSON=$(
  curl -sS --fail-with-body "$SESSION_URL/turns" \
    -H "Content-Type: application/json" \
    -H "Idempotency-Key: $TURN_KEY" \
    --data-binary @- <<'JSON'
{
  "input": {"message": "整理会议待办：小李周五完成安装说明，下周一评审，时间待确认。列出还需要核实的信息。"}
}
JSON
)
TURN_ID=$(jq -er '.id' <<< "$TURN_JSON")
```

创建成功后，应用保存 `SESSION_ID`，让聊天页面的路由能够定位这段对话；同时保存 `TURN_ID`，用于识别刚提交的任务。只有用户新建会话时才创建新的 Session。如果用户只是刷新页面，应用应读取原有记录，而不是再次创建会话或发送问题。

示例中的两个幂等键分别用于创建会话和提交任务。网络超时后重试同一次操作时，应沿用原来的 key 和相同的请求内容。用户确实新建另一段对话或提出下一个问题时，应用才为相应请求生成新的 key。

## 2. 先恢复内容，再订阅 SSE

```bash
SNAPSHOT=$(
  curl -sS --fail-with-body "$SESSION_URL/snapshot"
)
jq '{snapshot_id, as_of, resources}' <<< "$SNAPSHOT"
CURSOR=$(jq -er '.as_of' <<< "$SNAPSHOT")
```

```bash
curl -sS --fail-with-body -N -G "$SESSION_URL/events/stream" \
  -H "Accept: text/event-stream" \
  --data-urlencode "after=$CURSOR"
```

在终端按 Ctrl-C 只会关闭事件订阅，后台任务仍然继续。再次运行这一段时，快照先恢复断开期间已经保存的消息和工具结果，事件订阅再从快照的游标继续更新页面。这样，刷新后的页面既能显示之前的内容，也能接上尚未结束的执行。

同一个 Session 的 SSE 连接可以跨越多个 Turn 保持打开，因此应用不能把连接关闭当作任务完成。应根据目标 `turn_id` 的 `turn.completed` 事件或查询得到的 Turn 状态，判断这次任务是否成功。事件去重、断线续传和后台通知的完整说明见[SSE 与事件续传](/v2/zh/service/sse-events)。

```bash
curl -sS --fail-with-body "$SESSION_URL/turns/$TURN_ID"
```

HTTP 快照在 `resources.<kind>.data` 中返回有界分页。需要更多历史时，沿用同一 `snapshot_id` 通过 `/resources/{kind}` 继续读取。下面的控制台辅助函数会把首批页面转换成自己的视图类型；其中 `snapshot.items` 不是原始 HTTP 响应结构。

## 3. 接到自己的页面

控制台 Session 的 **Execution** 页签已经把这些交互连接起来，对应的组件是 `agentscope-service/frontend/src/components/SessionExecution.tsx`。可以先在这里提交任务、查看工具调用和回答待办，了解一次完整交互如何进行，再把相同能力接到自己的业务页面。

下面的连接代码可以放在控制台的 `src/` 下，复用 `api/agentSessions.ts` 读取快照和事件，再通过 `api/agentSessionView.ts` 把事件应用到页面状态。这两个文件是控制台的客户端实现。迁移到其他应用时，还需要一并适配 `api/http.ts` 的认证依赖和会话类型，并使用自己的 Gateway 地址与登录方式。

```typescript
import {
  AgentStreamError, getAgentSessionSnapshot, streamAgentSession,
} from './api/agentSessions';
import { AgentSessionView } from './api/agentSessionView';
import type { AgentSessionSnapshot } from './api/agentSessions';

export function mountConversation(
  sessionId: string,
  render: (snapshot: AgentSessionSnapshot) => void,
  showError: (error: unknown) => void,
): () => void {
  const controller = new AbortController();
  const { signal } = controller;
  const follow = async () => {
    // Reload once if the stream rejects a stale cursor.
    for (let attempt = 0; attempt < 2 && !signal.aborted; attempt++) {
      const snapshot = await getAgentSessionSnapshot(sessionId, signal);
      if (signal.aborted) return;
      const view = new AgentSessionView(snapshot);
      render(view.snapshot());
      try {
        await streamAgentSession(sessionId, {
          after: snapshot.as_of, signal,
          onEvent(event) {
            if (signal.aborted) return;
            view.apply(event);
            render(view.snapshot());
          },
        });
        return;
      } catch (error) {
        if (signal.aborted) return;
        if (attempt === 0 && error instanceof AgentStreamError
            && [400, 409, 410].includes(error.status)) continue;
        throw error;
      }
    }
  };
  void follow().catch(error => { if (!signal.aborted) showError(error); });
  return () => controller.abort();
}
```

页面打开一个会话时，调用 `mountConversation(sessionId, render, showError)` 来恢复内容并订阅后续事件。离开页面或切换到另一个会话之前，调用它返回的清理函数，停止接收旧会话的事件。`render` 根据消息 ID 和工具调用 ID 更新已有卡片，让一次回复或工具调用在同一个位置持续展示。

短暂断网时，客户端从已经成功应用的 cursor 重连；刷新后走新 snapshot。不要只把 cursor 放入 localStorage 却丢掉对应视图，否则前半段内容不会再次播放。非可恢复的认证/请求错误通过 showError 交给页面处理。

| 页面区域 | 读取 / 更新规则 |
| --- | --- |
| 消息列表 | snapshot.items 的 data.item；按 item_id 更新，item.completed 用完整内容替换 |
| 工具卡 | snapshot.tools；按 turn_id + tool_call_id 更新参数、进度、结果和状态 |
| 待办 | snapshot.required_actions；保留 request_id、turn_id 和 kind |
| 发送中 / 已结束 | snapshot.turns 或目标 turn 事件；run.ended 只表示一次执行结束 |
| 产物与子任务 | snapshot.artifacts / subagents；展开子任务时读取其独立快照与事件 |

工具参数还在生成时，也可能刷新页面；状态更新器利用 snapshot 中保留的 active_tool_call_id 接上省略调用 ID 的后续参数片段。同一任务可产生多条 assistant item 和多个工具调用，不能把全部增量拼到最后一条消息。

## 4. 通过 Turn Input 提交交互

显示操作前，先读取 `$SESSION_URL/turns/$TURN_ID/capabilities` 和 Turn 的 `inputs_open`。原生 API 通过同一个入口接收四种交互：

| 用户意图 | Input 请求体 | 应用依据 |
| --- | --- | --- |
| 修正当前任务 | `{"type":"steer","payload":{"message":"新要求"}}` | Input 回执变为 `applied` |
| 给当前 Turn 补充资料 | `{"type":"inject","payload":{"message":"补充上下文"}}` | 在安全边界应用，或明确拒绝 |
| 回答待办 | `{"type":"reply","action_id":"REQUEST_ID","payload":{...}}` | Reply 已应用，且对应待办被解决 |
| 停止当前任务 | `{"type":"cancel","payload":{}}` | Turn 到达实际终态 |
| 提交新问题 | 另发 `POST /turns`，请求体为 `{"input":{"message":"下一个问题"}}` | 在同一 Session 受理一个新 Turn |

例如，修正一个开放的 Turn，并保存 Input 回执：

```bash
INPUT_JSON=$(
  curl -sS --fail-with-body "$SESSION_URL/turns/$TURN_ID/inputs" \
    -H "Content-Type: application/json" \
    -H "Idempotency-Key: notes-correction-001" \
    --data-binary '{"type":"steer","payload":{"message":"先列出待核实信息，暂时不要写文件。"}}'
)
INPUT_ID=$(jq -er '.id' <<< "$INPUT_JSON")
curl -sS --fail-with-body "$SESSION_URL/turns/$TURN_ID/inputs/$INPUT_ID"
```

`accepted` 表示持久受理，`delivery_status=delivered` 表示传输进展，只有 `status=applied` 才确认运行时已应用。拒绝回执保留原因。网络超时后沿用原 key 和相同内容重试，新意图使用新 key。Input 不创建新 Turn，steer 也不能改变已经发出的模型请求。

从快照的 `resources.required_actions.data` 读取待办。用户选择答复后，把卡片实际的 `data.request_id` 作为 `action_id`：

```bash
ACTION_ID="REQUEST_ID_FROM_PENDING_ACTION"
REPLY_JSON=$(
  curl -sS --fail-with-body "$SESSION_URL/turns/$TURN_ID/inputs" \
    -H "Content-Type: application/json" \
    -H "Idempotency-Key: notes-answer-001" \
    --data-binary @- <<JSON
{"type":"reply","action_id":"$ACTION_ID","payload":{"allow":true,"reason":"用户已确认"}}
JSON
)
REPLY_ID=$(jq -er '.id' <<< "$REPLY_JSON")
curl -sS --fail-with-body "$SESSION_URL/turns/$TURN_ID/inputs/$REPLY_ID"
```

确认类答复使用 `allow`；外部执行请求使用真实的 `content` 和 `is_error`，见[待办答复](/v2/zh/service/session-event-log#回答-required-action)。答复成功不代表任务完成，继续观察同一 Turn。通用外部结果和持久等待输入的完整验收仍属于本轮改造。

取消也通过相同入口提交：

```bash
curl -sS --fail-with-body "$SESSION_URL/turns/$TURN_ID/inputs" \
  -H "Content-Type: application/json" \
  -H "Idempotency-Key: notes-cancel-001" \
  --data-binary '{"type":"cancel","payload":{}}'
curl -sS --fail-with-body "$SESSION_URL/turns/$TURN_ID"
```

取消被受理不证明外部执行已停止，应等待 `completed`、`failed` 或 `cancelled`。`waiting_tool` 在 Hands 提交结果后自动恢复，不要求用户补造结果或另建 Turn。当前原生 capabilities 声明 `resume=false`，这里不提供旧的显式 `/resume` 和 `/commands` 流程。

## 5. 用真实工具走完一次流程

要验证整个交互过程，可以给资料助手绑定至少两个可用的工具操作，并让其中一个需要用户确认。随后提交与工具能力匹配的任务，例如“读取两份材料，分别核对后写出汇总”。当 Agent 调用工具并继续整理结果时，分别尝试下面的页面操作，检查应用是否能恢复已有内容并接上后续执行。

| 尝试 | 应看到的结果 |
| --- | --- |
| 文字生成途中刷新 | 先显示已提交前缀，再继续生成同一条消息 |
| 工具参数生成中刷新 | 同一工具卡接着更新参数 |
| 工具执行中关闭页面，稍后返回 | 离开期间完成的工具结果、后续工具和助手消息均可恢复 |
| 等待确认时刷新 | 待办保留；答复后继续原 turn |
| 断开 SSE 后查看任务 | 服务仍执行；连接中断不会触发 cancel |
| 完成后继续追问 | 同一 session 新建 turn，历史仍在 |

页面恢复用于重新展示已有执行。原生 capabilities 暂未开放 checkpoint restore 和 Fork，仍需完成历史状态验收。长工具自动续跑沿用原 Turn；当前边界见[会话日志与恢复](/v2/zh/service/session-event-log)。

当聊天页面还需要让用户上传材料或下载结果时，可以按[文件与产物](/v2/zh/service/files)接入对应能力。需要后台观察时，可由业务后端消费持久 SSE。原生 Webhook 注册仍待迁移；[Webhook 指南](/v2/zh/service/sse-events#webhooks)介绍已有后端的独立协议。

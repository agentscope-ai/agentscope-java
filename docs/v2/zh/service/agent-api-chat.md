---
title: "接入示例：可恢复的聊天应用"
description: 用 Session API 将 Managed Agent 接入聊天页面，串起任务提交、进度展示、刷新恢复与人工交互。
en_link: /v2/en/service/agent-api-chat
---

本例把一个 Managed“资料助手”接到业务聊天页面。用户提交整理资料的任务后，页面持续展示 Agent 的回复和工具调用；如果执行需要确认，页面让用户检查请求并作出决定。用户离开后再返回同一会话，应用会恢复已经保存的消息、工具结果和待办，再继续接收后续进度。

本例使用[通过 Session API 接入应用](/v2/zh/service/service-api)中介绍的同一套接口，并选择 Managed Agent 展示连续对话和运行中的交互。调用 Team、Workflow 或其他类型的 Agent 时，也通过这个入口创建 Session 和提交 Turn；具体能展示哪些内容、提供哪些操作，取决于目标的执行能力。

开始前，先按[创建 Managed Agent](/v2/zh/service/create-managed-agent)配置模型和 Environment，并验证 Agent 能正常回答。要体验工具调用，需要按[工具指南](/v2/zh/service/tools)绑定可用工具；要体验用户确认，还应将对应工具的 `permissionPolicy.type` 设置为 `always_ask`。尚未配置工具时，也可以先完成文字对话和刷新恢复部分。

## 1. 创建会话，提交任务

下面使用 curl、jq 和[登录取得的用户 token](/v2/zh/service/api-reference#认证与空间)，方便在本地验证与控制台相同的交互。接入业务后端时，可以改用入口指南中已经授权的 Application key。将示例中的 ID 替换为实际资源 ID；如果 Agent 已配置默认 Environment，也可以省略创建请求中的 `environmentId`。本地安装的 Gateway 默认端口为 18080。

```bash
set -euo pipefail
export BASE_URL="http://localhost:18080"
export TOKEN="YOUR_USER_TOKEN"
export TENANT="YOUR_TENANT"
export NAMESPACE="YOUR_NAMESPACE"
export AGENT_ID="YOUR_MANAGED_AGENT_ID"
export ENVIRONMENT_ID="YOUR_ENVIRONMENT_ID"
```

创建这段对话的 Session：

```bash
SESSION_JSON=$(
  curl -sS --fail-with-body "$BASE_URL/api/v1/agent-sessions" \
    -H "Authorization: Bearer $TOKEN" \
    -H "X-AgentScope-Tenant: $TENANT" \
    -H "X-AgentScope-Namespace: $NAMESPACE" \
    -H "Content-Type: application/json" \
    -H "Idempotency-Key: notes-session-001" \
    --data-binary @- <<JSON
{
  "target": {
    "type": "agent",
    "id": "$AGENT_ID"
  },
  "environmentId": "$ENVIRONMENT_ID"
}
JSON
)
SESSION_ID=$(jq -er '.id' <<< "$SESSION_JSON")
SESSION_URL="$BASE_URL/api/v1/agent-sessions/$SESSION_ID"
```

提交第一个问题：

```bash
TURN_KEY="notes-chat-001"
TURN_JSON=$(
  curl -sS --fail-with-body "$SESSION_URL/turns" \
    -H "Authorization: Bearer $TOKEN" \
    -H "X-AgentScope-Tenant: $TENANT" \
    -H "X-AgentScope-Namespace: $NAMESPACE" \
    -H "Content-Type: application/json" \
    -H "Idempotency-Key: $TURN_KEY" \
    --data-binary @- <<'JSON'
{
  "message": "整理会议待办：小李周五完成安装说明，下周一评审，时间待确认。列出还需要核实的信息。"
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
  curl -sS --fail-with-body "$SESSION_URL/snapshot" \
    -H "Authorization: Bearer $TOKEN" \
    -H "X-AgentScope-Tenant: $TENANT" \
    -H "X-AgentScope-Namespace: $NAMESPACE"
)
jq '{items, tools, turns, required_actions}' <<< "$SNAPSHOT"
CURSOR=$(jq -er '.as_of' <<< "$SNAPSHOT")
```

```bash
curl -sS --fail-with-body -N -G "$SESSION_URL/events/stream" \
  -H "Authorization: Bearer $TOKEN" \
  -H "X-AgentScope-Tenant: $TENANT" \
  -H "X-AgentScope-Namespace: $NAMESPACE" \
  -H "Accept: text/event-stream" \
  --data-urlencode "after=$CURSOR"
```

在终端按 Ctrl-C 只会关闭事件订阅，后台任务仍然继续。再次运行这一段时，快照先恢复断开期间已经保存的消息和工具结果，事件订阅再从快照的游标继续更新页面。这样，刷新后的页面既能显示之前的内容，也能接上尚未结束的执行。

同一个 Session 的 SSE 连接可以跨越多个 Turn 保持打开，因此应用不能把连接关闭当作任务完成。应根据目标 `turn_id` 的 `turn.completed` 事件或查询得到的 Turn 状态，判断这次任务是否成功。事件去重、断线续传和后台通知的完整说明见[SSE 与事件续传](/v2/zh/service/sse-events)。

```bash
curl -sS --fail-with-body "$SESSION_URL/turns/$TURN_ID" \
  -H "Authorization: Bearer $TOKEN" \
  -H "X-AgentScope-Tenant: $TENANT" \
  -H "X-AgentScope-Namespace: $NAMESPACE"
```

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

## 4. 把用户操作接到正确的 API

下面将聊天页面上的操作对应到 Session API。所有路径都相对于 `SESSION_URL`；应用使用服务返回的 Session、Turn 和请求 ID 定位已有工作，由服务管理具体执行过程。

显示按钮前读取当前 Turn 的能力；以下操作按用户意图选择，并不需要依次执行：

```bash
curl -sS --fail-with-body "$SESSION_URL/turns/$TURN_ID/capabilities" \
  -H "Authorization: Bearer $TOKEN" \
  -H "X-AgentScope-Tenant: $TENANT" \
  -H "X-AgentScope-Namespace: $NAMESPACE"
```

| 按钮 / 场景 | 请求 | 后续处理 |
| --- | --- | --- |
| 发送新问题 | POST `/turns`，`{message}` | 新 turn、新 key，继续同一个 session 流 |
| 调整当前任务 | POST `/turns/{turn}/steer`，`{message}` | 等 input.applied；409 时重新查看当前任务 |
| 仅补充背景 | POST `/inputs/inject`，`{message}` | 不唤醒空闲 Agent；后续步骤应用 |
| 允许 / 拒绝工具 | POST `/turns/{turn}/actions` | 按实际待办构造答复，继续观察命令状态和待办变化 |
| 停止 | POST `/turns/{turn}/cancel` | 等明确 turn 结果，不把 cancel_requested 当作已停止 |
| 继续中断任务 | POST `/turns/{turn}/resume` | 先处理待办和未知工具结果；沿用 turn，可能产生新 run |

<AccordionGroup>

<Accordion title="调整当前要求，或仅补充背景">

调整正在执行的任务：

```bash
curl -sS --fail-with-body "$SESSION_URL/turns/$TURN_ID/steer" \
  -H "Authorization: Bearer $TOKEN" \
  -H "X-AgentScope-Tenant: $TENANT" \
  -H "X-AgentScope-Namespace: $NAMESPACE" \
  -H "Content-Type: application/json" \
  -H "Idempotency-Key: notes-correction-001" \
  --data-binary @- <<'JSON'
{
  "message": "先列出待确认事项，暂时不要写入文件。"
}
JSON
```

仅保存背景材料，不启动推理：

```bash
curl -sS --fail-with-body "$SESSION_URL/inputs/inject" \
  -H "Authorization: Bearer $TOKEN" \
  -H "X-AgentScope-Tenant: $TENANT" \
  -H "X-AgentScope-Namespace: $NAMESPACE" \
  -H "Content-Type: application/json" \
  -H "Idempotency-Key: notes-context-001" \
  --data-binary @- <<'JSON'
{
  "message": "补充背景：评审参与人还包括安装说明的维护者。"
}
JSON
```

</Accordion>

<Accordion title="停止当前任务">

```bash
CANCEL_JSON=$(
  curl -sS --fail-with-body -X POST "$SESSION_URL/turns/$TURN_ID/cancel" \
    -H "Authorization: Bearer $TOKEN" \
    -H "X-AgentScope-Tenant: $TENANT" \
    -H "X-AgentScope-Namespace: $NAMESPACE" \
    -H "Idempotency-Key: notes-cancel-001"
)
CANCEL_COMMAND_ID=$(jq -er '.command.id' <<< "$CANCEL_JSON")
```

查询取消命令，再读取 Turn 状态确认终态：

```bash
curl -sS --fail-with-body "$SESSION_URL/turns/$TURN_ID/commands/$CANCEL_COMMAND_ID" \
  -H "Authorization: Bearer $TOKEN" \
  -H "X-AgentScope-Tenant: $TENANT" \
  -H "X-AgentScope-Namespace: $NAMESPACE"
```

```bash
curl -sS --fail-with-body "$SESSION_URL/turns/$TURN_ID" \
  -H "Authorization: Bearer $TOKEN" \
  -H "X-AgentScope-Tenant: $TENANT" \
  -H "X-AgentScope-Namespace: $NAMESPACE"
```

</Accordion>

<Accordion title="继续可恢复的任务">

只有 `available_commands` 包含 `resume`，并已处理待办和未知工具结果时，才提交恢复请求：

```bash
RESUME_JSON=$(
  curl -sS --fail-with-body -X POST "$SESSION_URL/turns/$TURN_ID/resume" \
    -H "Authorization: Bearer $TOKEN" \
    -H "X-AgentScope-Tenant: $TENANT" \
    -H "X-AgentScope-Namespace: $NAMESPACE" \
    -H "Idempotency-Key: notes-resume-001"
)
RESUME_COMMAND_ID=$(jq -er '.command.id' <<< "$RESUME_JSON")
```

```bash
curl -sS --fail-with-body "$SESSION_URL/turns/$TURN_ID/commands/$RESUME_COMMAND_ID" \
  -H "Authorization: Bearer $TOKEN" \
  -H "X-AgentScope-Tenant: $TENANT" \
  -H "X-AgentScope-Namespace: $NAMESPACE"
```

</Accordion>

</AccordionGroup>

创建 Turn、调整要求、补充背景和回答待办时，都应为这一次操作提供稳定的 `Idempotency-Key`。下面演示用户检查工具请求并点击“允许”后，应用如何提交确认答复。`REQUEST_ID` 必须来自当前待办卡片中的 `request_id`，不能用工具调用 ID 替代：

```bash
curl -sS --fail-with-body "$SESSION_URL/turns/$TURN_ID/actions" \
  -H "Authorization: Bearer $TOKEN" \
  -H "X-AgentScope-Tenant: $TENANT" \
  -H "X-AgentScope-Namespace: $NAMESPACE"
```

```bash
REQUEST_ID="REQUEST_ID_FROM_PENDING_ACTION"
COMMAND_JSON=$(
  curl -sS --fail-with-body "$SESSION_URL/turns/$TURN_ID/actions" \
    -H "Authorization: Bearer $TOKEN" \
    -H "X-AgentScope-Tenant: $TENANT" \
    -H "X-AgentScope-Namespace: $NAMESPACE" \
    -H "Content-Type: application/json" \
    -H "Idempotency-Key: notes-approval-001" \
    --data-binary @- <<JSON
{
  "request_id": "$REQUEST_ID",
  "payload": {
    "allow": true,
    "reason": "用户已确认"
  }
}
JSON
)
COMMAND_ID=$(jq -er '.command.id' <<< "$COMMAND_JSON")
```

答复被接收后，应用应继续查询返回的命令状态，并观察待办是否已经处理、任务是否继续执行。HTTP 请求成功只说明服务接收了这次操作，不能立即把待办标为完成。如果命令失败或待办仍然存在，应重新读取最新记录，让用户了解当前状态后再决定是否重试。

```bash
curl -sS --fail-with-body "$SESSION_URL/turns/$TURN_ID/commands/$COMMAND_ID" \
  -H "Authorization: Bearer $TOKEN" \
  -H "X-AgentScope-Tenant: $TENANT" \
  -H "X-AgentScope-Namespace: $NAMESPACE"
```

待办要求外部工具执行结果时，应在 `payload` 中提交实际的 `output` 和 `is_error`；需要指定人员确认时，则使用该人员有权限的用户身份。如何定位待办和处理命令回执见[回答 required action](/v2/zh/service/session-event-log#回答-required-action)。这些答复是在推进原来的任务，最终是否完成仍以 Turn 的结果为准。

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

上面的页面恢复只是在重新显示已有工作。恢复旧 checkpoint 会改变 Agent 的上下文，之后需要提交新的 Turn；如果需要继续被中断的原任务，则应检查恢复条件后使用 `resume`。这两类执行操作见[会话、任务与预算](/v2/zh/service/session-event-log)。

当聊天页面还需要让用户上传材料或下载结果时，可以按[文件与产物](/v2/zh/service/files)接入对应能力。如果希望用户离开后由业务后端接收完成通知，则在[SSE 与事件续传](/v2/zh/service/sse-events#webhooks)中继续配置 Webhook。

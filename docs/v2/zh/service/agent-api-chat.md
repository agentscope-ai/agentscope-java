---
title: "接入示例：可恢复的聊天应用"
description: 用 Managed 原生会话 API 串起多轮聊天、工具调用、刷新恢复、人工确认与任务取消。
en_link: /v2/en/service/agent-api-chat
---

本例把一个 Managed“资料助手”接到业务聊天页面：用户发送材料，Agent 调用工具并整理结果；用户离开后，返回同一会话仍能看到完整的已提交消息、工具参数、结果和待办。

本例使用 Managed 原生会话 API，适合直接管理托管会话及其消息、工具和待办。若要通过发布的服务统一接入不同类型的 Agent，或调用 Team、Workflow，从[统一服务 API](/v2/zh/service/service-api)开始。

先按[创建 Managed Agent](/v2/zh/service/create-managed-agent)配置模型和 Environment，并用该页的会话请求验证一次推理。工具演示需要绑定可用的工具；人工确认演示需要该工具的 `permissionPolicy.type=always_ask`。没有工具的 Agent 也可以先验证文字和刷新恢复。

## 1. 创建会话，提交任务

下面使用 curl、jq 和[登录取得的用户 token](/v2/zh/service/api-reference#认证与范围)。本地安装的 Gateway 默认端口为 18080。把 ID 替换为实际资源 ID；如果 Agent 已配置默认 Environment，可省略 environmentId。

```bash
export BASE_URL='http://localhost:18080'
export TOKEN='YOUR_USER_TOKEN'
export AGENT_ID='YOUR_MANAGED_AGENT_ID'
export ENVIRONMENT_ID='YOUR_ENVIRONMENT_ID'

SESSION_JSON=$(curl --fail-with-body -sS "$BASE_URL/api/v1/agent-sessions" \
  -H "Authorization: Bearer $TOKEN" -H 'Content-Type: application/json' \
  -d "$(jq -n --arg agent "$AGENT_ID" --arg env "$ENVIRONMENT_ID" \
        '{agent:$agent, environmentId:$env}')")
export SESSION_ID=$(printf '%s' "$SESSION_JSON" | jq -er '.id')
export SESSION_URL="$BASE_URL/api/v1/agent-sessions/$SESSION_ID"

TURN_KEY='notes-chat-001'
TURN_JSON=$(curl --fail-with-body -sS "$SESSION_URL/turns" \
  -H "Authorization: Bearer $TOKEN" -H 'Content-Type: application/json' \
  -H "Idempotency-Key: $TURN_KEY" \
  -d '{"message":"整理会议待办：小李周五完成安装说明，下周一评审，时间待确认。列出还需要核实的信息。"}')
export TURN_ID=$(printf '%s' "$TURN_JSON" | jq -er '.id')
```

保存 SESSION_ID 作为聊天页面的路由参数，保存 TURN_ID 作为这次任务的标识。创建只发生在“新建会话”时；刷新页面不创建 session、不重发输入。每个新问题使用新幂等键；同一次提交超时重试保持原 key 和原输入。

## 2. 先恢复内容，再订阅 SSE

```bash
SNAPSHOT=$(curl --fail-with-body -sS "$SESSION_URL/snapshot" \
  -H "Authorization: Bearer $TOKEN")
printf '%s' "$SNAPSHOT" | jq '{items,tools,turns,required_actions}'
CURSOR=$(printf '%s' "$SNAPSHOT" | jq -er '.as_of')
curl --fail-with-body -N -G "$SESSION_URL/events/stream" \
  -H "Authorization: Bearer $TOKEN" -H 'Accept: text/event-stream' \
  --data-urlencode "after=$CURSOR"
```

终端中的 Ctrl-C 只关闭订阅。再次运行这一段，会先读到离开期间已保存的消息和工具，再继续接收新事件；不会漏掉“离开前已生成的前半段”。SSE 跨越多个 turn 保持打开，以目标 `turn_id` 的 `turn.completed` 判断成功。

## 3. 接到自己的页面

Console 已有完整参考：`agentscope-service/frontend/src/components/SessionExecution.tsx`，位于 Session 的 **Execution** 页签。它展示消息、工具卡、待办、文件输入及 steer/inject。先用这个页面体验能力，再接到自己的业务 UI。

下面是可放在 Console `src/` 下的页面连接代码。复用 `api/agentSessions.ts` 和 `api/agentSessionView.ts`；它们是仓库中的客户端实现，不是需要安装的独立 SDK。迁移到其他应用时，一并处理 `api/http.ts` 的认证依赖和会话类型，替换为自己的 Gateway 地址及用户登录方式。

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
            && [400, 409].includes(error.status)) continue;
        throw error;
      }
    }
  };
  void follow().catch(error => { if (!signal.aborted) showError(error); });
  return () => controller.abort();
}
```

挂载或切换 session 时调用 `mountConversation(sessionId, render, showError)`；卸载或切换前调用它返回的清理函数。`render` 按消息 ID 和工具调用 ID 更新卡片；会话列表排序来自列表查询，选择会话只更新选中态。

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

以下路径相对 SESSION_URL。页面只需要保存 session/turn/request 标识，不需要自己分配 run ID。

| 按钮 / 场景 | 请求 | 后续处理 |
| --- | --- | --- |
| 发送新问题 | POST `/turns`，`{message}` | 新 turn、新 key，继续同一个 session 流 |
| 调整当前任务 | POST `/turns/{turn}/steer`，`{message}` | 等 input.applied；409 时重新查看当前任务 |
| 仅补充背景 | POST `/inputs/inject`，`{message}` | 不唤醒空闲 Agent；后续步骤应用 |
| 允许 / 拒绝工具 | POST `/turns/{turn}/actions` | 按实际待办 kind 构造答复，等待 resolved / rejected |
| 停止 | POST `/turns/{turn}/cancel` | 等明确 turn 结果，不把 cancel_requested 当作已停止 |
| 继续中断任务 | POST `/turns/{turn}/resume` | 先处理待办和未知工具结果；沿用 turn，可能产生新 run |

turns、steer、inject 和 actions 每个逻辑提交都携带稳定的 Idempotency-Key。下面演示用户点击“允许”后的 confirmation 答复，REQUEST_ID 必须取自当前卡片，而不是工具调用 ID：

```bash
REQUEST_ID='REQUEST_ID_FROM_PENDING_ACTION'
curl --fail-with-body -sS "$SESSION_URL/turns/$TURN_ID/actions" \
  -H "Authorization: Bearer $TOKEN" -H 'Content-Type: application/json' \
  -H 'Idempotency-Key: notes-approval-001' \
  -d "$(jq -n --arg request "$REQUEST_ID" \
        '{answers:[{request_id:$request,allow:true,reason:"用户已确认"}]}')"
```

`accepted` 表示已接收答复；`resolved` 表示运行时已处理。`rejected` 表示投递失败，若仍待处理则恢复卡片。`external_execution` 待办需提交 output/is_error，格式见[人工交互](/v2/zh/service/session-event-log#回答-required-action)。HTTP 答复成功不等于整个任务完成。

## 5. 用真实工具走完一次流程

给资料助手绑定至少两个可用的工具操作，并让一个需要确认。发送与工具能力匹配的任务，例如“读取两份材料，分别核对后写出汇总”。不要只靠提示词要求模型慢慢输出；实际工具耗时和模型调用决定增量节奏。

| 尝试 | 应看到的结果 |
| --- | --- |
| 文字生成途中刷新 | 先显示已提交前缀，再继续生成同一条消息 |
| 工具参数生成中刷新 | 同一工具卡接着更新参数 |
| 工具执行中关闭页面，稍后返回 | 离开期间完成的工具结果、后续工具和助手消息均可恢复 |
| 等待确认时刷新 | 待办保留；答复后继续原 turn |
| 断开 SSE 后查看任务 | 服务仍执行；连接中断不会触发 cancel |
| 完成后继续追问 | 同一 session 新建 turn，历史仍在 |

恢复旧 checkpoint 是另一种操作：它改变 Agent 的上下文，之后提交新 turn；不是刷新页面，也不是继续原任务。需要分支试验、文件交付、用量预算或后台通知时，继续阅读 [Agent API 使用指南](/v2/zh/service/session-event-log)。全部事件与错误处理见 [SSE 文档](/v2/zh/service/sse-events)。

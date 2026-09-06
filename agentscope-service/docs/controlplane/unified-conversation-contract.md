# 统一会话展示契约

## 决策

AgentScope 不把 Codex、Claude Code、Qoder、QwenPaw、OpenClaw 或 Managed Harness
的原生事件改写成一套伪装成“原生”的万能协议。不同运行时对 turn、step、tool、stream chunk、
permission 和 compaction 的定义并不等价，强行合并会丢失恢复和排障所需的信息。

控制面采用两层模型：

1. **Native event log** 是事实源。原始事件和 framework metadata 必须无损保留；
2. **AgentScope conversation projection** 是读模型。它把事实源投影成统一的 Messages 和 Events，
   供 Session detail、Managed Chat、Chat 和 Endpoint Test API 使用。

前后端交互采用 DeepSeek Harness 相同的基线模型：打开会话时读取一次 Event history tail，随后保持
一条可续传的 SSE subscription；客户端以最后一个 `seq` 恢复连接并增量折叠 projection，不轮询
Messages 或 Events。`message-query` 仅保留为旧客户端/运行时兼容接口，不是会话 UI 依赖能力。
SSE 服务端通过事件仓库通知唤醒（PostgreSQL `LISTEN/NOTIFY`，内存仓库 channel），心跳只负责保持
连接，不通过周期性查询发现新事件。

公开 Endpoint Test API 的 conversation SSE 使用 Session `seq`，job SSE 使用 Run
`sequence`；两者都接受 `after` 和 `Last-Event-ID`，并通过对应持久仓库的通知机制唤醒。它们共享恢复
语义，但 Run event 仍是编排事实，不会伪装成 Agent Session message。

### 交付与恢复语义

- Java/Python SDK 在发送前把完整 `SessionEventMsg` 追加到本地 journal 并 `fsync`；控制面返回
  `EventReportAck` 的连续持久化水位后才删除本地记录；断线、进程重启或 ACK 丢失都会重传。
- 控制面要求每个 session 的 `seq` 连续，以 `(session_fk, seq)` 唯一约束实现幂等落库。因此传输是
  at-least-once，数据库中的可见结果是一次；缺口不会被静默越过。
- 浏览器先取历史尾页，再以最后一个 `seq` 建立 SSE。SSE 重连携带同一游标；若收到跳号事件，
  客户端把它暂存并用 `GET .../events?after=<seq>` 正向补洞，成功后再合并实时事件。
- `message-query` 不参与上述路径。页面关闭期间事件继续进入控制面事件仓库，重新打开后从历史尾页
  恢复；页面刷新和代理断开只会重建订阅，不会重置 session 游标。

这与 DeepSeek Harness 的关键设计一致：append-only event log 负责可重放事实，message surface
负责人类和模型可读的会话；UI 不把渲染状态写回事件日志。

## `agentscope.conversation.v1`

前端规范类型位于 `frontend/src/features/conversation/model.ts`。

### Message

Message 是可读投影，不是持久化协议：

| 字段 | 说明 |
| --- | --- |
| `id` / `seq` | 稳定标识与可选原始序号 |
| `role` | `user`、`assistant`、`system`、`tool`、`error` |
| `blocks` | 顺序排列的 text、tool 或 data 内容块 |
| `turnIndex` | 能可靠归属时记录 turn；不做猜测时可省略 |
| `state` | streaming、complete 或 error |
| `raw` | 可选原始 message 记录，供检查而非渲染逻辑使用 |

Tool block 使用稳定 `callId` 关联调用与结果。输入、输出不能仅拼成一段文本，否则无法支持
详情面板、错误状态以及后续的 tool-specific renderer。

### Event

Event 是展示层的统一 envelope，原始 payload 保持不透明：

| 字段 | 说明 |
| --- | --- |
| `id` / `seq` / `occurredAt` | 标识、顺序和时间 |
| `type` | 原始或 adapter 规范化后的事件名 |
| `category` | message、model、tool、turn、lifecycle、error、other |
| `messageId` / `callId` / `turnIndex` | 与可读投影的关联键 |
| `durationMs` / `tokensIn` / `tokensOut` | 可选观测信息 |
| `payload` | 无损原生/框架数据，Events 视图展开显示 |

`category` 只控制展示，不参与 session replay。未知 event 必须进入 `other` 并保留 payload，
而不是被前端丢弃。

## Runtime 映射

| Runtime | 当前事实源 | 投影策略 |
| --- | --- | --- |
| Managed Harness | `user.message`、`agent.message`、`agent.tool_use/result`、status/error 与 SSE delta | 增量合成 assistant message；所有持久事件同时进入 Events |
| Codex | `codex app-server --listen stdio://` 双向 JSON-RPC，包含 thread/turn/item 生命周期及服务端 approval request | thread id 映射 provider session；agent message、reasoning、command/tool item 投影为 message/tool；approval 接入控制面，完整 JSON-RPC envelope 保留为 payload |
| Claude Code | `stream-json` 的 system/assistant/user/result 记录与 content blocks | text、thinking、tool_use/tool_result 分块；session id 保持 resume 关联；原始 envelope 保留 |
| Qoder | `--input-format stream-json --output-format stream-json` 双向宿主协议 | 使用独立 adapter；`control_request/can_use_tool` 接入控制面 approval，消息投影到相同 Message/Event contract |
| QwenPaw | ACP JSON-RPC，主要由 `session/update` 和 permission request 表达过程 | update kind 映射 message/model/tool/lifecycle；JSON-RPC request/response 保留为原始 payload |
| OpenClaw | 当前 `agent exec --json` 是 one-shot final envelope | 产生 user message、final assistant message 和 result lifecycle event；未提供的中间 tool/turn 事件不伪造 |

现有 ASDP Level-2 Event 和 Level-3 Message 继续作为 External Application 的稳定传输面。
Runtime Host adapter 的原生事件仍可按 provider 独立扩展，控制台只依赖 conversation projection。

### Hosted Agent 会话语义

Conversation 能力与部署模式解耦。Hosted Agent 的一个逻辑 Session 可以包含多个 turn；每个 turn
在控制面内部物化为独立、不可见于默认 Issues 列表的 `conversation_turn` Issue，以及对应的 Run、
AgentTask 和 ExecutionAttempt。ExecutionAttempt 保存稳定 `sessionId`、本轮 `turnId` 和 provider 返回的
不透明 `providerSessionId`。下一轮仅在 Runtime Host 的 provider descriptor 声明 `resume=true` 时把该 ID
交给 Codex、Claude Code、Qoder 或 QwenPaw adapter 执行原生 resume；OpenClaw 等未声明 resume 的
adapter，或没有返回可续传 ID 的 provider，由控制面从持久事件日志回放最近的 user / assistant
消息。同一会话的后续 attempt 优先回到上一轮 Runtime Host，并使用会话级稳定 workspace；若该 Host
下线，则清除仅对原 Host 有效的 provider session ID，在其他兼容 Host 上通过 transcript replay 恢复。
Team 和 Workflow 仍保持 Job-only，避免把多 Agent 编排状态伪装成单一对话状态。

Runtime Host 上报的 provider event 同时写入 Run timeline 和 Session event log。后者以
`attemptId + ordinal` 去重并在 `frameworkMeta.raw` 中保留原始 envelope；成功终态追加统一的
`assistant.message` 与 `turn.completed`，失败或取消则追加对应 turn 事件并把 Session 恢复为 idle。
因此页面关闭、刷新或 SSE 重连不影响消息完整性，Endpoint invocation 的终态也由同一次 hosted
attempt 完成投影。

## 统一交互

所有会话入口共享 `ConversationSurface`。面向最终用户的直接会话入口是 `Chat`；统一导航中的
Sessions 提供全局运维视图，Agent Detail 仍保留该 Agent 范围内的只读 Session 投影；
Endpoint Detail 中保留经过真实 Gateway 的 `Test API`：

- 默认显示 Conversation，可切换到完整 Events；
- assistant Markdown、user bubble、tool input/output card 使用同一套样式；
- 原始 event payload 可逐条展开；
- Chat 与 Endpoint Test API 共用底部 composer；只读 Session 不渲染 composer；
- Conversation 与 Events 来自同一份 event history，加载更早内容推进同一个事件游标；
- 首次加载 history tail，之后通过 `/api/v1/sessions/{id}/events/stream` 实时增量更新；
- Chat 引用稳定的 control-plane `sessionRef`，后续事件订阅不再依赖可能歧义的 provider session id；
- Chat 是独立的用户级聚合，保存 creator、title、pinned、archived 等产品状态；Runtime Session
  继续只表达绑定、实例、provider session、phase 和事件等运行状态；
- 每个 Chat turn 可以在当前调度实现中使用隐藏的 `conversation_turn` Issue 作为执行载体，
  但该记录不会进入 Issues 的普通或“全部来源”列表。

当前默认不清理 `session_events`（`--retention-session-events=0`）。后续引入归档或分层存储时，必须
保持相同的 seq 游标和 history + live 合并契约。

## 边界

- Context snapshot 是“下一次模型调用看到什么”，不等于完整 conversation，继续放在独立详情面板；
- Execution/Run event 是编排事件，不等于 Agent Session event，不纳入本契约；
- UI 的折叠、选中、滚动和当前 tab 都是本地状态，不写入 event log；
- adapter 无法提供中间事件时，界面显示能力缺失，不根据最终文本反推虚构事件。

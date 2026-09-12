# AgentTask 运行时工具确认 HITL 契约（v5）

> 状态：已实现的运行契约与验收基线（`agentscope-service-v5`）。本文覆盖 Managed、Hosted Runtime
> 和 External Application 三类 AgentTask 的控制面审批；个人 Chat 继续使用 Session 内联确认。
>
> 相关设计：[Team Issue-first 协作契约](./team-collaboration-v5.md)、
> [Issue 协作终态契约](./collaboration-contract.md)。

## 1. 决策摘要

Managed AgentTask 的工具确认必须使用控制面的 `Approval` 作为唯一人类入口：

```text
Managed turn 请求工具确认
  -> data plane 持久化原始 ticket，并上报 fenced session.requires_action
  -> control plane 幂等创建 Approval + Inbox，Task/Attempt 进入 waiting
  -> designated human 在 /work/approvals approve/reject
  -> approval.decided.v1 outbox 可靠回调原 data plane ticket
  -> data plane 按 session/attempt/generation/turn/toolUse fence 做一次性 resolve
  -> 同一个仍存活的 turn 恢复，Task/Attempt 回到 running
  -> 正常完成、失败，或在 continuation 已丢失时由新 Attempt 恢复
```

这不是 Workflow `approval` node，也不是 Session 页上的第二套按钮：

- `Approval` 是人类决定与审计事实；
- `ExecutionAttempt.waiting` 是物理执行暂停；
- `AgentTask.waiting` 是本次语义义务等待外部输入；
- Session `requires_action` 是运行时投影；
- `/work/approvals` 是 AgentTask HITL 唯一可写 UI；AgentTask Session transcript 保持只读；
- 个人 Managed Chat 没有 AgentTask fence，仍在 ChatPanel 直接发送
  `user.tool_confirmation`，不创建 Work Approval。

## 2. 实现状态与边界

### 2.1 已实现

- `ToolConfirmationMiddleware` 对 `always_ask` fail-closed，持久化完整 fence ticket，并把拒绝结果以
  `DENIED` ToolResult 交还 Agent 继续推理；
- `session.requires_action` 在控制面幂等投影为 Approval + human Inbox，并以同一存储事务把
  AgentTask/Attempt 切换到 `waiting`；PostgreSQL 与 memory store 都支持 waiting lease 续租；
- human approve/reject 使用 designated approver + `expectedVersion` CAS，决定先持久化，再由 durable
  outbox 至少一次投递；超时由控制面 sweeper 以 `cancelled` 决定收敛；
- callback、confirmation ACK、heartbeat、abort、普通 managed event 和产品 Session runtime 状态都携带
  `agentTaskId + attemptId + dispatchGeneration + turnId`；旧 Attempt 返回 409/410，且不会占用新事件的
  source key；
- data plane 把 admitted scope 与精确 turn lease 固化在 `ManagedTurnContext`，工具中间件不再从可变的
  session 全局状态重新发现 scope；
- 决定分两阶段释放：共享 ticket 先 CAS，只有实际持有本地 waiter 与精确 lease 的 JVM 才能向控制面
  ACK 并标记 `continuationReady`；回调落到其他副本时返回可重试结果；
- 旧 Attempt 的 abort 使用 durable outbox + 完整 fence。延迟的 abort、heartbeat、status patch 或 finally
  都不能停止/覆盖新 Attempt；产品 Session 另有持久、generation-monotonic runtime fence；
- data plane turn lease 在协调库 heartbeat 暂时失败时只容忍到本地 `validUntil`；一旦租约可能已被其他
  副本接管，旧 owner 必须按精确 lease fail-closed，不能在数据库分区后继续执行；
- `/work/approvals` 展示类型化工具摘要、脱敏输入、expiry 以及 Issue/Task/Execution/Session 链接；
  AgentTask Session 保持只读，个人 Chat 继续使用内联确认。

### 2.2 有意保留的边界

- ticket 可以跨副本传递决定，但 Java Future、模型调用栈不能跨 JVM 重启恢复。owner 丢失后系统 fence
  旧 Attempt，并重试同一 AgentTask；不会假装原地续跑；
- Approval 的 `approved` 和 delivery ACK 都不表示外部工具副作用已经成功。副作用 exactly-once 仍需
  tool adapter 使用 `toolUseId` 或等价 idempotency key；
- `AttemptTimeoutSeconds > 0` 当前定义为 wall-clock attempt 上限，包含 human wait；默认 0 表示禁用。
  人类审批另有独立 expiry。若产品以后需要 active-compute budget，应新增累计等待时长，而不是改变
  当前语义；
- delivery 终态当前用互斥幂等的 RunEvent
  `approval.delivery_delivered | approval.delivery_stale` 投影，不额外修改 Approval 表结构。
- Hosted 是 runtime-host 能力，不等于每个 provider 都原生支持可恢复 permission。当前 QwenPaw ACP
  adapter 支持 `session/request_permission`；其他 provider 通过 capability descriptor 显式显示不支持，
  不能把非交互 CLI 的 permission denial 伪装成 HITL。

## 3. 权威对象与唯一写入者

| 事实 | 权威位置 | 唯一写入者 |
| --- | --- | --- |
| 原始 tool name/input、等待线程 | data plane HITL ticket / 活跃 turn | `ToolConfirmationCoordinator` |
| 人类是否同意及其身份/note | control plane `Approval` | Approval application service |
| 物理 turn 是否仍可恢复 | `ExecutionAttempt` lease + fence | managed heartbeat / sweeper |
| Task 是否等待人类 | `AgentTask.status` | control-plane managed event projector |
| Session 展示状态 | Session event/read model | data plane event log + fenced CP projector |
| 决定是否成功投递 | delivery RunEvent + outbox | control outbox handler |

`approved` 只表示人类同意，不表示工具已经执行。工具执行成功仍由后续 tool result、Attempt 与
Task completion 证明。

## 4. 请求契约

### 4.1 data plane ticket

AgentTask 中创建的 ticket 至少持久化以下不可变字段：

```json
{
  "schemaVersion": 1,
  "sessionId": "managed-session-id",
  "toolUseId": "provider-tool-call-id",
  "toolName": "web_search",
  "inputJson": "{...original arguments...}",
  "ownerRef": "human-or-managed-owner",
  "agentTaskId": "uuid",
  "attemptId": "uuid",
  "dispatchGeneration": 3,
  "turnId": "turn-id",
  "continuationLeaseId": "brain-instance/turn-uuid",
  "createdAt": 1788652800000,
  "expiresAt": 1788656400000,
  "resolutionStatus": null,
  "decisionVersion": 0,
  "resolvedAllow": null,
  "denyMessage": null,
  "continuationReady": false
}
```

必须以复合 identity 查找和 resolve ticket，不能再把 `toolUseId` 当成全局唯一键：

```text
(sessionId, attemptId, dispatchGeneration, turnId, toolUseId)
```

数据库同时保持 `(sessionId, toolUseId)` 和完整执行 fence 唯一约束；新 turn admission 会清除旧 turn
ticket，因而同一 provider 重用 `toolUseId` 时不会别名到旧 continuation。个人 Chat 的 URL session
owner 校验之后，还会验证 `ticket.sessionId == path.sessionId`。

### 4.2 `session.requires_action` event

请求事件 payload 使用稳定、可版本化的最小形状：

```json
{
  "kind": "tool_confirmation",
  "schemaVersion": 1,
  "toolUseId": "call_xxx",
  "toolName": "web_search",
  "inputPreview": {"query": "..."},
  "inputSha256": "sha256-of-canonical-original-input",
  "requestedAt": 1788652800000,
  "expiresAt": 1788656400000
}
```

`sessionId` 在 path/report body，`attemptId`、`dispatchGeneration`、`turnId` 在 managed report
envelope；控制面不接受 payload 自己覆盖这些 scope。

控制面只对同时满足以下条件的事件创建 Work Approval：

1. Session 唯一存在并绑定一个 AgentTask；
2. report 的 Attempt 等于 Task `currentAttemptId`；
3. Attempt backend 为 `managed`，session、generation、turn 全部相等；
4. Task 与 Attempt 非终态且处于 `running`（重复事件可处于 waiting）；
5. `kind=tool_confirmation`、toolUseId/toolName 非空、expiresAt 合法；
6. 能解析 designated human approver。

个人 Chat 的同名事件只更新 Session read model，不创建 Approval。

### 4.3 Approval 映射与幂等

AgentTask tool confirmation 使用已有、受 task scope 支持的目标：

```text
targetType = execution_attempt
targetRef  = attemptId
```

Approval `request` 保存可审计关联，不保存 token、内部 callback URL 或未脱敏 secret：

```json
{
  "kind": "managed_tool_confirmation",
  "schemaVersion": 1,
  "sourceEventId": "data-plane-event-id",
  "sessionId": "managed-session-id",
  "agentTaskId": "uuid",
  "attemptId": "uuid",
  "dispatchGeneration": 3,
  "turnId": "turn-id",
  "toolUseId": "call_xxx",
  "toolName": "web_search",
  "inputPreview": {"query": "..."},
  "inputSha256": "...",
  "requestedAt": 1788652800000,
  "expiresAt": 1788656400000
}
```

当前实现以 URL namespace UUIDv5 派生 Approval ID，名称为：

```text
aistio:managed-hitl:v1:<tenant>:<sessionId>:<attemptId>:<dispatchGeneration>:<turnId>:<toolUseId>
```

控制面会重新计算并核对该 ID，同时按完整物理 tool-use fence 查重。它不依赖 `sourceEventId`：即使
data plane 崩溃重报了不同事件 ID，也只能得到同一 Approval、一条 Inbox 和一条
`approval.requested.v1` outbox；同一 fence 携带另一 Approval ID 会被拒绝。

`requestedBy` 是执行 Task 的 Agent，`issueId/runId/runNodeId` 来自 Task，不采信 payload。
approver 的解析顺序必须确定且在 Task 创建时固化：

1. `AgentTask.accountableHumanRef`；
2. 若缺失，只可回退到 human 类型的 Issue creator / Run creator；
3. 仍不可解析时，不得自动路由给任意 admin。请求以
   `hitl_approver_unavailable` 明确失败，并创建运维 attention。

Team 委派必须把根任务的 accountable human 传播到所有 worker Task，否则 worker HITL 会变成
无人可批的 pending 工作。

### 4.4 输入安全

- 原始 input 只留在 data plane ticket；控制面存储并展示 schema-aware 脱敏的 preview；
- 凭据、token、password、secret、authorization 字段必须遮盖；request 总大小设硬上限；
- `inputSha256` 绑定人类看到的调用与 data plane 原始调用；
- callback 不携带可修改 input，data plane 只能执行原 ticket 中的 tool call；
- 审批不是副作用 exactly-once 保证。会产生外部副作用的 tool 必须把 `toolUseId` 作为
  idempotency key，或由 tool adapter 提供等价防重。

## 5. 状态机与事务边界

### 5.1 请求进入 waiting

处理首个合法 `session.requires_action` 时，控制面必须以一个事务或可重放的原子 application
operation 完成：

```text
Approval:       absent -> pending
Inbox:          absent -> approval attention
AgentTask:      running -> waiting
Attempt:        running -> waiting
Session:        active -> requires_action
RunEvent:       append hitl.requested
Outbox:         append approval.requested.v1
```

RunNode 保持其既有 `waiting(agent_task|team_coordinator)`，不创建额外 Workflow Approval node。
当该 Run 没有其他 runnable node 时，Run 可投影为 `waiting(human_approval)`。

若当前 store 不能跨 Session/Approval/Task/Attempt 开一个事务，处理顺序必须让重放可以补齐半状态：
先通过稳定 request key `get-or-create` Approval，再 CAS 状态，最后以 source key 追加 Session
event；不能因为 Session event 已存在便跳过尚未完成的 Approval/状态投影。

### 5.2 waiting 期间的三个时钟

1. **liveness lease**：继续每 10 秒 heartbeat，waiting 允许 `RenewLease`；45 秒无活动说明
   原 JVM continuation 已丢失，旧 Attempt fail/retry。data plane 另用精确 owner token 维护 turn
   lease；callback 只有在该 lease 与本地 waiter 同时存在时才能 ACK。协调库 heartbeat 失败只可容忍
   到本地记录的 lease deadline；超过 deadline 后旧 owner 必须精确中断自己，防止新副本接管后 A/B
   并跑。
2. **human approval timeout**：由控制面 `Approval.expiresAt` 驱动，默认与
   `builder.tool-confirmation.timeout-ms` 一致；到期将 pending Approval 置 `cancelled` 并回调 deny。
   managed data plane 的本地 timer 不自行决定超时，只等待控制面权威决定。
3. **execution timeout**：Runtime Policy 的 `AttemptTimeoutSeconds` 当前明确是从 `startedAt` 计算的
   wall-clock 上限，waiting 也计时；默认 0（禁用）。配置非零值时，运维侧应保证它大于期望的
   approval timeout。

未来若需要 active-compute timeout，应引入 `waitingSince/accumulatedWait` 后再改变 policy；v5 不对
现有 wall-clock 语义作隐式暂停。

### 5.3 决定与恢复

人类决定只允许：

```text
pending -> approved | rejected
```

系统超时、Task/Run cancel、Attempt 被替换使用：

```text
pending -> cancelled
```

不要把系统超时伪装成 human `rejected`。Approval 决定提交与 Inbox archive 必须先完成，再由
`approval.decided.v1` outbox 投递；禁止先调用 data plane、执行工具后才提交人类决定。

data plane callback 建议为内部认证 endpoint：

```text
POST /api/internal/sessions/{sessionId}/tool-confirmations/{toolUseId}/decision
X-Builder-Internal-Token: ...
```

```json
{
  "approvalId": "uuid",
  "decisionVersion": 2,
  "status": "approved",
  "allow": true,
  "denyMessage": "",
  "agentTaskId": "uuid",
  "attemptId": "uuid",
  "dispatchGeneration": 3,
  "turnId": "turn-id"
}
```

callback URL 只能来自服务端 `BUILDER_DATA_URL`/可信 registry，不能来自 Approval request，避免
SSRF。data plane 必须验证全部 fence、ticket 未过期及 decision 与既有 resolution 一致：

| 情况 | HTTP | 语义 |
| --- | --- | --- |
| 首次合法决定且命中 owner JVM | 204 | ticket CAS resolve，owner ACK 后恢复 turn |
| 相同 approval/version/决定重放 | 204 | 幂等成功，不再次执行工具 |
| ticket 属于旧 Attempt/turn/session | 409 | stale fence，永久不可投递 |
| ticket 已过期/被取消 | 410 | 永久不可投递 |
| ticket 不存在 | 404 | 不可恢复；控制面收敛旧 Attempt |
| continuation lease 已丢失 | 410 | owner/model stack 不存在；控制面收敛旧 Attempt |
| callback 落到非 owner 副本 | 503 | 决定已持久化；等待 owner poller ACK，outbox 重试 |
| 瞬时网络/5xx | 5xx | outbox 指数退避重试 |

合法 resolve 的顺序为：ticket CAS -> 精确 local waiter/turn lease 复核 -> owner 追加带
`approvalId/source=control_plane` 的 fenced `user.tool_confirmation` 并等待控制面接受 -> Session
status running -> ticket `continuationReady=true` -> 完成本地 Future。非 owner 副本只能执行第一步，
不能伪造 continuation ACK。confirmation ACK 是释放 continuation 的线性化点；其镜像
`session.status_running` 驱动控制面完成：

```text
AgentTask: waiting -> running
Attempt:   waiting -> running
Run:       waiting -> running（若仍有 active work）
```

拒绝不是 Task 自动失败：middleware 把该 tool call 标为 denied，Agent 可以换方案或明确报告
blocked。只有 Agent/turn 随后显式失败，Task 才失败。

### 5.4 决定投递状态

人类决定和运行时投递是两个不同事实。当前不扩充 Approval 行，而是在 Run timeline 中作等价投影：

```text
approval.delivery_delivered
approval.delivery_stale
idempotencyKey = approval-delivery:<approvalId>:<decisionVersion>
```

两个终态共享同一个 first-write-wins idempotency key，因而 outbox 重放、事件保留清理或进程崩溃都
不能同时发布 delivered 与 stale。5xx 重试次数和最近错误继续由 durable outbox 记录。

- `approved + delivered`：允许信号已被活跃 turn 接受，仍不代表工具成功；
- `approved + stale`：人类同意，但 continuation 在投递前已丢失；工具没有从该决定执行；
- `rejected/cancelled + delivered`：等待线程被解除，tool 不执行；
- 持续 5xx 由 outbox retry/dead-letter；
- 404/409/410 是永久结果，不应无限重试。记录 `hitl.delivery_stale` RunEvent，并让旧 Attempt
  以 `hitl_continuation_lost` 失败、同一 Task 按 policy 创建 fresh Attempt。

## 6. 崩溃、重试与竞态

### 6.1 data plane 重启

持久 ticket 不能重建 Java Future、ReAct 调用栈或未 checkpoint 的模型 stream。v5 的恢复承诺是：

1. heartbeat 停止；
2. lease sweeper fence 旧 Attempt，写 `hitl_continuation_lost`/`heartbeat_timeout`；
3. 同一个 AgentTask 按 Runtime Policy 创建 fresh Attempt；
4. 旧 Approval 若仍 pending 则 cancelled；若已 decided，则 delivery 标为 stale；
5. 新 Attempt 若再次请求同一工具，创建属于新 fence 的新 Approval，不能复用旧 allow。

“同一进程/其他 Brain 副本可通过共享 ticket resolve”不等于“重启后可恢复模型栈”。在实现
checkpointable continuation 前，不应向用户承诺原 turn 跨 JVM restart 原地恢复。

### 6.2 关键竞态

- **approve 与 timeout**：控制面 Approval CAS 的先提交者胜出；managed data plane 本地 expiry 不产生
  第二个决定，只等待控制面的权威 cancelled/rejected callback。
- **approve 与 cancel**：Task/Run cancel 若先提交，Approval cancelled，后续 human decide 409；
  human decide 若先提交，callback 仍需发现 Task/Attempt 已 cancel 并返回 stale。
- **旧 callback 与新 Attempt**：`currentAttemptId + generation + turn` fence 必须拒绝旧决定。
- **callback 落错副本**：可以持久化决定，但没有 exact local waiter 的副本返回 503，只有 owner
  poller 能发送 continuation ACK；owner 消失则 lease 到期后返回 410。
- **旧 abort/heartbeat 与新 Attempt**：abort ticket、heartbeat response 和本地 interrupt 都绑定原
  Attempt + turn lease；校验与取消在同一 per-session 临界区，不能在 check/act 间杀掉替代 turn。
- **旧 finally 与新 Attempt**：active turn 句柄、turn lease 和 self-hosted Hands work lease 都按
  expected owner token 条件删除；旧 teardown 不能删除或 stop 新资源。
- **旧 runtime PATCH 与新 Attempt**：dispatch 在 wake 前推进产品 Session 的持久 runtime fence；PATCH
  还要通过 runtime-store current Attempt 校验与 generation-monotonic SQL CAS。
- **重复 event**：同一 request key 只创建一个 Approval；状态已 waiting 时视为幂等。
- **重复 callback**：ticket resolution CAS 只完成 Future 一次；相反决定返回 409。
- **ACK 后控制面崩溃**：稳定事件 ID `evt_hitl_decision_<approvalId>` 及完整 metadata 是 durable
  receipt；outbox 重放据此记录 delivered，不会因 Task 随后终态而误记 stale。
- **工具已执行后崩溃**：Approval 不提供 exactly-once；依赖 tool idempotency/checkpoint。

## 7. UI 契约

`/work/approvals` 是当前可写入口：只查询当前 operator、默认只显示 pending、5 秒刷新、按
expectedVersion 决策并归档对应 Inbox。AgentTask HITL 已补：

- 识别 `request.kind=managed_tool_confirmation`，展示 tool name、脱敏 input preview、倒计时；
- 展示 requesting Agent，并链接 Issue、AgentTask、Execution、只读 Session transcript；
- approve/reject 提交后禁用重复点击，CAS 冲突时刷新并显示真实终态；
- pending Approval 到期或 Task 已 cancel 时按钮不可用；
- AgentTask 的 Session 页面继续 `readOnly=true`，可以显示“前往 Approval”链接，但不得出现另一套
  Allow/Deny；个人 Chat 继续原内联 confirmation card。

Raw request JSON 可保留为 diagnostics 展开项，但不能成为主要交互，也不能包含未脱敏 secret。
delivery delivered/stale 当前在 Execution Run timeline 中可审计；独立的 Approval 历史/详情页属于后续
可观测性增强，不是 continuation 正确性的前置条件。

## 8. 验收测试矩阵

### 8.1 data plane 单元/组件测试

| 编号 | 场景 | 断言 |
| --- | --- | --- |
| DP-H01 | AgentTask always_ask 创建 ticket | ticket 含完整 fence；event payload 版本正确；Session requires_action |
| DP-H02 | personal Chat always_ask | 不要求 AgentTask scope；仍可 URL session 内联确认 |
| DP-H03 | 跨 session 猜中 toolUseId | resolve 被拒绝，原 ticket 不变 |
| DP-H04 | 旧 attempt/generation/turn callback | 409；Future 不完成；工具不执行 |
| DP-H05 | 同一 approved callback 重放 | 两次均 204；Future/工具只推进一次 |
| DP-H06 | approved 后收到 rejected | 409；原决定不变 |
| DP-H07 | cancelled/timeout | tool 不执行；追加 resolved/status 事件；waiter 释放 |
| DP-H08 | 第二 Brain resolve | 共享 ticket 被轮询者发现，同一活跃进程恢复 |
| DP-H09 | secret input | CP preview 被遮盖，原 ticket input 不变，hash 稳定 |
| DP-H10 | callback internal auth | 无/错误 token 401/403，不能 resolve |
| DP-H11 | callback 落到非 owner replica | 只 CAS 决定并返回 503；不 ACK、不设 ready |
| DP-H12 | owner crash / lease lost | 410；旧 ticket 不能形成 delivered ACK |
| DP-H13 | A/B turn takeover | A 的 heartbeat/abort/finally 不取消或覆盖 B |
| DP-H14 | self-hosted Hands takeover | A release 不能 stop B 的 work lease |
| DP-H15 | coordination DB heartbeat 分区 | deadline 前容忍；deadline 后精确停止 A，不能与接管的 B 并跑 |

### 8.2 control plane memory + PostgreSQL store contract

| 编号 | 场景 | 断言 |
| --- | --- | --- |
| CP-H01 | 首次 request projection | 一条 Approval/Inbox/requested outbox；Task/Attempt waiting |
| CP-H02 | 相同事件重放 | 所有对象数量不变；返回相同 Approval |
| CP-H03 | 不同 event ID、相同 request key | 仍只有一条 Approval |
| CP-H04 | waiting heartbeat | lease/heartbeat 更新，版本递增，不产生 conflict |
| CP-H05 | resume | Task/Attempt waiting->running，Run 恢复；fence 不变 |
| CP-H06 | 非 designated human decide | 403；Approval 保持 pending |
| CP-H07 | expectedVersion 竞争 | 仅一个决定成功，另一个 409 |
| CP-H08 | expiry sweep | pending->cancelled，Inbox 归档，产生 decided outbox |
| CP-H09 | task/run cancel | 关联 pending Approval cancelled，并产生 deny callback |
| CP-H10 | stale event | 不创建 Approval、不修改当前 Task/Attempt |
| CP-H11 | approver propagation | Team worker 继承 accountable human；可在该用户 Inbox 查询 |
| CP-H12 | 无 approver | 明确 `hitl_approver_unavailable`，不创建孤儿 pending Approval |
| CP-H13 | delayed old event/ACK/abort | 409/410 或幂等 204；不修改 B，不占 B source key |
| CP-H14 | delayed product status PATCH | pre-wake marker + monotonic CAS 阻止 A 覆盖 B |

### 8.3 outbox/callback contract

| 编号 | 场景 | 断言 |
| --- | --- | --- |
| CB-H01 | decision commit 后进程退出 | outbox 重启后仍投递 |
| CB-H02 | callback 5xx 两次后 204 | 指数退避；最终 delivered；只执行一次 |
| CB-H03 | callback 404/409/410 | 不无限重试；delivery stale；旧 Attempt 收敛/重试 |
| CB-H04 | callback URL 注入 request | 被忽略，只调用可信配置的 data URL |
| CB-H05 | outbox 重复 claim/delivery | DP 幂等，delivery audit 不重复 |
| CB-H06 | owner ACK 后 CP crash | stable confirmation receipt 使重放仍记 delivered，不误记 stale |

### 8.4 发布前最小真实 E2E

先使用一个 deterministic test tool（记录 invocation count），无需复杂 Team fanout：

1. **Allow**：Managed worker 触发 always_ask；`/work/approvals` 出现一条；等待超过两个 lease
   TTL 不重试；approve 后同一 Attempt 恢复，tool invocation count=1，Task/Run 成功。
2. **Reject**：reject 后 tool invocation count=0；Agent 收到 denial 并给出替代结果或明确 blocked；
   Approval 为 rejected+delivered。
3. **Timeout**：不操作直到 expiry；Approval cancelled，tool count=0，等待线程释放；无永久 pending。
4. **Restart while waiting**：停 data plane；旧 Attempt 被 fence 并 fresh retry；旧 Approval
   cancelled/stale；晚到 approve 不能作用于新 Attempt。
5. **Race**：approve、cancel、timeout 并发，最终只有一个 Approval 决定；最多一个活跃 Attempt；
   tool count 不因 callback 重放增加。
6. **Team accountable human**：Managed leader 委派 Managed worker；worker HITL 出现在原
   accountable human Inbox；approve 后 worker result 回流 leader，Team coordinator 正常收敛。

快速开发阶段不要求每次代码改动都运行这一组；合并/发布前执行即可。

### 8.5 Console 测试

- pending tool Approval 的类型化字段、倒计时和四个资源链接正确；
- approved/rejected 请求按钮立即禁用，版本冲突刷新后不覆盖别人决定；
- AgentTask transcript 无直接确认按钮；personal Chat 仍有；
- secret 字段永不出现在 DOM snapshot；
- approval delivery stale 在 Execution timeline 可查，不显示成“工具已执行”。

## 9. 必须永久成立的不变量

1. 一个 `(attempt,generation,turn,toolUse)` 最多一个 Approval/Inbox。
2. 一个 pending AgentTask tool Approval 对应恰好一个 waiting current Attempt。
3. waiting Attempt 的有效 heartbeat 可以续租，Session idle 不能触发 retry。
4. 旧 Attempt/turn 的 event 或 callback 永不修改当前 Task、ticket 或新 Approval。
5. 人类决定先持久化，再投递；callback 至少一次，ticket resolve 至多一次。
6. `approved` 不等于 tool succeeded；投递状态和工具结果独立可查。
7. reject/cancel/timeout 均不执行 tool，并最终释放 waiter。
8. data plane restart 不假装恢复已丢失的模型栈；通过 fresh Attempt 恢复同一 Task。
9. AgentTask 只从 Work Approval 决策；个人 Chat 只从 Session 内联决策。
10. Approval request、event、log、UI 都不得泄漏 credential 或 task/internal token。
11. 只有持有 exact local waiter 与 exact turn lease 的 JVM 能发布 continuation ACK；共享 ticket 本身
    不能证明模型调用栈仍存在。
12. 所有 session-scoped teardown 都是 expected-owner 条件操作；A 的延迟 cleanup 不能删除 B 的
    turn、waiter 或 Hands work lease。
13. 协调库不可达不能无限延长本地 turn 权限；本地 deadline 到期后旧 owner 必须 fail-closed。

## 10. 完成标准与后续范围

本轮 P0 已按以下边界完成：request -> Approval/Inbox -> waiting；human CAS decision -> durable
outbox -> fenced owner ACK -> running；reject/cancel/timeout、continuation lost、Attempt replacement 和
跨副本 callback 均 fail-closed；个人 Chat 不回归。快速开发回归采用定向组件/竞态测试，不把真实模型
和长时间故障注入作为每次修改的门禁；协调库分区时旧 owner 在本地 lease deadline 后停止。

发布前仍应执行 8.4 的真实 E2E。可运营性增强包括 Approval 历史详情、dead-letter attention、主动
展示 delivery 状态，以及未来可选的 active-compute timeout；这些不改变本文件已定义的安全协议。

### 扩展到其他 backend

Hosted/External 已接入统一 Approval domain，但 continuation adapter 与 Managed 不同：

- Hosted/External 使用 task token 调用
  `POST /agent-tasks/{taskId}/runtime-approvals` 创建请求，轮询
  `GET .../{approvalId}/decision` 获取决定，再调用 `POST .../{approvalId}/ack` 确认接收；
- Hosted 的 QwenPaw ACP adapter 把 permission request/response 映射到上述通道；runtime-host 在等待期间
  持续续租；
- External Java Harness 把 `GenerateReason.PERMISSION_ASKING` 中的 `ToolUseBlock` 映射为 Approval，收到
  决定后以 `ConfirmResult` 恢复同一个 Agent，并在最终完成前刷新 Task version；
- 三类 backend 共享 Approval/Inbox、超时 sweeper、Task/Attempt waiting 状态和完整
  `(backend,session,attempt,generation,turn,toolUse)` fence；Managed 仍使用原有 push callback/ticket，
  Hosted/External 使用 pull decision + ACK，Managed 的 JVM Future 假设没有渗透到统一协议。

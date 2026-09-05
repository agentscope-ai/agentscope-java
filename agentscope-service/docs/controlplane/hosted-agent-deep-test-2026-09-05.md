# Hosted Agent 深度测试记录（2026-09-05）

## 结论

本轮针对真实本地 Runtime Host、真实 Codex/Qoder provider、PostgreSQL 控制面完成了端到端测试，没有修改实现代码。

- Hosted Chat 基本链路通过：Codex 两轮连续对话正确保留上下文并复用 provider session；Qoder 干净单轮对话也成功。
- 声明式 Workflow 通过：Hosted worker 先完成，Hosted lead 能读取持久化结果并验收，Run 最终成功。
- 自适应 Team 未通过：leader 能分配子 Issue，worker 能认领并产出结果，leader 也能收到结果，但回调任务不断生成新 RunNode，原 coordinator 无法收敛；测试最终人工取消。
- 故障恢复未通过：一次机器暂停/时钟跳变触发执行租约过期后，Task 被重排为 `queued`，但重试事件死信，Task、Run 和 Chat 永久悬挂；旧 provider 也没有及时停止。

当前确认 4 个 P1、5 个 P2/P3 问题。建议先修复 Team 收敛和租约恢复，再处理协议语义与观测噪声。

## 测试环境

- 测试标记：`hosted-deep-20260905-192144`
- API Gateway：`http://127.0.0.1:18080`
- 存储：PostgreSQL，schema `rt`
- Runtime Host：`c299c684-29fb-49c7-a6ba-a20b8ef33ad3`
- Runtime Pool：`946930d5-77a4-4f9d-8867-58e5889d8843`（`coding-default`，capacity `1`）
- Codex：`codex-cli 0.152.1`
- Qoder：`qodercli 1.0.37`
- Codex Runtime Profile：`1040db5d-040b-4e2d-b09c-41eedc5e5b7a`
- Qoder Runtime Profile：`3da496bc-117e-4112-9e0c-8d2a36a1a8f6`

测试时仓库已有大量未提交改动，因此本记录对应“当前工作区 + 当时运行中的本地二进制”，不是某个干净 commit 的发布验收结果。所有测试对象保留在本地开发库中，便于后续逐项修复和复验。

## 测试对象

| 对象 | ID |
| --- | --- |
| Hosted Codex lead Agent | `10b37c4e-b5e2-4d7c-97ce-0bace5319b02` |
| Hosted Codex worker Agent | `d5f2530e-ff55-49dc-b47a-f9687bc4998c` |
| Hosted Qoder Agent | `9e29e3f9-65e4-4afe-8678-88f2d5019eb0` |
| Team | `564b3266-546a-4acd-be6a-1f738201390c` |
| Team adaptive Run | `564cb18c-c722-4a68-88f9-3ba651a15902` |
| Declared Workflow Run | `56bab462-bd3f-4f5d-84c1-6fd95a848fc2` |

## 场景结果

### 1. Codex Hosted Chat：通过

Chat：`0e5ba229-796a-4701-9f0c-2dfba4583b37`

第一轮要求记住随机值 `ORCHID-7319` 并严格回复 `CHAT_TURN1_OK`；第二轮只询问之前的随机值，返回 `CHAT_TURN2_OK:ORCHID-7319`。两轮结果均正确。

两轮复用了同一个 provider session `01a0714d-e057-7b61-ac83-42fa95453173` 和同一个 workspace：

```text
default/default/conversations/10b37c4e-b5e2-4d7c-97ce-0bace5319b02/46f0599a-face-40d9-a981-c69490f53ecc
```

这证明当前 Hosted Codex 的 session resume 和对话上下文连续性有效。

并发保护也实际生效：第二轮仍在运行时发送重叠 turn，被拒绝；但 HTTP 状态码不正确，见 `HST-005`。

### 2. Qoder Hosted Chat：正常路径通过，恢复路径失败

干净重试 Chat：`d0236de7-9c50-4328-874a-3520c01f7fae`

- 输入：`Reply with exactly QODER_CLEAN_OK and do not use any tools.`
- 输出：`QODER_CLEAN_OK`
- provider duration：`6551 ms`
- Task：`456965ab-855b-4d0c-bb24-5323e7e9f624`
- Attempt：`8356b90d-5db1-49d4-b4d8-92d66d72d27c`
- Run：`dac85b54-ba8e-5fc3-8169-3cb5b125fcf4`
- 最终状态：Task、Attempt、Node、Run 均成功

另一条 Qoder Chat 遇到机器约 5 分钟暂停/时钟跳变，触发真实 lease-loss 恢复路径。它暴露了 `HST-003` 和 `HST-004`，不能用于判定 Qoder 正常推理耗时或可用性。

### 3. 自适应 Team：分配和执行通过，收敛失败

- 根 Issue：`2a290c68-b4c7-4d0f-9465-b09a77d39c46`
- 根 leader Task：`878b2491-9b22-4023-b54f-29096578923e`
- 子 Issue：`8115388c-9362-49a6-a3c1-b9205c823c12`
- 子 worker Task：`446efc18-7564-4ef4-b13c-82495c4b510d`
- Run：`564cb18c-c722-4a68-88f9-3ba651a15902`

已验证的正向能力：

1. Hosted lead 正确读取 Team 上下文。
2. lead 创建了一个指向 worker 的子 Issue/Task。
3. Hosted worker 正确认领并写入 `WORKER_RESULT:42`。
4. 完成结果被路由回 lead；后续 lead 能从持久化 Issue/Run graph 中确认结果，并写入 `LEAD_FINAL:42`。
5. Issue comment、Task result、Run event 都能持久化。

失败点：预期仅有一个 coordinator 节点加一个 worker 节点，实际膨胀为 7 个节点、8 个 Task。运行约 4 分钟仍处于 waiting，最终人工取消，以免继续回环。

### 4. 声明式 Workflow：通过

- Issue：`1566770b-1fa4-4302-a1aa-1d0742ec7932`
- Definition：`de5b580a-8808-422c-ae6e-7471c9a2ee05`
- Revision：`7bfb6712-a1b7-478b-b65d-e5905164ba66`
- Run：`56bab462-bd3f-4f5d-84c1-6fd95a848fc2`

DAG 为 `worker -> lead`，成功边触发：

- worker Task `f98f31fc-c614-4bc5-8a0b-165bf5748c69` 输出 `WORKFLOW_WORKER_RESULT:42`。
- lead Task `84068d57-32d2-4e60-8d96-485c6a9d59e2` 读取 Issue discussion 和 Run graph 后输出 `WORKFLOW_LEAD_RESULT:accepted-42`。
- 两个节点均成功，Run 于 `2026-09-05 19:31:28 +08:00` 成功。
- capacity 为 1 时，两个 Hosted Task 被同一个 Runtime Host 顺序认领，行为正确。

Issue 仍为 backlog 是 completion policy 的结果，不计为缺陷。

## 问题清单

### HST-001 / P1：Team follow-up leader 无法收敛原 coordinator

**实际结果**

初始 leader Task 完成后，原 coordinator node `899b989e-7b71-4a07-96dc-9989696472fb` 进入 waiting。worker 完成后，系统为 lead 创建了新的 follow-up Task `42114263-1de0-4f50-9e30-1a975e5c9220`，同时创建新的动态 node `a3fc3e7f-a83e-4747-83da-0b966945845e`。该 follow-up Task 的 `leaderTask=false`。

follow-up lead 已得到正确结果并尝试完成原 Task 的 coordinator，控制面返回：

```text
403 {"error":"resource is outside the AgentTask scope"}
```

因此 `LEAD_FINAL:42` 虽已持久化，原 coordinator 仍不能完成，Run 继续 waiting。

**预期结果**

worker 完成后的 leader follow-up 应恢复原 leader/coordinator 上下文，或被授权完成原 coordinator；不应为每次 follow-up 创建互不相干的新 coordinator node。

**相关实现边界**

- `internal/collaboration/service.go` 的 `completionTargets` 将 child completion 路由回 parent agent。
- `internal/store/postgres/collaboration_tasks.go` 为路由 Task 创建 `task:<task-id>` 新节点。
- `internal/orchestration/service.go` 的 `CompleteCoordinatorNode` 只接受当前 `LeaderTask`，无法让 follow-up Task 完成原 coordinator。

### HST-002 / P1：同一 Agent 的 self-trigger 防护可被 Team 上下文差异绕过

**实际结果**

worker 在子 Issue 上调用 `task.respond` 写入 `WORKER_RESULT:42` 后，结果被再次路由给该 Issue 的直接 assignee，而 assignee 正是同一个 worker，于是生成自触发 Task：

```text
源 Task: 446efc18-7564-4ef4-b13c-82495c4b510d
自触发 Task: 7d89a8a1-b196-4608-83af-39c1a7311e4f
Agent: d5f2530e-ff55-49dc-b47a-f9687bc4998c
```

源 Task 带 `TeamID/TeamRole`，直接 assignee target 不带 Team 上下文。`sameCollaborationRole` 因一侧 `TeamID=nil` 返回 false，即使 `AgentRef` 完全相同，也不会触发 `self_trigger` 阻断。

**预期结果**

同一 Agent 对同一 Issue/因果链的结果不应再次触发自己；Team 上下文可以参与细化角色，但不应取消最基本的 same-agent 防环保护。

**代码证据**

`internal/collaboration/service.go:780-807`。

### HST-003 / P1：lease-loss 后 queued Task 可永久搁置

**触发条件**

测试期间系统发生约 `5m14s` 的 thread starvation/clock leap。Qoder Attempt `0866ad5f-4e46-46ea-bbed-b784a6a1f358` 的 lease 因此过期。这个外部暂停不是产品缺陷，但随后恢复行为是本项测试目标。

**实际结果**

- Attempt 于 `19:37:37` 变为 `failed/heartbeat_timeout`。
- Task `464c5d59-60f4-4edd-99ee-72f9069ee3ac` 被重排为 `queued`。
- Run `2bb38622-9d5a-5758-9d7b-905e88d90a05` 仍为 `waiting/node_wait`。
- Chat session `b827ab02-4d73-4a18-9fa8-d42f175073f0` 仍为 `active`，只有 `turn.started`，没有 `turn.failed` 或 `turn.completed`。
- 对应 `agent-task.queued.v1` outbox event 重试 12 次后进入 dead letter，最后错误为 `policy runtime candidate 0 is unavailable`。
- 旧 provider 占用 capacity 期间候选不可用；旧 provider 退出后没有周期性扫描或新的事件再次调度该 queued Task。

最终形成永久不一致状态：`Task=queued`、`currentAttempt=failed`、`Run=waiting`、`Session=active`，且没有后续 Attempt。

**预期结果**

租约失败后的重排应保证最终会创建新 Attempt，或在重试预算耗尽后将 Task/Run/Chat 明确终止；不能仅依赖一个可能死信的瞬时 outbox event。

### HST-004 / P1：Renew 观察到 terminal Attempt 后没有取消 provider

**实际结果**

控制面已将旧 Attempt 标记为 `failed` 后，Runtime Host 的 renew 请求返回了该 terminal 状态。`renewLoop` 直接返回，但没有调用 `cancel()`。Qoder provider 随后继续运行至 `19:43:20`，晚到事件全部因 execution attempt token 已失效而返回 `401 invalid execution attempt token`。

这延长了 capacity 占用时间，也直接促成 `HST-003` 的候选不可用和 outbox 死信。

**预期结果**

renew 响应一旦表明 Attempt 已 terminal，Runtime Host 应立即取消 provider 进程并释放 capacity。

**代码证据**

`internal/runtimehost/engine.go:542-548`：terminal 分支直接 `return`，只有 `cancel_requested` 分支调用 `cancel()`。

### HST-005 / P2：并发 Chat turn 的领域冲突被映射成 HTTP 503

**实际结果**

同一 Chat 已有 turn 运行时提交第二个 turn：

```text
HTTP 503
{"error":"conversation already has a turn in progress: store: conflict"}
```

并发保护本身正确，但这是可预期的客户端状态冲突，不是服务不可用。

**预期结果**

返回 HTTP `409 Conflict`，并提供稳定的机器可识别错误码。

**代码证据**

- `internal/httpapi/hosted_conversation.go:167-170` 正确包装了 `store.ErrConflict`。
- `internal/httpapi/chat_handler.go:196-198` 将所有发送错误统一映射为 503。

### HST-006 / P2：`task.respond` + `task.complete` 产生重复 result，并放大路由

**实际结果**

provider prompt/MCP 同时暴露 `task.respond` 和 `task.complete`。Agent 很自然地先 respond，再 complete；两者各创建一个 result Comment。

- 声明式 Workflow：2 个 Task 产生 4 条 result Comment。
- Team 根 Issue：同一 worker Task 产生两条 `WORKER_RESULT:42`。
- Team 子 Issue：回环后累计 6 条相同或等价 result Comment。

在 Workflow 中只是重复记录；在 Team 中每条 result 都可能触发 routing，显著放大任务回环。

**预期结果**

协议应明确二选一，或让 complete 复用已有 respond Comment、或通过 source task/idempotency 合并等价 result。不能依赖模型自行避免两次调用。

### HST-007 / P2：Run terminal 后仍追加 provider event，terminal 对象保留 waitReason

**实际结果**

声明式 Workflow Run 的事件顺序为：

```text
57 attempt.succeeded
58 node.succeeded
59 run.succeeded
60 attempt.provider_event
61 attempt.provider_event
62 attempt.provider_event
```

原因是 Agent 通过 MCP 先完成了 Task/Run，而 provider 进程仍在刷新最终 JSONL。与此同时，成功 Run 仍保留 `waitReason=node_wait`，成功 node 仍保留 `waitReason=agent_task`。

**风险**

只把 `run.succeeded` 当作事件流尾部的消费者会漏掉后续 provider final/usage；终态对象上的 wait reason 也会误导 UI 和排障。

**预期结果**

需要明确并实现一致契约：要么 terminal lifecycle event 真正封口 provider event，要么提供独立 telemetry 水位/结束事件；对象进入 terminal 时应清理当前 wait reason。

### HST-008 / P2（需确认契约）：Qoder Runtime Profile 的工具限制没有缩小暴露面

Qoder profile 配置为：

```json
{
  "allowedTools": ["mcp__agentscope-collaboration__*"],
  "permissionMode": "default",
  "strictMCPConfig": true
}
```

但干净 turn 的 provider init event 仍暴露 `Bash`、`Write`、`WebSearch`、`Agent` 以及大量本地 plugin MCP tools。简单的 exact-reply 请求报告 `30627` input tokens。

如果 `allowedTools` 的契约是“可见/可调用工具白名单”，这是权限隔离缺陷；如果它仅表示“无需审批的工具”，字段命名、capability 描述和文档需要澄清。无论哪种语义，当前巨大工具面都会显著增加 prompt 成本和非确定性。

### HST-009 / P3：provider 环境告警污染能力和事件流

- Runtime Host 把 Qoder 探测 stderr 与版本拼接，`capabilities.providers.qoder` 变成：

  ```text
  Skipped invalid MCP server "ali-skill-market" ... Unrecognized key(s) ... 'authType'
  1.0.37
  ```

  版本字段不再是稳定、可比较的版本字符串。

- 每个 Codex turn 都出现 provider `error` event：

  ```text
  clamping SessionEnd hook timeout to 3s in /etc/codex/hooks.json
  ```

  Turn 最终成功，但 UI/监控会看到错误事件，容易形成假告警。

**预期结果**

版本探测应只解析版本；环境诊断应放在独立字段。非致命 provider warning 应保留 raw telemetry，但不应伪装成执行错误。

## 非缺陷但值得记录的行为

- leader 在 progress Comment 中显式 mention worker 会额外触发一个 worker Task。此次系统提示要求“mention delegation”，模型确实产生了 structured mention，因此除了子 Issue Task，又生成根 Issue worker Task `ec4165c4-1ae1-4ad0-b785-d6d0220fba81`。这符合当前 mention 即路由的语义，但系统提示/SDK 应避免把“文本引用”误导成“再次派工”。
- 声明式 Workflow 完成后 Issue 未自动 done，符合当前 Issue completion policy，不计为缺陷。
- Team 测试中的 backlog Issue 未自动关闭，符合 review policy，不计为缺陷。

## 建议修复顺序

1. `HST-001`：建立 follow-up leader 与原 coordinator 的身份/授权关联，先保证 Team 可收敛。
2. `HST-002`：按 same-agent + causation/issue 做基础防环，避免任务风暴。
3. `HST-003` + `HST-004`：修复租约丢失后的 provider 取消、可靠重新调度和最终失败收敛。
4. `HST-006`：统一 respond/complete 的结果与路由语义。
5. `HST-005`、`HST-007`：修正 HTTP 和生命周期契约。
6. `HST-008`、`HST-009`：收紧 Qoder 工具面并清理 provider 观测噪声。

## 复验基线

修复后至少应满足：

- Team 场景只创建预期节点/Task，worker 一次完成后 leader 能完成原 coordinator，Run 自动成功。
- same-agent result 不再创建新的 Task。
- 人为暂停 Runtime Host 超过 lease TTL 后，旧 provider 被取消；queued Task 能重试并最终成功，或在明确预算后完整失败，Chat 必须出现 terminal turn event。
- `task.respond` 与 `task.complete` 不再产生两次等价 result routing。
- 并发 Chat turn 返回 409。
- `run.succeeded` 后事件流行为与公开契约一致，terminal 对象不保留活动 wait reason。

## 修复与复验结果（2026-09-05 21:35 +08:00）

本节记录后续修复工作；前文“仅测试不改代码”只描述首次问题发现阶段。`HST-001` 至 `HST-009` 均已修复，并在修复后的真实故障注入中新增、修复了 `HST-010`。

| 编号 | 状态 | 修复与验证摘要 |
| --- | --- | --- |
| HST-001 | 已修复 | follow-up leader 继承原 coordinator 身份，可接受 worker child Issue 并完成原 coordinator node。 |
| HST-002 | 已修复 | same-agent 防环不再信任可伪造/不一致的 Team context；实测 leader result route 为 `suppressed/self_trigger`。 |
| HST-003 | 已修复 | 暂时不可调度的 queued Task 使用 deferred outbox，不进入普通失败死信阈值；硬崩溃后成功创建第二个 Attempt。 |
| HST-004 | 已修复 | renew 看到 terminal Attempt 时主动取消 provider；lease 过期的取消顺序也已消除竞态。 |
| HST-005 | 已修复 | 并发 Chat turn 返回 HTTP 409 与稳定错误码 `conversation_turn_conflict`。 |
| HST-006 | 已修复 | `task.complete` 复用同一 Task 已有的 `task.respond` result Comment，不再重复写结果或重复路由。 |
| HST-007 | 已修复 | terminal Attempt 拒绝晚到 provider event；声明式 Workflow 以 `attempt.succeeded -> node.succeeded -> run.succeeded` 封口，终态 wait reason 清空。 |
| HST-008 | 已修复 | Qoder 使用隔离且稳定的 config dir，显式限制 built-in tools/MCP server，并阻止 custom args 绕过隔离参数。 |
| HST-009 | 已修复 | Qoder 版本探测只解析 stdout；已知 Codex SessionEnd hook 限时提示不再发布为 provider error。 |
| HST-010 | 已修复 | lease sweep 后“同一 Task 重新派发”曾丢失 conversation 身份，导致 Task 成功但 Chat 永久 running；现已继承 `SessionID/TurnID/WorkspaceKey`，并清除旧失败字段。 |

### 实机 Chat 复验

- Qoder Chat：`ca04246e-9fd2-466f-8515-63adbfb9f26f`，Session `2d98c15b-de75-4bb9-a1c7-a97ca1dcc9e3`。
- 首轮输出为 `QODER_CHAT_TURN_1_OK`；运行中并发提交返回 `409`，body 包含 `code=conversation_turn_conflict`。
- 第二轮输出为 `PREVIOUS=QODER_CHAT_TURN_1_OK`，两个 turn 的 provider session 均为 `225171fd-853e-41d1-8d29-deb0f611966c`，证明 resume 生效。
- 两轮 Qoder init event 均为 `plugins=[]`；input tokens 分别为 `4599`、`4955`，相较修复前的 `30627` 明显下降；版本字段稳定为 `1.0.37`。

### 实机 Team lead/worker 复验

- Team：`564b3266-546a-4acd-be6a-1f738201390c`。
- Root Issue：`0cab10c2-8ba9-4644-aa0c-542599c1ef45`；Run：`c1878e84-cae7-44d7-84d6-e9b8a866e4ce`。
- 拓扑严格为 2 个 node、3 个 Task：初始 lead、researcher worker、lead follow-up；没有额外 self-trigger Task。
- worker 写入唯一结果 `WORKER_RESULT:42`，follow-up leader 成功 `issue.accept` child Issue `5eba9cdb-d169-423f-9beb-a47dafc558a6`，写入唯一结果 `LEAD_FINAL:42`，并完成原 coordinator node。
- 两个 node 与 Run 均为 `succeeded`，终态 `waitReason` 均为空；每个 Task 恰有一个 result Comment；Run 的 74 个事件中没有 Codex hook 假错误。

### 实机声明式 Workflow 复验

- Definition：`d4a83da5-741f-4d8f-b327-158337bd8d44`，Revision：`251b6e4c-fa97-4f36-87c9-ed31b33ce754`。
- Issue：`aafac30c-2729-47d2-a45c-d953ab20ab0b`；Run：`0a34b050-7ac3-4858-bade-c8a0c72a71c0`。
- hosted worker node 与 hosted lead node 顺序成功，结果分别为 `WORKFLOW_WORKER:42`、`WORKFLOW_LEAD:accepted:42`，每个 Task 恰有一个 result Comment。
- 事件 47/48/49 依次为 `attempt.succeeded`、`node.succeeded`、`run.succeeded`；49 后没有任何事件，尤其没有 provider event；Run/node 的终态 wait reason 均为空。

### 实机 lease/outbox 故障注入复验

第一次硬崩溃验证确认 deferred outbox 在 Runtime Host 离线期间重试 14 次仍未 dead-letter，第二个 Attempt 最终成功；同时发现 `HST-010`：第二个 Attempt 缺少 Session/Turn 元数据，Chat 没有 terminal event，Task 还残留 `heartbeat_timeout`。

修复 `HST-010` 后再次执行完整硬崩溃：

- Chat：`e4a717b3-7a19-412d-965f-a5d5757cee27`；Session：`d971bc47-4a42-42f9-8937-3100821d6477`；Task：`9f977331-6759-43ad-918d-bd719800dc31`。
- provider 进入 90 秒 shell 阻塞后，对 Runtime Host PID 执行硬终止；旧 Attempt `31ab9d61-6da0-4f05-b6d3-695e14750a01` 在 lease sweep 后变为 failed，Task 回到 queued。
- Host 离线时 outbox 连续 deferred 且 `dead_lettered_at IS NULL`；Host 恢复后 outbox delivered。
- 新 Attempt `5cd26cfb-975d-44a7-a515-e066eb7ff3b8` 保留与旧 Attempt 完全一致的 SessionID、TurnID 和 WorkspaceKey，随后成功。
- Chat 最终只有一个 `assistant.message=CONVERSATION_RETRY_OK`、一个 `turn.completed`、零个 `turn.failed`；两个 Attempt 的 provider events 都可见；Task 成功后 `errorCode/errorMessage` 均为空。

### 自动化验证

- `go test ./...`：通过。
- 覆盖关键回归测试：`TestWorkerResultWakesOriginalCoordinatorAndLeaderCanAccept`、`TestLeaderFollowUpConvergesOriginalCoordinatorAfterDelegation`、`TestTaskRespondThenCompleteReusesSingleResultComment`、`TestSelfTriggerGuardIgnoresMismatchedTeamContext`、`TestDeferredQueuedTaskNeverDeadLettersAndEventuallyDispatches`、`TestRenewLoopCancelsProviderWhenControlPlaneReportsTerminal`、`TestChatMapsOverlappingHostedTurnToConflict`、`TestHostedPlaygroundConversationPersistsEventsAndResumesProviderSession`、`TestQueuedConversationRedispatchPreservesSessionAndTurn`、`TestBuildArgsIsolatesHostedMCPFromAmbientQoderSettings`。

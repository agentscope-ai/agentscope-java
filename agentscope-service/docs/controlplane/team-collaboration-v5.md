# Agent Team 的 Issue-first 协作契约（v5 收敛设计）

> 状态：目标架构与测试基线；用于收敛当前实现，不描述所有代码都已完成。
>
> 参考：`/Users/ken/agentscope-2/multica` 的 Squad 实现、
> [Issue 协作终态契约](./collaboration-contract.md) 和
> [统一执行与编排契约](./orchestration-v4.md)。

## 1. 决策摘要

AgentScope Service 的 Team 应被定义为：

> 固定 leader 与有角色的成员集合。Issue 是长期目标和验收事实，Comment 是可靠消息，
> AgentTask 是定向的语义工作义务，ExecutionAttempt 是一次可重试的物理执行，Session
> 只提供对话/运行连续性，OrchestrationRun 负责可观察的执行拓扑与收敛。

Team 不是一组同时启动的 Agent，也不是一个公共任务池。把 Issue 分配给 Team 时只启动
leader。leader 每次被唤醒只处理一个协调回合：读取新增事实、作出一次可观察的评估，
然后委派、等待、升级或收敛。worker 只执行定向工作，不争抢业务任务，也不替 leader
决定整个 Team 是否完成。

`adaptive Team` 和 `declared Workflow` 是两层能力：

- Team 是动态决策器。leader 根据 Issue 当前事实选择下一步，拓扑可在运行时增长。
- Workflow 是确定性外壳。DAG 决定节点何时可运行；其中一个 team node 内部仍按上述
  Issue-first 协议协作。
- 不应用 Workflow 的 node 状态代替 Issue 的验收状态，也不应用 Team 的动态协作模拟
  一个静态 DAG。

## 2. 从 Multica Squad 保留什么、增强什么

### 2.1 保留的核心

Multica 的 Squad 已验证了几项重要设计：

1. Squad/Team 有固定 leader；分配给 Team 的 Issue 只唤醒 leader。
2. roster、role、skills 和 team instructions 是 leader 的路由上下文，不是调度器自动
   技能匹配规则。
3. Issue/Comment 是跨进程、跨时间的事实与消息；Session chat 不能承担可靠协作。
4. leader 委派后结束本轮。worker 结果、child Issue 状态变化或新的人类评论再唤醒
   leader，而不是让一个模型调用无限等待。
5. 显式 mention 优先于隐式回流；leader 自己的评论不能再次唤醒自己。
6. leader 每回合记录 evaluation，包括 `no_action`，使“看过但没有动作”和“从未处理”
   可区分。
7. 新触发到达时，queued 工作合并；已经 running 的工作必须形成后继义务，不能丢输入。

### 2.2 AgentScope Service 应增强的部分

AgentScope Service 比 Multica 多了三种 runtime backend、ExecutionAttempt、统一
Orchestration 和 typed TeamPolicy，因此应把 prompt 约定提升为服务端不变量：

- leader 只能委派给冻结的 Team snapshot 中允许的成员，并受 fanout/depth/budget 限制；
- 每条 Agent route 必须有 `queued/coalesced/deferred/blocked` 的持久结果；
- worker result 必须恰好形成一个 leader 后继输入；
- coordinator 只能在所有 worker node、child Issue 和 required approval 收敛后完成；
- retry 只能创建新 Attempt，不能悄悄改变逻辑 Task 或 runtime target；
- late event/report 必须被 attempt/generation/turn fence 拒绝；
- runtime backend 的差异不得渗透进 Team 业务状态机。

## 3. 领域边界与唯一写入者

当前频繁出现局部修复的根因，是同一个事实可被多条链路推进。v5 要求每类状态只有一个
语义写入者。

| 对象 | 表达什么 | 允许推进它的主体 | 不能做什么 |
| --- | --- | --- | --- |
| Issue | 目标、讨论、child、验收 | human 或受 task scope 授权的 Agent 命令 | Run 成功不能自动等同 Issue done |
| Comment/Route | 持久消息和路由结果 | collaboration transaction | 不能只发瞬时 wake 而不落事实 |
| AgentTask | 一次定向语义义务 | task command/application service | Session idle 不能把它标成 completed |
| ExecutionAttempt | 一次物理尝试 | runtime report + fenced control-plane reconciler | provider 消息不能直接重写 Issue |
| Session/Turn | 对话和运行连续性、诊断 | runtime session event projector | 不能替 Agent 判断业务完成 |
| RunNode | 编排步骤的收敛 | orchestration engine；team coordinator 通过显式命令请求 | 单个 leader 文本回复不能绕过 barrier |
| Run | 一次可观察执行实例 | orchestration engine | 不能成为 Issue 的第二份业务事实 |

### 3.1 必须分开的三个“完成”

```text
provider turn 结束
    ≠ ExecutionAttempt 成功
    ≠ AgentTask 语义完成
    ≠ Team coordinator 收敛
    ≠ Issue 被验收
```

正常情况下它们按顺序发生，但任何一层都不能根据相邻层的遥测自行猜测。特别是：

- `session.status_idle` 只表示 turn 没有继续执行；它不能自动完成 leader Task。
- `task.complete` 完成本回合的语义义务；初始 leader 完成后 team node 进入 waiting。
- `run.node.complete` 是 leader 对 coordinator barrier 的显式收敛请求，服务端仍需校验。
- `issue.accept` 是对 child/Issue 的验收；是否要求 human review 由 TeamPolicy 决定。

## 4. 标准 Team 协作循环

### 4.1 初始回合

1. human/endpoint 把 Issue 分配给 Team。
2. 服务端冻结 Team snapshot，创建 adaptive Run、team coordinator node 和一个
   `team_coordination` leader Task。
3. leader 读取 Issue、未处理 inputs、roster、roles、skills、policy 和 Run 图。
4. leader 必须产生一条 evaluation：
   - `delegate`：创建一个或多个 child work；
   - `complete`：无需委派且目标已完成；
   - `blocked/escalate`：创建 human attention/approval；
   - `no_action`：输入已知或暂时无需动作。
5. 若委派，服务端创建定向 worker Task；leader 调用 `task.complete` 结束当前回合，
   coordinator node 保持 waiting。leader 不在同一 turn 中等待 worker。

### 4.2 Worker 回合

1. worker 只收到自己的 assignment、所属 Issue/child Issue、触发输入和允许的工具。
2. worker 可写 progress/result/artifact，但不能创建 Team child、accept Issue、replan 或
   完成 coordinator。
3. worker 通过 `task.respond` 发布结果，再通过 `task.complete` 完成本回合。
4. completion transaction 同时完成 Task/Attempt、处理 inputs、写 result Comment，并把
   该 Comment 路由为 leader 的一个后继协调输入。

### 4.3 Leader follow-up

1. 同一 coordinator 上的新 leader Task 读取 worker 结果和 child 状态。
2. leader 可验收 child、要求返工、委派下一阶段或升级给 human。
3. 仍有未完成工作时，leader 完成本回合，coordinator 继续 waiting。
4. 全部 barrier 满足时，leader 请求 coordinator complete；服务端校验：
   - 没有活跃 worker Task；
   - 没有未终态 worker/run node；
   - 没有未验收 child Issue；
   - required approval/review 已满足；
   - 没有未覆盖 input。
5. coordinator 和 leader Task 都成功后 Run 才能成功。Run 成功把根 Issue推进到
   `in_review` 或保留 `in_progress`；最终 `done` 仍服从 Issue acceptance policy。

### 4.4 新输入与去重

以 `(issue, target agent, team role, coordinator)` 作为串行化键：

- 已有 queued Task：把 input 合并到它，route=`coalesced`；
- 已有 dispatched/running/waiting Task：创建或合并到唯一 successor，route=`deferred`；
- 没有活跃 Task：创建新 Task，route=`queued`；
- 违反权限、policy 或预算：不创建 Task，route=`blocked` 并保存 reason；
- leader 自己的结果回给自己：route=`suppressed/self_trigger`；
- 同一个 Comment/version 重放：返回同一个 route/input，不产生重复工作。

“running 时创建 successor”不是普通 parent/child 委派。实现上应使用独立的
`continuationOfTaskId`，不要继续复用 `parentTaskId`。

### 4.5 Worker 失败、重试与 lead 决策

Team worker 的“失败”必须区分物理执行事实与业务义务状态：

| 场景 | Task / Attempt / Node | child Issue | Team Run / coordinator |
| --- | --- | --- | --- |
| transient runtime/provider failure，重试预算未耗尽 | 本次 Attempt failed，新建 Attempt/Task | 保持 `in_progress` | 继续等待，不唤醒 lead |
| 缺少凭证、权限或能力等不可通过原样重试恢复的失败 | failed | `blocked` | 保持活动，向 lead 投递 failure outcome |
| transient failure 耗尽 `maxTaskRetries` | failed | `blocked` | 保持活动，向 lead 投递 failure outcome |
| lead 明确接受降级/部分结果 | failed 保留为诊断事实 | lead 显式改为 `cancelled` | coordinator 可成功收敛，Run 为 `partial_succeeded` |
| lead 判断主目标不可恢复 | failed | 保持 `blocked` | lead 调用 `run.node.fail`，Run failed，根 Issue `blocked` |

这里的 `cancelled` 只表示 lead/human 明确放弃该业务义务，绝不能由 worker failure 自动推导。
`blocked` 是可恢复状态，不是完成状态；修复配置后由 lead replan/reassign 时恢复为
`in_progress`。`API_KEY_MISSING`、credential/configuration missing、permission denied、
unsupported capability 和 invalid input 默认不可原样自动重试，以免浪费 retry budget。

failure outcome 必须至少包含 `code`、`message`、失败的 Task/Attempt、已消耗尝试次数和
可选的恢复建议，并作为普通持久 Comment/Input 唤醒 lead。lead 收到后必须选择且记录一种
动作：

1. retry/reassign/replan；
2. 请求 human 补充凭证、权限、输入或审批；
3. 明确跳过 child，接受 degraded/partial result；
4. 判定目标不可恢复并失败 coordinator。

任何 worker failure 都不能在 lead 决策前直接取消 coordinator。一个 worker 失败时，其余
独立 worker 默认继续；所有 worker 失败时，对相同根因（例如同一个缺失 credential）应
聚合提示，lead 只作一次决策。最终 coordinator 成功或失败时，结果/失败说明必须写回根
Issue；成功进入 `in_review`（自动策略可直接 `done`），部分成功进入 `in_review`，不可恢复
失败进入 `blocked`。根 Issue 不能静默停留在 `in_progress`。

## 5. Task 类型与 lineage

当前 `LeaderTask + TeamRole + ParentTaskID` 可以推断大部分场景，但语义重载会使 prompt、
路由和完成逻辑依赖脆弱的 if/else。目标模型应显式增加：

```text
taskKind:
  direct_work
  team_coordination
  team_work
  team_review

delegatedFromTaskId   # 谁创建了这份业务工作
continuationOfTaskId  # 哪个繁忙/已结束回合的后继输入
rootTaskId            # 相关回合的稳定根
coordinatorNodeId     # 所属 Team barrier
```

`retryOfTaskId` 仅表示语义级重跑；基础设施 retry 不创建 AgentTask，而是在同一 Task 下创建
新的 ExecutionAttempt。`ParentTaskID` 在兼容期只读，新的路由逻辑不得再同时拿它表示
delegation 和 continuation。

## 6. Coordinator 命令应收敛为一个事务

旧协议要求 leader 依次调用 `issue.accept`、`run.node.complete`、`task.complete`。这些命令
之间可能发生超时、token 失效或进程退出，产生“node 已完成但 task 未完成”一类半状态。
兼容工具 `run.node.complete`/`run.node.fail` 现在先终结当前 leader Task/Attempt，再推进
node；即使第二步异常，也只留下可用同一 fenced token 重试的 waiting node，不再暴露
Run false-success。最终仍应把以下动作下沉为单个数据库事务。

目标工具应为：

```json
{
  "name": "team.conclude",
  "arguments": {
    "expectedTaskVersion": 7,
    "acceptIssueIds": ["..."],
    "evaluation": {"outcome": "complete", "reason": "..."},
    "summary": "...",
    "output": {"...": "..."},
    "processedInputIds": ["..."]
  }
}
```

同一数据库事务完成 child acceptance、evaluation、leader Task/Attempt、coordinator node、
RunEvent 和 Run reconciliation。`team.delegate` 也应批量接收 assignments，在一个事务中
创建所有 child/worker work、记录 evaluation、完成初始 leader Task 并把 node 置 waiting。
在这两个原子命令上线前，现有细粒度工具保留，但必须通过状态校验和幂等键支持恢复。

## 7. 三种 Agent backend 的统一契约

Managed、Hosted 和 External 只在 ExecutionAttempt 以下不同。

| 能力 | Managed | Hosted | External Application |
| --- | --- | --- | --- |
| 执行位置 | Service data plane | Runtime Host 管理的进程/容器 | 用户应用实例 |
| 唤醒 | stable Session + new Turn | Host claim Attempt | ASDP dispatch command |
| 连续性 | Session state | provider session/checkpoint | 应用自管，可上报 session |
| 存活证明 | fenced turn heartbeat + fenced session events | host lease renew | fenced ASDP heartbeat/report |
| 协作身份 | attempt-scoped task token | attempt-scoped task token | attempt-scoped task token |
| 取消 | interrupt Turn | host cancel + provider stop | ASDP cancel/ack |

所有 backend 必须满足：

1. 调度前解析并冻结同一个 Agent 的 RuntimeBinding candidate。
2. task token 只能用于语义工具；attempt token/lease 只能用于物理报告。
3. token 携带 attempt/generation，旧 Attempt 永远不能修改当前 Task。
4. 有效物理活动持续续租；超时只使 Attempt failed 并按 policy 重试同一 Task。
5. Session 状态只做诊断，不直接产生业务完成。
6. backend fallback 必须由 policy 显式允许，并创建 fresh Attempt。

### 7.1 本次真实失败说明

Session `eeaa95f7-bb87-4a26-9971-692fd81d3940` 的 leader Attempt
`b160925f-d20b-4638-a62c-118238ef691c` 在 11:24:24 开始，`heartbeat_at` 一直停在
11:24:24，11:25:12 被 sweeper 以 `heartbeat_timeout` 终止。模型到 11:25:25 才调用
`issue.child.create`，因此 task token 被正确 fencing，但用户看到的是协作失败。

这不是 environment 或 Team membership 问题，而是 Managed 物理 turn 的存活协议没有在
部署中兑现。修复要求两条独立但同 fence 的存活通道：data plane 每 10 秒显式 heartbeat；
每个带 attempt/generation/turn 的有效 session event 也续租。任何无 fence 或旧 fence 的
事件只能保存为诊断信息，不能推进当前 Task/Attempt。

## 8. Chat 与 Issue execution 必须分流

Chat 的用户承诺是“每个已接受的 user turn 最终得到 assistant message 或明确 error”。它
不应为了复用 Team 代码而创建 Issue/Run，也不能把 provider.assistant 原始事件直接当成
最终消息。标准链路是：

```text
Conversation -> Turn -> provider events -> normalized assistant message -> Turn terminal
```

Team/Workflow 的用户承诺是“每个持久 trigger 都能追踪到 route、Task、Attempt、结果和
Run 收敛”。标准链路是：

```text
Issue/Comment -> Route -> AgentTask -> ExecutionAttempt -> result Comment -> reconciliation
```

二者可以共享 Agent runtime 和 Session 事件规范，但不能共享终态判定。

## 9. 当前实现审计

### 9.1 已具备的正确基础

- Issue/Comment/Route/Task/Input/Attempt/Run 已持久化；主要写入具备 transaction/CAS。
- Team assignment 先唤醒 leader；worker result 可回流到原 coordinator。
- RunTeamSnapshot 冻结 roster；TeamPolicy 已覆盖 fanout/depth/budget/review 等边界。
- queued input coalescing、running successor、outbox 和 dead-letter 框架已存在。
- Managed/Hosted/External 都通过 RuntimeBindingResolver 和 ExecutionAttempt。
- coordinator complete 已检查 active tasks/nodes/child Issues。
- task token 已按 attempt/generation fencing。

### 9.2 需要继续收敛的结构性问题

| 优先级 | 问题 | 目标修改 |
| --- | --- | --- |
| P0 | Session/Attempt/Task 生命周期曾互相代写 | 保持 idle 无语义 completion；所有 managed event 强制 fence；双通道续租 |
| P0 | coordinator 收敛曾依赖多个非原子工具调用 | `run.node.complete` 现先完成 leader Task 再完成 node，避免 false-success；继续收敛为单 store transaction |
| P0 | 缺少 leader evaluation/no_action 事实 | 新增 TeamEvaluation，所有 leader Task terminal 前必须有 evaluation |
| P1 | `ParentTaskID` 同时表示委派和 continuation | 增加 taskKind/root/continuation/coordinator 字段并迁移路由 |
| P1 | follow-up 的去重键没有显式 coordinator 维度 | 唯一 successor + input coalescing，route outcome 明确 deferred/coalesced |
| P1 | prompt 在 Managed 与 External adapter 中曾不一致 | 由控制面生成一个结构化 protocol block，各 runtime 只负责渲染 |
| P1 | Team node barrier 尚未覆盖 approval、未处理 input 等全部策略 | 将 barrier 集中到一个可单测的 readiness evaluator |
| P1 | Chat 最终消息与 provider event 投影曾分叉 | Turn terminal 必须引用 normalized final message，补 projection contract tests |
| P2 | Console 需要同时解释 Issue/Run/Task/Attempt/Session | 增加统一 execution timeline 和明确的“业务状态/运行状态”分栏 |

## 10. 测试模型

### 10.1 分层测试

1. **领域/Store contract**：memory 与 PostgreSQL 跑同一套状态机和幂等测试。
2. **Application contract**：通过 collaboration/orchestration service 验证跨对象事务。
3. **Runtime adapter contract**：同一 Task fixture 分别喂给 Managed、Hosted、External，
   只替换 dispatch/report adapter。
4. **真实 E2E**：使用真实模型和真实进程，验证 Chat、Team、Workflow 以及故障恢复。
5. **Console projection**：用户看到的最终消息和状态必须与 API/DB 权威状态一致。

### 10.2 核心场景矩阵

每个 backend 至少覆盖 direct worker、Team leader、Team worker、leader follow-up 四个角色：

| 场景 | Managed | Hosted | External | Mixed Team |
| --- | --- | --- | --- | --- |
| 单轮/多轮 Chat 最终响应 | 必测 | 必测 | 按 endpoint 能力 | 不适用 |
| 直接 Issue 成功/失败 | 必测 | 必测 | 必测 | 不适用 |
| leader 无需委派直接完成 | 必测 | 必测 | 必测 | 必测 |
| leader 单 worker 委派与验收 | 必测 | 必测 | 必测 | 必测 |
| leader 并行 fanout + 汇合 | 必测 | 必测 | 必测 | 必测 |
| worker 返工一轮 | 必测 | 必测 | 必测 | 必测 |
| human comment 在 leader running 时到达 | 必测 | contract | contract | 必测 |
| heartbeat timeout + same Task retry | 必测 | 必测 | 必测 | 必测 |
| 旧 Attempt late event/report | 必测 | 必测 | 必测 | 必测 |
| cancel/interrupt | 必测 | 必测 | 必测 | 必测 |
| declared workflow 中的 team node | 必测 | 必测 | 必测 | 必测 |

Mixed Team 至少验证 Managed leader + Hosted worker、Hosted leader + External worker、External
leader + Managed worker。这样可以证明协作语义确实位于 Attempt 之上，而不是某种 runtime
的特殊能力。

### 10.3 必须永久成立的不变量

- 每个 committed Agent route 都有 Task/input 或 blocked/suppressed reason。
- 每个 Task 最多一个 current non-terminal Attempt。
- retry Attempt 不改变 Task identity、Issue、RunNode 和 Team role。
- `session.status_idle` 永不产生 `AgentTaskCompleted`。
- 无 attempt fence 的 task Session event 永不修改当前 Attempt/Task。
- 每个 terminal Task 至多一个 result Comment；重试/重放不重复。
- 每个 worker result 对同一 coordinator 至多形成一个 leader successor input。
- coordinator barrier 未满足时，任何 backend 的 leader 都不能完成 node。
- leader 每个 terminal coordination Task 恰好有一个 evaluation，包括 `no_action`。
- Run 成功不绕过 Issue acceptance；Issue done 不依赖瞬时 Session 状态。
- Chat accepted Turn 恰好有一个 normalized final assistant message 或一个明确 terminal error。

## 11. 实施顺序

### Phase A：先稳定共同底座

- 完成 Managed 双通道续租、强制 event fence、移除 idle auto-completion。
- 对 Hosted/External 运行同一 lease/late-report contract suite。
- 统一 Managed/Hosted/External 的 leader/worker protocol 生成逻辑。
- 为现有真实失败补 E2E 回归，禁止只用 mock 证明 heartbeat。

### Phase B：收敛 Team application commands

- 增加 TeamEvaluation 与 outcome schema。
- 增加原子 `team.delegate` 和 `team.conclude`。
- 抽出 coordinator readiness evaluator，并让 REST/MCP/engine 共用。
- 为每个触发暴露 route outcome 和 successor identity。

### Phase C：清理 lineage 与兼容字段

- 增加 taskKind、continuationOfTaskId、rootTaskId、coordinatorNodeId。
- 双写并回填旧数据；读路径优先新字段；完成后停止用 ParentTaskID 推断任务种类。
- 明确基础设施 retry、语义返工和人工 rerun 的不同 lineage。

### Phase D：产品级验证

- 运行完整 backend × role × trigger × failure 矩阵。
- Console 增加统一 timeline、route/evaluation、barrier 未满足原因。
- 把真实 E2E 纳入 release gate；Team/Workflow 不能只依赖单元测试与 prompt 测试发布。

这四个阶段的原则是先修“所有 Team 都依赖的共同状态机”，再优化某一种 Agent 的适配。
后续遇到新问题时，先判断它违反了哪条唯一写入者或永久不变量，再决定修复层级。

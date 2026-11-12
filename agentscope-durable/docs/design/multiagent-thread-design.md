# Durable Thread 与 Multi-Agent 演进建议

[English](multiagent-thread-design.en.md)

日期：2026-11-10。状态：**设计参考，非当前能力承诺**。

本文结合 AgentScope 当前 Session / Thread / Turn / Run / Step 执行模型、现有
Durable 子任务委派实现，以及 Anthropic Managed Agents 的 Session Threads 与
Multi-Agent Orchestration 语义，记录 Thread 面向后续 Multi-Agent 能力的定位和改进计划。

外部参考：

- [Anthropic Multi-agent orchestration](https://platform.claude.com/docs/en/managed-agents/multiagent-orchestration)
- [Anthropic Session threads](https://platform.claude.com/docs/en/managed-agents/session-threads)

本文不引入对 Anthropic API 的兼容要求，也不改变
[统一执行模型](execution-model-plan.md)已经确定的职责边界。Durable 仍为通用框架，
Core 和 Harness 不反向依赖 Durable，Managed Agents 等平台能力在上层装配。

## 1. 结论

当前 Durable Thread 与 Anthropic Session Thread 的核心边界基本一致：

- Session 是一次持续协作的总范围，保存身份、Thread 目录、公共事件和生命周期。
- Thread 是一条持续且隔离的 Agent 上下文，保存 Agent 绑定、原生状态、历史、
  checkpoint、任务队列和执行所有权。
- 主 Agent 使用主 Thread；需要持久上下文的子 Agent 使用 Child Thread。
- 发给某个 Agent 的一次任务或 follow-up 是该 Thread 上的一个 Turn。
- Suspend、恢复或接管产生新的 Run，不产生新的 Agent Thread；Step 仍是 Run 内部执行明细。

因此无需为 Multi-Agent 重新定义 Thread，也不应恢复已经删除的 AgentSession 调度层。
当前模型应继续保持：

```text
Session：一次业务会话或多 Agent 协作范围
├─ main Thread：Coordinator / Primary Agent 的持续上下文
│  └─ Turn → Run → Step
└─ child Thread：一个持久 Subagent 的持续上下文
   ├─ Turn 1：首次委派
   └─ Turn 2：follow-up
```

当前 Durable 已具备 Multi-Agent 所需的持久执行基础，但尚未形成完整的 Multi-Agent
用户能力。主要缺口是 Harness 子 Agent API 与 Durable Child Thread 的统一、Thread
发现和关系视图、Session 级预算与活动聚合、共享资源策略及临时 Workflow Thread
生命周期。

## 2. 概念对照

| Anthropic 概念 | Durable 对应概念 | 说明 |
| --- | --- | --- |
| Session | Session | 一次协作、授权和资源范围；包含主 Thread 与子 Thread |
| Primary thread | `main` Thread | 主 Agent 的上下文和默认用户入口 |
| Subagent thread | Child Thread | 子 Agent 的持久、隔离上下文 |
| 发给子 Agent 的一次任务 | Child Turn | 可排队、暂停、取消和恢复的逻辑任务 |
| 向已有子 Agent follow-up | 同一 Child Thread 上的新 Turn | 复用原 Agent 状态和上下文 |
| Agent 实际运行 | Run | 一次实际调用；Suspend 后恢复使用新 Run |
| 一轮模型/工具处理 | Step | Run 内诊断、用量和恢复关联单位 |
| Workflow run thread | 临时用途的 Child Thread | 后续可用用途字段和自动归档策略表达 |

Anthropic 将每个 Agent 的对话和事件隔离到独立 Thread，同时让同一 Session 下的
Agent 共享 sandbox、文件、凭据和预算。Durable 的默认边界更偏执行正确性：Thread
独立持有 checkpoint、journal、FIFO、lease、epoch 和私有资源；需要共享的 Workspace、
Sandbox、知识或结果应显式配置。这个差异不需要修改 Thread 定义，只需要补充 Session
资源策略。

## 3. 当前已经具备的基础

### 3.1 多 Thread 和隔离执行

`DurableSession` 已提供主 Thread、创建、查询和列举 Thread 的操作；
`DurableThread` 提供独立的 Turn、history、event、checkpoint、restore 和 fork 操作。
每个 Thread 使用完整 `ThreadKey(SessionKey, threadId)`，并固定自己的
`ThreadBinding(harness, definitionVersion, stateVersion)`。

Backend 按 Thread 调度 ready work、lease 和 epoch。同一 Session 的不同 Thread 可以
并行执行，而同一 Thread 仍只有一个权威执行者。Session 公共事件使用统一 sequence，
Thread/Turn/Run 观察通过过滤器选择目标范围；Session 事件流不会自动成为主 Agent 的
模型上下文。

### 3.2 持久子任务委派

`DelegationExecutor` 已使用标准 Executor/result 边界实现以下语义：

1. 默认按父 `executionId` 创建确定性的 Child Thread。
2. 在 Child Thread 上受理一个 Child Turn。
3. 将 `executionId → childThreadId + childTurnId` 与父子来源持久化。
4. 重投或宿主替换复用已冻结映射，不重复创建子任务。
5. 父 Turn 等待 Child Turn 结果；Child Thread 独立调度。
6. 取消只作用于对应 Child Turn；父暂停不自动暂停子任务。
7. 拒绝跨 Session、自委派及未完成委派依赖环。
8. 目标解析器可以显式选择已有 Child Thread，从而复用上下文。

这些能力已经覆盖 Multi-Agent 最难的恢复边界：父 Agent、子 Agent 或 Runtime 在委派
过程中退出后，持久映射、Child Turn 和结果应用仍可以恢复。

### 3.3 当前 Harness 子 Agent 仍是另一条路径

Core `SubAgentTool` 和 Harness `SubagentsMiddleware` 已支持创建子 Agent、返回
`agent_key`、异步任务和 follow-up，但本地子调用当前通常使用独立 child sessionId，
并清除 threadId、turnId 和 runId。它能够提供进程内或 Harness 自有的多 Agent 体验，
但不会自动成为当前 Durable Session 下的 Child Thread。

只有工具通过 Durable tool route 交给 `DelegationExecutor` 时，才获得上述持久
Child Thread、Child Turn、恢复和独立调度语义。后续工作的核心是统一这两条路径的
对外体验，而不是让 Harness 增加第二套持久调度器。

## 4. 与完整 Multi-Agent 能力的差距

### 4.1 缺少统一的 Subagent 句柄

Harness 使用 `agent_key` 标识可继续对话的子 Agent，Durable 使用 `ThreadKey`。
目前没有通用协调层持久保存二者的映射，也没有统一的 spawn、send/follow-up、list、
interrupt 和 archive 操作。

目标语义应是：

```text
spawn(agent definition)
  -> 创建或解析 Child Thread
  -> 返回 opaque agent_key
  -> agent_key 持久映射到 ThreadKey

send(agent_key, message)
  -> 在同一 Child Thread 提交新的 Turn
```

`agent_key` 是面向模型和产品的安全句柄，`ThreadKey` 是 Durable 权威身份；两者不应
合并为一个未经授权即可构造的字符串。

### 4.2 Thread 关系缺少查询视图

当前父子来源记录在 Delegation/Turn 上，这是执行正确性所需的真实关系。`ThreadView`
只包含定义和生命周期，没有用途、创建来源、显示名称或归档信息。

父子关系不宜简单改成 `Thread.parentThreadId` 的唯一所有权关系。一个显式复用的
Child Thread 可能接收同一主 Thread 的多次 follow-up，未来也可能被多个 Turn 使用；
真正的等待和取消关系属于一次 Delegation，而不是 Thread 本身。

建议给 Thread 增加只用于发现和生命周期策略的元数据：

- `purpose`：`PRIMARY`、`SUBAGENT`、`WORKFLOW`、`GENERAL`。
- `createdByThreadId`：最初创建来源，只用于 lineage 和审计。
- `label`：可选的人类可读名称。
- `retention`：持久保留或终态后自动归档。

权威执行关系继续使用：

```text
parent Thread/Turn/executionId -> child Thread/Turn
```

### 4.3 Session 级聚合能力不足

`SessionView` 当前主要表示 Session 身份、主 Thread、生命周期和 metadata。完整的
Multi-Agent 产品通常还需要：

- 从已提交 Turn 派生 Session 活动状态，例如 idle、running、waiting 和 paused。
- Session 级 token、金额或时间预算，并原子分配给各 Thread/Turn。
- Thread 数量、并发数、委派深度和待处理任务限制。
- Session 级取消、关闭和资源回收策略。

这些状态不得由另一套可漂移的状态机维护。活动状态应从权威 Turn/lease 事实计算；
预算使用记录需要与 Run/模型请求事实一致。

### 4.4 事件可见性与结果路由需要区分

Durable 已有 Session 级公共事件和 Thread 过滤，但仍应明确两个不同通道：

- **观察通道**：用户、控制台或平台查看所有 Thread 的公共事件。
- **上下文通道**：子 Agent 的结果、请求或权限确认进入主 Agent 上下文。

Session 能观察 Child Thread，不表示主 Agent 自动读取 Child Thread 的完整历史。
委派结果应通过稳定 executionId 作为工具结果应用到父 Turn；权限确认等阻塞交互可以
向主观察流投影摘要，但回答必须路由回原 Thread/Turn/interactionId。

### 4.5 共享资源和隔离策略尚未统一

Multi-Agent Session 需要明确哪些资源共享、哪些资源隔离：

| 资源 | 建议默认值 | 说明 |
| --- | --- | --- |
| Agent 对话、checkpoint、journal | Thread 隔离 | 不能合并到 Session 状态 |
| Turn 队列、lease、epoch | Thread 隔离 | 保持并行和 fencing 正确性 |
| Workspace | 按 Agent 定义选择共享或隔离 | 复用 Harness `SHARED/ISOLATED` 能力 |
| 凭据和权限策略 | Session 可共享，执行时授权 | 不把密钥复制进 Thread DTO/checkpoint |
| Sandbox | 显式共享或独占 | 共享写入仍需 CAS/操作账本 |
| 预算和并发配额 | Session 聚合 | Thread/Turn 消耗进入统一账本 |

共享资源不能依赖 Thread lease 保证安全，因为不同 Thread 可以并行运行。

## 5. 建议的目标模型

### 5.1 保持现有身份层级

继续使用：

```text
SessionKey(namespace, sessionId)
└─ ThreadKey(session, threadId)
   └─ turnId
      └─ runId
         └─ stepId
```

不把 agentId、父 Thread、用途或 Agent 定义编码进 `ThreadKey`。这些属性会变化或属于
视图，编码进身份会破坏复用、授权和恢复。

### 5.2 持久 Subagent 与临时 Workflow 分开

- `SUBAGENT` Thread 默认持久保留，允许通过 `agent_key` 提交多个 Turn。
- `WORKFLOW` Thread 可以按一次工作流运行创建，终态后自动归档。
- 普通内部辅助调用无需全部升级为 Durable Thread；只有需要后台执行、独立观察、
  重启恢复或后续 follow-up 的调用才进入 Durable 委派。

### 5.3 Delegation 是边，不是新的资源层级

Delegation 表达父 Turn 等待 Child Turn 的关系，并负责幂等、结果应用和取消边界。
它不替代 Session/Thread/Turn，也不需要成为新的调度器。

一个 Child Thread 可以连续承载：

```text
Delegation D1 -> Child Turn C1
follow-up D2 -> Child Turn C2
follow-up D3 -> Child Turn C3
```

每个 Delegation 独立关联父 executionId；Thread 上下文持续存在。

### 5.4 Run 和 Step 保持内部执行粒度

Multi-Agent 不改变 Run/Step 的定义：

- Child Thread 上每次实际 Agent 调用产生一个 Run。
- Child Turn 因工具等待而 Suspend，恢复时产生新 Run。
- 一个 Run 内可以有多个 Step。
- follow-up 是新 Turn，不是旧 Run 的继续。

产品默认展示 Agent/Thread 和任务/Turn；Run/Step 用于诊断、用量和恢复审计。

## 6. 分阶段改进计划

### P0：冻结语义和协议

1. 将本文中的 Session、Thread、Turn、Delegation、Run、Step 映射写入公共协议说明。
2. 明确 Thread 用途、来源和 retention 是发现元数据，不参与 Thread 身份。
3. 明确 follow-up 创建新 Turn，Suspend 恢复创建新 Run。
4. 明确 Session 事件可见性不等于模型上下文共享。

验收：Java 类型、schema、HTTP 和双语协议对相同字段和生命周期给出一致定义。

### P1：补充 Thread 发现和生命周期元数据

1. 扩展 `ThreadSpec/ThreadView`，增加 `purpose`、`createdByThreadId`、`label` 和
   retention/archived 语义。
2. 保留 `DelegationParent` 和 `DelegationLink` 作为权威任务关系。
3. 增加按 purpose/status 查询 Thread 的有界分页能力。
4. 定义 close 与 archive 的区别：close 拒绝新任务，archive 控制默认可见性和保留策略。

验收：创建重试、关闭/归档竞争、快照、事件回放、HTTP 和后端共享契约通过。

### P2：建立 Durable Subagent Coordinator

1. 定义 Harness 中立的协调接口，不让 Core/Harness 依赖 Durable。
2. Durable 实现将 opaque `agent_key` 持久映射到 `ThreadKey` 和冻结的 Agent 定义。
3. spawn 解析 Agent 定义并创建 `SUBAGENT` Thread；send/follow-up 向已有 Thread 提交 Turn。
4. list/status/interrupt/archive 通过 Durable 公共 API 操作，不读取进程内 Agent 实例。
5. 使用现有 `DelegationExecutor` 完成父子受理、等待、取消和结果应用。

验收：主 Agent 重启、子 Agent 重启、Runtime 替换、重复 spawn/send 回执丢失均不重复创建
Thread 或 Turn；同一 `agent_key` 的 follow-up 恢复原上下文。

### P3：接入 Harness Subagent 工具

1. Harness 保留本地轻量子调用路径。
2. 当宿主提供 Durable coordinator 时，spawn/send 工具使用 Durable Child Thread。
3. 明确选择规则：本地调用、持久 Subagent、临时 Workflow，不做隐式且不可观察的升级。
4. 统一事件 source、任务回执和错误语义，避免向模型暴露 Durable 内部 ID。

验收：现有普通 HarnessAgent 行为不依赖 Durable；启用 Durable 后，agent_key、异步完成通知、
follow-up、取消和恢复使用同一 Child Thread。

### P4：Session 级资源、预算和策略

1. 增加 Session 级预算/并发策略与原子消费记录。
2. 将 WorkspaceMode、Sandbox 和凭据解析为明确的共享或隔离配置。
3. 增加 Thread 数、并发、委派深度和 fan-out 限制。
4. 从权威 Turn/lease 派生 Session 活动视图。

验收：并发 Child Thread 不会突破预算或覆盖私有状态；共享 Workspace 的竞争由实际资源
机制处理；配额和权限失败不会伪造成 Agent 业务失败或成功。

### P5：Workflow 和完整观察体验

1. 支持 `WORKFLOW` Thread 的自动归档和保留期限。
2. Session 观察流提供主 Thread 与 Child Thread 活动摘要，完整内容仍从目标 Thread 查询。
3. 阻塞交互可在主观察流呈现，但回答根据原始 interaction 身份路由。
4. 控制台展示 Session → Thread → Turn，并按需展开 Run/Step。

验收：事件重连、过期 cursor 重同步、归档恢复、权限确认、父子并发和大量 Thread 分页通过。

## 7. 关键验收场景

后续实现至少覆盖以下场景：

1. 主 Agent 委派 Child Turn 后退出；子 Agent完成，替代宿主恢复父 Turn。
2. Child Agent 在模型完成、工具发起、结果提交前后分别退出，外部副作用不重复。
3. 同一 `agent_key` 连续发送多次 follow-up，均进入同一 Thread 的不同 Turn。
4. 重复委派、重复 MQ 投递和提交后回执丢失不产生第二个 Child Thread/Turn。
5. 父 Turn 暂停时 Child Turn 可以完成，父恢复后只应用一次结果。
6. 父 Turn 取消只取消其 Child Turn，不取消复用 Child Thread 中其他 Turn。
7. 自委派、跨 Session 委派和未完成依赖环被拒绝。
8. 同 Session 多 Child Thread 并行时，checkpoint、journal、plan mode 和私有资源不串线。
9. Session 预算耗尽时停止新的执行受理，同时保留已提交事实和真实执行状态。
10. 主观察流中的权限请求可以准确路由回原 Child Thread/Turn/interaction。
11. `WORKFLOW` Thread 自动归档不删除仍需审计的 Turn/Run/Step 事实。
12. 未配置 Durable 的 Core/Harness 用户继续使用原有直接调用和本地 Subagent 能力。

## 8. 暂不建议的做法

- 不增加第二套 `AgentSession` 或 MultiAgentSession 调度器。
- 不让一个 Session 的所有 Agent 共享同一 AgentState、checkpoint 或 journal。
- 不把每次工具调用、每个 Run 或每次恢复都建成新 Thread。
- 不把完整 Child Thread 历史自动拼入主 Agent 上下文。
- 不把 `parentThreadId` 作为 Child Thread 唯一所有者或取消传播依据。
- 不让 Harness/Core 直接依赖 Durable；通过可选宿主协调接口接入。
- 不为了展示 Agent 树而牺牲 Delegation 的真实 Turn 级归属。

## 9. 相关实现与文档

- [统一执行模型](execution-model-plan.md)
- [实施进度](../verification/execution-model-progress.md)
- [独立验收清单](../verification/execution-model-acceptance.md)
- [对外交互协议](../../protocol/public-interaction-contract.md)
- `DurableSession` / `DurableThread`：Session 与 Thread 用户操作。
- `DelegationExecutor`：同 Session Child Thread + Child Turn 委派。
- `TransactionalSessionBackend.delegate`：父子映射、受理、事件和调度的原子提交。
- `SubAgentTool` / `SubagentsMiddleware`：当前 Core/Harness 子 Agent 入口。

本文作为后续 Multi-Agent 设计评审的起点。实际完成状态和验证证据应更新到
[实施进度](../verification/execution-model-progress.md)与[独立验收清单](../verification/execution-model-acceptance.md)，
不能仅修改本文后宣称能力已实现。

# External Agent 深度测试与修复报告（2026-09-05）

## 结论

本轮在隔离 worktree 中对 External Agent 的 chat、直接 AgentTask、声明式 Workflow、Team lead/worker 协作、Issue 生命周期、会话归属、ASDP 路由及 Attempt 心跳进行了真实端到端测试。共发现 11 个问题，均已在分支 `codex/fix-external-agent-deep-test` 修复并补充回归测试。

修复后的最终验证结果：

- 两轮 chat 使用同一个 session，第二轮能准确回忆第一轮标记。
- 声明式 Workflow 中 External lead、External worker 顺序执行成功，run 和两个节点均为 `succeeded`。
- Team 中 External lead 成功创建并委派子 Issue，External worker 成功执行，lead 收到 follow-up 后验收子 Issue 并完成 coordinator；run 和两个节点均为 `succeeded`。
- 两个 External Agent 故意使用相同的物理 `instanceRef`，任务仍分别路由到正确 agent，没有串线。
- 所有 AgentTask 都使用控制面分配的独立 runtime session，没有 task UUID 形式的幽灵 session。
- Issue 状态随执行从 `backlog` 进入 `in_progress`，完成后进入 `in_review`；被 lead 验收的子 Issue 进入 `done`。
- Go 全量测试以及 Java core/harness/aistio 相关模块测试全部通过。

## 实际端到端用例

### Chat 会话

- Agent：`external-e2e4-lead`
- 第一轮标记：`EXTERNAL_CHAT_OK_4_20260905`
- 第二轮要求回忆标记，返回完全一致。
- 两轮返回同一 session key：`agent:317c9202-0413-4a7e-8658-baec035ea096:main:main-37abd8e6-6ab8-46c0-9048-e43a10e4232a`
- 控制面仅记录一个对应的 runtime chat session：`main-37abd8e6-6ab8-46c0-9048-e43a10e4232a`。

### 声明式 Workflow

- Definition：`d07d8e46-acea-4b40-8602-ee8feb109475`
- Run：`9b8b392c-c32b-451a-a584-909abd9c6c47`
- 根 Issue：`5269f636-ce5d-478a-ae52-25210063fc43`，最终状态 `in_review`
- `lead-stage`：External lead 返回 `LEAD_WORKFLOW_OK_20260905`，节点 `succeeded`
- `worker-stage`：External worker 返回 `WORKER_WORKFLOW_OK_20260905`，节点 `succeeded`
- 两个任务都只有一次 Attempt，均为 `succeeded`，无 retry/failure。

### Team lead/worker

- Team：`d697205e-8c7f-4e16-88de-579b656dcffa`
- Run：`26a38dd2-d9ce-43be-8c06-0eb78f166aa1`
- 根 Issue：`419b8c01-cd8a-490c-a303-8455ce924705`，最终状态 `in_review`
- 子 Issue：`664c23dc-fb71-4d9a-ac93-6b29e0c0023c`，worker 完成后由 lead 验收，最终状态 `done`
- Worker 结果：`WORKER_TASK_OK_4_20260905`
- Coordinator 结果：`TEAM_RUN_OK_4_20260905`
- 初始 lead task、worker task、lead follow-up task 共三次 Attempt，均一次成功，无 retry/failure。
- Worker 的工具目录不包含 `issue.child.create`、`issue.accept`、`run.node.complete`、`run.node.fail`、`run.replan`；lead follow-up 才获得 coordinator action。

### 同 host 多 agent 路由

两个运行中的 External Agent 都注册为 `instanceRef=U-FF406114-1819.local`，但使用不同 agent ID：

- lead：`182934d0-940e-47b3-b6d3-648d67f28fce`
- worker：`417cbff7-9238-4de3-80fd-9d95a6ff2610`

Workflow 和 Team 中的所有任务均到达指定 agent，验证 ASDP connection key 加入 agent 维度后不会覆盖或误投递。

## 发现并修复的问题

| ID | 级别 | 现象与影响 | 根因 | 修复 | 状态 |
| --- | --- | --- | --- | --- | --- |
| EXT-001 | P0 | `task.start` fallback 后 External Agent 丢失 Issue 描述、Team、输入和 availableActions，只看到精简 task | `task.start` 返回紧凑响应，但 starter 把它当成完整 context envelope | start 成功后重新读取权威 task context；409 只在新鲜读取确认同一 task 已 running 时视为成功 | 已修复并测试 |
| EXT-002 | P0 | External Team lead 无法真实创建子 Issue、验收或完成 coordinator，只能在文本中声称已操作 | Java external adapter 没有把 task-scoped collaboration MCP actions 注册为 Agent 工具 | 增加动态 MCP 工具发现/调用、task token 上下文和 CollaborationClient API | 已修复并测试 |
| EXT-003 | P0 | lead 仅用文本声称委派完成时，Team coordinator/run 可能被错误自动完成 | orchestration engine 将 leader AgentTask 的文本完成推断为 Team 已收敛 | Team coordinator 改为显式一致性边界，只接受真实 `run.node.complete/fail` | 已修复并测试 |
| EXT-004 | P1 | AgentTask 的模型执行可能使用 task ID 而不是控制面分配的 runtime session，状态、历史和资源归属不一致 | ASDP runtimeBinding 中的 `sessionId` 未传入 `AgentTaskAssignment` | 从 runtimeBinding 解析并传递 sessionId，starter 使用该 session 执行 | 已修复并测试 |
| EXT-005 | P1 | External Agent 已执行并完成，但 Issue 仍停留在 `backlog` | 启动 AgentTask 时未同步推进关联 Issue | memory/Postgres store 在 task start 时原子推进 Issue 至 `in_progress` 并记录 activity/event；完成后正常进入 review | 已修复并测试 |
| EXT-006 | P2 | Session/overview 中可用的 healthy External instance 被显示为不健康 | stale affinity miss 先写入 false，后续 healthy dataplane 没有覆盖 | 可解析的 healthy peer 明确覆盖旧的 false | 已修复并测试 |
| EXT-007 | P2 | 禁用执行工具后，skill prompt 仍提示 shell 用法并暴露 files root，诱导模型调用不存在工具 | 工具过滤只影响工具目录，未同步影响能力提示和 skill prompt | ToolFilter 同步过滤 prompt capability；没有 shell 时不渲染 shell/files-root 指令 | 已修复并测试 |
| EXT-008 | P0 | 同一 host 上运行两个 External Agent 时，后连接者覆盖先连接者，lead/worker 任务可能串线或无法投递 | ASDP connection registry key 只有 tenant/namespace/instance，没有 agent 维度 | connection identity、查询、路由、session transport 全部加入 agent name/id 维度 | 已修复并测试 |
| EXT-009 | P1 | 每个 AgentTask 额外产生一个以 task UUID 命名、没有真实对话的幽灵 session | `AgentScopeAdapter` 记录 session 时硬编码使用 agentTaskId | 优先登记 assignment 中实际 runtime sessionId，仅在旧协议缺失时 fallback | 已修复并测试 |
| EXT-010 | P0 | workspace/context 上报较慢时阻塞 Attempt heartbeat，30 秒后被控制面判死并发起重复 Attempt；旧 Attempt 随后上报得到 stale-token 401 | best-effort reporting 与执行租约心跳共用单线程 scheduler | Attempt heartbeat 使用独立 scheduler 和生命周期，避免被 workspace 扫描/状态上报饿死 | 已修复并测试 |
| EXT-011 | P0 | worker 能看到并误调用 coordinator-only action；初始 lead 本地等待 worker，follow-up 与原 task 并发，造成 accept/node-complete 冲突 | MCP 工具目录及 Java 注册未按 Team 角色/availableActions 收敛，提示词也未区分初始 lead、worker、follow-up lead | 服务端和 Java 双层 action 过滤；角色化 prompt；初始 lead 委派后立即返回，worker 只交付结果，follow-up lead 顺序验收并收口；错误返回包含控制面原因 | 已修复并测试 |

## 自动化回归

- `go test ./...`：通过。
- `mvn -pl agentscope-harness,agentscope-extensions/agentscope-extensions-aistio -am test`：`BUILD SUCCESS`。
- Java 汇总：core 及相关模块通过；Harness 831 tests（0 failure / 0 error，3 skipped）；aistio extension 59 tests（0 failure / 0 error）。
- 新增/扩展了 start-context、tool discovery/call、角色提示、MCP 角色过滤、Issue 状态、实例健康、ASDP 多 agent 路由、runtime session、Attempt scheduler 隔离等回归用例。

## 分支与合并

- 隔离 worktree：`/Users/ken/agentscope-2/agentscope-java-external-fixes`
- 修复分支：`codex/fix-external-agent-deep-test`
- `00074a5f5` 是开始测试前对当前工作区状态的基线快照。
- 后续修复按问题域拆分为独立提交，便于整体 merge 或按提交 cherry-pick。

# Managed Agent 深度测试与修复记录（2026-09-06）

## 结论

本轮在独立 Git worktree、独立端口和独立 PostgreSQL 容器中，对 `managed` runtime 完成了真实模型端到端测试，并修复测试中确认的问题。

- Chat：连续两轮对话、上下文延续、事件/消息投影和紧邻 turn 竞争均通过。
- 直接 Issue：Managed worker 能读取任务上下文、开始任务、写 result 并完成 Task/Attempt/Node/Run。
- Team：Managed Agent 分别作为 lead 和 worker，完成子 Issue 派发、认领、结果回传、leader follow-up 和显式 coordinator 收敛。
- Workflow：Managed worker 与 lead 按 DAG 顺序执行，均能处理同一 Issue 并使 Run 成功。
- 失败路径：独立 Agent 的 `task.fail` 能一致地终止 Task、Attempt、Node 和 Run；Team worker 的失败则作为可决策结果回流 lead，不再直接终止整个 Run。
- 安全性：task/attempt token 不再出现在 wake 文本、公开 Session taskContext 或公开事件中。

测试共确认并修复 20 类实现问题。最终没有遗留本轮已确认的 managed 产品缺陷；一次模型 provider 超时和机器时钟跳变属于外部测试条件，但它帮助暴露并修复了旧 turn 污染新 Attempt 的 fencing 问题。

## 隔离环境

- 基线 commit：`2bc9fdad841f10524c3d612c7b3a0f693be3c9ff`
- 修复分支：`codex/fix-managed-agent-deep-test`
- 独立 worktree：`/Users/ken/agentscope-2/agentscope-java-managed-fixes`
- Gateway / Control / Data / Scheduler：`28080` / `28081` / `28082` / `28083`
- PostgreSQL：容器 `agentscope-managed-fix-pg`，端口 `55433`
- 模型：真实 DashScope provider

原工作目录 `/Users/ken/agentscope-2/agentscope-java` 未被本轮修改，因此可以继续承载另一个 session 的并行工作，后续通过 Git merge 合并本分支。

## 真实场景结果

### 1. Managed Chat：通过

- Agent：`dcd6ce2f-1f8f-44df-8f23-45de8860f1c5`
- 验证输出：第一轮 `CHAT_TURN1_OK`；第二轮 `CHAT_TURN2_OK:ORCHID-7319`
- 验证项：两轮共用 Session、第二轮正确恢复第一轮随机值、4 条消息和 11 个 Session event 正确投影。
- 紧邻 turn 竞争复验：Chat `a6ef053a-f62a-437a-911f-609b77154490`，Session `4f51622a-0e41-4fad-8760-0824f5c79a49`。第二轮在第一轮刚发布 assistant event 时提交，经过约 660 ms 的窄窗口重试后被正确接纳，没有重叠执行或 optimistic-lock 异常。

### 2. 直接 Issue worker：通过

- Agent：`139ee73c-b504-47b8-807f-25c81bbf3777`
- Issue：`7396c288-31db-4606-828f-8b9fb5acca48`
- Task：`3acd30fe-ff28-43e3-bd75-3836847e3a7e`
- 结果：`WORKER_DIRECT_OK`
- 最终状态：Task completed、Attempt succeeded、Node succeeded、Run succeeded。

该场景还验证了 `task.respond` 后调用 `task.complete` 会复用已有 result Comment，并在同一事务中推进 Attempt、Node 和 Run，不再生成重复 result 或留下半完成状态。

### 3. Team lead + worker：通过

- Team：`68a05152-2fa5-4688-aa58-b061f711fc4d`
- Lead：`3c69a6b0-0eff-4314-bebd-ba8657bdd84a`
- Worker：`51571f37-f252-4240-8d2f-e5b7007e452f`
- 根 Issue：`8318dcfa-42bb-4fc3-b956-6dd65fd39093`
- 子 Issue：`0e62bee0-...`（完整对象保留于隔离测试库）
- Run：`fa09eb53-b4c6-47ec-a2e9-f62c7f4e865d`
- 结果：worker 写入 `WORKER_TEAM_OK`，leader follow-up 写入 `LEADER_TEAM_OK`。
- 最终状态：初始 lead、worker、leader follow-up 三个 Task/Attempt 均成功；子 Issue done；coordinator 由 leader 显式完成；Run succeeded。

另做了一次负向顺序测试：leader 在子 Issue 尚未 accept 前调用 `run.node.complete`，控制面正确拒绝；其 Task 结束后，terminal token 也只能做最终 coordinator transition，不能继续修改 Issue/Task。该行为是预期保护，不计为缺陷。

### 4. Workflow worker + lead：通过

- Definition：`c5463a7f-6d6a-45fc-a926-94b8ae8ae768`
- Revision：`0460202a-e59b-4071-887f-b87a8aa0be01`
- Worker：`f44c978a-a10c-4f86-8bf8-e236a251477e`
- Lead：`1390d6f3-9a2f-4511-a7b9-db3bfa2664d6`
- Issue：`05493fa2-4fa0-43e1-afab-403c2440618e`
- Run：`20ff1f67-dd1d-4968-bed1-88c03186b364`
- 结果：`WORKFLOW_WORKER_OK`、`WORKFLOW_LEAD_OK`
- 最终状态：两个 agent node、两个 Task/Attempt 均成功，Run 产生 `run.succeeded`。

### 5. 显式失败路径：通过

- Agent：`5dd28786-5404-4435-a779-b7b6bddaed91`
- Issue：`6654d41b-ade4-4a09-85a7-3eb6d6611ecd`
- Task：`76dea3f6-d990-4d12-a918-a86beeabe443`
- Run：`ce84e2ef-e90e-49aa-9110-7e9fa300b3e8`
- 错误：`EXPECTED_TEST_FAILURE / MANAGED_FAILURE_PATH_OK`
- 最终状态：Task、Attempt、Node、Run 均 failed，错误码和消息一致。

## 已发现并修复的问题

| ID | 级别 | 问题 | 修复与回归覆盖 |
| --- | --- | --- | --- |
| MGD-001 | P1 | Data-plane 事件只保存在 managed 本地事件表，Chat/控制面看不到 assistant 消息和完整生命周期。 | 增加内部幂等事件接收与 Session read-model 投影；真实两轮 Chat 和事件序列通过。 |
| MGD-002 | P0 | wake 文本和持久化 Session taskContext 携带 task/attempt token，可能经用户消息和公开 API 泄漏。 | wake 改为无凭证协议提示，公开上下文移除 token；凭证仅由内部 session resolve 按当前 Attempt 动态签发。公开 Session/Events 扫描无敏感模式。 |
| MGD-003 | P1 | Managed Agent 没有私有 Issue/Task/Team/Run 上下文，也没有 collaboration MCP，无法真正认领、派发或完成任务。 | 内部 resolve 注入 fenced executionContext；构建 Agent 时注入专用 MCP、系统协议和可用 action 白名单。 |
| MGD-004 | P1 | collaboration tools 在默认 permission 流程中进入 ASKING，后台 managed turn 无用户可交互审批而卡住。 | 对服务端已签名 task scope 中的 action 注入显式 ALLOW permission context。 |
| MGD-005 | P1 | 工具协议缺少 `task.start`，Agent 无法显式确认 dispatched Task 已开始。 | 新增幂等 `task.start` MCP tool，并加入 availableActions；单元和真实任务覆盖。 |
| MGD-006 | P0 | HarnessAgent 按 Agent 定义缓存，多个 Task/Attempt 可能共享旧上下文和旧 token。 | AgentTask cache key 加入 session、attempt 和 dispatch generation；新 Attempt 自动清理旧实例。 |
| MGD-007 | P1 | Managed capability/readiness 只检查 binding，缺少 Data URL、Agent definition、默认 Environment 仍报告 available。 | capability 判定执行完整 managed runtime 校验；增加无 Environment 的回归测试。 |
| MGD-008 | P0 | `task.respond` 后复用 Comment 的 `task.complete` 只推进 Task，Attempt/Node/Run 可能永久 waiting。 | memory/PostgreSQL 完成事务统一校验 fencing、推进 Attempt、合并 usage、reconcile Node/Run、写 activity/outbox；直接 Issue 与 Workflow 实测通过。 |
| MGD-009 | P1 | Team leader Task 完成会被当成 coordinator 已收敛，可能凭一段文字绕过子 Issue/节点检查。 | 取消 leader auto-complete；必须显式调用 `run.node.complete`/`run.node.fail`。负向和正向 Team 测试均通过。 |
| MGD-010 | P1 | leader Task 完成后 token 立即失效，无法执行协议要求的最后 coordinator transition；若放宽全部权限又会越权。 | 允许 terminal coordinator token 仅访问最终 node complete/fail，其他 MCP action 明确拒绝。 |
| MGD-011 | P0 | 长模型 turn 没有续租 ExecutionAttempt，超过 lease TTL 会被 scheduler 错误重试。 | Data plane 每 10 秒向内部 heartbeat 续 45 秒 lease；每个带 attempt/generation/turn fence 的有效 session event 也作为第二通道续租；terminal 的同一 Attempt heartbeat 幂等返回 204。 |
| MGD-012 | P1 | Session 遥测与 Task 语义终态混淆：`session.error`/terminated 没有同步失败，而 idle 曾被用于推断 leader 完成。 | running 映射 task start，error/terminated 走统一 FailTask；idle 只更新 Session，不完成或失败 Task。Task 必须由显式语义命令终结，遗漏命令时由 Attempt lease/retry 收敛。 |
| MGD-013 | P0 | turn lease owner 使用进程固定 ID；上一轮尾事件尚未结束时，下一轮会被误判为同 owner 续租，造成两个 turn 重叠和 JPA optimistic-lock。 | 每个物理 turn 使用 UUID-fenced lease owner，旧 turn teardown 不能释放新 turn lease；紧邻 Chat turn 实测通过。 |
| MGD-014 | P1 | Managed task wake 恰逢旧 turn 最终事件/lease 清理时收到 409，会把可执行任务错误 requeue。 | 对 1 秒以内的窄竞争窗口执行 20×50 ms 有界重试，长运行仍返回 busy 交由编排层排队。 |
| MGD-015 | P1 | heartbeat 在 Reactor parallel scheduler 中调用 WebClient `block()`，运行时抛出 illegal blocking，实际没有续租。 | heartbeat 调度迁移到 bounded-elastic；真实长 Team turn 收到 204，日志不再出现 illegal-blocking。 |
| MGD-016 | P0 | retry 创建新 Attempt 后，旧物理 turn 的迟到 terminal event 会按“当前 Attempt”处理，导致新 Attempt 被错误失败。 | admission 时冻结 attempt/generation/turn scope；后续 resolve 不得覆盖；所有 event/heartbeat 带 frozen fence，旧事件被忽略；增加 Java 和 Go 双侧回归测试。 |
| MGD-017 | P0 | Managed AgentTask 注入协作 ALLOW 规则后使 Core permission context 由 trivial 变为 DEFAULT non-trivial；未列出的只读 `web_search` 被错误置为 `PERMISSION_ASKING`，但没有生成 Service HITL ticket，最终表现为 idle、heartbeat timeout 和失败重试。 | AgentTask 的 Core permission fallback 改为 BYPASS（bypass-immune safety check 仍保留），由 `ToolConfirmationMiddleware` 统一执行 AgentSpec 的 `always_ask`/deny 并创建可恢复 ticket；防御性识别残留 Core ASK 并立即报告明确错误，不再写 idle。Execution `68bf2905-08f3-4cd2-bea1-652e0493e14f` 为回归样本。 |
| MGD-018 | P0 | 两个 worker 并行完成时会各自创建同一 coordinator node 的 leader follow-up；完成屏障把另一个 leader continuation 误判成 active worker，两个 lead 互相阻塞并最终耗尽 Attempt。 | 同节点 leader continuation 不再作为 worker barrier；一个 continuation 收敛前会取消其余冗余 continuation。失败样本 `ee87044a-44ea-4922-b126-4fd2d8a065f9`，真实成功回归 `c21f0570-49c7-4634-9f0a-1ef6b95e9965`。 |
| MGD-019 | P1 | fail-fast 只把 sibling node 标为 cancelled，没有取消节点上的 active AgentTask/ExecutionAttempt，Run failed 后仍残留 running lead。 | fail-fast 转换节点前先取消该节点的所有非终态 Task/Attempt；`TestFailFastCancelsActiveTasksOnSiblingNodes` 覆盖状态收敛。 |
| MGD-020 | P0 | adaptive Team 中一个 worker 因工具/凭证配置明确返回失败后，child Issue 和根 Issue 都停在 `in_progress`，默认 `fail_fast` 又取消 coordinator 与其他 worker，lead 没有任何后续决策机会。 | 将 exhausted/non-retryable worker failure 定义为有效协作结果：Task/Attempt/Node 保留 failed，child Issue 转 `blocked`，写入结构化 status Comment 并唤醒 lead；adaptive worker 默认 continue，TeamPolicy 控制 transient retry；lead 可 replan、显式 skip、请求人类处理或 fail coordinator。失败样本 `80c3679a-721a-43e5-8c66-74b40ff3ea01`。 |

补充真实回归：`2cf5fa0f-5d5d-458f-b24f-d7221559ecff` 因测试环境未配置 `TAVILY_API_KEY`，worker 返回说明文本但没有执行 `task.complete`/`task.fail`，其三次 Attempt 按 lease 策略耗尽。该样本未进入双 leader 收敛阶段，不作为 MGD-018 修复结果；随后使用不依赖外部搜索的相同双 worker 场景完成了上述成功验证。

## 安全与一致性检查

- `GET /api/v1/sessions?limit=200` 中的公开 `taskContext` 包含 Task、Issue 和 availableActions，但不含 `taskToken`/`attemptToken`。
- `GET /api/v1/sessions/{id}/events` 的 14 个 managed 事件中不含 token、task-token header 或 Bearer credential。
- session runtime 已不在线时，实时 `/context` 返回 `503 agent lookup requires a Kubernetes connection or a registered data plane`，属于动态 runtime 查询的既有行为；持久化公开快照仍可用于上述泄漏检查。
- Session taskContext 是 dispatch-time snapshot，其中 Task 状态可能仍为 `dispatched`；Task/Attempt/Run 的权威终态应从 collaboration/orchestration API 读取。这是快照语义，不计为本轮缺陷。

## 自动化回归

已执行的重点测试：

```bash
cd agentscope-service/aistio
go test ./internal/httpapi ./internal/product ./internal/collaboration ./internal/orchestration ./internal/runtimebinding ./internal/store/memory

AISTIO_TEST_POSTGRES_DSN='postgres://builder:builder@localhost:55433/builder?sslmode=disable&search_path=rt' \
  go test ./internal/store/postgres -run 'TestPostgresStore/Collaboration$' -count=1 -v

mvn -pl agentscope-service/service-dataplane -am \
  -Dtest=ControlPlaneClientExecutionScopeTest,TurnLeaseServiceTest,SessionTurnAdmissionTest,HarnessAgentBuildServiceCacheKeyTest,SessionEventLogMirrorTest \
  -Dsurefire.failIfNoSpecifiedTests=false test
```

最终回归结果：

- 上述受影响 Go 包全部通过。
- PostgreSQL collaboration store contract 通过。
- `mvn -pl agentscope-service/service-dataplane -am test` 通过：15 个 reactor module 全部 SUCCESS，其中 core 2275 个、harness 830 个、service-common 22 个、service-dataplane 25 个测试均无失败；该命令同时执行了各模块 Spotless check。
- `git diff --check` 通过。
- `go test ./...` 除 `internal/runtimehost/TestRenewLoopCancelsProviderAfterLeaseExpires` 外均通过。该 Hosted Runtime 用例在独立 `-count=5` 复跑中 3 次通过、2 次失败，原因为测试在 failure channel 收到值后立即检查 context，而生产 goroutine 的 `cancel()` 在发送之后执行，存在微秒级通知顺序竞态。它不在 managed runtime 路径或本分支改动文件内，并且已记录于既有 Hosted 深测报告；为避免与并行 session 的 Hosted 修复产生不必要冲突，本分支没有修改该范围。

合并到 `agentscope-service-v5` 后，主分支已包含并行完成的 External/Hosted 修复；再次执行 `go test ./...` 全部通过，包括 `internal/runtimehost`。

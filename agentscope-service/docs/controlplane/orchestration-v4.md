# AgentScope Service v4 统一执行与编排契约

## 事实边界

Issue 是工作、讨论、验收和最终状态的唯一事实源。Comment/Mention 是人和 Agent、Agent 与 Agent 之间可恢复的持久通信；Artifact 承载不能放入 Comment 的文件与大对象。Run 完成只产生 Activity 和可选 result Comment，不会自动完成 Issue。

每个 AgentTask 必须同时属于一个 OrchestrationRun 和 RunNode；每次物理执行必须产生新的 ExecutionAttempt。基础设施重试创建同一 Task 的新 Attempt，节点策略重试创建新 Task，终态 Run 的人工 rerun 创建带 `rerunOfRunId` 的新 Run。

```text
Issue
└── OrchestrationRun
    ├── RunNode
    │   ├── AgentTask
    │   │   └── ExecutionAttempt
    │   └── approval | condition | join | timer | signal | subrun
    └── RunEvent / TeamSnapshot / policySnapshot / usage
```

## Run 形态

- `direct`：Agent 分配或显式调用形成的单 Agent 节点 Run。
- `adaptive`：Team leader coordinator 动态委派 worker、child Issue 或新节点；leader 必须调用 `run.node.complete`/`run.node.fail` 显式收敛。
- `declared`：固定到不可变 OrchestrationRevision 的无环 DAG。
- `subrun`：父节点固定引用某个已发布 revision 的子 Run。

Definition 节点只允许 `agent`、`team`、`approval`、`condition`、`join`、`timer`、`signal` 和 `subrun`。发布前会校验 DAG、节点配置并编译全部 CEL。CEL 只看到 `run.input`、`run.variables`、`issue`、`trigger`、`nodes.<key>.status/output/artifacts`，没有文件、网络、反射或自定义代码入口。

## Runtime 与调度

AgentRuntimePolicy 按 `RunNode override > Team member override > Agent policy` 解析有序 candidates。缺少策略时 fail closed；只有显式 `fallbackMode=fresh` 才能在当前 candidate 重试耗尽后跨 backend，新 Attempt 仅从 Issue、Comment、Artifact 重建上下文。每个 candidate 的 capability 和 security 约束都会冻结到 dispatch snapshot；`securityConstraints` 是目标 labels 的递归子集，且可使用平台注入的不可变虚拟 label `backendKind`，格式错误或不匹配都 fail closed。

三种 backend 共用 ExecutionAttempt 状态、dispatch generation、fencing、heartbeat、cancel、failure 和 usage：

- Managed：一个 Task 使用稳定 `externalKey=agent-task|{taskId}` 的 Session，每个 Attempt 是新 Turn。
- External Application：ASDP 使用 `ExecutionAttemptCommand(dispatch|cancel)` 和 `ExecutionAttemptReport`。
- Hosted Runtime：Runtime Host 从 `/execution-attempts` claim/renew/report；workspace、provider session、checkpoint 只属于该 Hosted Attempt。

调度器以数据库作为持久队列，执行 tenant/Team/Agent 并发、预算、deadline、capability、安全约束和 namespace weighted fairness。任何 completion/report 都必须匹配 attemptId、generation/fencing 和冻结的 backend target；重复或 stale report 不得覆盖较新的 Attempt。

## 事务与恢复

Attempt success、Task completion、result Comment、input reconciliation、RunEvent 与 node reconciliation在一个数据库事务中提交。多副本 worker 使用 outbox、`SKIP LOCKED`、lease、CAS version 和 idempotency key；sweeper 收敛 timer、deadline、startup/heartbeat timeout、cancel grace、lost host 和 orphan Attempt。

pause 只禁止调度新节点，已运行 Attempt 可以保存结果；cancel 级联 node、Task、Attempt 和 subrun，但不改变 Issue。失败策略仅支持 `fail_fast`、`continue` 和 `partial_success`，不提供 Saga 补偿。

## 公共入口

Operator REST 的主要资源是 `/api/v1/orchestration-definitions`、`/api/v1/orchestration-runs`、`/api/v1/execution-attempts` 和 `/api/v1/agent-runtime-policies/{agentRef}`。终态 declared Run 可通过 `/api/v1/orchestration-runs/{id}/rerun` 固定原 revision 创建带 lineage 的新 Run。Task token 提供 `run.get`、`run.graph`、coordinator complete/fail、replan、signal 和 artifacts；Attempt token 只能由冻结的 backend target 上报对应 Attempt。

`scripts/dev-up.sh` 在 `BUILDER_REBUILD=1` 时重建未发布开发 schema，`scripts/smoke.sh` 验证 Runtime Policy、adaptive Run、Managed Attempt、持久 follow-up、Definition publish、declared signal Run 和 Issue 独立验收。

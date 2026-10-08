# AgentScope Service 终态架构

> 状态：已完成 Issue-first v4 统一执行与编排终态（2026-08-26）

AgentScope Service 的协作主链路是：

```text
Issue -> OrchestrationRun -> RunNode -> AgentTask -> ExecutionAttempt -> result Comment
```

Issue 保存长期工作、discussion、验收和 parent/child 关系；OrchestrationRun/RunNode 保存 direct、adaptive、declared 和 subrun 编排；AgentTask 表示一次明确的 Agent 工作义务；ExecutionAttempt 只表示某次物理尝试。Session 提供运行连续性，但不是协作事实来源。Team 是持久 roster，Issue 分配给 Team 时首先路由到 leader，worker 通过 mention、child Issue 或动态 replan 按需工作。

Managed、External Application 与 Hosted Runtime 都通过 `RuntimeBindingResolver` 接收同一 `ContextEnvelope`，读取同一 Issue/Comment API，并把结果写回同一 discussion。数据库是事实来源，outbox/ASDP/WebSocket 只用于可靠唤醒或缓存失效。

持久协作基础见 [Issue-first v3 历史计划](./multica-issue-collaboration-plan.md)。v4 终态模型、状态与 Runtime 约束见 [统一执行与编排契约](./controlplane/orchestration-v4.md)，Issue 语义见 [协作终态契约](./controlplane/collaboration-contract.md)，运行和恢复见 [协作控制面运维手册](./controlplane/issue-collaboration-runbook.md)。

# Agent Application 与 Runtime 生命周期

Agent 身份与运行方式分离。控制面通过 `RuntimeBindingResolver` 为 AgentTask 固定 backend snapshot：

- Managed：查找或创建 Managed Session；
- External：选择健康 AgentInstance，通过 ASDP 投递 ExecutionAttemptCommand；
- Hosted：创建 ExecutionAttempt，由 Runtime Host 使用 lease/fencing 领取；
- Launched Application（未来）：由独立 deployment/launcher 管理应用副本，Ready 后仍注册成 External AgentInstance。

Launcher 不拥有 Issue、Comment 或 AgentTask 状态机。RuntimeProfile 不保存明文 Secret；workspace 按 tenant/task 隔离；运行结果必须通过 task-scoped API 写回 result Comment/Artifact。

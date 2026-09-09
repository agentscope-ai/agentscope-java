# ADR-0001：Issue-first 协作与统一 AgentTask

- 状态：Accepted
- 日期：2026-08-26

## 决策

Issue 是长期工作和验收的唯一主对象，Comment 是人和 Agent 的持久通信，AgentTask 是一次执行义务，ExecutionAttempt 是一次物理尝试，Team 是持久 leader/worker roster。Managed、External、Hosted 由 RuntimeBindingResolver 统一接入。

## 约束

- 协作事实只写 Issue/Comment/Activity；Session transcript 不是事实源。
- Agent 定向 Comment 必须有 route，并关联 Task/input 或明确 blocked outcome。
- Comment 路由、Task/input、Inbox、Activity、outbox 在同一事务提交。
- 运行时只通过 task-scoped credential 读写被授权 scope。
- Artifact bytes 使用 provider SPI；数据库只存 metadata/link。
- Team 分工使用 child Issue 或 mention，不建立第二套消息/任务板。
- 不保留未发布设计的兼容层、双写或旧 API。

## 结果

系统可以跨 runtime 重试而不改变 Issue 事实；同一 discussion 可由人、Managed Agent、External Agent 和 Hosted Agent 重建；所有启动、输入、输出和失败都有因果链与审计记录。

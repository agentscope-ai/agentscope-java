# Issue 协作控制面运维手册

## 健康与核心信号

检查 `/readyz`、PostgreSQL、Artifact shared storage、ASDP 连接和 Runtime Host heartbeat。重点指标：

- `agentscope_comment_routes_total` 按 outcome；
- `agentscope_agent_task_transitions_total`；
- `agentscope_agent_task_input_age_seconds`；
- `agentscope_execution_attempt_transitions_total` 与 duration；
- outbox、dead-letter、SLA/失败 Inbox 数量。

## 常见恢复

1. Comment 已提交但未执行：查看 Comment routes；queued/coalesced 必须可追到 Task/input，blocked 查看 reason 和 human Inbox。
   每个 queued Task 应有 `agent-task.queued.v1` outbox。worker 会自动调用 RuntimeBindingResolver；不要依赖人工 `dispatch` 才能启动。重复事件在 Task 已离开 queued 后安全跳过。
2. Input 未 ack：dispatcher 会退避重试；达到上限进入 dead-letter。修复 runtime 后用 `POST /agent-tasks/{id}/inputs/replay` 人工重放。
3. Runtime 失联：startup/heartbeat/lease 超时后 sweeper 将旧 ExecutionAttempt 终态化；可重试基础设施故障在同一 AgentTask 创建新 Attempt，business failure 由 node policy 决定是否创建新 Task。历史 Attempt 永不复用或覆盖。
4. outbox dead-letter：修复 sink 后审阅 human Inbox；事件事实仍可从 Activity/数据库重建。
   如果错误是 `no runtime policy`，为对应 Agent 配置 ordered `AgentRuntimePolicy`，或配置 RunNode/Team member override；恢复 candidate 所需的健康 backend 后重放 outbox/input。默认 12 次、最大 5 秒退避可在约 1 分钟内产生 attention。
5. Artifact 失败：核对 checksum、provider mount、tenant/task scope 和 expiry；不要复制 runtime 本地绝对路径。多副本 Helm 部署必须设置 `artifact.persistence.enabled=true` 并使用 RWX StorageClass，或通过 `artifact.persistence.existingClaim` 指向共享 RWX PVC。
6. WebSocket 断开：Console 自动退避重连并继续 REST polling，不影响协作正确性。

Agent 内部诊断优先使用 task token 调用 `/mcp/collaboration` 的 `task.get`、`issue.get` 和 `issue.comment.list`；MCP、REST、CLI/SDK 读取的是同一数据库事实。不要向 human Agent 共享登录 token，也不要把 task token 放入 Comment 或 Artifact。

## 数据库与发布

开发期 schema 允许重建。生产 migration 禁止 FK/cascade；每个索引单独 `CREATE INDEX CONCURRENTLY`。发布前运行 Go、Java、Python、TypeScript、Console、manifest/Helm 全量验证和 `git diff --check`。

## SLO

- 100% agent-directed Comment 有 route；
- 100% queued/coalesced route 可追到 Task/input；
- 100% Agent result 可追到 source Task；
- runtime 可用时 99% Comment 在 5 秒内 queued/coalesced；
- dead-letter 在 1 分钟内创建 human attention；
- 任一 input 最终 processed、deferred、blocked 或 dead-letter。

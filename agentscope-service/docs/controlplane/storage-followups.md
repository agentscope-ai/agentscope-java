# 存储验收状态

> 状态：协作层事项已完成，并已纳入 v4 统一执行与编排终态

当前实现已覆盖事务化 Comment 路由、queued coalesce、running successor、input ack/retry/dead-letter/replay、outbox claim、ExecutionAttempt lease/fencing、scope 冗余、稳定 cursor、Issue archive/export、SLA attention 和 retention。后续新增公开契约必须遵循 [API 版本政策](./api-version-policy.md)，不能另建平行协作事实源。

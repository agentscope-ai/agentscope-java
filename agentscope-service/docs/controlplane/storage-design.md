# Issue 协作存储设计

> 状态：终态实现

PostgreSQL 保存 Issue、Comment、Mention、route、AgentTask/input、Team/member、Artifact metadata/link、subscriber、human Inbox、Activity、Approval、Automation、Runtime registry/execution、Session 和 durable outbox。文件字节由 Artifact provider 保存。

关系约束由应用事务验证；schema 不使用外键或级联。高频子表冗余 tenant/namespace，UUID 详情请求先解析持久化 scope 再做授权。每个索引都在独立 migration 中用 `CREATE INDEX CONCURRENTLY` 构建。

关键原子边界：

- Comment + mentions + routes + AgentTask/input + Inbox + Activity + outbox；
- AgentTask completion + input states + result Comment + successor reconciliation；
- runtime claim/lease/fencing；
- Approval decision 和 attention item。

Memory Store 实现同一 contract 用于开发与测试，生产必须使用 PostgreSQL 和共享 Artifact provider。

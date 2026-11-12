# AgentScope 运行协调迁移验证

[English](verification-coordination-20261028.en.md)

2026-10-28；主目录 `/Users/ken/agentcore/agentcore-managed-agents`，分支 `master`。未提交、未部署。

## 改动与保留的保障

这次补齐的是访问边界迁移，原有核心保障继续保留。Brain 通过 Session Service 的 `/prepared/coordination` 接口访问运行账本，复用原事务与状态转换。

| 范围 | 迁移结果 | 保留的保障 |
| --- | --- | --- |
| 进程租约、Turn 认领与输入关闭 | Session Service 校验进程身份并执行事务；Brain 注册、续租和提交认领请求 | 丢响应重试沿用原身份；过期进程不能复活；换 owner 后旧 epoch 不能写；续租不延长执行片段的固定期限 |
| Session 队列与输入读取 | Brain 使用远程协调接口 | 保留队列 generation、dispatch token 和输入交付记录 |
| FIFO ACK | `SessionTurnAckLedger` 归 Session Service；Broker ACK 仍由 Brain 消费者发送 | 终态后才可 ACK；账本不跨 Broker RPC 持锁；消费者退出或丢响应后可接续重试 |
| MCP 与子任务 | Service 保存冻结的 MCP 描述和子任务关联，Brain 远程读取 | MCP 发现及连接仍在 Brain，保留本地命令/workspace；子任务由已提交的工具意图创建 |

工具调用原子提交、Hands 可靠接单与 ACK 顺序、未知执行结果处理、结果与恢复通知一起提交均未改变。接口、Schema、样例和中英文职责文档已同步。

## 验证结果

- `mvn -B -ntp -T1 clean verify`：通过；本次 reactor 共 1,378 项，1,001 通过、377 跳过，0 失败/错误。按本次构建汇总计数，未纳入旧目录的历史报告。
- `mvn -B -ntp -T1 spotless:check`、`git diff --check`：通过。
- protocol `make check`：9 个 Schema、17 个 HTTP 操作、102 个正例与 29 个反例通过；Node.js 的 4 组 JCS 与 4 组身份向量通过。
- 新增 `SessionCoordinationHttpTest` 7 项均通过：丢注册/认领响应、进程接管、固定期限、ACK 接续、队列代次、MCP 本地发现及远程冻结、子任务关联。使用真实 HTTP 与 H2，Brain 协调客户端未传入 DataSource。
- `AgentScopeRemoteFlowTest` 的短工具与挂起续跑两项均通过；使用真实 AgentScope 和 HTTP，认领/输入协调也经过远程接口。模型、消息传输和外部工具使用测试替身。
- 打包检查：ACK 账本实现在 Session Service jar；common 仅保留 ACK 接口；Brain 不包含 Session Service/Tool Worker，Agent Service 不包含 Adapter/Tool Worker，Tool Worker 不包含 Adapter/Session Service。

## 尚未验收

本轮没有运行真实 PostgreSQL、RocketMQ、Redis 与 Sandbox 的部署及故障验收。Brain 的本地 JPA 目录/记忆和共享基础设施装配仍依赖 DB；Redis 短租/输入交付、MQ 消费及 Broker ACK 仍是其运行职责。本次完成上述 AgentScope 协调路径，不代表全部 Brain 已无数据库依赖。

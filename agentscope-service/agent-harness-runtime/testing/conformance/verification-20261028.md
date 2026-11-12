# 本轮验证记录

[English](verification-20261028.en.md)

目录 `/Users/ken/agentcore/agentcore-managed-agents`，分支 `master`。代码保留在主目录，未提交、推送或部署。

| 检查 | 结果 |
| --- | --- |
| Agent Service、Java Brain、Hands 的 Maven package（Java 17 目标，`-T1`） | 通过 |
| `mvn -B -ntp -T1 spotless:check`、`git diff --check` | 通过 |
| 定向 Java 用例 | 99 项通过：提交/恢复、工具结果/乱序/迟到结果、回执、原生解释隔离、跨语言摘要、Service 事务与预算、Java 服务装配 |
| TypeScript | 5 个包构建通过，10 项测试通过 |
| protocol | 8 份 Schema、14 个 HTTP 操作、82 个正例、23 个反例与身份/JCS 向量通过 |
| PI durable | 使用真实 1.4.0 Harness/Storage 重开根会话；持久化确认前不可见，确认失败不继续写，恢复后 ID 不复用 |
| Codex | 本机 0.163.0-alpha.11.1 app-server 完成 initialize 探测并退出；未调用模型，未验证 durable 工具能力 |
| Node 生产包 | `pnpm deploy --legacy --prod` 生成本地包，包内可加载 PI Adapter |

Service 的故障用例覆盖：旧租约不能新提交、原提交回执可重查、工具授权与原生状态同提交、Brain 退出后接受原工具结果、新 owner 恢复读结果、进程租约/固定期限校验、同模型预留幂等。Schema 校验与单元测试不等于真实 MQ ACK 故障验收。

命名审计未全通过：现有三份 SQL 的冻结摘要和四处旧源码链接仍与检查基线不符；这些 SQL 本轮未修改。目录迁移引起的源码路径和路由清单已更新，未改写历史摘要来隐藏问题。

未执行全仓 `clean verify`、Docker 镜像构建、真实 PostgreSQL/MQ/Redis 故障注入和模型端到端验收。Java 调度器的数据库依赖、PI 能力范围、完整快照上限和 Codex 门禁见[实现映射](../../protocol/implementation-map.md)。

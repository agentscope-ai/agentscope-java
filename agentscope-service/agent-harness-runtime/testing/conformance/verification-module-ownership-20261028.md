# Java 模块职责迁移验证

[English](verification-module-ownership-20261028.en.md)

目录更新（2026-11-02）：下文记录当时的模块结构。原 Session Service 子模块现已合并到 `agent-service/src/`，相关跨角色联调测试也归入 Agent Service；历史验证结果不代表本次合并的验证结果。

2026-10-28；主目录 `/Users/ken/agentcore/agentcore-managed-agents`，分支 `master`。未提交、未部署。

## 改动

- AgentScope 的 SessionLog 接入、执行/恢复、checkpoint 准备、模型捕获及工具适配归 `java/adapter-agentscope`，删除 common 的 Adapter SPI 加载路径。
- `agent-service/session-service` 内部库承载原平台提交事务、发布/结果补偿、恢复通知、生命周期及投影流程；仍与 Agent Service 同镜像、同 JVM。
- Tool Worker 承载消费、控制、Sandbox 绑定及执行账本。共享执行器通过 `ManagedToolWorkStore` 调用取消策略和结果收尾，不再识别具体 Hands 实现类。
- 跨组件测试显式组合实现，生产依赖保持分离；Tool Worker 部署入口改为 `*-exec.jar`，启动脚本与 Docker 构建选择同步调整。
- 原事务算法、原生载荷、工具身份和 ACK 顺序保持不变。共享账本访问仍在 common，Java 旧调度器仍需要 DB/Redis/MQ。

## 结果

- `mvn -B -ntp -T1 clean verify`：通过，当前 reactor 共 1,359 项测试，985 通过、374 跳过、0 失败/错误。未计入旧 `agent-harness-runtime/target` 中的历史报告。
- `mvn -B -ntp -T1 spotless:check`、`git diff --check`、`bash -n managed-agents/scripts/dev-up.sh`：通过。
- Jar 内容检查：common 不包含 AgentScope 执行器、Hands 执行账本或提交事务；对应实现只在所属模块中。Brain 生产包不包含 Session Service/Tool Worker，Agent Service 不包含 Adapter/Tool Worker，Tool Worker 不包含 Adapter/Session Service。
- 回归覆盖原子提交、原回执重试、旧 owner、短/长工具、丢响应、挂起恢复、输入/预算、子任务、MCP、私有 workspace 与三方启动装配。

完整检查中修正了一条过时的 MQ 角色断言：Session Service 接管发布后，两种队列模式均需允许工具命令与恢复通知。

额外命名审计仍有 7 项既有问题：3 个 SQL 文件与冻结摘要不符，以及中英文文档中指向已不存在的 `ManagedPostgresObjects`、`ManagedContinuationLoop` 的 4 个链接。本轮未修改这些 SQL 或放宽审计；新增迁移涉及的源码链接已更新。真实基础设施故障验收、镜像构建与部署尚未执行。

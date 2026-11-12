# Harness 行为回归基线

[English](README.en.md)

Java HTTP 平台接口最新实施与验收见[阶段记录](verification-java-platform-http-20261030.md)：无平台存储装配、任务回调、共同租约、原生工具与恢复；明确列出未部署及 owner 定向控制缺口。

AgentScope prepared 模型选择、工厂接线及 OpenAI / Anthropic 原生请求验证见[AgentScope 模型配置记录](verification-agentscope-models-20261029.md)。

通用模型目录、PI 凭据引用及 Codex 原生参数的验证见[模型绑定记录](verification-model-bindings-20261029.md)。

有效配置身份、摘要校验及 PI 从封存定义到原生请求的验证见[配置与组装记录](verification-configuration-20261029.md)。

跨内核持久化消息、模型 span、真实 usage 和大消息对象引用的验证见 [verification-public-events-20261029](verification-public-events-20261029.md)。在线文本通道的定向验证见 [verification-previews-20261029](verification-previews-20261029.md)。

本目录记录 Java、Session Service 和 TypeScript 的共享行为验收。离线字段与样例校验仍由 [protocol](../../protocol/README.md) 维护。

迭代开发默认只运行受影响模块编译、协议校验和关键路径测试；以下全仓与真实基础设施命令用于阶段性或最终验收，不要求每次改动重复执行。Managed Agents 尚未发布，不为历史格式兼容新增测试。

AgentScope 最新验证记录见 [verification-agentscope-20261028](verification-agentscope-20261028.md)。

最新模块职责迁移验证见 [verification-module-ownership-20261028](verification-module-ownership-20261028.md)。

最新运行协调迁移验证见 [verification-coordination-20261028](verification-coordination-20261028.md)。

跨 Brain、Hands、平台存储的集成用例位于 Agent Service 测试中；它们通过测试依赖组合各角色，生产包不因此互相依赖。

本轮收敛验证见 [verification-boundary-convergence-20261028](verification-boundary-convergence-20261028.md)。

## 必须保留的行为

平台事务与跨角色联调测试主要位于 `managed-agents/agent-service/src/test/java/`；不依赖 Session Service 实现的 Adapter 单元测试保留在 `managed-agents/agent-harness-runtime/java/adapter-agentscope/src/test/java/`。

| 场景 | 现有/新增测试入口 | 验收要求 |
| --- | --- | --- |
| AgentScope 跨 HTTP 完整链路 | Agent Service 测试 `AgentScopeRemoteFlowTest`、`AgentServiceAppTest` | 真实内核及 HTTP、短/长工具、原结果恢复、丢回执、输入/预算、私有存储与鉴权 |
| 远程运行协调 | Agent Service 测试 `SessionCoordinationHttpTest` | 丢响应重试、过期进程隔离、固定期限、队列代次、ACK 接续、MCP 本地发现及子任务关联 |
| 同提交重试、响应丢失、旧 owner | `ManagedSessionStoreTest`、`ManagedPostgresSessionStoreIntegrationTest` | 原回执返回且只提交一次；不同内容冲突；准备后换 owner 也不能提交 |
| Adapter 与事务分离 | `ManagedSessionStoreTest.platformTransactionConsumesExplicitChangesWithoutReadingNativePayloads` | 平台变化能够提交，不依赖 AgentScope 原生载荷解析 |
| checkpoint、任务与 Outbox 回滚 | `ManagedSessionStoreTest.adapterEvidenceCannotOverrideDurableToolIdentityAndRollsBackTheWholeBoundary`、`ManagedJournalStoreTest` | 不能用 Adapter 证据覆盖数据库中的执行身份；失败不留下部分提交 |
| 执行/ACK、unknown、取消 | `ManagedToolWorkerReliabilityTest`、`ManagedToolWorkerLoopTest` | 可靠接单后才可 ACK，正常消息路径确认 ACK 后启动；未知动作不盲目重跑 |
| 乱序结果、晚到结果、结果与唤醒 | `ManagedToolWorkerReliabilityTest`、`ManagedToolResultDeliveryIntegrationTest` | 缓冲结果不提前就绪；保留原执行结果；结果与 Resume 一起提交 |
| 结果先到、挂起、恢复交接 | `ManagedSuspensionsTest`、`ManagedResumeHandoffTest`、`ManagedPostgresResumeHandoffIntegrationTest` | 无丢失唤醒；可靠接管或延后才 ACK |
| 原生恢复、输入、模型与预算 | `ManagedSessionStoreTest`、`ManagedToolRecoveryTest`、`ManagedJournalStoreTest`、`ManagedBudgetStoreTest` | 不重做已确认工具；结果应用可恢复；输入、模型结果和用量不漏记/重复结算 |
| 收尾、私有 workspace 与投影 | `ManagedSessionStoreTest` | frame/模型/私有写回执继续可恢复；公开投影不改变原生恢复位置 |
| 跨语言身份与固定字节摘要 | `HarnessProtocolVectorsTest` + protocol `make check` | UTF-16 长度分帧、UTF-8、SHA-256 与固定样例一致；包含实际 Java JCS 编码器 |

这些测试是行为基线，不要求其他语言复制 Java 类名。PI/Codex 通过等价的行为用例，并额外验证其原生恢复、工具拦截和结果回填。

## Codex Rust

从仓库根目录运行（需 Rust 1.95.0 和 protocol README 中的 Python 环境）：

```sh
make -C managed-agents/agent-harness-runtime rust-check
cd managed-agents/agent-harness-runtime/rust
MANAGED_CODEX_TRANSCRIPT="$PWD/target/codex-transcript.json" cargo test --locked -p managed-adapter-codex --test durable_roundtrip
/tmp/managed-agents-protocol-venv/bin/python scripts/validate-transcript.py target/codex-transcript.json
```

`durable_roundtrip` 使用真实 codex-core，覆盖双工具部分结果、新 owner、丢回执、SIGKILL 后跨进程恢复，以及成功/取消之后的下一用户 Turn；验证原线程、原 Turn、原 call_id 和原结果。模型 HTTP、Session Service transport 与 Hands 结果由测试替身提供。`runtime/tests/recovery.rs` 覆盖授权、确认、预算、晚到回执和身份校验；`proxy` 验证未授权本地工具在进入内核前被拒绝。容器/真实 MQ 和外部工具验收仍需另行执行。

## 执行方式

从仓库根目录：

```sh
mvn -B -ntp -T1 clean verify
mvn -B -ntp -T1 spotless:check
make -C managed-agents/agent-harness-runtime/protocol check PYTHON=/tmp/managed-agents-protocol-venv/bin/python
```

协议校验环境安装见 protocol README。定向 Java 回归：

```sh
mvn -B -ntp -T1 -pl managed-agents/agent-service,managed-agents/agent-harness-runtime/java/adapter-agentscope -am \
  -Dtest=HarnessProtocolStoreTest,HarnessProtocolVectorsTest,SessionStoreProtocolTest,ManagedSessionStoreTest,ManagedJournalStoreTest,ManagedBudgetStoreTest,ManagedSuspensionsTest,ManagedToolWorkerReliabilityTest,ManagedToolRecoveryTest \
  -Dsurefire.failIfNoSpecifiedTests=false test
```

真实 PostgreSQL 用例使用 `MANAGED_DATABASE_TEST_URI`，测试创建和清理独立临时数据库；未配置时会跳过。使用专用测试实例，凭据和启动方式见 [基础设施测试说明](../../../tests/managed-mq-redis/README.md)。真实 MQ/Redis/进程故障用例也受环境开关控制，须分别报告通过和跳过，不能以 Maven 成功代替这些验收。

```sh
cd managed-agents/agent-harness-runtime/typescript
pnpm -r build
pnpm -r test
```

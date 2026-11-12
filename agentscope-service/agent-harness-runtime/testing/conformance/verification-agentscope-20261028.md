# AgentScope 链路验证

[English](verification-agentscope-20261028.en.md)

2026-10-28，在 `/Users/ken/agentcore/agentcore-managed-agents` 的 `master` 工作区完成；未提交、推送或部署。前一次跨语言改造记录保留在 [verification-20261028](verification-20261028.md)。

## 结果

AgentScope 的执行与恢复主链路已通过本地集成回归。修复内容见[实现映射](../../protocol/implementation-map.md#agentscope-完整链路核对)。

- `AgentScopeRemoteFlowTest`：真实内核、HTTP、H2 事务和 Hands 生产接单/执行账本代码。短工具走直接返回；长工具走 Session 队列、挂起、Resume 和重建 Agent。两者均确认工具只启动一次、模型调用两次、输入已应用、预算预留不重复、最终 checkpoint 与公开投影完成。
- 注入提交成功后的 HTTP 503，确认 Writer 先查原回执。验证远程 workspace 完整保存时间戳、读取原回执、写入不重放、CAS 冲突和旧 owner 拒绝。
- `AgentServiceAppTest`：真实 Spring HTTP 服务验证内部 Bearer 鉴权、协议 JSON 编解码和 2 MiB 原生对象往返。
- 原有回归继续覆盖业务失败/基础设施失败区分、取消与外部答复、预算拒绝、旧 owner、checkpoint/工具原子提交、unknown 和迟到结果。

本轮定向 Java 用例共 **102 项通过**（多次定向运行，非全仓测试）：`ManagedSessionStoreTest` 50、`HarnessProtocolStoreTest` 5、`AgentScopeRemoteFlowTest` 2、`ManagedFailureIntegrationTest` 16、`ManagedToolRecoveryTest` 6、`ManagedToolWorkerReliabilityTest` 17、`AgentServiceAppTest` 4，以及 Brain 装配测试 2。

| 检查 | 结果 |
| --- | --- |
| Agent Service / AgentScope Brain / Hands 的 Maven package，`-T1`、Java 17 目标 | 通过 |
| 全仓 `spotless:check`、`git diff --check` | 通过 |
| Compose 静态配置解析、本地启动脚本语法 | 通过 |
| 生产 JAR 内容 | AgentScope Adapter 仅在 Brain 包中；Agent Service 只在测试时依赖它 |

快速重跑 HTTP 链路：

```sh
mvn -B -ntp -T1 -pl managed-agents/agent-service -am test \
  -Dtest=AgentScopeRemoteFlowTest,AgentServiceAppTest \
  -Dsurefire.failIfNoSpecifiedTests=false
```

## 尚待整体验收

测试的模型、沙箱和 MQ/Redis 传输是替身，数据库为 H2；没有执行全仓 `clean verify`、容器部署、真实 PostgreSQL/RocketMQ/Redis/Sandbox、OSS 或模型服务验收。下一阶段优先验证真实环境中的实例恢复、SSE 断线续读、Brain/Hands 退出、服务中断和迟到结果。Java 原调度、MCP 与子任务协调仍依赖共享 DB/Redis/MQ，本轮不删除这些依赖。PI/Codex 没有扩展。

# Brain–Hands 队列执行

[English](queued-hands-plan.en.md)

Java Durable 已实现本计划。当前跨仓库范围见[实施计划](../archive/platform-implementation-plan.md)和[验证记录](../verification/verification.md)。

- Runtime、HarnessAdapter、SessionBackend、Executor 仍为四个主要概念。
- Hands 运行内核是可嵌入的 WorkerRuntime；进程内 Brain 通过 `LocalWorkerExecutor` 接入，
  也可经 HTTP 调用或部署为独立 MQ 服务。
- RocketMqRemoteExecutor / RocketMqWorker 使用由 namespace 与 route 自动派生的命令、控制、更新三条 NORMAL Topic。控制使用独立消费者/消费组，执行背压不阻塞取消。
- 持久工具意图授权执行，Hands 提交受理后 ACK，输出/结果发布持久化并补发；Brain 去重连续源事件并修复 inbox 到 Session 的提交。
- CANCEL 先到会留下墓碑。补发不会重跑工具；执行身份与 fencing 保护回执，不伪造 UNKNOWN 或盲目重复外部副作用。
- 输出使用字节游标、明确缺口与封闭终页，大信封通过共享 ObjectStore 的作用域/摘要引用传递。SSE 读取 SessionBackend 公共事件，而非直接转发工具消息。
- Brain/Hands 可以分别使用存储；Worker 副本共享执行账本，Brain 副本共享交付记录和 Backend。SDK 客户端、存储和 Provider 归宿主管理。
- SQLite/HTTP/PostgreSQL 契约、SDK 故障测试、真实 RocketMQ Broker/Proxy 与独立 JVM 恢复均已通过；生产云、集群和负载验收单独进行。

见[echo 独立进程示例](../../durable-examples/queued/queued-hands/README.md)和
[队列 Sandbox Hands 示例](../../durable-examples/queued/sandbox-hands/README.md)。不依赖 Managed Agents。
新增 Sandbox 示例的离线协议测试与真实 Broker 验收分开记录；上面的既有框架验收不等于新增示例的联调结果。

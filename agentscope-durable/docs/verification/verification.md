# Durable Java 实现验证记录

[English](verification.en.md)

> 本文保留原有日期、结果、数字和命令，仅作为当时版本的历史证据，不是当前验收通过声明。
> 平台仓库与真实服务的历史验证不属于当前复测；当前检查入口见[库 README](../../README.md)。

验证日期：2026-11-08。代码位于 `/Users/ken/agentscope-3/agentscope-java`，分支 `automatic-acr-release-durable`。平台代码位于 `/Users/ken/agentcore/agentcore-managed-agents`，分支 `durable-ma`。未创建代码副本，未提交、推送或部署。导入清单仅保留历史来源，不代表当前模块列表。

## 验证结果

| 检查 | 结果与范围 |
| --- | --- |
| Durable 独立 reactor | 12 个 reactor 模块（10 个 JAR），Java 17 编译目标；编译、打包、安装和 Spotless 通过。161 项测试中 160 项通过，Java-only 模式跳过 1 项可选 Pi HTTP 测试。 |
| 状态与恢复契约 | SQLite 与 HTTP 共用契约，覆盖回执、增量原生日志、fencing、认领响应丢失、checkpoint/恢复/fork、私有资源、不可变对象引用、输入/信号/取消和绑定父 Session 的子 Run。相同契约也在真实隔离 PostgreSQL 17 上执行。 |
| 原生 AgentScope | 真实 Harness/ReAct 与确定性模型验证回放、权限、外部结果、工作区与子 Run、固定收尾上下文和持久记忆维护；不代表任意 Java 调用栈重放。 |
| Hands / MQ | Worker 回执、取消先到、发布/ACK 丢失、重复消息、独立控制消费、连续输出/缺口、封闭终态；真实 RocketMQ 5.3.2 Broker/Proxy 与独立 JVM 恢复示例也已通过。 |
| Provider 适配 | MCP 与并行批次、本地 POSIX Sandbox、数据库/文件/S3/OSS 对象适配；S3/OSS 使用受控 fixture，没有调用付费云服务。不可查询的副作用保留 UNKNOWN。 |
| 独立性 | 依赖树没有 Managed Agents、AgentCore 或 Service 制品；最初隔离缓存验证从当前源码构建框架依赖，不需要平台类。BOM/shade 同步通过（57 个扩展、71 个 BOM 模块）。 |
| 平台 | Managed 独立 Java reactor、Go 控制面测试/构建、Console 测试/lint/构建、SDK 与部署测试通过；详细范围见平台仓库验证记录。 |
| 进程验收 | 三个打包 JVM + 真实 PostgreSQL/RocketMQ：工具派发后强杀 Brain、重启 Hands、恢复原 Run；两次主模型调用、一次辅助调用、一次工具副作用。验证事件续接、二进制文件、产物发布/删除、预算、恢复/fork、Go/Python/DSH 客户端、归档/激活、删除等待取消与物理清理。 |
| 协议 | 共用版本化 schema 与投影 fixture 保留字符串序号和结构化内容；TypeScript 投影测试通过，不声明其他语言运行时功能一致。 |

## 复现

```sh
python3 agentscope-durable/scripts/verify-independent.py --java-only
# 已安装当前 Core/Harness 制品时：
mvn -B -ntp -T1 -f agentscope-durable/pom.xml -Pdurable-standalone clean verify
# 在隔离 PostgreSQL 上执行相同契约：
mvn -B -ntp -T1 -f agentscope-durable/pom.xml -Pdurable-standalone \
  -Dtest=DurableBackendTest,HttpBackendContractTest,BackendAssemblyTest,QueuedExecutorTest,RocketMqExecutionTest \
  -Dsurefire.failIfNoSpecifiedTests=false \
  '-Ddurable.test.jdbcUrl=jdbc:postgresql://localhost:5432/test?user=test' test
bash .github/scripts/check-shade-and-bom-sync.sh
```

另见 [Hands 示例](../../durable-examples/queued/queued-hands/README.md)与[实施计划](../archive/platform-implementation-plan.md)。平台进程测试位于平台仓库 `managed-agents/tests/durable-platform/run.py`，只应连接隔离测试数据库和 Broker。

## 验证边界

AgentScope 根目录 `clean verify` 仍在 POM 解析阶段被重复 `agentscope-service` 的既有父模块错配阻塞。用户要求忽略该目录，因此没有修复它，也不声明根 reactor 与最终聚合分发包通过。

未验收真实云 Sandbox、OSS/S3 账号、Kubernetes 部署、生产故障切换/负载基准，未发布制品。Provider UNKNOWN 对账与数据保留/GC 是明确的运维责任。平台暂不支持的配置在准入时拒绝，详见平台文档；不声明其他语言运行时完成或旧数据迁移。

## 本轮设计复核的增量验收

原文对照分析见[实施计划中的复核表](../archive/platform-implementation-plan.md)。
本轮新增 19 项行为检查（共享 Backend 契约计入 SQLite/HTTP 两种执行），完整 Java reactor 的
161 项中 160 项通过，唯一跳过项仍为可选 Pi HTTP 测试。

- 静态就绪扫描按定义/状态版本先筛选再限量；不兼容宿主不隔离其他宿主可恢复的 Run。
- 动态/多版本能力查询不认领 Run、不调用模型，错误版本明确拒绝；返回对象通过同一 Schema 校验。
- 直接 Brain 与 Worker 共用恢复判断：恢复所得句柄用于取消并在取消响应丢失后保留；查询故障不阻断独立取消；幂等但不支持查询的启动可复用原身份；非幂等未知操作不重做。
- UNKNOWN 核实覆盖所有权、原结果回执、内容冲突、旧 owner 拒绝、输出 gap、原 MQ inbox/outbox、Backend 接收与原生结果应用；相同测试也在真实 PostgreSQL 上运行。
- 独立 Node JSON 实现生成命令身份 fixture，Java 校验一致；重复字段、多个根值和丢精度数值被拒绝。TypeScript 共同投影测试仍为 5 项通过，不宣称其 Runtime 已实现本轮新增 Java API。
- 使用既有独立缓存重新构建 12 个模块；缓存无平台制品，12 份依赖树无 Managed/AgentCore/Service 依赖。BOM/shade、16 项文档测试、文档构建和链接检查通过。

人工核实是框架宿主接口，平台尚未开放管理 API/审批 UI。证据核实、操作者授权、对象保留/GC 与真实云验收仍由部署方完成。

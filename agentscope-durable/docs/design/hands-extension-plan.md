# 通用 Hands 扩展实施计划

[English](hands-extension-plan.en.md)

> 本文保留既有实施结果、数字和命令作为历史证据，不代表当前版本重新验收通过。
> 历史平台与跨语言记录不是当前客户端支持声明；当前模块和检查入口见[库 README](../../README.md)。
> Sandbox 示例的当前启动器为 `agentscope-durable/durable-examples/integrated/sandbox-hands/run.sh`；原命令仅供追踪。

状态：本轮 Java / AgentScope 改造与本地验收完成。依据用户确认的 Tool Worker 提炼分析，先完成通用 Durable 扩展，再收敛 Managed Agents。仅 Java / AgentScope；不恢复旧协议或重复状态机，不新增强制服务，不改变平台到框架的依赖方向。

工作目录为 `/Users/ken/agentscope-3/agentscope-java`（`automatic-acr-release-durable`）和 `/Users/ken/agentcore/agentcore-managed-agents`（`durable-ma`）。保留已有改动；忽略重复 agentscope-service；不提交、推送或部署。

## 交付与验收

| 编号 | 必须交付 | 验收证据 | 状态 |
| --- | --- | --- | --- |
| H1 | 统一冻结工具绑定，Brain schema 与 Hands 目标校验，AgentScope 原生工具 Executor | 版本/目标变化拒绝、同一调用身份、原生结构化结果与权限集成测试 | 完成：框架与平台冻结绑定、原生结果应用验证通过 |
| H2 | 可选 Sandbox 绑定扩展：持久创建/重连证据、保活、有界缓存、幂等清理和终止屏障 | 创建回包丢失不重建、接管原实例、并发创建/清理、停止进程不销毁环境 | 完成：通用扩展与平台适配、本地故障及跨进程接管通过；真实云验证单列 |
| H3 | MCP 有界连接管理、冻结描述校验和显式恢复契约 | 凭据轮换/引用生命周期、原调用查询、能力漂移拒绝、未知结果不重试 | 完成：连接/恢复契约及平台发现、凭据适配测试通过 |
| H4 | Worker builder、独立启动/恢复容量、退避、分阶段停机、状态与故障观测、无效 MQ 消息隔离 | 长调用不阻挡恢复、排空与强停、重启退避、隔离后 ACK、健康/积压可查询 | 完成：生命周期、隔离、排空、健康接线及真实进程验证通过 |
| H5 | 有序及有界并行批次，稳定子调用、取消、输出关联和聚合 | 限流、部分完成接管、取消不新增调用、输出可重放、清单授权 | 完成：有序/并行、恢复、取消及平台输出转发验证通过 |
| H6 | Managed Agents 仅保留配置、授权、凭据和环境策略适配，删除被替代代码/脚本 | 平台单一路径、构建及真实跨进程恢复测试 | 完成：平台 68 项测试、完整构建及真实三进程验收通过 |
| H7 | 独立示例、协议/中英文文档、独立构建和部署验收 | Java 17、格式、依赖审计、BOM、SQLite/PG/MQ、故障与结果应用闭环 | 完成：本轮 Java 必选构建、协议、示例、文档及本地部署验收通过 |

验收必须覆盖：checkpoint+intent 原子性、持久受理后 ACK、稳定 executionId、原执行查询、连续输出封口、结果接收与恢复待办原子性、原生结果应用与 checkpoint 原子性。授权不能因取消/查询而跳过，也不能用新的执行权限撤销阻止旧操作收尾。MQ 和持久执行并发分别配置。

self-hosted 出站领取协议仍作为后续可选方向，不在本轮必选交付中。真实云账号和生产负载验收必须单列，不能以本地 fixture 证明。

## 实施记录

- 2026-11-08：复核当前源码及构建布局，开始 H4。既有 BatchExecutor 是有序执行，不能以 Worker 并发配置声称批内并行；H5 将明确两种模式。

- 2026-11-08：H1 已接通 AgentScope 原生工具。通过 Toolkit 执行，保留参数校验、预设参数和上下文；新增按调用选择的异常透传，覆盖注解工具同步/Mono/Future。正常返回按原生终态规则解释，UNKNOWN 不重复执行；结构化结果及冻结绑定接回原调用。
- H3 新增 McpConnections 有界连接池、Lease 生命周期及配置轮换；McpExecutor 冻结主/控制描述，支持显式 McpRecovery 协议。启动、查询、取消共享稳定身份；原调用可在主工具变化后使用未变化的控制描述收尾。共用 Schema 已包含可选 MCP 契约。
- 验证：Core `verify` 2635 项（2626 通过、9 跳过）；Durable standalone 全模块 `verify` 180 项（179 通过、1 个可选 Pi HTTP 集成跳过）。本次使用 JDK 21 编译 Java 17 目标；实际 JDK 17 验收仍待完成。日志：`/tmp/durable-hands-core-full.log`、`/tmp/durable-hands-framework-2.log`。中英文文档测试 16/16、validate、broken-links 和 BOM 同步检查通过。TypeScript 中仅同步协议字段，build/test 通过，不代表新增 Java 能力的跨语言实现已完成。
- H2 已提供 SandboxBindings 和 LocalSandboxClient：持久创建身份、未知创建的证据核实、原实例重连、持久保活、有界缓存及终止屏障清理。解析配置失败不登记创建副作用；跨 namespace 绑定拒绝。查询/取消不创建资源，组件关闭不销毁资源；本地适配固定原目录，不支持以快照重建执行证据。真实云资源验收仍独立进行。
- H4/H5 已有启动/恢复容量、退避、隔离和批次实现并随上述全量测试通过；关闭竞争、真实 PG/MQ 和最终运维验收仍纳入 H7。H6 尚未开始，Managed Agents 源码本轮未修改。完整独立重建、部署示例和最终依赖审计均待最终验收，不以当前通过项替代。

- 2026-11-08：Sandbox 15 项测试通过，覆盖原实例接管、创建及删除丢回包、并发所有权、私有路径、配置解析失败和本地适配。Worker 11 项及 MQ 6 项通过，新增强停未运行队列任务释放容量、MQ 关闭等待回调及回调自关闭测试。日志 `/tmp/durable-hands-lifecycle-1.log`（Sandbox 通过，随后 Worker 编译命名冲突已修复）、`/tmp/durable-hands-lifecycle-2.log`。文档已说明五秒强停等待与宿主有界 IO 约束。H6 仍待执行；本记录不是整体验收完成声明。

- 实际 Java 17 验证：使用独立临时 JDK 17.0.21，`verify-independent.py --java-only` 从不含 AgentScope/Managed 缓存的仓库构建 Core/Harness 和全部 Durable Java 模块；195 项测试，194 通过、1 个可选 Pi HTTP 集成跳过，依赖审计通过。独立仓库 `/var/folders/b3/0cr5wc6n1rq5r7x3ts0xvt1c0000gn/T/agentscope-durable-maven-7r5sn1kw`；日志 `/tmp/durable-hands-independent-java17.log`。Core 单独 verify 2635 项（2626 通过、9 跳过），日志 `/tmp/durable-hands-core-java17.log`。
- 新增 `SandboxHandsExample.java` 与 `run-sandbox-example.sh`：实际两个 JVM 接管同一分离执行、单次 fixture 副作用、输出封口及清理通过。嵌入式例子的重复受理检查通过；MQ 示例编译通过。日志 `/tmp/durable-hands-sandbox-example-java17.log`、`/tmp/durable-hands-embedded-example-java17.log`、`/tmp/durable-hands-queued-example-java17.log`。PostgreSQL 配置下 JDBC 与 MQ 故障 fixture 共 40 项通过（后者不是实际 Broker），日志 `/tmp/durable-hands-postgres-java17.log`。中英文文档 16 项、434 页检查、BOM 同步通过。

- 真实 RocketMQ 5.3.2 + JDK 17 的独立 Brain/Hands 示例完成调用、结果应用及重复受理；两次 Brain JVM 均返回 `run_22777fe7-fb23-4bc3-83a6-d813120d7b66` SUCCEEDED。原始日志与核验文件位于 `/tmp/durable-rocketmq-platform/hands-java17-example-lis1f1we`。临时验证器最初误按简写输出解析 Run，已按实际 RunView 输出核对原始两份日志；示例自身均正常退出。该验收是实际 Broker 冒烟与重复受理，不替代故障 fixture 或平台三进程恢复验收。

- 框架源码、独立构建、协议、本地跨进程与 Broker 冒烟已有 Java 17 证据。H6 仍未实施：冻结目录接入、MCP Lease、Sandbox 策略适配、完整 batch 转发、旧脚本删除及 Managed 平台构建/进程验收均待完成。本轮未修改 Managed 源码。

- Harness 在同一实际 JDK 17 与独立 Maven 仓库下完成 verify：1161 项测试，0 失败、0 错误、18 跳过；日志 `/tmp/durable-hands-harness-java17.log`。本地验证用 PostgreSQL、RocketMQ 和 Worker 已停止，测试磁盘已卸载。

- H6 已开始实际替换 Managed 源码：冻结绑定贯穿 Brain/准入/Hands，MCP 发现冻结恢复描述并使用连接 Lease；Sandbox 改用 SandboxBindings/LocalSandboxClient，删除重复 managed-tools.py；批次启用有界并行及输出转发，增加共享后代删除屏障和 Spring 排空接线。正在执行 Java 17 平台构建与新增故障测试；首轮构建在 Spring Boot Health API 包名处失败后已按当前依赖修正。本记录不代表平台验收完成。

## 最终需求核验

- H1：ToolBinding / AgentScopeToolExecutor / AgentScopeAdapter；框架定义漂移与原生结果测试，加上平台准入摘要、Hands 预校验、真实 HTTP 与 MQ 结果应用。平台摘要独立保存在权限记录中，不解密配置执行事务授权。
- H2：SandboxBindings / LocalSandboxClient；框架 15 项 Sandbox 故障测试，平台共享环境、八层绑定、无创建查询/取消、关闭后接管、后代终止屏障和物理清理测试。
- H3：McpConnections / McpExecutor / McpRecovery；框架 9 项测试，平台连接隔离/轮换、恢复描述冻结、控制工具隐藏及非法参数不借用连接测试。真实 MCP 服务和云账号的能力仍由目标部署另行验收。
- H4：WorkerRuntime / RocketMqWorker；启动与恢复容量、持久退避、MQ 隔离、强停竞争、排空、状态接口均有框架测试；ManagedHandsLifecycle 与健康检查已接入，停机与重启经三 JVM 验证。
- H5：BatchExecutor；框架有序/并行、部分恢复与取消测试；平台明确 PARALLEL，测试证明两个叶子在任一个完成前启动，输出关联、封口与绑定保留。
- H6：ManagedSandboxes / ManagedHandsExecutors / ManagedMcpClients 只保留平台策略及组装，移除重复 managed-tools.py 和旧 settled HTTP 入口。九模块实际 Java 17 clean verify：68 项全部通过；43 项部署配置、5 项源码审计及命名/路由审计通过。
- H7：上文独立 Java 17 构建 195 项（1 项可选 Pi 跳过）、依赖审计、PG 40 项、独立例子、真实 Broker，以及本轮平台三 JVM 均有原始日志。成功 Run `run_8a9272e3-f236-491e-95b4-ae7ca1bacdfd`，两次主模型、一次辅助、一次工具副作用、17 条初始公共事件；包含 SDK、checkpoint/fork 和删除等待清理。

最新平台构建日志 `/tmp/durable-hands-platform-5.log`，独立格式检查 `/tmp/durable-hands-platform-spotless.log`，进程日志 `/tmp/durable-hands-platform-process.log`，原始进程证据目录 `/var/folders/b3/0cr5wc6n1rq5r7x3ts0xvt1c0000gn/T/managed-durable-process-h6ru0f8s`。目标生产云账号、Kubernetes/镜像和负载验收不在本轮必选范围，未以本地测试替代这些验证。未提交、推送或部署生产环境。

- 最终收尾：16 项文档测试、Mintlify validate/broken-links、BOM/shade 同步和两仓库 diff 检查通过；日志 `/tmp/durable-hands-final-doc-tests.log`、`/tmp/durable-hands-final-doc-validate.log`、`/tmp/durable-hands-final-doc-links.log`、`/tmp/durable-hands-final-bom.log`。本轮所有测试 JVM 已退出，PostgreSQL/RocketMQ 已停止，测试磁盘已卸载。

# Java Durable 与 Managed Agents 实施计划

[English](platform-implementation-plan.en.md)

状态：Java 执行主链路已完成，外部环境验收另列。用户授权先完成 Durable，再直接重构 Managed Agents；不保留旧协议、旧执行内核或双路径兼容。仅实施 Java / AgentScope。

工作目录：`/Users/ken/agentscope-3/agentscope-java`（`automatic-acr-release-durable`）与 `/Users/ken/agentcore/agentcore-managed-agents`（`durable-ma`）。保留既有改动，忽略 AgentScope 仓库中的重复 `agentscope-service`。不提交、推送或部署。

## 实施与验收

| 阶段 | 范围 | 验收 |
| --- | --- | --- |
| D1 | 分段事务存储、增量原生日志、就绪索引、多版本解析、认领回执、容量接纳与 drain | SQLite/PostgreSQL/HTTP 同一后端契约；长历史不随普通写入重写；丢认领回包恢复；容量与停机测试 |
| D2 | 对象引用、历史检查点、恢复/fork、输入应用与撤回、事务授权扩展 | 对象完整性与隔离；不可变提交；空闲约束；故障恢复；并发输入与撤回 |
| D3 | Hands 事件、连续前缀、输出缺口与重放、独立控制通道、大结果 | 重复乱序、取消早到、输出收尾、发布与 ACK 丢失、真实 Broker |
| D4 | AgentScope 结构化结果、模型/Finalization 恢复、工作区、子 Agent、MCP 批次、预览 | 真实 Harness 的恢复与取消；无权限绕过；部分批次接管；原始内容块保留 |
| D5 | 独立构建、依赖审计、双进程示例、协议与文档 | Java 17；零 Managed 依赖；相关测试、格式、BOM、全构建检查，明确外部阻塞 |
| M1 | 平台直接采用 Durable Backend、Java Brain 与 Hands；保留产品配置和策略 | 跨服务文本/工具/取消/恢复/SSE；Brain 无 DB/MQ 连接；单一执行状态机 |
| M2 | 删除被替代的内核、适配、协议、配置与测试；更新部署和文档 | 无旧启动开关和双路执行；平台构建与集成验收 |

Runtime、HarnessAdapter、SessionBackend、Executor 仍为四个主要概念。具体存储和传输属于扩展；平台身份、权限规则、定价、凭据和环境管理留在平台。所有任务/结果/输入/控制以持久事实为准，通知仅加速发现。

## 实施结果

D1–D5、M1–M2 的 Java / AgentScope 执行链路已实现，验收与部署边界见[验证记录](../verification/verification.md)。

- Durable 统一负责状态、原生日志/checkpoint、不可变对象引用、恢复/fork、输入、事件、fencing、队列执行与 Worker 回执；仍以 Runtime、HarnessAdapter、SessionBackend、Executor 为四个主要入口。
- Managed Agents 直接组合框架：Agent Service 托管 Backend 与平台策略，Java Brain 通过 HTTP 工作，Hands 执行 Provider，Control Plane 保留产品资源与授权。没有第二套平台执行内核或 Redis 协调层。
- MCP、Sandbox、叶子工具并行批次、工作区子 Run 和事务共享记忆已接入。资源定义在准入时冻结，实时权限和凭据由平台管理。
- 被替代的 Java 内核、TypeScript/Rust Brain、旧协议、启动开关及失效测试已删除。Console、Java/Python/DSH 客户端、Compose、Helm、schema Job 与 MQ 初始化使用新链路。
- 真实 PostgreSQL + RocketMQ + 三个打包 JVM 的重启、取消、资源和恢复验收通过，工具副作用只执行一次；SDK 也已访问实际打包服务验证。
- 不声明旧数据迁移、生产部署或云 Provider 验收完成。平台暂时明确拒绝 JEV、外部 skill repository、catalog multiagent 引用和 self_hosted 环境；支持冻结的工作区 skills/子定义及 cloud/local Sandbox。
- 重复的 `agentscope-service` 目录保持未修改，其既有父 POM 错配阻塞根 reactor；Durable 独立 reactor 和 Managed Agents reactor 可分别构建。

## 按原始设计复核（2026-11-08）

依据[原始 Durable 设计](https://aliyuque.antfin.com/ad3swf/gg3g1v/qecdop0f9bdu5irs?singleDoc)，本轮逐条检查 Java 实现和 Managed Agents 的实际接线。结论是继续保留四个主要概念，不增加统一推理循环，也不把平台身份、凭据或部署环境下沉到内核。

| 设计要求 | 当前实现与本轮处理 |
| --- | --- |
| 原生状态、执行事实、公开事件分开 | Adapter 私有 journal/checkpoint；Backend 保存受理、意图、结果和应用事实；公开 projection/cursor 不暴露原生状态。三种位置（原生位置、输出字节偏移、公开序号）保持独立。 |
| 一套 Backend 降低接入成本 | LocalBackend 完成 SQLite、对象和通知装配；生产使用 PostgreSQL/对象存储及可选通知。RecordStore 是高级 SPI，MQ 不是状态真相。HTTP 后端转发同一契约。 |
| Brain/Hands 分离 | Service 托管 Backend 和队列 inbox/outbox；Brain 只访问 HTTP；Hands 保存独立执行账本并执行 Provider。命令、控制、执行事件使用三个 topic；SSE 读取已持久化公开事件。 |
| 先保存、再执行；失去响应后接回原操作 | 已有原子 checkpoint+intent、提交回执和 fencing。本轮让 Runtime/Worker 复用 ExecutionRecovery，保留查询所得句柄，独立尝试已支持的取消；无查询但启动幂等的 Provider 复用原 ID。非幂等未知操作不重启。 |
| UNKNOWN 有核实入口 | 新增 ExecutionStore.reconcile：持有有效 Hands 租约，仅接受 UNKNOWN 的已核实终态和 evidenceRef，原子保存审计回执与 outbox。重试相同 ID 返回原结果；冲突拒绝；丢失输出以显式 gap 封口，再通过原结果链路恢复 Brain。它是宿主运维接口，不是新增的公开 RPC、MQ 控制命令或模型工具。 |
| 按兼容版本发现工作 | HarnessSelector 将就绪扫描限定到定义/状态版本索引，先筛选再限量；其他版本积压不会阻挡兼容任务。不兼容宿主保留工作可恢复性，不写入 runtime.error 等待。存储格式提升至 3，旧开发格式拒绝。 |
| 能力声明反映实际定义 | 新增 runtime.capabilities(definition)，按冻结版本解析动态 Harness，并拒绝不兼容状态；全局查询列出 resolvedHarnesses。能力查询不认领 Run、不启动模型。Managed Brain 的 HTTP 集成测试覆盖这一路径。 |
| 语言无关的协议身份 | Schema、规范化 SHA-256、稳定执行 ID 和投影 fixture 不依赖 Java 类。重复 JSON 字段、多值正文和规范化后损失精度的数值被拒绝；精确大数使用字符串。未来语言可独立实现接口，但必须复现相同身份与故障语义。 |
| Harness 保留自己的恢复边界 | AgentScopeAdapter 使用原生日志、middleware 和工具结果边界；Core/Harness 不依赖 Durable。共享 Backend 契约和本轮执行恢复/核实测试使用普通 Harness/Executor fixture，不依赖 AgentScope。第二个生产 Harness 的适配与验收仍是后续工作。 |
| 权限、预算、环境恢复 | 平台在受理/提交事务中冻结定义、检查和预留预算；凭据及 Sandbox 身份由平台管理。Durable 不保存可迁移进程镜像，不自动新建未知结果的 Sandbox。 |
| 公开读取、输出与保留期 | 快照+游标、提交后发布、断线接续、过期重同步已有契约。MQ 按连续源前缀传递输出与结果，不能合并跳序。直接 Executor 的 output 能力表示字节读取 API；不会自动把所有直接输出镜像为 Session SSE。保留/GC 策略由宿主选择。 |

宿主核实 UNKNOWN 的步骤：先检查 `reconciliation(request, requestId)` 回执；无回执时认领同一 Hands 账本项，核实 Provider 的原操作后调用 `reconcile(work, command)`。核实证据和操作者授权由宿主负责，不能仅凭超时推断成功、失败或停止。MQ 宿主使用与 RocketMqWorker 相同的 `TransactionalExecutionStore(records, executorName)`；操作完成后的普通 source event 仍经原队列回流，平台不得另行覆盖 Run 或 Session 结果。

Managed Agents 继续保留产品受理、租户授权、模型/工具定义、Vault、计费策略、Sandbox 供应和生命周期、控制台与产品 API。通用恢复算法、状态机、Worker 账本、可靠交付和事件协议只在 Durable 实现。平台的人工核实管理 API/审批界面尚未开放；本轮提供的是可供受控运维宿主组合的框架入口。

本轮不将“存在接口”视为云环境验收：真实云 Sandbox、OSS/S3 账号、生产负载/故障切换、其他语言 Runtime、第二个生产 Harness 和不同 Harness 间运行中迁移仍需独立证据。验证结果记录在[验证记录](../verification/verification.md)。

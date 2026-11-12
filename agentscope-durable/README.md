# AgentScope Durable

[English](README.en.md)

Durable 是可内嵌的 Java 框架，为 Agent 提供持久任务受理、原生恢复和独立工具执行。
它复用 Core/Harness 的推理循环、权限、ThreadJournal 与工作区，不依赖 Managed Agents。
当前为开发中的源码 API；开发检查不等于独立集成或生产验收。

## 从哪里开始

- 应用接入：[Java 使用指南](../docs/v2/zh/docs/harness/durable.md)。
- 用户操作、消息与事件：[对外交互契约](protocol/public-interaction-contract.md)。
- 权威 Schema 与共享 fixture：[协议入口](protocol/README.md)。
- 宿主接线与启动：[Spring Boot 示例](durable-examples/README.md)。

## 公共边界

| 边界 | 职责 |
| --- | --- |
| `DurableRuntime` | 装配、业务访问与生命周期；创建/查询 Session，调度和恢复 Turn |
| `HarnessAdapter` | 解释原生状态，将 Agent 调用与 Durable 提交边界连接 |
| `SessionBackend` | 持久事实、原子提交、幂等回执、租约/fencing 与事件回放 |
| `Executor` | 独立工具的启动、查询、取消和输出；如实报告未确认结果 |

Session 管理 Thread 目录与公共时间线；Thread 固定定义版本并按顺序执行 Turn。
Turn 是逻辑任务，Run 是一次调用，Step 属于一个 Run；恢复保留 Turn 身份。
API-only Runtime 不需要本地模型或 Agent 工厂，也不调用 `start()`；执行宿主显式启动。
引入库不会自动启动服务，关闭观察连接或 Runtime 不等于取消持久任务。

## 模块布局

| 目录 | 职责 |
| --- | --- |
| `durable-runtime` | 公共 API、调度/恢复、事务状态机、消息与事件协议 |
| `durable-worker` | 调用侧可靠交付、Hands 执行账本、恢复与批次执行 |
| `durable-adapters/durable-adapter-harness-agentscope` | 原生 ReActAgent/HarnessAgent 适配与 checkpoint codec |
| `durable-examples` | 独立的 integrated/queued 宿主示例，不作为库运行时依赖 |

Java groupId 为 `io.agentscope`。原生适配使用
`agentscope-durable-adapter-harness-agentscope`，不再有独立的 codec 制品。
`AgentScopeCheckpoints` 与 `AgentScopeCheckpointCodec` 已并入该 adapter；
checkpoint 验证、restore/fork 仍不构造或启动 Agent，但引入此制品会带入 Harness 依赖。

基础设施扩展均位于 [`../agentscope-extensions`](../agentscope-extensions)：

| 扩展目录 | 用途 |
| --- | --- |
| [durable-jdbc](../agentscope-extensions/agentscope-extensions-durable-jdbc) | SQLite 与 PostgreSQL Backend |
| [durable-http](../agentscope-extensions/agentscope-extensions-durable-http) | 公共/内部 HTTP 客户端、handler 与 SSE |
| [durable-sandbox](../agentscope-extensions/agentscope-extensions-durable-sandbox) | Sandbox 资源绑定、独立操作、输出与清理 |
| [durable-rocketmq](../agentscope-extensions/agentscope-extensions-durable-rocketmq) | Brain–Hands 队列执行与可选唤醒 |
| [durable-objects](../agentscope-extensions/agentscope-extensions-durable-objects) | S3/MinIO、OSS 对象存储 |
| [durable-mcp](../agentscope-extensions/agentscope-extensions-durable-mcp) | MCP 工具连接与显式恢复扩展 |

SQLite 不需要数据库服务。使用 PostgreSQL 时须准备服务并显式引入 optional 驱动
`org.postgresql:postgresql`；模型、连接池、凭据、资源授权和宿主生命周期由应用负责。

## 示例启动

从仓库根目录运行；启动器位于各场景目录：

```sh
bash agentscope-durable/durable-examples/integrated/embedded/run.sh
bash agentscope-durable/durable-examples/integrated/http-server/run.sh
bash agentscope-durable/durable-examples/integrated/sandbox-hands/run.sh
bash agentscope-durable/durable-examples/queued/queued-hands/run.sh --compile-only
bash agentscope-durable/durable-examples/queued/sandbox-hands/run.sh --compile-only
```

`integrated` 包含 embedded、API-only http-server 和同 JVM Sandbox Hands。
`queued` 包含 echo queued-hands 与 Sandbox Hands，Brain/Worker 在独立进程中经 MQ 通信。
离线 fixture 无需模型凭据；本地 Sandbox 需要 POSIX shell 与 Python 3，使用宿主权限，
不提供生产隔离。真实队列运行还需 RocketMQ 5 服务、Topic/消费组及授权配置；
`--compile-only` 只检查打包，不验证 Broker 或端到端交付。角色参数见各示例 README。

## 构建与检查

需要 Java 17+、Maven；隔离依赖检查另需 Python 3。从仓库根目录使用：

```sh
make -C agentscope-durable check
make -C agentscope-durable independent
```

正常检查用于构建、格式与测试；隔离检查使用独立 Maven 缓存构建源码依赖并审计依赖树。
命令是否成功须以实际运行记录为准，本入口不新增通过声明。真实 MQ、云 Sandbox、
多进程故障矩阵、后端组合和生产容量验证仍需独立验收。
无法查询或核实的外部副作用保持 `UNKNOWN`，不承诺任意外部操作恰好一次。

## 设计、验证与历史来源

- [docs/design](docs/design)：[执行模型](docs/design/execution-model-plan.md)、
  [多 Agent / Thread](docs/design/multiagent-thread-design.md)、
  [Hands 扩展](docs/design/hands-extension-plan.md)、[队列 Hands](docs/design/queued-hands-plan.md)。
- [docs/verification](docs/verification)：[独立验收清单](docs/verification/execution-model-acceptance.md)、
  [开发进度](docs/verification/execution-model-progress.md)、
  [用户 API 历史验证](docs/verification/user-api-verification.md)、[历史验证记录](docs/verification/verification.md)。
  原有日期、结果和命令是对应版本的历史证据，不是当前版本的通过声明。
- [docs/archive](docs/archive)：[迁移计划](docs/archive/migration-plan.md)、
  [平台边界](docs/archive/platform-boundaries.md)、[平台实施计划](docs/archive/platform-implementation-plan.md)、
  [上一轮 API 计划](docs/archive/user-api-and-runtime-plan.md)、[上游说明](docs/archive/UPSTREAM.md)、
  [导入清单](docs/archive/source-manifest.json)、[提取记录](docs/archive/extraction-record.json)、
  [上游 Makefile](docs/archive/Makefile.upstream)。仅用于来源追踪；历史链接和命令可能失效，不能作为当前构建指引。

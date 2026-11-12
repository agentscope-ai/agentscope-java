# Durable 实现设计

[English](migration-plan.en.md)

设计以[原始规划](https://aliyuque.antfin.com/ad3swf/gg3g1v/qecdop0f9bdu5irs)第 2 章的
四个抽象、第 4.3 节的公共事件要求、第 5.2 节的交付阶段为依据。
`source-manifest.json` 保留首次导入记录，不代表当前架构，也不构成兼容承诺。

## Runtime 与原生内核的职责

Runtime 负责任务接单、发现、Session 内单活 Run、租约、外部执行恢复和取消。
HarnessAdapter 在该租约下推进原生内核。AgentScope 使用自己的
SessionRecorder/SessionProjection 和私有 SessionLog；Pi Durable 使用自己的
MemoryStorage、commit preparation、请求去重和任务恢复。两者均未另写推理循环。

Agent 工厂和 Executor 注册表由宿主装配，替代 Brain 的平台模型/目录/环境构建器。
Session 固定 Harness、定义、原生状态版本；共用 Backend 的宿主跳过自己不服务的 Harness。
未知状态格式或缺失固定版本不会静默开启新会话。

一个逻辑 Run 可以经历多个执行尝试。AgentScope 每次调用生成新的 native run ID，
Durable run ID 继续作为逻辑 turn ID。原生 SessionLog writer 委托给外层租约，
不建立第二套调度器。原生事件、私有推理不直接成为公共传输事件。

## Java 协议落地

`DurableTypes` 定义执行事实和恢复命令，`DurableMessages` 定义公共结构化输入、
输出与制品引用。`DurableProtocol` 将随 JAR 发布的 JSON Schema 用于边界校验：
Backend 与 Executor 分别校验各操作请求/响应，领域状态机校验提交和保留事件载荷。
嵌入式调用与 HTTP 使用相同 Java 接口和事务状态机。

Frame 的 CheckpointMetadata 绑定 Harness、定义/状态版本、独立位置和摘要。
位置仅随原生状态变化；普通事件提交不推进它。HarnessContext 在原生恢复前验证元数据，
损坏、不兼容或缺失状态不会降级为新会话。状态提交不能用 null 擦除已有检查点。

AgentScope 将通用 text/image/audio/video 输入转换为原生消息；公共完成事件与 Run 输出
使用同一 Message，排除原生推理和内部元数据。投影保存完整消息、工具事实及制品引用，
即使旧事件被清理，也能由对应快照恢复。工具的非终态观测变化有独立持久事件；
RUNNING → UNKNOWN → RUNNING 不会被错误地去重。

能力声明按实际实现区分恢复边界、事件交付、输入内容、查询/取消/输出、持久性与共享范围。
SQLite/PostgreSQL 和两者的 HTTP 访问共用 `SessionBackendContract`，协议及能力声明
不依赖 Managed Agents 的配置、表或 DTO。当前 Java 使用方式见仓库内中英文 Durable 文档。

## 持久化与原子性

`TransactionalSessionBackend` 在 `RecordStore` 上维护一套状态机。
`JdbcRecordStore` 提供跨进程事务及数据库时间：SQLite 先获取写锁再读，
PostgreSQL 使用 FOR UPDATE 锁定记录。当前每个 Session 使用一个 JSON 行；
Worker 执行使用独立记录和执行状态机，可以共用同一存储 SPI。

| 事务 | 一起生效的内容 |
| --- | --- |
| 接单 | 请求摘要、原始回执、Run、可调度工作 |
| 原生提交 | 检查点、工具意图、可派发工作、公共事实 |
| 结果接收 | 冻结的终态结果、立即可恢复工作 |
| 原生应用 | 已接收结果的应用标记、包含结果的原生检查点 |
| 信号/取消 | 幂等命令、状态变化、恢复工作 |
| 公共投影 | 事件序号、投影变更、快照游标 |

requestId/commitId 不允许换内容。租约失效后仍可查询已存在的提交回执。
原生提交响应不确定时先查询回执，再重试同一个命令；仍不能确认则停止后续原生写入，
直到新持有者重新读取权威状态。事务回调内不进行外部 I/O。

所有原生写入和派发接单都校验 owner、epoch、数据库时间下的租约期限。
结果接收是独立授权的操作，必须对应已有 executionId，不能凭空创建工具。
终态结果不可改写；派发前已经接收的取消会阻止首次外部调用。

Wait key、绝对截止时间、提前到达的 Signal 都持久化。定时器恢复依靠扫描存储，
不依赖内存回调。RunExecutor 用稳定子 Session 和接单回执协调异步子 Agent，
无需另建工作流数据库。

## Executor 与 Worker

Runtime 记录逻辑工具意图和结果的接收/应用；可选 Worker 记录物理接单、
provider 尝试、原句柄、取消意图和冻结结果。两个账本描述不同事实。
轮询/查询完成持久化结果交接，不要求分布式事务或消息队列。

首次派发先落库，再调用 provider。替换宿主查询原 executionId/handle。
只有 provider 明确保证幂等 start 时，才允许重试查询不到的操作。
UNKNOWN 保持待处理，不能由租约超时推断副作用失败。
取消必须等待外部操作全部终态，并由原生适配器清理状态后才完成。

Sandbox 迁移了原实现中的独立进程日志和进程身份校验，保留命令、文件、制品操作，
去掉平台 Workspace 快照流程，并在启动前同步接单目录记录。
控制进程退出不影响远程 runner。日志丢失不能作为重做依据。
Worker/HTTP 暴露独立输出字节游标及 gap，不将 stdout 冒充已确认的公共文本增量。

## 公共事件与传输

已提交事件是重放和 SSE 的唯一可靠来源。快照及对应游标在同一事务读取；
保留策略删除旧事件但保留投影，过期游标必须通过快照重同步，不能静默跳过缺口。
SSE 始终读取同一事件日志，不存在历史到内存实时总线切换的丢失窗口。
断开连接只终止观察。

AgentScope 持久化文本增量和完整消息，并提供 attempt/revision，
恢复后的新尝试替换旧的部分文本。Pi 发布已提交的 root assistant 完整消息，
排除私有推理。原生位置、提交版本、公共事件序号/游标、工具字节偏移彼此独立。

HTTP 处理器只解码、授权、委托和映射响应，不维护第二套业务状态机。
宿主负责认证、租户/namespace 权限、请求策略和部署。
RocketMQ 只提供可丢失、可重复的低延迟唤醒提示；持久化工作仍由 Backend 扫描补偿，
MQ 不是 Run 或公共事件的权威账本。

## 依赖方向与源码提取

```text
应用 / 未来 Managed Agents 平台装配
  -> Durable Runtime -> HarnessAdapter -> 原生 Core/Harness 或 Pi
  -> SessionBackend -> RecordStore -> SQLite / PostgreSQL
  -> Executor -> 直接 Sandbox / 子 Run / HTTP Worker
                             WorkerRuntime -> ExecutionStore -> provider
```

Durable 的构建、测试、classpath、运行不使用 Managed Agents 的制品、源码目录、
fixture、平台表或服务。此前来自 common 的依赖闭包落实为中立身份、回执、事务和执行账本，
没有保留改名的 common 包装层。参考来源及改造的 Sandbox 脚本见
[extraction-record.json](extraction-record.json)。

旧平台协议、Spring/JPA/目录装配、Brain 应用、未启用的 Rust/Codex 宿主均已移除。
当前支持的第二内核是 Pi Durable 1.4.0，不宣称支持 Codex 或普通 Pi。
Context、MCP 专用 Executor、云对象存储和更多数据库方言属于独立拓展工作，
不是隐藏依赖。

## 验收与边界

[验证记录](../verification/verification.md) 分开列出契约测试、真实进程故障、PostgreSQL 和真实 provider 验收。
Java 与 Pi 共用 HTTP/状态机契约，不互相翻译原生检查点。

当前实现可由独立宿主使用，但不等于云生产环境认证。真实 E2B/AgentRun 账号验收、
真实 RocketMQ Broker 故障测试、吞吐/成本基准、保留/压缩容量评估仍是明确的发布门槛。
原生大历史目前内联存储；Pi 提供可靠完整消息，尚未提供可靠逐字增量。

Managed Agents 接入留在下一轮：Agent Service 装配 Backend 和公共处理器，
Brain 装配 Runtime/工厂，Tool Worker 装配 WorkerRuntime/provider。
Gateway、租户认证、配额、凭据、资源池、目录、计费和运维保留在平台。
[平台规划](platform-boundaries.md) 继续作为下一轮适配清单。

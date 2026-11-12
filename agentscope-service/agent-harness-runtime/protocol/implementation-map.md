# 协议与实现映射

[English](implementation-map.en.md)

2026-10-30 当前 Java 生产入口已切换为 HTTP Brain：共同 claim/renew/release + 原生 prepared profile；不再装配平台 DB/Redis/MQ。旧进程注册、FIFO ACK、JPA 依赖说明属于保留的历史消费路径，不再描述当前 App。任务主动提示已接入，owner 定向控制仍待完成。详见[本轮实施与边界](../testing/conformance/verification-java-platform-http-20261030.md)。

通用模型目录、PI 凭据引用及 Codex 原生参数的验证见[模型绑定记录](../testing/conformance/verification-model-bindings-20261029.md)。

有效配置身份、摘要校验及 PI 从封存定义到原生请求的验证见[配置与组装记录](../testing/conformance/verification-configuration-20261029.md)。

2026-10-29 补充：通用模型提交已生成公共 span；PI / PI Durable / Codex 已提交原生中间文本事实，终态通过 `messageId` 选择已有消息；大文本走对象引用。Codex 已提取 usage，PI 已归一化缓存输入。PI / PI Durable / Codex 已接入独立在线文本通道，见[预览验证记录](../testing/conformance/verification-previews-20261029.md)；故障中的未完成模型调用收敛仍待补齐。[验证范围](../testing/conformance/verification-public-events-20261029.md)。

2026-10-28，开发基线。一次部署选定一种内核；Session 固定绑定内核和原生格式。未发布，不维护旧版本兼容或双写。

## 实际调用链

```text
AgentScope Java → claim/renew + Adapter → prepared 接口 ─┐
PI / PI durable → TypeScript Runtime → 通用 0.1.0 接口 ───┤
Codex Rust → Rust Runtime → 通用 0.1.0 接口 ─────────────┤
                                                        ↓
                 Agent Service / Session Service → 同一 Journal 提交核心
                                                        ↓
                         工具任务 + 命令 Outbox → Hands
                         结果消费 + 恢复意图 ← Hands 结果 Outbox
```

平台事务不解释 AgentScope/PI/Codex 原生事件。原生格式、工具 call 与原生结果关联归 Adapter；SQL 幂等、所有权、预算、Outbox 和结果连续前缀仍由现有平台核心处理。


## Java 模块职责收拢

- `java/codec-agentscope`：纯原生格式插件，私有事件注册表；支持 checkpoint 校验和恢复/分叉基线，不加载执行器。
- `java/adapter-agentscope`：原生 SessionLog、执行与恢复、模型捕获、工具转换、checkpoint 准备；Brain 显式组装，不再由 `common` 通过 ServiceLoader 找 Adapter。
- `agent-service`：接口与提交实现统一位于 `src/`，承载 `SessionCommitTransactions`、工具命令发布、结果补偿、恢复通知、生命周期、checkpoint 管理及投影处理；使用一个 Maven 模块，在同一 JVM 中运行。跨角色联调测试也位于该模块，测试依赖组合 Adapter 与 Tool Worker，生产依赖保持分离。
- `tool-worker`：工具消息消费、执行控制与 Sandbox 绑定。普通 jar 用于联调依赖，`*-exec.jar` 是部署入口。
- `common`：共享实体、协议结构、传输、租约、对象存储及仍由多个角色使用的账本访问；不放单一角色的执行编排，也不加载具体 Adapter。AgentScope 的调度协调已通过 Service 调用，详见下节；共享存储代码与本地 JPA 装配尚未全部拆完。

提交链路为 `AgentScopePreparation → ManagedCommitPlan → HTTP → SessionCommitTransactions`。本地回归通过显式 `PreparedCommitter` 注入同一事务实现；生产 Brain 不依赖 Session Service 或 Tool Worker 的实现包。原子提交、幂等回执、所有权校验、Outbox 与 ACK 边界保持原语义。


### common 的具体归属

按生产调用和 Spring 装配核对后，下列实现已移出 `common`：

| 所属模块 | 实现与用途 |
| --- | --- |
| `tool-worker` | `ManagedAsyncToolExecution` 及新拆出的 `ManagedAsyncToolExecutions` 工厂：异步工具执行、续租、ACK 交接、结果收集和退出清理。 |
| `agent-service` | `ManagedToolExecutions`：子任务控制使用的同步执行循环；在有界工作线程运行。它不再创建 Hands 的异步执行器。 |
| `java/adapter-agentscope` | `SkillRepositorySupport`、`LoadTestModel`、`AgentStateEntity/Repository`：AgentScope 的 Skill 仓库、压测模型和原生状态持久化。Git Skill 后端依赖也随实现迁入。 |
| `java/brain-agentscope` | `SessionAgentBuildSpec`、JPA 装配、目录/版本/记忆读取及其专用实体、仓库和访问接口：目前仅用于 Brain 本地 Agent 装配；对应类和测试放在同一侧。 |

`ManagedToolExecutor`、`ManagedToolWorkStore`、`ManagedToolResultPublisher` 仍留在 `common`：Hands 的外部工具执行与 Session Service 的子任务控制都使用这些契约及结果发布能力。内部令牌校验也保留，Agent Service 显式导入，Brain 通过组件扫描使用，不能只按显式引用判断归属。

本次只调整代码归属和工厂装配：保留原执行标识、claim → ACK → 执行顺序、租约/取消、未知结果处理、结果补偿和原子提交。迁移不等于把旧目录/记忆读取改成远程调用；共享账本与基础设施仍按上述边界保留。

## 第 3–7 步落点

| 工作 | 代码与行为 |
| --- | --- |
| 3：Session Service | `agent-service/.../sessionservice/api` 和 `store`，同 Agent Service 镜像/JVM。提交、回执、恢复读取、对象和私有 workspace 远程访问；旧 Java 进程租约和固定执行期限继续检查。 |
| 4：Hands 交接 | 工具结果消费者、命令/恢复 Outbox 发布和相关生命周期 worker 移到 Agent Service。Hands 的可靠接单、执行账本、unknown、乱序输出、迟到结果与 ACK 顺序保留。 |
| 5：代码组织 | Java 分为 `runtime`、`adapter-agentscope`、`brain-agentscope`；TypeScript 使用独立 pnpm workspace。构建入口、镜像和路径已调整。 |
| 6：PI | 普通 PI 注入原生 SessionManager；PI durable 注入 Storage。两者同一 Node 进程运行，工具走 Hands，提交由服务返回持久回执。 |
| 7：Codex | `rust/` 直接链接固定版本 codex-core，独立 Rust 进程与镜像。原生挂起、原 call_id 结果注入、原 Turn 跨进程恢复已有用例。TypeScript workspace 仅用于 PI / PI Durable。真实基础设施验收仍待完成。 |

## 三个工具阶段

| 阶段 | 保证与仍可能发生的情况 |
| --- | --- |
| call | 原生恢复点、工具关联、任务和命令 Outbox 同事务提交；只有持久回执后才能继续。HTTP 响应丢失先查原回执；重试保持同一个 commitId 和内容。 |
| execute | Hands 先可靠接单，确认 MQ ACK 后执行；同 executionId 不创建新动作。外部动作已发生、结果尚未保存时仍可能是 unknown，不能据此重新执行。 |
| result | Service 可靠收下事件后 ACK；连续输出或明确缺口齐备后接受结果，并同事务登记恢复意图。Adapter 把原结果写入原生状态后，才提交 resultApplications。 |

Brain 退出不影响 Service 接受原 Hands 的有效结果。接管后的 Brain 使用原 taskId / executionId，读取已接受结果，不发起替代执行。

## 接口配置与实际 wire

- 通用接口见 `openapi.json`：认领、续约、队列读取、配置、提交/回执、恢复、对象、工具状态、输入读取/关闭和模型预留，共 14 项；另列 2 个 Java prepared 交接接口。
- AgentScope 使用 `/prepared/commits`，提交明确的 `ManagedCommitPlan`；`prepared.schema.json` 定义其数据格式。`/prepared/receipt` 查原回执，`/prepared/query` 提供白名单恢复读取，`/prepared/model` 预留预算，`/prepared/objects` 存取原生对象，`/prepared/workspace` 执行原生 Store 操作及带回执的收尾读写。它是当前 Java 集成配置，不让 PI 依赖 Java DTO。
- 两种提交都调用同一个 `SessionCommitTransactions.commitPrepared`，没有第二套 SQL 提交算法。Java prepared 保留数值型位置和原生提交结构；跨语言接口的计数器使用十进制字符串。不能直接混传。
- Hands 的实际 Broker body 仍为 `ManagedBatchCodec.Envelope(version=2)`：作用域、消息 ID、摘要、内联字节或私有对象引用。解码后是 `ManagedToolCommand / ManagedToolEvent`；大消息继续走私有对象存储。`messages.json` / `tools.schema.json` 描述逻辑交接，不能当作实际 Broker 字节直接发送。
- 通用 `toolOperations` 由服务转换为现有任务和命令；保留原 submission epoch/token、授权 commitId、配置密文和 executionId。结果由现有事件消费核心接收，再投影为通用 Result。输出 offset/缺口来源于实际输出账本；原工具结果中的产物引用原样保留在 content 中，尚不转换为通用 artifacts 列表。

## 本轮补齐的问题

- Turn 配置首次生成后固定保存，防止恢复时重新加密导致工具配置引用变化。
- 续租返回服务端时间；内核启动前先续租，用单调时钟计算期限，避免机器时钟差异。
- 校验完整请求 JCS 摘要、对象内容/归属、原生位置、重复身份、模型请求摘要、交互答复与输入关联。
- 对象先保存、事务后引用；新提交检查当前所有权，已成功的重试返回原回执。
- PI durable 存储 ACK 后才让原生写入可见，并保留原生 ID 分配进度；ID 1 留给内核根会话。
- 接口与客户端限制请求/响应大小；所有阻塞服务操作放在 boundedElastic，避免阻塞 Reactor 事件循环。

## AgentScope 历史 MQ 路径核对（2026-10-28）

本节记录 AgentScope 的链路核对。独立 Codex Rust 实现见后文；各内核保持相同的平台职责和恢复边界：

```text
Agent Service 受理 Turn / Input → MQ 或 Session 队列 → AgentScope Brain
  → 原生恢复、模型调用、工具意图 → HTTP Session Service 提交
  → 任务与命令 Outbox → Hands 可靠接单、ACK 后执行
  → 结果 Outbox → Agent Service 持久接收 → Redis 通知或 Resume
  → Brain 应用原结果、继续推理 → 最终 checkpoint / Turn 结算 → 公开事件投影
```

本轮修正了会影响这条链路的衔接问题：

| 问题 | 调整 |
| --- | --- |
| HTTP 默认解码器与协议 `JsonNode` 类型不一致，调用返回 500 | Agent Service 的 HTTP 编解码显式使用持久化协议的 Jackson 2 mapper；真实 Spring 启动、内部鉴权和大对象传输一起验证。 |
| 已提交但收到 5xx，没有按不确定提交处理 | 客户端将 5xx / 408 / 429 视为未确认；提交方查询原回执后再决定重试。远程基础设施异常不能被记为 Agent 业务失败。 |
| 远程 workspace 写入丢失文件时间戳 | 操作摘要排除重放时再生成的时间戳，首次物理写入仍保留完整值；写与回执同事务，重放读取原结果。 |
| 远程读取与预算客户端依赖 Writer 的初始化顺序 | 在各自 Bean 创建时完成绑定；Brain 不会因 Writer 尚未构造而临时使用本地提交配置。 |
| 部署与本地启动入口不完整 | 修正 Compose 缩进；本地 Session Service URL 跟随 Agent Service 端口，补齐 Brain JAR 的缺失构建检查。 |

`AgentScopeRemoteFlowTest` 使用真实 AgentScope、真实 HTTP 和 H2 事务，穿过生产的工具命令接单、ACK、结果账本和恢复代码；模型、外部沙箱、MQ/Redis 传输使用替身。覆盖短工具、长工具挂起后重建 Agent、输入注入、预算预留、提交响应丢失、最终投影，以及私有读写重放和旧 owner 拒绝。它证明组件已连通，**不代表真实 PostgreSQL / RocketMQ / Redis / 沙箱和容器故障验收完成**。

## 历史边界收敛（2026-10-28）

- checkpoint 事务归 Session Service，原生语义通过 `NativeCheckpointCodec` 委托给 `codec-agentscope`。私有注册表包含 `managed/finalization_*`，独立进程不再依赖 Brain 的全局注册；未知必需事件仍拒绝。非 AgentScope 绑定在加锁占用前明确拒绝，等待对应 codec 接入。
- TaskRepository、JPA AgentStateStore、SkillRepository 原生接口实现归 Adapter；模型 `ChatResponse` 在 Adapter 转成中立文本增量。子任务结果由 Service 原样传递原生 JSON，不再构造 AgentScope Msg。
- `/prepared/tool-publication` 在当前 owner 下持久化提案并取得 Broker 回执，**尚未授权执行**；之后的 `/prepared/commits` 才原子授权。响应丢失按原 command 重试，原 executionId 不变。Brain 不再写提案表或启动取消转发。
- `/prepared/query` 增加 `tool-proposals`、`mcp-batches`、`resume-settled`；`/prepared/resume-defer` 保留原 Resume 身份，可靠延期后才释放交接。Schema、OpenAPI 和 [交接样例](examples/prepared-handoffs.json) 已同步。
- 未发布，不保留旧 Hands 完成通知升级通道；Brain 只接受 Service 的标准 Resume。Hands 结果持久接收、结果与唤醒事务、执行 ACK 和 unknown 保障不变。


## AgentScope 调度与协调已接入 Session Service

原来缺的是访问边界迁移，核心保障一直存在。现在 Brain 通过 `/prepared/coordination` 调用原有账本算法；接口是固定白名单，不接受 SQL 或任意存储操作。请求与样例见 [Schema](schemas/coordination.schema.json) 和 [交接样例](examples/coordination-handoffs.json)。

| 环节 | Session Service 负责 | Brain 保留 |
| --- | --- | --- |
| 进程与 Turn 认领 | 进程注册/续租/退出；校验 incarnation、固定执行期限、发布序号、前序 Turn 和 Resume 身份；同事务更新 epoch 与队列认领。 | 每进程一个注册身份；短 Redis 执行租约、本地取消；响应丢失按原身份重试。 |
| 输入与 Session 队列 | 输入关闭与截点、队列 generation、dispatch token、Ready 校验与延后。 | 消费通知、读取结果并调用内核。 |
| FIFO ACK | `SessionTurnAckLedger` 保存每一代私有 Broker 回执；与终态提交共用 Session 锁；仅终态回执可领取，领取失败/丢响应后可补偿。 | `ManagedTurnAcknowledgements` 向 Broker 发 ACK，确认后删除对应回执；Broker RPC 不占数据库事务。 |
| MCP | 查找、首次固定描述、绑定公开目录事实、读取取消状态。并发发现沿用先提交的描述。 | HTTP / stdio 连接和发现；保留本机命令、工作目录及缓存隔离，模型/凭据配置不随目录 RPC 传输。 |
| 子任务 | 从已提交 ToolTask 创建子 Session/Turn，保留原冻结定义、身份、根任务关系、预算和取消语义；提供关系读取。 | 生成原生委派意图、读取冻结身份、应用返回结果。 |

恢复边界：

- **注册或认领响应丢失**：重试原 incarnation 或原 process/token/publication，不能换身份重做；过期 incarnation 无法重新注册或续租。
- **Brain 失联或执行期限到达**：Service 拒绝旧 owner 写入；后继认领仍经过原锁顺序，活跃 owner 不能被抢占。进程续租不会延长 Turn 的固定期限。期限由 Agent Service 的 `managed-agents.runtime.fragment-deadline-seconds` 配置决定。
- **ACK 响应或消费者丢失**：保留原回执，等待领取期限后重试；回执过期靠 Broker 重投递获取新一代能力。不得把超时视为 ACK 成功，也不因挂起 ACK 掉尚未终态的 FIFO Turn。

Java Brain 启动时向 Session Service 注册进程，注册成功后才启动消费；Service 不可用或租约失效时不回退到本地账本。`SessionCoordinationHttpTest` 覆盖这些 RPC 的失败恢复，`AgentScopeRemoteFlowTest` 的短工具、挂起、重建 Agent 和续跑也已使用远程认领与输入接口。真实基础设施故障验收仍单独进行。

### 尚未收口

- 剩余 DB 依赖主要是本地 JPA 目录/记忆与共享基础设施装配；上述运行协调不再由 Brain 直接操作账本。Redis 与 MQ 仍承担短租和传输职责。
- 公共账本中的 SessionEvent 封装和 checkpoint 对象布局仍带 AgentScope 格式痕迹；本轮已隔离解释逻辑，尚未承诺全套 DTO 都已内核无关。
- 真实 PostgreSQL/RocketMQ/Redis/沙箱、容器故障、镜像部署验收仍待执行。PI 能力范围不变；Codex Rust 的实现与验收范围见下文。

## 验收与明确限制

Java 旧提交/恢复/工具可靠性定向回归、Service H2 事务、HTTP 丢回执、并发工具去重、原生 durable Harness 重开、Codex Rust 跨进程恢复已有自动化用例。详见[验收入口](../testing/conformance/README.md)。真实 PostgreSQL、MQ/Redis、容器故障和模型端到端验收尚未执行。

上述 Redis/MQ/JPA 配置说明对应旧消费路径。当前 Java App 已移除这些运行装配；部署接线和剩余验收以本页顶部的 2026-10-30 实施记录为准。

PI 首版开放执行、文件读写和已声明 MCP 工具；原生自动加载的本地工具/插件关闭。通用多 call 批次、AgentScope 收尾私有读写协议的跨语言等价能力，以及平台 checkpoint 管理功能在 PI 下仍需专项接入。PI durable 当前保存完整写日志快照，受 64 MiB 对象上限约束，后续需做分段与压缩。

## Codex Rust 接入

`rust/` 直接依赖固定提交的开源 codex-core，不改上游内核、不启动 CLI/app-server、不依赖 Node。`ScratchStore` 实现原生 ThreadStore 暂存接口，权威检查点仍通过 Session Service 提交。每个原生实例推进一个模型响应；在工具边界捕获 rollout 后调用 `suspend_turn_and_shutdown`。新实例用 `inject_response_items` 注入原 call_id 的结果，再用 `recover_turn_if_idle` 恢复原 Turn。

已验证原生双工具的部分结果、owner 切换、提交丢回执和 SIGKILL 后新进程恢复。服务端开放显式 `codex` 绑定；Rust 请求另经共享 Schema 验证。测试模型和 Session Service transport 是替身，不等于真实 MQ/Hands 联调。单 root 文本/动态工具之外的子 Agent、多模态、追加输入、增量快照与平台 checkpoint 管理 codec 仍待扩展。构建、镜像、配置及限制见 [Rust Brain](../rust/README.md)。

AgentScope 显式模型绑定现经 prepared `model-configuration` 取得按 Turn 固定的选择，工厂校验身份后创建 OpenAI Chat Completions / Responses 或 Anthropic 实例；原生命名/默认模型保留明确的 native 模式。定向行为与剩余子 Agent、准入及完整链路范围见[模型配置验证](../testing/conformance/verification-agentscope-models-20261029.md)。

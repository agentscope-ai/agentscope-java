# Managed Agents 架构重构实施计划

实施目标是把公开资源管理 API 收敛到控制面，把运行请求、Session 和 Turn 准入、运行视图及 SSE 收敛到 Agent Service；Harness 数据面负责执行和原生状态恢复，Tool Worker Worker 负责远程工具执行及 Sandbox 操作。各组件支持多实例部署，应对大量用户同时提交 Session 请求、持续生成事件和并发执行工具。首期采用共享持久化存储、普通负载均衡和任务认领，落实幂等、Session 执行租约、有界并发、背压及故障恢复。

用户已确认允许不兼容重建，优先完成新架构；无需承担旧 Service API、历史 Session 和旧游标的兼容迁移。新契约仍须在新系统内部保持稳定，并同步更新 UI、SDK、示例和部署制品。Agent Service 续租用于刷新权限和准入资格，沿用当前任务存活语义，SSE 断线后执行继续。

本轮采用快速迭代方式，优先完成所有核心流程和多实例并发机制，使整体链路能够构建、启动并跑通。各阶段只执行与当前变更相关的构建、核心流程 smoke 和少量关键逻辑测试；全量回归、穷举故障、长时间压测及完整部署矩阵放在后续独立测试轮次。实际实施证据维护在 [实施记录](agent-service-implementation-status.md)，待补项目维护在 [后续测试清单](agent-service-test-backlog.md)，不以测试覆盖率达到 100% 作为本轮阶段门槛。

代码基线为 `/Users/ken/agentcore/agentcore-managed-agents`，分支 `codex/automatic-acr-release`，2026 年 10 月 10 日 HEAD 为 `fbd32ca60`。当前工作区包含尚未提交的 Managed Runtime 改造，属于本计划的实现起点。后续直接在该目录和当前分支实施，保留已有改动；本计划中的新模块、协议和测试均为待实现内容。

## 1 架构实现目标

整体完成需要同时满足以下条件：

1. Gateway 按明确的 API 路由表分发请求。公开入口只落到控制面或 Agent Service；执行端、调度器和 Worker 不直接向公网暴露服务端接口。
2. 控制面管理 Agent、Application、Endpoint、Team 定义、Environment、Vault、Memory、权限和发布配置，提供配置准入与版本化权限投影。实际执行、执行租约和运行事件不再由控制面进程调度。
3. Agent Service 统一 Session、Turn、Invocation 等运行请求的入口，负责鉴权、幂等准入、配额预留、命令投递、公开状态及事件查询。Session 和 Turn 的用户可见标识、运行后端和执行实例分开。
4. Managed 数据面通过内部命令启动 Harness，维护原生 SessionInbox、SessionLog、checkpoint、执行租约和 writer fence。API 请求或 SSE 断开不会直接取消执行。
5. Managed、External、Hosted、Team、Workflow 通过后端适配器接入同一套公开事件模型。标准字段和错误语义统一，后端特有能力通过 capabilities 明确表达。
6. Tool Worker 使用持久化 ToolTask 和结果协议，不再扫描公共事件全量历史寻找待执行工具。平台托管 E2B 与 self_hosted 共用执行协议，保留不同的环境能力和隔离规则。
7. 任一 Agent Service 或执行副本重启后，已受理命令和已提交事件可恢复；执行者替换后，旧执行者不能更新原生状态、公开运行状态或提交有效的新结果。
8. 同一 Application 跨 Managed 和其他后端执行时，使用同一套原子并发准入与用量去重规则。
9. 文件、产物、子 Agent、人工确认、预算、checkpoint、Channel、Cron、Webhook、Runtime Host 和 Worker 回调均纳入路由和授权清单；新增队列、读取和调度使用业务范围索引、有限批次及容量上限，避免并发增加时出现无界扫描和资源占用。
10. 提供新环境初始化、版本升级和切流回滚方案、部署制品、前端、SDK 和中英文文档。本轮在隔离环境跑通新架构，记录实际验证及剩余测试；完整容量认证和生产切流按后续测试结果与实际部署计划开展。

本轮交付以架构和核心流程完整为准：各运行后端经 Agent Service 准入、执行、提交事实并形成公开事件闭环；Tool Worker 工具请求、执行、结果与恢复闭环可运行；权限、幂等、租约和资源限制已实现并有代表性验证。生产容量和完整故障覆盖在后续测试轮次确认。已验证失败的核心流程或状态逻辑当轮修复，覆盖范围尚未补齐的测试如实登记。

## 2 组件职责与状态归属

| 组件 | 最终职责 | 权威状态 |
| --- | --- | --- |
| Gateway | 路由、公开与内部接口隔离、可信身份头处理、SSE 转发 | 路由配置，不保存执行状态 |
| 控制面 | 资源定义、版本发布、RBAC、凭据管理、配置准入、管理概览 | 资源配置、权限策略、配置版本；运行状态只保存异步管理投影 |
| Agent Service | 公开运行 API、原始调用者鉴权、Session 生命周期、Turn 准入、配额、后端路由、查询及 SSE | 公开 Session 注册表、准入命令、公开事件和查询视图、配额预留、权限缓存 |
| Harness 数据面 | 执行命令认领、构建 Harness、模型调用、原生输入消费、恢复、工具请求与结果接收 | 执行配置快照、原生 Inbox 和日志、checkpoint、执行租约与 fence、待处理工具请求 |
| Go 编排运行时 | 复用现有 Team 和 Workflow 引擎，维护 AgentTask、Attempt 和 Runtime Host 执行协调 | 编排状态、任务及 Attempt 日志、运行时命令与租约 |
| Tool Worker Worker | 认领工具任务、执行、独立心跳、结果重试、Sandbox 创建与连接及回收 | Worker 执行记录、待上报结果；通过运行协议更新共享 Sandbox 绑定和工具任务记录 |
| Scheduler 与 Channel Worker | Cron 触发、Channel 协议处理、出站投递和重试 | 触发与投递记录；通过 Agent Service 提交运行意图 |
| 共享存储 | 持久化、跨副本恢复、文件及产物访问 | 各组件拥有各自表和写权限；Workspace 的归属与 Sandbox 生命周期分离 |

Agent Service 管理公开生命周期和准入；数据面报告实际执行事实。Agent Service 的 `cancel_requested` 不代表 Harness 已停止，数据面的 `running` 也不能覆盖 Agent Service 的删除或拒绝准入决策。公开视图由带版本的准入记录和执行事实归并得到。

租约分三类：Agent Service 刷新权限与准入资格及必要的配额预留；数据面续租 Session 的独占执行权并维护原生日志 fence；Tool Worker 续租工具任务的执行权。SSE 心跳用于连接存活检测，不代替任何一类执行租约，也不决定任务存活。

Team 和 Workflow 继续使用现有 Go 引擎，拆出独立运行角色，不重写为 Harness。Runtime Host 执行完整 Hosted Agent；Tool Worker 执行 Harness 发出的工具任务，两者身份、任务和租约不能合并。

### 多实例并发机制与首期实现

| 机制 | 必须具备的设计 | 首期实现 |
| --- | --- | --- |
| 多实例受理和执行 | 任意 Agent Service 副本可以受理请求；任意可用数据面副本可以认领待执行 Turn；同一 Session 只有一个有效执行者，不同 Session 可以并行 | 普通负载均衡、共享 PostgreSQL 任务表、原子认领和 Session 执行租约；当前执行实例记录在租约中，仅用于交互请求转发 |
| 命令与事实传递 | 稳定 ID 去重、提交位置持久化、至少一次投递及补偿；同一 Session 的输入与事实按约定顺序处理 | 持久化 Inbox 和 outbox，按索引批量读取、确认及有界重试；通知只作唤醒，丢失后仍可从数据库恢复 |
| 限流与背压 | 限制租户或 Application 的受理速率、待执行数量及执行并发；各实例和 Worker 有独立并发上限，预取不超过可用槽位 | 配置化限额、请求大小和队列上限；超限返回明确错误；新 Turn、控制命令及续租使用独立处理额度，单个用户不能占满全部资源 |
| 共享配额与用量 | 多个 Agent Service 和不同运行后端共用准入记录；预留、激活、释放及用量入账均可去重 | 按 Application 原子检查并更新额度，使用短事务、唯一 reservationId 和用量收据，复用现有数据库 |
| 事件和 SSE | 公开事件持久化并按 Session 编号，支持任意 Agent Service 副本按游标续传；慢连接和重放请求不能无限占用内存 | 通知唤醒加批量增量读取；实例内复用同一 Session 的读取并向连接分发；限制缓冲、读取批次和重放窗口，慢客户端退出后续传 |
| 权限刷新 | 按策略、调用者及资源版本复用查询结果，有限有效期和变更失效；Session 数量增加不能等量增加控制面全量查询 | 复用版本缓存和变更 outbox，批量刷新到期记录；设置超时、抖动与并发上限，保留现有过期拒绝规则 |
| 共享存储与查询 | 多副本共享原生日志、公开事件及持久化 Workspace；按状态、Session、租户和游标索引读取 | 首期可使用同一数据库，各服务拥有自己的表或 schema 和写权限；列表、调度和清理均采用有限批次及分页 |
| 大请求和长 Session | 输入、工具结果及历史读取有大小或数量限制；文件和大产物通过引用传递，避免一次加载全部历史 | 流式传输、分页增量读取、checkpoint 恢复及共享文件存储；请求体、上下文构建和事件重放分别设置上限 |
| 资源隔离 | 模型调用、长工具、数据库操作和慢 SSE 不能阻塞执行续租或其他请求；连接池与线程池按实例容量配置 | 保留 WebFlux 非阻塞处理，阻塞操作使用有界线程池；模型、工具、扫描及续租分别限制并发，总数据库连接预算覆盖实际副本数 |
| 故障恢复 | 实例退出后持久化任务和事件可恢复；过期执行者不能写入；重试不能放大后端故障或重跑未知副作用 | 共享租约、writer fence、超时、有限退避及失败状态；停机先停止认领，再处理在途任务 |
| 可观测性 | 受理延迟、队列积压、运行并发、事件滞后、租约丢失、连接池占用和工具未知结果可查询 | traceId 贯穿各服务，按组件、实例和后端汇总指标；Session 与用户标识放在日志和 trace 中，避免无界监控标签 |

首期直接部署多个 Agent Service、数据面和 Tool Worker 实例，共用持久化存储，通过现有服务发现和负载均衡通信。Session 不固定绑定某个进程；重启、扩容和缩容依靠任务状态与执行租约处理，不依赖 sticky Session 或进程内 Map 保存权威状态。

数据库的重点是原子认领、必要索引、短事务、批量查询和连接数控制。先使用现有 PostgreSQL 和共享 Workspace 跑通完整流程；新增消息中间件、缓存或存储组件仅在实际瓶颈及部署条件明确后单独决策，本轮不预建通用基础设施框架。

## 3 当前代码的处理方式

当前 Managed Session 已具有共享配置、幂等 Turn Inbox、执行租约、原生输入、持久化增量、SSE 重放和用量收据。重构复用经过验证的内部机制，以新 Service 契约拆解目前集中在同一个数据面部署中的职责。当前实现边界见 [Managed Runtime 设计](managed-runtime-dataplane.md)。

| 当前代码 | 处理方式 | 相关阶段 |
| --- | --- | --- |
| [Gateway 路由](../gateway/src/main/resources/application.yml) | 建立完整路由清单，Managed 先切 Agent Service，其他运行入口分阶段迁移 | P0 P2 P5 P8 |
| `ManagedRuntimeSessions`（历史实现，已拆分） | 拆为 Agent Service 生命周期及准入、权限缓存、数据面执行配置及状态、控制面投影同步 | P1 P2 P3 P6 |
| `SessionTurnInbox`（历史实现，已拆分） | 拆开公开受理与执行认领；以内部持久化协议替换进程内 Runner 回调 | P2 P3 |
| `SessionInputService`（历史实现，已拆分） | 原生 steer 和 inject 准入及应用回执继续由数据面负责，Agent Service 提供公开入口 | P3 |
| `SessionNativeLogService`（历史实现，已拆分） 与 `公共投影器`（历史实现，已拆分） | 数据面保留原生提交、隐私过滤及可靠导出；Agent Service 负责公开事件规范、查询及投影 | P4 |
| [SessionEventLog](../common/src/main/java/io/agentcore/managed/server/web/managed/service/SessionEventLog.java) | 保留持久化、去重和跨副本唤醒能力，公开日志最终由 Agent Service 写入和读取 | P2 P4 |
| [ControlPlaneClient](../agent-harness-runtime/java/brain-agentscope/src/main/java/io/agentcore/managed/server/control/ControlPlaneClient.java) | 分离管理准入客户端与运行配置访问，解除对本地 ManagedRuntimeSessions 的反向耦合 | P2 P3 P6 |
| [Go 编排引擎](../control-plane/internal/orchestration/worker.go) 与 [HTTP 路由](../control-plane/internal/httpapi/server.go) | 资源 CRUD 留控制面；执行、任务协调和运行事件抽到独立 Go 运行角色，公开运行 API 经 Agent Service | P5 P8 |
| `EnvironmentSpecFactory`（历史实现，已拆分） | 将服务场景下 E2B 的实际操作迁到 Tool Worker，保留 Harness 对 Workspace、Memory 和内部工具的抽象 | P0 P7 |
| `PendingToolWorkerToolService`（历史实现，已拆分） 与 [ToolWorkerApp](../tool-worker/src/main/java/io/agentcore/managed/server/worker/ToolWorkerApp.java) | Worker 入口已迁到 Tool Worker 模块；继续用 ToolTask 查询、认领、执行记录和结果 outbox 替换全历史扫描与执行后直接批量回传 | P1 P7 |
| `ChannelWorkBridge`（历史实现，已拆分） 与 `CronDeploymentScheduler`（历史实现，已拆分） | 改为经 Agent Service 的统一准入调用，不直接启动 Turn | P8 |
| [Service POM](../pom.xml)、[镜像构建](../docker/Dockerfile.service)、[发布脚本](../release/release.py) 与 [Helm](../helm/managed-agents/templates/planes.yaml) | 增加 Agent Service、Tool Worker 和 Go 运行角色的构建、部署、检查及发布映射 | P1 P2 P5 P7 P9 |

## 4 决策清单

“实施默认”表示计划据此安排工作。已确认项按下表执行；其他产品或运维取舍在相应阶段提供具体方案，涉及数据退役和生产切流的操作在执行前确认。

| 决策 | 实施默认或建议 | 需要确认的时间 |
| --- | --- | --- |
| D1 API 和历史数据兼容 | 已确认允许不兼容重建，优先新架构；采用新运行数据空间和新 Service 契约 | 已决定；旧环境保留或退役的具体操作在 P9 确认 |
| D2 首期容量和性能 | 已确认各组件多实例部署，应对大量并发 Session 请求；采用普通负载均衡、共享状态、有界并发与背压，本轮验证小规模多副本核心流程，完整扩容曲线后补 | 架构要求已决定；实际容量与生产 SLO 根据后续测试和部署资源确认 |
| D3 Session 和 Turn 续租含义 | 已确认刷新权限和准入租约，沿用任务存活语义；SSE 断线不自动停任务 | 已决定 |
| D4 Agent Service 技术栈 | 使用 Java，复用 WebFlux、鉴权、公共事件日志及现有 Managed API；Go 引擎保留独立运行角色 | 按已认可的分析方向推进；出现实质性约束时再讨论 |
| D5 队列和存储 | 首期复用 PostgreSQL 持久化 Inbox、outbox 和租约，多副本通过原子操作认领任务；可共用物理数据库，表和写权限分属各服务 | P0 明确接口和部署支持范围；需要新增消息中间件或数据库时再决策 |
| D6 权限过期和控制面故障 | 保留现有 60 秒权限有效期、过期拒绝操作，已有 SSE 定期重新检查；不通过无限延长缓存提高可用性 | P6 若需改变撤权延迟和故障可用性，再提交具体选项 |
| D7 并发和预算语义 | 已确认 Application maxConcurrent 继续包含 queued、running、cancel_requested 和 requires_action；实例实际执行并发另设上限。预算按已报告用量检查后续调用，并发在途调用可能超额，当前机制不提供固定超额上界 | 已决定；P3 落实占用规则，P6 完成跨后端验证及用量说明 |
| D8 工具副作用与未知结果 | 重复投递允许，结果仅应用一次；已开始且结果未知的非幂等工具不自动重跑 | P7 明确人工恢复入口和工具重试分类；改变该规则需确认 |
| D9 Sandbox 和 Workspace | 已确认默认空闲 5 分钟后回收，下次从归档恢复；Environment 可以覆盖。文件归档保留，Sandbox TTL 和运行中保活另行配置 | 默认已决定；P8 确认生产存储和历史归档保留要求 |
| D10 切流和发布 | 本轮在隔离新环境跑通并交付切流、旧任务排空和回滚方案；实际生产切流独立安排 | 执行实际生产变更前确认；提交、推送和发布另行获得授权 |
| D11 本轮验证投入 | 已确认快速迭代，以核心流程与设计正确、整体跑通为目标；选择少量关键逻辑测试，其余测试登记后补 | 已决定；阶段验证按第 7 节执行 |

本轮以小规模多副本和并发请求验证核心机制，后续测试轮次测量递增负载曲线、横向扩容效率和饱和后的稳定性，再按实际部署资源给出容量及 SLO。新架构使用新运行数据空间，旧环境数据处理属于具体切流时的部署决定。

## 5 阶段安排与依赖

| 阶段 | 可检查的交付结果 | 前置阶段 |
| --- | --- | --- |
| P0 基线和关键验证 | API 归属、状态矩阵、新契约、多实例并发方案、远程工具恢复验证 | 当前代码 |
| P1 协议和模块基础 | 内部协议、数据归属、Agent Service 与 Tool Worker 模块骨架、迁移框架 | P0 |
| P2 Managed 服务拆分 | Managed 公共 API 和 SSE 经独立 Agent Service，Harness 独立部署 | P1 |
| P3 持久化命令与执行控制 | 跨服务 Turn、steer、inject、取消、恢复、租约和权限复核 | P2 |
| P4 公共事件与查询 | 可靠导出、统一公开投影、游标重放、终态与快照一致 | P3 |
| P5 其他后端与 Go 运行角色 | External、Hosted、Team、Workflow、Invocation 接入；控制面退出执行调度 | P4 |
| P6 全局准入与权限刷新 | 跨后端原子配额、用量去重、按策略版本刷新权限 | P3 P5 |
| P7 Tool Worker 和 Sandbox | ToolTask、Worker 恢复、E2B 与 self_hosted、工具结果继续推理 | P3 及 P0 工具验证 |
| P8 剩余入口和存储边界 | Channel、Cron、Webhook、文件、SDK、UI、所有公开运行路由收敛 | P4 P5 P6 P7 |
| P9 集成跑通与交付 | 新环境启动、核心闭环、关键恢复、部署制品、切流方案及待补测试清单 | P8 |

主线按 P0 → P1 → P2 → P3 → P4 → P5 → P6 推进；P7 的完整实现可在 P3 后开展，但其恢复机制必须先在 P0 验证。P8 汇合各后端和 Tool Worker，P9 完成集成跑通与交付。每个阶段拆成可独立审查的变更包，按变更范围执行必要构建、流程验证和关键测试；全仓库回归集中到后续测试轮次。

### P0 基线和关键验证

- [x] 建立[路由归属与源码声明清单](agent-service-api-inventory.md)，列出 URL、方法、调用者、鉴权方式、管理或运行语义、当前与目标服务。覆盖公开 Invocation、原生 Managed 扩展、协作 MCP、Runtime Host、Environment Worker、数据面注册、Hosted DistributedStore、Channel 回调及 Automation webhook；最终运行挂载/旁路关闭探测继续 P8/P9。
- [x] 固定 Session、Turn、Command、Attempt、ToolTask、InputReceipt 的状态转换，明确同一 Session 的执行顺序、前序失败或中断阻塞规则，以及 queued 与实际 started 的差别。契约见 [状态与顺序](agent-service-state-contract.md)；各流程的实际接通情况独立登记在实施记录。
- [ ] 制定新的 ID、游标、错误码、幂等键、文件描述、动作答复和 capabilities 契约 fixture。记录不同后端当前的输入、结构化输出、暂停和人工确认能力，用于确定新的统一行为。
- [x] 对当前工作区执行必要构建和核心链路 smoke，保存可重复运行的命令与隔离环境配置；既有和本轮失败独立登记，完整测试后补。证据见实施记录。
- [x] 验证最小远程工具闭环：Harness 提交 external execution 请求后暂停，进程退出，另一个执行实例加载 checkpoint，应用持久化结果并继续同一个公开 Turn；重复结果不重复加入上下文。证据见实施记录中的跨 JVM 测试；完整 Tool Worker 链路仍由 P7 实现。
- [ ] 同时验证 Workspace、Memory、Skill、子 Agent 与外部工具的关系，区分需要 Tool Worker 执行的 shell 和文件操作，以及应继续留在 Harness 的内部行为。
- [x] 制定[容量场景和观测指标](agent-service-capacity-scenarios.md)，覆盖 API 准入、运行调度、持久化增量、SSE 重放、权限刷新、工具和投递；完整压力曲线后补，当前没有容量认证结论。
- [ ] 明确 Session、命令、公开事件和工具任务的关联及顺序；确定共享任务认领、执行租约、租户或 Application 限额、队列上限、实例并发、重试预算和资源隔离规则。

验收：新的 API 归属、状态矩阵和多实例并发规则可直接指导代码拆分；远程工具在执行者替换后的恢复有通过的行为测试。若现有 Harness 暂停协议不足，先在 Core 或 Harness 的正确边界补齐，保持可复用库的公开接口及序列化契约，再进入完整 Tool Worker 改造。允许 Service 重建不自动扩大为重写 Core 和 Harness。

本轮验证：按 V1、V2、V3 的快速检查建立可运行基线；远程工具恢复补一项代表性行为测试，使用 fake 工具和临时存储。先定义容量场景，完整负载曲线后补。

### P1 协议和模块基础

- [x] 新建 Java `agent-service` 和独立可运行的 `tool-worker` 模块。Tool Worker 的托管和 self_hosted 启动及持久化工具闭环已通过实际进程验证。
- [x] 复用 `common` 的 DTO、错误和鉴权基础，限定 JPA/组件扫描；Agent Service 不依赖 `agent-harness-runtime` 可执行模块，也不装配 Harness。
- [x] 定义版本化 `RuntimeSessionBootstrap`、`TurnCommand`、`InteractionCommand`、`RuntimeFact`、`ToolTask`、`ToolResult` 和 `UsageReceipt`。Java 与 Go 类型及 v1 JSON fixture 已建立；业务端实际使用和请求往返继续在后续步骤实现。
- [ ] 统一协议字段：tenant、namespace、Application、Session、Turn、后端绑定、commandId、输入摘要、调用者身份或凭据引用、授权版本、traceId；执行事实另带 attempt 或 activation 标识、有效 fence 和源日志位置。
- [ ] 定义幂等作用域、相同键不同内容的冲突行为、乱序处理、超时重试、失效凭据、拒绝准入及协议版本不支持的错误。
- [ ] 定义内部身份边界，保留原始调用者和人类批准者，签发或验证限定 Session、Turn、Environment 和有效期的执行授权。公网输入的内部身份头不得变成可信凭据。
- [ ] 建立新 schema、后续增量迁移和表归属清单，必要索引包含 tenant、Session、状态和下次可执行时间。生产初始化及升级由单独 migration job 执行，应用副本以 validate 启动。
- [ ] 执行协调使用数据面或独立共享存储的权威租约与状态，解除运行时锁、KV 和总线对控制面 HTTP 进程的同步可用性依赖。
- [x] 落实内部命令、运行事实、工具请求和结果接口，使用 PostgreSQL Inbox、outbox、ToolTask 及有界认领、确认和重试；实际 PostgreSQL 多副本与 Tool Worker 恢复验证见实施记录。
- [ ] 建立后端适配接口和 capabilities 模型；接口区分受理命令、查询事实、取消与恢复，不要求每个后端支持 steer、inject 或工具拦截。

验收：模块可构建和独立启动；协议有最小请求与响应示例及明确的版本拒绝行为；每张新表只有一个所属业务服务，其多个副本通过原子操作协调写入。多个运行副本共用存储并认领任务，跨服务通过协议交换状态。

本轮验证：V1 构建及必要格式检查，核对 Java 与 Go 的跨语言请求响应字段和一条实际往返。完整协议失败矩阵与 V5 全仓库回归记入后续测试清单。

### P2 Managed 服务拆分

- [ ] 将 `ManagedRuntimeSessionsController`、`AgentSessionTurnsController`、`ManagedRuntimeTurnReadsController`、`AgentSessionEventsController` 及 Managed 公共鉴权移到 Agent Service。文件、预算、子 Agent、trace、checkpoint 和 webhook 入口同步纳入清单，不因主流程拆分而漏掉授权。
- [x] Agent Service 持久化注册表、准入策略与公开配置视图；数据面持有加密冻结快照和独立生命周期镜像。归档、恢复、删除及版本化回执已接通。
- [ ] 完成 PATCH/动态配置覆盖与控制面管理摘要，移除旧 `ManagedRuntimeSessions` 的执行/公开双重职责。
- [ ] 数据面保留 Runner、原生输入、日志、checkpoint、Agent 构建和执行调度。拆开 `SessionTurnInbox` 的受理部分与认领部分，停止通过组件扫描把两类角色一起启动。
- [ ] 改写 `ControlPlaneClient` 和 `DataSessionService` 依赖，执行配置从数据面自身持久化快照读取，公开 lifecycle 和准入变更通过内部协议获得。
- [x] Gateway 新增 Agent Service upstream，新环境 Managed 公共流量经 Agent Service，内部执行接口不经公网；真实账号/资源及内部路由隔离进程验证已通过。
- [ ] 增加 Agent Service 的 Compose、Helm、健康检查和本地启动配置。采用新运行数据空间，公共事件存储按 Agent Service 所属服务配置，可靠导出在 P3、P4 完成。

验收：Agent Service 与数据面是两个进程；只扩容 Agent Service 或重启 SSE 进程不改变 Harness 的执行权；Agent Service 无法通过本地 Runner 启动推理。新 Managed API fixture、鉴权及重放行为通过。

本轮验证：V1、V3、V6 的快速检查；用 fake 模型在隔离 Compose 中跑通创建、提交和 SSE，选择一次跨 Agent Service 副本续传或重启验证。阶段结束保留可回退的 Gateway 路由配置。

### P3 持久化命令与执行控制

- [x] Agent Service 在同一事务中完成 Turn 准入、配额预留、`turn.accepted` 和命令 outbox；重复请求返回原 Turn，相同幂等键不同内容返回冲突，多 Agent Service 事务验证通过。
- [x] 数据面执行命令 Inbox 幂等落库，通知丢失仍可扫描恢复；Agent Service outbox 与数据面 Inbox 以稳定 commandId 和重试闭环，多副本/停机/重启进程验证通过。
- [ ] 按 Session 串行执行并定义前序阻塞规则。多个数据面副本按空闲槽位从共享 Inbox 原子认领待执行命令，再取得 Session 租约及原生 writer fence，成功后才能开始执行。实例重启或退出后由其他副本在租约失效后接替；失去租约的实例停止继续执行，后续写入由 fence 拒绝。
- [ ] 每次实际激活前复核原始调用者、策略版本、生命周期与配额资格；长队列中的旧授权不得无限有效。根 Turn 与子 Agent 的调用身份保持贯通。
- [x] `steer` 通过有时限的内部调用进入共享原生 SessionInbox，在运行时的接收锁与终止锁下校验指定 Turn、当前 writer 和准入期限，提交后才返回 runtime accepted 回执。共享持久化 Inbox 允许任一数据面副本受理，执行副本从同一 Inbox 消费，因此无需增加实例发现路由。请求超时用同一输入 ID 恢复回执或重试。Agent Service 自己落 outbox 只能表示 queued，不能冒充“原生输入已接收”。
- [x] `inject` 继续不绑定 Turn、不创建或自动启动新 Turn；输入回执的 accepted、applied、rejected 来自数据面原生状态。任何数据面副本可幂等写入共享原生 Inbox，避免输入停留在某个副本内存中。
- [x] 将 cancel 转成可重试命令，取消受理与实际停止分开。控制投递和停止补偿独立于普通新 Turn 的调度，停止事实确认后才释放预留；空闲恢复取消不自动重放不确定的外部副作用。
- [x] 将 resume 转成可重试命令；显式恢复复核原调用者及恢复发起人，继续同一个 Turn，已释放额度重新预留，旧激活事实不能覆盖新状态。恢复投递使用独立控制队列，实际执行仍受实例槽位限制。
- [x] 将 approval、tool result 转成可重试命令。人类批准与 Application key 提交外部结果保留不同权限要求；同 Turn 答复、原生请求校验、always_ask 与多工具分批结果跨重启闭环已跑通；Tool Worker 任务授权在 P7 接入。
- [ ] 创建和 PATCH 使用控制面准入及数据面持久化确认。跨服务超时重试复用同一 Session 和命令；内部 provisioning 状态不作为已就绪的成功响应。
- [ ] 替换进程内 finished 回调，用带有效 fence 的持久化运行事实驱动 Agent Service 状态。执行者丢失后沿用显式恢复，不自动重放可能具有外部副作用的操作。
- [ ] 分开配置命令扫描、权限刷新、模型执行和续租线程或调度器；阻塞数据库与文件操作不得占用 reactive event-loop。
- [ ] 实现租户或 Application 受理限额和执行背压，避免单个用户占满队列及执行额度。调度按可执行状态和时间索引有限批量认领，预取不超过可用槽位；稳定失败进入可查询的失败状态，瞬时失败使用有界退避和抖动，不为此新增独立调度框架。

验收：在 Agent Service 提交后崩溃、outbox 重复发送、数据面落库后响应丢失、取消与启动竞争、steer 与结束竞争、执行租约替换等场景下，只有一个有效执行者；命令与回执可以查询并恢复。

本轮验证：V1 与小规模双副本 smoke；优先给幂等受理和旧 fence 拒写增加少量测试，涉及数据库竞争时使用隔离 PostgreSQL。跑通 steer、inject、cancel、resume 的核心路径；完整竞争组合和每个崩溃点的故障注入后补。

### P4 公共事件与查询

- [ ] 数据面只导出已提交且经过隐私过滤的运行事实。Prompt、思考、原始 checkpoint、凭据与内部恢复内容不进入公开事件链路。
- [ ] 保留现有原生日志提交 hook 与补偿扫描；如无法同事务写 export outbox，则以原生日志提交位置作权威补偿来源，禁止出现提交后进程退出导致永久漏导出的窗口。
- [ ] Agent Service 按 source、原生 eventId、Session 和执行身份去重，持久化标准 event 后再推进消费位置；没有新增事实时不重复推送。先保存数据，再用通知唤醒 SSE。
- [x] 制定不同日志域的排序规则：准入事件先于 started，后端源日志按各自提交顺序，父子 Session 使用各自位置；不把不同源序号当作可直接比较的全局序号。
- [x] 定义新系统的稳定 eventId、Session 内公开 seq 和版本化 opaque cursor；快照包含对应的已提交游标，之后增量读取不能丢失或重复应用。同一新协议版本内不因副本或 transport 变化改写游标含义。
- [ ] 明确终态提交关口，最终 item、usage 和 required action 状态就绪后再发布对应 Turn 终态；延迟的补充事实使用既定更新规则，不能使 UI 永久停在终态前视图。
- [x] Session 和 Turn 查询共用同一份公开日志，Turn 只是过滤视图。Session 列表按公开读权限索引分页，Turn 按准入序号分页，事件和固定快照使用 Turn 索引筛选，不加载完整历史。后续权限共享刷新在 P6 接入索引。
- [x] 执行配置冻结和原生状态恢复由数据面负责；Agent Service 公共投影不作为 Harness 恢复日志。Checkpoint 恢复/fork 已有事务与实际进程证据。
- [ ] 建立标准事件映射 fixture，包括文本增量、工具请求和结果、人工动作、子 Agent、产物、用量、失败、中断与取消。其他后端在 P5 使用同一套映射验证。
- [ ] 公共投影支持多个消费者原子认领待处理事实，按 Session 有序归并并在提交时校验位置。SSE 使用批量增量读取及实例内连接分发，连接有界缓冲、重放限额及慢客户端退出策略。Agent Service 可按实际连接负载增加副本，读取和权限刷新均复用结果。

验收：任意 Agent Service 副本可按新 cursor 重连，snapshot 后继续 SSE 不丢事件；重复、乱序、通知丢失和投影器重启可恢复；终态与公开快照一致；公开事件中没有私有恢复数据。增加 SSE 连接数不会线性增加对控制面的请求数，慢连接不会拖慢执行器和其他连接。

本轮验证：V1、V7 的轻量 smoke；跑通 snapshot 后续传和事件终态，选择重复事实去重或公开字段过滤补一项关键测试。通知丢失组合、投影故障矩阵和重放压力曲线后补。

### 本轮优先级调整（2026-10-11 用户确认）

本轮重点是 Managed Agents 的能力完整、核心流程正确并能跑通。Team、Workflow 及其他辅助后端先完成公开受理、基本执行、标准事件和终态的代表性闭环；已有恢复、取消和完成屏障保留，影响基本正确性的缺陷继续修复。复杂编排、完整动态委派、独立 Team child Run 全矩阵、跨后端混合及其系统性回归放到后续实施清单，不再作为本轮 Managed 交付的阻断项。

下一步顺序：Managed 输入与控制命令的确定性拒绝/未知接收补偿 → 状态恢复与配置/权限更新 → Tool Worker 工具和文件/产物闭环 → Console 与 Java/Python/DSH 的 Managed 主流程 → 多组件部署与运行写权限收尾。共享运行存储的已开始容量改动先完成构建和关键验证，再按这个顺序推进。

### P5 其他后端与 Go 运行角色

- [ ] 在 Agent Service 实现 Managed、External、Hosted、Team 和 Workflow 的后端适配器；公共 Session 绑定 runtimeKind 和配置 revision，后端内部 ID 只作为关联字段，不绑定固定执行实例。
- [ ] 接入 Endpoint Invocation 的同步、异步和流式调用，明确 invocationId 与 Session、Turn 的对应关系、超时与取消传播。新 API 统一规范，不保留 Go handler 和 Java handler 两套公开准入。
- [ ] 复用 Go `internal/orchestration`、任务派发及 Runtime Host 协议，新增独立运行角色入口，例如待创建的 `cmd/managed-agents-orchestration-runtime`。控制面进程关闭这些执行循环，运行角色通过版本化配置快照获得必要定义。
- [ ] 将 AgentTask、ExecutionAttempt、编排 Run、运行事件和命令表的业务写入归到 Go 运行角色；资源定义及策略仍归控制面。先可使用同一物理数据库，分别配置表归属及账户权限。
- [x] Runtime Host 的 register、claim、renew、checkpoint、events、complete、fail 和 cancelled 公开协议经 Agent Service 的机器身份适配器进入 Go 运行角色。保留 task scope 和 dispatchGeneration 检查，不把它简化为普通用户 JWT。补充只读 observe、物理停止证明及 task scope 工具确认 create/decision/ack；实际 daemon、MCP、取消、人工允许/拒绝与重复目录拒绝已通过本地 fake CLI 进程验证。
- [ ] Hosted 和 External 由适配器读取实际能力并转换事件。后端不支持原生 steer、inject、逐工具回调或 checkpoint 时，capabilities 明确说明，请求返回标准能力错误。
- [ ] 将 Team 和 Workflow 的父子任务、并行节点、暂停、信号、重试、取消清理和失败汇总映射到公开视图；保留内部拓扑和任务日志用于自身恢复。
- [ ] 协作 MCP 的公开入口放到 Agent Service，按工具语义调用控制面资源操作或运行角色；Issue 评论、审批或状态变化引发执行时，由控制面提交持久化运行意图，经 Agent Service 准入，不能在 CRUD handler 内直接启动执行。
- [ ] 每个后端输出 RuntimeFact 和 UsageReceipt，采用 P4 的公开投影；逐步移除 Managed 到 Go 公共事件的重复镜像，新系统中只保留必要的管理摘要。

当前进展：Managed、Hosted、Workflow 与 Hosted Team 的公开 Session 创建及根 Turn 执行已接通；
Workflow 根审批、信号、并行等待与最终节点分批观测，以及 Hosted Team 公开创建、
MCP 结论、动态并行节点和取消已有代表性验证。共享根 Issue 的 Workflow 子图审批、
信号和有限观察已接通；独立 Issue 子 Run、Agent 发出信号、暂停/重试、External/混合
执行及统一 Invocation 仍属实现工作，P5 不据此完成。

验收：同一种公开操作经过同一准入链路；后台编排与 Hosted 任务在控制面 HTTP 进程停机时可按有效授权继续协调；控制面不再承担执行 worker。Team 并行与失败清理行为保留，能力差异可通过 API 查询。

本轮验证：V1、V2 的构建与选定测试；每个后端至少跑通创建或调用、执行、标准事件和终态，按支持能力验证一次关键交互。Hosted 和 External 可先用 fake provider 验证平台链路；实际供应商接入情况单独记录。完整 Go 编排和后端回归后补。

### P6 全局准入与权限刷新

- [ ] 将 Application、租户、队列和运行并发的限制统一放到 Agent Service 准入链路，多个 Agent Service 副本共享原子额度记录。Managed 和 Go 运行角色不再各自计算一份互不协调的 Application 并发限制。
- [ ] 明确队列额度、执行额度、人工等待和取消等待的占用规则；分别记录 reservationId、状态、授权版本、租约及释放收据。激活、显式恢复和子 Agent 不得重复预留同一份额度。
- [ ] 首期通过按 Application 的原子条件更新或行锁事务落实预留与释放，配合唯一 reservationId 去重；只锁本次操作涉及的记录，限制事务时间，并通过并发测试核对多 Agent Service 竞争下的限额。
- [ ] 配额租约失效的回收必须与执行 fence、取消和实际恢复协调；不能因心跳超时就把仍可能执行的额度立即重复发放。明确“权威许可数”与外部尚未终止的模型或工具调用的计量差异。
- [ ] UsageReceipt 以源事件及计量域去重，父子 Session、Hosted 和 External 用量统一归属；累计摘要、原始增量和已入账部分有明确界限，禁止两次相加。
- [x] Managed 按已报告用量检查后续模型调用并按收据去重结算；API、UI 和文档明确在途并发超额且无固定超额上界。其他后端的统一预算仍随跨后端准入项完成。
- [x] Managed 权限按 policy、principal、Application、冻结资源版本及挂载共享刷新；控制面用原子变更 outbox 传播权限失效，Agent Service 使用有限有效期、持久化收据和有界补偿。
- [ ] 完成各后端动态资源覆盖与绑定更新规则；Managed 的 Vault 凭据、Memory 和已绑定 Environment 配置已通过共享加密策略覆盖层在下一次执行激活时更新。其他后端沿用同一权限机制，现有 Sandbox 的 provider/isolation 变更继续审计。
- [ ] 保持 Agent 定义在创建时冻结，凭据、Memory 与 Environment 的可更新范围明确。内部执行授权带策略版本，运行激活、模型调用和关键工具操作检查必要的有效性。
- [x] Managed 权限刷新及重试采用抖动、有限批量、并发上限和超时预算；多个 Agent Service 从共享记录认领，使用独立刷新及通知调度器，过期拒绝新操作。
- [ ] 在其他运行角色接入后验证相同刷新和故障隔离机制。

验收：同 Application 的 Managed 和非 Managed 请求竞争时不会超发权威额度；重复恢复、取消、结果回传和用量汇总不重复记账。增加 Session 数不会产生按每 Session 固定频率全量读取控制面的请求风暴。

本轮验证：V1、V2 的相关检查；优先补跨后端配额竞争和用量去重的代表性测试，并 smoke 验证权限过期或范围拒绝。额度释放及回收的完整矩阵、撤权时序组合及热点压力后补。

### P7 Tool Worker 和 Sandbox

- [x] 将已提交的 external execution 请求转换为持久化 ToolTask，字段包括 Session、Turn、源执行与 toolCallId、环境、Sandbox scope、工作区引用、参数摘要、授权代次、超时及重试类别。
- [ ] 数据面拥有工具请求和结果接收协议；Tool Worker 经 Agent Service 的 Environment 范围授权认领具体 ToolTask，使用 claim token 及 lease，按可用执行槽位预取。取消和停机 drain 使用独立控制通道。
- [x] 区分 ToolTask 的授权代次与 Harness 每次激活的 execution fence。正常暂停后，原有效工具结果仍可进入结果 Inbox；取消、替换任务或重新执行工具后，旧结果必须受对应任务代次限制。
- [x] Worker 在开始副作用之前保存执行记录，执行后先保存结果及结果 outbox，再尝试上传。心跳在独立线程或调度器运行，长工具调用不能阻塞续租。
- [x] 数据面幂等接收结果，在原生日志和待办状态中确认应用，再认领同一公开 Turn 的下一次激活并续推理。多工具结果、部分结果和错误结果有明确聚合与恢复规则。
- [x] 对只读或具有供应商幂等键的工具实现安全重试；已经开始但无法确认结果的操作进入 unknown 状态，通过已有执行记录、供应商查询或人工选择恢复，不能凭 lease 过期自动重跑。首期只读尝试最多三次；未声明幂等性的写工具不自动重跑。
- [ ] 将服务 E2B 的创建、连接、执行、文件操作、TTL 和回收迁至托管 Tool Worker。数据面保存期望绑定与执行协议，Tool Worker 更新实际操作结果；并发创建同 scope Sandbox 使用共享绑定和 CAS，防止两个 Worker 各自创建实例。
- [ ] 保留 self_hosted 的出站连接方式、Environment key 范围与本地工作区；实现多任务有界并发、能力上报、健康、任务进度、取消和优雅停机。平台托管与 self_hosted 使用同一任务和结果协议。
- [x] 将 Worker 启动、工具执行器和输入 staging 的运行装配归入 `tool-worker`；跨服务共享协议与通用库，不让 Tool Worker 依赖数据面或 Scheduler 的可执行模块。
- [ ] 映射现有 Environment 的 SESSION、USER、AGENT 等隔离 scope，验证子 Agent 与父 Session 的 Sandbox 继承；明确暂停时保留策略、空闲回收、最大保活和孤儿回收。
- [ ] 区分运行恢复存储和 Sandbox 文件系统。原生日志、checkpoint、Memory 挂载与 Workspace 引导不依赖临时 Sandbox 存活；Skill、输入文件、shell 工作目录和文件工具使用一致的路径映射。
- [ ] Worker 获取执行该任务所需的最小凭据及授权，工具大结果通过产物引用回传；控制面负责凭据来源，Tool Worker 不接收无关 Session 的模型或 Vault 凭据。
- [ ] 移除待工具全历史扫描和服务场景下数据面直接操作 E2B 的路径。Core 和 Harness 的通用文件系统、内部工具与 standalone 用法保留在库边界内。

验收：托管 E2B 和 self_hosted 都可完成工具结果驱动的推理；Worker 执行后退出、结果重复、租约替换、取消和 Sandbox 重建的行为可恢复。结果只应用一次；外部副作用按工具可证明的幂等性处理。

本轮验证：V1、V6 的相关构建；跑通托管和 self_hosted 工具闭环、一次 Worker 或执行实例重启恢复，优先给结果重复应用增加测试。真实 E2B 环境具备时跑一次实际闭环，缺少环境则明确记录。完整租约、scope、TTL、未知结果和文件恢复矩阵后补。

### P8 剩余入口和存储边界

- [ ] Channel 回调和 Automation webhook 的公网入口经 Agent Service；协议适配器保留原始请求体、供应商签名校验、去重与快速回执，后续由 Channel Worker 消费持久化 ingress。
- [ ] Cron 和 Automation 触发使用稳定触发 ID，经 Agent Service 统一准入；出站消息和 `/api/outbound` 接口经 Agent Service 的投递命令进入 Scheduler，供应商回执和重复发送独立记录。
- [x] Session webhook：Agent Service 订阅、有限事件游标、fenced claim 与回执；Scheduler 有界发送、原始 read 权限复核、稳定 delivery ID 重试。核心真实权限/受限账号/Gateway/两组件重启证据见实施记录；完整故障矩阵后补。
- [x] Managed 文件、产物、预算、子 Agent、trace 和 checkpoint 的公开入口统一在 Agent Service；恢复转发数据面，下载核对归属，特权 trace 与公开事件分离。核心权限及多副本进程通过，全部矩阵后补。
- [ ] 完成 Runtime Host、Tool Worker poll、ack、heartbeat、pending、result 和观测 API 的 Agent Service 范围鉴权。执行端和调度器的接口只允许内部或受限机器身份访问。
- [ ] 数据面注册、心跳和 Hosted DistributedStore 列入内部协议，统一受限身份及服务归属；运行时锁、状态和总线由执行协调存储或运行角色提供。
- [ ] 按权威状态划分 schema 和数据库账户；Agent Service 不读写原生 checkpoint 表，数据面不写公开事件投影，控制面不更新运行租约。服务共用实体代码不能造成共用业务写权限。
- [ ] Workspace 使用配置化共享存储或可替换的对象及文件存储，定义文件命名空间、版本、校验、并发写入和访问授权；Sandbox 缓存与持久化工作区分别有恢复规则。
- [ ] 建立在线事件与历史归档、文件及工具结果的保留方案，按时间、状态及游标有限批量清理和恢复，不用无界全量扫描。现阶段只实施确定的保留配置，生产删除期限由 D9 确认。
- [ ] 新 UI 与 Java、Python、DSH 客户端统一使用新 Service API；更新 capabilities、错误、queued 与 runtime accepted、恢复、工具 unknown 和 SSE 续传交互。
- [ ] 新旧 Service 版本不能混用接口和数据 schema，客户端明确版本不支持错误；更新所有调用示例、中英文 Service 文档、部署和运维说明。
- [ ] 完成 Gateway 全路径检查，移除公网到数据面、Go 运行角色和 Scheduler 的直连路由；控制面 Session 概览使用 Agent Service 管理摘要或受权查询，不重建一套执行日志。

验收：公开运行 API 的路由清单全部落在 Agent Service；管理 API 留控制面；新 UI 和 SDK 全流程通过。按账户权限运行时，各服务只能写自己拥有的状态；文件与 Workspace 在跨副本执行和 Sandbox 回收后符合定义的恢复语义。

本轮验证：按修改范围选择 V1、V2、V3、V4、V6 的快速检查；跑通一次 Channel 或 Cron 触发、Webhook 回执、文件及客户端主流程。完整浏览器 E2E、SDK 回归和协议边界矩阵后补。前端只修改 `console/` 源码，生成 UI 由构建产生。

### P9 集成跑通与交付

- [ ] 更新 Java 模块列表、Go 运行角色、镜像 allowlist、Compose、Helm、Secret、端口、探针、RBAC、启动及关闭顺序；Agent Service、数据面、Go 运行角色和 Tool Worker 分别可增加副本，并配置各自并发与连接池上限。
- [ ] 在隔离新环境中以小规模多副本启动整套服务，跑通 Managed、其他后端、工具、标准事件、文件和客户端的核心链路。任意 Agent Service 可受理或续传，运行实例通过共享任务和租约协调，不依赖 sticky Session 或共享进程内 Map。
- [x] 命令重复、执行者替换、工具结果回传后恢复、SSE 重连和用量已有代表性事务/实际进程证据；完整故障矩阵后补。
- [ ] 检查队列、并发、缓冲和重试配置确实有界，做一次小规模并发与超限验证；完整扩容曲线、热点、重放风暴和长期稳定性测试记入后续清单。
- [ ] 核对每个后端、环境和公开入口的实际验证情况，记录 fake、真实环境、已有测试、手工 smoke 以及尚未验证的边界。
- [ ] 提供启动手册、配置示例、基础观测、schema 初始化、副本及并发配置和切流回滚步骤；记录本轮实际观察数据，不把它作为已完成的生产容量认证。
- [ ] 初始化独立的新运行环境和数据空间，验证资源定义与身份配置的导入；新客户端、新版本和新流量的切换方案成套交付。
- [x] 在[切换与排空操作手册](agent-service-cutover.md)明确旧任务排空、旧环境保留和回退条件；新格式状态由同一协议版本执行器处理。实际生产切流未执行。
- [x] 持续更新[后续测试清单](agent-service-test-backlog.md)，区分已有证据、待补自动化、环境依赖与尚未实现的机制。

验收：新架构能整体构建、启动并跑通所有核心流程，多实例认领、幂等、租约和背压已落实，代表性状态和恢复验证通过；新模块、配置、UI、SDK、文档和部署制品完整，测试欠项明确。完整测试轮次及生产切流独立安排。

本轮验证：V1 至 V4 的相关构建与必要检查、V6 部署检查、V7 核心 smoke；V5 全仓库回归、完整 faults、load、rollout 测试集中后补。交付报告区分实现完成、核心流程已验证和尚待系统性验证的项目。

## 6 必须保持的协议约束

1. 受理只在持久化成功后响应，queued、runtime accepted、applied、started、cancel_requested 和 terminal 含义分别明确。不同操作可以有不同受理层级，响应中不能混用。
2. Turn 的命令入口、执行恢复日志和公开事件投影分别有唯一权威来源；两套独立状态机不能同时认领同一执行。
3. 原始调用者、Session owner、人类批准者、Application key、Environment Worker 和 Runtime Host 的身份不可互相替代。拒绝准入在调度前执行，授权过期和撤权在必要执行边界复核。
4. 传输允许重复，业务效果去重；未知外部副作用不能被“至少一次投递”自动变成重新执行。承诺的结果是有效结果只应用一次，不宣称通用外部工具天然具备 exactly once。
5. 串行顺序以 Session 为单位，不同 Session 可由多个实例并行执行。子 Agent 保留独立原生日志及根调用者归属，不能因 root Turn 不同而绕过权限或用量归集。
6. 运行暂停时，工具任务、配额、Sandbox 与执行租约分别按自己的生命周期管理。有效待办的结果接收不能机械要求旧 Harness fence 仍然有效。
7. 所有读写、队列、预取、批处理和缓冲均有上限；慢模型、长工具、慢 SSE 和撤权刷新不共用一个会被阻塞的执行槽位。
8. 多实例竞争和恢复通过共享任务状态、执行租约和 fence 协调。只有旧执行权被撤销或失效后才能取得替代执行权；无法确认共享租约有效时停止继续执行，旧实例的后续写入必须被拒绝。
9. Session 列表、待工具查询、权限刷新和事件读取使用索引、有限批次、版本或游标。禁止把全表扫描、全历史扫描和逐 Session 全量轮询作为正式实现。
10. 新 Service API 可以重建，但可复用 Core 和 Harness 仍遵守库的兼容性、RuntimeContext、取消与 middleware 契约。适配行为用扩展点实现，不 fork Agent 类。

## 7 本轮快速验证与后续测试

本轮每个变更包按实际范围构建和验证，优先复用已有测试。核心链路使用薄的 smoke 脚本或直接调用验证，不先建设完整测试平台。跨模块或公开协议变化也按本轮快速迭代约定选择验证范围；全仓库回归集中后补。

优先为以下容易破坏流程的逻辑增加少量行为测试：幂等受理、旧 fence 拒写、工具结果去重及恢复、跨后端配额与用量、关键身份范围校验。每项选择代表性场景，不展开所有状态和故障组合。已发现的核心错误先修复，测试覆盖欠项进入 [后续测试清单](agent-service-test-backlog.md)。

### V1 Java 构建与选定测试

在仓库根目录按修改范围选择模块。当前数据面构建示例：

```bash
mvn -T1 -pl managed-agents/agent-harness-runtime/java/brain-agentscope -am -DskipTests package
mvn -T1 -pl managed-agents/agent-harness-runtime/java/brain-agentscope spotless:check
```

P1 创建模块后把 Agent Service、Tool Worker 及其他实际修改模块加入选择列表；修改 Core、Harness 或 extension 时检查对应依赖链。`-DskipTests` 用于构建和打包，不代表测试通过；现有测试源码编译问题按新契约做必要适配。

当前工作区的选定测试命令示例：

```bash
mvn -T1 -pl managed-agents/agent-harness-runtime/java/brain-agentscope -am -Dtest=SessionInputServiceTest -Dsurefire.failIfNoSpecifiedTests=false test
```

实施时使用实际测试类名，并核对报告确认该类确实执行。允许上游模块没有同名测试，不允许把目标测试未执行记作通过。必要数据库竞争测试使用隔离 PostgreSQL，小规模并发即可；新增 extension 时仍更新父模块、distribution、BOM 并检查同步。Java 源码保持项目 Java 17 语言目标。

### V2 Go 构建与选定行为

工作目录 `managed-agents/control-plane`：

```bash
make build
```

P5 把独立运行角色加入构建。按实际变更选择 package 和 `go test -run` 的代表性用例，不在每个阶段运行 `make test` 或全量 `go test ./...`。需要数据库行为验证时使用隔离 `CONTROL_PLANE_TEST_POSTGRES_DSN`，共享同一测试库的包按现有规则串行执行。完整 Go 回归、vet 和 controller envtest 集中后补。

### V3 UI 构建与主流程

工作目录 `managed-agents/console`：

```bash
npm run build
```

按变更选择相关 Vitest 或 Playwright 用例，也可在隔离环境完成主流程 smoke。重点核对创建、流式输出、交互、恢复和文件行为；完整浏览器矩阵和全量单元测试后补。记录实际使用的入口和验证结果，不通过削弱断言隐藏失败。

### V4 SDK 与文档必要检查

SDK 本轮完成构建或导入检查，以及必要的真实调用或 fixture smoke；按修改范围选择少量已有测试，不要求每次完成全部 SDK 回归。DSH 工作目录 `managed-agents/control-plane/sdk/dsh`：

```bash
npm run build
```

文档工作目录 `docs`，修改对应用户文档时执行静态检查：

```bash
npm run check
```

用户可见行为同步更新 Java、Python、DSH 客户端、调用示例和 `docs/v2/en/service/`、`docs/v2/zh/service/`；完整 SDK 回归、文档测试和 Mintlify 验证后补。

### V5 后续全仓库回归

以下检查保留为后续测试轮次的入口，不作为本轮每阶段门槛。仓库根目录执行：

```bash
mvn -B -T1 clean verify
```

后续同时执行完整 Go、UI、SDK、文档和发布回归，核对条件测试是否实际启用。覆盖计划、优先级和完成状态以独立测试清单为准。

### V6 部署构建与启动

本轮按部署变更执行相关镜像构建、Compose 新环境启动和核心 smoke。Helm 修改时在仓库根目录检查：

```bash
helm lint managed-agents/helm/managed-agents
helm template managed-agents managed-agents/helm/managed-agents
```

按需运行发布源码检查与选定发布配置测试，更新 `release.py` 的模块及镜像映射。完整 `release.py verify`、发布测试、Kubernetes 安装升级、RWX 及架构矩阵集中后补。本轮默认交付可运行制品和操作方案，实际生产切流另行安排。

### V7 核心多实例 smoke

从 P3 起随实现积累薄的跨进程 smoke，优先复用现有启动和调用工具。验证小规模多 Agent Service、多运行副本及 Worker 下并发请求的核心闭环，保留配置、命令和关键输出；不在功能完成前建设完整的故障注入与负载测试框架。

选定场景包括：同一幂等键重复提交、跨副本 SSE 续传、一次旧执行者拒写、工具结果重复或重启后应用、一次配额竞争。使用 fake 模型和工具降低外部不确定性，真实供应商环境的验证情况单独记录。

后续在待创建的 `managed-agents/tests/distributed/` 下扩展 correctness、faults、load 和 rollout 验收工具，统一记录种子、实例及并发配置、故障时间点和容量结果。该完整工具不属于本轮前置条件。

### 验证记录规则

阶段报告分别列出构建结果、核心 smoke、实际执行的自动测试、fake 或真实环境，以及未执行项目。不运行、不适用和缺少环境分别记录；已有测试可迁移或复用时先核对覆盖，避免重复编写。本轮结束交付可运行流程及持续更新的测试清单，不把尚未执行的系统性测试标成完成。

## 8 切流和回滚方法

具体准备、资源导入次序、验收、旧任务排空与回退动作见[切换手册](agent-service-cutover.md)。实际生产操作独立安排；手册不代表全部阶段已经完成。

新架构使用新的运行 schema 和协议版本。资源定义及身份配置可从现有控制面导入，新的 Session 从新环境开始；历史 Session 和旧 API 兼容不属于默认实施范围。

切流顺序为：初始化新环境与配置 → 完成 fake 和真实后端验证 → 新 UI 和 SDK 指向新协议 → 将新请求切到新环境 → 排空旧环境任务 → 验证稳定性与对账 → 退役旧入口。每一步记录环境、版本、运行归属和回退条件。

回退新流量时，新系统中已受理的 Session 继续由同一新协议版本的运行环境处理或 drain；不能交给不认识该格式的旧执行器。Gateway 回切只能改变新请求入口，不能使同一 Session 同时被新旧系统执行。新环境升级优先使用可回退的增量 schema；破坏性变更需要独立版本或先完成状态转换。

旧数据库、Workspace 和运行日志的保留期、关闭及删除在生产操作前确认。允许不兼容重建意味着可以新建数据空间，不自动触发旧数据清理。

## 9 实施起点与阶段交付

实施从 P0 开始，按以下顺序完成起点任务，再继续完成后续阶段：

1. 基于当前工作区锁定路由清单和新 API 的职责归属，确定 Session、Turn、Command 和事件的统一命名与状态语义。
2. 明确共享任务认领、权限与准入租约、执行 fence、配额占用、实例并发和重试机制，给出新协议 fixture。
3. 实现 Harness 暂停后跨进程应用工具结果的最小行为测试；发现库边界问题先修复并验证。
4. 建立当前代码可运行基线和轻量隔离环境，再创建 Agent Service 和 Tool Worker 模块骨架；随实现逐步补充核心 smoke。
5. 从 Managed 的创建、Turn 受理、执行和 SSE 完成第一条独立 Agent Service 闭环；随后按 P3 和 P4 拆开持久化命令与公开事件链路。

每个阶段交付时说明：已实现的可见行为、变更文件及协议、实际执行的验证与结果、待补测试及现有验证证据、保留的临时实现、下一阶段需要的决策。每完成一项关键机制，同步更新后续测试清单。若数据库、消息系统、配额语义、Sandbox 成本或生产切流出现需要取舍的约束，先给出具体选项和影响，再推进依赖该选择的工作。

本轮完成以第 1 节的架构实现目标和 P9 核心闭环验收为准。P2 的服务分开部署、P4 的事件闭环或 P7 的工具执行单独通过，都只是阶段完成。完整测试补齐作为后续独立工作，不阻塞本轮快速迭代。

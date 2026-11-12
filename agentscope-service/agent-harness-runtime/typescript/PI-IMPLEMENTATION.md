# PI Brain 实施改造

[English](PI-IMPLEMENTATION.en.md)

2026-10-28。工作目录 `/Users/ken/agentcore/agentcore-managed-agents`，当前分支 `master`。本计划承接 PI / PI Durable 接入评审；状态以代码和本页验收记录为准，不把方案或单元测试描述为生产验收。

2026-11-02 更新：历史 Codex 探针及其测试已删除，TypeScript workspace 现仅有 Runtime、PI Adapter 和 PI Brain 三个包。下文各批次测试数量保留当时结果；Codex 当前实现与验收见 [Rust Brain](../rust/README.md)。

## 目标与边界

一个独立 Node 22 Brain 镜像包含 TypeScript Runtime、PI Adapter 和 PI Durable Adapter，启动时只选择一个内核。Session 固定绑定内核和原生格式。Brain 负责推理、原生上下文与恢复、工具决策、输入和执行协调；Session Service 负责所有权、事务、预算、对象、Outbox 和结果接收；Hands 负责实际工具动作。Brain 不持有 DB、Redis、MQ、对象存储凭证，不需要 Java Brain 中转。

实现集中在 `typescript/`。同仓有其他并行改造，保留现有未提交改动；跨组件链路使用独立 Java 联调测试验证，联调证实的服务端断点做最小修复，共享协议、Helm 和发布入口的改造必须显式记录并保持默认 Java 行为兼容。涉及这些组件的配套列为依赖项，只有实际联调后才标记完成。源码、文档与测试在当前主目录修改，不提交或推送。

## 基线与问题

已具备真实 PI SessionManager / Durable Harness 接入、服务端认领和续租、fencing、提交回执、工具身份去重、原生结果应用关联、确认输入以及 Node/Docker 启动骨架。Durable Storage 在远端 ACK 后才暴露本地状态，保存 ID 分配进度。

评审确认的问题：普通 PI 扩展事件吞异常导致预算门禁无法可靠阻断模型请求；Durable 把消息数组传给单消息输出转换函数；用户取消回调未接入两个 Adapter；普通 PI 工具失败缺少 `isError`；HTTP 5xx/408/429 不走不确定提交回执恢复。现有测试主要覆盖协议与 Storage，缺少实际 Adapter 推理路径。

其余差距包括长工具/审批挂起释放、完整快照增长、辅助模型计量、实时事件/steering、平台 Skills/记忆/资源/子 Agent/checkpoint 能力以及镜像交付与真实故障验收。

## 分阶段实施

| 阶段 | 内容 | 验收 | 状态 |
| --- | --- | --- | --- |
| A：正确性 | 阻断式模型准入、最终输出、工具失败、用户取消、不确定回执 | 真实 SDK + 本地模型替身驱动两种 Adapter；拒绝后零请求；正常/恢复结果一致；取消可结束且仍能提交 | 已实现，组件验证通过；真实环境验收待执行 |
| B：协调与故障 | 长工具/人工确认挂起，停止内核，释放并发，唤醒后使用原 executionId；失败分类、恢复退避和可诊断日志 | 等待不占并发；旧 owner 不能继续；已授权动作不重发；取消、停止、挂起互不混淆 | 挂起/重建/审批与关联日志已实现；HTTP 自动唤醒、新进程恢复和容量释放验收通过；重试治理、限时退出已实现；真实基础设施验收待完成 |
| C：存储与模型治理 | 分段日志与周期快照；恢复分页/上限；所有模型路径含压缩的预算与计量；模型参数/凭证范围 | 长会话增长与恢复基准；提交失败不暴露状态；压缩不绕过计量；恢复不复用 ID | 分段快照、恢复分页、辅助模型准入与安装级封存模型配置已实现；原生压缩、长期存储回收和租户凭证治理待完成 |
| D：平台能力 | Skills、记忆/资源、产物、多模态、事件增量、输入 steering、子 Agent、checkpoint 管理 | 能力矩阵逐项声明；不支持的功能明确拒绝，不能静默忽略 | 待对接 |
| E：交付 | PI 镜像验证；Helm 内核选择、探针、Secret、移除 Java schema Job 假设；release 构建、指标/Trace、滚动升级 | 两种内核独立启动；Linux 目标架构；真实平台依赖及故障验收 | Node 生产依赖打包、Helm/Compose、PI release 入口和基础指标已实现；镜像构建、完整 Trace 与发布验收待完成 |

普通 PI 使用原生会话快照，由 Adapter 保证恢复边界；PI Durable 使用原生 Storage 和任务恢复。共享平台 Runtime，不强行统一原生状态格式或把两种恢复保证等同。

## 实施约束

- 模型准入必须在真实调用之前完成，不能依赖会捕获异常的扩展通知；失败必须能传回协调层，即使 SDK 将异常转成模型/工具错误。
- 只有原生工具结果进入可恢复状态后才能提交 resultApplications；平台中工具 unknown 不能触发新外部动作。
- 用户取消要中止推理/等待但保留提交通道；owner 失效或进程停止必须终止新提交。Hands 动作取消须遵循平台执行账本，不以重建任务替代取消。
- 原提交响应不确定时查同一 commitId 回执；不得换身份重发授权。确认持久化之前，不推进本地可见状态。
- 挂起必须提交原生恢复点和 continuation 后才释放；不能把 SDK 普通工具异常当作安全挂起。
- 大对象优化保留已提交前缀、内容摘要、作用域校验和原生 ID，不只截断历史数组。Service 的恢复 boundary 数量上限也必须一起设计。
- 本地自动发现 Skills/插件/工具保持关闭；平台资源显式装配。辅助模型调用也必须纳入预算。

## 跨组件依赖清单

1. Session Service：分段恢复与查询分页、模型参数/凭证引用、公开事件、资源/Skills 描述、平台 checkpoint 操作能力；保持与已封存 Turn 配置一致。
2. Hands / Service：长工具与确认唤醒、取消传播、迟到结果、unknown 对账与产物映射。
3. Helm / release：现有 Java actuator 探针、Java schema Job、Java Brain Dockerfile 不能直接用于 PI；按内核选择配置和发布制品。
4. 运维：模型凭证注入、受控可写临时目录、资源限制、退出期限、session/turn/task/modelCall 关联日志、指标和 Trace。

## 验收矩阵

- 两种内核分别：无工具对话、多 Turn、短工具、失败/取消工具、多 call、审批允许/拒绝、附加输入、取消和模型预算拒绝。
- 恢复窗口分别：工具授权前后、外部副作用后、结果接收后、原生结果持久化前后、最终提交后响应丢失；重建后复用原 task/execution，结果不丢失。
- 模型与基础设施：模型中途断流、超时、预算不足、压缩、Service 5xx/408/429、失联、租约失效、SIGTERM、SIGKILL、双实例接管。
- 容量：长会话、日志分段、对象上限、恢复上限、并发等待、停机 drain、背压。
- 本地检查：`pnpm -r build`、`pnpm -r test`、`pnpm -r check`。真实 SDK + 替身只证明组件契约，不能替代真实 PostgreSQL/MQ/Redis/Hands/模型和容器故障验收。

## 验证记录

评审基线：5 个包构建和类型检查通过；10 项测试（Runtime 3、PI Storage 4、Codex 3）。最小复现确认 Durable 数组输出为空、普通 PI provider 事件吞异常、JSON HTTP 503 不查回执。未构建镜像或部署。

### 2026-10-28 第一批实现

- `adapter-pi/src/models.ts`：在实际 `stream/streamSimple/complete/completeSimple` 调用前预留并提交模型请求，响应进入统一计量队列；不再依赖吞异常的 PI 通知。持久化/准入错误保留原始原因并停止内核，即使 SDK 将异常转换为普通响应。原生压缩使用的 `completeSimple` 同样经过该入口。deferred、图像生成、分类路径尚无平台契约，显式拒绝，不绕过准入。
- `pi.ts`：正确传递工具 `isError`；拒绝审批的恢复路径补入原 call 的错误结果；使用内存 Settings 并关闭 cache warming；模型/工具基础设施故障不固化为原生业务结果。
- `pi-durable.ts`：使用当前根 Conversation 的原生 context 提取最终助手消息，不再扫描全历史并误传数组；用户取消通过原生 Conversation.abort 收敛，致命基础设施错误单独中断调度，保留恢复点。
- `runtime/src/index.ts`：分离工作取消信号与结算信号；5xx/408/429 查原回执；等待工具/审批超时后提交现有协议的 suspension，ACK 后立即冻结提交通道；协调层识别已挂起执行，不提交 terminal。
- `runtime/src/worker.ts`：支持 `BRAIN_INLINE_WAIT_MS`（默认 1000，范围 0..600000；0 仍先查询一次），Turn 配置中的 `inlineWaitMs` 优先；故障日志增加 scope、turnId 和内核。挂起返回后释放 active slot。

测试覆盖两种真实 SDK 的正常输出、完成后恢复不重调模型、多 Turn 上下文、预算拒绝零请求、工具失败、运行中/启动前取消、基础设施故障后恢复，以及真实 RunHost 的 unknown 工具挂起、审批挂起后允许/拒绝、原 call/execution 复用和最终结算。HTTP 测试覆盖不确定响应查回执、缺失回执同身份重试；Runtime 测试覆盖取消仍可结算、挂起 ACK 后禁止新提交、挂起失败不误报交接成功。

本批结果：`pnpm -r build`、`pnpm -r test`、`pnpm -r check` 通过；39 项测试通过（Runtime 11、PI 25、Codex 3，较基线增加 29 项）。修改的 TS/MJS 文件通过 Prettier 3.6.2 检查。使用已有 `/tmp/managed-agents-protocol-venv/bin/python` 执行协议检查：8 份 Schema、14 个 API、82 正例、23 反例及 Node 身份/JCS 向量通过。系统 Python 缺少 rfc8785，未改动系统环境。

第一批验证使用真实 PI SDK、本地模型 Provider 和内存协议对端，不连接真实模型、MQ、Redis、数据库或 Hands。尚未完成分段日志、平台资源能力、重试治理、容器/Helm/release 和真实依赖故障验收。按用户后续确认，先完成下述跨进程主链路验收，再补故障分类与恢复退避、存储增长治理；涉及 Service 恢复分页的改造需与并行工作对齐。

### 第二批：跨进程 Brain → Hands → Brain 验收

入口：在 `typescript/` 执行 `pnpm test:http`。脚本先构建 TypeScript，再从仓库根目录以 `-T1` 运行 `PiBrainHttpFlowTest` 和 `HarnessProtocolStoreTest`。需要 Node 22、pnpm、Java/Maven、Python 3 和 shell；Java 常规测试默认跳过该用例，必须显式传 `-Dmanaged.pi.http=true`。JUnit 失败会附带各 Brain 的日志。

测试启动独立 Node 进程，使用真实 `runWorker`、PI/PI Durable SDK、HTTP SessionClient、Java SessionServiceApi/HarnessProtocolStore、Hands Command Publisher、Worker Store/Loop、Result Publisher 和生产 Sandbox 执行器。Session Service 与 Hands 使用各自独立的 H2 数据库。shell 真实执行并写入计数文件。模型 Provider 为确定性替身；运行配置预写入服务端封存配置缓存，不覆盖 Control Plane/Vault 配置生成。MQ、租约后端为内存实现；对象存储为临时本地目录。

四种场景为两种内核各自的短工具与长工具接管：

1. Worker 自行轮询 `executions/next`、认领并通过 HTTP 恢复/执行，不直接调用 `runClaimed`。
2. PI 生成工具调用，Service 原子保存原生状态和授权，Hands 消费 Outbox 并启动真实 shell。
3. 长工具等待期间，Brain 保存 suspension；检查 Turn=`waiting_tool`、服务端租约删除和 `/readyz` 的 `active=0`，确认 Hands 已写入第一次副作用后，再 SIGKILL 原 Brain。
4. 释放 shell 的等待条件，真实结果经 Hands Publisher 和 Service 接收器入账。新的 Node 进程通过 `next → claim → recovery/read` 自动接管，恢复原调用结果后继续模型请求。
5. 检查物理执行一条、副作用一次、模型调用两次、Turn 成功结算、公开事件包含最终答案。所有成功边界响应改为 HTTP 503，覆盖授权、挂起、原生结果应用和最终提交后的回执恢复。

联调发现并修复两处调度问题：

- `HarnessProtocolStore.next()` 原先用 `t.resume_id = r.resume_id` 查恢复任务；`resume_id` 在认领后才写入，因此第一个恢复任务永远不可见。改为匹配 Turn 当前 `suspension_id` 与恢复 Outbox 的 suspension，既能发现尚未认领的恢复，又排除旧 suspension。
- 原查询在排除未就绪的挂起 Session 之前应用 `LIMIT`，导致排在最前面的长工具等待占据轮询窗口、饿死其他 Session。将可运行/恢复就绪条件下推到 SQL 分页之前。

Service 回归测试覆盖：结果未到不可认领；单并发下跳过最早的未就绪 Session、返回另一个可运行 Session；结果到达但 resume 尚未激活时可发现；认领后不能被重复派发。

待生产环境验收：真实 PostgreSQL/Redis/RocketMQ、Sandbox 远程控制面、模型 HTTP 与真实凭证、容器重启/滚动发布、双 owner fencing、审批与取消的跨组件传播。当前机器测试端口未监听，Docker 服务查询无响应，尚未进行镜像构建或部署。


本批验收：PI / PI Durable × 短工具 / 挂起后新进程接管共 4 项全部通过（最终全量构建中 12.41 秒）；Service 协议回归 6 项通过，含未就绪 Session 不占轮询窗口和未激活 resume 可见性。TypeScript 5 包构建/类型检查、39 项现有测试、Prettier、shell 语法检查均通过。`mvn -B -ntp -T1 clean verify -Dmanaged.pi.http=true` 全部 12 个模块通过（6 分 31 秒）；`mvn -B -ntp -T1 spotless:check` 通过。外部基础设施测试仍按其环境开关跳过，不视为真实依赖验收。所有修改仍在上述主目录 `master`，未提交、推送或部署。


### 第三批：恢复治理、长状态、模型与交付

1. **失败分类与退出。** Worker 区分 ownership、配置/鉴权和暂时故障；所有权冲突不延后别人的任务，配置错误停止接单并退出，暂时故障使用指数退避和抖动。原 delivery + deferralId 在不确定响应时原样重试；有 owner 的延后携带 fence，Service 校验后同时更新 HTTP 调度期限和 resume outbox，不释放 owner。达到重试预算后进入最大间隔冷却，不能把未知工具结果转成 terminal。SIGTERM 停止接单、先排空，超过期限再中断，内核不响应中断时 Worker 有界返回失败。readiness 依赖 Service 轮询，新增低基数 Prometheus 指标。
2. **分页恢复。** `recovery/read` 增加可选 `pageSize=1..16` 和 `cursor={afterRevision,throughRevision}`，响应可带 `nextCursor`。旧请求仍是单次完整读取并保留原上限。PI 默认每页 16 个边界，固定 native head/brainRevision/snapshotId，跨页版本变化拒绝；读取期间续租，分页 bundle 大于 512 KiB 改为对象引用。工具结果与输入是读取时的观察值，后续仍通过原 tool/input 接口对账。客户端累计限制 512 MiB / 100 万条事实；未分页的 pending input 上限仍为 256。
3. **分段原生快照。** 小状态继续完整 JSON；PI 的 `entries`、Durable 的 `commits` 达到 128 项或 256 KiB 后使用不可变片段。每段最多 128 项，目标 512 KiB；较大单项最多 8 MiB。manifest 存 metadata/顺序/条数/总大小，全部片段显式列入 `nativeState.journalRefs`。内容摘要缓存复用未变化前缀，重启读取后也恢复缓存。每个恢复点仍包含完整逻辑状态，不截断原生记录、不重分配 ID。最多 1024 个片段、512 MiB 数据、512 KiB metadata；超过限制明确拒绝。Durable 的 mintId 不再深复制全历史；新 commit 只深复制新 writes，旧前缀冻结，远端 ACK 后才推进本地状态。
4. **封存模型配置。** Service 的 `MANAGED_AGENTS_PI_MODEL_OPTIONS` 支持 api/baseUrl/credentialEnv/contextWindow/outputLimit/cost/parameters，与 Agent 版本中的 model id 一起缓存到 Turn。Schema 拒绝密钥正文与未知字段。Brain 默认使用内存 CredentialStore，不发现本地 auth/models 文件；自定义 endpoint 要求显式 `PI_MODEL_` 凭证名，自定义模型要求显式价格和 token 限额。temperature、reasoning 和 maxTokens 进入普通/辅助调用与预算请求事实。另修复 Durable 的 SDK `root(options)` 仅首次创建应用配置的问题：恢复 root 后显式应用本 Turn 的封存定义，保证下一 Turn 的模型切换生效。
5. **交付配套。** [镜像部署说明](../images/pi/README.md)给出配置和升级顺序。Helm 新增 `brain.type=pi|pi-durable`，使用独立 Secret、只读根目录、临时目录、Node 探针和足够退出期限；默认 Java 部署不变，PI 不创建 Java Brain dataplane schema Job。Compose 补退出窗口和模型参数。`release.py images-pi` 只构建 PI 镜像，支持既有 digest/source/platform 校验、SBOM/provenance，单独保存 PI 元数据；未推送。

**格式兼容与边界：** 新代码可读旧完整快照，逻辑原生 `stateFormatVersion` 不变；对象编码使用 `managed-pi-snapshot-v1` / `managed-pi-segment-v1`。manifest 的 `version` 不等于原生的数字 1，旧 Adapter 会拒绝它，不能将旧镜像直接回滚到已分段的 Session。先升级 Service，再升级 Brain。当前优化减少重复上传与深复制；SDK 仍在内存还原完整历史，Service 提交仍校验所引用的全部片段，尚不是有界内存、常数成本的长期存储。不可把本批描述为已完成原生压缩、历史对象 GC 或周期归档。

**当前仍待完成：** 真实 Linux 镜像与双架构启动/滚动回退；真实 PostgreSQL/Redis/MQ/远程 Sandbox/模型与凭证验收；持久累计重试预算、告警与人工隔离；原生自动压缩专项、GC 和大规模恢复基准；Control Plane 逐模型配置及租户 Vault 凭证下发；流式公共事件/实时 steering、Skills/记忆/资源/产物/多模态/子 Agent/checkpoint 平台能力；完整 Trace 与跨组件审批/取消/双 owner 故障验收。原有 Brain 挂起 → Hands 执行 → 结果入账 → Brain 接管链路继续纳入回归。


第三批验收记录：

- TypeScript 5 包 build/check 通过，60 项测试通过（Runtime 25、PI 32、Codex 3）；覆盖 Worker 分类/退避/退出、5001 边界读取、分段复用与损坏检测、参数/凭证配置、本地真实 HTTP 流和跨 Turn 模型切换。修改的 TS/MJS 通过 Prettier。
- Java 定向回归 14 项通过：Service 协议 9、模型配置 1、跨进程 PI 链路 4。长工具场景增加 300 KB 原生内容，检查真正写入分段对象，在 SIGKILL 后以新进程恢复，仍只执行一次工具；所有边界 ACK 继续改为 503 以覆盖回执恢复。裸 WebFlux 测试显式使用与生产 application.yml 相同的 90 MiB 对象传输额度。
- Helm 21 项、release 26 项相关测试通过；协议当前 9 Schema / 17 API / 102 正例 / 29 反例及 Node 向量通过。完整 release 测试 65 项中，既有 `test_helm_cloud_connections_override_stale_workload_and_schema_secrets` 缺失 `queryStore.existingSecret` 导致 1 项失败；其云 Secret 顺序约定也已落后于当前模板，本批未修改该并行范围。
- 使用镜像固定的 corepack pnpm 10.28.2 独立 production deploy，包只包含项目 dist 与生产依赖；两种打包后入口均可启动、ready，并在 SIGTERM 后 exit 0。本机为 macOS；Docker daemon 在 8 秒内无响应，未构建/推送 Linux 镜像，也未部署。
- 已执行根目录 `mvn -B -ntp -T1 clean verify -Dmanaged.pi.http=true`，在 Java adapter 的 Javadoc 因并行目录改造中的 `ManagedTurnAckLedger` 无法解析而失败，后续模块未执行。根目录 spotless 检查另被 common 下并行文件格式阻断。定向回归使用 `-DspotlessFiles=.*(HarnessProtocolStore|HarnessProtocolStoreTest|HarnessRunConfiguration|HarnessRunConfigurationTest|PiBrainHttpFlowTest).java` 验证本批 Java 文件，未批量格式化别人的改动。因此本批不能宣称全仓构建绿灯。

详细日志：`/tmp/pi-batch3-ts.log`、`/tmp/pi-batch3-http-scoped.log`、`/tmp/pi-batch3-helm.log`、`/tmp/pi-batch3-release-scoped.log`、`/tmp/pi-batch3-full.log`、`/tmp/pi-batch3-spotless.log`。源码仍在主目录 master，未创建 worktree，未提交、推送或部署。

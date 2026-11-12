# Agent Service 重构实施记录

本记录对应 [完整实施计划](agent-service-refactoring-plan.md)，持续更新实际代码和验证证据。目标仍是完成 P0 至 P9；模块可启动或协议测试通过只代表当前步骤完成，不能代替整体架构验收。

代码位置：`/Users/ken/agentcore/agentcore-managed-agents`，原实施分支 `codex/automatic-acr-release`；新项目当前使用 `master`。保留开始实施前的改动；未提交、推送或操作生产环境。

## 当前状态

| 阶段 | 状态 | 已有证据与剩余工作 |
| --- | --- | --- |
| P0 | 进行中 | 数据面依赖链和 Go 制品构建通过；远程工具暂停、跨 JVM 恢复及重复结果保护通过。API 归属源码清单、状态契约及容量实验场景已记录；统一契约 fixture、Workspace/Memory/Skill 关系与实际容量曲线继续完成 |
| P1 | 进行中 | 独立模块、二十五类 Java/Go 协议、共享 fixture、Agent Service 注册表、数据面快照表及执行授权已建立。独立迁移与普通副本版本校验、部署业务账号已接通；Go/Scheduler 逐表/列权限、更多后端适配继续实施 |
| P2 | 进行中 | Agent Service 创建、单 Session 查询、索引列表、事件分页和 SSE 接通；独立进程和各自数据库重启恢复通过。归档、恢复、删除及版本化数据面镜像已接通；Gateway 的主 Session 路由及部署制品已接通；PATCH、所有旁路入口和完整安装继续实施 |
| P3 | 进行中 | Agent Service Turn 原子准入、持久化投递和数据面命令 Inbox 已接通；执行授权、Harness 执行、inject、steer、取消、显式恢复与原生人工批准已跑通；外部结果和 Tool Worker 回传已跑通；原生日志已使用独立 SQL 事务及索引，长历史恢复和完整并发边界继续完善 |
| P4 | 进行中 | 事实去重、来源顺序、公开 seq、用量/额度事务、批量传输、事件分页、SSE、资源快照与有界原生导出已接通；Session/Turn 列表、Turn 事件和快照过滤已接通；本地子 Harness 的独立事实、子游标/快照/SSE、根授权及用量汇总已跑通；同步子工具、部分结果、父续跑及重启后根取消已跑通；异步恢复与跨后端映射继续实施 |
| P5 | 进行中 | 独立 Go 运行角色、Hosted/Team/Workflow 根与子图、取消、确认/信号及部分产物已接通；实际 daemon 和代表性重启通过。Agent Signal 的 MCP/Task CLI 持久化闭环及重启通过；External/混合后端、完整 Team 委派与恢复继续实施 |
| P6 | 进行中 | Managed 原子准入、共享有限期策略、独立刷新、控制面撤权 outbox 及下次激活的凭据/Memory/Environment 配置覆盖已接通；跨后端额度、绑定 PATCH 和 Sandbox 变更规则继续实施 |
| P7 | 进行中 | self_hosted 和托管 E2B Worker 共用持久化 ToolTask、有限授权、独立心跳及本地结果 outbox；两类进程闭环通过。共享 scope 绑定、创建 CAS 和禁止未知副作用自动重跑已接通；两类 Worker 崩溃后的显式重试与共享健康/容量已验证；文件归档、供应商失效重建、空闲回收与显式绑定修复已跑通；子工具继承根授权与 Workspace 已跑通；挂载与部署继续实施 |
| P8 | 进行中 | Managed 文件/输入、显式产物、Go 任务产物下载、Java/Python/DSH Session 客户端、Gateway 主路由已接通；Webhook 的 Agent Service/Scheduler 重启及 Gateway 真实权限闭环通过；预算、checkpoint 分页/恢复/fork、特权 trace 和 Console 恢复/预算/公开事件导出已接通；Channel/Cron、绑定 UI 和全部存储边界继续实施 |
| P9 | 进行中 | 七组件制品/迁移/账号接线和真实 Managed/Tool Worker 源码进程通过；全后端多副本部署、资源导入与排空/回退交付仍需完成 |

## 已落实的决定

- Application `maxConcurrent` 继续包含 `queued`、`running`、`cancel_requested` 和 `requires_action`。各执行实例和 Worker 的实际执行并发另设上限。
- token 与费用预算按已报告用量检查后续调用；在途并发可能产生超额，当前机制不提供固定超额上界。用量收据去重，累计摘要不能再次作为增量入账。
- 允许重建 Service API 和运行数据；权限及准入续租保持原有任务存活语义，SSE 断线不取消执行。
- 各组件按普通多副本部署实现，共享数据库和 Workspace；按本轮快速迭代约定补少量核心测试，其余登记后补。

## 2026-10-10：模块与协议基础

1. 增加 [agent-service](../agent-service/pom.xml)，使用独立 Spring Boot 应用和限定组件扫描。没有装配 Harness、SessionTurnRunner 或 Agent 构建服务；当前只有健康检查和内部协议探针，公开 Session API 尚未迁移。
2. 增加 [tool-worker](../tool-worker/pom.xml)，迁入原有 Worker 入口、LocalToolWorkerToolExecutor 和两项已有行为测试。新 jar 可以独立执行 self_hosted CLI；托管 E2B、ToolTask 认领和结果 outbox 尚待 P7 实现，该起点当时仍使用旧 Worker HTTP 协议，后续 P7 已改为 ToolTask 协议。
3. 将 SessionResolveResult 迁到 common，保持原包名，供配置准入客户端及新 bootstrap 契约复用。Agent Service 和 Tool Worker 不依赖数据面或 Scheduler 的可执行模块。
4. 增加 [Java 内部协议](../common/src/main/java/io/agentcore/managed/server/runtime/protocol/RuntimeProtocol.java)、[Go 对应类型](../control-plane/internal/runtimeprotocol/protocol.go)及 [v1 JSON 示例](../common/src/test/resources/runtime-protocol/v1/turn.json)。Go 解码有消息大小上限和版本拒绝；具体操作的授权及有效期复核仍由业务处理器落实。
5. Docker 服务镜像的模块 allowlist 增加 Agent Service 和 Tool Worker；Compose、Helm、发布映射及 Gateway 切流尚未完成。运行中的已有本地服务未重启，数据库未重置。

### 实际验证

| 检查 | 命令或入口 | 结果及范围 |
| --- | --- | --- |
| 初始数据面构建 | `mvn -T1 -pl managed-agents/agent-harness-runtime -am -DskipTests package` | 通过；确认已有工作区可构建，不代表测试执行 |
| Go 制品基线 | 在 control-plane 执行 `make build` | 通过；控制面、Runtime Host、CLI 构建 |
| 当前 Java 构建及选定测试 | 下方命令 | 通过；Agent Service、Tool Worker、数据面、Scheduler 及依赖可打包，实际执行 7 项 Java 测试 |
| Java/Go 协议对照 | `go test ./internal/runtimeprotocol -count=1 -v` | 通过；七类共享 JSON fixture 往返及未来版本拒绝，尚未验证跨服务 HTTP 闭环 |
| Agent Service 独立 jar | 启动 agent-service jar，test profile，绑定 127.0.0.1:18084 | 健康为 UP，带内部身份调用协议探针返回 version=1；验证后已停止该测试进程 |
| Tool Worker 独立 jar | `java -jar managed-agents/tool-worker/target/managed-agents-tool-worker-2.1.0-SNAPSHOT.jar --help` | 通过；验证可执行 jar 的入口 |
| 格式 | 对本次 Java 文件执行 Spotless；新 Go 包执行 gofmt | 通过；相关构建同时执行 Spotless check |

```bash
mvn -T1 -pl managed-agents/agent-service,managed-agents/tool-worker,managed-agents/agent-harness-runtime,managed-agents/service-scheduler -am -Dtest=RuntimeProtocolFixtureTest,AgentServiceAppTest,SelfHostedToolWorkerUnitTest,HarnessExternalToolProcessRecoveryTest -Dsurefire.failIfNoSpecifiedTests=false package
```

上游历史测试 `HarnessExternalToolProcessRecoveryTest`（源码未迁入本项目）实际启动三个独立 Java 进程：提交并暂停 → 读取同一持久化日志并应用工具结果 → 重复结果被拒绝。第二个进程继续同一 Turn；结果只出现一次；第三个进程没有增加日志或调用模型。此证据只证明 Harness 的恢复前提，尚未证明完整 Tool Worker 服务链路。

相关报告保存在各模块 `target/surefire-reports/`。全仓库回归、真实 E2B、真实 Hosted/External、多副本竞争、压力和生产部署未在本步骤执行，覆盖欠项见 [后续测试清单](agent-service-test-backlog.md)。

## 2026-10-10 Session 注册与数据面 bootstrap

Agent Service 拥有 `builder_portal_session` 注册表，保存生命周期、加密权限投影、公开视图及待确认的 bootstrap outbox。数据面拥有 `builder_runtime_configuration`，保存加密执行快照和不可变配置 revision。两张表位于各自模块的实体包；Agent Service 仅扫描自己的持久化包，不扫描旧数据面实体和执行组件。Agent Service 收到有效 ready 回执后，在同一事务标记 ready 并清除 outbox 中的凭据副本。

bootstrap 使用稳定 commandId 和内容摘要。跨副本插入冲突后读取已提交记录；相同内容返回原回执，不同内容返回 409。首次提交要求权限未过期；已提交回执的重放不修改快照。Agent Service 在短事务内认领待发送记录，在事务外调用数据面，再用 claim token 确认结果；超时和临时错误有有限退避及抖动，永久拒绝进入 failed，失败记录不保存可能含凭据的上游错误正文。

控制面新增内部配置准入入口 `POST /api/internal/runtime-admissions/sessions`，要求内部服务身份及原始用户或 Application key 双重校验。创建后的配置选择记录目前继续复用控制面 Session contract，后续进一步收敛为管理配置投影；执行不从该入口启动。Agent Service 公开 `POST /api/v1/agent-sessions` 和 `GET /api/v1/agent-sessions/{id}` 已接通。尚未接入的其他后端返回明确错误。Gateway 仍使用原路由，等待 Turn 和 SSE 迁移后统一切换。

数据面的 ControlPlaneClient 优先读取新持久化快照；旧 Managed Runtime 路径暂时保留，等待 Turn、授权和事实一并迁移后移除。新快照仅保存冻结配置，运行状态后续由独立执行事实和原生日志维护。

Agent Service 的 `schema.sql` 和数据面的 `runtime-schema.sql` 提供新增表初始化；部署初始化 SQL 已加入 portal schema。应用默认 validate；独立初始化任务、升级版本和数据库账户权限仍需完成。本步骤未运行部署脚本、重启已有服务或重置数据库。

### 实际验证

| 检查 | 结果及范围 |
| --- | --- |
| Java 相关构建与测试 | 下方命令通过，实际执行 7 项测试：协议 2、Agent Service 启动及内部身份 2、Agent Service 回执丢失恢复 1、数据面快照与过期准入 2 |
| Go 共享协议 | 九类共享 fixture 往返及未来版本拒绝通过 |
| Go 配置准入边界 | 内部身份缺失、错误或缺少原始调用者均拒绝；已有管理摘要单调及 Turn 归属测试通过 |
| 独立 jar smoke | 实际 Agent Service 和数据面进程、各自临时文件数据库：创建 ready、Agent Service 重启后幂等返回原 Session、数据面重启后回执重放、配置冲突 409、内部 token 无法访问公开查询 |

```bash
mvn -T1 -pl managed-agents/agent-service,managed-agents/agent-harness-runtime -am -Dtest=RuntimeConfigurationStoreTest,AgentServiceSessionProvisioningTest,AgentServiceAppTest,RuntimeProtocolFixtureTest -Dsurefire.failIfNoSpecifiedTests=false package
python3 managed-agents/tests/agent_service_bootstrap_smoke.py
cd managed-agents/control-plane
go test ./internal/runtimeprotocol -count=1
go test ./internal/httpapi -run 'TestRuntimeConfigurationAdmissionRequiresServiceAndOriginalCaller|TestManagedRuntimeSummaryIsMonotonicAndProtectsTurnOwnership' -count=1
```

当时的 `agent_service_bootstrap_smoke.py`（已退役，当前入口见[原生进程测试](../tests/managed-mq-redis/README.md)）的控制面是只提供配置准入的 fixture；两个 Java 服务及持久化是实际实现。测试使用独立临时目录和假凭据，成功后清理；失败时保留日志。该证据只覆盖 Session 创建，不覆盖 Turn、模型、Tool Worker、全量 RBAC、多副本 PostgreSQL 竞争或真实控制面资源冻结集成。

状态与顺序契约已写入 [状态契约](agent-service-state-contract.md)，区分准入、运行、人工等待、取消等待、原生输入确认和工具未知结果。待接通流程在该文档中明确标注。

## 2026-10-10 Turn 原子准入、命令投递与执行授权

Agent Service 在同一事务保存公开 Turn、Application 预留、准入序号、`turn.accepted` 和加密命令。共享配额行与 Session 行使用短事务锁；两个服务对象并发提交同一幂等键只产生一份 Turn、事件和占用。排队上限、Application 并发与已报告 token 预算、受理速率和输入 schema 检查已接入。实例实际执行限制随后在数据面消费者落实。

Agent Service outbox 按 Session 准入顺序批量认领，HTTP 调用在事务外执行；数据面独立 Inbox 在内容摘要和作用域校验后提交，重复命令返回原 `runtime_accepted` 回执。投递成功只修改投递状态，不伪造 running；永久传输拒绝保留占用和 queued 状态，等待真实执行事实或确认撤销，避免回执丢失造成误释放。

执行授权为独立内部协议，绑定原始 actor、输入摘要、准入序号、执行代次和租约 fence。Agent Service 重新检查当前权限、生命周期、有效预留及已报告预算，返回最多 30 秒且不超过权限有效期的授权；较旧代次不能续租覆盖新执行者。授权成功不生成 started 事件，不减少 queued 计数。此关口已实现，数据面消费者正在接入。

### 实际验证

- 相关 Java 构建执行 9 项核心测试并通过：协议 2、Agent Service 启动与身份 2、bootstrap 恢复 1、共享并发准入 1、数据面快照及命令 Inbox 3。
- Agent Service 调度器改用 Spring 协调关闭阶段排空在途任务，关闭时拒绝新任务；修复多个测试 Context 关闭时调度线程等待导致 Surefire 强制退出的问题。上述测试恢复正常退出。
- 授权扩展验证通过：原始 actor 伪造拒绝、不同 fence 的续租拒绝、旧代次续租拒绝、预算耗尽拒绝，公开状态及额度未被授权请求改变。
- Java/Go 十二类共享协议 fixture 往返和未来版本拒绝通过，actor 字段统一为实际服务使用的 `user/key_hash/internal`。
- 实际两个独立 jar、各自临时文件数据库 smoke 通过：公开 Turn 返回 202，重复提交保持同一 ID，Agent Service outbox 到数据面 Inbox 落库，服务重启后的幂等回执与内容冲突检查通过。

该 smoke 的配置准入仍由 fixture 提供，尚未执行模型 Turn，也未验证 PostgreSQL 多副本容量；不代表 P3/P4 或整体新架构验收。

```bash
mvn -T1 -pl managed-agents/agent-service,managed-agents/agent-harness-runtime -am -Dtest=RuntimeConfigurationStoreTest,AgentServiceSessionProvisioningTest,AgentServiceTurnAdmissionTest,AgentServiceAppTest,RuntimeProtocolFixtureTest -Dsurefire.failIfNoSpecifiedTests=false package
mvn -T1 -pl managed-agents/agent-service -am -Dtest=AgentServiceTurnAdmissionTest,RuntimeProtocolFixtureTest -Dsurefire.failIfNoSpecifiedTests=false package
python3 managed-agents/tests/agent_service_bootstrap_smoke.py
```

## 2026-10-10 Managed Turn 实际执行与运行事实闭环

数据面新增有界命令消费者：按有业务索引的 Session 准入序号选择待执行命令，预取不超过实例空余执行槽位；短事务认领为 preparing，独立任务续租。Harness Runner 获得 Session 独占执行租约后申请 Agent Service 授权；每次后续模型调用再次检查原始调用者与 Agent Service 已入账预算。后续事件对账发现默认后台记忆调用丢失授权和日志上下文，见下方修复记录。同步模型准入放到 blocking worker，避免阻塞 Reactor 事件循环。

新运行不调用旧 ManagedRuntimeSessions 准入或控制面执行状态更新。原生 runId 绑定新数据面命令；已提交的原生日志生成数据面事实 outbox。事实按同一 Session 的来源序号投递，Agent Service 在同一事务完成内容去重、来源游标、公开 seq、Turn 状态、增量用量及额度释放。历史执行授权保留，已提交的旧事实可以补传；旧激活的状态事件只记录 execution 历史，不回退当前 Turn。

running 来源为原生 turn/start；授权或 HTTP 回执不能代替它。requires_action 保留 Application 占用；终态释放一次，排队占用另有独立标记，避免 cancel_requested 掩盖尚未开始的排队占用。执行者丢失后先确认实际 Session 与原生日志写租约均已到期，再锁定恢复认领并导出已提交后缀，不自动重跑不确定执行。

### 实际验证与边界

- 相关构建、格式和 16 项测试通过：原生日志 7、共享协议 2、Agent Service 4、数据面持久化 3。Agent Service 用量、终态释放、内容冲突、来源缺口及旧执行历史保护纳入已有核心事务测试。
- `python3 managed-agents/tests/agent_service_bootstrap_smoke.py --execute-turn` 通过：实际两个 Java 服务、各自临时文件数据库、真实 Harness 和本地模型 HTTP fixture。Application maxConcurrent=1 下连续两轮完成；幂等重复不增加逻辑 Turn，额度释放后第二轮可受理；共四次模型调用（两次推理、两次记忆整理），Agent Service 和数据面重启恢复通过。
- 控制面配置准入仍使用 fixture，不代表真实控制面全资源集成。数据面的真实恢复竞争、暂停/动作/工具结果、自动执行与用户取消竞争尚未在独立进程 smoke 中覆盖。
- 本次闭环最初使用逐条事实 courier 和全后缀 exporter；后续已接入批量投递及事件/SSE，有界导出继续推进。不能据此宣称已通过大流量容量验收。
- Agent Service 权限投影目前仍有固定有效期，P6 刷新尚未接入；对到期或不可用授权拒绝新的调用。实际准入消费、原生 checkpoint 和 writer fence 复用现有机制，但完整停机/旧 writer 故障矩阵仍待补。


## 2026-10-10 批量事件、SSE 与记忆用量对账

Agent Service 新增事件列表、单事件查询和 SSE。游标绑定 Session；分页有上限，SSE 按有限事件页读取，支持 Last-Event-ID 和 after 续传。每个实例限制连接数，设置重放上限和追赶阶段的慢连接超时，断开连接不取消执行。

本机和 PostgreSQL 通知只在事务提交后发出，同一批次合并为一次通知。本机订阅者按 Session 查找，避免每条事件遍历全部连接。通知作为唤醒提示，丢失后按数据库游标补读。数据面按同一 Session 的连续来源位置认领最多 64 条事实，通常限制为 1 MiB，HTTP 上限 2 MiB；网络调用在事务外执行，Agent Service 原子提交整批事实，重复批次返回原收据。

后续模型调用先导出当前已提交日志，并将已经报告的来源前缀送达 Agent Service，再检查预算。传输未完成时拒绝继续调用；已经在途的并发调用仍可超额，没有固定超额上界。

实际对账发现原默认后台记忆调用没有进入当前 Turn 的日志和授权，四次模型请求只有两条用量。新增 onAgentFinish 扩展点，在结果产生后、原生 writer 释放前等待服务托管记忆提取与整理；辅助调用保留身份、政策和 SUMMARY 用量记录，暂停时不触发整理。独立 Harness 的默认后台行为保持不变。

### 验证

- 最终相关构建和格式通过，实际执行 9 项测试：模型政策 2、受控记忆 3、Agent Service 事务/事件 1、数据面持久化 3。覆盖记忆调用被拒绝后不派发模型、辅助用量先于 Turn 终态、批次回滚和去重、跨 Session 游标拒绝、分页和 SSE 补读。
- `python3 managed-agents/tests/agent_service_bootstrap_smoke.py --execute-turn` 通过：独立 Agent Service、数据面、真实 Harness 与本地模型 fixture；两轮 Turn、四次模型请求、四条用量记录和 26 条公开事件。Agent Service 重启后分页内容和 SSE 标识、内容、顺序一致，数据面重启恢复检查通过。
- docs 静态检查通过：430 页面、584 重定向。没有执行全仓库回归、PostgreSQL 多副本通知、慢连接压力和容量认证；公开资源快照与完整交互仍需实施。

## 下一步

继续补齐交互、列表、子 Agent 事实、入口和存储写入总清单、版本化初始化与权限，推进全部后端和 Tool Worker，不缩小原计划的完成范围。

## 2026-10-10 有界原生导出与预算同步重放

原生 exporter 新增有限批次 drain，服务提交 hook 每次最多导出 256 条事件；模型准入与终态关口仍等待当前已提交后缀完成。新数据面从命令表的有索引待导出标记认领补偿任务，传输认领 token 与执行租约分别管理，避免周期性遍历全部历史 Session。旧路径的补偿扫描暂时保留，按未归属新运行的来源有限分页。

预算边界允许重放已经被其他 courier 认领的不可变事实前缀。Agent Service 内容去重保证重复传输不重复计费；成功 ACK 清除传输 claim，旧 courier 的后续回调被 token 校验拒绝。Agent Service 不可用时不确认来源位置，也不继续模型调用。

- 构建及 11 项选定测试通过：exporter 2、受控记忆 3、Agent Service 事务 1、courier 2、数据面持久化 3。
- 完整两个 Turn 的进程 smoke 再次通过：四次模型请求、四条用量、26 条公开事件，Agent Service 重启后的分页和 SSE 续传一致。
- 原生 Journal 的链校验和 checkpoint 仍可能读取较长历史；有限导出批次不代表这些 Core 存储操作已经通过容量认证，后续优化与压力验证单独登记。

## 2026-10-10 固定游标资源快照

Agent Service 按资源 ID 增量持久化 items、tools、turns、runs、actions、inputs、artifacts、subagents 与 model usage；资源、用量汇总和公开事件在同一个 Session 锁及事务中提交。复用的纯事件 reducer 移至 common，Agent Service 不依赖数据面可执行模块。

`GET /snapshot` 为各集合返回首个有限页、`snapshot_id`、`as_of` 和有效期；`GET /resources/{kind}` 使用不透明 keyset 游标继续读取，资源页上限 256 条。只保存仍被有效快照引用的旧资源值，不复制全量 Session 视图；快照数量、有效期及清理批次均有上限。默认 120 秒和每 Session 16 份。单资源查询读取当前一致状态，不为轮询创建分页快照。run 终态通过对应游标下的 run 资源归并到未完成 item/tool，避免终态时遍历所有历史资源。

- 相关构建和 10 项选定测试通过：Agent Service 核心事务 1、原 reducer 5、已有资源行为 4。代表性检查覆盖分页期间的文本更新、旧快照保持与新快照完成状态、跨 Session 和资源类型游标拒绝。
- 新进程 smoke 通过：公开快照用量为四次模型调用、输入 28 与输出 12 tokens；Agent Service 重启后同一 `snapshot_id` 返回完全相同的 items 页，SSE 续传保持原结果。
- docs 静态检查通过：430 页面、584 重定向。补充核心事务测试通过：删除资源仍可按旧快照读取，到期返回 410，二十次当前单资源查询不新增快照。多副本 PostgreSQL、长 Session、大资源、TTL 竞争和容量测试仍待后续。

## 2026-10-10 原生激活授权归属与停止语义

数据面为每个原生 run 保存不可变的 command、授权代次和 fence。恢复后的新激活不覆盖旧 run 的身份；补导出的旧事实仍携带它们当时的授权。完成处理只选择当前代次的终态，避免上一轮暂停覆盖下一轮失败。绑定原生 run 前再次确认授权有效期。

原生 cancelled/interrupted 只有在同一授权代次存在已持久化取消命令时映射为用户 cancelled，否则公开状态为可显式恢复的 interrupted；迟到的旧激活不能借用新激活的取消请求。取消 API 仍在接入，这一项先固定数据面状态边界。

- 相关构建及六项测试通过：持久化配置/命令 3、激活历史与停止语义 3。检查旧事实不启动新执行、旧暂停不覆盖新失败，以及取消命令的代次约束。
- 新版本完整进程 smoke 再次通过，两 Turn、四次模型请求、四条用量、26 条事件，SSE 和资源快照均在 Agent Service 重启后恢复一致。
- 真实租约丢失与用户取消竞争、跨进程显式恢复仍未验证；已登记后续矩阵。

## 2026-10-10 交互协议接入准备

新增 `RuntimeInteractionReceipt`，区分 runtime_accepted 与原生输入的 accepted/applied/rejected，cancel 的接收不代表任务已经停止。Java/Go 十四类协议 fixture 对照及版本拒绝通过。公共输入 DTO 迁至 common，保留原包名；数据面输入接收器新增使用 Agent Service 已分配 inputId 的入口，继续复用原生 Inbox 的接收与终止锁。

新增运行时交互 Inbox 表及索引，运行时输入接收服务已连接原生 Inbox；先保存待确认记录，原生输入提交后再确认 runtime_accepted。原生已提交但后续导出或回执失败时，重试从同一原生输入 ID 修复回执；新输入在接收锁内检查准入期限，steer 另有固定接收窗口。inject 的读取和重放支持空 turnId。

- 协议及输入基础构建通过，七项选定测试执行：共享 fixture 2、原生输入回执 1、已有资源行为 4。
- 后续三项输入核心测试通过：原生输入 2、运行时丢回执恢复 1。覆盖过期新输入拒绝、已提交输入到期后重放、inject 不启动 Turn，以及原生提交后导出失败的回执修复和内容冲突。
- 此基础步骤之后的 HTTP 和输入事实接入见下节；取消与恢复仍待接入。

## 2026-10-10 公开 inject 与 steer 闭环

Agent Service 的 `POST /inputs/inject`、`POST /turns/{turn}/steer` 和 `GET /inputs/{id}` 已接通。原始调用者、作用域、内容摘要与幂等键先写入交互 outbox，直接尝试及后台重试共享实例并发上限；数据库认领与 HTTP 调用分开。待处理输入默认每 Session 128 份，实例投递默认 32 槽位，后台每批 8 份、并发 4 份，与普通新 Turn 的投递调度分开。

数据面先持久化交互意图，再在共享原生 Inbox 接收锁内受理；回执丢失时，内部 GET 可根据原生输入 ID 修复确认，读取不添加新输入。固定期限内的 steer 校验当前 Turn 和有效 writer；已结束或到期的 steer 在同一 Inbox 锁内写入拒绝回执。inject 保持不绑定 Turn 和不自动执行的语义。重试前复核原始调用者，运输凭据不取代用户身份；已提交回执允许在原运输授权到期后恢复。

输入接受、应用和拒绝事实进入统一持久化来源及 Agent Service 事务。确认只来自有效运行时回执或事实；迟到的 accepted 不覆盖 applied/rejected。普通 Turn 提交产生的内部输入 ID 不透出为公开输入资源。共享 Inbox 已具备跨副本受理，因此实施计划移除了不必要的执行实例发现路由。

- 相关构建和 7 项核心测试通过：Agent Service 事务 1、输入原生状态 2、丢回执恢复 1、执行历史 3。随后扩充的 Agent Service 事务检查通过，覆盖输入事实确认、来源去重、投递确认与迟到接受不回退应用状态。
- 独立进程 smoke 通过：inject 后模型未启动；重复输入返回同一 ID，内容冲突 409；运行中 steer 接收后进入下一次模型上下文；完成后新 steer 返回 409。两 Turn 共 5 次实际本地模型请求、5 份用量和 33 条公开事件。
- 同一 smoke 的事件分页、SSE 续传与固定资源快照在 Agent Service 重启后保持一致；数据面重启后的命令回执重放通过。使用独立临时数据库和假凭据，测试进程已停止并清理临时数据。

```bash
mvn -T1 -pl managed-agents/agent-service,managed-agents/agent-harness-runtime -am -Dtest=AgentServiceTurnAdmissionTest,RuntimeExecutionHistoryTest,RuntimeInteractionStoreTest,SessionInputServiceTest -Dsurefire.failIfNoSpecifiedTests=false package
python3 managed-agents/tests/agent_service_bootstrap_smoke.py --execute-turn --exercise-inputs
```

当前证据覆盖单数据面执行进程；真实多副本 PostgreSQL 输入竞争、每个丢回执时机、队列饱和与撤权竞争留在后续测试清单。整体 P0–P9 仍继续实施。

## 2026-10-10 取消命令与实际停止闭环

Agent Service 在 Session/Turn 锁中登记幂等取消命令和 `turn.cancel_requested`。活跃 Turn 保留 Application 预留，运行时回执仅表示停止请求已持久化；`command_status=applied` 来自有效终态事实。已释放额度的 failed/interrupted Turn 保留原状态，直到原生关闭；取消不重新申请模型执行额度。已提交完成可以胜出取消竞争，并同时确认该停止请求已经有最终结果。

数据面先提交命令和当前执行代次的取消标记，再通过精确 run fence 中断。取消进度使用独立、有界的持久化认领与补偿，不能因 HTTP 回执丢失而停止处理。尚未进入原生执行的任务关闭执行认领，后续激活和 run 绑定被状态校验拒绝；准备 run 已绑定但没有原生提交的情况，在原生 writer 锁内验证并记录撤销。空闲任务复用 `SessionRecovery` 关闭原生 Turn，行政 writer 先绑定原来的授权代次，公开事实仍能追溯有效授权。不确定的外部工具副作用保留待核对状态，不自动执行。

修正已拒绝 steer 被终态准入当作待处理输入的问题，避免过期输入造成额外推理循环。公共取消事件使用固定长度摘要 ID；重复回执及迟到确认不回退 applied 状态。

- 相关打包与 13 项核心测试通过：Core 恢复取消 3、Inbox 恢复 3、Agent Service 事务 1、数据面输入/执行历史 6。Agent Service 检查覆盖 queued 撤销仅在事实到达后释放预留，以及完成事实先于取消执行时保留 completed 并确认命令。
- 完整进程 smoke 通过：两 Turn 正常完成、一个运行中 Turn 取消、一个 queued Turn 在禁用执行消费的进程中撤销；重复取消返回原 ID，运行中仍占 Application 槽位，停止后新 Turn 可以受理，queued 撤销没有模型请求。共 6 次本地模型请求、45 条公开事件，Agent Service/数据面重启、SSE 及快照恢复继续通过。
- docs 检查通过：430 页面、584 重定向。真实多副本取消竞争、每个准备阶段故障点、unknown 工具核对、停机 drain 和完整恢复仍待后补与后续阶段接入。

```bash
mvn -T1 -pl managed-agents/agent-service,managed-agents/agent-harness-runtime -am -Dtest=SessionRecoveryCancellationTest,SessionInboxCrashRecoveryTest,AgentServiceTurnAdmissionTest,RuntimeExecutionHistoryTest,RuntimeInteractionStoreTest,SessionInputServiceTest -Dsurefire.failIfNoSpecifiedTests=false package
python3 managed-agents/tests/agent_service_bootstrap_smoke.py --execute-turn --exercise-inputs --exercise-cancellation
```

## 2026-10-10 同 Turn 恢复与原生人工批准

每次恢复、批准或外部答复都有独立 activationCommandId，原始 Turn 和准入序号保持不变。Agent Service 在事务中重查原始调用者和操作发起人，复用仍有效的 Application 预留，或重新取得已释放额度。运行时保留上一个原生 run 的不可变授权，旧激活的启动、终态及取消事实仅进入历史。恢复尚未真正执行时的取消也关闭原生未完成任务，不产生额外模型请求。

`POST /turns/{turn}/actions` 从公开待办取出可信工具调用，客户端只能提交请求 ID、批准或结果。人类批准需要 approve 用户权限，Application key 不能批准；外部结果单独需要 interact。运行时再次校验原生待办的 Turn、种类和完整调用摘要。失效答复登记为 rejected 的失败激活，避免永久占用队列。

Agent Service 任务的 always_ask 通过原生日志持久化暂停，释放实例推理槽位，保留逻辑 Turn 与 Application 额度。批准后的新激活读取原生状态，不再阻塞旧进程内确认线程。普通运行时的已有确认语义保留。

- 恢复与取消核心打包检查 14 项通过；隔离进程 smoke 10 次模型请求、75 条公开事件，覆盖同 Turn 失败恢复、禁用执行时持久化受理、数据面重启和恢复前取消。
- 批准相关打包检查 22 项通过；实际 Harness 批准 smoke 13 次模型请求、100 条公开事件，覆盖原生 list_files always_ask、Application 槽位保留、重复答复、不同内容冲突、重启后继续同 Turn、工具结果进入模型上下文、待办清空及 applied 回执。
- 两组 smoke 同时保持 inject、steer、运行及排队取消、SSE 和资源快照重启恢复。使用本地 fake 模型、临时 H2 和假凭据；不代表 PostgreSQL 多副本、真实供应商或 Tool Worker 已验收。
- 文档检查通过：430 页面、584 重定向。

```bash
mvn -T1 -pl managed-agents/agent-service,managed-agents/agent-harness-runtime -am -Dtest=AgentServiceTurnAdmissionTest,RuntimeExecutionHistoryTest,RuntimeInteractionStoreTest,SessionInputServiceTest,RuntimeResumeStoreTest,ToolConfirmationMiddlewareTest -Dsurefire.failIfNoSpecifiedTests=false package
python3 managed-agents/tests/agent_service_bootstrap_smoke.py --execute-turn --exercise-inputs --exercise-cancellation --exercise-resume --exercise-actions
```

## 2026-10-10 多工具外部结果闭环

self_hosted 配置中的外部工具由原生 Harness 暂停；本次直接通过 Agent Service actions 提交结果，尚未使用新的 Tool Worker ToolTask。Runner 对 Agent Service 任务读取数据面持久化 Environment，不再通过旧控制面 Environment 接口查询，也不向旧 Tool Worker 工作队列登记。

- 实际跨进程 smoke 通过：7 次本地模型请求、60 条公开事件。两个 read_file 请求分批回传，每次在禁用调度时受理后重启数据面；第一份结果仅清除对应待办，不启动新的推理，第二份错误结果到达后两份结果共同进入模型上下文，完成同一个 Turn。
- 只含 read/interact 的 Application key 能提交及查询结果，不能 invoke 新 Turn；缺少 interact 的原始人类不能提交结果。相同键返回原回执，不同输出冲突，已消费请求用新键重复答复冲突。等待剩余工具时仍占 Application 额度。
- 命令 applied、待办清空、SSE 和快照重放、重复 bootstrap 与冲突继续通过。隔离临时进程和数据库已清理，不代表 Tool Worker/E2B、新 ToolTask 或多副本 PostgreSQL 已完成。

```bash
python3 managed-agents/tests/agent_service_bootstrap_smoke.py --execute-turn --exercise-external-results
```

## 2026-10-10 Agent Service 索引查询与动作应用确认

Session 列表使用持久化读权限索引与 createdAt/id 游标，包含 Application 共享用户和具备 read 的应用凭据；每页再次按当前权限投影校验，最多 200 行，游标绑定调用者和过滤条件。Turn 列表按准入序号分页，不进行全历史拼接或 offset 扫描。Session 详情的状态、用量、事件游标和 Turn 数来自当前 Agent Service 记录。

Turn 事件、SSE、资源分页和固定快照共用 Session 日志及游标，按 Turn 索引筛选；不同 Turn 的事件序号空隙不会造成重复或错误的下一页。资源与保留版本记录 Turn 归属，固定快照绑定 Turn，不能跨视图混用。模型用量在同一投影事务中维护 Session 与 Turn 的小型聚合，Turn snapshot 明确返回 turn_only。

动作命令不再因新激活开始就标为 applied。每个请求的 required_action.resolved 原生事实进入命令的应用集合，所有本批答复确认后才为 applied；未消费前激活失败形成 failed，明确的原生失效答复为 rejected，迟到运输 ACK 不回退终态。

- 4 项选定核心检查及打包通过：索引共享权限与游标过滤 1、Turn 事件/SSE/历史快照/用量 1、原子准入与控制事务 1、丢 bootstrap 回执 1。事务检查增加同批多个结果只在全部原生 resolution 到达后应用的断言。
- 实际独立进程 smoke 通过：7 次本地模型请求、60 条公开事件。Agent Service 重启后读列表、Turn 分页、Turn SSE、Turn 固定快照和用量均一致，原外部结果分批跨重启流程继续通过。
- 文档检查通过：430 页面、584 重定向。当前读权限索引关联初始投影，长期刷新继续在 P6 实施；PostgreSQL 查询计划、大规模列表/SSE、授权变化竞争和资源保留边界记入后续测试。

```bash
mvn -T1 -pl managed-agents/agent-service -am -Dtest=AgentServiceRegistryReadsTest,AgentServiceTurnAdmissionTest,AgentServiceSessionProvisioningTest -Dsurefire.failIfNoSpecifiedTests=false package
python3 managed-agents/tests/agent_service_bootstrap_smoke.py --execute-turn --exercise-external-results
```

## 2026-10-10：共享权限刷新与控制面变更 outbox

Managed 的权限投影现在按冻结资源版本、选择的 Memory/Vault/Environment、Application 和原调用者作用域共享；Session ID、事件位置和输入文件不会形成单独刷新组。控制面刷新路径按 ID 查询冻结依赖，最多 256 项，不再为了一个 Session 列出整个 Namespace 的资源。Agent 的定义继续冻结；Application 禁用后，原创建者及有效 Application key 不能继续 invoke、interact 或 approve；按原有范围保留 read/cancel 权限。取消投递单独检查 cancel。

`builder_portal_policy` 是共享权限与有效期的权威记录。多个 Agent Service 从索引待刷新记录中认领，HTTP 不持有 Session 或执行事务锁。默认最长有效期 120 秒（也受控制面较短有效期限制），单实例刷新槽位 16，每批选择 8、并发 4；提前刷新有抖动，失败退避 2–20 秒。刷新失败不延长权限，过期读取、列表及新操作返回 503；旧响应不能覆盖替换认领。Session 列表批量读取一页需要的共享策略。

控制面 `policy_change_outbox` 通过数据库触发器与 Namespace、Application、Application key、资源启停和产品账户/资源权限变更原子提交。控制面和产品 schema 各自拥有 outbox；内部 claim 每个来源最多 8 条、租期 30 秒，ACK 必须匹配当前 token。Agent Service 先提交 `builder_portal_policy_change` 收据，再 ACK；重复通知只查同一收据，不会再次撤销已刷新的策略。失效任务每个事务最多处理 128 个共享策略并保存游标，撤销旧刷新 claim，独立调度器随后刷新。Namespace/Application 变化使用相应索引；产品账户/资源变化当前使用有界全局失效任务，精确依赖索引优化仍待后续实现。创建或刷新与通知之间的竞态、延迟通知及新插入策略由有限有效期补偿；不能把 outbox 描述为同步撤权或立即终止已发出的外部调用。

实际验证：

- Java `AgentServicePoliciesTest`（3）、`AgentServiceRegistryReadsTest`（2）、`AgentServiceTurnAdmissionTest`（1）、`AgentServiceSessionProvisioningTest`（1）共 7 项及打包通过：共享刷新、失效不延期、旧 claim、128 行分批游标、通知重投、作用域隔离、取消撤权以及原有列表/准入/bootstrap 行为。
- 临时 PostgreSQL 17 容器中，Go 8 项选定检查通过：内存与 PostgreSQL outbox claim/ACK、变更回滚、Namespace/Application/key 范围、禁用 Application、共享作用域、冻结依赖、并发新 schema 初始化及产品账户变更。另复用 `TestPublicManagedSessionAccountFilesAndNativeBridge` 通过实际账户、版本、文件及配置投影链路。数据库为临时测试空间，没有生产切流。
- 实际 jar smoke `python3 managed-agents/tests/agent_service_bootstrap_smoke.py --execute-turn --exercise-external-results --exercise-policy-refresh` 通过：7 次本地模型请求、60 个公开事件，部分外部结果、列表/Turn 快照、SSE、Agent Service/数据面重启；控制面通知持久化并 ACK，权限源 503 时拒绝读取，Agent Service 重启保留失效，恢复刷新后 invoke 撤权生效而 read 保留。
- 日志：`/tmp/agentscope-policy-changes-final-tests.log`、`/tmp/agentscope-policy-scope-graph-tests.log`、`/tmp/agentscope-policy-managed-integration.log`、`/tmp/agentscope-policy-outbox-smoke.log`。批量列表策略读取的最终 7 项检查和打包通过，记录见 `/tmp/agentscope-policy-page-tests.log`；禁用 Application 后有效 key 保留 cancel/read 的补充检查见 `/tmp/agentscope-policy-cancel-key-tests.log`。

P6 尚未整体完成：跨后端额度/用量、动态 Vault 凭据、Memory 和 Environment 的运行时更新范围及投递仍需实现。后续多进程竞态、SSE 持续撤权、精确产品依赖失效索引、通知积压和压力验证记入后补清单。


## P2 Session 生命周期命令

Agent Service 提供归档、恢复、删除和 lifecycle-commands 查询。归档/删除需 interact，恢复需 invoke；Session 锁内同时提交期望生命周期、递增 lifecycleRevision、稳定幂等命令及公开事件。仍有有效预留或未结束 Turn 时返回 409，必须先取消并等终态。归档保留 read 与历史；删除为持久化 tombstone，普通查询/事件/新操作返回 404。删除命令的授权查询及同键重试仍可访问，不能用新命令恢复删除状态。

数据面只保存独立生命周期镜像，冻结执行配置不随之改变。内部 courier 按 Session 版本投递，短租约/claim token 保护回执确认；远程调用在事务外进行，失败采用有限退避。已受理删除在公开 tombstone 或权限到期后仍可投递；这种投递不能授予执行权限。旧版本回执标记 superseded，不能回退新状态或恢复已删除 Session。当前删除不物理清理原生日志、Workspace 或 Sandbox，保留策略与生产期限仍在 P8/D9 明确。

验证：Java 相关模块构建成功，执行 9 项选定检查（共享协议 2、Agent Service 索引/生命周期 3、数据面快照/生命周期 4）；Go 的 16 类协议 fixture 往返通过。实际 jar smoke 使用独立文件数据库、真实 Agent Service/数据面和本地 fake 模型，验证未结算 Turn 归档冲突、取消后归档、归档读列表与新 Turn 拒绝、Agent Service 重启、恢复、旧版本投递、删除重试/回执及 tombstone 重启；模型调用 4 次，既有 SSE/snapshot 重放也通过。

```bash
mvn -T1 -pl managed-agents/agent-service,managed-agents/agent-harness-runtime -am -Dtest=RuntimeProtocolFixtureTest,RuntimeConfigurationStoreTest,AgentServiceRegistryReadsTest -Dsurefire.failIfNoSpecifiedTests=false package
python3 managed-agents/tests/agent_service_bootstrap_smoke.py --execute-turn --exercise-lifecycle
```

仍需实现 PATCH/动态覆盖、控制面管理摘要、删除保留和公开路由切流。多副本生命周期/新 Turn/恢复竞争及所有故障点的完整回归后补。本步骤不表示整个 P2 或总体重构完成。


## 2026-10-10：Tool Worker ToolTask 与托管 Sandbox 第一条闭环

数据面在原生 external_execution 请求提交并导出时，同事务建立有索引的 ToolTask；不用公开事件全历史扫描发现待办。任务保留 Session、Turn、原调用者证明、源事件、Environment、scope、Workspace 引用、超时、重试分类及独立授权代次。128 个待执行任务上限耗尽时提交明确错误结果，保证原生事实导出与推理有结算路径。

Environment key 仅经 Agent Service 的指定环境接口认领 self_hosted 任务；托管 Worker 使用不同的 `X-Managed-Agents-Tool Worker-Token`，仅获内部 Tool Worker 命名空间权限，不能调用准入、运行事实或用户 API。原调用者的 invoke 权限在认领、开始和续租时重新检查；结果回传与 Harness 已释放的 writer fence 分离。Worker 不接收模型和 Vault 凭据，只有托管 Sandbox context 含选中 Environment 的 E2B 连接凭据。

Worker 按可用槽位认领，副作用前写本地 journal，结果先落盘再上传；执行、续租和结果重试分别运行。心跳覆盖 staging 和工具执行。Task claim 过期时，未开始任务及有界只读尝试可以重新排队，已开始且结果未知的非幂等工具进入 unknown。结果落库、传送 Agent Service、同 Turn 原生续推理及 required_action.resolved 确認是不同去重点。

Workspace scope 绑定由数据面持久化。self_hosted scope 长期归属原 Worker，短工具租约到期不迁移本地目录。托管 scope 使用共享绑定与唯一约束/CAS；同 scope 的工具操作串行，不同 scope 仍可并行。创建前提交 creating，已知供应商 state 先存 Worker 本地再确认到共享绑定；重复 acquire 返回 unknown，不再次创建。托管 Worker 禁止 SDK 在连接失败时隐式创建，所有创建操作均关闭供应商请求自动重试。绑定 state 加密保存，已知迟到创建回复可修复绑定，旧代次不能覆盖替换后的绑定。

服务场景的 sandbox 文件及 shell 工具现已注册为 external schemas，实际 E2B 创建、连接、文件工具和命令执行在 tool-worker；standalone Harness 的 E2B 默认行为保留。文件和 shell 都以 provider Workspace 根目录执行，初始定义只写一次，输入单独 staging。

实际证据：

- self_hosted 真实 Agent Service/数据面/Tool Worker jar smoke：7 次 fake 模型调用、60 个公开事件；分批双工具结果、数据面重启、重复结果与冲突、Worker 重启通过。新增共享 scope 绑定后的同一 smoke 也通过。
- 托管 Worker 真实 jar smoke，fake E2B 生命周期及 Connect JSON envd：同一 Sandbox 1 次创建、1 次连接、10 次命令执行，双工具结果进入推理，Worker 重启通过；机器令牌访问其他命名空间拒绝。第一次 fake envd 帧同时填充 stdout/stderr，违反供应商 oneof 格式；修正替身为独立帧后通过。
- 选定 Java 检查及 Agent Service/Tool Worker/数据面构建通过，包含协议 fixture、任务租约恢复/结果去重/创建未知与迟到修复/显式代次替换、E2B 连接失败禁止隐式创建、流输出上限、已有 Tool Worker 文件与 shell 行为。
- Java/Go 共 23 种协议 fixture 往返及未来版本拒绝通过，新增 Sandbox 更新契约。

人工恢复的公开 ToolTask 状态分页、已有 actions 提交已知结果、取消以及显式确认后生成新授权代次的重试接口已实现；真实进程崩溃与人工重试 smoke 已通过：副作用发生后 SIGKILL Worker，任务到期进入 unknown 且 marker 仍只写一次；明确确认的恢复命令只替换一次代次，重复键返回原 applied 回执，旧 Worker 结果 409，最终 marker 两次写入、同 Turn 续推理完成（7 次模型调用、61 个公开事件）。此阶段之后的共享归档、保活、空闲回收、Worker 健康及人工绑定修复证据见下节；子 Agent 继承、挂载、Gateway/部署接入仍需实现。真实 E2B 服务及 PostgreSQL 多 Worker 并发尚未测试，不把 fake 供应商证据当作真实供应商验收。


## 2026-10-10 Worker 共享容量、不可变归档与供应商失效恢复

Worker 健康与工具租约分开：每 5 秒上报容量及 active/draining 状态，服务器用同一 Worker 行锁和共享 Task 计数保护并发认领，健康超过 45 秒不再开放新任务。原有任务仍受独立有限授权和租约控制。self_hosted Workspace 保持原 Worker 归属；托管 Workspace 的工具操作通过共享 binding 串行，不能靠重复 poll 突破容量或 scope 占用。

两类实际 Worker 进程均已验证副作用之后 SIGKILL：副作用只有一次，Task 租约到期后进入 unknown，没有自动重跑。显式人工确认后创建新授权代次，重复恢复幂等，旧结果被拒绝，替代执行只增加一次副作用。额外同 Worker poll 与其他 Worker 的同 scope poll 均没有取得任务。

托管 Worker 要求 `--shared-workspace-root` 指向所有副本使用的同一个绝对路径和共享可写存储。已知工具结果先 fsync 到私有 journal；随后将 Sandbox 文件保存成 claim 独有的 tar 归档，fsync 后原子 rename，再通过 binding CAS 选中归档。迟到写入不会覆盖新归档。保存失败保留 workspace_snapshot_pending，禁止自动回收；归档文件当前保留，尚未实施历史归档删除。

供应商明确返回 404 时，只允许从已确认归档重建干净 Workspace。binding 原子增加 generation 并保留归档指针，Worker 仅创建一个新实例并恢复文件。网络失败或未知创建不隐式创建替代实例，未归档状态需要人工恢复。

- 相关三个可执行 jar 构建通过，选定 Java 检查通过（实际执行数量及分布以日志中的各模块汇总为准），结果以 `/tmp/agentscope-hands-restore-final-build.log` 及模块报告为准。
- `python3 managed-agents/tests/agent_service_bootstrap_smoke.py --execute-turn --exercise-sandbox-recovery` 通过：实际 Agent Service、数据面、Worker、真实 Harness，fake 控制面/模型/E2B API 与 envd；两次创建、一次失效连接、21 次文件/执行请求，7 次模型调用、60 条公开事件。旧实例文件删除后，后续工具仍从共享归档读取正确内容；Agent Service/数据面重启、SSE 与固定快照继续通过。
- 最新清理协议新增两个 Java/Go fixture，目前共 25 种；Go 对照通过。最新构建和 29 项 Java 检查通过：协议 2、ToolTask 6、maintenance 2、E2B 恢复 4、envd 11、Tool Worker 文件与 shell 4，日志为 `/tmp/agentscope-hands-p7-final-build.log`。

这些证据没有使用真实 E2B 凭据，也没有验证供应商真实 TTL、计费或 PostgreSQL 多 Worker 竞争。后补矩阵见测试清单；整体 P0–P9 尚未完成。


## 2026-10-10 空闲回收与 Workspace 人工恢复

用户确认默认空闲 5 分钟回收，Environment 可覆盖，下次从归档恢复；当前不删除文件归档。工具执行中独立续供应商 TTL，最长保留也只在无操作、已确认归档的边界轮换。数据面通过 maintenance lease/代次认领，Tool Worker 删除同一个 provider ID；未知删除保留 retiring 并退避，成功或 404 才增加绑定代次、清空实例并保留归档。脏 Workspace、缺失归档和未知副作用不能进入自动清理。

Workspace 恢复使用持久化 workspace_recover 命令和当前工具/绑定代次。reconnect 要求已核实实例空闲；restore/discard 要求已核实旧实例不存在并确认潜在数据丢失。修复共享绑定不会自动重复 unknown 工具，显式 tool-recovery 另行确认。旧 Worker 结果和 provider state 被替换代次拒绝。已删除 Session 的未知孤儿仍需后续操作员入口；self_hosted 目录重归属尚待实现。

- `python3 managed-agents/tests/agent_service_bootstrap_smoke.py --execute-turn --exercise-sandbox-recovery --exercise-sandbox-cleanup` 通过：2 次创建、21 次执行、1 次删除，归档保留且绑定回到 reserved；7 次模型调用、60 条公开事件，重启和 SSE/快照续传通过。测试仅将空闲期缩短为 2 秒。日志 `/tmp/agentscope-sandbox-maintenance-smoke-2.log`。
- `python3 managed-agents/tests/agent_service_bootstrap_smoke.py --execute-turn --exercise-platform-hands --exercise-hands-recovery` 通过：副作用后崩溃仅一次写入；reconnect 修复一次、重放去重且不自动执行；明确工具重试后第二次写入、旧结果 409；7 次模型调用、62 条公开事件。日志 `/tmp/agentscope-workspace-manual-recovery-smoke.log`。
- maintenance 的 dirty/未提交归档保护、工具代次恢复及 E2B 保活已有代表性单元检查；新增本地 grep 输出限额与符号链接越界检查通过。

以上使用真实 Java 进程及 fake 供应商，未验证真实 E2B TTL/计费、PostgreSQL 多 Worker 竞争或 restore/discard 的完整进程矩阵。其余测试和实现欠项分别记录在测试清单中。


## 2026-10-10 本地子 Harness 的独立公开流

父原生 spawn 在导出游标推进前登记不可变父子关联，按根 Session 和主键索引读取，移除父日志全历史遍历。Core SessionExportSink 增加兼容默认 forChild，Harness 对明确提供的 child sink 导出独立事实。开始子调用前有限批次导出父前缀，子 run 通过持久化 parentRunId 继承原根 Turn 的激活代次和 fence，不创建第二份 Application 并发预留。

Agent Service 接收内部 subagent.event，为子 Session 分配独立公开 seq 和资源快照；根流只发布进度摘要和子模型用量。子 terminal 不结算根 Turn。子模型调用 ID 按子 Session 命名空间归并，Root Session/Turn 的 subtree 汇总及 Application 预算只计一次。子读取始终复核根 read 权限和 tombstone，子视图不开放独立运行/生命周期管理操作。

- 代表性 Java 检查：原生父子索引 2、既有镜像状态 2、原生执行历史 8、Agent Service 子事实事务 1、Agent Service 独立启动 2；另有 standalone 子流 9 项通过。日志为 `/tmp/agentscope-child-facts-build-2.log`、`/tmp/agentscope-child-export-final-build.log` 和最终映射/汇总检查 `/tmp/agentscope-child-contract-final-build.log`。
- `python3 managed-agents/tests/agent_service_bootstrap_smoke.py --execute-turn --exercise-subagents` 通过：真实 Agent Service、数据面和父/子 Harness，fake 配置与模型；9 次模型调用、56 个根公开事件，子事件从自身位置 1 开始。子结果交回父任务、独立固定快照分页、SSE 游标续传、Agent Service 重启后事件与快照重放、错误根路径拒绝和直接子 Turn 控制拒绝通过。日志 `/tmp/agentscope-child-facts-smoke-3.log`。成功后测试进程与目录已清理。
- 首轮实际调用暴露原生 spawn 对外映射为 subagent.started，登记入口误用了 subagent.spawned；已按真实映射修正并重新构建/跑通。单元替身同步改为现有标准事件名。
- 前端 view 的 subtree 用量在 model.completed、重放与刷新后保持一致，4 项局部行为测试及 TypeScript 类型检查通过。中英文文档已同步；全量 UI 构建/端到端回归留 P8/P9。

子工具等待/结果路由、人工动作、异步子运行及根取消后的结算、remote 子事实和准备阶段孤儿补偿仍需实现。此阶段只确认同步本地子 Harness 的事实与读取闭环，不将 P4/P7 整体标记完成。PostgreSQL 多副本竞争和完整故障矩阵后补。


## 2026-10-11 同步子工具、父续跑与取消闭环

数据面持久化每个父工具调用对应的子原生 Turn、Agent 声明和调用参数。Agent Service 的根 actions 支持 `child_session_id`，按根授权及不可变父子关联校验；Tool Worker 将子结果路由到对应子原生 Turn，Workspace scope 和 Application 预留仍继承根。部分结果只关闭本批子待办，不提前开始推理；最后一个结果提交后，运行时逐层生成父工具结果并续跑。动作收据只由匹配子命名空间的原生 resolution 确认，父工具自动关闭不会冒充人工答复。

根取消按索引、有限批次检查和停止子 writer；关闭子原生待办、导出停止事实后才结算根 Turn，仍有子执行或未核实副作用时保留 cancel_requested 与预留。取消回调与 requires_action 竞争不能撤销已受理取消；单个待核实子操作也不会挡住同批其他子任务停止。显式恢复可以读取已提交的子结果，补齐尚未关闭的父工具。同步子执行默认最多 64 个实例槽位，达到上限在开始执行前返回工具错误。

固定资源分页游标采用确定字段顺序编码，解决不同 Agent Service JVM 为相同快照位置产生不同 opaque cursor 的问题。

- `python3 managed-agents/tests/agent_service_bootstrap_smoke.py --execute-turn --exercise-child-tools` 通过，日志 `/tmp/agentscope-child-tools-smoke-10.log`：12 次 fake 模型调用、135 个根公开事件，真实 Agent Service/数据面/Tool Worker 进程。部分人工结果后重启、Tool Worker 实际文件输出、父续跑、applied 命令收据、独立子事件/快照/SSE 和 Agent Service 重启重放、重启后的根/子取消及配额释放均通过。成功后清理测试进程与目录。
- `/tmp/agentscope-child-continuation-build-14.log` 为 42 项检查通过，包括子动作事实事务、工具命名空间、恢复、原生执行历史、子取消及已提交结果恢复，standalone 子流行为保持通过。

这些证据仍使用 H2、fake 配置/模型及本地 Tool Worker；真实 PostgreSQL 多副本和 E2B 未测试。异步及 remote 子运行、中断子续跑的显式恢复、持久子 Session 多 Turn 的并发/恢复、已关闭根的孤儿管理继续实施。P4/P7 不据此整体完成。


## 2026-10-11 Go 编排运行角色与 Agent Service Runtime Host 通道

新增 `cmd/managed-agents-orchestration-runtime`，复用已有 Go 编排引擎、task plane、运行状态补偿及持久化 outbox。入口只注册私有运行协议、健康检查和 Hosted DistributedStore，不注册资源 CRUD、公开 Session 准入或控制台。默认监听 8086；生产要求 PostgreSQL 和所有副本共享的服务/任务签名凭据，memory 只能明确启用 local-dev。当前首先共用已有物理数据库，版本化配置准入、运行表账户权限及全部后端桥接仍在实施。

控制面默认关闭执行 worker，可用 `--enable-runtime-workers=true` 明确启用过渡运行。独立角色负责编排、runtime sweeper、控制 outbox 及已有 Invocation 协调。控制面的部分旧 HTTP 运行 handler、Runtime Protocol 写入、Automation/Channel 和资源管理投影尚未完成迁移，因此不能将此步骤描述为控制面已完全退出所有运行写入。

Agent Service 接通 Host register、heartbeat/state、Attempt claim、renew、preparing/running、checkpoint、events、complete/fail/cancelled。转发使用原 Host bearer 加独立服务凭据；Go 私有监听同时校验二者，内部凭据不再跳过 Host scope 校验。原任务 lease/fence/dispatchGeneration 检查复用已有 task plane。普通转发与心跳/续租分别限并发；饱和返回 429，不积累无界等待。运行角色 Host 注册只更新观测，不自动写入 Pool/Profile 定义。

编排补偿每轮最多一批，以 created_at/id 游标续扫，避免 OFFSET 在较早运行闭合后跳过后续运行。新增 PostgreSQL active partial index，后台查询直接使用活动条件与 keyset。每个 Run 仍由共享锁、版本和幂等节点保证协调；真实 PostgreSQL 查询计划与多副本验证后补。

- `make build` 通过，包含新运行角色及原控制面/Runtime Host/CLI 四个制品，最终日志 `/tmp/agentscope-orchestration-role-build-5.log`。
- 4 项选定 Go 检查通过：私有角色与双身份、已有 Host enrollment、enrollment 交换，以及运行闭合后的有界公平续扫；日志 `/tmp/agentscope-orchestration-role-tests-2.log`。25 类 Java/Go wire fixture 与未来版本拒绝通过，日志 `/tmp/agentscope-orchestration-protocol-check-1.log`。
- Agent Service 启动/转发 4 项、Java 协议 2 项及打包通过，日志 `/tmp/agentscope-runtime-host-portal-build-3.log`。覆盖身份与代次保留、409 错误、无关响应头过滤、转发饱和时心跳仍可用、取消释放槽位、无执行组件的独立启动。
- 新 `orchestration_runtime_host_smoke.py` 真实 Agent Service/Go 进程通过，日志 `/tmp/agentscope-orchestration-runtime-host-smoke-3.log`：控制面不启动，Agent Service 重启前后 Host 观测/心跳保持；跨 Host/namespace 拒绝，内部凭据不能充当 Host 身份，重新注册提高代次、旧代次 409，无任务领取为 204。本机从 Python 直接启动新 Go 程序时被 SIGKILL；本次以终端启动同一制品并用 `--runtime-url` 接入测试，成功后所有进程已停止。

这一 smoke 使用 memory 运行库，不验证任务持久化或真实 Hosted Provider。External、Hosted、Team、Workflow 的 Agent Service Session/Turn 准入与标准事实、Endpoint Invocation、冻结配置投递、跨后端配额和协作 MCP 仍继续实施，P5 未整体完成。


## 2026-10-11 通用后端配置和 Go Agent Service Inbox 基础

- RuntimeSessionBootstrap 改为后端通用的不可变 JSON 配置；保留 Context、配置 revision、原始调用者和 Session identity 校验。Agent Service 按 registry 的 runtimeKind 把配置、Turn、输入和 lifecycle 投递到 Managed 或 Go 服务地址，地址可由负载均衡指向多个副本。
- Go 私有接口新增 Session bootstrap、Turn 受理及回执查询。创建只提交冻结配置，Turn 受理只落 Inbox；调用者权限期限和实际执行许可必须在后续激活复核。控制面可以投影 Go 后端的冻结 Contract 及通用权限，Team/Workflow 的依赖来自冻结定义，实时资源可用性和撤权仍参与刷新。
- 新增 orchestration_portal_sessions、orchestration_portal_turns、orchestration_portal_facts 表和 0118 migration。配置、命令输入和事实 payload 使用 AES-GCM 加密与记录 identity 绑定；PostgreSQL 运行角色要求独立稳定的 MANAGED_AGENTS_VAULT_MASTER_KEY。消息数字的原始表示不会被 JSONB 改写。
- Inbox 按 Session 前序命令阻塞，认领批次最多 128 条且只取可执行时刻到期的工作；租约过期或旧 claim 不得提交。状态和事实 outbox 同事务提交；source 序号与事件去重同事务提交，事实只在收到匹配 Agent Service 回执后确认，后续事件不得越过未确认前序。
- Go 授权客户端和事实客户端使用独立的有限 HTTP 连接池、超时和 2 MiB 消息上限，拒绝跨 scope/fence 的授权与不匹配回执。共享 Java/Go digest fixture 覆盖中文、emoji、UTF-16 键排序、控制字符、null 和精确大整数。

验证：通用配置 Java 构建、配置仓库与 Agent Service provisioning/app 检查通过；Managed 子工具全进程回归通过（12 次本地模型调用、135 条根事件）；4 项 Java 协议测试通过；选定 Go 检查和 PostgreSQL 双连接仓库契约通过，涵盖并发 Bootstrap/Turn、同 Session 排序、旧租约、状态/事实事务回滚、去重与有序 ACK、密文随机 nonce/损坏/跨 identity、授权 fence 与收据匹配、冻结 Team 和绑定撤销。Go 四个入口构建通过。

进程验证：orchestration_runtime_agent_service_smoke.py 使用真实 Agent Service jar、真实 Go 私有运行角色和 PostgreSQL，控制面未启动。Session 注册及 Turn 投递、中文/emoji/大整数摘要、重复请求、错误 scope 与内部凭据隔离、Agent Service 重启通过；随后停止并重启 Go 进程，原 Turn 回执保持可读。公开 Turn ID 是 64 位摘要，Go 的原生 Invocation UUID 应由这个公共身份稳定派生，不能直接解析为 UUID。

日志：/tmp/agentscope-generic-backend-build-1.log、/tmp/agentscope-generic-backend-managed-smoke-1.log、/tmp/agentscope-runtime-digest-java-tests-4.log、/tmp/agentscope-go-runtime-foundation-tests-8.log、/tmp/agentscope-orchestration-foundation-build-8.log、/tmp/agentscope-go-portal-inbox-build-1.log、/tmp/agentscope-go-portal-inbox-smoke-1.log。

边界：当前尚未把 Go Inbox 激活、原生任务/Host 领取、运行事实和 UsageReceipt 接成闭环，不能宣称 Hosted/External/Team/Workflow 已可执行；公开 Session 创建仍保留未接通后端的 501。其执行授权、续租停止、取消/恢复、能力差异、Endpoint Invocation、动态 provider 配置、管理摘要和 MCP 接入继续属于 P5/P6/P8 的实现工作。Go 运行角色仍有旧 worker 循环和共享资源读取，表账户及 migration job 仍待 P1/P9 落实。

## 2026-10-11 Go 原生 Hosted 执行与终态关口

Go Inbox 已接入原生 Hosted Job 与 Conversation：共享认领取决于空闲实例槽位，默认每实例 32、可配置 1..128；公开 Turn 摘要稳定派生原生 Invocation UUID。首次 HTTP 授权前持久化 generation/fence，响应丢失时复用原身份。原生 Invocation 仅保存加密 admission 的引用，不复制输入及完整配置；实际 Attempt 使用创建时冻结的 Runtime Profile/Pool 和 Agent 定义，调用者身份沿根任务传递。

Task 派发、Host claim/preparing/running/renew 读取共享存储中的根许可。停止请求和许可到期拒绝新的执行，事件、checkpoint、结果及停止证明仍可排空。原生暂停只观察，不重新获取模型执行授权；终态事实排空先于权限续租，因此最终一次调用耗尽已报告预算不会把成功结果改为授权中断。Go 入口关闭旧 Invocation 公共投影循环，Agent Service 统一持久化公开事实。

事实读取每轮最多 16 个 Attempt、64 个事件；新增 0119 的 Agent Service command 索引及 keyset，游标和状态随 outbox 原子提交。usage 事件与 Attempt 完成累计量取正差补齐，最终输出与用量之后才提交 Turn 终态。原生 Invocation 同步收敛为终态，并应用冻结 Endpoint 的 result mapping/output schema；校验失败产生 failed。8192 个 Attempt/Node 的单 Turn 上限会明确报错，完整复杂拓扑事件和更完善的失败收敛仍待后续实现。

Host 新增 physical-stop：provider 已退出且持久事件 journal 排空后，使用原 Host/Attempt 凭据和 fence 提交停止证明。原生 terminal 之后、停止证明之前仍接受已提交事件；公共 Turn 保留占用直到停止证明和最终事实齐备。Host 最终事件每轮最多 64 条，未知 ACK 保留 journal，下次重放原身份。真实跨进程验证发现 Agent Service 漏转发 X-Execution-Attempt-Token，现已修复并增加转发断言。

取消命令新增加密的 0120 interaction receipt 表；回执与 StopRequested 同事务提交，撤销旧 step claim，旧执行者不能提交事实。新 Worker 继续停止过程，queued cancellation 不启动原生执行；实际停止前保持 cancel_requested。已经完成的原生结果在取消/撤权竞争中保留 completed。当前公开内部 provision 的 Hosted Session 可提交 cancel，其他 Go 控制和公开非 Managed 创建仍有门禁。

已验证：Agent Service 选定 4 项测试与 jar 构建；Go 独立入口构建；内存与真实 PostgreSQL 的 Hosted Job/Conversation、Host 根许可到期、最终物理停止、调用者及冻结 Profile；新增运行中取消的内存/PostgreSQL 检查、输出 schema 失败、仓库双连接取消回执/旧 claim 拒绝和 Worker 完成竞争检查通过。真实 Agent Service jar + Go 进程 + PostgreSQL + 认证 Host 替身的 Job/Conversation 均通过：控制面执行期间未启动，Agent Service 在提交停止证明之前重启，错误 fence 409，重复停止证明幂等，用量仅计 5，12 条公共事件连续且 SSE Last-Event-ID 续传一致。Provider CLI 未调用。

日志：/tmp/agentscope-go-native-postgres-core-tests-7.log、/tmp/agentscope-go-native-portal-build-2.log、/tmp/agentscope-go-native-job-process-smoke-4.log、/tmp/agentscope-go-native-conversation-process-smoke-1.log、/tmp/agentscope-go-native-cancellation-tests-2.log、/tmp/agentscope-go-native-cancellation-tests-5.log、/tmp/agentscope-go-native-cancellation-build-1.log、/tmp/agentscope-go-native-cancel-portal-build-1.log。

后续进程证据：/tmp/agentscope-go-native-job-process-smoke-5.log 与 /tmp/agentscope-go-native-conversation-process-smoke-2.log 验证原生 Invocation 终态收敛后的两类完成链路；/tmp/agentscope-go-native-job-cancel-process-smoke-1.log 验证运行中取消、重复回执、Host renew 403、停止证明之前保留 cancel_requested、Agent Service 重启后的 cancelled/command applied、旧 fence 拒绝及 SSE 续传，9 条公共事件。

实现边界：尚未完成 Hosted CLI/MCP/文件链路、resume/approval/steer/inject、External/Managed 混合及 Team/Workflow 的完整公开映射和能力适配。取消仍复用旧编排图清理，需要继续改为有界增量推进；Host 离线后的停止证明补偿、孤儿管理、暂停续租及 journal 并发/存储上限仍需实现。Agent Service admission/fact payload 已加密，原生 Attempt 现有 resolved provider configuration 不应宣称已经全部加密。动态覆盖、统一 Invocation、管理摘要、角色账户、migration job、部署及客户端均继续推进，整体 P0–P9 未完成。

## 2026-10-11 Go 生命周期镜像与事件封存

Go 私有运行角色已接通 Session lifecycle mirror。Session 行锁串行更新 active/archived/deleted，按 revision 保留新状态，旧命令返回 superseded，同版本不同命令或摘要冲突，deleted 不能再次恢复。归档阻止新的 Inbox Turn，实际执行仍需独立 Agent Service grant。此 mirror 不创建执行授权，也不延长任务存活。

跨语言进程验证发现生命周期回执摘要不一致：Java record 保留声明字段顺序，Go canonical digest 对对象字段排序。新增 RuntimeLifecycleDigest 将 Java record 转成嵌套 map 后规范排序，Agent Service 与 Managed 数据面同步使用；共享 lifecycle/receipt fixture 校验相同摘要。旧 Service 协议及数据空间不能与这套新构建混用。

已验证：内存与真实 PostgreSQL 双连接中的归档/恢复、重复命令、旧 revision、跨 scope、deleted 拒绝恢复；Java 5 项协议 fixture + 2 项 Agent Service app 检查、Agent Service/数据面 jar 构建、Go 摘要检查通过。真实 Agent Service/Go/PostgreSQL Hosted Job 生命周期进程检查通过，archive/restore 的 mirror command applied、重复回执及归档新 Turn 409；真实 Agent Service/数据面/本地模型的 Managed 生命周期回归通过，4 次模型调用、24 个公开事件，archive/restore/delete、两服务重启、SSE 和固定快照继续通过。

Host 最终事件与 physical-stop 现在通过共享 Attempt stream lock 串行，封存前接收的事件完成原生与 Session 投影后，停止证明才能提交。内存及 PostgreSQL 的 Job/Conversation/取消/schema 失败共 7 个代表性场景验证：原生 terminal 后、proof 之前的事件仍接收；proof 之后 accepted=false。完整并发封存及 journal 崩溃矩阵留在后续清单。

日志：/tmp/agentscope-go-native-lifecycle-tests-1.log、/tmp/agentscope-lifecycle-digest-go-tests-1.log、/tmp/agentscope-lifecycle-digest-build-1.log、/tmp/agentscope-go-native-lifecycle-process-smoke-2.log、/tmp/agentscope-managed-lifecycle-digest-regression-1.log、/tmp/agentscope-go-native-event-seal-tests-1.log。首次 Go lifecycle 进程检查因摘要冲突失败，修复后的第二次通过；保留失败日志用于排障。

本节当时的 smoke 使用手动认证 Host 替身；后续真实 daemon、增量映射、MCP 和 journal 的新增证据见下一节。

## 2026-10-11 真实 Hosted daemon、协作 MCP 与有限 journal

Codex app-server 的文本增量、消息开始/完成、shell 工具请求/输出及 token usage 已转换成标准公开事实。消息和工具 ID 带 Attempt 命名空间；子通知保留原来源，恢复 checkpoint 使用根 provider Session。首次累计用量排除以前 Turn 的历史，重复累计报告不重复计费，最终结果只补正差。Claude/Qoder 文本与工具块、QwenPaw 文本已有代表性映射，完整 provider 格式仍待补齐。映射依据实际安装的 Codex schema 及[官方 app-server 文档](https://learn.chatgpt.com/docs/app-server)，测试不调用模型。

协作 MCP 经 Agent Service 的 `/mcp/collaboration` 进入私有 Go 运行角色，保留原任务身份。新 Hosted task token 绑定 Attempt、dispatchGeneration、物理 fence 和 Host；运行角色同时校验根许可、实际租约和内部传输凭据。旧 fence、无 fence、缺任务/服务身份及过期根许可均拒绝。普通 MCP 与控制续租使用不同容量池，内部凭据不进入 provider 环境。Managed 混合任务及 MCP 资源管理能力继续接入。

Host journal 改为每个事件独立文件及小型 metadata，提交包含文件和目录同步；生产扫描有限处理 8 个 Attempt、投递 64 个事件，清理每轮最多 64 个文件。限制为 256 个待交付 Attempt、每 Attempt 65,536 个事件/256 MiB、单个事件 1 MiB、metadata 8 MiB。未知 ACK 保留原身份和记录，失败使用 1–30 秒基数及有限抖动退避，积压达到上限时停止新领取。终态和物理停止回执成功后，持久 cleanup 阶段只继续清理，不重新投递停止操作。单状态目录多进程排他和非终态崩溃恢复仍需实现。

实际 daemon 验证发现 Attempt 凭据被 complete 提前移除，使 physical-stop 401；已改为停止回执成功后才释放。续租 403/409 后新增只读 observe，以相同 lease/fence 读取权威状态，不延长租约/许可；取消会终止 provider，再确认取消和实际停止。provider 事件写入、续租更新和最终状态读取串行协调，代表性 `-race` 检查通过。

已验证：真实 Agent Service、Go、PostgreSQL 和 Host daemon，使用本地 fake Codex CLI。完成场景 18 个公开事件、取消场景 16 个，均报告 5 tokens 一次；MCP 初始化、tools/list、task.get、math.evaluate 的实际 HTTP 调用通过，快照显示文本/工具结果/用量，Agent Service 重启、SSE 续传、archive/restore mirror 和 journal 排空通过。取消 CLI 等待过程中由 Host 实际终止，没有外部模型调用。测试选项 `--exercise-daemon`、`--daemon-cancel`；默认手动 Host 场景仍保留停止前 Agent Service 重启和旧 fence 断言。

日志：`/tmp/agentscope-runtime-mcp-process-smoke-1.log`、`/tmp/agentscope-runtime-cancel-daemon-smoke-1.log`、`/tmp/agentscope-host-bounded-journal-tests-3.log`、`/tmp/agentscope-host-attempt-race-tests-3.log`、`/tmp/agentscope-runtime-mcp-postgres-tests-1.log`、`/tmp/agentscope-host-stop-observe-tests-2.log`；Java AgentServiceRuntimeHostsTest 实际运行 3 项、Go 四个入口构建通过。最初真实 daemon 的停止凭据失败日志保留，修复后的完成和取消检查通过。

继续实施：有界取消图推进、approval/resume 及能力适配、文件/产物、Host 离线/孤儿补偿、完整 provider 映射、External/Managed 混合、Team/Workflow、统一 Invocation 和后续 P6–P9。以上不因当前 Hosted 闭环通过而标记完成。

## 2026-10-11 编排取消的有限推进和 Host 目录排他

Run cancellation 先持久化 cancelling 意图，再每轮处理最多 8 个活动节点；每节点最多处理 16 个子 Run、pending approval 和活动任务。对子 Run 只提交停止意图，由各自 Worker 推进，不在父锁内递归清理。节点按 UUID keyset 和持久 cursor 续扫；等待子 Run 的节点不会饿死后面的节点，逻辑 Run 终态只在没有活动节点后提交。后台每个 Run 的推进有 20 秒期限。Agent Service 路径从根 Run 和显式子节点传播停止，停止最终关口仍独立等待所有实际 Attempt 的停止证明及事实。

活动任务列表过滤终态并可跳过历史输入，物理任务取消每轮最多提交 16 个尚未请求停止的 Attempt；未排完时任务保持可发现。新增 0121 节点 keyset、活动任务和子 Run 的 partial index，生产查询保留可见活动条件。旧兼容 Invocation 和 fail-fast 仍有历史整图路径；独立 Issue Run 的所有权/取消及完整 Team/Workflow 汇总继续实施，不能把本节等同于所有运行入口已改完。

Host daemon 在身份读取、注册和 provider probe 之前取得状态目录的操作系统文件锁；第二个 daemon 使用相同目录立即失败。文件保留，不删除锁 inode；正常退出/进程结束释放 OS 锁。Unix 使用 flock，Windows 使用 LockFileEx，复用已有 x/sys 依赖。

验证：内存及真实 PostgreSQL 的 25 节点/等待子图、不同 coordinator 接续、33 活动任务分批清理通过，既有取消及 fail-fast 代表性检查保持通过。Hosted Job/Conversation 的 7 个代表性 PostgreSQL/内存场景与旧 Invocation cancel 幂等检查通过。真实 Agent Service/Go/PostgreSQL/Host daemon 取消进程回归通过，第二 daemon 在注册前拒绝，16 个公开事件、5 tokens、MCP、快照、SSE、生命周期镜像及 journal 排空保持通过。状态锁重复/释放检查通过，Windows daemon 交叉编译和 Go 四制品构建通过。

日志：`/tmp/agentscope-bounded-cancel-core-tests-2.log`、`/tmp/agentscope-bounded-native-cancel-postgres-2.log`、`/tmp/agentscope-bounded-daemon-cancel-smoke-1.log`、`/tmp/agentscope-runtime-host-lock-tests-1.log`、`/tmp/agentscope-runtime-host-lock-windows-build-1.log`、`/tmp/agentscope-runtime-host-lock-build-1.log`。完整并发故障、目录损坏和 Windows 实际运行矩阵后补；暂停审批、非终态恢复、Host 离线及其余阶段继续实施。

## 2026-10-11 Hosted 人工工具确认与能力查询

Hosted 确认的创建、轮询和交付 ACK 经 Agent Service 进入私有 Go 运行角色，始终使用原 task token 的物理 Attempt、Host、dispatchGeneration 和 lease fence。等待只维护物理租约，不刷新根执行许可。公开 Turn actions 接收根确认答复；持久化 Inbox 隔离旧激活的写入，保存原人工身份，在刷新调用者与审批人的权限后有限处理每轮最多 8 个决定。允许和拒绝均由 Host 确认后发布 `required_action.resolved`，受理回执不等于应用。新控制台决定接口拒绝写入 Agent Service 所属工具确认，运行回调还核对不可变审批命令和当前执行许可，防止旧入口绕过准入。

事实使用现有标准 `required_action.created`，形成持久化待办。审批交付事实保留原激活代次/fence，较晚观测到的 ACK 不会改记到下一次审批；状态与 outbox 提交仍要求当前工作 claim，只有与已持久化答复匹配的确认结果允许携带历史证明。已提交决定的重试可在 provider 已结束后完成，避免决定写入与推进状态提交之间崩溃造成死循环。实际任务恢复为 running 后可使用新许可，不必等待公开视图的下一次观测。

新增公开 Turn capabilities 读取接口，按当前后端和状态列出已接通操作。Hosted 只有取消及已观测到的根工具确认；steer、inject、显式 resume 和任意 checkpoint_restore 不作支持承诺。Managed 继续列出其输入、恢复和动作能力。查询按 Session/Turn scope 鉴权并有限读取一个当前待办；具体动作仍在受理和激活时核对权限。

已验证：内存和真实 PostgreSQL 的 10 个 Hosted Job/Conversation、允许/拒绝、取消、输出 schema 场景；双副本重复审批、旧 claim 拒绝、取消竞争及跨激活迟到证明通过。代表性 `-race` 检查含控制面写入门禁、交付时的准入证明及旧 Hosted/Managed 审批回归。Agent Service Runtime Host 传输 4 项、capabilities 1 项测试和 Java package 通过；Go 四个制品构建通过。

真实 Agent Service、Go、PostgreSQL、Runtime Host 及 fake Codex CLI 的允许和拒绝均通过：等待跨 30 秒物理租约周期，Agent Service 等待中重启，重复答复去重、改变答复 409、Host 实际收到 accept/decline、确认后协作 MCP、applied 回执、快照、SSE 续传、27 个公开事件、5 tokens 一次及 journal 排空。没有外部模型调用。首次进程检查暴露事件类型未进入待办投影和测试租约不足以覆盖重启，修正后的两个场景通过，失败日志保留。

日志：`/tmp/agentscope-hosted-approval-flow-tests-4.log`、`/tmp/agentscope-hosted-approval-historical-tests-1.log`、`/tmp/agentscope-hosted-approval-ownership-tests-1.log`、`/tmp/agentscope-hosted-capabilities-portal-build-2.log`、`/tmp/agentscope-hosted-approval-created-build-3.log`、`/tmp/agentscope-hosted-approval-daemon-smoke-2.log`、`/tmp/agentscope-hosted-denial-daemon-smoke-1.log`。

继续实施：公开 Hosted 创建、各后端完整 capabilities 与控制，Team/Workflow 的普通审批/信号和并行映射、Hosted 非终态恢复及离线补偿、长任务身份刷新、文件/产物、External/Managed 混合和 P6–P9。本节不代表 P5 整体完成；大规模故障与边界测试留在后补清单。

## 2026-10-11 公开 Hosted Session 创建

Agent Service 的公开 Session 创建现支持 Managed 与 Hosted，保留原调用者、共享策略范围与冻结 revision，projection 返回的 runtimeKind 必须与准入选择一致。其他后端继续显式 501，逐个接通。重复创建沿用已有幂等与权限重验。

Java 创建、capabilities 和 Runtime Host 传输共实际运行 7 项测试，package 通过。真实 Agent Service、Go、PostgreSQL、Host daemon 与 fake Codex CLI 的公开 Hosted Conversation 创建、重复创建、能力查询、MCP、Agent Service 重启、18 个公开事件、5 tokens 一次、快照、SSE、生命周期和 journal 排空通过。配置源为隔离 HTTP fixture，创建后关闭；这不等于真实控制面账户/RBAC 创建已验证。Managed 公开创建和执行回归通过，24 事件、4 次本地模型调用以及双进程重启续传。

日志：`/tmp/agentscope-hosted-public-create-portal-build-1.log`、`/tmp/agentscope-public-hosted-daemon-smoke-1.log`、`/tmp/agentscope-managed-public-create-regression-1.log`、`/tmp/agentscope-hosted-public-create-go-build-1.log`。尚需真实控制面身份/权限创建集成；其余 P5–P9 继续实施。


## 2026-10-11 Workflow 根审批、信号与授权前停止

公开 Session 创建已增加 Workflow Job。执行保留整个准入输入及冻结 revision，仍有效的
Agent Service 授权不依赖实时定义是否已被修改或归档。Team 的后续进展见下一节；External/混合创建暂未开放。
普通审批采用稳定 UUID，避免决定已落库但节点等待状态尚未提交时重复创建。
根 actions 一批答复确认或信号，保留原调用者和指定审批人，在持久 Inbox 分配新激活
fence 后取得新许可；只读观测和物理停止不借用该许可启动执行。

图节点每轮按 UUID keyset 读取 16 个，最多缓存 8192 个节点的版本，直接产生标准
step 状态及待办，不假设每次节点变迁都有 RunEvent。终态需要完整的最后一轮节点扫描、
最终输出和实际 Attempt 停止关口。人类等待与自主等待分别通过索引 existence 查询区分；
有独立分支仍运行时根许可继续刷新，只有人工/信号等待时无需续根许可。
信号使用按节点定向的持久事件，每轮消费最多 8 个答复，重复不改变原值；legacy broadcast
不能覆盖 Agent Service 答复，旧控制面及 MCP 广播入口不会报告虚假的成功受理。下游 CEL 的
有符号/无符号 64 位整数使用整数类型，避免先转 float64 丢失大整数精度；其他数值仍采用
CEL double 语义。控制内部证明不进入节点输出或公开事件。

答复受理后、新许可取得前取消或拒绝时，运行时保留上一轮证明以排空已经发生的事实。
最终停止回执必须通过实际停止关口，并匹配当前持久化答复或取消意图，之后才结算当前
激活与释放配额；授权已提交但响应丢失的情形也可收敛。它不会给新激活增加执行权限。

已验证：Workflow 内存/PostgreSQL 的允许、拒绝、并行、信号、35 节点最终分批扫描、
授权前取消与拒绝共 14 场景通过 -race。共享协议 6 项、Agent Service 停止证明 7 项、子事件
1 项与 capabilities 1 项通过，Java package 和格式检查通过。真实公开 Workflow 允许、
拒绝及并行取消已完成 Agent Service/Go/PostgreSQL 进程验证，等待中重启两个服务、应用回执、
快照与 SSE 续传分别为 13/12/14 事件。执行前已停止控制面；配置源为隔离 HTTP fixture，
不代表真实控制面 RBAC 集成已验证。

日志：`/tmp/agentscope-workflow-public-process-5.log`、
`/tmp/agentscope-workflow-deny-public-process-1.log`、
`/tmp/agentscope-workflow-parallel-public-process-1.log`、
`/tmp/agentscope-workflow-precise-signal-tests-2.log`、
`/tmp/agentscope-control-stop-inbox-regression-1.log`、
`/tmp/agentscope-control-stop-portal-tests-build-1.log`。
首次信号进程检查暴露共享 DTO 缺少 signal 类型，已修复并增加共享协议检查；其失败日志
保留。修复后的信号进程检查通过，13 个事件，答复 9007199254740993 进入下游 CEL 仍精确，
双服务重启、applied、快照和 SSE 续传通过。新代码的编排 PostgreSQL 10 场景回归及
文档 430 页/584 redirects 检查通过。日志：`/tmp/agentscope-workflow-signal-public-process-2.log`、
`/tmp/agentscope-workflow-precise-cel-postgres-regression-1.log`、
`/tmp/agentscope-workflow-controls-doc-check-1.log`、`/tmp/agentscope-workflow-precise-go-build-1.log`。

仍待实现：完整 Team、子图命名空间与审批/信号、Agent/MCP 触发信号的持久准入、暂停/恢复
和重试视图、失败/产物汇总、旧整图协调扫描的有限推进、所有后端统一 Invocation，以及
P6–P9 的动态配置、资源入口及部署整合。本节不代表 P5 整体完成。

## 2026-10-11 Hosted Team 公开创建与并行停止关口

公开创建已接通 Hosted Team Job，使用冻结 Team/Agent 配置及公共根准入。协调者通过
原任务身份和物理 fence 的协作 MCP 提交结论；有未完成工作节点时拒绝提前结束。
动态并行节点的逻辑结论齐备后，仍等待所有 Hosted Attempt 的实际停止证明，再排空
节点、结果和用量事实。这些检查中的并行节点使用同一冻结 Agent，不等于完整 roster
委派与子图支持已经完成。

原生 Hosted/Team 的内存及 PostgreSQL 15 场景已通过，包含 Team 根完成、取消和两种
存储的动态并行工作；Java 公开创建实际运行 2 项，package 与格式应用通过。
真实 Agent Service/Go/PostgreSQL 的公开 Team 完成和取消进程检查分别为 13/11 个事件、5/0
tokens，重复创建、Agent Service 重启、旧 fence 拒绝、快照、SSE 续传与生命周期镜像通过。
完成场景实际经过 MCP。配置源为隔离 fixture，Host 为手动认证替身，未执行 provider
CLI，也未验证真实控制面账户/RBAC。执行前已停止控制面。

日志：`/tmp/agentscope-team-native-start-stop-tests-2.log`、
`/tmp/agentscope-team-native-parallel-tests-1.log`、
`/tmp/agentscope-team-create-portal-build-1.log`、
`/tmp/agentscope-team-public-process-1.log`、
`/tmp/agentscope-team-cancel-public-process-1.log`。
子图事件和交互、完整委派/失败/产物、其他控制、External/混合与 P6–P9 继续实施。

## 2026-10-11 原生事实分页、Workflow 子图与容量停止

原生观察按事实数量限制为 120，为状态、结果和停止回执保留事务空间，Inbox 每次仍
最多 128 个事实。单条原生消息生成多条事实时，保存消息内 fact offset，全部输出后
才推进原生 sequence。累计 provider 用量先在候选副本映射，提交时推进，不因本批
空间不足提前消费。内存/PostgreSQL 的 192 个文本块、部分消息提交后重建运行服务、
唯一事件 ID、终态延后及 5 tokens 一次通过；迟到总量的正差记录使用不同计量记录 ID，
避免公开快照把前一份正差覆盖。

共享根 Issue 的 Workflow 子图每轮只观察一个图，读取最多 16 个节点和 16 个 RunEvent，
使用各图独立游标及全局有限状态缓存。公共图/步骤 UUID 区分同名节点，事件保留 graph
和 parent 引用。根终态重新开启完整最终扫描，全部子图与物理 Attempt 完成后再发布。
已答复但尚未消费的审批/信号按可执行工作处理，避免新许可被误判为人工等待而撤掉。
新增 0124 根 Issue keyset 和本地图工作 partial index。独立 Issue 子 Run 的全树发现
与完整 Team 委派仍需继续实现。

两层嵌套的允许、拒绝、信号和独立并行分支，内存/PostgreSQL 8 场景通过。公开嵌套
信号进程验证通过，27 个事件，9007199254740993 进入最终结果仍精确，双服务重启、
applied、快照与 SSE 续传通过。首次进程检查在最后读取时碰到 16 个活动快照上限；
fixture 改为等待事件就绪后读取资源，避免每 200ms 创建一个固定快照。失败日志保留。
配置和 Host 仍使用隔离 fixture；这些证据不覆盖真实控制面 RBAC 或供应商。

8192 图/节点/Attempt、256 provider usage stream、64 内容块及 8192 个待交付 Hosted
确认超限，转入持久化容量停止。停止观察不再增加状态缓存，有限扫描实际任务和最终
计量；原始日志保留。终态使用 `native_observation_capacity_exceeded`，在全部实际
执行停止、已公开图待办及 Hosted 待交付确认排空后才发布。未收到 Host 交付 ACK 的
决定不会被当成已应用。正常取消也分批清理未答复的 Hosted 确认。明确停止的 failed/
interrupted 可结算取消回执；停止中的完成可直接结算等待 Turn，内部证明不进入公开事件。

容量内容块和节点缓存的 4 个内存/PostgreSQL 场景通过；节点检查用已填满的游标触发
保护，未构造真实 8192 节点数据库。待确认取消及容量停止的 3 个原生场景通过，包含
确认先排空、停止前保留占用和服务重建。Java 停止/取消/等待完成实际运行 10 项，package
与格式应用通过。完整上限和故障矩阵后补。

日志：`/tmp/agentscope-native-fact-page-tests-3.log`、
`/tmp/agentscope-native-fact-usage-tests-1.log`、
`/tmp/agentscope-nested-workflow-core-tests-1.log`、
`/tmp/agentscope-nested-workflow-signal-process-2.log`、
`/tmp/agentscope-native-capacity-hosted-tests-1.log`、
`/tmp/agentscope-native-capacity-node-tests-1.log`、
`/tmp/agentscope-native-pending-stop-tests-1.log`、
`/tmp/agentscope-native-pending-stop-portal-build-1.log`。

### 图步骤资源与重连快照

共享资源 reducer、Agent Service 行投影与分页快照、前端 reducer 已接入 `steps`。图和节点
按 step_id 保存，保留 graph_id、parent_step_id 和 parent_graph_id；不同子图同名
节点不会互相覆盖。固定游标快照读取旧版本，新的直接读取返回已提交更新，Turn
范围不会读到其他 Turn 的步骤。步骤投影不参与原生执行恢复。

Agent Service 资源读取 4 项、停止结算 10 项以及 package 通过；前端 reducer 5 项通过。
公开嵌套 Workflow 拒绝流程实跑通过：26 事件、双服务重启、SSE 重放、pending 排空，
最后快照 steps 与已提交事件逐项相同。配置 fixture、真实本地 PostgreSQL，未连接
供应商或验证真实控制面 RBAC。格式应用及 git diff --check 通过。

日志：`/tmp/agentscope-step-portal-build-1.log`、
`/tmp/agentscope-step-view-ui-tests-1.log`、
`/tmp/agentscope-step-nested-deny-process-1.log`。

### Hosted 短期凭据刷新及协作 CLI 入口

Hosted claim 不再用延长 TTL 支撑长任务：任务和 Attempt token 有效期 15 分钟。
Host 在提前两分钟的窗口及报告接口 401 后，凭 Host 身份与当前 lease token/fence
换发。刷新不更新执行租约，过期/撤销 Agent Service grant 不产生任务权限，报告身份仍可
用于日志排空及物理停止 ACK。原始日志 token 到期后也能用同一物理身份重新取得
报告凭据。Host 入驻凭据的长期轮换仍是独立实现欠项，不能认为任意离线时长可恢复。

每个 Attempt 启动回环 HTTP 转发，8 槽、2 MiB 请求、8 MiB 响应、超时、固定目标
和路径许可。provider 环境及 MCP 配置使用随机本地凭据，转发时取最新带 fence 的
任务 token；不携带 Host、控制面服务或人类凭据。人工确认的创建、等待、重试和 ACK
也读取当前 token。Agent Service 接通任务 CLI、所属 Issue/Team 查询和评论；运行角色逐项
检查任务范围和根 grant。上下文返回不下发较弱的 legacy token。Agent signal 与独立
子 Issue 委派的完整执行机制仍保留为实现欠项，增加路由不代表完成这些语义。

Gateway 新 Session、Host 机器协议、MCP、带任务 token 的 CLI 与 ToolTask 路径已
转至 Agent Service；人类 Issue/Team CRUD 和 Host 管理仍到控制面。新增
MANAGED_AGENTS_AGENT_SERVICE_URL，默认 localhost:8084。旧数据面/调度器公网路由、完整
Compose/Helm/镜像与最小数据库权限仍待 P8/P9 扫尾，未部署用户环境。

Go client/relay/approval 代表性检查及 race 通过，内存和 PostgreSQL 原生刷新各 1
场景通过（旧 lease/fence、缺 Host、撤权、限定 TTL、任务上下文范围）。Agent Service 8
项及 Gateway 4 项、package 通过。真实 Host daemon + 假 Codex + 真实 Go/Agent Service/
PostgreSQL 批准流程通过：27 事件、5 tokens、等待跨租约、Agent Service 重启、MCP、SSE；
新增 CLI 上下文检查后普通完成也通过：18 事件、5 tokens。没有外部模型调用，配置
准入仍使用 fixture，Go 执行阶段不依赖控制面。

日志：/tmp/agentscope-host-credential-client-tests-1.log、
/tmp/agentscope-host-credential-native-tests-1.log、
/tmp/agentscope-host-credential-race-tests-2.log、
/tmp/agentscope-host-cli-native-tests-2.log、
/tmp/agentscope-host-cli-portal-gateway-build-1.log、
/tmp/agentscope-host-credential-daemon-process-2.log、
/tmp/agentscope-host-cli-daemon-process-1.log。首次 Hosted 包装进程因专用 schema
未创建而停止；创建后重跑通过，失败日志保留，不算实现通过证据。

### UI 分页快照及用量摘要

客户端按 Agent Service resources 页结构读取首批 200 行，公开后续页接口，UI 按需加载并
复用 snapshot_id。旧页不能覆盖已知实时资源、复活已知解决的动作或推进 SSE 游标。
不同快照拒绝混入。等待指定 Turn 使用 Turn 自己的快照，不受 Session 首批 Turn
数量影响。旧快照格式报版本不匹配，列表读取 data 字段，展示图步骤。信号直接发送
已校验的原始 JSON，避免 9007199254740993 被 JS Number 重序列化舍入。

usage/model 事件在同一提交中附带 Agent Service 权威 session_usage/turn_usage，覆盖源声明
的同名字段；快照及 UI 用权威摘要，避免只加载部分模型资源就把总用量归零。UI
展示已报告用量及并发预算超额语义。Java 准入/资源/停止 15 项、package 与格式
通过。前端 API/reducer 13 项通过；第一次 UI build 用到了超出项目 target 的 at/
replaceAll，改为兼容写法后 build 通过。完整浏览器、客户端及大历史 UI 尚未完成。

日志：/tmp/agentscope-paged-ui-tests-1.log、/tmp/agentscope-paged-ui-build-2.log、
/tmp/agentscope-paged-usage-portal-build-1.log。

### UI 能力控件、历史交错与工具恢复

Turn 列表新增 order=desc 及单状态过滤，游标绑定排序/过滤；UI 默认最新 200 行，
额外读取一行 running Turn，避免超过首批历史或长队列隐藏运行任务。后续列表页按
admissionSequence 合并，同毫秒旧页不覆盖已知实时状态。按所选 Turn 查询一次
capabilities，不向 200 行并发发起能力请求。动作变化也刷新能力，非 Managed 不
显示 steer/inject/resume。修正 requires_action 的 Resume 和 cancel_requested 的
Cancel 广告，它们此前与执行控制拒绝条件不一致。子 Session 使用同一分页资源结构。

晚到的固定资源页先重放该快照之后的有界事件，再合并：消息及工具参数前缀、模型
失败、run 结束、已受理/解决的动作、已删除产物不会被旧页还原。权威用量不依赖模型
历史加载量。UI 索引最多 8192 条、事件 4096 条、序列化文本累计 16 MiB；到限仅暂停
读者并提示从新快照刷新，不停止任务。Turn 和工具列表显示各最多 2000 行；长历史
应通过重新查询浏览，不能认为已经实现虚拟滚动或无限离线缓存。

Managed 工具执行页接通 Agent Service 有界 ToolTask 查询。显式 unknown 重试发送当前
authorization_generation 和副作用确认；Workspace reconnect/restore/discard 使用
当前 workspace_generation、实例空闲或已不存在及数据丢失确认。两者独立命令，受理
后显示 accepted，再查询 command_status，不把 HTTP 202 当作实际恢复完成。恢复后
要求刷新代次，未知工具仍需独立重试。未连接后端不展示该入口。

代表性证据：前端 API/reducer 17 项、构建和变更文件 lint 通过。真实浏览器的 Hosted
能力/长队列选择及 Managed unknown/Workspace 分别 1 场景通过（HTTP fixture）；
Java 列表/能力/准入/停止 16 项及 package、随后能力 1 项和 ToolTask 7 项及 package
通过。Hosted 批准进程再次通过：真实 Go/PostgreSQL、Agent Service 文件 H2、真实 Host daemon、
假 Codex、配置准入 fixture，27 事件和 5 tokens，重启、MCP、SSE、权威用量事件与
快照相等，终态最新列表及 running 过滤通过。Team 取消进程 8 事件也通过；未调用真实
模型或供应商。浏览器前几次失败来自测试挂载的 Vite 入口/路由拦截/默认导出错误，
修正 fixture 后通过，失败日志保留且不算实现证据。Agent Service PostgreSQL 多副本尚未验证。

日志：/tmp/agentscope-ui-turn-registry-build-1.log、
/tmp/agentscope-ui-tool-recovery-java-build-1.log、/tmp/agentscope-ui-paged-tools-tests-1.log、
/tmp/agentscope-ui-portal-browser-5.log、/tmp/agentscope-ui-paged-usage-hosted-process-1.log、
/tmp/agentscope-steps-usage-team-cancel-process-1.log。完整资源管理 UI、其余客户端/SDK、
真实 Gateway、分层部署、角色账户、migration job 与其他 P0–P9 实现继续推进。

### Hosted 原生派发快照加密

新增 Store 的小型 DispatchCodec，复用运行角色已有 AES-GCM Cipher 和稳定 Vault
key；内存 fixture 为本 Store 创建临时 key。派发前先 clone 冻结快照，再将 provider
configuration、resolved configuration、双层 execution overrides、Workspace 定义
从可读 metadata 移入密文。原生 Task、Attempt、同事务派发 outbox 使用相同密文记录。
metadata 留存调度索引、provider、profile/pool 版本及关联标识，原 SQL 认领和查询
不需要解密配置。身份绑定 Task、Agent/binding、Agent Service/Session、profile/pool、候选
来源和派发时间；JSONB 的 capability 数值/键排序不改变这些稳定身份。

授权 Host claim 解密实际执行配置，RetryTask 和 node binding 选择、Workspace 定义
应用检查也读取原冻结值。没有稳定 key 时 PostgreSQL 派发报错，不回退明文；新的
Agent Service Hosted snapshot 拒绝旧明文格式。历史重建数据应按允许不兼容重建的约定重新
初始化。本步骤不宣称全部资源定义、原生日志或 Host 本地文件已加密；角色数据库
账户、migration job、动态配置覆盖等仍需实施。

Cipher 新增 2 项证明字段移除、源对象不变、精确大整数恢复、跨 Task/provider 拒绝、
缺 key/旧格式拒绝、JSONB 格式差异；agentservice/taskplane/runtimebinding/scheduler
现有包测试通过。带实际 PostgreSQL DSN 的原生 Hosted matrix 通过；额外直接查询
PostgreSQL Task/Attempt/派发 outbox，未出现冻结配置明文且保留 sealedConfiguration。
4 个 Go bin 构建通过。真实 Host daemon + 假 Codex + Go/PostgreSQL + Agent Service 文件 H2
批准/重启/MCP/SSE 进程再次通过，27 事件、5 tokens；配置准入仍为 fixture，无真实
模型或供应商访问。第一次旧测试直接解析明文断言失败，改为先验证密文再解密核对
同一冻结值；没有降低冻结配置及终态断言。

日志：/tmp/agentscope-dispatch-sealing-tests-2.log、
/tmp/agentscope-dispatch-sealing-tests-3.log、/tmp/agentscope-dispatch-sealing-storage-test-1.log、
/tmp/agentscope-dispatch-sealing-hosted-process-1.log。

### 独立 schema 初始化与普通副本只读校验

两个 Go 服务入口默认 `--schema-mode=validate`；`--migrate-only` 在 Store 初始化后
退出，控制面可以一起初始化 Product，绕过产品用户/Workspace 初始化、HTTP、
Kubernetes 和执行 Worker。控制面也已接入 RuntimeMasterKey。原有 Go Store 的
默认开发调用保持 migrate，运行入口显式设置模式。

Product 新增 4 个初始迁移版本及 SQL 校验和，独立连接锁与逐迁移事务；普通启动
不执行 schema SQL。Java Common 增加独立 ServiceSchemaMain，使用原 Agent Service/Data
jar 的 PropertiesLauncher，不启动 Spring 业务容器。Agent Service 固定 SQL 与从当前
JPA/JDBC 生成的数据面基线作为 V0001，后续追加迁移；JPA 默认为 validate，数据面
JdbcStore 自动建表和 DpSchemaInitializer 仅在显式嵌入式开发初始化中使用。
PostgreSQL 普通启动校验版本/校验和，仍用 Hibernate/JDBC 校验自己的结构。

真实 PostgreSQL 的 Runtime 元数据只读角色、Product 并发初始化/只读角色、Agent Service
并发初始化/只读角色测试通过；代表性缺失/未来版本及 checksum 拒绝通过。Product
schema 初始化后用户数为 0。Java AgentServiceSchemaMigrationTest 和 AgentHarnessRuntimeAppContextLoadTest
通过，两个 Go bin 和两个 Java jar 构建通过。普通 H2 fixture 已明确开启开发初始化，
Go process fixture 在启动前单独运行迁移任务。

新增可重复的 agent_service_postgres_smoke.py：只对指定本地测试容器创建本轮唯一
schema 和业务账号，实际执行两个 jar 的迁移入口，再以没有 CREATE 权限的 Agent Service
和 Data 账号启动进程。单副本 Managed 闭环通过，两轮推理含 memory flush 共 4 次
本地模型调用，24 条公开事件，Turn/Command 幂等、进程重启、SSE 与分页快照通过。
控制面准入和模型仍为 fixture，不涉及真实供应商；测试结束删除本轮 schema/账号。
多副本及 Tool Worker PostgreSQL 进程验证在后续证据中单独登记。

随后两个 Agent Service + 两个数据面副本同时连接同一 PostgreSQL 数据空间的 fixture 通过。
两个 Agent Service 分别受理同一个幂等 Turn，返回同一 ID；两轮推理与 memory flush 仍为
4 次模型调用，24 条事件跨 Agent Service 查询一致，之后停止额外副本执行重启与重放。
该证明是同 Session 去重/认领及跨副本查询的代表性检查，不是高负载容量结果。

Tool Worker 的实际 jar + PostgreSQL Agent Service/Data 独立账号链路也通过：持久化 ToolTask、
部分结果、Worker 重启、Sandbox 绑定复用、结果继续推理和 SSE/快照共 60 条事件，
7 次本地模型调用；假 E2B 协议记录 create=1、connect=1、execute=12。两个新 fixture
均已清理自己的进程、schema 和账号，原测试 PostgreSQL 容器保持运行。

初始化用法与边界见 [数据库初始化与升级](agent-service-schema.md)。Go rt 内部的
资源/协作/运行表写权限仍需结合入口迁移完成；Compose/Helm、发布制品和全部组件
的最小写权限还未完成，P1/P8/P9 不因此标记整体完成。

日志：/tmp/agentscope-runtime-schema-readonly-tests-1.log、
/tmp/agentscope-product-schema-readonly-tests-1.log、
/tmp/agentscope-java-schema-build-tests-1.log、/tmp/agentscope-postgres-dml-process-1.log。
追加日志：/tmp/agentscope-postgres-replica-process-1.log、
/tmp/agentscope-postgres-hands-process-1.log。

额外直接执行控制面 bin 的 `--migrate-only`，在本轮新建的隔离 PostgreSQL 数据库
初始化 rt 与 cp；未提供服务/JWT/Vault secret，成功退出，Product 用户数为 0，
Product 版本数为 4，未创建 Workspace 目录。数据库随后删除。
日志：/tmp/agentscope-schema-cli-process-1.log。

### Go 运行角色拒绝未获 Agent Service 准入的执行

编排 Engine/Worker 和 TaskPlane 增加内部 RequireAgentService 开关，独立 Go 运行入口
及 RuntimeOnly Server 显式启用。Engine 在处理 planned、cancel/terminal 收敛前
检查来源契约是否包含 Agent Service command；Task 派发、Host claim、preparing/running、
续租也拒绝空契约。旧角色已创建但尚未交给 Host 的 Attempt 被严格 claim 拒绝时，
会先取消这次尚未公开的物理分配。RuntimeOnly 的调度资源鉴权和运行凭据刷新也
不能使用旧契约无 Agent Service ID 时的宽松分支。原有库/过渡角色保留默认行为。

新引擎和 TaskPlane 的 2 个代表性测试通过：未准入 Run 不更改自身/根 Issue，未
准入 Task 不派发，旧未公开 Attempt 不能被 Host 取得并续租。orchestration、
taskplane、runtimebinding、controller 包测试通过。实际 PostgreSQL DSN 下的
Hosted 冻结配置/physical-stop 与 Workflow 批准、信号、嵌套、并行、容量矩阵通过，
Runtime Host 私有路由身份测试通过；新 Gate 没有阻止已经通过 Agent Service 的正常流程。

该改动收紧复用引擎的新执行路径；公开旧资源 CRUD 中产生运行意图的路径、旧
outbox 的来源过滤、各类子任务所有权及入口收敛仍需实现，不能由此宣称控制面
及所有运行入口已经完成拆分。

日志：/tmp/agentscope-runtime-portal-required-tests-1.log、
/tmp/agentscope-runtime-portal-required-native-tests-1.log、
/tmp/agentscope-runtime-portal-required-legacy-tests-1.log。


### 七组件制品、初始化接线与实际服务启动

release.py 的镜像集合现在包含 Control、Gateway、Agent Service、Dataplane、Go 编排运行时、
Tool Worker、Scheduler；Java release verify 选择五个执行模块。新增 Go 运行角色 Dockerfile；
Compose 与 dev-up/dev-down 接入新角色，先运行独立初始化及业务授权，再启动校验模式
副本。dev-up 未对用户正在使用的服务执行；源码 Compose 的解析、build 映射与 SQL/
授权脚本路径实际校验通过。

Helm 配置支持六个 Deployment、可选 Tool Worker StatefulSet、三个迁移 Job、schema.only、
独立迁移 Secret、逐组件 Secret 和副本数。Agent Service/Data/Go 的并发与连接池可配置。
Workspace/Artifact 共享 RWX，Tool Worker 以稳定 pod 名保留各副本的独立记录目录；只有
Gateway 的入口配置面向外部。迁移凭据不能配置到普通工作负载。

真实 PostgreSQL 的新数据库初始化后，以部署脚本创建五个业务账号。账号不拥有
schema/表，不具有 CREATE 权限；迁移元数据仅可 SELECT，Agent Service 与 Data 相互没有
schema USAGE。授权脚本拒绝 owner/特权账号，密码及授权在带超时的单事务中提交。
实际启动 Control、Go 运行时、Agent Service、Data、Scheduler、Gateway 六个进程，Gateway
登录、内部接口屏蔽和独立 Go readiness 命令通过。本次没有运行模型；Managed 与
Tool Worker 执行证据仍使用前面的各自进程 fixture，不能拼接成已验证的完整部署模型流程。

Java 五模块构建与 Scheduler context 测试通过；37 个发布/Helm/package 测试通过，
包含 Tool Worker 两副本目录、迁移 Secret 隔离、只初始化模式。源码 hygiene 和 docs check
通过。未运行完整镜像多架构构建或 Kubernetes 安装，未执行生产部署/切流。

P8 的 Go rt 资源/运行表及 Scheduler dp 表/列写权限、剩余入口迁移和全套客户端仍属
实现欠项；部署初步接线不代表整个 P9 已完成。环境导入、所有后端/文件/SDK 集成、
新旧环境排空和回退文档仍需完成。

日志：/tmp/agentscope-seven-roles-java-build-1.log、
/tmp/agentscope-seven-roles-release-tests-3.log、/tmp/agentscope-service-deployment-process-1.log、
/tmp/agentscope-service-deployment-grants-3.log、/tmp/agentscope-seven-roles-hygiene-1.log、
/tmp/agentscope-deployment-docs-check-1.log。

### 执行来源查询有界

ContractForIssue 遇到循环父 Issue 链直接 Forbidden，来源查找最多 256 次数据库读取。
普通三层、单节点循环和无限父链代表性测试通过，invocation/taskplane/orchestration
包通过。该限制为异常输入提供有界失败；正常 Agent Service 契约的运行角色要求保持不变。
日志：/tmp/agentscope-runtime-ancestry-tests-1.log。

### Runtime Host 机器凭据续期及控制台注册作用域

独立运行角色新增 Host credentials 接口，仅接受已验证的 Host bearer 和当前注册
代次，保留机器身份，不修改注册 lastSeen/state/generation，也不续执行租约。Agent Service
使用保留控制槽位转发，Gateway 只暴露标准机器通道。daemon 在剩余不足 24 小时
时续期，串行合并并发刷新，5 秒超时及有限重试；失败时继续使用尚有效的凭据。
续期先持久化再用于请求，缓存绑定原初始凭据指纹及 Host/tenant/namespace；同一
状态目录重启可复用，新注册优先，过大或异作用域缓存拒绝。Unix 文件权限 0600，
Windows 使用目录 ACL。已过期且无有效缓存时需要重新注册；签名 key 轮换及此前
token 的主动撤销尚未实现，不能把自动续期视为撤销机制。

真实 Gateway 联调发现 single-scope 注册 handler 会覆盖控制台已经授权的 namespace，
现改为优先使用鉴权结果；拥有者注册及只读拒绝的代表性回归通过。实际 PostgreSQL
及五个业务账号下，六个服务进程完成 Gateway 登录、控制面 enrollment、Agent Service 转发
Host 注册及 credentials 续期。真实 daemon + fake Codex CLI 的 Hosted 工具确认、
用量和 SSE 流程也通过；daemon 用同一初始凭据重启后复用缓存，未重复续期。没有
调用外部模型/provider，未做跨 30 天 TTL 的长期运行。

日志：/tmp/agentscope-host-machine-namespace-tests-2.log、
/tmp/agentscope-host-machine-related-tests-1.log、/tmp/agentscope-host-machine-race-tests-1.log、
/tmp/agentscope-host-machine-java-build-1.log、/tmp/agentscope-host-machine-gateway-process-2.log、
/tmp/agentscope-host-machine-portal-process-1.log、/tmp/agentscope-host-machine-daemon-process-1.log。

### 真实控制面 Managed 集成及有界失败清理

六个实际服务进程及独立 PostgreSQL 业务账号下，使用真实控制面账号创建
Environment/Managed Agent，再经 Gateway→Agent Service→Data 准入并执行一个 Turn。
Session 与 Turn 重复提交去重，2 次本地 fake DashScope 调用、13 条公开事件和
权威用量一致；无外部模型调用。该流程补足之前使用配置源替身的身份与资源边界。

联调修正两个真实问题：内部 runtime-admissions/sessions 使用与公开 Session
相同的 namespace work.write 准入，随后仍检查实际 target use 与依赖资源；原生
完整身份组合后的日志路径超过 JDBC Store 255 字符主键。Service 日志现在在私有
版本化 namespace 内将物理 key 映射到 SHA-256，完整逻辑路径及内容保存在 envelope，
读取校验映射、保持原 CAS/version。长身份、独立读实例、用户隔离和替换 writer
后旧写入拒绝的 H2 测试通过；原生 Core/Harness 日志格式没有修改，新环境不混用
此前重建阶段的 Service 日志 namespace。

fail-fast 先持久化 Run cancelling 与原始失败原因，使用原有游标每轮处理 8 个
活跃节点、每节点 16 个义务；子 Run 只提交取消意图，不在父锁中递归推进。另一个
协调实例可以继续游标，兄弟清理完成后转 failed，失败事件不重复。节点 timeout
也先有限清理自身义务，尚有剩余时不能提前标记节点 failed。移除被替换的旧
stopNodeWork helper。25 个兄弟节点及等待子 Run、40 个任务 timeout 的内存和
实际 PostgreSQL 测试通过；orchestration/model/collaboration/agentservice 包通过。
真实 Agent Service/Go/PostgreSQL 的嵌套 Workflow 拒绝、重启、26 条事件及 SSE 再验证通过。
公开 Turn 终态仍须等待原有 physical-stop 关口。

一般推进的 ListNodes/ListEdges、materialize 与终态 Issue 树收敛仍包含全量读取，
上述有界清理没有解决这些独立实现欠项；不能把它作为整个编排调度有界的证明。

日志：/tmp/agentscope-real-admission-permissions-tests-1.log、
/tmp/agentscope-native-journal-build-tests-4.log、/tmp/agentscope-managed-real-control-deployment-4.log、
/tmp/agentscope-failfast-batched-tests-1.log、/tmp/agentscope-failfast-related-tests-3.log、
/tmp/agentscope-timeout-batched-tests-1.log、/tmp/agentscope-failfast-native-workflow-process-1.log。


### 实际七组件 Managed 与平台 Tool Worker 部署链路

在同一完整部署 fixture 中，以真实控制面账号创建 Sandbox Environment 和 Managed
Agent；Gateway→Agent Service→Data 执行，平台 Tool Worker 通过私有 Agent Service 通道取得两次工具任务，
完成 write_file/read_file、归档、结果回传和继续推理。Worker journal 为 reported，
两份共享归档可读；4 次本地 fake 模型调用、43 条公开事件、权威用量和 Gateway SSE
续传一致。假 E2B create=1、connect=1、execute=8，未调用外部供应商。普通业务账号
仍不具 DDL 权限，公开 Gateway 拒绝内部 Tool Worker claim。

这补足前面六组件启动及分别验证推理/Tool Worker 的范围；当前为每种组件一个真实进程，
不是完整部署的多副本压力结果，也没有构建七镜像或安装 Kubernetes。文件等剩余
入口还在继续实现，P8/P9 不整体完成。

日志：/tmp/agentscope-seven-components-managed-hands-process-2.log。

### Managed 文件输入、显式产物及七组件联调

Agent Service 新增 Session 文件上传、列表、元数据和内容接口，按原始调用者检查 interact/read，
只向数据面传递内部凭据与冻结 owner。私有数据面接口校验新 Runtime bootstrap 的 owner，
不再使用旧 ManagedRuntimeSessions 注册表。Agent Service/Data 默认各 16 个传输槽位，整次
传输限时 30 秒，饱和 429；未知长度上传也累计限制，下载槽位保持到实际写完或取消。
Managed 根 Turn 先规范化输入并解析 Session 文件，再构建原生消息；最多 128 引用、
合计 64 MiB，重复引用计入限制。单文件默认 16 MiB，其他后端的输入适配仍未完成。

显式产物由 Agent Service 写入不可变幂等收据、公开事件和查询投影，三者同事务提交。
文件引用必须归属当前 Managed Session；外部 HTTPS 只登记元数据、不下载或授予访问。
删除移除当前记录、保留文件及旧快照；原发布键重放原收据，不重新发布已删除记录。
删除运行端产物保留 Turn ID，使 Turn SSE 能收到删除事件。Agent Service V0002 新增收据表，
原 V0001 校验和不改；升级先迁移和授权新表，再启动普通副本。

完整部署 fixture 以真实控制面资源及 DML-only PostgreSQL 业务账号运行七组件，
Gateway 文件上传/去重/冲突/列表/下载/16 MiB 拒绝、跨 Session 隔离及私有接口边界通过；
图片引用进入本地 fake 模型输入。产物发布、删除、旧快照和幂等重放通过，平台 Tool Worker
完成两工具及归档，4 次本地模型调用、45 公开事件、权威用量及 SSE 续传一致。
不是全后端文件证明、完整部署多副本压力或外部供应商验证。

Agent Service 文件 3、能力 1、原生文件输入 2、私有传输 2、产物 3 个代表性测试实际通过，
Java Agent Service/Data jars 构建通过。其他后端任务产物和文件输入继续实现。
日志：/tmp/agentscope-portal-artifacts-root-build-tests-2.log、
/tmp/agentscope-portal-artifacts-final-build-tests-1.log、
/tmp/agentscope-seven-components-managed-files-hands-process-4.log。

### Go 任务产物统一投影、内容下载及客户端

任务传输保留通过验证的根契约，运行端上传为产物记录绑定不可由公开输入指定的
AgentServiceCommandID。新增 rt 0125 迁移及按 tenant/namespace/Turn/createdAt/id 的索引；
每次观察最多读取 16 产物，重复轮次捕获晚提交记录，以持久化已见 ID 避免跨运行
代次重复事实，最大 8192 产物后明确触发容量失败和原有停止流程。公开数据不包含
provider/storageKey，下载地址统一为 Agent Service Session/Turn 路径。上传后再次检查当前
Task/Attempt 和根授权，已替换或已终止任务不能继续登记。

Agent Service 新增 Session/Turn artifact metadata/list/content；使用当前投影检查原始读取
权限和 Turn 归属，不使用调用者提供的下载 URI。Go 私有端点再次检查不可变 Turn/
租户/namespace/产物绑定、过期、size 与 checksum；16 个传输槽位及 30 秒实际 HTTP
写期限。Agent Service 有界流式传输并保留槽位到写完/取消；Managed file_id 产物复用私有
文件传输，外部 HTTPS 只登记引用，不由服务端下载。

内存与实际 PostgreSQL 验证 34 条产物的有限分页、游标恢复、代次替换不重复、未授权/
跨 Session 和损坏文件拒绝。Agent Service 二进制 >2 MiB、原身份不转发、错误 Turn、读取
权限、慢写满槽与取消释放两测试通过，原产物三测试通过，Agent Service jar 构建通过。
真实 Agent Service/Go/PostgreSQL 的 Hosted 和 Team 手工 fenced Host 路径均通过任务 MCP
上传、公开事实、Agent Service 重启后的元数据/下载及 SSE；没有真实 daemon 或外部 provider
调用，Workflow/External 尚未形成此次产物过程验证。

Python ServiceClient 分离 metadata 和 /content，支持 Session/Turn 分页及删除，下载
读取有 100 MiB 上限；真实 Team 进程验证了实际 ServiceClient 方法。前端按 Session
capabilities 隐藏不支持的附件，显示产物及下载/移除、显式上传发布；模糊发布失败
保留文件 ID 和幂等键。17 相关单元、7 Python 入口、3 浏览器 fixture 通过，包含不重复
上传、相同发布键重试、实际浏览器保存内容与删除；UI 构建和局部 lint 通过。完整 UI/
Java/DSH 客户端和其他运行入口仍有实现欠项，不能据此关闭 P8。

七组件真实部署 fixture 再验证 Managed 产物 /content、文件输入、两次 Tool Worker、45
公开事件及权威用量；模型/E2B 为本地替身，普通业务账号无 DDL 权限。
日志：/tmp/agentscope-runtime-artifacts-go-tests-1.log、
/tmp/agentscope-runtime-artifacts-java-build-tests-2.log、
/tmp/agentscope-native-task-artifacts-team-process-1.log、
/tmp/agentscope-native-task-artifacts-hosted-process-2.log、
/tmp/agentscope-native-task-artifacts-team-sdk-process-1.log、
/tmp/agentscope-artifact-client-ui-browser-1.log、
/tmp/agentscope-seven-components-artifacts-content-process-1.log。

### 执行账号边界、统一客户端与公开 Turn 状态

Data/Scheduler 的 `managed-agents.seed-users` 默认改为 false，部署 fixture 显式关闭 seeding。
真实七组件/五个 DML 业务账号的 Managed 文件及 Tool Worker 链路通过，启动和执行后
`dp.builder_user` 都保持空，仍有 4 次本地 fake 模型调用、2 次工具、45 条公开事件。
原有 JpaUserStore 的 false 分支已经正确；本次调整组件默认值和 fixture 接线，未将其
误报为 Store 忽略配置。三项 seed 测试及 Data/Scheduler jar 构建通过。

Python ServiceClient 的列表改用 keyset，动作统一 answers，命令查询使用 Session 路径，
待办使用 required_actions 资源页；快照及 SSE 有明确格式和内存限制。示例不再分叉
Managed/Team 审批格式。DSH 导出原始用户/Application 身份的 ServiceClient，支持公开
Session/Turn、资源、命令、SSE、权威用量及产物内容；OrchestrationClient 不再提供直接
启动/控制旧 Run 的方法。原 DSH 注册和 External 执行通道仍待后续入口迁移。

控制台通用 Turn 面板使用真实 Agent Service 资源快照、Session 级命令状态和 /content 下载。
资源页加载后重放已收到的事件，避免旧页覆盖消息增量或复活已删除产物；显示限制为
8192 条/16 MiB/4096 个实时事件，达到后保留错误和显式刷新入口。Application 请求不再
同时携带用户 Authorization；外部 HTTPS 产物展示独立链接。23 项相关前端测试和 UI
构建通过。此面板完整浏览器场景仍未验证，不能用已有 SessionExecution fixture 代替。

真实 Team SDK 过程发现终态事实未提供 payload.status 时，公开 Turn 资源缺少状态。
Agent Service 现按事实类型规范化 status；当当前执行者的 running 与 cancel_requested/终态不
一致时，保留为 execution.running，不覆盖逻辑 Turn 投影。13 项关键 Java 测试包含
缺少 status 的完成、取消/终态后的迟到 running 及带错误 status 的事实；Agent Service jar
构建通过。GET Session/Turn usage 从同一事务游标读取已提交汇总，不生成保留快照；
重复 20 次读取与原快照一致，并保持 snapshot 数量不变。

真实 Agent Service/Go/PostgreSQL Team、手工 fenced Host、Agent Service 重启后，实际 Python 和编译
后的 DSH ServiceClient 均通过 capabilities、Turn 列表/快照、权威用量、产物元数据/
内容，DSH 还通过持久游标 SSE 续传。5 个 reported token、11 个公开事件；配置源为
fixture，无实际 daemon/外部模型，控制面执行前停机。该新增证据不覆盖 Java 客户端、
External、所有 UI 或真实供应商。Python 12 项入口/示例测试通过，DSH build 通过。

日志：/tmp/agentscope-execution-account-boundary-process-2.log、
/tmp/agentscope-portal-usage-build-tests-1.log、
/tmp/agentscope-service-client-portal-ui-tests-1.log、
/tmp/agentscope-service-client-portal-python-tests-3.log、
/tmp/agentscope-public-clients-team-process-3.log。
实际过程前两次新增验证分别发现缺少状态及空 status 筛选导致 400，均已修复后重跑
通过；失败用例没有删减。Go artifact SHA 字段只发布经检查的 SHA-256，其他 checksum
不伪装为 SHA-256；相关 Go 测试和四个二进制 build 通过。

## 2026-10-11 Agent Signal 与工作流结果汇聚

Workflow/Team 任务通过 MCP `run.signal` 或 Task CLI 提交明确的待办节点 UUID、requestId 与幂等键。Go Runtime 校验当前任务凭据、Attempt/fence、所属 Run 和根执行授权；Agent Service 按根 Turn 的原始调用者重新检查 invoke 权限，保存代理任务归属并持久化控制命令。Agent 不能批准人工确认。返回 queued/runtime_accepted 表示持久化受理，applied 仍以原生 Signal 事件为准。控制命令切换期间旧授权续租返回可重试的 425，防止旧激活先行打断原生工作流。

相同内容的重试保持同一命令回执；不同内容返回冲突。任务在受理后完成不撤销已持久化意图；原生应用前再次核对 Attempt/fence，发生替换时进入受控停止。Task CLI 请求具有超时并拒绝重定向。MCP 用 `json.Number` 保留大整数。

修复最后一个 Agent 任务完成时提前把整个 declared/subrun Run 标记成功并覆盖输出的问题；这些 Run 的终态与所有节点输出由工作流引擎汇聚，保留其他 Signal/Condition 节点结果。

- Java 14 项选定测试与 Agent Service 打包通过（`/tmp/agentscope-agent-signal-java-tests-5.log`）；Go invocation/httpapi/agentservice/orchestration/CLI 选定检查及四个制品构建通过（`/tmp/agentscope-agent-signal-go-tests-10.log`、`/tmp/agentscope-agent-signal-go-build-8.log`）；DSH 构建通过。
- 实际进程验证通过（`/tmp/agentscope-agent-signal-process-5.log`）：Go 真实 PostgreSQL、Agent Service 持久 H2，两组件重启，MCP 与 Task CLI 同一请求回执、不同内容冲突，19 条事件、SSE 续传、快照一致，最终输出包含所有工作流节点并精确保留 9007199254740993。配置准入使用隔离 HTTP fixture，Host 任务由测试手动执行 fence 协议，无真实模型或供应商。
- EN/ZH 文档静态检查通过：430 页面、584 重定向；部署源卫生检查通过。该证据不代表 P5 完成，完整委派、混合后端和故障矩阵仍继续实施。

## 2026-10-11 Session Webhook 的 Agent Service/Scheduler 分工

新增 Agent Service 公开订阅注册、keyset 列表、删除、失败/暂停订阅显式重试以及投递记录列表。订阅在 Session 锁内检查原始调用者的管理与 read 权限，默认每 Session 64 份有效订阅；Application key 使用已有 `webhooks:write`，用户沿用 interact 权限。注册从当前公开游标之后开始。签名密钥和原始身份加密持久化；仅注册及同键重试响应返回 signing_secret，列表/投递记录没有密钥。

Agent Service 每订阅维持一个待投递事件和顺序游标，提交公开事件时事务内合并唤醒 idle 订阅。数据库 due 索引、有限候选页和 256 条事件页避免全量扫描；每次 claim 带稳定 delivery ID、新 token/fence、60 秒有限租约。独立 Scheduler 通过 private API 认领与发送前权限复核，不读取 Agent Service 表。每实例默认 8 个槽位，空闲槽位不足时不认领。回调只带事件定位元数据，完整内容经用户的带权限事件 API 读取。

发送要求 HTTPS 443 精确域名名单，不跟随重定向，不转发用户/内部凭据；HMAC 使用独立时间戳、delivery ID 与 v1 签名头。网络请求有超时，响应正文直接关闭；2xx 回执才推进游标。失败最多 8 次，使用退避抖动；进程崩溃或 ACK 丢失后接管同一 delivery ID，接收端必须去重。删除及撤权阻止之后的发送，已经在途的 HTTP 请求可能到达。未成功的事件不会因失败推进游标。

- 新迁移 `V0003__webhooks.sql`、普通副本版本校验、embedded 幂等初始化、Compose/Helm operator 配置、Python/DSH 客户端及 EN/ZH 文档同步更新。
- 构建与四项 Java 核心测试通过（`/tmp/agentscope-portal-webhooks-java-tests-3.log`）：两项订阅/接管/撤权/失败重试行为、一项 Sender 签名/拒绝重定向，以及已有 Agent Signal 回归。Python 12 项选定测试与 DSH 构建通过。
- 完整进程通过（`/tmp/agentscope-portal-webhooks-process-1.log`）：实际迁移入口、五个 DML-only 账号、六类服务进程、真实控制面账号和权限、Gateway 注册、Managed Turn 两次本地 fake 模型请求/13 条事件；首次投递 503 后 Agent Service/Scheduler 重启，保持同一 delivery ID/secret，成功 204，验证签名、无凭据转发、事件权限读取、private 路由隔离、公开列表及删除后密钥清空。接收端是明确开启的 loopback HTTP fixture，无外部模型或真实 HTTPS 供应商。
- 文档验证与发布源卫生检查通过；Channel/Cron/outbound durable intents 和完整存储权限审计继续实施。

## 2026-10-11 独立 Java Service 客户端与三客户端回归

- 新增 `java-client` 模块，制品为 `io.agentcore.managed:managed-agents-client`，Java 17/Jackson，
  不引入 Spring、Harness 或 Service 服务端依赖。接入当前 Session/Turn、keyset 列表、
  capabilities、快照/资源分页、事件/SSE、控制命令及回执、生命周期、用量、文件、产物
  和 Session Webhook；budget/checkpoint/fork 等尚未完成的服务端 API 未伪装成可用能力。
- 保留原始 Application key 或用户 token，禁止两种身份同时传入；命令读取验证 Turn
  归属，幂等写操作要求有效 key。JSON/上传 16 MiB、错误/单 SSE 事件 1 MiB、缓冲内容
  100 MiB；Java 整个响应体及 SSE 连接均有总 deadline，消费者显式关闭与游标重连。
- DSH 补文件上传/列表/元数据/内容及产物元数据；Python 补上传响应限额。三客户端
  拒绝 HTTP 重定向，避免原始凭据随重定向转发。后续完整故障矩阵单独记录。
- 四项 Java 核心测试、jar/sources/javadoc 构建、dependency classpath、格式检查通过
  （`/tmp/agentscope-java-service-client-tests-1.log`）。DSH 构建及二项文件/身份行为测试
  通过；Python 九项选定公开入口测试通过（`/tmp/agentscope-service-client-python-tests-3.log`）。
- 真实 PostgreSQL、独立 Agent Service/Go Team 流程通过：Agent Service 重启后 Java/Python/DSH 读取
  相同 completed 快照、5 token 汇总、产物元数据/字节摘要与 SSE 游标重放，11 公开事件。
  日志 `/tmp/agentscope-java-service-client-team-process-1.log`。配置来源是 fixture，种子控制面
  执行前停止，使用手动 fenced 假 Host；没有真实 daemon/供应商模型调用。
- SDK 模块与发布 BOM 对齐，源码 package 收集 jar/sources/javadoc、flattened POM 和双语
  README，发布清单包含 Java revision。实际临时目录打包六文件通过；发布/Helm/source
  检查 37 项及 BOM 同步、源码 hygiene 通过，未执行 Maven 发布、提交或生产部署。
- 新 Webhook 集成后的 Agent Signal 回归再次通过：真实 PostgreSQL、Agent Service/Runtime 双重
  重启、完整工作流输出及 19 事件；`/tmp/agentscope-agent-signal-webhook-regression-process-1.log`。

Channel/Cron/Automation 的新准入仍未迁移；当前 Deployment 的资源 owner 可能是 Namespace，
缺少可恢复的运行用户。已询问无人值守触发应显式绑定 Application 或使用创建者用户身份，
收到决定后实施来源绑定、稳定触发 ID 和持久化受理，不能把 owner 字符串当作人类授权。

## 2026-10-11 编排分页修复及终态 Issue 有界收尾

- 真实 PostgreSQL 复现调度器第二页多传 SQL 参数（expected 3 arguments, got 4），修复
  keyset 查询参数；新增真实存储回归，确认前一页 Run 结束不造成后续行丢失。
  修复前日志 `/tmp/agentscope-runtime-keyset-regression-before-fix-2.log`，修复后通过
  `/tmp/agentscope-runtime-keyset-regression-after-fix.log`；原 Worker 有界扫描回归也通过。
- 失败根 Run 进入终态时保留 `terminal_cleanup_pending`，Runtime 的有界定时扫描同时
  发现未完成收尾的终态 Run。完成后 CAS 清除标志；已完成的历史 Run 不持续参与扫描。
  `0126_terminal_issue_cleanup` 新增部分索引和子 Issue keyset 索引。
- 原失败收尾的无界 BFS 改为既有 KV 中的版本化 DFS 游标，每轮最多 16 个遍历/状态操作，
  只保存 ancestor frame 与 UUID 游标，不保存整棵 Issue 树。状态副作用先提交、游标后 CAS，
  崩溃重放已阻塞状态不重复生效；发现恢复 Run 后停止历史收尾。
- 子查询按 Parent/UUID 稳定排序，状态修改的 updated_at 不影响续读；按失败时刻过滤，
  不把失败之后创建的 Issue 当作旧工作阻塞。仍保留先写 Team 汇总再推进根状态的顺序。
- 两项新增核心行为在内存和真实 PostgreSQL 均通过：50 子 Issue（含孙节点）首批受限，
  每轮新建协调器后继续共享进度并收敛；较新恢复不被旧失败阻塞。终态义务清除后不重跑。
  加上汇总/Worker/分页选定回归共五个测试入口通过，`/tmp/agentscope-failed-issue-cleanup-go-tests-2.log`。
- 编排模块 36 个测试入口在真实 PostgreSQL 配置下整体通过，
  `/tmp/agentscope-failed-issue-cleanup-orchestration-tests-4.log`；Runtime/Control Plane 两个制品
  构建通过。首轮扩大回归的 Workflow fixture 需要连接 URL 带查询串，改用显式
  `?sslmode=disable` 后通过，没有修改或削弱断言。

主编排循环、materialize、ListNodes/ListEdges、Team 完成检查及完整输出仍需继续收敛容量。
本次终态失败收尾的分批机制不等于全部编排热路径都已完成容量改造；跨副本崩溃点、
并发恢复及迁移/业务账号矩阵单独后补。


## 2026-10-11 编排图容量及分批建图

- 首期使用普通 PostgreSQL Run 行锁和配套 run_id 索引，静态节点、动态 AgentTask、
  Team 恢复协调节点及边写入都在同一事务内检查容量。两个连接池代表的副本并发抢占
  最后一个名额仅一项成功；动态委派超限不残留孤儿 Issue。没有引入 region/shard。
- 定义限制为 4 MiB / 4,096 节点 / 8,192 边；每 Run 为 8,192 节点 / 16,384 边。
  完整节点、边视图各最多读取 limit+1 条并校验 16 MiB 聚合载荷，超限返回明确错误，
  不截断 DAG 或输出。Engine 将运行中的容量失败持久化为取消义务，有限排空后失败。
- 评论超限路由记录 blocked/run_graph_capacity；queued coalescing 和复用节点不占新容量。
  PostgreSQL savepoint 回滚该路由的 continuation 副作用，保留评论或完成报告事务。
- 建图每轮最多创建 16 节点 / 64 边，身份和已提交记录作为恢复进度；完整图提交前
  保持 planned。主 Engine 共享每轮 32 节点处理预算，依赖边按目标索引，避免每个
  pending 节点重扫全边集合。节点处理预算不等于 SQL 写入数：一种处理可有数次 CAS。
- 等待节点为共享 KV UUID 游标，半份预算优先已答复审批/信号，半份预算循环所有等待
  节点，成功副作用后提交游标。65 个长等候节点中的 timer 经新 Engine 继续轮转后完成。
- 新增六个行为测试入口全部通过，包含内存/真实 PostgreSQL 分批 40 节点及 114 边、
  完整 40 项输出、等待公平性、容量失败排空、定义校验、双连接池到限及开发存储路由。
  `/tmp/agentscope-graph-capacity-behavior-tests-1.log`、
  `/tmp/agentscope-graph-capacity-replica-tests-1.log`。
- 选定 Workflow/Team/失败收尾/分页回归 11 个入口通过，
  `/tmp/agentscope-graph-capacity-regression-tests-1.log`；Runtime 和 Control Plane 构建通过。
- 上一阶段实际 Workflow 拒绝审批进程验证通过（14 事件，Agent Service/Runtime 均重启），
  `/tmp/agentscope-failed-issue-cleanup-workflow-deny-process-1.log`。

首期有界索引计数是简单可运行的容量实现；大图写入吞吐的计数优化须按压测数据决定。
Team 完成检查的全部子 Issue、Task 重试历史、最终结果聚合写入及跨图总量仍需继续有界化，
普通副本吞吐认证、运行来源过滤和完整部署切流也仍未完成。


## 2026-10-11 Team 完成屏障的完整有限视图

- 修复 coordinator 只检查前 500 Task / 子 Issue 的遗漏：使用 UUID keyset 有限页读取
  完整集合，Task 视图最多 32,768 项、子 Issue 最多 8,192 项，集合各限制 16 MiB。
  超限明确返回 resource limit，不将不完整视图用于成功判定。这是读取视图的边界，
  不宣称所有 Task/Comment 创建路径已具有总量预留。
- 运行历史按 created_at/id 排序；普通排队 List API 的 priority 语义保留。曾在回归中
  发现把优先级放入重试历史会误把旧失败作为最新任务，修正后瞬时失败的 Team 自动
  重试通过。等待节点也检查完整有限重试历史，容量错误进入既有持久化失败排空。
- Team 汇总任务/子 Issue、协作上下文及完成后恢复根状态复用有限视图；声明式 Team
  的任务子树按 parent 邻接表各访问一次，避免深委派反复扫描整批 Task。
- 新回归在内存与真实 PostgreSQL 中确认第 521 个 active worker 和第 521 个 active
  child 都阻止完成，不被第一页遗漏。四个模块扩大回归通过（编排 41 个测试入口），
  `/tmp/agentscope-team-capacity-go-tests-2.log`；此前 HTTP 全模块回归通过，
  `/tmp/agentscope-graph-team-capacity-http-tests-3.log`（真实 PostgreSQL 原生分支启用）。
- 首轮扩大 HTTP 回归发现宽图 fixture 固定两轮的旧假设，以及缺少初始化存储/测试
  vault key 的两项 fixture；已按真实依赖和分批机制修复，最终状态断言保留。
  `/tmp/agentscope-graph-capacity-http-regression-2.log` 全部通过。
- 分批图版本的实际嵌套 Workflow 进程闭环通过（27 事件，Agent Service/Runtime 均重启），
  `/tmp/agentscope-graph-capacity-workflow-process-1.log`。

仍待实施：每 Task 的 Input/Comment 历史及汇总正文
聚合、跨子图总容量、完整运行来源过滤与后续 P0–P9 其余阶段。keyset 避免状态/优先级
变化造成 offset 位移；它不是跨多条查询的数据库快照或最终完成的事务屏障。


- 补充 `0127_graph_consistency_cursor`：原边表缺少 Run 前缀索引，增加
  `(run_id,ordinal,id)`；完整节点、Task 的 Run/Node/Issue UUID 续读也增加索引。
  具体已指定的 Run/Node/Issue/AfterID 使用静态 SQL 条件，避免可选 OR 扫描其他运行。
- 最终 Team 子树算法及 HTTP/Workflow 核心回归通过，
  `/tmp/agentscope-team-capacity-final-tests-3.log`；完成阶段的全部空 leader 退场复核通过，
  `/tmp/agentscope-team-capacity-completion-tests-4.log`。源码 hygiene 与双语文档检查通过。


## 2026-10-11 Runtime 扫描归属和共享有限游标

- Run、Task、Attempt 和 Approval 的运行扫描增加 Agent Service 归属过滤。Run 归属从
  Invocation 及 Issue parent 链派生，独立 child Root Run、嵌套 Run 与终态收尾
  不会因缺少 parent_run_id 被排除；调用方传入的标记被忽略。Engine/Task Plane
  仍执行原有 live contract/admission 检查，标记只决定扫描范围。
- Runtime outbox 在任务/审批/Attempt 事务中保存派生归属，使用部分索引和 scoped
  SKIP LOCKED 认领。两个消费身份交接同一事件后，旧身份不能 ACK；控制审计、
  非法 aggregate UUID 和旧队列不会进入 Runtime 批次。Host 的物理 Attempt
  认领也先过滤归属，不再先认领旧执行再取消，旧状态/版本/Host/lease 保持原样。
- Runtime 超时与输入重试过滤相同来源，不再兼做旧 Conversation 和通用 Issue
  逾期扫描。单独 Run Worker 已负责有限 Run 轮转，移除重复的 sweeper Run 推进。
  队列超时、每种 Attempt 租约状态、取消等待及工具审批使用不同共享 KV UUID 游标；
  每类每轮至多 500 条，副作用后 CAS，页尾回绕。工具审批不再读完所有 pending。
- 在固定 tenant/namespace 的 Host 认领中去掉候选间相同的活跃数量排序，避免对每个
  候选重复计数；跨范围旧执行角色仍保留原有排序。首期使用普通 PostgreSQL 索引、
  行锁与 CAS，没有 region/shard。
- 内存/真实 PostgreSQL 的来源、子任务、容量批次、终态收尾、outbox 租约交接、
  Host 认领、Task/Approval/Attempt UUID 续读通过；只读 schema 校验、新环境并发
  初始化及 0128–0131 种子升级/下行后重新升级通过。
  `/tmp/agentscope-portal-runtime-migration-scope-tests-1.log`。
- 两项扫描器重建回归通过：有限审批页继续到后面的过期审批；两条持续有效的租约
  不会遮住第三条过期 Attempt，旧 Attempt 保持原样。
  `/tmp/agentscope-portal-runtime-rotation-tests-1.log`。
- Runtime 与 Control Plane 制品构建通过。真实 PostgreSQL 的 Agent Signal Workflow
  通过（19 事件，Agent Service/Runtime 均重启、Task CLI 回执重放、SSE 和快照），
  `/tmp/agentscope-portal-runtime-scope-workflow-process-1.log`；配置与 Host 为 fixture，
  不是实际供应商执行或完整 RBAC 联调。前一版同场景也通过，
  `/tmp/agentscope-team-capacity-workflow-agent-process-3.log`。

扩大回归曾发现测试把同时间戳根/子 Run 的顺序视为确定先后；已改按存储返回的
created_at/UUID 顺序验证，并分别确认终态收尾义务。第一版 fixture 缺少 JSON
runtime_binding 也已补齐，没有放宽生产约束。

仍待实施：输入/评论及汇总正文总容量、跨图总容量、
完整控制面运行写入归属与角色逐表授权、完整输入重试递送与旧调用路径归属，以及 P0–P9
其余欠项。现有 scope 谓词是简单首次实现；大规模查询计划、延迟和吞吐需后续压测。


- 这一阶段扩大 Go 回归中 Task Plane、Collaboration、Memory 与 HTTP 模块整体通过，
  HTTP 的真实 PostgreSQL 原生分支启用；首轮编排模块仅上述顺序 fixture 失败。
  修正后编排全部 42 个测试入口通过，
  `/tmp/agentscope-portal-runtime-scope-orchestration-regression-2.log`，其余模块结果见
  `/tmp/agentscope-portal-runtime-scope-go-regression-1.log`。
- 最后补充仅在状态副作用无非预期存储错误时推进扫描游标，竞争 CAS 不作为异常；
  三项相关回归通过，`/tmp/agentscope-portal-runtime-sweep-cursor-final-tests-2.log`。
  两个 Go 制品已按该版本重建。源码 hygiene、完整 diff whitespace 与 430 文档页面 /
  584 重定向的严格检查通过。


- 0128–0131 版本的实际三层嵌套 Workflow 通过：27 事件、Agent Service/Runtime 双重重启、
  子图审批、命令回执、SSE 和完整输出，
  `/tmp/agentscope-portal-runtime-scope-nested-process-2.log`。
- 进一步修复输入重试按 Task 放大更新的缺陷。PostgreSQL 事务保存真正认领的输入
  ID，只更新该集合；内存按输入数量计数。`0132_retryable_input_scan` 提供 due 部分
  索引，每轮最多 500 输入。真实 PostgreSQL 与内存确认一个 Task 的 17 项输入
  每次只重试 3 项、六轮完成、旧 Task 的输入和版本保持原样；
  `/tmp/agentscope-portal-runtime-input-retry-tests-2.log`。两个 Go 制品按新版本构建通过。
  该存储批次验证不等于全部后端的输入重试投递/唤醒闭环已经完成。


- 0132 版本的六组件实际部署进程通过：显式 migration job、五个 DML-only 账号、
  六类服务、Gateway 登录/private 路由隔离、Go readiness、Host 机器凭据注册及续期；
  真实控制面用户创建资源后 Managed Turn 幂等、终态、13 公开事件及 usage 通过，
  使用两次本地 fake 模型调用。`/tmp/agentscope-portal-runtime-scope-deployment-process-3.log`。
  fixture 已清理自己的数据库、角色和进程；测试 PostgreSQL 容器保留。此证据验证
  业务账号启动及 Managed 链路，没有覆盖全部 Go owned Task 的逐表权限或实际镜像。


## 2026-10-11 Team 完成与并发工作提交

- 完成屏障抽取为共用 scope 校验，preflight 和存储事务使用相同规则；Adaptive
  检查整个 Run，声明式 Team 只检查自己的 Task parent 子树。数据库提交使用有限
  轻量列集合和 EXISTS 输入检查，不读取全部输入/result 正文；超限显式失败。
- 新增 atomic coordinator finalization：Run 行锁下复核完整屏障，节点成功与
  node.succeeded 事件同事务提交并唤醒事件等待者。NOWAIT 避免与持有 Task/Issue/
  Node 锁的写入相互长等，冲突保留安全的 waiting node。
- 委派、复用 Task node、coalesced input、图节点/边新增及无 assignee 子 Issue
  使用同一 Run guard，取得锁后重新检查 coordinator 是否已关闭。子 Issue
  重新打开、AgentTask source 变更也参加该边界。多个相关 Run 按 UUID 顺序锁定；
  声明式 Team 的来源树不被同 Issue 已结束的 Workflow sibling 阻断。
- 新输入推进 queued Task 版本；完成前只取消空重复 leader，旧快照不能取消刚收到
  输入的任务。Memory metadata update 同 PostgreSQL 保留原 status/resolvedAt，
  生命周期变更经 TransitionIssue 进入上述边界。
- 明确完成意图在 Task 行锁下持久化；尚未接受时可以修订。物理协调完成核对最新
  输出、接受意图并结束 Task 同事务；普通 Task 完成不接受意图。恢复只重试已接受
  的意图并复核全部义务。已接受输出不可改写，JSON 规范化保留大整数；摘要后于
  节点屏障发布，重放不重复事件。原物理停止与 Agent Service 终态屏障继续保留。
- 六个核心入口覆盖内存/真实 PostgreSQL 的明确意图重启、普通完成不自动结束、
  pending 决定修订和旧结果拒绝、晚委派/建图拒绝、输入 CAS、声明式 sibling/
  子 Issue 重开/source 变更；两 PostgreSQL 连接池的 12 次并发竞争和已持锁的
  未提交 child 事务通过。`/tmp/agentscope-portal-team-conclusion-intent-tests-5.log`。
- 第一版未提交写入 fixture 使用了物理 Task 完成前的旧 node version，错误地只
  测到了 CAS 冲突；已读取最新节点，保留具体 active child 与事件原子性断言。

仍待实施：每 Task 输入/评论和摘要正文容量、跨子图总容量、独立 Team child 运行
发现/恢复/取消/输出聚合、输入重试完整递送，以及角色逐表写权限和 P0–P9 其余欠项。
以上完成边界覆盖当前运行写入路径；未经过运行意图的行政/旧 API 写入仍需按原规划
收敛，不能把独立子 Run 全链路或完整运行写权限宣称为完成。


- 最终六模块 Go 扩大回归通过：547 个通过测试条目，25 个按现有环境条件跳过，
  `/tmp/agentscope-portal-team-barrier-go-regression-2.log`；没有将跳过记为覆盖。
  两个 Go 制品已重建。源码 hygiene、完整 diff whitespace 和 430 页面 /
  584 重定向的严格文档检查通过。
- 0133 及 accepted-intent 版本的实际 Team Job 进程链路通过：显式 migration、
  真实 PostgreSQL、公开 Session 创建、MCP coordinator 完成、原生产物、Agent Service
  在物理停止前重启、旧 stop fence 拒绝、SSE 恢复，以及 Python/DSH/Java SDK
  读取（12 公开事件、5 reported tokens）。`/tmp/agentscope-portal-team-atomic-process-1.log`。
  使用 frozen 配置与带凭据的 manual fake Host，没有调用实际模型/CLI provider；
  没有覆盖逐个 intent commit 崩溃点或 Runtime 重启。已清理自己的进程与 schema。


## 2026-10-11 评论历史与团队摘要容量

- 全历史读改为 created_at/UUID keyset，每页 128，最多 32,768 条和 16 MiB
  序列化载荷，超限显式 resource error，不能返回被截断的完整视图。普通评论页也
  限制 16 MiB，在保留页面时累计原文大小，在返回前包含关联数据检查。
- PostgreSQL 评论列表先读取有限页并释放 rows 连接，再查 mentions/routes，修复
  单连接或多个并发读占满小池时的等待；未省略默认 API 的关联字段。
- 摘要按 Issue 索引一次 Run Task 集合，直接查询相关 Task 的最新、未删除 Result，
  跳过无关来源、讨论和完整旧评论历史。新增 0134_comment_delivery_scan 部分索引，
  来源 ID 参数有 32,768 上限。摘要内部不加载该交付的 mention/route sidecars。
- 内联摘要最多 16 MiB。容量不足时，发布带明示的 system/status 详情引用，链接
  实际 Console /work/executions 路由，保留原始 Task/Comment/Artifact；稳定摘要 ID
  不重复。不会把已经累积的部分正文作为完整交付，也不因布局容量阻止终态收敛。
- 已有核心回归通过：真实 PostgreSQL MaxOpenConns=1 的完整 mentions/routes 和
  精确交付筛选、521 个同时间戳评论的完整 keyset 历史，以及 16 MiB 邻界摘要的
  明确引用和重放去重。`/tmp/agentscope-portal-comment-capacity-tests-5.log`。

仍待实施：每 Task 输入的读写预算、comment mention/route 的聚合及写入预算、
跨图/整体输出容量、细化输出使用产物的闭环，以及上述独立 child Run 和其余阶段。
全历史上限与摘要引用不等于运行图、Task、文件和所有公开快照均已完成统一容量控制。


- 评论/摘要版本的五模块 Go 扩大回归通过：545 个通过条目、25 个环境条件跳过，
  `/tmp/agentscope-portal-comment-capacity-go-regression-1.log`。两个 Go 制品已重建，
  hygiene、完整 diff whitespace 与 430 页面/584 重定向严格检查通过。
  这一版本未重复实际 Team 进程 fixture；上一节进程证明对应 0133 及 accepted-intent。


## 2026-10-11 Task 输入预算与重试关联

- 单 Task 输入最多 8,192 条，完整输入视图最多 16 MiB；写入为后续 delivered、acknowledged、processed、错误及响应引用预留元数据空间。新输入的序列通过 Task/route 锁串行分配，0135_task_input_sequence 索引用于序列末尾及有限有序读取。满额时既有 comment/version 投递仍幂等，新增输入生成 task_input_capacity 阻塞路由并保留评论，不创建额外 Run/Task。
- 输入 ID 数组和完成的 processed/deferred 总量受相同数量预算保护。列表包含输入时还检查整页 16 MiB；超限返回明确错误，不把部分历史当完整视图。PostgreSQL claim-with-attempt、ack、delivery-failure 在事务内读完并验证响应后才提交，容量拒绝不遗留已认领 Task/Attempt。
- LastError 是有界诊断预览：最多 1,024 JSON 字节，缩短时明示 diagnostic preview、originalBytes 和 SHA-256；不把该字段视为完整日志或交付正文。不同生命周期时间戳及 responseCommentId 的最大尺寸在初次接收时预留。重放和 Task 重试重置投递次数、错误、时间戳，序列按原顺序重新连续编号。
- 取消仅读取 Task 元数据与有限物理 Attempt，不读取输入历史，因此遗留输入数量或正文超限仍能取消。内存完成先复制输入状态，避免后续 pending-input 冲突时部分确认原数组。
- PostgreSQL Task 重试沿用 failed Task 的活动 Run；两种实现均保存 ParentTaskID/DelegatedFromTaskID，声明式 Team 的完成屏障能跟踪该重试。在旧 Run 已终态或 review-feedback 独立运行的场景继续沿用已有 continuation 规则。
- 关键真实 PostgreSQL/内存回归通过：两连接池 8 请求竞争最后一格，恰好 1 合并/7 阻塞；满额既有投递幂等；8,193 条旧历史拒绝 claim 并无残留 Attempt；16 MiB 错误旧历史仍能取消；Team 活动重试阻止完成、成功重试解除屏障；转义/多字节错误预览和 1/3 输入选择重放。32 个通过条目，日志 /tmp/agentscope-portal-task-input-capacity-tests-4.log。
- 前三版 fixture 分别遗漏 Attempt 的非空 runtime_binding、假定底层 Task cancel 同时结束图节点、以及未保存 StartAgentTask 前的 input ID。已按真实 claim/start/complete 路径修正，保留物理事务回滚和完成屏障断言；失败日志不算验证证据。

仍待实施：评论 mentions/routes 的读写预算、跨图总输出/产物引用、独立 Team child Run 完整链路、实际输入重试投递/唤醒及其余 P0–P9 欠项。当前是存储/编排机制验证，尚未按 0135 版本重复实际六组件或 Team Job 进程。


- Task 输入阶段扩大六模块回归通过：565 个通过条目、25 个环境条件跳过，/tmp/agentscope-portal-task-input-capacity-go-regression-1.log。make build 的四个 Go 制品均通过，/tmp/agentscope-portal-task-input-capacity-go-build-1.log；源码 hygiene、diff whitespace 和 430 页面/584 重定向检查通过。

## 2026-10-11 本轮重心调整

用户明确 Managed Agents 为重点，Team/Workflow 等本轮先跑通基本流程。复杂 Team/Workflow 和混合后端欠项移动到后续实施清单，不再阻断本轮 Managed 交付。继续完成已开始的共享评论容量改动，随后按最新计划推进 Managed 命令补偿、恢复/配置权限、工具/文件、客户端和部署边界。没有将后续复杂功能标记为已经实现。


## 2026-10-11 评论关联数据容量收尾

- 新评论/结果完成每次最多 128 mentions、128 路由目标；服务层在逐条解析 mention 前检查数量。单评论正文及基础字段 JSON 上限为 12 MiB 减 4 KiB 保留空间，关联数据最多 4,096 项（mentions/routes 合计）和 4 MiB。新写入预留路由 Task ID、生成 reason、版本与时间戳；过大及 JSON 转义放大明确拒绝，不留部分评论或新工作。
- 评论校正计算所有已保留 routes 加本次投递目标的完整预算后才改正文和版本、产生输入。生产 correction 查询最多 129 个目标，超出 128 显式失败；关联读 LIMIT 4,097 且累计字节预算。0136 索引覆盖 mentions/routes 顺序读取和 comment/version→Task 投递查询。达到累计上限时整个校正拒绝，原文、版本和 Task 输入不变。
- PostgreSQL Update 返回事务内验证后的完整关联视图，不在提交后才发现响应容量错误。普通 Get/List 不返回部分关系；Memory 只为选中页面加载关联。摘要写入触及评论正文预算时同样发布既有、明确标注的详情引用，不阻止 Run/Issue 收敛。
- 关键 34 个通过条目，/tmp/agentscope-portal-comment-relations-tests-2.log：memory/PG fanout、转义放大拒绝且无残留；PG 满额 correction 回滚版本/输入；mixed mentions/routes 超额读明确拒绝；16 MiB builder 和约 12 MiB publication 两层容量都使用详情引用。扩大六模块回归通过 563 条目、25 环境跳过，/tmp/agentscope-portal-comment-relations-go-regression-1.log。

评论订阅者通知的大规模 fanout、附加 outbox 载荷及跨图聚合仍需后续实施/验证；没有将上述评论预算声称为整套容量认证。本轮已按用户方向转向 Managed Agents 的完整能力，复杂 Team/Workflow 项目已转后续实施。

## 2026-10-11 Managed 输入拒绝与回执补偿

- Agent Service 对 Managed inject/steer 的明确 401/403/404/409 权限或生命周期拒绝，保持原始 input ID、actor、scope 和 payload，发送过期的准入期限。503 权限源不可用仍重试，不伪造终态。其他交互及后端保持各自准入规则。
- 数据面允许过期 inject 进入内部持久化 interaction；先修复既有原生回执，再为此前未接收的输入持久化拒绝。过期拒绝无需读取文件或重新校验已失效的配置；原始 payload digest 与作用域仍在 interaction 入库时核对。已接收的输入不会被后来的拒绝撤销，已拒绝的输入不会因权限恢复而执行。
- Core Inbox 新增 acceptRejected，将 accepted/rejected 事件一次 journal commit，消除两次提交之间的可执行窗口。丢失 commit ACK 后重建日志仍看到拒绝；重复不新增事件。
- Java 11 项关键测试及相关模块 package/Spotless 通过，/tmp/agentscope-managed-input-convergence-tests-2.log。第一轮失败来自 Mockito 用 when 重新设定已有抛错 stub，改用 doReturn 后通过；格式命令的 glob 误用已改为正则，未计为成功证据。
- 实际 Agent Service/数据面/原生 Harness 进程使用 file H2 和本地 fake 配置、模型 HTTP：5 次模型调用、35 条公开事件；输入在数据面离线时排队，权限源不可用及 Agent Service 重启后撤权，数据面恢复后拒绝且不调用模型。拒绝事件一次、回执重启保持；原 inject/steer、SSE/快照重放同时通过。日志 /tmp/agentscope-managed-input-convergence-process-1.log。
- 430 页面/584 重定向及源码 hygiene 通过。双副本数据库竞争和全部崩溃组合留在后补清单；本步骤未使用真实供应商或证明生产容量。

## 2026-10-11 Managed 共享资源配置刷新

- 创建时冻结 Agent 和 Workspace 定义；Vault 凭据、Memory 挂载与已绑定 Environment 配置通过共享权限记录刷新，在下一次物理执行激活时应用。正在执行的调用不热替换配置，执行续租不携带配置正文。
- Agent Service V0004 把资源配置放入独立加密列；普通授权、列表和 SSE 只查询权限列。配置最多 1 MiB，与策略版本同事务提交；多个同策略 Session 复用一份刷新。权限源不可用或返回缺失的 Managed 配置时不延长有效期。
- 数据面按 scope、revision 和摘要更新三类允许字段，保留原始 bootstrap 摘要、Agent、Workspace 和 Session 身份。旧版本不覆盖新版本，同版本不同内容拒绝；授权激活事务失败会回滚配置。纯模型 Session 允许空 Environment，资源刷新不能更换已有绑定。
- Java 47 项核心测试、相关模块 package/Spotless、Go 协议及 Agent Service 适配测试与四个 Go 制品构建通过。日志 /tmp/agentscope-managed-resource-overlay-tests-4.log 和 /tmp/agentscope-managed-resource-overlay-go-build-1.log。
- 两组实际进程通过：file H2 单副本的权限故障、撤权、输入拒绝与重启（5 次本地 fake 模型调用、35 条公开事件）；真实 PostgreSQL 双 Agent Service/双数据面、独立迁移和 DML 账号、Turn 与重启（4 次 fake 模型调用、24 条公开事件）。日志 /tmp/agentscope-managed-resource-overlay-process-2.log 和 /tmp/agentscope-managed-resource-overlay-postgres-process-2.log。fixture 已清理自己的进程、schema 和角色，保留测试 PostgreSQL 容器。
- 首轮进程发现合法的空 Environment 被协议拒绝，已修复并增加绑定校验回归；首轮失败不计入成功证据。上述进程证明初始覆盖与授权刷新，凭据轮换由实际 H2 事务测试证明；尚未覆盖供应商凭据在线轮换或现有 Sandbox 的 provider/isolation 变更。

Session 绑定 PATCH、Sandbox 绑定变更规则、全部资源轮换进程组合及其余 P0–P9 欠项继续实施；本步骤不代表 P6/P7 完成或生产容量认证。

## 2026-10-11 Agent Service 标准事件导出

- Session/Turn GET export 统一读取 Agent Service 标准公开事件；不读取特权 trace。固定 through 水位，以 after 续读，每窗口最多 1,000 条，先读取有限序号再每批读取 16 条正文并复核 read 权限。导出并发独立于 SSE（默认 16），窗口总期限 60 秒，断开/取消释放槽位。
- 响应声明 through、next cursor、has_more 和记录数；Python/DSH 自动逐窗口迭代，Java 提供可关闭窗口。三个客户端按行读取并校验 schema、Session 归属、固定水位和数量；半行或缺失记录明确失败，只能从最后完整应用游标恢复。
- Java 11 项、Python 1 项（另 9 项未选择）、DSH 3 项通过，相关 Java package/Spotless 和 DSH 构建通过。实际 H2 测试含 270 条正文分批、固定水位后的新增排除、Turn 过滤、未授权 404、批间删除停止，以及取消释放唯一槽位。首轮测试错误预期 403，现按既有资源隐藏规则校正为 404；没有削弱拒绝断言。
- 实际 file H2 单副本和真实 PostgreSQL 双 Agent Service/双数据面均通过：公开导出两窗口跨 Agent Service 重启，双副本版本由另一 Agent Service 继续；Turn 导出与公开分页一致，内部 token 无法访问公开导出。分别 5/4 次本地 fake 模型调用、33/24 条公开事件。日志 /tmp/agentscope-public-export-process-1.log 和 /tmp/agentscope-public-export-postgres-process-1.log。
- 源码 hygiene、完整 diff whitespace、430 页面/584 重定向检查通过。未执行完整压测或真实模型；进程验证使用 HTTP 读取，SDK 的新增导出由本地 HTTP fixture/响应 fixture 证明。

预算、checkpoint/fork/trace 与绑定 PATCH 继续实施；导出完成不代表全部 Managed 扩展或 P8 完成。


## 2026-10-11 Managed Session 预算闭环

- Agent Service GET/PUT /budget 已接通；PUT 202 持久化根 Session 设置和单调版本，If-Match
  防止并发覆盖，旧版本 412，相同内容不重复递增。GET 区分期望 settings 与数据面
  native_limits/configuration_revision。每次模型调用前执行授权同步设置，旧 grant
  不回退配置。Managed 子 Agent 共用根预算，非 Managed 后端明确返回不支持。
- 新数据面逐调用收据和固定大小汇总取代 Agent Service-owned Session 的旧单大对象账本。
  跨副本准入与结算使用共享配置锁，重复结算不重复计费，首次最终结算不被后来
  价格覆盖。未知用量和缺少计价在对应约束下阻止后续调用。并发 token/费用仍按
  已报告用量判断，没有固定超额上界；不会自动重新调用未知的模型副作用。
- 后台按索引认领到期调用，持久化 scanSequence、claim token 和短租约，每次32调用、
  每调用64原生事件。读摘要不扫描日志或后代。预算拒绝写可选原生 budget/exceeded
  事件，统一公开事实链路提供 budget.exceeded；恢复 Agent 状态不依赖该 Service 事件。
- Agent Service V0005 与 Data V0002 迁移、Java/Python/DSH 预算入口已完成。27 项相关 Java
  测试通过：/tmp/agentscope-managed-budget-api-tests-2.log；随后原生事件四项通过：
  /tmp/agentscope-managed-budget-native-fact-tests-2.log。Python 两项、DSH四项及 build、
  Go runtimeprotocol/agentservice 和四个制品 build 通过。当前源码仍未提交/部署。
- 实际 file H2 单副本及 PostgreSQL 两 Agent Service/两数据面流程均通过，分别见
  /tmp/agentscope-managed-budget-process-4.log 与
  /tmp/agentscope-managed-budget-postgres-process-2.log：6次 fake 模型调用、41公开事件，
  耗尽后零供应商调用、修改设置、Agent Service/数据面重启、显式恢复同 Turn、汇总去重与
  重放；PG 使用独立迁移和无 DDL 的各侧账号。前几版 fixture 缺 interact、保留
  dispatch=false，以及必需未知事件校验失败均已修复，不计为成功验证。
- 源码 hygiene、diff whitespace 与430页面/584重定向文档检查通过。预算完整压力、
  故障与浏览器矩阵仍列后补；checkpoint/fork、绑定 PATCH 和其余 P8/P9 继续实施。


## 2026-10-11 特权原生 trace 查询

- 新 Agent Service GET /api/v1/agent-sessions/{session}/trace 转发到 private Data，默认关闭。
  人类 JWT 要求 ADMIN/SESSION_TRACE 角色，同时复核当前 Session read 与 trace 策略；
  API key 拒绝，内部 transport 身份不代替用户。控制面按当前账号角色发布 trace scope，
  从真实账号撤销 admin 后重新投影立即移除该 scope，旧 JWT 无法重新取得权限。
- 子 Session 使用根策略与自己的原生日志，private Data 核对内部 role 和冻结 owner，
  openChild 继续核对已登记关系。固定 through/as_of_seq、after/next_seq 支持重启续读；
  每页最多16事件、4MiB，一次读取1原生事件，不扫描全部日志；过大单事件返回413。
  两侧各8读槽位与 AgentService15秒超时。Data 的阻塞读取结束前不因HTTP取消释放槽位。
- Trace 只读，Cache-Control no-store，原生敏感数据不进入公开事件/快照/export。
  不恢复旧 trace reconcile 旁路；工具修复继续使用已接通的显式恢复命令。
  Java/Python/DSH 客户端、Compose、Helm traceEnabled=false 和双语说明已接通。
- Java11项相关检查分别在 /tmp/agentscope-managed-trace-java-tests-2.log（Client6、
  AgentServiceTrace2、AgentServiceBudget2）和 /tmp/agentscope-managed-trace-java-tests-3.log（Data1）
  通过，相关制品 package/Spotless 通过。前两次错误是新版HttpHeaders的方法和测试
  import 包位置，修复后不再计入通过证据。DSH build 与Python编译通过；Go限定回归
  及四个制品build通过。真实PG账号角色升降级检查见
  /tmp/agentscope-managed-trace-account-postgres-tests-1.log。
- 实际 H2 Agent Service/Data/Harness 进程通过：/tmp/agentscope-managed-trace-process-1.log，
  4次模型调用、24公开事件，普通用户403、key403、内部token401、特权固定前缀、
  未来序号409、AgentService重启后续读；公开SSE/export/用量保持正常。未重复全量trace矩阵。

当前代码仍在 /Users/ken/agentcore/agentcore-managed-agents 的 codex/automatic-acr-release，
未提交、未部署。checkpoint/fork、绑定PATCH、Channel/Cron和交付排空等继续实施。

## 2026-10-11 Managed Checkpoint 元数据读取

- Agent Service/private Data 的 checkpoint 列表已接通，普通 Session read 权限及冻结 owner 校验。
  cp1 句柄携带 Session 范围与日志位置，cpl1 分页固定原生水位；每次最多扫描128事件，
  单次读取1事件，元数据256KiB、reason1024字符，两侧各8槽位及AgentService15秒期限。
  空页面可有has_more=true，必须继续推进返回游标。原始stateJson不进入响应。
- Java/Python/DSH分页参数已接通；双语文档说明读写迁移边界。DP关键测试1项和
  package/Spotless通过：/tmp/agentscope-managed-checkpoint-reads-java-tests-2.log；
  Client6项、AgentServiceTrace2项及相关打包在前一日志通过，前一构建的DP accessor错误
  已修复。DSH构建通过，/tmp/agentscope-managed-checkpoint-reads-dsh-build-1.log。
- 实际H2 Agent Service/Data/Harness进程通过，/tmp/agentscope-managed-checkpoint-reads-process-1.log：
  4次模型调用、24公开事件、固定checkpoint水位和AgentService重启续页、无恢复状态泄露；
  同时保持特权trace、公开export/SSE和用量读取。未重复完整PG checkpoint矩阵。
- 预算not_dispatched明确贡献零费用且不依赖报价；4项回归、package/Spotless通过，
  /tmp/agentscope-managed-budget-not-dispatched-tests-1.log。trace的带实际参数Helm render
  及环境变量分配检查通过，/tmp/agentscope-managed-trace-helm-template-1.yaml。

Checkpoint恢复/fork写接口、绑定PATCH和其余计划欠项继续实施。

## 2026-10-12 Managed Checkpoint 恢复与 Fork

- public restore/fork 经AgentService受理持久化202命令；原始actor在相关根Session均需interact，
  按固定顺序加锁，拒绝非空/未结清Turn与输入。state=checkpointing原子冻结新准入，
  保留只读查询、列表和权限刷新；终态回执后解除冻结并追加公开审计。
- private Data 持久化固定日志/Inbox水位、checkpoint位置和核验进度，每次最多128
  事件及2秒工作预算，保存未决run/tool/action集合（4096数量/256KiB上限），
  拒绝选中checkpoint时未决工具/动作、当前未决输入/子执行和变化前缀。
  committing阶段先持久化；日志writer fence下写restore/baseline，随后SQL确认。
  原生已写、SQL/HTTP确认丢失时读精确目标位置并核对operationId/digest，
  不重写状态，即使随后撤权也先确认已经提交的事实。
- Java/Python/DSH写入接口接通；Agent Service V0006和Data V0003迁移已登记。4项实际
  H2事务核心测试（含丢失原生确认、随后撤权、选中工具不确定、变化前缀与子执行）
  与查询1项通过，/tmp/agentscope-managed-checkpoint-writes-java-tests-2.log；
  Client6项及全相关package/Spotless在第一日志通过。AgentService预算/trace回归和构建
  通过，/tmp/agentscope-managed-checkpoint-writes-portal-tests-1.log。
- 实际AgentService/Data/Harness进程通过，/tmp/agentscope-managed-checkpoint-writes-process-2.log：
  8次模型调用、37公开事件；Data停机后受理、AgentService重启续投、幂等/冲突、拒绝非空
  fork目标、两侧重启后再运行、私有恢复内容不进入公开事件。前一进程暴露只读
  ready检查导致503，已修复并同时允许checkpointing的策略刷新/列表。

审计发现Workspace JournalSessionLog.readAfter内部仍回溯提交链，事件数量限制
不等于底层读取成本有界。下一步改为Service专用SQL原生日志及序号索引，
保持Core SPI、独立Inbox、writer seal/fence与原子commit receipt；底层索引验证
完成前不宣称大型日志分页/恢复的容量验收完成。

## 2026-10-12 Service 原生日志 SQL 索引与事务隔离

- 发现 Core JournalSessionLog 的 readAfter 虽有限返回条数，物理定位仍遍历提交链。
  新增 Service 专用 RuntimeSqlSessionLogStore，保持 Core/Harness SPI；五张原生表
  按日志及序号索引，先读最多256条元数据，再加载不超过16MiB的正文。原生/Inbox
  域独立，批次收据、事件、head原子提交；稳定批次重放不重复，改变内容冲突。
- 写者认领、续租、永久seal和commit使用同一个head锁及数据库时间；PG使用
  clock_timestamp，commit末尾再次检查租约。旧写者、缺失前缀、改变身份/authority
  明确拒绝。Native commit独立于外层JPA业务事务；丢失业务ACK后不撤销原生事实。
- 独立有界Hikari池默认8连接，5秒获取、有限SQL/事务期限；所有原生读写在自身
  事务中执行，避免外层连接预算及连接绑定。Compose/Helm提供配置，所有副本的
  业务池与日志池需累加。根范围native discovery有4096数量上限，跨根诊断另行设计。
- Data V0004登记SQL与格式标记。新Session sql-v1；旧workspace-v1明确返回重建
  错误，未自动迁移或混跑。普通PG副本保持只校验；显式H2开发初始化幂等执行
  runtime-schema.sql。首次H2进程失败来自非JPA日志表未建，已修复并增加回归。
- 6项SQL日志核心测试和4项checkpoint事务测试通过、package及Spotless通过：
  /tmp/agentscope-managed-native-sql-java-tests-3.log。覆盖600事件索引读取、丢失ACK、
  双store争抢、永久seal、批次失败回滚、缺失前缀、外层业务回滚和开发建表幂等。
- 三组实际进程通过：H2 checkpoint/trace8模型调用37公开事件；PG双AgentService/双Data、
  独立迁移及DML业务账号同为8/37；H2子Agent工具/人工部分结果、ToolWorker回传、父子
  恢复及取消12/135。均含重启和公开事件/快照重放。日志分别为
  /tmp/agentscope-managed-native-sql-checkpoints-process-2.log、
  /tmp/agentscope-managed-native-sql-postgres-process-2.log、
  /tmp/agentscope-managed-native-sql-child-tools-process-2.log。fixture清理自身进程与PG
  schema/角色，保留测试容器。使用本地模型/供应商替身，没有访问真实供应商。
- DSH build与22项测试通过，/tmp/agentscope-managed-checkpoint-dsh-tests-1.log；
  Helm日志池非默认值渲染通过。架构及EN/ZH运维文档同步。

按序号定位成本已改为索引查询，Core SessionProjection/Inbox全量逻辑的历史增长
成本仍需优化/容量验证，不把这一步称为长Session或生产高并发认证。绑定PATCH、
其余Managed收尾和P8/P9欠项继续推进；实际切流、镜像发布和全仓verify尚未执行。

## 2026-10-12 Console Managed Checkpoint 与预算

- SessionExecution 基于 capabilities 展示 Checkpoints and budget。checkpoint 页固定
  as_of并续读空扫描页，最多显示1000记录；restore/fork携带稳定幂等键、明确原因，
  区分202受理和completed/rejected命令。处理时禁止同面板重复修改，用户显式刷新
  命令状态。折叠重开及Session刷新保留未确认请求键和已知命令，不重新提交工作。
- 预算显示limits和native_limits。新表单明确替换全部限制，空字段清除；写入携带
  观察到的If-Match版本，费用输入以十进制字符串提交避免JS浮点舍入。限制保存
  只表示下次模型调用前应用，不能把202显示为已经应用。子Session预算在根上写。
- console12项API/请求暂存测试、lint（0 errors / 98既有warnings）及build通过；新增代表性
  Playwright用实际组件与本地HTTP fixture，证明稀疏分页、503后同键重试、折叠/刷新
  保持命令、validating到completed分别确认、预算版本及精确费用字符串。
  请求在session storage写入成功后才发送；不确定结果及已知回执跨整页reload保留，
  旧回执不能覆盖/删除后来请求。/tmp/agentscope-managed-console-maintenance-browser-3.log
  （1 passed）、/tmp/agentscope-managed-console-maintenance-tests-2.log、
  /tmp/agentscope-managed-console-maintenance-build-2.log。构建产物由Vite生成，未手改
  control-plane/ui。控制面源码二进制重建通过，未发布或更新用户运行服务。
- EN/ZH使用文档同步。完整视觉/权限/错误矩阵及
  真实Gateway浏览器联调后补；当前浏览器fixture不代表真实后端权限测试。

Session绑定PATCH的Environment变更规则已提交用户选择；无人值守触发身份仍沿用
此前待决策项。其余不依赖这些决定的整套Managed源码进程继续验证。

## 2026-10-12 整套 Managed 源码进程与 Console 导出

- 实际Control、Gateway、Agent Service、Data、Go Runtime、Scheduler与平台ToolWorker链路通过，
  采用独立schema job、五个DML业务账号及本地模型/E2B替身。真实控制面账号/资源、
  公开与内部路由隔离、Managed文件（大于2MiB、去重/冲突/会话隔离）、产物发布、
  删除后固定快照读取、文件输入、两个ToolWorker任务及共享归档跑通；4次本地模型调用，
  45条公开事件。Webhook先失败，Agent Service/Scheduler重启后使用同ID/密钥投递并确认，
  签名、无凭据公开响应与删除清密钥通过。证据：
  /tmp/agentscope-managed-native-sql-full-deployment-3.log和-4.log，两轮完整通过。
- 第一次Webhook fixture未明确事件订阅，合法requires_action被错误当作完成回调；
  已指定turn.completed并保留签名/重启/稳定ID断言。第二次产物固定快照读取的响应
  缺少data，旧断言未捕获HTTP状态/正文，根因未确认。已改断言保留诊断，随后两轮
  未复现；保留故障调查欠项，不将连续通过描述为该偶发问题已修复。
- Console增加Session及选中Turn的公开NDJSON导出，固定当前视图水位，自动逐窗口
  读取并校验作用域、UTF-8、数量、游标和最终水位。仅全部成功后创建下载；取消、
  不完整响应与撤权不生成文件。总量32MiB、单条1MiB、总期限两分钟，较大总量用
  流式SDK。相邻维护/产物/导出面板key已区分，修复更新后出现重复面板的问题。
- console19项核心检查通过，/tmp/agentscope-managed-console-export-tests-1.log；
  五项浏览器流程通过，/tmp/agentscope-managed-console-export-browser-2.log，包含
  中文完整Session/Turn文件、缺少末尾换行无下载、恢复整页重载、预算、产物与工具。
  lint无错误、98项既有warning；build通过。首轮build的测试Uint8Array类型问题已修复；
  首轮浏览器暴露的相同key问题已修复，失败轮次不计入通过证据。
- 补充真实Control资源冻结/Gateway鉴权下的预算和checkpoint写流程：预算If-Match
  冲突412、稀疏固定水位、restore同键同回执、恢复后模型调用应用新限额、同Agent
  空目标fork、目标不复制预算/用量及两侧继续执行通过。
  /tmp/agentscope-managed-maintenance-full-deployment-2.log（6次本地模型调用）；
  -3.log与文件、ToolWorker和Webhook合并通过（共8次模型调用）。第一次新增流程中
  checkpoint公开查询返回503，未捕获直接数据面响应，根因未确认；fixture已在
  相同失败时记录私有元数据响应和共享策略失效/刷新状态，保留调查欠项。权限
  失效期间的503是已有契约，但尚不能据此判定第一次失败就是该原因。
- 最终console构建与嵌入UI的Control源码二进制重建通过；430页面/584重定向的
  文档检查、源码hygiene及git diff --check通过。全部检查仍不替代全仓verify或
  真实镜像/集群安装，未运行用户已有服务的升级。

源码构建/进程验证不等于镜像安装或生产容量验收。重建切换操作手册已记录在
[切换与排空](agent-service-cutover.md)；Session绑定PATCH、触发身份、剩余Managed机制
和P8/P9安装交付继续完成，未提交、发布或操作用户运行中的服务。

## 2026-10-12 用户本地镜像部署

- 用户授权完成本地Maven与镜像打包部署。代码仍在指定主目录及当前分支；Service
  client、Gateway、Agent Service、Data、Tool Worker、Scheduler及依赖以-T1 install成功，版本
  2.1.0-SNAPSHOT，跳过本轮测试重跑。console重新构建，Go两个角色交叉编译Linux
  arm64；七个本地镜像local/agentscope/as-*标签2.1.0-local-20261012已加载，未推送。
- 实际Compose使用独立项目agentscope-portal-local和18081入口，新的数据库/归档/工具
  记录卷、独立稳定密钥和业务账号。原18080服务与数据未停止或覆盖。schema-control、
  schema-agent-service、schema-data及grants全部退出0，六个HTTP服务与PG均healthy。
- 增补快速镜像路径对AgentService/ToolWorker制品的dockerignore放行和Go预编译镜像Dockerfile；
  Java镜像创建非root可写/data/hands目录。scripts/local-stack.sh封装该独立配置。
  已创建Local Managed Test、Local Tool Worker Docker及空ready Session；self_hosted Tool Worker
  Worker运行、持久目录可写，数据库心跳active且4并发槽位。真实登录、资源创建、
  Session snapshot/capabilities/budget/checkpoints及公网内部路由404检查通过。
- 私有配置/登录文件均mode600且Git忽略；未输出凭据。已有模型凭据配置到新环境，
  未执行真实模型或E2B调用。此实例供用户交互测试，不作为生产容量认证。镜像ID、
  Maven制品校验与资源ID记录在target/local-deployment，具体命令见deploy README。

绑定PATCH、Channel/Cron以及其余已有实施欠项仍保留；本次部署不表示这些功能已完成。

用户随后要求新实例全部用dev模式、关闭旧实例：新栈已设置MANAGED_AGENTS_LOCAL_DEV=true，
Go运行角色设置--local-dev=true；直接无登录调用dev-session、单namespace、创建
Local Managed Test/Local Tool Worker Docker、ready Session及所有维护元数据通过。
ToolWorker重新绑定开发身份的Environment，心跳active。原managed-agents五个容器已
停止，卷与归档保留；新栈入口仍18081。原普通鉴权测试资源保留，开发样例ID单独登记。
实际部署的Console浏览器验证通过：自动进入Overview、打开开发样例Execution页面，
dev-session请求2次且无页面错误；截图记录在target/local-deployment/dev-session.png。

## 2026-10-12 namespace 身份首次 Turn 修复

- 用户手工测试发现默认 namespace 的首次 Turn failed，model_calls=0、native_started=false。
  日志确认 Harness 实例 ID 直接拼入 namespace:default:default，违反 AgentBase 的安全
  ID 规则；并非模型或 curl 错误。数据面将非法/超长组合转换为 owner、Agent、Session
  元组的 SHA-256 ID，保持确定性与作用域隔离；已合法的旧 journal 身份保持不变。
- 新增身份回归覆盖默认 namespace、Unicode/长 owner、元组边界和旧身份；连同缓存与
  SQL journal 共13项测试通过，Spotless通过，Maven -T1 install成功。独立数据库的
  实际Control/Gateway/Agent Service/Data流程通过：2次本地假模型调用、13条公开事件。
  该进程 fixture 使用普通登录作用域；dev 默认 namespace 由身份单元测试覆盖，
  不将此结果描述为真实供应商或默认 namespace 全链路已经验证。
- 本地 managed-agents-agent-harness-runtime:2.1.0-local-20261012 镜像重新打包，18081栈只更新Data服务且healthy；
  现有资源与失败记录保留，用户可显式resume该尚未native启动的Turn。未自动运行用户
  会话。源码hygiene、37项发布工具测试与git diff --check通过；未重跑全仓verify。
  证据：/tmp/agentscope-managed-identity-{tests,deployment-smoke,build,local-update}-20261012.log。

## 2026-10-12 已有 Session 完成事件同步修复

- 用户继续手工resume后，公开Turn被标为interrupted。原生日志已有turn/completed
  (MODEL_STOP)及回复“已记住”；实际完成，只是事件导出源仍保留修复前的非法Agent ID。
  上一轮身份修复遗漏既有导出源的对齐，导致native尾部未导出，不作为模型中断处理。
- register现在同时核对已存用户/Agent日志身份。Managed当前执行代次与激活命令的
  已提交完成事实可修复保守failed/interrupted；旧执行不可结算新执行，cancelled不
  改写，额度不重复释放。Data同步修复命令状态，run/end关闭尾部修复任务。
- 35项回归通过，覆盖既有namespace导出源、当前/旧执行完成、无原生证明拒绝、重复
  完成及额度释放；Spotless与Maven -T1 install通过。Agent Service/Data本地镜像已更新且
  healthy。EN/ZH API说明同步，430页面/584重定向检查、源码hygiene与diff检查通过。
- 本地用户Session只校正带已提交完成证明的导出源并重新启用事件尾部修复，没有修改
  公开Turn状态或重执行模型。实际公开状态已completed，native游标75/75，最终回复已
  同步；用量仍3次模型调用（1次REASONING、2次SUMMARY）。其余资源与失败历史保留。
  证据：/tmp/agentscope-managed-export-repair-{build-tests,local-update}-20261012.log。
  未运行全仓verify或真实供应商自动测试。
- 新增--namespace-identity进程fixture，真实Control默认共享namespace资源、预置旧非法
  导出源、完整Managed模型调用及native尾部追平通过，2次本地假模型调用/13条公开事件。
  证据：/tmp/agentscope-managed-namespace-export-deployment-20261012-3.log。该测试使用
  普通登录与本地进程，不替代dev免登录镜像自动用例或多副本故障注入。

## 2026-10-12 流式工具事件顺序与语义修复

- 用户三阶段SSE文件/tmp/result.txt证明实际执行完成，但工具挂起被误投影为完成，且
  request事件晚于挂起返回。Core在action/start之前记录工具请求与状态；Service将
  挂起映射为tool.waiting，不携带空的“已完成”结果。实际外部结果按tool.completed、
  required_action.resolved顺序投影，成功/失败状态规范化，清除公开结果的私有metadata。
- 一个原生结果产生两条公开事实，各自有稳定幂等身份；原生导出游标仅在两者处理成功
  后推进。根/子Session和旧适配通道均使用相同投影。补充两条事实间丢ACK重放验证。
- turn/output改为item.finalized，更新同item_id的最终内容，避免重复item.completed；
  Java快照、Console两种视图、旧消息适配和Go公开视图同步支持。工具等待跨Run挂起
  保留waiting。普通context/replaced映射为context.updated；真实压缩按成功及数量减少
  报context.compacted，失败/无变化报context.compaction_ended，不公开原始上下文。
- 53项Java核心回归、17项前端测试、Go invocation测试通过。Maven -T1 install、
  console构建、Go本机及Linux arm64构建、Spotless通过；lint无错误，98项既有warning。
  430页面/584重定向、源码hygiene和37项发布工具测试通过，未运行全仓verify。
- 独立DB、实际Control/Gateway/Agent Service/Data/Tool Worker/Scheduler流程通过：共享namespace及
  预置旧导出源、两个工具请求/等待/完成/动作解除顺序、真实结果状态、单条最终消息
  快照和Gateway SSE重放一致；4次本地假模型调用、47条公开事件。模型和E2B为本地
  fixture，不调用真实供应商。日志：/tmp/agentscope-stream-order-deployment-20261012.log。
- 七个本地镜像及Maven制品已重建部署到18081，dev模式保留；六个HTTP服务healthy，
  ToolWorker心跳active。现有用户Session仍ready/active。历史公开事件保持不变，新Turn
  使用修正后的事件语义；重测使用新幂等键和最新游标。本地部署与镜像摘要记录在
  target/local-deployment/manifest.json，日志/tmp/agentscope-stream-order-local-update-20261012.log。

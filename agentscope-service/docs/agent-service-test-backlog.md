# Agent Service 测试补齐清单

本轮优先完成核心机制和可运行闭环，保留代表性测试。这里记录后续集中补齐的验证；尚未完成的实现仍列在 [实施记录](agent-service-implementation-status.md)，不以测试欠项代替实现。行为依据为 [状态与顺序契约](agent-service-state-contract.md) 和 [完整实施计划](agent-service-refactoring-plan.md)。

## 后续集中验证范围

| 阶段 | 需要补齐的测试 |
| --- | --- |
| P0 边界与容量 | 完整公开/内部入口归属；高并发 Session、Turn、SSE、工具及控制消息的容量基线；队列满和慢依赖下的有界内存与线程 |
| P1 协议与存储 | 全部 Java/Go fixture 的可选字段、版本、大小及错误映射；新数据库初始化、升级与回滚；服务账户最小写权限 |
| P2 Session | 多 Agent Service 并发创建、回执丢失、配置冲突、生命周期与新 Turn 竞争；PATCH、管理摘要和删除保留策略 |
| P3 命令 | 多数据面的准入、领取、fence 替换；提交/接收/ACK 各崩溃点；取消与启动/完成竞争；steer/inject 与恢复/终止竞争 |
| P4 事件 | PostgreSQL 来源位置、公开 seq 及批量事务竞争；重复/乱序/永久拒绝；快照与 SSE 重连、慢客户端和通知丢失；终态、动作与最终结果一致；私有字段过滤 |
| P4 子 Agent | 多层并行子调用、重复工具 ID、父子运行的崩溃点；子工具部分结果、确认答复、恢复后父上下文；取消停止证明、迟到结果及用量去重；持久子 Session 多 Turn |
| P5 后端 | External、Hosted、Team、Workflow、Invocation 的相同公共协议与 capabilities；Host 离线/重连、未知副作用、并行节点、信号与取消汇总 |
| P6 准入与权限 | 跨后端 Application 配额竞争；预留恢复与仅一次释放；撤权、通知/快照竞态和控制面故障；动态 Memory/Vault/Environment 覆盖；已报告预算与在途超额 |
| P7 Tool Worker | PostgreSQL 多 Worker 认领、共享 scope 绑定、容量/健康、结果 outbox；未知写操作与显式恢复；取消/drain、凭据轮换；journal/fsync/磁盘满、超大输入输出与产物引用 |
| P7 Sandbox | 真实 E2B 创建/执行/续租/归档/删除/恢复；DELETE ACK 丢失；保活失败和未知创建；多 Worker 清理竞争；dirty/缺失归档保护；孤儿管理与 self_hosted 目录迁移 |
| P8 入口与客户端 | Gateway 完整路由；Channel/Cron/Webhook/文件路径；SDK 示例、前端生命周期/动作/子状态；角色授权和机器身份隔离；中英文文档与契约一致 |
| P9 集成 | Compose/Helm 多副本闭环、新库初始化、故障切换及服务重启；打包发布检查；全部 Java/Go/UI 回归；可复现并发曲线和长期积压运行 |

未跑的 PostgreSQL 多副本、真实供应商、压力和全量回归，不以 H2 或 fake 模型的进程 smoke 结果替代。每次补齐时记录实际命令、环境、断言和剩余范围。

## 已有代表性证据与具体欠项

### Managed 输入拒绝与未知回执

已验证：11 项 Java 关键测试包括原子拒绝丢失 ACK、重建后拒绝、既有接收不被撤销、过期文件输入不再读取内容、runtime digest 冲突及 Agent Service 的明确拒绝/临时 503 区分。实际 Agent Service/数据面/file H2/fake 模型进程：离线时排队 → 权限源不可用 → Agent Service 重启 → 撤权 → 数据面恢复 → native rejected 与公开事件一次，再重启保持；原 inject/steer、SSE 与快照通过。具体日志见实施记录。

后补：真实 PostgreSQL 多 Agent Service/数据面同时递送接受与拒绝的竞争、每个 interaction/journal/fact/Agent Service receipt 提交点崩溃、旧 courier 在拒绝后恢复、权限恢复使用旧/新幂等键、归档/删除与文件清理交错、长停机/日志压缩/队列满和错误响应组合。取消、恢复、工具结果等其他 interaction 的明确拒绝收敛仍是实现审计项，不能以此输入验证宣称完整控制矩阵完成。

### Managed 资源配置覆盖层

本轮实施范围：共享策略刷新 Environment/Vault/Memory 配置，激活授权携带有版本覆盖层，数据面在激活事务内应用；运行中的装配不热替换，Agent/Workspace 定义不变。后补真实多副本同时刷新与激活、旧响应/权限通知交错、OAuth 旋转、Memory 后端变化、Environment provider/隔离配置变化与既有 Sandbox 绑定、共享 scope 中不同 Session 的身份/引用组合、覆盖配置 1 MiB 边界和定义大于 HTTP 限制、应用事务各个崩溃点及长期不可用。资源绑定 PATCH、更新后 Sandbox 代次和已暂停工具的完整恢复交互仍单独审计，不将配置覆盖层单独声称为全部 P6/P7 已完成。

### Session 生命周期新增证据与欠项

已验证：9 项选定 Java 检查和真实 jar 生命周期 smoke；不结算 Turn 拒绝归档、归档读/新 Turn 冲突、恢复、版本和幂等键冲突、删除后命令回执与原键重试、Agent Service 重启及旧版本数据面镜像不回退。

后补：PostgreSQL 多 Agent Service 的归档/删除与新 Turn、显式恢复、批准/结果及迟到终态竞争；镜像响应丢失、旧 claim 回执、长期积压和稳定拒绝的排障；已连接 SSE 收到删除前后的行为、权限撤销后的命令查询、各角色授权矩阵及管理摘要同步。物理删除/保留、PATCH/动态覆盖和公开路由尚需实现，不能只登记为测试欠项。


### Tool Worker 与 Sandbox 新增证据和欠项

已有：两类真实 Worker jar 加 fake 模型闭环，self_hosted 结果重复/冲突与重启、托管同 scope 创建和复用及机器身份隔离；最新 29 项选定 Java 行为检查和 25 种 Java/Go fixture。self_hosted 已跑通副作用后杀进程、unknown 不自动重跑、显式代次替换、重复恢复去重及旧结果拒绝；托管同类崩溃恢复也已通过，额外并发 poll 与其他 Worker 同 scope 认领均被容量/绑定拒绝。共享归档和供应商明确 404 后文件恢复已通过。

后补：PostgreSQL 多 Worker 同 scope 首次绑定与创建竞争；跨 Session USER/AGENT/GLOBAL scope 并发及失败隔离；claim/start/renew/commit/result 任一点响应丢失；多工具并发部分结果、旧 token/代次、取消与晚到结果；撤权/控制面故障/Environment key 轮换；只读重试耗尽和未知写操作人工确认；慢输入、超大输入/输出、磁盘满、journal fsync/rename/重启；Sandbox SDK PROTO 和真实 E2B 端到端。候选查询过滤未授权任务的公平性、闭合 Turn 任务清理、队列满错误结果补偿和 outbox 长积压需补容量测试。

仍待实现的 P7 内容：已删除 Session/孤儿的操作员恢复、复杂跨 Session scope 规则、Memory/Skill 路径和凭据动态覆盖、大结果产物引用、生产部署与公开路由。以上是实现欠项，不能只归为测试欠项。self_hosted 本地目录的 Worker 重新归属必须由明确的数据恢复或迁移操作完成。

清理后补：多 Worker 同时清理/认领、DELETE 响应丢失与重放、清理代次替换、长工具保活及供应商实际到期、dirty 或缺失归档的保护、共享存储不可用、历史不可变归档增长和保留/删除策略。


新增已验证：实际平台 Worker 清理已归档空闲实例，绑定代次更新且两份归档保留；崩溃后的显式 reconnect 修复、重复命令去重、不自动重跑以及后续工具重试/旧结果拒绝；E2B 保活连接失败不重建；本地 grep 限额、符号链接越界拒绝。对应日志见实施记录。

人工 Workspace 恢复后补：restore/discard 端到端、未知创建后补录 provider ID、其他 Session 正在使用共享 scope 时的拒绝、权限撤销、代次冲突、晚到创建/归档/结果、孤儿与已删除 Session 的管理处理。清理后补：响应丢失/重复和租约过期、多 Worker 竞争、长期工具实际供应商保活、未归档状态、共享存储故障和档案保留容量。


子 Session 代表性检查：已提交 spawn 的嵌套索引、重复登记、外来身份/环路拒绝、查询不打开父历史；子事实继承旧 run 的授权证明，子终态不结束根命令，等待 run/end 才清除导出补偿；真实 Agent Service JPA 事务中的独立 seq、根预留保留、子用量及重复去重、根删除后子读取拒绝；standalone 子流的 9 项既有检查；前端 subtree 汇总重放与类型检查通过。

子链路后补：PostgreSQL 多数据面/Agent Service 的父子登记和事实竞争；父 export cursor 响应丢失及父前缀积压；多层/大量子任务分页；独立快照和 SSE 撤权、慢连接与重连；同一持久子 Session 的多个 run/根 Turn、旧 run 迟到、取消/失败及用量范围；child_export_pending 与创建/闭合的崩溃点。子 Tool Worker、根 actions 的子目标关联、部分结果、父续跑和重启后取消已通过代表性全进程验证：12 次模型调用、135 个根公开事件；固定快照游标跨 JVM 稳定。Task 唯一键按根/原生 Session/原生 Turn/请求 ID 隔离。还需补子批准、多个子同时等待、嵌套续跑、取消与晚到结果、满载拒绝和实例退出的矩阵。异步/remote 子运行、显式恢复中断的子续跑、持久子 Session 多 Turn 的并发/恢复和根已关闭后的孤儿补偿仍是实现欠项，不能只增加测试。


### Go 运行角色与 Host 通道

已有：控制面离线时的真实 Agent Service/Go Host 机器通道、Agent Service 重启、双身份隔离、Host scope 和代次替换、204 空领取；转发满载保留控制槽位、取消释放槽位；编排有界 keyset 在较早行闭合后的公平性；25 类 wire fixture。

后补：PostgreSQL 私有角色初始化、表账户权限、active partial index 的 EXPLAIN/大历史；多运行副本的 outbox、Run 锁/版本、游标公平性及长停机；Host Attempt 续租、checkpoint/events、complete/fail/cancelled 的全部代次/身份矩阵；Agent Service 饱和、超大消息、空响应、超时/ACK 丢失/撤权；真实 Host/Provider；跨服务配置快照和 Invocation/Team/Workflow 事件与额度对账。当前尚未接通的后端是实现欠项，不能仅登记为测试欠项。


### 通用配置与 Go Agent Service Inbox 新增证据

已验证：真实 PostgreSQL 双连接的幂等提交、旧认领租约拒绝、Session 前序阻塞、状态与 outbox 原子提交、批内内容冲突回滚、序号去重与有序 ACK；加密 payload 的随机 nonce、数字精度、密文损坏和跨记录拒绝；Java/Go Unicode 和大整数摘要一致。真实 Agent Service/Go/PostgreSQL 注册和 Inbox 投递在控制面离线时通过，Agent Service 与 Go 的进程重启均保留回执。

后补：更多 Session/副本之间的领取竞争和公平性、租约 expiry/reclaim 与 SQL 写入竞争、所有 transaction/HTTP ACK 丢失点、大消息边界、旧加密 key 和轮换、数据库/磁盘满、事实长期积压与索引 EXPLAIN、冷热历史规模。P5 原生任务、授权续租停止、跨后端用量、能力/取消/恢复与事件映射仍待实现，不能仅归为测试欠项。

### Go 原生 Hosted 执行新增证据

新增已验证：真实 PostgreSQL 的 Hosted Job/Conversation、冻结 Profile 与原调用者、根许可到期拒绝 Host 续租、最终 usage 正差去重、physical-stop 之前不闭合 Turn、Go Server 重建后停止凭据/fence/重复请求；内存与 PostgreSQL 运行中取消、双连接取消回执及旧 step claim 拒绝；Worker 在实际停止之前保留 cancel_requested，完成竞争保留 completed；冻结输出 schema 失败。Host journal 129 条事件按 64/64/1 分批，停止 ACK 丢失后保留并重放的代表性检查通过。

真实 Agent Service/Go/PostgreSQL 进程的 Hosted Job 与 Conversation 已通过，执行期间不启动控制面；认证 Host 替身手动完成任务，Agent Service 重启后才提交停止证明。旧 fence 拒绝、重复证明、5 tokens 对账、连续公共事件与 SSE 续传通过；未调用实际 Provider CLI。运行中取消也已通过真实进程检查：重复取消去重、Host renew 403、停止证明前保留 cancel_requested、Agent Service 重启后 cancelled、命令 applied 和 SSE 续传。日志 /tmp/managed-agents-go-native-job-cancel-process-smoke-1.log。

后补：多 Go Worker/Agent Service 同 Session 的 grant/claim/取消/最终事实竞争，运行中 Go 进程替换、终态前后 Agent Service outage 和 budget 耗尽；Host 真实 CLI 输出及 journal 各崩溃点、最终事件与 physical-stop 并发封存、Host 不再上线时的停止补偿；原生事件隐私过滤和全部 provider usage 格式、迟到累计量/结果 mapping、复杂多节点和子图事件；索引 EXPLAIN/大量历史、8192 上限收敛的完整故障矩阵；新 migration 升级/回退、密钥轮换和最小账户权限。

仍待实现：Go 其他控制和 capabilities、公开非 Managed 创建、Hosted CLI/MCP/文件、External/Managed 混合和完整 Team/Workflow 映射；有界取消图推进、Host 停止孤儿及 journal 并发/容量、动态配置和统一 Invocation。以上属于实现工作。

生命周期新增证据：Go 行锁/revision 的内存与 PostgreSQL 双连接契约、Java/Go lifecycle 摘要共享 fixture、真实 Hosted Job 的 Agent Service→Go archive/restore mirror command applied 和归档新 Turn 409；摘要修改后的 Managed archive/restore/delete + 两服务重启 + SSE/快照进程回归通过。后补跨副本生命周期与 Turn 准入/停止/事实的并发，mirror ACK 丢失、乱序/长期积压、deleted 旧请求及 schema 升级；使用已有不同版本数据空间不属于新协议兼容要求。

封存新增证据：共享 Attempt stream lock 串行事件提交与停止证明；内存/PostgreSQL 原生 terminal 后仍可接收未封存事件，physical-stop 后 accepted=false。后补真实多 HTTP 请求的上传/封存竞态、超时/投影失败/ACK 丢失、旧 lease/fence 和 stream lock 中断。Provider 原始事件到标准增量、工具和 usage 的完整适配仍是实现欠项。

### 真实 Hosted daemon、MCP、journal 与取消新增证据

已验证：真实 Agent Service/Go/PostgreSQL/Host 进程运行 fake Codex app-server CLI，完成 18 条公开事件与取消 16 条均为 5 tokens；实际 MCP initialize、tools/list、task.get、math.evaluate，文本/工具快照、重复 usage 去重、Agent Service 重启、SSE 续传、生命周期镜像和 journal 排空。取消在 provider 等待中实际终止进程。`--exercise-daemon` 和 `--daemon-cancel` 不访问外部模型；手动 Host 场景继续覆盖停止前 Agent Service 重启与旧 stop fence，不能把这些断言算作 daemon 场景证据。

代表性 `-race` 检查覆盖续租与事件并发、停止凭据在未知 ACK 时保留、renew 拒绝后只读 observe 不延长租约、129 条事件按 64/64/1 投递及多轮清理。journal 独立文件、事件/Attempt 上限与满载背压通过。真实 PostgreSQL 验证 MCP 服务/任务双身份、根许可及 fence 拒绝。Java Agent Service Runtime Host 适配器实际运行 3 项测试。

后补测试：所有 provider 的真实录制/本地 fixture 格式、token baseline/reset/迟到/超大计量报告、子事件与同名 item/tool ID、完整隐私过滤；journal 每个 fsync/rename/metadata/ACK/cleanup 崩溃点、磁盘满及损坏、Windows 目录同步差异、256 Attempt 与最大事件长期积压；多副本事件封存、MCP 撤权和工具副作用竞争；daemon 中 Go/Agent Service 替换及未知 ACK、真实 provider 取消/恢复。日志见阶段记录。

实现欠项：Hosted 文件/审批/恢复及完整能力、Managed MCP 新身份、非终态 journal 恢复/单目录进程排他、Host 离线停止证明/孤儿管理、超大工具结果产物引用、有界取消图及 Team/Workflow/External 混合；opaque CLI 内部模型调用不能仅靠外部根 grant 实现逐调用预算门禁。以上不能只登记为补测试。

取消/排他新增证据：Run cancelling 的有限 8 节点/16 待办推进、持久 UUID cursor、等待子图不饿死后续节点，在内存和真实 PostgreSQL 通过；33 活动任务经过多轮排空，原有终态历史不会挡住它们。真实 daemon 重复进程在注册前被 OS 状态目录锁拒绝，取消/MCP/5 tokens/16 事件/Agent Service 重启/SSE 回归通过；状态锁重复与释放单元检查、Windows 交叉编译通过。单目录进程排他已实现；旧兼容 Invocation、fail-fast 整图及独立 Issue Run 的取消仍需继续迁移。

后补：多 Worker 同 Run 的锁/CAS/游标竞争、取消与子图/审批/任务创建竞争、超过一批的子 Run 和物理 Attempt、cursor ACK 丢失/损坏/存储不可用、0121 索引 EXPLAIN 与大历史公平性；Unix SIGKILL 后锁释放、Windows 实际锁与进程退出、路径别名和文件权限、不同目录误配置相同 Host key。OS 锁不替代服务端 Host 代次与物理 fence。

### Hosted 工具确认新增证据

已验证：审批 Inbox 的内存/PostgreSQL 双副本重复与冲突、旧推进 claim 拒绝、取消先行及跨激活迟到交付事实；Hosted 原生 10 场景的实际数据库/内存回归，包含允许、拒绝、等待不续根许可、决定提交后 provider 先结束的重试、原人身份及审批作用域、控制台旧决定入口门禁、MCP 在等待中拒绝及恢复后立即可用。旧 Hosted/Managed 审批代表性回归通过。Java Runtime Host 原身份 GET/POST/204 传输及 capabilities 查询共实际运行 5 项。

真实四进程 fake Codex 检查：`--daemon-approve` 与 `--daemon-deny` 均跨物理租约周期等待并在等待中重启 Agent Service，重复答复/改变内容冲突、provider accept/decline、applied 回执、27 条公开事件及 5 tokens 去重、快照、SSE 和 journal 排空通过。首次检查的事件类型与测试租约问题已修复；失败日志留存，不计为通过证据。

后补测试：超过 8/100 个确认的有限推进、并行任务部分答复、每个决定/状态/outbox/Host ACK 提交边界的崩溃与未知结果；真实多副本审批/取消/换代/撤权竞争；迟到 ACK 在多次激活与 Agent Service policy 变更后投影；确认过期、接口积压、长停机和数据库错误；API Key/只读/非指定审批人等完整权限组合；不同 provider 的审批录制与大预览、脱敏和 Unicode 上限。

仍需实现：长任务 token 刷新及 Host 离线、非终态 provider 恢复；Team/Workflow 普通审批和信号、子命名空间确认、External 及其他 provider 完整能力；公开非 Managed 创建、SDK/UI 接入和数据库账户边界。这些不能归为补测试。

### 公开 Hosted 创建新增证据

Managed/Hosted 配置投影与准入种类一致性、其他后端门禁、原调用者和服务身份分离共 2 项创建测试通过；加上能力与机器传输共 7 项。公开 Hosted Conversation 四进程检查与 Managed 回归通过，具体日志见阶段记录。Hosted 配置源使用隔离 HTTP fixture，未验证真实控制面账户、Namespace RBAC 与资源 use 授权。

后补：真实控制面创建集成、同一创建键的多副本竞争、配置冻结与绑定更新、权限撤销/变化与创建提交交错、配置源不可用和未知 ACK 的所有边界；实际 Hosted provider 录制与能力差异。


### Workflow 根交互与授权前停止新增证据

根审批/信号和节点投影已有 14 个内存/PostgreSQL 场景，包含 35 节点终态 drainage、原
人工身份、并行分支仍运行、作用域及广播门禁、大整数进入下游 CEL、授权前取消/拒绝。
Agent Service 7 个停止证明检查含授权响应丢失、取消 applied 与配额一次释放、历史事实排空、
无实际停止证明时拒绝终态，另有协议/子事件/capabilities 核心回归。公开允许、拒绝、
并行进程验证已通过；信号修复后也通过了 13 事件进程验证及大整数下游精度检查。实际配置源是 fixture，真实控制面账户/RBAC
不是这些通过项的一部分，完整日志见实施记录。

后补测试：超过 8/100 个答复的有限推进及部分应用；同名多信号、节点同时完成/创建时
keyset 遍历、审批创建与等待状态提交之间崩溃、RunEvent/节点/outbox 各提交点 ACK 丢失；
控制命令撤权、取消与已提交/未确认授权的多副本竞争；0122/0123 索引 EXPLAIN 与大历史
读写公平性；所有 API Key/审批人权限组合和真实控制面
准入；信号 double 范围、极大整数及数值表达式类型组合。

实现欠项单独保留：Team/子图交互、Agent/MCP 信号的持久准入、暂停/恢复/重试、完整失败
与产物视图、主编排循环及 fail-fast 的整图扫描、非 Managed 混合和统一 Invocation。
授权前取消/拒绝的排空与终态协议已实现，后续继续补竞争与故障覆盖；不能因此认为其他
后端控制或所有分布式恢复已经完成。

容量停止的后续实现和证据见下一节。8192 图/节点/Attempt、256 provider usage stream
与 64 内容块上限已有停止与失败路径；完整跨副本竞争、长历史和全部超限矩阵后补。

### Hosted Team 公开路径新增证据

内存/PostgreSQL 的 Team 根结论、取消和动态并行工作已有代表性验证，未完成工作拒绝
提前结论，所有物理 Worker 停止前不结束根 Turn。公开创建及完成/取消的真实进程检查
已通过，具体范围和日志见阶段记录。使用手动 Host 与配置 fixture，未验证 provider
CLI、真实控制面 RBAC 或完整 roster 委派。

后补测试：多协调者并发 replan/结论/取消、并行 Worker 成功失败及部分停止、根结论与
节点创建竞争、MCP ACK 丢失/撤权/旧 fence、各最终事实与生命周期交错、多 Agent Service/Go
重建、大规模节点公平性。子图交互、完整委派/失败/产物及剩余图控制仍是实现欠项。

### 原生事实分页、嵌套 Workflow 与容量停止新增证据

192 个文本块分批、消息内游标跨服务重建、唯一事实及 5 tokens 去重，内存和 PostgreSQL
通过。累计用量在零剩余空间时不提前消费，恢复后不重计。嵌套 Workflow 8 场景及公开
27 事件信号进程通过，保留重复节点名的不同 UUID/父图、子待办作用域、完整最后扫描及
大整数输出。节点容量使用满游标触发保护；内容块容量使用真实 65 块原生事件，两种
存储各通过。Hosted 未答复确认的取消/容量排空已有 3 个场景，Java 停止结算 10 项通过。

后补测试：任意 fact offset/最终用量/状态/outbox 的事务 ACK 丢失，多个副本连续接管，
事件边界为零/最大剩余空间、Unicode/超大内容、所有 provider 录制；真实 8192 节点/
Attempt/图和 256 usage stream 上限、待交付确认超过 16 的分批排空、容量停止的迟到
用量/最后停止/取消/生命周期竞争、未知 ACK、数据库错误、长期日志归档；大量嵌套图
的查询公平性和 0124 EXPLAIN、多个子审批并行、跨层取消/失败/晚到事件/最终版本变化。
资源读取应使用事件/SSE 判断刷新就绪，固定快照分页复用 snapshot_id；需补 UI 频繁刷新
与快照容量、TTL 和过期恢复的集成验证。

实现欠项仍单独保留：独立 Issue 子 Run 发现与父子停止、完整 Team 委派/失败/产物，
暂停/显式恢复/重试、Agent/MCP 信号的持久准入、长任务身份刷新、非终态恢复和 P6–P9。
共享根 Issue 的 Workflow 子图审批与信号、事实分页和容量停止已实现，不能因此认为
完整后端和分布式恢复全部完成。

### 步骤快照新增证据与后补

已覆盖重复节点名的身份、前端各刷新点恢复、固定快照分页旧值、更新后新值、
Turn 范围隔离，以及公开嵌套拒绝流程重启后快照与事件一致。后补大量图分页、多
Agent Service 同时投影/更新/读取、快照 TTL 过期、删除生命周期及大节点结果；这些覆盖
欠项不阻塞已实现的 steps 资源。

### Hosted 凭据及分页客户端后补

已有证据：短 token 换发、过期 journal credential 恢复、旧物理证明拒绝、撤权只给
报告、MCP/CLI 本地转发与批准 ACK 取新 token、代表性 race；真实 daemon 的批准/
重启与普通完成、公开 CLI 上下文。后补跨多个实际 TTL 的进程运行、所有 provider
静态配置录制、更新 ACK 丢失、临近过期并发、长时间断网、Host 入驻凭据轮换、
完整 shell mactl CLI 及所有任务操作。Host 机器凭据续期与 Gateway 实际转发已补齐，
证据见下文；签名 key 轮换/撤销、非终态 journal 恢复、孤儿执行停止仍是实现欠项。

已有分页证据：新结构、200 行上限、固定快照续页、已知实时资源不被旧页覆盖、
已解决动作不复活、错误快照拒绝、Turn 专属终态查找、权威用量与大整数信号提交。
后补浏览器刷新与 SSE/分页/动作交错、TTL 到期恢复、多个同时待办、队列大于首批
列表、迟到页中未曾加载的失败模型/工具、artifact 删除与旧页、防无限 UI 资源增长
及大历史交互。这些边界在最终 UI 接入中仍需实现或补齐，不能只归入测试欠项。
完整管理页、capabilities 控件、其余客户端/SDK、分层部署和旧路由关闭仍需继续实现。

### UI 能力和恢复新增证据及后补

已覆盖最新/运行 Turn 的分页过滤与游标范围、单 Turn 能力限制、同毫秒旧列表页不
覆盖新状态、子快照结构、历史页之后的消息/工具前缀、模型失败、停止、动作受理和
产物删除重放、缓存到限提示。浏览器 HTTP fixture 已验证 Hosted 操作隐藏/长队列
选中运行任务、Managed 恢复确认与代次、不同命令、受理及实际状态查询。代表性 UI
17 项和 Java 16 + 8 项通过；Hosted 批准进程再次核对权威用量/列表/SSE。

后补：真实 Gateway + UI + Agent Service + Tool Worker 交互、权限变化与能力刷新竞争、确认提交
ACK 丢失/重复/拒绝、恢复表单所有模式与失效代次、2000 行列表及真实高负载下窗口
到限、连接异常/TTL 过期/滚动体验、工具任务跨页状态变化、全客户端版本不匹配。
这些验证欠项与已实现能力控件、事件重放及恢复入口区分。虚拟历史浏览、完整管理
页、其余 SDK/客户端、部署/角色账户/迁移任务和 P0–P9 未完成项仍需实施。

### 原生快照加密新增证据与后补

已验证敏感配置、覆盖和定义移出 metadata；Task/Attempt/outbox 直接 PostgreSQL
查询无冻结配置明文；真实 Host 进程仍取得冻结执行配置并完成批准/重启/SSE。后补
错误 key、key 轮换/灾备恢复、极限配置大小、篡改每个身份字段、多个实际 Task/候选
接管和长时间恢复、数据库权限与迁移初始化一起验证。JSONB 的原始 capability 数值
不是 Cipher 绑定字段，解密返回原完整快照；不能把调度元数据与所有资源存储描述为
已整体加密。稳定 key 配置、角色账户和 migration job 仍需在部署实施中落实。

### 独立 schema job 与 PostgreSQL 业务账号

已验证 Go Runtime 元数据只读校验，Product/Agent Service 并发迁移及只读校验，缺失/未来
版本拒绝，Product/Java checksum 拒绝，Product 初始化不创建用户。真实 Java jar
独立迁移和无 CREATE 权限的 Agent Service/Data 账号执行 Managed 推理、重启、SSE、快照
与幂等通过。这个链路现在使用实际 PostgreSQL，而前面的历史进程证据仍按其实际
文件 H2/Go PostgreSQL 范围解释。

两个 Agent Service + 两个数据面共享 PostgreSQL 的跨 Agent Service 幂等 Turn、独占执行与事件
读取通过，仍为两轮 4 次预期模型调用。实际 Tool Worker jar 的 PostgreSQL 工具结果、
部分结果、Worker 重启、Sandbox 复用、继续推理/SSE/快照通过，60 条事件、7 次
本地模型调用。供应商/模型是 fixture，代表性多副本检查不是容量认证。

后补：迁移各阶段进程退出、DDL 超时/数据库断线、Go 无事务索引失败再执行、极限
版本记录、所有未来增量迁移的升级/回滚、长 Session/LOB/归档的清理与账号权限、
实际 Helm/Compose 安装升级及多副本滚动。Go rt 表/列归属及最小写权限、部署接线
和全部新组件制品仍属实现欠项；不能用这份测试清单替代其实现。

### Go 运行角色的 Agent Service 来源要求

已验证无 Agent Service 契约的 Run/Task 不开始执行，旧尚未公开的物理 Assignment 被拒绝，
且不能续租；PostgreSQL 原生 Hosted/Workflow 正常矩阵及相关旧角色包测试通过。
后补不同后端、等待/终态/归档/删除/契约损坏、凭据刷新与租约失效的并发组合。
旧 outbox 来源过滤和所有资源操作的运行意图桥接仍属实现欠项。


### 新部署制品与初始化接线

已验证：五个 Java 执行模块构建、Scheduler context，37 个发布/Helm/package 测试，
源码 Compose 的 build 映射与路径解析；实际独立迁移、授权脚本、五个无 CREATE 的
业务账号及六个真实服务进程启动，Gateway 登录/私有路由与 Go readiness。授权脚本
拒绝 owner，元数据写权限拒绝、Agent Service/Data schema 隔离通过。此次启动没有模型调用；
Tool Worker/Managed 工具与推理证据来自此前独立 fixture。

后补：实际七镜像单/多架构构建、Compose 完整生命周期和小规模多副本、Helm 新装/
新增表升级/失败 Job/Secret 轮换/节点迁移、Tool Worker StatefulSet 重启和共享 PVC 实际
恢复、Ingress/SSE、真实外部数据库权限。不能把渲染通过写成集群已经部署通过。

仍需实现：Go rt 与 Scheduler dp 逐表/列写权限，剩余公开入口/SDK/UI、资源配置导入、
所有后端整套部署核心流程和切流/排空/回退方案。这些项目不作为单纯测试欠项处理。

执行来源循环拒绝及 256 次查找上限已经验证；后补边界恰好 256、缺失/损坏契约、
父 Issue 删除/重新关联与并发权限变化的组合。

### Host 机器凭据续期新增证据

已有：过期/跨 Host/服务身份拒绝、旧代次冲突、作用域及非执行权限保持；8 个
并发 heartbeat 合并刷新、Unix 私有文件权限、缓存重启、新 enrollment 优先、
异身份缓存拒绝及 race；六个真实服务进程 Gateway→控制面→Agent Service→Go 运行角色
注册/续期和实际 daemon 重启复用缓存。控制台 single-scope 的授权 namespace 与
只读拒绝回归通过。日志见阶段记录。

后补：多次实际 TTL 运行、临界过期/时钟偏移/长离线、ACK 或磁盘写入失败、磁盘
满/断电、Windows 实际 ACL/rename、Gateway/Agent Service 饱和和代次替换竞争的完整矩阵。
签名 key 轮换及主动撤销、非终态执行恢复和离线孤儿停止仍是实现欠项。

### 真实 Managed 配置源与失败清理新增证据

已有：实际 Control 账号、Environment、Agent，经 Gateway/Agent Service/Data 执行，
Session/Turn 去重、13 事件与 2 次 fake 模型用量一致；普通业务账号没有 DDL 权限。
内部准入与公开 Session 的 namespace owner/member/viewer/非成员权限回归通过。
长原生日志身份超过 255 字符时，Service 私有物理 key 固定 64 字符；H2 验证读取、
发现、用户隔离及 writer 换代后的旧 CAS 拒绝，实际 PostgreSQL 执行通过。

已有：fail-fast 25 个兄弟节点、等待子 Run、不阻塞后续页、换协调者继续及原始失败
原因/单一终态事件；timeout 40 个任务按 16/16/8 排空后才标记失败，内存与 PG 均通过。
Workflow 实际进程的嵌套拒绝/重启/SSE 已再验证。

后补：失败/取消/timeout 与任务创建、重复 outbox、撤权、物理停止的多副本竞争，
各 Run/cursor/终态提交边界崩溃及索引 EXPLAIN；日志 key envelope 损坏、CAS 竞争、
大 Unicode 身份和迁移/版本拒绝。一般图推进、materialize、终态 Issue 树的全量
读取，以及其他后端文件及剩余公开入口仍为实现欠项。


### 完整部署中的平台 Tool Worker

已有实际七组件、真实控制面资源/准入、5 个 PostgreSQL 业务账号下的两次工具执行、
共享归档、结果继续推理和 Gateway SSE 重放通过，fake 模型/E2B，无外部调用。
后补实际部署多副本认领/重复结果、服务饱和与故障重启、实际七镜像/Helm/Compose
安装和供应商测试。源码进程验证不能代替镜像及集群验证。

### 文件及显式产物剩余验证

- 已完成真实七组件 Managed 文件/图片输入/产物/Tool Worker 核心 smoke，仍需多副本大文件
  并发、慢读写满槽、30 秒超时、断网/客户端取消后的连接及 DataBuffer 释放压力验证。
- 补完整 MIME/Unicode/编码/未知长度边界、Application read/interact 权限和撤权期间
  传输、配置单文件上限、总输入大小及多类型模型解释矩阵；不使用真实供应商凭据。
- 补 Agent Service V0001→V0002 升级与新表授权、失败回滚及发布并发压力；旧快照、删除
  Turn SSE 已有代表性逻辑覆盖，补实际运行端产物删除与重启断点联调。
- Team/Workflow/Hosted/External 文件输入和运行端产物适配目前仍有实现欠项，不能只
  作为待测试项目关闭。完整 SDK/UI 文件发布和下载流程随实现补齐。

### 运行端产物与客户端剩余验证

- 已完成 Hosted/Team 真实进程与 Python SDK、Managed 七组件产物内容及前端 3 个
  fixture；补 Workflow、External、混合后端、实际 Host daemon 的产物路径和完整
  SDK 包入口、Java/DSH 客户端。部分客户端仍是实现欠项。
- 补晚提交产物/多副本导出游标、8192 容量停止、HTTP 超时和 RWX 故障/极慢客户端，
  产物过期、权限撤销、16 槽饱和跨请求竞争、异常 MediaType 及大文件压力。
- 补实际文件权限角色 UI、只读视图、不同 Session 快速切换、浏览器断线重连后
  发布/删除投影，以及模糊文件上传失败；当前浏览器验证使用 API fixture。
- 运行端 MCP 仍经 JSON/base64 上传；大产物流式 task 上传和全后端 file_input
  适配属于继续实现内容，不能把 100 MiB 平台大小检查当作已经跑通的大上传能力。

### 统一公开客户端与 Turn 状态剩余验证

- 已有 23 项前端、12 项 Python、13 项关键 Agent Service 测试及真实 Team/Python/DSH 过程。
  补通用 ServiceTurnPanel 的浏览器/API-key/指定审批者/快速切换/分页/模糊 ACK/达到
  显示上限场景，已有 SessionExecution 浏览器 fixture 不覆盖这个独立面板。
- 补全部 SDK 的重复/无序事实、不同后端交互能力、断流和超时、1 MiB 事件及 16 MiB
  JSON/100 MiB 内容边界、客户端取消、Unicode/路径编码、Python 3.9/Node 20 运行矩阵。
  DSH 的真实进程路径只验证了标准 Node 客户端，未加载真实 DSH/Cordis。
- 补 running 与取消/终态的并发事务、旧代次/过期授权及后台事实排空；确认公开 Turn
  投影始终与准入状态一致，未知状态被拒绝。权威 usage 的热 Session 读写争用后补。
- DSH External 执行/注册入口、Channel/Cron、budget/checkpoint/
  PATCH 和完整 UI/SDK 仍属实现欠项，不能作为纯测试清单关闭。

## Agent Signal 后补覆盖

- 多 Agent Service/Runtime 副本中 Task Attempt 替换、撤权、命令受理后发送者完成与应用前交错；已有核心测试及真实单 Runtime/重启流程不能替代这一矩阵。
- 受理 ACK 丢失、原生 applied ACK 丢失、重复/迟到 Signal、Task CLI/MCP 同一请求跨实例重放；稳定回执、不同内容冲突和普通重启已验证。
- 工作流最后 Agent 完成后的汇聚覆盖复杂条件、fail-fast、子图和并行任务；本次验证 declared 的 Signal/Condition/Agent 完整输出与大整数，更多组合后补。

## Agent Service/Scheduler Webhook 后补覆盖

- 多 Agent Service 与多 Scheduler 副本的并行 claim、时钟偏差、删除/撤权与网络请求交错；本次核心测试覆盖新 fence 拒绝旧 ACK 和撤权后暂停，进程验证覆盖两组件重启。
- 发送前/后各崩溃点、2xx 后 ACK 丢失、lease 超时接管、8 次退避与显式重试的长时间矩阵；稳定 delivery ID、首次 503 后重启成功已验证。
- 真实 HTTPS/TLS、DNS/域名 allowlist 调整、超时/巨大回调正文、按 Session 64 订阅/长日志公平性及吞吐；本次使用 loopback HTTP fixture 和受限响应流。
- 所有后端标准事件的订阅、Application key 的专用 webhooks:write 与 read 组合、禁止无权用户查询跨 Session 订阅/回执；本次真实控制面用户与 Managed 流程通过。
- Webhook 投递记录保留/清理与运维告警作为后续实现和验证单独完成；没有把该实现工作视为已完成测试。

## Java/Python/DSH 客户端后补覆盖

- Java 已构建独立客户端且四项核心行为测试通过；真实 Team/Agent Service 重启后 Java、
  Python、DSH 的快照、用量、产物及 SSE 读链通过。补 Java 实际创建/提交与各后端
  人工答复、取消、恢复、生命周期回执、Webhook 的完整应用集成矩阵。
- DSH 文件二进制/编码/原始凭据及重定向拒绝两项行为测试通过；Python 公开入口九项
  通过，含上传限制和重定向拒绝。三客户端 Managed 文件真实读写、多副本及大文件
  压力另补，不能用 Team 产物下载代替 Managed 文件上传证据。
- 补 SSE 分片 UTF-8、CR/BOM、多行、半条事件 EOF、消息超限和慢消费者；Java 已验证
  CRLF/多行、大整数以及收到 headers 后超时关闭；三种客户端的超时语义需分别验证。
- Java/Node/Python 运行版本、代理/TLS、凭据轮换、模糊 ACK 与下载取消矩阵后补。
  流式大文件客户端接口属于实现欠项；当前三客户端下载使用有界缓冲。
- Java SDK jar/sources/javadoc/POM/双语 README 源码打包通过，版本列入发布清单；完整
  安装依赖解析、制品签名与实际 Maven 发布另行执行，未宣称仓库已发布。

## 终态失败 Issue 收尾后补覆盖

- 已有内存/真实 PostgreSQL 的 50 子 Issue + 孙节点、有限批次、换协调器续跑、失败后
  新建工作排除、历史恢复与完成后不重跑，以及 36 个编排测试入口回归证据。
- 补多副本 Run lock/CAS、状态已写但 cursor 未提交、cursor 已 done 但标志未清除等
  崩溃点；进程被杀/重启与普通 DML 账号、迁移回滚的组合。
- 补树深/循环异常、巨量兄弟、归档/删除、权限撤销及恢复恰好与旧子状态写入并发；
  当前恢复 guard 每批复核，尚未覆盖所有交错，需要核对是否需要更细的原子边界。
- 已补主 Engine 节点预算、materialize 分批和 ListNodes/ListEdges 容量上限；Team 完成
  检查、全部重试历史和结果写入聚合继续实施；核心 Runtime 来源过滤已补，完整控制面运行写入归属继续实施。


## 编排图容量后补覆盖

- 已有六个新增行为入口，覆盖真实 PostgreSQL 两连接池最后名额竞争、超限委派无孤儿、
  可见阻塞路由与 queued coalescing、边批次原子拒绝、40 节点分批重建与完整输出、
  等待公平性、定义预检与内存超大图失败排空。
- 补独立 Runtime 进程竞争、建图的逐个 Issue/node/edge/run.started/状态提交崩溃点、
  KV 副作用已提交但游标未提交、多副本游标 CAS、暂停/取消/撤权与分批建图交错。
- 补 PostgreSQL 16 MiB 聚合载荷与异常旧图、最大图读写吞吐及 p99；当前索引计数会随
  Run 大小增加扫描成本，是否引入计数列属于压测后实现选择。
- 补完整 correction/completion 超限路由、满容量 Team continuation 与复用协调节点；
  本轮代表性创建评论/任务和共同 capacity guard 通过，未声称全部路由矩阵均已验证。
- Team 完成检查、重试 Task 历史、结果写入聚合和跨子图总容量仍属实现工作，不能用
  测试待补来表述。完整图按明确上限保留输出，未使用静默截断降级。


## Team 完成屏障后补覆盖

- 已有第 521 个 Task / 子 Issue 阻止错误完成的内存和真实 PostgreSQL 回归；有限
  keyset 历史读取、重试时序、已有 Team/Workflow 并行及审批场景通过。
- 补跨页优先级/状态修改、同时间戳 UUID、满 32,768 Task / 8,192 Issue、16 MiB
  视图到限及撤权；UUID keyset 的一般分页不提供多次查询的一致快照。
- 完成与委派/输入共享事务 guard 已在 2026-10-11 接通，核心竞争/崩溃恢复已补；
  大规模交错与角色完整矩阵仍按以下专节补测。
- 每 Task 的 Input/Comment 历史和人类可见汇总正文聚合仍需明确容量或增量机制；
  本轮有限 Task/Issue 集合不等于全部历史已被限制。


## Runtime 来源和有限扫描后补覆盖

- 已覆盖内存/真实 PostgreSQL 的 Agent Service 根/独立子 Run、旧任务排除、非法 UUID、
  标记不赋予执行权限、终态收尾、scoped outbox 的租约交接和旧 ACK 拒绝，以及
  Host 认领不修改旧 Attempt。实际 Agent Signal 进程及 Agent Service/Runtime 重启通过。
- 已有内存扫描器重建证明工具审批与各 Attempt 页能绕过持续有效记录；补真实
  PostgreSQL 多 Runtime 同时读取/提交 KV 游标、每个副作用前后崩溃与 CAS 失败。
- 补来源深度/循环/跨 Namespace 异常、来源创建与 Run 创建交错、独立 Team child
  恢复/取消/输出聚合、未投递事件的 backfill 与 retention/死信显式回放组合。
- 已覆盖 0128–0131 种子升级与只读版本校验；补 DML-only Runtime 实际 owned Task
  执行、精确函数/表权限及部署 upgrade/down 的完整角色矩阵。
- 补大量旧和当前记录混合时 scoped EXISTS 查询计划、部分索引命中、队列/各租约
  状态/审批扫描 p99、共享 KV 热点、Batch 1/500 和无记录页回绕。当前小规模行为
  证明不是容量结论，扫描轮转完成的延迟随待扫描总量和处理速率变化。
- 输入重试按输入 ID 写入预算已补，完整重试递送/唤醒、Input/Comment 及聚合载荷总量、
  归档清理、控制面逐表写权限继续实施，不能作为纯测试欠项。


- 输入重试新增内存/真实 PostgreSQL 的单 Task 17 输入、3 项有限写入与六轮收敛，
  旧任务隔离和空批次通过。补多 Runtime SKIP LOCKED 竞争、ACK/完成与输入状态
  更新交错、due 索引计划、500 边界、整轮存储失败及完整 Hosted/Managed 输入
  重试递送/唤醒；Task 总历史与载荷容量仍是实施欠项。


## Team 完成边界后补测试

- 已覆盖两独立 PostgreSQL pool 的完成/child 创建竞争（含有/无 AgentTask
  source 的 child）、未提交 Run 持锁写入、节点与事件同时提交；Memory/PG 的
  queued input CAS、明确完成意图恢复、普通完成保留 waiting、pending 输出修订/
  旧物理结果拒绝、accepted 输出不可改写和大整数/字段顺序重放通过。
- 补 PostgreSQL 多进程在意图写入、物理接受、Run barrier commit、事件通知和摘要
  发布每个边界崩溃；该轮 Go 对象替换证明状态恢复，不是每个崩溃点的进程证明。
- 补持续同时委派、输入合并、leader 空重复退场、Issue reopen/source 修改与 node
  completion；校验输入没有误取消、Task/Attempt 退场、Run/Turn/event 最终一致。
- 补 256/257 层 Task ancestry、循环、多个活动 Adaptive Run 锁顺序、同 Issue 多个
  声明式 Team、容量上下界、NOWAIT 冲突退避与批次公平，以及万 Task 查询计划/p99。
- 补 DML-only Runtime 的 coordinator/intent 表权限与生命周期 retention 清理。
  每 Task 历史和摘要容量、独立 child Run 全链路及旧行政写入意图仍是实施欠项。


## 评论历史与摘要后补覆盖

- 已覆盖真实 PostgreSQL 单连接带 mentions/routes 读取、按来源的最新有效交付，
  521 条同时间戳的 keyset 完整历史；摘要超限不发布部分结果，并以明确 status/
  system 详情引用继续收敛和去重。
- 补 32,768/32,769 和 16 MiB 多页边界、JSON 转义放大、持续并发插入/更新/删除、
  多个 Runtime 同时查询小连接池，以及大历史中的 latest delivery 查询计划/p99。
- 补已接受 coordinator → 引用摘要 → Root Issue 状态 → Agent Service Turn 终态的多进程
  大输出链路，以及发布每一步重启、并发完整摘要/引用摘要的同 ID 竞争。
- 每 Task 输入、mentions/routes 集合及写入预算、跨图输出总量、产物替代与公开快照
  统一容量仍是实施欠项；当前行为测试不能当作这些机制已经完成。


### Task 输入预算后补矩阵（2026-10-11）

已选做：PG 两池争最后名额/幂等，8,193 旧历史 claim 回滚无孤立 Attempt，旧大错误仍能 cancel，memory 对应行为，memory/PG Team 重试范围与成功解除屏障，多字节及转义诊断重放。

后补测试：8,192 输入实际处理/批量 ack/完整完成与 Agent Service/Host 停机交互；长度极限 tenant/namespace、时间戳/ResponseCommentId、转义放大与总页预算；重复/未持有 input ID 和同时校正/投递/complete 的竞争；PG 小池、多副本最后名额持续压力、索引 EXPLAIN 和 p99；显式 HTTP 容量错误及 UI 展示；Task 重试/手工重放所有 commit 崩溃点、终态旧 Run continuation、review feedback 运行及原始投递次数清除；旧越界历史的运维排查和分步修复。

实际输入 retry delivery/wakeup、活跃 Hosted Attempt 的安全递送仍是实现欠项，不能列为仅补测试。诊断预览不保证保留完整错误日志；完整 provider 诊断及关联可观测性按原阶段继续实现。


### 2026-10-11 本轮实施范围调整

用户明确重点为 Managed Agents，Team/Workflow 等先跑通基本流程。已有公开 Hosted Team/Workflow 实际创建、事件、审批/信号、完成/取消和重启代表性证据继续有效。复杂 Team child Run 全发现/恢复/取消/输出、完整委派、跨后端混合及高级 Workflow 控制属于后续实施项，不作为本轮 Managed 交付的必达项；这些不是仅缺测试，后续应先完成剩余实现再扩展测试。Managed 的命令、工具、文件、恢复、配置权限和客户端能力仍是本轮实施重点。


### 评论关联预算后补矩阵（2026-10-11）

已做核心：memory/PG 超量 mention 和转义正文入库拒绝无残留；PG 4,096 relations 后 correction 整体回滚、Task version/input 不变；mixed mention/route 4,097 读拒绝无部分响应；摘要构建/发布预算两类详情引用。

后补：内存累计 correction 边界及全部 mention/target 128 交叉组合；多连接池并发 correction/complete、4096/4MiB/12MiB 等号边界，metadata/非ASCII 转义及 API 错误映射；索引 EXPLAIN、历史 relation 分页替代视图与热点 p99；订阅者 fanout/附加 outbox 的剩余实现和通知 job 故障恢复。最后两项仍涉及实现，不能只登记为缺测试。

### Managed 资源覆盖后补矩阵（2026-10-11）

已做核心：实际 H2 事务的凭据/Memory/Environment 更新、冻结定义不变、旧版本忽略、同版本内容冲突、授权事务回滚、纯模型空 Environment 与绑定替换拒绝；共享策略刷新及独立加密列；单副本与 PostgreSQL 双副本进程启动、执行、权限刷新和重启。

后补：真实 PostgreSQL 多激活/多刷新竞争与所有提交崩溃点，资源版本变更 outbox 到激活的完整凭据轮换进程链路，旧执行者与新配置交错，1 MiB/多字节边界与迁移前种子升级，小连接池和大量同策略 Session 的读取量及 p99。供应商凭据、Memory 挂载和已有 Sandbox 的 provider/isolation 变更需要先完成绑定语义审计；Session 绑定 PATCH 仍是实施欠项。

### Agent Service 导出后补矩阵（2026-10-11）

已做核心：270 条正文分页、窗口间新增不进入固定水位、Turn 过滤、权限拒绝、批间删除、取消释放槽位；三种 SDK 固定水位/截断拒绝；实际单副本/PG 双副本跨重启和跨副本导出。

后补：长期 Session 及 1,000/1,001 数量边界、单条最大载荷/非 ASCII UTF-8 分块、慢消费者和 60 秒总期限、网络在每行/每窗口中断、全部 SDK 直接连接实际 Agent Service 的导出，跨副本持续撤权和删除竞争、小池查询计划与峰值内存。历史 retention/purge 后缺失事件的明确恢复策略和 Console 导出交互仍需实施。


## Managed Session 预算补测

后续补充：多副本真实 PostgreSQL 子 Agent 并发竞争与进程在 reserve/dispatch/end/settle
各关口退出；修复 claim 到期与慢存储交错；大型原生日志完整增量追赶；价格币种变更、
无报价、部分 usage、费用未知到最终修复；Agent Service 改限与取消/恢复竞争；跨租户/API key
scope 完整权限矩阵；实例槽位耗尽、查询断线及私有服务畸形响应；浏览器预算编辑流程。
当前关键测试及代表性进程验证不等于全量预算容量和故障认证。


## 特权原生 trace 补测

补充真实多副本子Session trace、读取途中角色/Session删除、JWT过期、数据库慢读时
取消和槽位长期上限、单事件及整页JSON转义/UTF-8邻界、固定水位跨写入竞争，以及
Console运维查看。当前关键权限、PG角色撤销、大小上限和实际进程续读已验证；
尚未作为所有部署和敏感trace审计机制的完整认证。

## Checkpoint 元数据读取补测

补充真实PG多副本和子Session分页、大型原生日志稀疏checkpoint追赶、
单条16MiB/整页256KiB和多字节reason边界、固定水位期间写入、
撤权/删除与慢存储取消的槽位检查、三种SDK直接连接AgentService及Console页面。
恢复/fork写链路已接通，代表性验证见实施记录；其完整故障矩阵仍需按下项补充。

## Checkpoint 恢复/Fork补测

补充PG多副本源/目标交错操作、所有命令/核验/原生提交/SQL确认退出点；
实际HTTP提交响应丢失与撤权补偿；原始/目标不同owner/Application全权限矩阵；
未决工具/批准/输入/异步child恢复组合；大型日志和4096/256KiB/16MiB边界；
Console完整恢复交互及SDK进程往返。Service底层原生日志索引现已实现，
仍需检查大型日志的全量projection/Inbox恢复成本及持续并发。

## 2026-10-12 Service SQL 原生日志后补验证

已完成代表性SQL事务/幂等/争抢/封禁与实际H2、PG双副本checkpoint/trace和子工具恢复。
后补：

- 递增长Session的索引计划、存储增长、写入/查询尾延迟；Core projection/Inbox全量
  恢复的CPU/内存成本及checkpoint加速。索引定位有界不代表全逻辑恢复有界。
- PG事务各提交点崩溃、数据库重连/切换、跨主机时钟偏移、租约提交中到期和进程
  freeze后的旧写者；批次收据丢失ACK、重复event ID的并发冲突。
- 多副本业务池+native池的总预算、池耗尽/超时下续租与控制请求、过载后的恢复。
- Unicode/转义放大、16MiB边界、多种日志事件字段极值、null owner诊断；4096后代
  上限及跨根诊断的明确授权和分页，不靠native list越过根权限。
- sql-v1重建切换演练、旧workspace-v1显式拒绝、禁止旧二进制混跑和整套AgentService/
  native/Workspace/ToolWorker结果日志的同批恢复。迁移未转换旧日志。
- 源码进程通过不能代替镜像构建、完整Compose/Helm安装或生产容量结论；全仓
  verify及安装/排空/切流验证仍按后续阶段安排。

## 2026-10-12 Console Managed 维护面板

已有：实际React组件HTTP fixture的稀疏checkpoint分页、未确认恢复同键重试、折叠与
执行页刷新保持状态、命令完成确认及预算版本/精确十进制；console构建与lint通过。
整页reload后的请求/回执保留、旧回执不得覆盖后来请求已补实现及代表性测试。
后补：账号切换/存储不可用/多标签页、fork目标权限与占用、拒绝及长时间pending，
预算412重新加载、read-only/子Session权限、会话删除/撤权，浏览器与实际Gateway/
Agent Service/Data集成、移动端/无障碍及完整视觉检查。不得把fixture当真实后端权限证明。

## 2026-10-12 整套 Managed 与 Console 导出

实际Gateway/Control权限、五个DML账号、Managed/文件/平台ToolWorker/Webhook全流程两轮通过。
一次产物删除后固定快照读取缺少data未记录状态/响应，根因尚未确认；fixture已保留
后续失败诊断。需追加有界重复、固定快照TTL/撤权/删除竞争及小池调查，不通过无条件
重试把故障隐藏。首次Webhook订阅fixture已明确只订阅turn.completed。

实际Control资源冻结与Gateway鉴权下的预算版本、恢复回执、fork及独立目标预算/用量
已完成单独与合并流程验证。首次新增流程的checkpoint查询503根因未确认；后续失败
会记录数据面直接元数据响应及共享策略刷新/失效状态。需要区分正常权限失效窗口、
资源创建outbox延迟与运行服务故障，不把后续通过当作原因已查明。

Console导出核心与实际浏览器下载已做：固定水位、多窗口UTF-8、Session/Turn作用域、
32MiB内存上限、取消前无请求、截断响应无文件；恢复、产物及工具面板回归通过。
后补：真实Gateway浏览器长导出/中途取消/持续撤权、两分钟期限、1MiB/32MiB等号与
Unicode边界、服务断线及全部错误页、下载后对象URL释放、移动端/无障碍。SDK大文件
进程链路和历史retention/purge策略仍未完成，不以浏览器fixture替代这些实施与验证。

## 2026-10-12 dev namespace 首次执行回归

已补 Harness 实例身份的默认 namespace、超长/Unicode owner、作用域隔离、元组边界与
合法旧身份回归。普通登录作用域的实际Managed进程链路重新通过。
后补：dev/default及多种namespace资源创建到首次Turn完成的整套镜像自动用例；构建阶段
失败、native_started=false后显式resume原始输入的进程用例；跨Data重启与重复resume
幂等检查。元数据ready与健康检查不能替代首次模型调用/ToolWorker实际执行验证。

已有导出源身份对齐、当前代次迟到完成修复、旧代次不得结算新代次、已取消不改写、
无原生完成证明拒绝及额度不重复释放已补回归。用户真实Session的已提交尾部重放完成，
native游标追平且用量未增加。仍需补批量历史源修复/异步导出竞争、恢复与迟到完成并发、
多个Data副本丢ACK/撤权/池耗尽组合及批次大小边界；不以单次手工修复覆盖这些场景。
新增普通登录默认namespace的完整进程fixture，预置旧导出身份后首次Turn完成及游标
追平已通过。dev免登录的整套镜像自动用例仍待补。

## 2026-10-12 流式公开事件顺序

已补工具请求先于挂起、waiting不得伪造结果、实际成功/失败结果先于动作解除、事实间
丢ACK重放、确认批准不伪造工具完成、上下文替换/压缩区分、同消息finalized更新及
Console等待状态回归。实际两工具的Control/Gateway/Agent Service/Data/ToolWorker链路及SSE/快照
一致性通过。后补多工具并行/分批结果、子Session结果双事实故障、撤权/取消竞争、
多个exporter同时重放、真实压缩与失败/无变化数量边界、Middleware最终消息修订和
浏览器跨版本缓存。self_hosted工具stdout逐块推送仍为后续实现，不由本轮修复补齐。

## 2026-10-12 独立 Session Playground

已有：UTF-8逐字节/CRLF/多行data/截断尾帧、已应用游标续接、preview不推进游标、
错误Session及归并失败停止、空闲流abort、snapshot用量格式；浏览器fixture的工具
waiting→实际结果→同消息finalized、断开观察不发POST、丢失POST响应后reload原键
原内容重试、桌面/手机无横向溢出。独立构建及新增源码lint通过。实际本地Gateway
Session快照与SSE心跳已做只读浏览器验证；这些不代替真实模型端到端验证。
后补：真实Gateway浏览器发起Turn到Managed/ToolWorker完成、SSE中途断网/代理重启及
快照刷新/分页并行竞争、撤权/429退避、snapshot TTL410、4096事件/16MiB显示与
1MiB单帧边界、大量历史资源的性能、多标签页/账号或Namespace切换、POST超时与
服务端受理竞争、浏览器存储/剪贴板不可用、完整无障碍。当前工具stdout仍取决于
后端实际事件能力，不通过网页虚构逐块工具输出。Cancel/Resume/动作应答、文件
和子Session读取界面暂不在这个简化工具中实现。

独立小程序现已迁至 `managed-agents/tests/session-playground/`，依赖、协议类型、
归并器、源码和浏览器测试均在该目录；无主console源码导入。迁移后独立npm ci、
构建、17项核心测试、2条浏览器测试及lint通过，生产console的专用入口已移除。

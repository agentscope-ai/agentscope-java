# Agent Service 状态与顺序契约

本文件固定重构后的状态语义，供 P2–P7 的实现及测试共用。标注为“待接通”的流程尚未完成实现，不能据此宣称已经验收。公开状态与执行状态各有权威来源，通过持久化命令和事实同步。

## Session 创建与生命周期

Agent Service 注册表的创建状态为 `provisioning → ready` 或 `provisioning → failed`。创建过程不启动 Turn；只有数据面返回与 Session、commandId、配置 revision 和配置摘要均匹配的已提交回执后，才能返回可用的 Session。响应丢失时重试相同命令；同一命令携带不同配置返回 409。已提交配置的回执可在授权过期后重放，重放不创建或修改执行配置；首次提交必须持有尚未过期的准入授权。

Session 的公开生命周期与上述创建状态分别保存：

| 生命周期 | 可读 | 接收新 Turn | 允许操作 |
| --- | --- | --- | --- |
| active | 按权限 | 是，另需检查队列、额度及调用身份 | 交互、取消、归档、删除 |
| archived | 按权限 | 否 | 恢复、删除 |
| deleted | 普通公开请求返回 404 | 否 | 当前 read/interact 授权下查询已有生命周期命令或原键重试；内部排空和保留策略处理 |

归档或删除前必须处理仍占用该 Session 的 Turn；请求删除不能被迟到的 running 事实恢复为 active。SSE 断开只关闭连接。Agent Service 权限投影刷新、数据面 Session 执行租约和 Tool Worker 工具租约独立续租。

创建、快照持久化、回执重试和归档/恢复/删除已接通。生命周期按 Session 递增 revision，与公开事件和命令 outbox 同事务提交。归档/删除要求 interact，恢复要求 invoke；有效预留或未结束 Turn 导致 409。数据面持久化独立镜像，旧版本仅返回 superseded；冻结配置与 bootstrap 重放不能复活 Session。生命周期 courier 可在原准入到期后完成已提交期望状态的投递，不能据此授予模型执行权。删除当前为 tombstone，物理保留/回收在 P8 实施。

## 公开 Turn 与实际执行

| Turn 状态 | 来源及含义 | Application 并发占用 | 同 Session 的下一普通 Turn |
| --- | --- | --- | --- |
| queued | Agent Service 已原子受理，尚未实际开始执行 | 占用 | 等待 |
| running | 有效执行者已开始当前激活 | 占用 | 等待 |
| requires_action | 等待批准或工具结果；推理暂时退出不等于逻辑 Turn 完成 | 占用 | 等待 |
| cancel_requested | Agent Service 已受理取消，执行器尚未确认停止 | 占用 | 等待 |
| interrupted | 执行权丢失或显式中断；需要用户明确恢复或取消 | 不占用 | 阻塞，直到明确处理 |
| failed | 已确认失败，保留显式恢复入口 | 不占用 | 阻塞，直到恢复或取消 |
| completed | 最终 item、usage、动作状态及终态均已提交 | 释放一次 | 可运行 |
| cancelled | 未启动任务已撤销，或有效执行者确认停止 | 释放一次 | 可运行 |

允许的主转换为：queued → running/failed/interrupted/cancelled/cancel_requested；running → requires_action/completed/failed/interrupted/cancel_requested；requires_action → queued/cancel_requested/cancelled；cancel_requested → completed/cancelled/interrupted/failed；interrupted/failed → queued/cancelled。启动前的授权拒绝、构建失败或执行者丢失可以形成 failed/interrupted。取消受理与已完成提交竞争时，已经提交的完成事实仍可形成 completed；取消请求不追溯撤销已经完成的操作。cancel_requested 期间迟到的 running/requires_action 事实不撤销取消状态。终态不得被旧执行者或迟到重复事实回退。已经 completed 或 cancelled 的 Turn 不能恢复。

暂停后的工具结果或人工答复继续同一个 turnId，但可以产生新的原生执行激活和新的 execution fence。显式恢复重新检查权限及额度；如果原 reservation 仍有效不得重复占用，如果已经释放则需重新取得占用。reservation 与一次状态转换的收据去重，不能依赖 Agent Service 进程内回调释放。

Application 的占用与数据面实例的实际执行槽位分开。等待外部动作的 Turn 仍占 Application 额度，却不应永久占用实际推理槽位。预算按已报告用量在后续模型调用前检查；不同 Session 或子任务的在途调用可以超额，当前机制没有固定超额上界。

## 命令、原生输入与事件顺序

| 对象 | 状态或确认关口 | 权威来源 |
| --- | --- | --- |
| TurnCommand | queued → delivered → runtime_accepted → applied；失败有明确 rejected/failed | Agent Service 受理和 outbox、数据面 Inbox 各自持久化；delivered 不代表 started |
| steer InputReceipt | queued → accepted → applied/rejected | 数据面在运行时接收锁与终止锁下确认，之后由原生 Inbox 消费；Agent Service outbox 不生成 accepted |
| inject InputReceipt | queued → accepted → applied/rejected，turnId 为空 | 数据面原生 Inbox；不创建、绑定或启动 Turn |
| cancel Command | queued/runtime_accepted 与 Turn cancel_requested 分开；applied 表示已确认停止 | Agent Service 命令记录及有效执行者的停止事实 |
| approval/tool result | 校验动作 ID、任务代次及调用身份，重复返回原回执 | 动作或结果 Inbox；只有第一次有效消费进入推理上下文 |

同 Session 的普通 Turn 按准入序号串行。控制命令、结果和续租拥有独立处理额度，不能排在普通 Turn 队尾等待。steer 由任一数据面副本在共享原生 Inbox 的接收锁中校验目标 Turn、有效 writer 和准入期限，执行副本从同一 Inbox 消费。实例变更和超时使用同一 inputId 恢复回执或重试，避免产生两份输入。

Managed inject/steer 投递期间的明确权限拒绝或生命周期关闭，转换为过期的内部准入期限，由数据面查询既有原生回执或原子提交 accepted/rejected 两条 Inbox 记录。过期拒绝不依赖文件内容读取或已失效配置；Runtime interaction 的原始 actor、scope 和 payload digest 仍必须一致。既有接收不被后来的拒绝撤销；临时权限源 503 不转换为拒绝。拒绝及接收记录在一个 journal commit 中提交，不存在可供执行器消费的中间接收状态。

取消的 runtime_accepted 只确认执行代次上的持久化停止请求。未进入原生执行的任务通过已登记取消意图和禁止后续激活的状态确认撤销；已经运行的任务使用 run fence 中断，原生停止事实确认后才释放占用。空闲任务使用原生 writer 锁进行恢复取消；无法确认的工具副作用继续等待核对。对于已释放额度的 failed/interrupted Turn，取消进度记录在命令中，Turn 保留原状态直到关闭事实，不重新占用推理额度。命令的 command_status=applied 表示已有停止或原先完成的事实。

Agent Service 同一事务提交 turn.accepted、命令 outbox 和额度占用。数据面实际激活前持久化 started 来源事实；Agent Service 发布 accepted 后才能发布对应 started。每个后端源按自己的提交位置递增，源位置之间不能直接比较。公开 seq 由 Agent Service 按 Session 分配，子 Session 各有自己的 seq。终态事实必须包含或引用已提交的最终结果、用量和动作状态。

Turn 准入、实际执行、事实、公开资源/SSE、inject、steer、取消与显式恢复已接通；原生人工批准与外部结果已接通；Tool Worker ToolTask 和两类 Worker 的基本结果闭环已接通。现有 Harness 原生恢复机制继续复用。

每次同一 Turn 的显式恢复拥有独立 activationCommandId，关联原始 Turn 命令和恢复命令。Agent Service 先复核原始调用者及恢复发起人，重新获得已释放的 Application 占用与有界排队额度，再提交恢复意图。恢复不创建新的 Turn 或准入序号；后续授权仍复核这两种身份。运行时先持久化恢复意图，正常执行消费者随后分配新代次和 Session 租约；旧激活事实只进入历史，不能释放恢复的额度或修改当前状态。

原生 run 的授权身份不可变，独立于当前恢复状态。运行时保留上一个原生 run，恢复尚未开始时的取消也会在 writer 锁内关闭原生未完成 Turn；行政 writer 沿用原生 run 的旧授权，新恢复的取消结果另外确认当前意图。已完成的原生任务不能作为未开始的恢复重新提交，已经未知的工具结果也不自动重跑。授权尚未成功时，数据面已分配的代次允许暂时领先 Agent Service，但对应激活命令必须一致。

## Go 任务与 Attempt

Go 内部 AgentTask 保留 queued、dispatched、running、waiting、completed、failed、cancelled。ExecutionAttempt 保留 queued、assigned、preparing、running、waiting、cancel_requested、succeeded、failed、cancelled。Agent Service 映射 waiting 为 requires_action，succeeded 为 completed；assigned/preparing 只能作为执行准备信息，不能假称模型已经开始输出。

一个公开 Turn 可以关联多个子 AgentTask 或 Attempt。内部 taskId、attemptId、dispatchGeneration 和 Host lease 继续用于内部恢复；它们不替代公开 turnId。迟到的 Host 结果须先通过对应 Attempt 的代次和租约检查，再生成事实。

## ToolTask 与工具结果

ToolTask 使用 queued → claimed → running → succeeded/failed/cancelled/unknown。claim token 保护当前 Worker 的执行权；authorizationGeneration 保护这一次工具请求的授权。二者均独立于 Harness 每次激活的 execution fence。

执行器在副作用开始前保存 running 记录，工具结束后先保存结果及结果 outbox，再上报。暂停后恢复出的 Harness 可以消费原工具结果；取消、替换请求或重新执行工具会废止旧任务代次。结果落库、原生输入消费及其回执各自去重，收到重复结果只返回原处理状态。

租约丢失时，尚未开始的任务可以重新认领。已经开始且结果未知的非幂等工具进入 unknown，不自动重跑；read_only 或明确 idempotent 的任务按有界重试规则处理。人工恢复必须明确选择提交已有结果、终止请求或创建新授权代次重新执行。

ToolTask、Worker journal、有限 Tool grant、claim/start/renew/result 和结果 courier 已接通。公开 `GET /api/v1/agent-sessions/{session}/tool-tasks` 按 Session/Turn 和 keyset cursor 返回进度，不返回 Worker token、工具输入或 Sandbox 凭据。known result 可以通过已有 actions 接口提交，cancel 终止 Turn；`POST /turns/{turn}/tool-recovery` 使用 interact 权限、幂等键、task_id、authorization_generation 和 confirm_side_effects=true 明确请求重新执行 unknown 工具。回执区分 queued、runtime_accepted、command_status=applied/rejected；应用只增加工具授权代次，不启动新的 Harness 激活或重复预留 Turn 配额。原调用者 invoke 仍在实际认领时复核。

共享 Sandbox 绑定和 Task claim 分离。self_hosted 本地 scope 不随 Task 过期迁移；托管 scope 一次只开放一个工具操作，binding 创建操作先持久化，再调用供应商。重复 acquire 不再发 create，已知迟到 provider ID 可以修复 unknown 创建；连接失败不隐式创建。unknown/unavailable 绑定需要供应商恢复，不能仅通过 tool-recovery 重跑工具。不可变共享归档、已确认 404 后重建、空闲回收及显式绑定修复已接通；部署继续实施。

## 原生动作答复

公开 actions 命令按请求 ID 查找 Agent Service 待办，内部命令携带可信调用快照；运行时按原生待办再次核对 Turn、动作种类和完整工具参数。批准 requires approve 的人类身份，外部结果 requires interact；混合答复需要两种权限。答复发起人的权限在实际激活前复核，同时保留原始 Turn 的 invoke 身份。

动作先持久化为独立激活并进入 queued；runtime_accepted 只确认运行时已接收。原生开始事实仅保留 runtime_accepted；每条 required_action.resolved 确认消费，整批请求都确认后才 applied。失效待办或参数不匹配通过 action_rejected 事实确认 rejected，并结算本次预留。重复键与相同答复返回原回执，不同内容冲突。requires_action 仍占 Application 额度，但本地等待批准不阻塞推理执行线程。

多个外部工具允许分批答复。每批有独立激活和回执，原生 Inbox 消费已到达结果，并保持剩余待办；尚缺结果时继续 requires_action，不重新执行工具或提前调用模型。已消费请求通过同一幂等键查询原回执，新键重复答复拒绝。全部结果到达后在同一个 Turn 的下一激活继续推理。

取消使用 cancel 权限，不要求当前 invoke 权限。撤销启动模型的权限后，仍有 cancel 权限的原用户可以提交及投递停止命令；resume 继续要求 invoke，人工批准和外部结果分别要求 approve 与 interact。

## 共享权限与变更传播

公开 Managed 请求通过 scope_id 引用共享策略。作用域包含 tenant、namespace、Application、原调用者、冻结目标版本与定义、选择的 Memory/Vault/Environment 和 Agent overrides；Session、事件与输入文件不参与。权限读取及列表以共享策略的 valid_until 为准，不因新 Session 加入、刷新失败或通知重投延长有效期。

控制面的权限相关更新与 outbox 同事务提交。每个来源的事件采用唯一 ID、claim token 和有限租期，Agent Service 先持久化收据再 ACK；已受理通知以索引和有限批次使共享策略失效。失效撤销旧刷新 claim，后续迟到的响应不得覆盖。有限有效期处理延迟通知、通知与初次快照的竞态和分页后新插入的策略，不承诺同步即时撤权。过期返回 503，已有 SSE 在定期 read 授权检查中终止；客户端可以按原游标在恢复后重连。SSE 断线不会改变任务的存活语义。

运行授权仍有独立有限期限及执行 fence。新操作和授权续租重新检查权限；已经发出的模型/工具调用需要按原有取消和停止事实核对。当前策略刷新只更新授权，Agent 定义保持冻结，动态凭据与挂载覆盖层仍在实施。


### Sandbox 归档与回收状态

工具运行前标记 Workspace dirty；已知结果先写入 Worker journal，文件归档成功并以当前 binding generation/operation token 提交后才清除 dirty。公开 Task 查询返回 workspace_status 和 workspace_snapshot_pending，不返回归档绝对路径、provider ID 或凭据。

默认空闲 5 分钟后回收，Environment 的 sandboxIdleTimeoutSeconds 可以覆盖；sandboxMaxLifetimeSeconds 默认 3600 秒，两项设为 0 分别关闭对应边界。active、无 operation token、dirty=false 且存在已确认归档的绑定才可认领为 retiring；清理使用独立 maintenance token/lease，阻止同 scope 新工具进入。删除超时保留同一 provider ID，退避重试其删除；已知成功或 404 后清空 provider ID、增加 generation、恢复为 reserved，保留文件归档。旧清理确认不能覆盖替代租约或新绑定。最长保留时间也只在安全操作边界轮换，未知副作用与未归档文件不能按定时器清除。运行中的工具独立保活，不因空闲或最长保留配置被主动删除。真实 Worker 加 fake E2B 的清理闭环已通过。

### Workspace 人工恢复

POST /api/v1/agent-sessions/{session}/turns/{turn}/workspace-recovery 要求 interact、幂等键、task_id、authorization_generation、workspace_generation 和 confirm_side_effects=true。reconnect 还要求 confirm_provider_idle=true，可以补充已核实的 provider_id；restore/discard 要求 confirm_provider_absent=true 和 confirm_data_loss=true。restore 必须有已确认归档，discard 明确丢弃旧实例文件并预备新 Workspace，历史不可变归档仍保留。修复采用绑定代次检查，不能替换其他工具正在使用的绑定；共享 scope 的其他 Session 会观察到同一次绑定修复。

应用命令增加 Workspace 代次，并废止没有已知结果的旧工具授权。unknown 工具仍为 unknown，须单独通过 tool-recovery 确认重试；恢复绑定不自动重复副作用。旧 Worker 的结果和绑定提交会被代次检查拒绝。reconnect 的进程崩溃、幂等重放及随后显式工具重试已通过；restore/discard 的完整进程矩阵后补。


### Managed 子 Session 事实

原生 subagent/spawned 提交后，导出器在推进游标前登记不可变的直接父节点、根 Session、owner 和 log agent 关联；公开映射沿用 subagent.started。查询通过 root_session_id 和 Session 主键索引查找，不遍历父日志。子调用取得独立 SessionExportSink，先有限批次导出父前缀，再开始子执行，确保父子登记事实先进入持久化 outbox。standalone 默认不隐式继承父 sink；自定义 sink 可以覆盖 forChild。

子原生 run 通过 subagent/linked 的 parentRunId 继承根 Turn 已持久化的原调用者、激活代次和 fence。子事件作为 subagent.event 内部事实传送；Agent Service 为其分配独立 Session 公开 seq 和资源视图，根流只保留子进度及模型用量。子终态不转换根 Turn，也不结算根 reservation。导出补偿仅认领索引中的 child_export_pending，有限批次重放；确认 run/end 已导出后才移除待补偿标记，不能在 turn 终态时提前忽略末尾 run 事实。

子 Session 的读取复核根 Session 的当前 read 授权和生命周期，根 tombstone 后普通子读取也为 404；子视图没有独立管理权限或 Application 预留。现有 /subagents/{child}/snapshot、/resources/{kind}、/events 和 /events/stream 由 Agent Service 提供，复用固定快照与有界 SSE；游标绑定子 Session。直接对子 Session 提交普通 Turn、生命周期和交互操作返回 409，子操作将通过根 Session 的明确关联控制。

子模型调用在 Application 中仅计一次；模型调用 ID 带子 Session 命名空间。根 Session/Turn 汇总包含子用量时标为 session_subtree/turn_subtree；子视图继续显示自己的 session_only 用量。模型完成与用量重复投递不增加第二次计数。

同步子调用按父原生 Session、Turn 和工具调用 ID 持久化子执行身份与声明。子工具等待使父 agent_spawn 请求同时暂停，释放实例执行槽位，继续保留根 Application 预留。对子工具提交答复使用根 `POST /turns/{turn}/actions`，在 answer 中增加 `child_session_id`；Agent Service 校验根授权和关联，数据面再次校验子原生 Turn、待办种类及工具输入。Tool Worker 的任务身份包含 native_session_id/native_turn_id，相同请求 ID 在不同子 Session 或原生 Turn 中互不冲突；执行权限和 Workspace scope 仍来源于根 Session。

每批子答复取得根 Turn 的新激活授权。子续跑先提交根 running 事实；部分结果仅关闭对应子请求，仍为 requires_action，不能提前调用模型。子最终结果提交后逐层关闭原父工具请求并恢复父推理；父 agent_spawn 结果由运行时生成，不能用普通 actions 手工覆盖。子原生状态与动态声明在重启后由数据面加载，Agent Service 快照不能用作推理恢复状态。批准和外部结果的身份规则与根动作相同；子批准的完整进程矩阵仍需补齐。

同步子调用具有实例并发上限 `managed-agents.runtime.max-active-child-calls`，默认 64。达到上限时在开始子执行前返回工具失败，父 Agent 可明确重试；不会创建一个无法自动继续的虚假等待。取消按索引分批检查子 writer，关闭子原生待办并确认导出后才结算根取消。准备但未开始的子调用可撤销；尚有活动 writer 或未核实工具副作用时保留 cancel_requested，不能提前释放根预留。真实 Agent Service、数据面和 Tool Worker 的跨进程 smoke 已验证部分答复、父结果恢复及重启后的子取消。显式恢复也可读取已提交的子结果，补齐尚未关闭的父工具，避免重复执行子任务。

异步子任务恢复、remote 子事实、显式恢复中断的子续跑、准备阶段孤儿管理和持久子 Session 多 Turn 的并发/恢复仍需完善，不能据此标记完整子运行机制完成。


## Team 明确完成的事务边界

`run.node.complete` 在结束物理 leader Task/Attempt 前保存完成意图。意图在 Task
行锁下写入，尚未接受时可修改；协调完成必须在 Task 完成事务中核对同一输出并把
意图标为 accepted。普通 Task 完成不接受该意图。已接受的输出不可改写，重放比较
规范化 JSON，保留大整数精度。物理完成后新增的明确节点完成请求也可以接受意图。

完成节点时，Run 行锁与委派、复用节点、输入合并、新建图节点和子 Issue 写入共同
构成事务边界。锁内完整复核 Task、Node、直接子 Issue 及 Leader 输入存在性，再把
coordinator 节点成功与 node.succeeded 事件一起提交。完成使用 NOWAIT，短暂占锁
返回可重试冲突，不等待 Task/Issue 写入持有的 node 锁。运行恢复只重试已接受的明确
完成意图，不能用普通 leader 文本替代它；每次恢复都重新检查屏障。

Adaptive Team 检查整个 Run；Workflow 内声明的 Team 检查其 Task parent 子树，
不会因同 Issue 的另一个 Team 已完成而阻止仍开放的 Team 委派。没有 assignee 的
子 Issue 也属于 Adaptive 完成义务。子 Issue 在已有 Run 中重新打开或变更 AgentTask
来源也参与相同 Run 锁；终态 Run 上后续的人类工作不复活原 coordinator。已关闭的
AgentTask 来源不能继续创建子委派，Task parent 检查最多 256 层并拒绝循环。

合并新输入会推进 queued Task 的版本。完成阶段只能取消空的重复 leader；旧版本
不能取消已经接收新输入的任务。最终人类可见摘要在屏障成功后发布，稳定 ID 用于
重放和终态修复。coordinator 成功仍不代替物理执行停止、根 Turn 终态和业务验收。

## Agent Service 公开事件导出

Session 与 Turn 的 GET export 只读取 Agent Service 公开日志，不读取原生 checkpoint 或特权
trace。after/through 使用原 Session 游标；首次请求固定 through 水位，后续窗口必须
保留该水位。每个窗口最多 1,000 条，先读取至多 limit+1 个序号，再以每批 16 条读取
正文；每批复核 read 权限。并发槽位与 SSE 独立，默认 16，窗口总期限 60 秒。

X-Export-Through、X-Next-Cursor、X-Has-More 和 X-Export-Count 声明窗口边界及数量。
只有完整收到并验证窗口后才能推进 next cursor；失败或提前停止时从最后完整应用的
事件 cursor 重试。新增事件不会进入既有水位。Turn 导出过滤同一日志，允许跳过其他
Turn 的序号；结束时 next cursor 达到 through。客户端拒绝半行、数量不足及水位变更，
不能把截断输出作为完整导出。权限失效时已授权取得的一批可以结束发送，下一批拒绝。


## Managed Session 预算

Agent Service 持久化根 Session 的期望限制与单调配置版本，PUT 202 表示设置已保存；
If-Match 对比当前版本，旧版本返回 412。数据面每次模型调用前通过根执行 grant
获取设置并在共享配置行锁下应用，旧版本不回退，同版本内容冲突拒绝。
GET 返回期望 limits/revision、native_limits/configuration_revision 和已报告 usage。
子 Session 读取根预算，只能在根 Session 修改；其他后端显式不支持预算能力。

数据面以逐调用收据和恒定大小汇总记账，根及子调用竞争同一 Session 预算锁。
模型调用次数在准入时计数；token/费用按已报告用量检查，未预留猜测用量，
在途并发可能超额且无固定超额上界。未知用量阻止后续受 token 限制的调用；
费用约束要求当前及历史已报告用量能够计价。首次最终结算保存费用贡献，
重复收据不重复计数或按新价格重算。失败 Turn 不因提高额度而自动重新执行。确认未 dispatch 的模型准入仍计入调用次数，
但费用为零且无需历史报价，不会因没有供应商调用而制造未知费用。

后台修复按已索引的到期调用认领，每根每次最多 32 条，每条最多 64 原生事件，
保存扫描位置和短期 claim lease；结果 ACK 丢失可重建并继续修复，无需扫描完整
Session 或所有后代。dispatch 不确定时保持 unknown，不自动重试外部模型调用。
预算 GET 的跨服务读取限制槽位、响应尺寸与超时，读取前后重新检查原始调用者权限。

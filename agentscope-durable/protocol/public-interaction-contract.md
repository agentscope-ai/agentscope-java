# Durable 对外交互协议：用户操作、消息与事件

[English](public-interaction-contract.en.md)

机器可执行格式见 [schema.json](schema.json)，应用接入见
[Java 使用指南](../../docs/dev/zh/docs/harness/durable.md)。只支持当前契约，不保留旧入口、
格式转换、双协议或兼容分支。版本不匹配明确拒绝；幂等、fencing 与恢复保证不因此削弱。

## 1. 统一概念与生命周期

用户 API、Input/Message 和 PublicEvent/Snapshot 共同构成语言无关交互协议。
Java 是它的绑定；其他语言可使用自己的 Future/Promise、回调或迭代器，但不能改变
同一操作的作用对象、状态约束、身份、幂等、事件和错误语义。

- SessionKey = namespace + sessionId。Session 保存会话元数据、生命周期和 Thread 目录；创建时原子建立 `main` Thread。
- ThreadKey = SessionKey + threadId。ThreadBinding 冻结 harness/definitionVersion/stateVersion；Thread 独立持有上下文、FIFO、checkpoint 和执行租约。同 Session 的不同 Thread 可以并行。
- Session / Thread 使用 OPEN/CLOSED 生命周期；有未终结任务时不能关闭。关闭父 Session 后不能新增 Thread、提交任务或恢复 checkpoint，历史仍可查询。
- DurableTurn 表示持久逻辑任务；Run 表示其中一次原生调用，Step 只属于一个 Run。
  原生 AgentRun 与持久 Run 记录使用同一个 runId；重启续接保留 turnId，使用新 runId/stepId。
  暂停先持久化意图，PAUSED 确认已到达安全边界；恢复需要观察到的版本。
- requestId 标识提交或控制。turnId/runId/stepId/messageId/eventId/interactionId/executionId 各有自己的范围，不能互换。
- Session “空闲”从队列和 Turn 状态推导。QUEUED/RUNNING/WAITING/PAUSING/PAUSED/CANCELLING 不是终态；
  SUCCEEDED/FAILED/CANCELLED 才是终态。TurnView.output 为公开 JSON，AgentScope 输出标准 Message。
- `DurableRuntime` 是 Java 唯一装配、业务操作和生命周期入口，直接创建、查询和列举
  Session。Session/Thread/Turn 是可重新查找的身份句柄。
- API-only Runtime 不需要 Agent/模型/Executor，不扫描或认领任务。配置执行能力并显式 start
  才启动 Brain；无执行配置时报错。关闭 Runtime/订阅/Future 不取消任务，不关闭借入的基础设施。
- Managed Agents 和其他应用使用同一协议。Durable 不依赖平台服务或平台私有语义。

公开用户请求、特权 Backend 请求、Hands ExecutionMessages、原生 JournalEvent 是四个不同边界。
不能把公开事件当作待执行命令，也不要求所有用户请求经过 MQ。

## 2. 信封、作用域与操作目录

协议标识为 `durable.v1`。请求 `{version, operation, payload}`；成功响应 `{version, result}`；
错误 `{version, code, message}`。字段、null、可移植数字和允许事件由同一 schema 校验。
未知字段/操作/保留类型及版本不匹配拒绝；重复 JSON key 拒绝。摘要使用 RFC 8785 和
[vectors.json](vectors.json)，不能先有损转换数字再计算身份。

公共 HTTP 只接受 `userRequest`，不暴露 claim、commit、Frame、对象内容或执行结果写入。
下面所有分页 limit 均为 1..1000；cursor 必须显式为字符串或 null。列表游标与事件游标不同。
列表游标绑定查询范围，不提供跨页快照隔离；查询期间的新受理项可能在后续页出现。

| operation | payload（字段均为必填，除 schema 明确可选项） | result |
| --- | --- | --- |
| capabilities | `{}` | protocolVersion、operations、已注册 definitions、checkpointHarnesses |
| createSession | session、definition（注册名称）、metadata（可为 null） | SessionView；原子创建主 Thread 并冻结其绑定 |
| getSession / closeSession | session | SessionView；查询缺失为 NOT_FOUND，不隐式创建 |
| listSessions | namespace、cursor、limit | SessionPage(items, cursor)；按 namespace 限定 |
| createThread | session、threadId、definition | ThreadView；固定该 Thread 的定义版本 |
| getThread / closeThread | session、threadId | ThreadView |
| listThreads | session、cursor、limit | ThreadPage(items, cursor) |
| listTurns / history | session、threadId、cursor、limit | TurnPage / HistoryPage；按该 Thread 的受理顺序 / 消息首次提交顺序 |
| submit | session、threadId、requestId、input | TurnReceipt(thread, requestId, turnId) |
| getTurn | session、threadId、turnId | TurnView |
| getRun / listRuns | session、threadId、turnId、runId / cursor、limit | RunView / RunPage；一次实际执行的只读记录 |
| getStep / listSteps | session、threadId、turnId、runId、stepId / cursor、limit | StepView / StepPage；限定所属 Run |
| getModelCall / listModelCalls | session、threadId、turnId、runId、modelCallId / stepId（可为 null）、cursor、limit | ModelCallView / ModelCallPage；只读请求来源与用量 |
| control | session、threadId、command | CommandReceipt(requestId, turnId, operation, status, cursor) |
| interactions | session、threadId、turnId | 该 Turn 的 pending Interaction 列表 |
| snapshot | session、可选 filter | 与 sequence/cursor 一致且按范围过滤的 Snapshot |
| events | session、cursor、limit、可选 filter | EventPage(events, cursor, highWatermark) |
| checkpoints | session、threadId、after、limit | CheckpointMetadata 列表；after 是 Thread checkpoint 位置，非事件游标 |
| restore | session、threadId、position、expectedPosition、requestId | CommitReceipt |
| fork | session、threadId、position、target（ThreadKey）、requestId | ThreadView；目标为同 namespace 的其他 Thread，继承源冻结绑定 |

创建同身份/同参数幂等，改变定义或会话 metadata 冲突；关闭后重试原创建请求不会重新打开对象。
提交和控制共用 Session + requestId 去重范围，摘要包括目标 Thread/Turn、操作和内容；改变任一项 CONFLICT。
只凭 SessionKey/threadId/turnId 可跨进程重新查询；读操作不认领、不执行任务。
Session 的 submit/call/stream 是主 Thread 的快捷入口，归一为同一 Thread 操作。
Session 的 getTurn/listTurns/history 也默认作用于主 Thread；指定其他 Thread 时必须先取得
对应 Thread 句柄。协议请求始终携带显式 threadId，Session 快捷入口不会改变存储作用域。
创建 Session/Thread 需要 Runtime 的 definition/definitionResolver；查询已有对象不需要本地 Agent。
fork 可在已有 Session 中创建目标 Thread，不隐式创建目标 Session；跨 Session fork 须先显式创建目标会话。
列表按 namespace 授权，所有 Session 操作按身份授权，fork 分别授权源与目标；游标不代替授权。

模型调用是 Run/Step 下的诊断记录，不增加调度或控制对象。`model.call.started/ended` 记录逻辑调用，
`model.request.started/usage/ended` 记录可观测到的独立请求；均通过同一 commit 与 checkpoint、原生事实和公共事件原子保存。
每次传输重试有独立 requestId 和递增 attempt；结果回放不创建新调用/请求，查询原 Run 的历史。
ModelCallView 的 usage 汇总各 requestId 已知的 token 数，包括失败请求已报告的部分；只有全部请求的输入/输出总计完整且调用成功时 usageComplete 才为 true。
缺失计数为 null，不填零；details 是输入/输出 token 的明细子集，不能重复加到总量上。这些记录不推断金额或服务商最终账单。
PROVIDER_ATTEMPTS 表示共用请求边界的可观测重试；MODEL_CALL 表示自定义模型只暴露逻辑调用，requests 为空不表示没有外部请求，usageComplete 保持 false。
不透明 SDK 内部重试超出该边界；没有结束事实的调用/请求在所属 Run 结束或失联后查询为 LOST，保留已知部分，不虚构结束时间或完整用量。
可空 stepId 用于尚未进入 Step 的辅助调用，不生成占位步骤。列表游标绑定 Thread/Turn/Run/Step 范围；这些查询和事件不携带模型原始输入或私有推理。

## 3. 类型化控制与状态约束

`ControlCommand` 必须包含 turnId、requestId、kind、target、value、expectedRevision。
不适用的 target/value 为 null，expectedRevision 除 RESUME 外为 0。Java 方法隐藏这些内部填充值。
以 Session + requestId 去重，摘要覆盖目标 Thread 和整个命令；相同请求返回最初回执，内容变化 CONFLICT。
校验、信号、用户消息和回执在同一权威事务中提交，不依赖客户端先读后写。

| kind / Java 方法 | target / value | 状态约束与效果 |
| --- | --- | --- |
| STEER / `turn.steer(requestId, Input)` | null / Input | 仅活动非终态 Turn；下一原生 reasoning 安全边界应用用户指令 |
| INJECT / `turn.inject(requestId, Input)` | null / Input | 同上，注入上下文；不提升为 system 权限、不承诺即时打断模型 |
| WITHDRAW / `turn.withdraw(requestId, signalId)` | 原控制 requestId / null | 必须存在未应用信号；与应用竞争，已应用 CONFLICT |
| CANCEL / `turn.cancel(requestId)` | null / null | QUEUED 立即取消；活动任务进入取消流程，核实后终态；已终态保持原结果 |
| RESPOND / `turn.respond(requestId, interactionId, response)` | Interaction.id / JSON | 校验同 Turn、pending、非 automatic；confirmation 为 boolean，external_execution 为 JSON |
| PAUSE / `turn.pause(requestId)` | null / null | 无活动所有者时直接 PAUSED；活动执行先 PAUSING，在安全边界交接后 PAUSED |
| RESUME / `turn.resume(requestId, expectedRevision)` | null / null | 只继续 revision 匹配的 PAUSED；未满足的工具/交互/信号条件仍保持 WAITING，不重启终态 |
| SIGNAL / `turn.signal(requestId, key, value)` | `x.*` key / JSON | 高级扩展；不能冒充 input、interaction 或框架内部 key |

除 CANCEL 及原请求重试外，终态/取消中的控制返回 CONFLICT。重复审批的新 requestId 也被拒绝，
直到原响应被合法撤回。用户响应不能覆盖自动 Worker 结果。普通客户端无需拼接内部信号 key；
Control.requestId 同时是 signalId，Interaction.id 是响应目标，Interaction.requestId 标识原生请求。

控制回执 status=`accepted` 只表示已受理，cursor 是该事务后的 Session 位置。
后续 `signal.applied/withdrawn`、`interaction.resolved`、Turn 终态说明执行事实。
SUCCEEDED 提交必须确认已受理的 steer/inject 均已应用或撤回。输入受理与成功提交在同一事务边界竞争；先受理的输入不能随调用正常结束而丢失，先完成的 Turn 拒绝新控制。AgentScope 在需要继续时保持 turnId，使用新 Run 消费输入。
FIFO 由 Backend 在每个 Thread 内的受理顺序定义，WAITING/PAUSING/PAUSED 不允许同 Thread 的后续任务越过；取消排队任务不需要等待队首完成。
PAUSE 的回执只确认意图已受理，需观察 PAUSED 才确认调用停止。暂停不取消已发出的 Hands 操作；
工具结果、信号和交互答案仍可持久化，但不能自动恢复。RESUME 不再接收 waitingKey，也不生成唤醒信号；
它只解除显式暂停，重新检查原等待条件。暂停与完成在同一事务竞争，取消后不能再恢复。

## 4. 操作—消息—事件闭环

Input 为 `{schemaVersion, content[]}`，Message 为 `{schemaVersion, messageId, role, content[]}`。
内容支持 text/image/audio/video，媒体 source 为 URL/base64；URL 是引用，不承诺已上传或可访问。
PublicEvent 包含 schemaVersion/eventId/session/threadId/turnId/runId/stepId/sequence/cursor/type/data。
runId/stepId 按适用性为 null；任务受理不伪造执行身份，Step 事件必须关联所属 Run。
sequence 是十进制字符串，cursor 不透明且绑定 Session；checkpoint 位置、输出字节 offset、
Hands sequence 均不能代替它。用户公开视图不包含私有 checkpoint 或推理状态。

| 操作/事实 | 公共消息和事件 |
| --- | --- |
| submit | 原子提交 turn.queued + user message.completed，随后 started/等待/结果/终态 |
| steer / inject | signal.accepted + user message.completed；后续 applied 或 withdrawn |
| respond | 同上，message.data 额外包含 interactionId；随后 interaction.resolved |
| cancel | turn.cancel_requested；排队任务同事务 cancelled，活动任务核实后 cancelled 或真实终态 |
| pause / resume | turn.pause_requested、turn.paused、turn.resumed；不生成用户聊天内容 |
| 扩展 signal | signal.accepted；等待条件满足后才可推进 |
| restore / fork | 校验 settled、目标空闲、版本和 expectedPosition 后提交 checkpoint.restored |

公开输入消息 ID 为 `input:` + hash([turnId, requestId, inputKind])，hash 使用当前 DurableJson
规范摘要。inputKind 为 submit/steer/inject/respond，伴随 requestId 和结构化 Message；respond
必须有 interactionId。文本响应保留原文本，其他 JSON 响应以 JSON 文本记入历史。消息表示
“已受理”，撤回不删除历史，由 signal.withdrawn 表示未应用。Backend 的公开投影是 history 的
来源；不从原生日志二次发布用户输入。事件修剪不删除已投影的消息。

一个 Turn 可以有多条 assistant 消息。AgentScope 的推理消息以 Turn 内的逻辑推理位置标识，
不会因 Run/Step 更换而改变 messageId；不同推理轮次使用不同消息。实际重新请求同一位置的
模型时增加 attempt；回放已提交模型结果时保持 attempt，并以更高 revision 替换尚未完成的
显示文本。最终内容修订也增加 revision；重复记录相同完成消息不再次发布。
同一版本的 delta 累积；更高版本替换旧文本；completed 替代累积内容并携带完整 Message；
已完成版本的迟到 delta 不能污染结果。支持 completed-only 的 Harness 可无增量。
AgentScope 的公开文本取自 middleware 处理后的原生 presentation/text，与原生日志同事务提交；
不直接公开模型原始 chunk 或私有推理。消息投影可携带关联的 runId/stepId，首次提交的 sequence
用于历史排序，不能依赖快照对象字段的迭代顺序。
blockId 不表示支持任意多块 patch。投影规则见 [projection-vector.json](projection-vector.json)。

工具调用视图统一放在 snapshot.projection.tools，键为 `tool:` + hash([threadId, turnId, callId])，
hash 使用同一 DurableJson 规范摘要。本地和 Hands 工具采用同一调用视图；只有真实外部执行
携带 executionId。视图保留 threadId、turnId、关联 Run/Step、首次 sequence 和合并后的 data。
data 中 name/arguments 是公开工具请求，content 是结果，progress 是已提交的进度内容；
不从原生执行参数中暴露宿主注入的凭据或配置。结果已接收与 applied 仍然分开。

Hands 的 `ExecutionRequest.call.origin` 是最初提交工具意图的 `{runId, stepId}`；
独立于调用记录的自定义执行可以显式为 null，辅助操作的 stepId 可以为 null。
AgentScope adapter 从每条原生交互事实取得来源，同批多个 Step 的工具不会被归到最后一个 Step。
来源参与完整请求摘要，重投、接管、批处理和 Sandbox 路由均保留该来源；同一 executionId 改变来源会被拒绝。
`tool.requested/dispatching/progress/result.accepted/output` 的事件关联指向原始位置。

提交结果应用使用 `appliedResults: [{executionId, appliedAt: {runId, stepId}}]`，
与 checkpoint 同事务保存；活跃 Agent 调用必须提交应用位置。宿主在调用结束后结清结果时
appliedAt 为 null，不虚构 Run/Step。
`ToolExecution.appliedAt`、`tool.result.applied` 及工具投影 `data.appliedAt` 表示实际应用位置，
恢复后通常属于新的 Run/Step；`data.origin` 保留原位置。结果接收不代表已经应用，
原始调用终止也不阻止接收 Hands 的迟到结果。此协议直接替换字符串 appliedResults，不保留旧格式读写。

| 调用事件 | 载荷与作用 |
| --- | --- |
| tool.call.arguments | callId、messageId、attempt、revision、text；已知时携带 name，后续无名称分片不清除已有名称；按消息版本组装 argumentsText，完整 arguments 到达后不再追加 |
| tool.call.updated | callId、status；可附 name、arguments、messageId、content、reason。状态为 REQUESTED/RUNNING/WAITING/SUCCEEDED/FAILED/CANCELLED/DENIED/UNKNOWN |
| tool.call.progress | callId、content；追加公开进度，不作为模型工具结果；终态后的迟到进度不重新打开调用 |

参数生成阶段的视图状态为 GENERATING_ARGUMENTS。已有 tool.requested/dispatching/progress/
result.accepted/result.applied/output 记录外部执行链路，并按 callId 合入同一视图，不为同一调用
生成第二个工具卡片。UNKNOWN 表示执行结果不确定，不能解释为可安全重试或已取消。

核心事件族：session.created/closed；thread.created/closed；turn.*；run.prepared/started/ended/lost；step.started/phase_changed/ended；signal.accepted/applied/withdrawn；interaction.requested/resolved；
message.delta/completed；tool.call.arguments/updated/progress；tool.requested/dispatching/progress/result.accepted/result.applied；
tool.output/output.gap；artifact.published；checkpoint.restored；turn.recovery。
应用扩展使用 `x.<namespace>.<name>`，不能替代核心生命周期事实。

Session/Thread 创建与关闭在资源事务中产生对应事件；创建 Session 同时产生主 Thread 的
thread.created。重复创建或关闭不追加生命周期事件，失败提交不留下资源或事件；提交回执丢失
可按原创建规格或关闭目标重试。载荷分别为 SessionView/ThreadView，created 的状态为 OPEN、
closed 为 CLOSED；不携带 Turn/Run/Step，Session 事件的 threadId 为 null。Agent 的 commit
不能自行注入这些事件。通知在提交后发送，丢失通知由轮询补偿。

快照 projection.session 为会话资源视图，projection.threads 按 threadId 保存 ThreadView；
两者与消息投影使用同一 sequence/cursor。资源投影和事件回放与直接查询一致，事件裁剪不删除
资源状态。Thread 过滤只保留该 Thread 资源，projection.session 为 null；Turn/Run 过滤
不包含资源生命周期，projection.session 为 null、projection.threads 为空。需要父资源当前
状态时使用 getSession/getThread，不把未订阅父事件的视图当作实时父状态。

`session.observe(callbacks)` 先交付一致快照，再读对应 cursor 后事件；显式 cursor 重载从该位置
重放，null 从保留开头。游标过期以快照重同步。回调成功才确认，失败重投；外部效果必须幂等。
`filter` 为 `{threadId, turnId, runId}`，字段均可为 null；省略 filter 表示整个 Session。
非空 turnId 必须有 threadId，非空 runId 必须有 turnId；服务器校验完整归属，错误目标返回
NOT_FOUND，缺少父身份返回 INVALID。过滤用于选择记录，不扩大宿主授予的 Session 访问权限。
Java 使用 `EventFilter`；Thread/Turn 的快捷入口委托同一公共操作，直连和 HTTP 语义一致。

limit 限制扫描的原始 Session 事件数，过滤页即使为空也推进原 cursor，highWatermark 仍表示
Session 水位。消费到 cursor == highWatermark 才表示本次已追平，不能用空列表判断流结束。
快照保留原 Session sequence/cursor，只交付匹配范围的投影；Turn 快照不包含同 Thread 的其他
任务。Run 快照保留所属 Turn 的逻辑状态，投影只包含当前关联该 Run 的记录；它不是历史时刻
的完整任务快照。消息、工具、产物和交互的归属用于选择，不推断跨 Run 的隐式归属。

使用 `new EventProjection(sessionKey, filter)` 消费过滤流，重同步使用同一 filter。
Session 全流继续检查连续序号；过滤流允许稀疏序号，完整性由服务端 cursor 契约保障，不能根据
序号间隔猜测丢事件。更换 filter 后仍从同一 cursor 读取意味着只获取后续事件；需要补齐此前
被排除的记录时显式重新加载该范围快照。

SSE 查询接受 namespace/sessionId、cursor 及可选 threadId/turnId/runId，拒绝重复和未知参数。
Last-Event-ID 优先于查询 cursor；身份和初始游标在发送 HTTP 200 前校验。`durable` 帧携带
PublicEvent。过滤掉的页尾记录通过 `cursor` 帧（id 为扫描后的原 cursor，data 为 `{}`）推进
重连位置；它是传输控制帧，不是公共事件，也不分配 sequence。中途过期仍发送 `resync`，
调用方获取同一范围快照后重连。断开流不控制 Turn。

SSE 初始过期返回 410，流中过期发送 resync。

`session.call` = submit + await，`session.stream` = submit + Turn observe。await/result 返回完整
TurnView，失败和取消不伪装成功；result 在非终态 CONFLICT。超时只结束等待，保留任务身份。
fork 在目标 Session 时间线记录目标 Thread 的恢复；继承的原生历史仅在私有恢复状态中，不伪造旧公开事件。

## 5. 交付保证

公共 `message.delta` 和 `message.completed` 事件持久化且可重放。消费者使用 Session 游标
恢复读取，必要时获取一致性快照，不需要从私有原生日志重建状态。控制回执确认已受理，
应用事件或最终 Turn 状态确认后续处理。用户输入不会获得系统权限，公共事件不包含私有推理。

## 6. 能力、错误与恢复

公共 capabilities 报告操作集合、已注册定义名称与 checkpoint 绑定，不枚举动态定义解析器内的所有配置。
Harness 恢复/媒体/事件能力、Executor 查询/取消/输出能力通过 `runtime.executionCapabilities()` 检查。
API-only Runtime 可以绑定 codec；未绑定的恢复返回 INCOMPATIBLE。原生 checkpoint 必须匹配
harness/definitionVersion/stateVersion；公开协议可跨语言不意味着原生状态可跨 Harness 转换。

| 错误 | 处理规则 |
| --- | --- |
| INVALID | 修正字段、类型、范围、分页游标或非法操作；不盲目重试 |
| NOT_FOUND | Session/Thread/Turn/interaction 不存在于请求范围 |
| CONFLICT | 状态、修订号、定义或幂等内容冲突；重新查询后决定下一操作 |
| INCOMPATIBLE | 当前 Harness/codec/协议能力不支持；不降级转换或重跑副作用 |
| CURSOR_EXPIRED | 取得一致快照及新 cursor 后继续；不是重新提交任务 |
| UNAVAILABLE | 传输结果可能不确定；用相同 requestId/内容重试或查询，不能换 ID 猜测未受理 |
| FENCED | 内部执行所有权失效；旧实例停止写入，不能当成用户重试任务 |

Framework 不提供统一身份系统：认证失败/拒绝授权由 HTTP 宿主映射并阻止调用。
持久状态是权威，通知只提示检查。Brain 启动扫描/租约接管与用户查询、SSE、resume 独立；
无用户连接也能推进。Hands UNKNOWN 必须核实原 executionId，不能重新 start 猜测成功。

调用失败恢复属于 Turn 公共视图：可空的 `recovery` 包含 `failures`、冻结的
`policy`（`maxRetries`、`initialDelayMillis`、`maxDelayMillis`）、`retryable`、可空的
`retryAt`（Backend 的绝对毫秒时间）和 `message`。`turn.recovery` 事件携带同样记录。
maxRetries 表示额外重试次数；可重试失败仅在 `failures <= maxRetries` 时允许继续调用，
停止恢复时 retryAt 为 null。首次失败固定本 Turn 的策略，后续成功推进、更换宿主和
pause/resume 均不清零累计失败次数；退避也不能绕过已有等待条件。

特权 Backend 操作 `recordFailure(lease, failure)` 每个租约 epoch 最多记录一次失败。
相同内容即使在所有者更换后仍返回原回执；改变内容冲突，未提交过记录的过期所有者被 fencing
拒绝。failure 包含 retryable、message、policy。计数、调用事实、恢复/等待事件和 ready
调度原子提交；该操作不是公共用户控制，也不授权重做外部副作用。停止恢复后仍核实或取消原工具，
结果明确后才 FAILED；暂停和取消仍优先。因此，recovery 或未结清工具可以解释没有 waiting 条件
的 WAITING。Java 默认额外重试 3 次、初始 1 秒、最大 30 秒；其他语言须遵守已持久化的策略和语义。

HarnessAdapter 使用 settle(context, status, error) 统一失败与取消的原生结清；AgentScope 将交互清理、真实结果应用、checkpoint 和 Turn 终态原子提交，失败写入不能提前确认 applied。

## 7. 一致性向量与 Harness 适配检查

[user-workflows.json](user-workflows.json) 提供可执行用户流程：提交、重复、历史/事件、排队取消、
内容冲突、未知字段、拒绝内部操作。`$turn` 绑定实际生成身份，其他值按当前规范比较。
[vectors.json](vectors.json) 覆盖摘要/数字/身份；[projection-vector.json](projection-vector.json)
覆盖恢复替代、迟到增量、大序号和公开用户消息。Java 本地与 HTTP 共用 UserApiContract；
类型化控制竞争、交互、恢复和生命周期在同一契约套件验收。共用向量是其他语言实现的对齐目标，
当前提供 Java 语言绑定；其他实现需要独立验证同一契约。

| Harness 接入项 | AgentScope Java 对应实现/约束 |
| --- | --- |
| 输入与结果 | DurableMessages ↔ Msg；最终公开 Message，原生对象不出现在用户协议 |
| 调度身份 | ThreadKey 隔离状态；Durable Turn → turnId；原生 AgentRun 与 RunView 共用 runId，不启动 AgentSession dispatcher |
| steer/inject | 调用级 AgentExecutionHost 在 reasoning 前应用并记录消费；用户输入保持 user 权限 |
| 审批/手动工具 | pending interaction → 原子 respond → 原生恢复；自动工具不可由用户伪造 |
| 工具交付 | checkpoint 与 intent 同事务；结果进入 Backend 后恢复原 Turn，原生 checkpoint 提交后 applied |
| 恢复 | codec 校验版本/settled，恢复原生日志、状态及允许资源；不重跑不可核实效果 |
| 公共投影 | input 原子发布一次，assistant attempt/revision 替代；不泄漏私有状态 |
| 能力不足 | 明确拒绝；不得改写操作含义或伪造终态 |

## 子委派协议

委派通过 Executor 边界执行，Backend 的 `delegate`、`delegation`、`cancelDelegation` 是特权基础设施操作。
`delegate` 原子提交目标 Thread、子 Turn、冻结目标/输入以及父子映射；目标可为新 Thread 或显式复用的已有
Thread，必须属于父 Session、使用相同 ThreadBinding。父 executionId 标识唯一映射，重试查询持久事实。

`TurnView.parent` 为 `{thread, turnId, executionId, origin}` 或 null，origin 使用 RunStepRef。
`ToolExecution.delegation`、公开工具投影 `data.child` 为 `{thread, turnId}` 或 null。
`DelegationView` 的 child 为空仅表示 cancelRequested=true 的受理前取消记录，不能当作成功或遗漏的子任务。
父暂停不传递给子任务；父取消只作用于映射中的子 Turn。取消先到时不解析目标、不创建 Thread/Turn。
Run/Step 来源和固定请求摘要在整个流程中保持不变。跨 Session、同 Thread 和未完成委派的依赖环均被拒绝。

| 事件 | 作用域与载荷 |
| --- | --- |
| tool.delegated | 父 Thread/Turn 及原始 Run/Step；executionId、callId、child |
| tool.delegation.cancel_requested | 父作用域；executionId、callId、可空 child。只确认取消意图，不宣称子任务已停止 |
| turn.delegated | 子 Thread/Turn，Run/Step 为空；载荷为父引用，不把父 Run 错当成子 Run |

正常结束后，子结果仍通过 Executor 的查询/结果交接进入父流程，与父 checkpoint 一起标记应用。
查询可以按子 Turn 的 parent 或父工具的 child 重建关联，不依赖进程内对象或平台数据库。

# 统一执行模型实施进度

[English](execution-model-progress.en.md)

> 本文保留原有开发阶段的结果、数字与命令作为历史证据；链接和示例目录按当前布局定位。
> 路径更新不表示重新执行或通过，历史命令不作为当前检查指引；当前入口见[库 README](../../README.md)。

更新：2026-11-10。按用户最新要求，本轮目标为[执行模型计划](../design/execution-model-plan.md)的 Durable 框架开发，**本轮开发已完成，独立集成验收待执行**；P7 Managed Agents 移到下一轮，本轮不修改平台。工作位置为 `/Users/ken/agentscope-3/agentscope-java`，分支 `automatic-acr-release-durable`。现有工作区改动继续保留；没有提交、推送或部署。

## 0. 本轮开发完成情况

本轮 P0–P5 功能改造及 P6 开发检查已完成；P7 Managed Agents 不实施。此前约 80% 的估算是历史节点，现在按下方逐项核对结果交付，不将测试数量换算为完成率。完整生产验收仍未完成，真实 MQ、多进程故障矩阵、最终完整框架构建和隔离依赖构建见[独立验收清单](execution-model-acceptance.md)。

最后完成了子委派：默认隔离子 Thread，也可显式复用已有 Thread。目标/输入冻结、子 Turn 受理、父子映射与两侧调度在同一 Session 事务提交；重试/换宿主不会重复解析或创建任务。取消先到写入墓碑，不启动子任务；已委派取消只影响对应子 Turn，父暂停不级联暂停子任务。拒绝同 Thread、跨 Session 和未完成委派形成的依赖环。父子来源进入 Java/HTTP/schema 与公共事件，未新增存储 SPI。Java Session 当前格式为 **14**，旧格式明确拒绝，无兼容转换。

就绪索引继续有界读取，并按新 Turn 的存储受理时间排序，防止同一 Session 新建的大量 Thread 越过另一 Session 已等待的任务；这不承诺租户配额或严格轮转延迟。已有暂停、退避、租约和版本筛选仍按同一契约执行。

最新开发检查（Java 17、`-T1 -Dagentscope.frameworkOnly=true`，移除真实模型凭据）：

| 检查 | 实际结果 | 证据 |
| --- | --- | --- |
| 委派/协议/投影/AgentScope 与 SQLite、HTTP 共享契约 | **224 项通过，0 失败/错误/跳过**；全部保留 Durable Java 模块及示例编译、相关 Spotless 通过 | `/tmp/durable-delegation-verified.log` |
| 最后就绪排序修改后的 Backend/User API 回归 | **166 项通过，0 失败/错误/跳过**；SQLite、HTTP 各 61 个 Backend + 22 个 User API 用例；相关 Spotless 通过 | `/tmp/durable-readiness-final.log` |
| 聊天消费者源码依赖构建及 Java 测试 | **12 项通过，0 失败/错误/跳过**；Spotless 通过 | `/tmp/durable-final-chat-consumer.log` |
| 聊天视图 Node 测试 | **4 项通过，0 失败/跳过** | `node --test agentscope-examples/agents/agentscope-chat/src/test/js/chat-view.test.mjs` |
| 最终格式检查 | 保留 Durable Java 模块、扩展及示例的 reactor `spotless:check` 通过 | `/tmp/durable-completion-format.log` |
| 文档静态检查 | 434 页、584 redirects 的导航、MDX 语法、本地链接/锚点与资源检查通过 | `cd docs` 后 `npm run check`；`/tmp/durable-final-docs-check.log` |
| BOM/聚合依赖同步 | 57 个 aggregate extensions、71 个 BOM modules，通过 | `.github/scripts/check-shade-and-bom-sync.sh` |
| 保留源码/POM 静态核对 | 无旧调度 API 引用；Durable 无 Managed Agents 源码/制品引用 | 不替代 I2 的解析依赖树及隔离缓存验收 |

224 与 166 包含重测的契约用例，不能相加成独立用例数。检查使用本地确定性模型、SQLite 和 HTTP；SDK mock 不代表实际 RocketMQ 验收。此前全框架检查 `/tmp/durable-thread-journal-framework-final.log` 主动停止，退出 143，不能计为通过。以下保留历史阶段证据及当时限制；当前状态以本节和第 3 节为准。

上述三次 Maven 检查的可复现入口（仓库根目录、Java 17，并移除真实模型凭据）：

```sh
mvn -B -ntp -T1 -Dagentscope.frameworkOnly=true \
  -pl agentscope-durable/durable-examples,agentscope-extensions/agentscope-extensions-durable-mcp,agentscope-extensions/agentscope-extensions-durable-objects -am \
  '-Dtest=DurableProtocolTest,EventProjectionTest,DurableBackendTest,DurableUserApiTest,AgentScopeAdapterTest,HttpBackendContractTest,PublicHttpUserApiTest,DurableHttpTest#delegationCannotHideAnotherSessionInsideAnAuthorizedEnvelope' \
  -Dsurefire.failIfNoSpecifiedTests=false spotless:apply test

mvn -B -ntp -T1 -Dagentscope.frameworkOnly=true \
  -pl agentscope-extensions/agentscope-extensions-durable-http -am \
  '-Dtest=DurableBackendTest,HttpBackendContractTest,DurableUserApiTest,PublicHttpUserApiTest' \
  -Dsurefire.failIfNoSpecifiedTests=false spotless:apply test

mvn -B -ntp -T1 -Dagentscope.frameworkOnly=true \
  -pl agentscope-examples/agents/agentscope-chat -am \
  '-Dtest=ChatControllerTest,ChatSessionsTest' \
  -Dsurefire.failIfNoSpecifiedTests=false spotless:check test
```

## 1. 已推进的实现

以下按阶段保留实现与验证记录；阶段中的“后续/待完成”描述已由第 0、3 节更新。

Hands 来源链路已接通：`ToolCall.origin` 携带原始 Run/Step，参与完整请求摘要，贯穿 Backend、Worker、批量子工具、Sandbox、HTTP 和 RocketMQ 消息；结果接收/进度事件关联原始位置。`AppliedResult` 与 checkpoint 原子提交实际应用位置，`ToolExecution.appliedAt` 和公共投影可读取；活跃调用不能遗漏位置，宿主结清不虚构 Run。旧字符串 appliedResults 直接删除，当前 Java Session 格式为 13，无旧格式转换。

恢复测试发现并修复 Core 在外部结果进入状态后、下一个 Step 开始前未记录 checkpoint 导致来源偏移的问题。定向 reactor `/tmp/durable-hands-origin-current.log` **107 项通过，0 失败/错误/跳过**：Core returnDirect 27、协议/投影 19、SQLite 与 HTTP 来源契约各 1、Worker 12、AgentScope adapter/工具 43、RocketMQ SDK mock 4；Durable Java 示例编译、相关 Spotless 通过。覆盖同批多 Step、接管、不可变来源、应用位置、失败结清、批量继承及消息摘要；不是实际 MQ 或多进程验收。

补充契约检查 `/tmp/durable-hands-origin-contracts.log`：**158 项通过，0 失败/错误/跳过**（Core 原生日志/外部工具 37、SQLite Backend 55、HTTP Backend 55、Sandbox 7、MCP 4）。使用 Java 17、`-T1 -Dagentscope.frameworkOnly=true`，reactor 包含 Durable examples、durable-mcp、durable-objects 及其依赖；全部保留的 Durable Java 模块和示例已编译、Spotless 通过。检查使用本地 SQLite、HTTP 和测试提供方，没有新增真实 PostgreSQL/MQ 验收。11 个相关 Markdown 文档的本地链接/代码围栏、协议 JSON 语法及相关 `git diff --check` 通过。

模型记录已贯通 Core → AgentScope adapter → Backend → Java/HTTP 只读 API。Core 在 ModelUtils 重试内部通过调用级 ModelRequestObserver 记录每次请求的独立身份、attempt、Run/Step/推理位置、耗时、部分用量与结束状态；保留逻辑调用记录。原生尾部无 usage 的 chunk 不再清除已报告用量，后一次请求缺少用量也不继承失败请求的值；模型回放快照同步修复。记录失败不触发 provider 重试。

Backend 在现有事务中保存模型事实与原生 checkpoint/公共事件；同批重复事件和提交回执重试不重复计量，跨 Turn/Run/Step 查询与游标范围校验一致。新增 ModelCallView/ModelRequestView/TokenUsage，以及 `turn.getModelCall`、`turn.listModelCalls`；未另建存储 SPI。已知用量按 requestId 汇总，缺失值为 null，失败/失联保留部分值和不完整标记。无结束事实在 Run 结束后查询为 LOST，不虚构结束时间。自定义模型只暴露外层调用时标记 MODEL_CALL，不推断内部请求；PROVIDER_ATTEMPTS 的边界是不透明 SDK 之外的共用请求封装。该模型记录阶段将 Java Session 格式推进到 12；随后 Hands 改造推进到 13。旧开发布局明确拒绝，无转换分支。

当前定向 reactor `/tmp/durable-model-evidence-verified.log`：**78 项通过，0 失败/错误/跳过**，包含 Core 20、协议 13、SQLite/HTTP Backend 与 User API 6、AgentScope adapter 39；Durable 下全部 Java 示例编译通过，相关 Spotless 通过。最后补齐取消前尚未 dispatch 的耗时处理后，Core 20 项重测通过（`/tmp/durable-model-evidence-core-final.log`）；模型回放快照与不重复请求 3 项定向检查通过（`/tmp/durable-model-capture-final.log`）。测试使用 Java 17、`-T1`、本地确定性模型及 SQLite，不是实际模型、PG 或 MQ 验收。公开协议/schema/正反例、EN/ZH 指南和 EmbeddedExample 同步更新。该节点后 Hands 来源已继续完成，委派边界仍待开发；Managed Agents 未改造。

- 已删除 Core 的 SessionInbox、SessionExecution、SessionTurns 和 SessionRecovery 的任务取消入口，以及 Harness 的 AgentSession、SessionAnswer、session() dispatcher/缓存。SessionLog.inbox() 和只用于旧 inbox 的发现分支同步删除，未增加兼容包装。ReAct 仍保留原生输入去重、结果应用、checkpoint 和清理；SessionRecovery 仍保留 UNKNOWN 结果核实。
- 原生日志只报告调用结果：使用 run/output 和 run/end，删除 turn/* 与 inbox/* 内置事件、旧 turn/end 读取兼容分支；adapter 同步消费调用输出，取消时仅提交原生交互/状态事实，Turn 终态仍由 Backend 决定。
- 原权限/中断恢复测试已改用原生 AgentRun，仍验证工具只执行一次且确认答案不成为聊天输入；三 JVM 的外部交互恢复测试迁入 Durable adapter，验证同 Turn、新 Run、结果只应用一次以及拒绝重复回答。旧输入/取消调度测试的有用保证由共享 Backend/User API 与 adapter 测试承接，新增客户端重建回执和终态取消隔离测试；末次模型返回、finalization、成功提交三个时点均验证新输入不会丢失。

- 新增 Core `RunOutcome` 和 `AgentRun.outcome()`，区分 Publisher 终态与调用的完成、挂起、中断、失败和取消。原生 recorder 与 Harness 使用同一结果映射；删除 SessionRecorder.outcome 字符串工具。结果提交/清理失败不再向调用句柄报告成功 outcome。取消回执不代表外部操作已经结清。
- adapter 不再依赖 SessionTurns 推导任务终态，改为读取已提交的调用结果，由 Durable 提交 Turn 状态。Core 不再因已有调用完成就拒绝同 Turn 的下一次调用。可恢复原生调用失败也不直接判定 Turn 失败；重试可创建新 Run，达到推理上限等明确结果仍按任务失败处理。完成阶段新受理的 steer/inject 保留原 Turn，重建宿主后通过新 Run 消费；成功提交与输入受理竞争由 Backend 事务校验，并自动重新检查，避免错误进入 runtime.error 等待。

- Core 已提供单一调用级 AgentExecutionHost。AgentScopeExecutionHost 集中输入/控制边界、finalization 续接与已记录模型结果回放；直接删除 AgentFinalization、DurableFinalization 和 DurableInputMiddleware，不保留别名。adapter 工厂返回 Agent Builder，adapter 自动安装调用级 Journal 并构建 Agent；宿主通过 RuntimeContext 自动注入，本地子调用会清除父宿主和模型回放绑定。宿主交接、旧调度删除与基本持久暂停已接通，具体边界见下文。

- SessionSpec/View、ThreadBinding/Spec/View、DurableThread 已接通。Session 创建时原子建立主 Thread；Thread 独立持有 FIFO、租约、epoch、checkpoint、journal、资源和执行记录。Runtime 的 active/claim/ready 按 Thread 调度，同一 Session 的不同 Thread 可以并行，未另建存储 SPI。
- 原生状态、日志与 AgentScope RuntimeContext 使用完整 Thread 身份；逻辑任务 API 为 DurableTurn，Backend 持久化独立 Run/Step。Run 启动幂等、失联接管和 checkpoint/event 仍在原有事务边界内。
- Session 管理生命周期、元数据、Thread 目录、公共事件序号和 requestId 去重。新增 Thread 创建/查询/列举/关闭 API；提交和控制摘要包含目标 Thread，不能借另一 Thread 操作原任务或复用请求身份。
- HTTP Backend、公共 HTTP、Hands ExecutionRequest、Sandbox/MCP/Worker 调用方和所有现有 Java 示例已迁移 Thread 身份；删除 SessionDefinition 和 RunExecutor，使用 DelegationExecutor 在同一 Session 内建立稳定子 Thread。委派复用、父子来源及取消策略仍未全部完成。
- checkpoint restore/fork 按 Thread 作用域复制原生状态与私有资源；目标 Session 必须已存在。关闭后的 Session/Thread 拒绝新的恢复，原恢复回执仍可幂等查询。
- Core 将累计推理位置与原生 checkpoint 一起保存。只改变进度、没有改变上下文内容时也提交新位置；同 Turn 的下一次调用恢复原位置，新 Turn 从零开始。外部工具续接不额外消耗一次推理预算。
- 外部结果在进入下一轮推理前记录 ACTING Step，包括 returnDirect 不再调用模型的路径。恢复推理和 finalization 也创建属于新 Run 的 Step，可引用旧 Step；正常 finalization 推进当前 Step 的阶段。
- 原生日志记录 Step 关联，adapter 把可关联的交互和完整消息映射到同一 Run/Step。模型回放沿用已提交结果，不伪造新的实际模型请求。
- 工具事件投影保留 Run/Step 字段，快照 schema 与投影一致。当时的 AgentScope 原生格式标识为 `agentscope-thread-log-v1`（现已由下述 journal-v2 直接替换），不增加旧格式读取或转换。

公共消息与工具投影已接通：每轮推理保留独立逻辑消息，已提交的 middleware 文本可在调用结束前回放；模型重试与原结果回放通过 attempt/revision 区分。工具按 Thread/Turn/callId 合并本地与 Hands 事实，提供参数分片、进度和结果，只有真实外部执行携带 executionId。无名称参数分片不会清除工具名称；已确认的外部结果转换为原生终态，恢复后不再丢失内容。returnDirect 的原生占位消息不覆盖已观察输出；未确认副作用保持 UNKNOWN，普通错误消息不能替代显式核实。公共投影与原生事实同事务提交，并验证快照与事件回放一致。

Java Session 存储格式调整为 8，以拒绝旧工具投影布局；旧数据不转换、不清空。旧格式拒绝和原数据保留有 JDBC 回归用例。上述投影实现不代表完整模型用量/来源、普通聊天示例或真实 MQ 故障验收已完成。

持久暂停已接入同一条执行链：Core 的 AgentExecutionHost.Paused 在安全边界转为 HOST_PAUSED / SUSPENDED，call 与 stream 使用相同 outcome。finalization 先保存已生成回答和续接位置，再检查暂停；原生 checkpoint 提交与暂停 revision 竞争时仍原子校验日志位置、输入和所有权。Backend 使用 PAUSING/PAUSED，禁止新调用与新工具交付；安全交接后释放租约，暂停队首不会让后续 Turn 越过。租约已失效的原调用保留 LOST 事实，不伪造正常完成。

公共 API 使用 pause(requestId) 和 resume(requestId, expectedRevision)，删除 waitingKey 形式。resume 仅解除显式暂停，原工具、交互和信号条件继续生效。后到的 Hands 结果和交互答案可落库而不推进；新宿主通过同 Turn、新 Run/Step 应用原结果。schema、双语用户文档和可执行用户流程同步更新，流程包含重复请求、旧形式拒绝及过期 revision。此后已将内部 runtime.error 等待替换为下述持久自动恢复，不提供公共绕过入口。

持久错误恢复已接入现有 Runtime/Backend：新增 RecoveryPolicy、TurnView.recovery 与 turn.recovery 事件，首次失败固定策略；失败计数与绝对退避时间随 Turn 保存。每个租约 epoch 的 recordFailure 有固定回执，响应丢失重取不重复计数；失败所有权不能再启动 Run 或交付工具。默认最多额外重试 3 次、1 秒起始/30 秒封顶，未知程序错误不自动重试；AgentScope 采用 Core 的模型分类识别 429/5xx、I/O 与超时。查询、通知、结果或暂停/继续不能重置计数或绕过退避，取消仍可进入结清。

预算耗尽或不可重试时，Runtime 停止新调用并继续核实原工具。UNKNOWN 保留，明确结清后才失败；HarnessAdapter 使用单一 settle 入口处理失败/取消，AgentScope 将待处理交互清理、真实工具结果应用、原生 checkpoint 和 Turn 终态同事务提交，不启动模型或伪造新 Run。Java Session 格式为 9，旧开发数据明确拒绝且不改写；没有增加旧格式转换。此阶段不代表真实 MQ 故障验收、普通聊天示例或完整 P0–P7 已完成。

agentscope-chat 已迁移到 LocalBackend + Runtime。每次调用返回新的 HarnessAgent Builder，由 adapter 安装原生日志并构建 Agent；删除 ChatHistory/ChatItems 原生日志解析与旧 AgentSession 调用。HTTP 显式创建 Session，查询缺失对象返回 404；控制指向 Turn 并携带 requestId，resume 携带 revision。公共快照承载消息、工具进度和交互；SSE 原样传递 cursor，以 Last-Event-ID 续传，游标过期发送公共快照重新同步。前端按首个未完成 Turn 操作，暂停时答案受理后不再显示可重复提交的表单。双语 README 与 session-chat 指南已同步。

根 reactor 的原始 `-pl ... -am` 命令在模块选择前被忽略范围内 agentscope-service 的无效父 POM 阻断。根 POM 新增 `agentscope.frameworkOnly=true` 选项，在模型解析时排除该聚合；未修改 Service 源码，默认未启用选项时仍包含它。完整框架验收须使用这个可复现入口，不能仅凭子模块构建宣称通过。

公共观察范围已经统一：新增 EventFilter(threadId, turnId, runId)，Java、公共 HTTP 和 SSE 使用同一父身份校验与过滤契约。Thread/Turn 快捷入口不再各自过滤，Turn 的初始/过期重同步快照只包含目标任务；Run 范围保留所属 Turn 状态并选择当前关联该 Run 的投影。原 Session cursor/highWatermark 保留，空过滤页也能前进。EventProjection 接受显式过滤的稀疏流，完整 Session 流仍检查连续序号。SSE 校验范围后才发送 200，Last-Event-ID 优先，cursor 控制帧传递过滤后的扫描位置；断连不取消任务。

产物与交互投影补充执行归属，相关 Java 类型及 schema 同步调整；Session 格式为 10，拒绝旧布局且不改写已有数据。新增共享契约验证错误父身份、Run 归属、Turn 快照隔离、回调失败重投和重同步；公共协议向量及双语指南同步。生命周期事件尚未完成，这一阶段不代表整个 P4 或 A11/A12 的所有故障组合已经验收。

Session/Thread 生命周期已接入同一资源事务：创建 Session 原子产生 session.created 与主 Thread 的 thread.created，显式创建/关闭产生对应事件；重复创建/关闭不重复追加事实。快照直接投影权威资源状态，EventProjection 回放与查询一致，过滤不混入未订阅的父资源。Agent commit 禁止伪造资源事件；事件/快照载荷校验归属。Session 格式为 11，旧格式明确拒绝且保留原数据，不新增兼容读取或独立事件存储。共享 SQLite、HTTP 及真实 PostgreSQL 契约覆盖创建/关闭回滚、提交后回执丢失、重复重试、Thread 关闭与受理竞争、跨客户端和过期快照恢复。生命周期基础完成不等于整个 P4/P6 或所有控制竞争已验收。

原生直接调用身份已收敛：不再生成占位 Turn，SessionInteractions 改为返回有原 Run/Step 归属的待处理外部请求。Run 开始与起始 checkpoint 原子提交；按已提交 Run 及 outcome 判定续接，不因空 turnId 相同而复用计数。完成、取消及预算/权限终止后的新调用从零开始；挂起、中断、基础设施失败和无结束记录的调用可续接。显式 UNKNOWN 核实保留原 checkpoint 的 Run/Turn/执行位置，不生成虚构的调用 ID。Harness 子调用保留父 Run/Step 来源，并清除父执行位置和旧祖先关联。当前 40 项 Core/Harness 定向测试通过，包含 Core SubAgentTool 与 Harness 两条子调用入口；该阶段完整框架检查 `/tmp/durable-native-identity-framework-final.log` 已结束，Core/Harness/Durable 通过，后续 RAG Simple 的真实 DashScope 多模态测试因远程图片下载失败而中止，不能计为全框架通过。该测试由环境凭据触发；后续本地回归隔离模型凭据，不修改其断言。Thread 日志命名/格式和其他 P1/P3 缺口仍在。

原生 Thread 日志已收敛为 core.journal 下的 ThreadJournal、InvocationRecorder、JournalProjection/ThreadHistory 与 ThreadCheckpoints；ThreadKey 移入 core.agent。Harness 使用 journalEnabled、threadJournalStore、threadTranscript/inspectThread 和 thread_search/list/history；已删除 2.1.0-BETA1 的旧模式枚举、旧 header 和双开关。2.0 已发布的 disableTranscript/disableSessionPersistence 入口保留为兼容 no-op。head 与原生事件只接受格式 2，AgentScope codec 标识为 agentscope-thread-journal-v2；旧/未来/损坏 head 拒绝且不写入。当前格式初始化不虚构 Run，工具结果解码不再补旧格式 discriminator。额外修复原生状态替换缺少 threadId 归属校验，并增加初始化/fork/跨 Thread 拒绝测试。公开双语日志指南已删除旧 AgentSession 操作，后台任务统一指向 Durable。

首轮原生日志定向检查 `/tmp/durable-thread-journal-targeted-final.log`：Core 83、Harness 25 项，0 失败/错误、1 项 Windows 专用用例跳过。随后 Core/Harness/Durable 联合 reactor `/tmp/durable-thread-journal-reactor.log` 通过；该轮未 clean，不能作为旧 class 清除或最终源码的交付证据。补齐 Thread 状态校验与最后的 provider 能力方法后，执行完整 clean verify。首次 `/tmp/durable-thread-journal-framework-verify.log` 在发现 DurableWorkspaceStore 遗漏方法改名后主动停止（143），不计作通过；后续 `/tmp/durable-thread-journal-framework-final.log` 已按最新快速开发要求主动停止（143），不计作全仓通过。所有本轮 Maven 检查移除真实模型凭据，保留本地夹具；未执行新的真实 MQ 验收。

打包审查另外发现 Core 仍携带旧 `core/session/session-event-v1.schema.json` 资源，Java 事件已切换为 2。现已移至 `core/journal/journal-event-v2.schema.json`，并通过实际已提交事件的 codec 消费测试；定向 clean 检查同时确认旧资源不存在，详见第 0 节。已确认当前 Core/Harness JAR 不再包含旧原生 session 包的 class。

## 2. 累积阶段验证证据

原生身份的首次全框架检查 `/tmp/durable-native-identity-framework-verify.log` 因发现 Core 子调用也需清除父执行位置而主动停止（退出码 143），不计作通过。补齐该入口并通过定向验证后，从根目录重新启动当前源码的完整 clean verify，日志为下述 `framework-final`；没有在同一构建运行期间修改被测实现源码。

以下命令均使用 Java 17，在本仓库根目录运行并保留 `-T1`。这是阶段证据，不是完整验收证明。

| 范围 | 命令及实际结果 |
| --- | --- |
| 原生可选 Turn 与续接：定向验证 | Java 17、`-T1 -Dagentscope.frameworkOnly=true -pl agentscope-core,agentscope-harness -am -Dtest=SessionRecorderAtomicFactTest,SessionExecutionIdentityTest,JournalSessionLogTest,SubAgentInvocationStateTest,SubagentInvocationIdentityTest,HarnessAgentSubagentStreamTest -Dsurefire.failIfNoSpecifiedTests=false spotless:apply test`：Core 28 项、Harness 12 项，0 失败/错误/跳过；日志 `/tmp/durable-native-identity-final-targeted.log`。未声明完整回归或覆盖率验收通过 |
| 生命周期改造后全框架检查 | Java 17、`-T1 -Dagentscope.frameworkOnly=true clean verify`，保留 Javadoc/覆盖率；105 模块中前 52 个成功，第 53 个 OSS 在旧状态测试夹具缺少 thread_id 时失败，后 52 个跳过。日志 `/tmp/durable-framework-lifecycle-verify.log`；进程退出码 1，不能视为全框架通过 |
| OSS/COS 当前状态夹具修正 | 两模块改用当前 AgentState 序列化，并断言 userId/sessionId/threadId 保留；未放宽生产读取校验。Java 17、`-T1 -Dagentscope.frameworkOnly=true -pl :agentscope-extensions-oss,:agentscope-extensions-cos spotless:apply verify`：82 项、0 失败/错误/跳过，6 份 XML；两模块均成功，日志 `/tmp/durable-storage-fixture-verify.log`。此结果不覆盖上一行跳过的其余模块 |
| 资源生命周期：Durable 全 reactor | Java 17、`-T1 -f agentscope-durable/pom.xml -Pdurable-standalone -Dmaven.javadoc.skip=true -Djacoco.skip=true spotless:apply install`：13 模块成功，326 项、0 失败/错误、1 项 Pi 跨语言跳过；35 个测试类报告，日志 `/tmp/durable-lifecycle-all.log`。默认 SQLite，包含 HTTP、全部 Java 扩展及 examples 编译；不代表真实 MQ 验收 |
| 资源生命周期：真实 PostgreSQL | Java 17、`-T1 -f agentscope-durable/pom.xml -Pdurable-standalone -pl :agentscope-extensions-durable-jdbc -am -Ddurable.test.jdbcUrl=... -Dmaven.javadoc.skip=true -Djacoco.skip=true spotless:check test`：124 项、0 失败/错误/跳过；日志 `/tmp/durable-lifecycle-postgres.log`。其中 JDBC 95 项：52 个 Backend、22 个 User API、7 个 ScopedWorkStore 用例使用实际本地 PostgreSQL 的隔离 schema，其余按各自夹具；Runtime 29 项另计 |
| Admin Starter 格式修正 | Java 17、`-T1 -Dagentscope.frameworkOnly=true -pl :agentscope-admin-spring-boot-starter spotless:check test`：40 项、0 失败/错误/跳过；日志 `/tmp/durable-admin-migration-check.log`，仅证明该模块修正通过 |
| 聊天示例源码依赖构建（过滤改造前） | Java 17、`mvn -B -ntp -T1 -Dagentscope.frameworkOnly=true -pl agentscope-examples/agents/agentscope-chat -am -Dmaven.javadoc.skip=true -Djacoco.skip=true spotless:check verify`：18 模块成功；5,053 项、0 失败/错误、29 跳过。日志 `/tmp/durable-chat-framework-reactor.log`；这是过滤改造前的证据，不能覆盖之后的源码修改 |
| 统一观察范围：最终 Durable reactor | Java 17、`mvn -B -ntp -T1 -f agentscope-durable/pom.xml -Pdurable-standalone -Dmaven.javadoc.skip=true -Djacoco.skip=true spotless:apply install`：13 模块成功，316 项、0 失败/错误、1 项 Pi 跨语言跳过，35 份报告；日志 `/tmp/durable-scoped-events-final.log`。覆盖 null 游标重载、托管初始快照和交互跨 Run 归属修复；默认 SQLite，未新增真实 MQ 验证 |
| 本轮文档与 BOM | `npm test` 16 项、`npm run validate`、`npm run broken-links` 全通过；EN/ZH durable 页面 MDX、计划/进度/协议链接与节次检查通过。`check-shade-and-bom-sync.sh` 通过：aggregate 57 个扩展、BOM 71 个模块 |
| 聊天示例公共 API 迁移 | Java 17、`-T1 -f agentscope-examples/agents/agentscope-chat/pom.xml -Dmaven.javadoc.skip=true -Djacoco.skip=true spotless:apply verify`：12 项、0 失败/错误/跳过，生成可运行 JAR；日志 `/tmp/durable-chat-complete.log`。Node 公共视图测试 4 项通过，含队首控制与大序号排序、暂停期间已受理答案的表单状态；源码依赖 reactor 与实际页面检查另外记录 |
| 持久错误恢复与原生结清：Durable 全 reactor | Java 17、`-T1`，`mvn -B -ntp -T1 -f agentscope-durable/pom.xml -Pdurable-standalone -Ddurable.test.jdbcUrl=... -Dmaven.javadoc.skip=true -Djacoco.skip=true spotless:check install`：307 项、0 失败/错误、1 跳过，已与 35 份 Surefire XML 核对。JDBC 88 项中 49 项 Backend 契约和 18 项 User API 契约直接使用真实本地 PostgreSQL；ScopedWorkStore 的 7 项也使用 PostgreSQL 配置，装配及其他用例按各自夹具运行；HTTP 对应契约使用 SQLite。包括所有 13 个 Durable reactor 模块和 Java examples 编译 |
| 持久错误恢复：SQLite 契约 | 不传 PostgreSQL 参数，选择 DurableBackendTest、DurableUserApiTest、BackendAssemblyTest、ScopedWorkStoreTest，以 `-pl :agentscope-extensions-durable-jdbc -am` 执行 `spotless:check test`：79 项、0 失败/错误/跳过 |
| 宿主入口收敛后 Core 全量 | `mvn -B -ntp -T1 -f agentscope-core/pom.xml spotless:apply install`，格式范围限定本次改动文件；2,657 项，0 失败/错误，9 跳过。另通过全模块 spotless:check |
| Thread 改造后 Durable 全 reactor | `mvn -B -ntp -T1 -f agentscope-durable/pom.xml -Pdurable-standalone -Ddurable.test.jdbcUrl=... spotless:apply test`，253 项，0 失败/错误，1 跳过；包含 HTTP、JDBC、MQ、Sandbox、MCP、Objects、Worker、adapter/codec 和 examples 编译 |
| 宿主入口收敛后 Durable 全 reactor | `mvn -B -ntp -T1 -f agentscope-durable/pom.xml -Pdurable-standalone test`，253 项，0 失败/错误，1 跳过；日志 `/tmp/durable-execution-host-confirmed.log`，结果与各模块 Surefire XML 核对一致。本轮使用默认 SQLite，不作为新增 PostgreSQL 或真实 MQ 验证 |
| SQLite / PostgreSQL | 默认 JDBC 73 项已通过 SQLite；上述完整 reactor 的 JDBC 模块共 73 项通过，其中 40 项共享 Backend 契约使用已确认运行的本地 PostgreSQL；其他测试使用各自夹具。HTTP 后端契约使用独立 SQLite 夹具 |
| 宿主入口收敛后 Harness 全量 | `mvn -B -ntp -T1 -f agentscope-harness/pom.xml spotless:apply install`；1,164 项，0 失败/错误，18 跳过；验证本地子 Agent 的宿主隔离。另通过全模块 spotless:check |
| RunOutcome 与输入竞争：Core/Harness 全量 | Java 17、`-T1` 分别执行 Core 和 Harness 的 `install`，附 `-Dmaven.javadoc.skip=true -Djacoco.skip=true`；Core 2,666 项、0 失败/错误、9 跳过；Harness 1,164 项、0 失败/错误、18 跳过。两模块全量 spotless:check 通过；本次未生成 Javadoc/覆盖率报告 |
| RunOutcome 与输入竞争：Durable 全 reactor | `mvn -B -ntp -T1 -f agentscope-durable/pom.xml -Pdurable-standalone -Ddurable.test.jdbcUrl=... -Djacoco.skip=true spotless:check test`：258 项、0 失败/错误、1 跳过。JDBC 模块共 74 项，其中 41 项共享 Backend 契约在真实本地 PostgreSQL 上执行；HTTP 的同组 41 项契约在 SQLite 上执行。两个输入竞争用例验证重建宿主后同 Turn、新 Run、输入只应用一次；另一个原生失败用例验证重试可成功且不会提前失败 Turn |
| 原生调度移除阶段 | Java 17、`-T1`，Core/Harness 分别 `spotless:apply install`（限定格式文件，跳过 Javadoc/JaCoCo 报告）；Core 2,659 项、0 失败/错误、9 跳过，Harness 1,162 项、0 失败/错误、18 跳过。Durable 完整 standalone reactor `spotless:apply install`：264 项、0 失败/错误、1 跳过，含迁移后的三 JVM 交互测试、共享 User API 新增用例及全部 Durable Java examples 编译；不含尚未迁移的 agentscope-chat。JDBC 76 项中仍仅 41 项共享 Backend 契约使用真实 PostgreSQL；HTTP 共享契约使用 SQLite |
| 格式与文档 | Durable 全 reactor `spotless:check` 通过；docs 的 `npm test`（16 项）、`npm run validate`、`npm run broken-links` 通过；公开文档校验覆盖 434 页、584 条重定向；计划/进度与协议文档另行检查 MDX、相对链接和中英文节次 |

当前 Thread 阶段完整日志为 `/tmp/durable-thread-postgres-all.log`；默认 SQLite 与 HTTP 通过见 `/tmp/durable-thread-verified-tests.log`。首次 HTTP 大对象测试遇到磁盘空间不足，清理本任务废弃临时 Maven 缓存后原测试通过，未改变大对象传输代码。当前 Core/Harness 证据为 `/tmp/durable-execution-host-core-final.log` 和 `/tmp/durable-execution-host-harness.log`。这两模块重新构建后临时磁盘空间再次下降，又清理了本任务废弃 Python 依赖环境及临时 JDK 的可选源码归档；保留 Java 二进制、项目源码、测试 XML 和数据库夹具。本地临时日志不替代最终可复现验收记录。

RunOutcome 阶段日志为 `/tmp/durable-outcome-core-full.log`、`/tmp/durable-outcome-harness-full.log` 和 `/tmp/durable-outcome-final.log`，Durable 计数已与本次 Surefire XML 核对。首次完整运行在 HTTP 大对象夹具遇到临时磁盘不足；清理本任务临时 JDK 中未用于编译运行的 jlink 模块归档后，原测试及完整 reactor 通过，没有修改 HTTP 实现或减小测试对象。真实 MQ 多进程验证仍未执行。

Core 的 9 项跳过包括：1 项 Windows 条件测试、1 项需要显式启用的 structured-output 竞争测试、7 项已有 disabled 事件流测试。Durable reactor 的 1 项跳过是 Pi 跨语言集成测试；它不属于本轮 Java 运行时范围。默认 JDBC 测试使用 SQLite，不能将默认通过误报为 PostgreSQL 验证。

Harness 跳过 9 项需要 `docker.it` 的 Docker 文件传输集成测试，以及在 macOS 上禁用的 8 项 LocalShell 和 1 项原生 SQLite 存储测试。上述跳过均不能视为相应部署形态已通过验收。

新增/加强的行为证据包括：跨三次 Runtime 重建仍遵守同 Turn 的 maxIters；新 Turn 重置预算；外部 returnDirect 应用前有新 ACTING Step；模型结果已持久化但状态未应用时，恢复仅产生一次真实模型调用；finalization 重建保留步骤来源，辅助模型和 Workspace 操作不重复执行；HTTP 快照可接收带 Run/Step 的工具投影。

新增 Thread 契约覆盖：同 Session 双 Thread 并行；队首等待时同 Thread 后续任务不越队、其他 Thread 可推进；独立 epoch/checkpoint；跨 Thread 查询、租约与请求身份拒绝；忙碌对象关闭冲突、关闭父对象后提交/创建/恢复拒绝；跨客户端重新查找及 Thread 历史/快照/事件过滤；同 Session 的 Harness Workspace fork 后写入隔离。

原生调度移除阶段日志为 `/tmp/durable-native-scheduler-core.log`、`/tmp/durable-native-scheduler-harness.log`、`/tmp/durable-native-scheduler-all.log`；本次 Durable 264 项已与构建开始后生成的 34 份 Surefire XML 核对。Core/Harness 和完整 Durable 的 spotless:check 通过。为留出大对象测试空间，仅清理任务生成的旧 Javadoc，并把本任务临时 PostgreSQL 发行包中 SHA-256 相同的重复动态库替换为指向同一文件的符号链接，保留库路径、运行中的数据库及数据；真实 PostgreSQL 契约随后通过。没有改小测试对象或降低断言。

当前恢复阶段日志为 `/tmp/durable-recovery-complete-reactor.log`、`/tmp/durable-recovery-sqlite.log`。新增契约覆盖：更换宿主不重置失败次数或策略、指数退避/限次耗尽、失败回执丢失后重取、旧租约不能绕过退避、暂停/取消优先、UNKNOWN 保留且不重新启动工具。AgentScope 覆盖 400/429/503 分类、新宿主自动恢复而无用户信号、失败后同 Thread 的下一任务，以及结清提交被拒绝时交互/checkpoint/结果 applied/终态都不提前确认。最后一组验证先拒绝结清写入，再恢复提交，仍只应用一次结果。当前阶段未修改 Core/Harness，也未重新运行其全量测试；全框架 verify 与真实 MQ 故障组合仍待 P6 完成。

全框架 `mvn -B -ntp -T1 -Dagentscope.frameworkOnly=true clean verify` 已结束，日志 `/tmp/durable-framework-full-verify.log`。105 个 reactor 模块中，前 46 个通过，在第 47 个 Admin Starter 因此前 Thread 状态键改造的 SessionOperations 格式错误停止，其余未验收；已修正该处格式。此轮未跳过 Javadoc 或覆盖率报告，已通过 Core/Harness、当前过滤 API 及初始快照契约，但不能视为全框架通过。运行期间追加的交互跨 Run 归属修复，随后在最终 Durable reactor 重新验证。

## 3. 按规划核对开发交付

| 阶段 | 已实现的落点与行为 | 开发证据与独立验收边界 |
| --- | --- | --- |
| P0 | `DurableTypes`、`DurableProtocol`、`DurableOperations` 与 schema/vectors/user-workflows；五层身份、控制、公共事件、模型/Hands/委派来源统一 | 当前协议 15、投影 5 项通过；共享 User API 验证操作流程和拒绝规则；组合故障见 I3–I8 |
| P1 | Core `AgentExecutionHost`、`AgentRun/RunOutcome`、ThreadJournal/InvocationRecorder；Harness 资源隔离；删除 AgentSession 和重复后台调度 | 原生 journal clean 定向 18、直接调用身份 40、Hands 外部结果 checkpoint 回归见历史记录；完整 clean 回归见 I1 |
| P2 | `TransactionalSessionBackend`、`InvocationRecords`、Runtime 按 Thread 认领；FIFO、fencing、暂停、持久退避、自主发现与按受理时间排序 | 当前 SQLite/HTTP Backend 各 61 项，包括无客户端驱动恢复、控制竞争和新增排序；多进程/负载见 I3、I5 |
| P3 | AgentScope adapter/codec、原生 checkpoint 与输入/结果/事件原子提交；Run/Step、模型请求/用量、Hands 原始及应用位置 | adapter 39 与 Core/来源定向检查；完整跨进程计量/结清组合见 I5、I6 |
| P4 | Runtime 统一顶层业务入口，Session/Thread/Turn 句柄和只读 Run/Step/model 查询，HTTP/SSE/投影；聊天示例迁移 | SQLite/HTTP User API 各 22、聊天 Java 12 + Node 4；消费者源码无旧 API 引用；真实远程组合见 I8 |
| P5 | Worker、RocketMQ、MCP、Sandbox、Objects 保留同一协议；DelegationExecutor 支持隔离/复用、原子映射、取消墓碑和依赖环拒绝 | 保留模块全部编译；Worker/MQ mock/MCP/Sandbox 及本轮委派契约见记录；真实 MQ 和独立父子退出见 I4、I7 |
| P6 开发 | 4 个 Java 示例作为独立 module，EN/ZH 指南与协议更新，BOM/静态依赖检查通过 | 已完成受影响构建与定向验证；I1–I9 均仍待独立验收，包括真实 MQ 失败注入夹具补齐 |
| P7 | 本轮未实施 Managed Agents 改造 | 下一轮单独规划，不保留临时兼容入口 |

本轮没有已知未修复的开发缺口；独立验收发现的问题继续按原子性、身份、幂等、fencing 与 UNKNOWN 的原要求修复，不降低断言。工作仍在 `/Users/ken/agentscope-3/agentscope-java` 的 `automatic-acr-release-durable`，没有提交、推送或部署。

## 4. 2026-11-12：模块与辅助内容整理检查

删除 `java` 聚合层，提升 runtime、worker、adapters 与 examples；codec 并入 `durable-adapter-harness-agentscope`。五个示例按 integrated/queued 分类，各自持有 `run.sh`；`scripts` 仅保留框架独立依赖检查。协议集中保存且仅打包四个 JSON，文档按 design/verification/archive 分层，五个历史来源文件原样保留。迁移指纹核对确认 96 个源码/资源字节一致，唯一额外修改是跨进程恢复测试的子 JVM 状态目录隔离。

`HarnessExternalToolProcessRecoveryTest` 的子 JVM 未继承 Surefire 的 `agentscope.state.home`，此前误读用户目录旧状态而失败。现显式使用同一测试临时目录，未修改产品状态导入行为、用户状态或恢复/幂等断言；定向测试 1 项通过，日志 `/tmp/durable-layout-recovery-fixed.log`。

仓库根目录执行 `make -C agentscope-durable check`：Maven 3.9.15、Java 21.0.2 运行时、Java 17 编译目标、`-T1`；19 个 reactor 模块全部成功，40 份 clean 后的 Surefire 报告核对为 **381 项、0 失败/错误/跳过**，Spotless、打包和 BOM/shade 同步通过。日志 `/tmp/durable-layout-final-check.log`。五个示例可执行 JAR 的 Start-Class、embedded/Sandbox 启动器烟测、13 个 POM 路径、200 个当前文档本地链接及四个协议 JAR 资源均通过。文档 `npm test` 16 项、`npm run validate`、`npm run broken-links` 通过，覆盖 434 页和 584 条重定向。

该结果使用默认 SQLite、HTTP 与确定性模型/MQ SDK fixture；不是全仓、当前 Core/Harness 全量测试、Java 17 运行时、真实 PostgreSQL/MQ 或云服务验收。改写后的独立缓存脚本仅完成静态与夹具检查，尚未实际执行独立 Maven 缓存构建；这些缺口继续由独立验收清单跟踪。模块、脚本、文档及测试隔离修复的独立只读复核无阻断；没有提交、推送或部署。

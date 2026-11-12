---
title: Thread 执行日志与恢复
description: 使用原生 Thread 日志读取消息、检查 Run 和 Step、保存 checkpoint 并显式恢复；持久任务由 Durable 调度。
en_link: /dev/en/docs/harness/session-log
---

HarnessAgent 默认将执行事实和 checkpoint 写入原生 Thread 日志。它支持连续对话、历史读取和显式恢复。需要后台排队、暂停、用户控制或跨实例自动接管时，使用 [Durable](/dev/zh/docs/harness/durable)；日志本身不启动任务调度器。

## 先选择调用方式

直接调用 `agent.call(input, ctx)` 或 `agent.streamEvents(input, ctx)`，由应用负责订阅和本次调用的生命周期。同一 Thread 复用上下文，每次新调用产生新的 Run。普通调用的 `turnId` 可以为空，不会为了日志生成持久任务。

后台任务使用 `runtime` 获取 Session，在其主 Thread 或指定 Thread 提交 Turn。任务控制指向 Turn；Run/Step 是执行记录。下面假设 Runtime 已按 [Durable 指南](/dev/zh/docs/harness/durable)装配：

```java
var session = runtime.getSession(sessionKey);
var turn = session.submit("request-1", "比较方案 A 和方案 B。");
turn.steer("request-2", "重点比较运维成本。");
turn.pause("request-3");
```

暂停回执表示意图已受理。观察到 `PAUSED` 后，使用该状态的 revision 调用 `turn.resume(requestId, revision)`。回复待办使用 `turn.respond`，取消任务使用 `turn.cancel`。查询、等待超时和 SSE 断连不取消持久任务。普通 Agent 订阅的取消则会结束当前原生调用。

## 读取一条 Thread 的消息与执行事实

Thread 身份为 `ThreadKey(userId, agentId, sessionId, threadId)`。保存身份及底层存储 namespace；同 Session 下不同 Thread 的工作状态和日志独立。直接调用同一 Thread 的并发请求需要应用协调；Durable 提供持久 FIFO 和执行所有权。

```java
import io.agentscope.core.agent.RuntimeContext;
import io.agentscope.core.journal.JournalProjection;
import io.agentscope.core.journal.ThreadHistory;

RuntimeContext ctx = RuntimeContext.builder()
        .userId("alice")
        .sessionId("conversation-001")
        .threadId("main")
        .build();

var journal = agent.threadJournal(ctx);
var transcript = ThreadHistory.transcript(journal);
transcript.messages().forEach(message ->
        System.out.println(message.getTextContent()));
var projection = JournalProjection.read(journal);
var events = journal.readAfter(0, 100);
```

读取不认领 writer，不修复状态，也不启动模型。Harness 也提供 `threadTranscript(ctx)` 和 `inspectThread(ctx)`。原生查询与存储 SPI 是阻塞接口，WebFlux 应将它们调度到适当的阻塞工作线程，不占用网络事件线程。

`ThreadHistory.Transcript` 包含完整消息和 `asOfSeq`，不是包含任务控制、流式片段和工具进度的公共页面快照。Durable 应用通过 Session/Thread/Turn 的 `snapshot()`、`history()` 和事件接口恢复页面；不要由业务层解析原生日志来推断 Turn 状态。可运行示例见[可恢复聊天](/v2/zh/blogs/best-practices/session-chat)。

## 事件结构与读取

`AgentEvent` 是本次调用的实时事件；`JournalEvent` 是原生已提交事实。实时发出不等于持久提交。原生 `seq` 在每条 Thread 内递增，不能与 Durable 的 Session 公共 cursor 混用。

| 字段 | 含义 |
| --- | --- |
| `schemaVersion` | 当前原生事件版本，值为 2 |
| `eventId / seq` | 事件身份与 Thread 内提交顺序 |
| `occurredAt / type` | 时间与原生事件类型 |
| `executionRunId / turnId` | 调用归属及可空的宿主任务关联；状态初始化不虚构 Run |
| `required / payloadJson` | 是否影响恢复及已冻结的 JSON 载荷；Step 身份和阶段在相应载荷中 |

| 事件族 | 内容 |
| --- | --- |
| `run/start`, `run/end`, `run/output` | 一次调用的开始、outcome 与输出 |
| `step/start`, `step/phase`, `step/end` | Run 内步骤与恢复阶段 |
| `input/received`, `input/applied` | 本次调用接收与应用的输入 |
| `model/dispatch`, `model/chunk`, `model/end` | 逻辑模型调用与输出；end 保留最后已报告的用量和观测范围 |
| `model/request_started`, `model/request_usage`, `model/request_ended` | 共用请求边界内每次传输尝试的独立身份、来源、部分用量与结果 |
| `tool/requested`, `tool/dispatch`, `action/end`, `tool/result` | 工具决策、执行与结果 |
| `interaction/requested`, `interaction/resolved` | 原生外部请求及回答 |
| `state/initialized`, `state/checkpoint`, `state/restored`, `recovery/applied` | 当前格式的初始化、状态和核实事实 |

一次 Step 通常包含模型决策及其零个或多个工具调用。恢复 Step 可以只应用原工具结果；它仍属于新的 Run，通过来源关联连接旧 Step。原生 `run/end` 不宣布持久 Turn 成功，该决定由 Durable 结合输入与外部执行结清情况作出。

`ModelUtils` 在重试内部使用调用级 `ModelRequestObserver` 记录请求，不将传输重试折叠成一次。
未采用该边界的自定义模型只报告逻辑调用，不推断隐藏请求数。请求结束前的用量可单独提交；
失败或失联不能把缺失用量当成零。持久记录失败不会触发模型重试，已提交结果回放也不生成新请求。

## 存储位置与后端配置

Harness 默认使用 `WorkspaceThreadJournalStore`，跟随 Workspace Filesystem 的作用域。POSIX 本地实现使用 `.agentscope-runtime/` 中的原子文件提交；Windows 使用该目录中的 `journal.sqlite3`。远程实现复用 BaseStore 保留分区。不要手工修改内部文件或键。

可以通过 `.threadJournalStore(store)` 指定实现。自定义后端实现 `ThreadJournalStore`；已有原子存储可实现 `AtomicJournalStorage` 并复用 `AtomicThreadJournal`。必须保持 CAS、writer fencing、提交顺序、幂等批次和导出游标。Filesystem 通过 `journalStorage(context)` 提供此能力，普通文件读写不足以实现原子日志。

`ThreadJournalStore.list(ctx)` 只列出调用者当前存储作用域内的 Thread；SESSION 隔离不意味着可以枚举其他 Session。内存实现只适用于进程内使用。共享持久存储本身不提供后台认领与自动恢复，需要这些能力时装配 Durable Backend。

原生日志格式版本保存在权威 `head.json`，当前版本为 2。缺失版本、不支持的版本和损坏数据明确报错，不自动转换、清空或新建日志掩盖旧数据。恢复读取提交链，不以目录扫描推断哪个片段已经提交。

不需要执行日志时，使用 `.journalEnabled(false).stateStore(snapshotStore)` 保存当前工作状态。状态快照模式不能提供完整历史事件。使用日志时，checkpoint 是原生恢复权威；受 Durable 管理时，工厂返回 Agent Builder，由 adapter 安装本次调用的 Journal，并与 Backend 统一提交状态和执行事实。

## 显式恢复与工具结果核实

直接调用恢复依靠已保存的原生阶段、输入及工具结果。完成后发起新调用只复用上下文，执行计数从零开始；续接未完成调用保留累计进度和来源。它不恢复任意 Java 栈，也不保证重启后自动执行。

`ThreadInteractions.pending(journal)` 查询待处理原生请求；`continuationRequests` 验证指定请求仍待处理并返回原 Run/Step 来源。普通 HITL 调用方式见[Agent 人机交互](/dev/zh/docs/building-blocks/agent#人机交互)。Durable 的持久交互使用所属 Turn 的 `respond`，业务应用不绕过 Backend 直接修改受管日志。

对执行结果不确定的工具，先查原操作，取得真实结果后再调用 `agent.reconcileToolOutcomes(ctx, outcomes, reason)` 或 `ThreadRecovery.reconcile`。核实结果与 checkpoint 一起提交，保留原执行来源。工具结果未知时不能通过重新执行有副作用的工具来猜测结果。

## 导出到其他系统

`ThreadTranscriptExport` 可导出消息视图；`JournalExporter` 配合 `JournalExportSink` 导出原生事件。sink 确认成功后才推进持久游标，失败后可重新 drain，因此目标系统需要按事件身份去重。

将 sink 放入调用 RuntimeContext 的 `JournalExportSink.CONTEXT_KEY` 可触发执行期间的导出。导出失败后的补投仍需应用安排；公共 SSE 和业务消费应使用 Durable 事件接口，不直接暴露包含私有状态的 journal。

## 记录应用自己的事件

在工具或 middleware 的本次调用上下文中取得 `InvocationRecorder.from(ctx)`，调用 `record(type, payload)` 并等待返回的 `Mono` 完成。诊断事件可通过 `append(type, payload, false)` 追加，再等待 `flush()`。不要在调用结束后继续使用 recorder。

影响恢复的扩展事件需在执行和恢复前通过 `JournalEventCodecRegistry` 注册验证及状态应用逻辑。未知的 required 类型会阻止恢复，不能静默忽略。应用事件也遵守同一提交和 writer 校验。

## 宿主托管的迭代预算

`AgentExecutionPosition` 记录累计逻辑推理进度；它不同于当前 Run 的 Step 索引。Durable 在同一 Turn 的多次 Run 间保留预算，工具结果续接不额外消耗一次模型推理。普通已完成调用后的下一次调用使用新的预算。

## 宿主托管的收尾恢复

Core 的 `AgentExecutionHost` 在 reasoning、model、acting 和 finalization 边界与宿主协作。接口在 Core，Durable 实现在 adapter；Harness 不依赖 Durable。已生成最终回答但尚未完成收尾时，应保存回答和恢复位置，再确认暂停；继续执行不重新生成同一回答。

## 初始化、导出状态与 fork

`ThreadCheckpoints` 提供当前格式的 `initialize`、`exportSnapshot`、`exportState` 与 `fork`。源日志需无活动 writer，先核实不确定工具并解决未完成执行和待处理交互。`exportSnapshot` 返回状态及 `asOfSeq`；`initialize(target, state, source, sourceSequence)` 在空目标写入有来源的 `state/initialized`，不伪造历史 Run。

fork 保留合法工作状态，并替换目标 user/session/thread 身份。它不能撤销外部副作用，也不复制可执行的旧任务。Durable 管理的 Thread 应使用其公共 checkpoint/restore/fork 接口，避免绕过任务与归属校验。

## 日志持久化调度

提交在 `agentscope-session-commit` 有界线程池执行，不阻塞模型请求线程。同一 recorder 串行提交；并发 `flush()` 等待各自已接受的事件前缀，取消一个等待者不会取消共享提交。提交失败或所有权失效会使执行边界失败，不能继续发起新副作用。

JVM 参数 `agentscope.journal.commitThreadCap`、`maintenanceThreadCap`、`leaseThreadCap` 和 `taskQueueCapacity` 配置相应通道。扩大队列不能提高存储吞吐；后端容量与线程数需匹配。模型准入策略使用 `ModelCallPolicy`，没有实际钩子时不安排空任务。

<span id="基本概念与-api" />
<span id="场景一聊天排队与重启恢复" />
<span id="场景二运行中补充信息与前端续传" />
<span id="场景三回复-hitl-待办" />
<span id="场景四中断后继续原任务" />
<span id="sessionturn-和-run" />
<span id="场景五排查执行导出记录和更换后端" />
<span id="历史事件与-checkpoint" />
<span id="完整示例可恢复的-web-chat" />

<span id="核对结果未知的工具" />

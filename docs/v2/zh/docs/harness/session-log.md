---
title: 会话操作、事件与恢复
description: 从后台聊天出发，学习提交任务、补充信息、回复待办和中断恢复，并用会话日志恢复页面、排查执行及配置存储。
en_link: /v2/en/docs/harness/session-log
---

用户让 Agent 比较两份方案，等待期间又补充了一条要求，随后关闭页面。再次打开时，应用应该展示之前的回答、工具执行结果和当前进度；如果任务曾被中断，还应该能从保存的状态继续。

这涉及两件事：**谁负责把任务继续执行下去，以及执行过程保存在哪里**。`AgentSession` 提供提交、引导、回复和恢复等会话操作；Session Log 保存输入、消息、工具执行和工作状态。前端读取这些记录，就能在重新连接后恢复页面，而不必重新发起任务。

本文从这个聊天场景逐步介绍用法，再说明事件查询、存储配置和扩展方式。可以配合[可恢复聊天示例](/v2/zh/blogs/best-practices/session-chat)实际体验。通过 AgentScope Service 使用托管 Agent 的应用，直接接入 [Service Agent API](/v2/zh/service/session-event-log) 的 HTTP/SSE 接口即可。

<span id="基本概念与-api" />

## 先选择调用方式

普通问答、多轮聊天，以及随当前请求结束的流式回复，继续使用 `agent.call(input, ctx)` 或 `agent.streamEvents(input, ctx)`。HarnessAgent 默认也会保存这些调用的消息历史和 checkpoint；仅为了记住上一轮对话，不需要改用 `AgentSession`。这类请求推荐从共享 Builder 创建新 Agent，执行结束后关闭，见[快速开始](/v2/zh/docs/quickstart)。

当任务需要在页面关闭后继续，或需要排队、运行中补充要求、回复待办和中断后续做时，再使用 `agent.session(ctx)`。返回的 `AgentSession` 是这段会话的操作入口，由框架持有后台执行；HTTP 请求或前端观察连接结束，不会因此取消任务。

后台执行需要对应的生命周期：应用的会话管理器或任务 worker 可以从共享 Builder 构建 Agent，但应持有它直到后台工作结束或应用退出，不能在提交接口返回时就关闭。具体资源管理方式见[实例生命周期](/v2/zh/docs/building-blocks/agent#实例生命周期)。

<span id="场景一聊天排队与重启恢复" />

## 提交任务，让它在后台执行

假设要做一个“比较方案”的聊天助手。应用启动时配置共享 Builder，由会话管理器构建并持有运行实例，再用用户和会话身份取得 `AgentSession`。下面的 `model` 是已经配置好的[模型](/v2/zh/docs/building-blocks/model)：

```java
import io.agentscope.core.agent.RuntimeContext;
import io.agentscope.harness.agent.HarnessAgent;
import java.nio.file.Path;

HarnessAgent.Builder agentBuilder = HarnessAgent.builder()
        .name("方案助手")
        .agentId("proposal-assistant")
        .model(model)
        .workspace(Path.of("/data/proposal-workspace"));

// 由应用的会话管理器持有，后台任务结束或应用退出时再关闭。
HarnessAgent agent = agentBuilder.build();
RuntimeContext ctx = RuntimeContext.builder()
        .userId("alice").sessionId("conversation-001").build();
var session = agent.session(ctx);

var task = session.submit("request-001", "比较方案 A 和方案 B，先给出初步结论。");
System.out.println(task.turnId());
```

`submit` 会先持久保存输入，再安排执行。返回的 `task` 是接收回执，不是模型回复；任务还在排队时，`runId` 可能尚未分配。Web 接口通常把回执返回前端，再让前端独立读取进度。

这里的 `request-001` 应是业务为这次提交生成的唯一请求标识。网络超时后重试时，沿用相同标识和输入，就会得到同一次提交的回执；用户主动发起另一个任务时，应使用新标识，也可以调用不带标识的 `submit(input)`，由框架生成。

如果用户在比较尚未结束时又提交“根据结论起草一封邮件”，这就是另一项任务，会按接收顺序排队。当前任务等待人工答复或被中断时，队列仍会保留，先处理原任务的待办或继续原任务，再执行后续任务。`session.tasks()` 可以读取任务状态。

在命令行或后台代码中，可以等待这次执行结束，然后读取已保存的消息：

```java
var outcome = session.await(task).block();
System.out.println(outcome.status());
session.transcript().messages()
        .forEach(message -> System.out.println(message.getTextContent()));
```

`await` 只负责观察。它会在本次执行完成、挂起、中断或失败后返回；`suspended` 表示 Agent 正在等用户答复，不代表整个任务完成。取消对 `await` 的订阅也不会取消后台任务。

即使此前一直通过 `agent.call(input, ctx)` 对话，仍可以用相同身份取得 `session` 并读取 `transcript()`，不必再次提交输入。**同一会话选择一套执行方式：直接调用，或由 AgentSession 调度。** 已提交任务的进度通过日志观察；再调用 `streamEvents` 会启动另一次执行。

<span id="场景二运行中补充信息与前端续传" />

## 在执行过程中补充要求或材料

比较正在进行时，用户说“重点看运维成本”。这句话是在调整当前任务，而不是要求再做一次比较。处理这条用户消息的请求可以调用 `steer`：

```java
session.steer(task, "重点比较运维成本，而不是只看采购价格。");
```

框架会把这条要求关联到原任务，在下一次推理步骤交给模型。已经发出的模型请求和正在执行的工具参数不会被改写。传入 `task` 还能检查目标是否仍是当前任务，避免用户在旧页面发出的要求误落到后来的任务上。应在任务仍运行时调用，而不是等前面的 `await` 返回后再调用。

如果补充的是背景资料，例如“团队只有两名运维人员”，可以使用 `inject`：

```java
session.inject("补充材料：团队只有两名运维人员，没有夜间值班安排。");
```

`inject` 保存材料供后续推理使用，但不会单独启动任务。Agent 正在工作且还有后续步骤时，可以在当前执行中读取；会话空闲或当前执行已没有后续步骤时，材料留到后续执行。未被消费的 `steer` 则仍属于原任务，中断后继续该任务时再读取。

因此，业务界面可以把“发送新任务”和“补充当前任务”做成不同操作：新任务调用 `submit`，调整当前工作调用 `steer`，只存入参考资料调用 `inject`。用户不需要理解底层 turn 或 run，也不需要手工设置这些标识。

## 刷新页面后，先恢复内容，再继续接收事件

页面重连与任务执行是两条独立链路。用户刷新页面时，后台可能已经完成了一次工具查询，写出半段比较结论，又开始执行第二个工具。此时仅接收“从现在开始”的新片段，页面就会缺少之前的内容。

先区分两种读取需求。只展示已经保存的完整消息时，使用 `session.transcript()` 即可：它返回消息列表和这份历史对应的 `asOfSeq`。但它不是一个完整的流式聊天界面快照，不包含从 `model/chunk` 重建的进行中文本或工具参数。

如果要恢复“写到一半的回复”和工具卡片，应用需要把已提交事件整理成页面视图。[聊天示例](/v2/zh/blogs/best-practices/session-chat)中的 `ChatHistory` 和 `ChatItems` 已实现这部分：除了完整历史，还恢复文本前缀、ToolCall 参数、ToolResult、执行进度和待答请求。这是示例提供的应用层视图，可以作为你接入前端时的参考。

接入时，围绕同一份快照完成以下流程：

1. **打开或刷新页面，先取快照。** 后端选定一个已提交日志位置，读取截至该位置的记录，生成页面内容，并一起返回 cursor。例如 cursor 对应第 120 条事件，快照就应该包含截至第 120 条的消息和工具状态。
2. **渲染完成后，从这个 cursor 继续观察。** 即使获取快照期间后台已产生第 121～125 条事件，续读也会把它们补上。消息和工具应按稳定 ID 更新原条目，避免同一个结果重复显示。
3. **区分短暂断线与页面刷新。** 页面仍保留内容时，可以从最后已处理的事件位置续读；刷新导致页面状态丢失时，应重新获取完整快照。只在浏览器保存一个 cursor，并不能恢复 cursor 之前的内容。

聊天示例把快照暴露为 `GET /api/sessions/{id}`，再通过 `GET /api/sessions/{id}/stream?after={cursor}` 提供 SSE。它收到 `committed` 通知后会合并频繁通知并重新读取快照，让页面始终显示后端整理好的视图；并不是把每条原生事件都直接交给浏览器拼接。这些 HTTP 接口属于示例应用，SDK 本身提供的是会话与日志读取 API。

重新连接时只做读取，**不要再次调用 `submit` 或 `streamEvents`**。浏览器的连接负责观察已有任务，任务是否继续由会话管理器负责。使用 Service 时，可以直接采用其公共快照、SSE 和 cursor 协议，见 [Service 事件接入](/v2/zh/service/sse-events)。

<span id="场景三回复-hitl-待办" />

## 回复 Agent 的问题或确认请求

假设 Agent 在生成建议前需要询问部署环境，或在执行工具前需要用户确认。通过 `AgentSession` 提交的任务可以挂起等待回答；问题保存在日志里，用户刷新页面、甚至服务重启后仍能看到。

用 `session.pending()` 读取尚未答复的交互。返回值的 key 是 `requestId`，页面应把它与对应的问题一起保存，提交答案时原样带回：

```java
session.pending().forEach((requestId, request) ->
        System.out.println(requestId + " " + request.data().get("kind")));
```

对于 `ask_user` 或外部执行产生的 `external_execution` 请求，把用户答案或真实执行结果交给 `respond`。下面的标识需要替换成页面所回答的待办 ID：

```java
var answered = session.respond("external-request-id", "部署在自建 Kubernetes 集群。");
```

如果待办类型是 `confirmation`，用布尔值表示同意或拒绝，例如 `session.respond(requestId, true)`。需要附带拒绝原因时，可传 `SessionAnswer.reject(reason)`。框架会查找待办所属的原任务和工具调用，应用无需自己拼接工具 ID，也无需再调用 `resume`；`respond` 已经安排了接续执行。

`respond` 返回新的执行回执。需要等待这次接续时，使用 `session.await(answered)`；原提交的 `await(task)` 观察的是挂起前的那次执行。未知、已处理或答案类型不匹配的待办会被拒绝。

需要自定义工具结果块时使用 `SessionAnswer.Output`；需要提交修改后的参数或权限规则时使用 `SessionAnswer.Confirmation`。同一任务内多个并行工具的答案可以用 `respond(Map<String, SessionAnswer>)` 一起提交。Service 的在线交互通过其所属服务的 actions 接口答复。

这是对原有 HITL 机制的会话封装：Agent 仍在原有的工具确认或外部输入位置暂停，Session Log 让待办与后续答案可持久恢复。直接使用 `call` 的应用也可以继续使用[原有 HITL API](/v2/zh/docs/building-blocks/agent#人机交互)，不必仅为人工确认更换执行方式。

<span id="场景四中断后继续原任务" />

## 中断后继续原任务

用户点击“停止”时，调用 `session.interrupt()` 请求当前执行在协作检查点停止。它不是立即终止工具的命令，方法返回也不代表执行已经停下；应等待任务状态变成 `interrupted`，再提供“继续”操作。

下面展示同一任务的停止与接续。Web 应用通常把这两步放在两个用户操作中；这里用 `await` 表示等待停止完成：

```java
session.interrupt();
var stopped = session.await(task).block();
if ("interrupted".equals(stopped.status())) {
    var continued = session.resume(stopped.turnId());
    System.out.println(continued.turnId());
}
```

前端已经知道正在显示的 `runId` 时，可以使用 `session.interrupt(runId)`，避免旧页面的停止操作误中断另一轮执行。`resume` 会恢复已保存的工作状态与尚未应用的原输入，继续原任务；业务无需重发用户消息、构造空输入或设置 turnId。

恢复使用的是 **checkpoint**：执行过程中保存的会话工作状态。它让 Agent 能从已提交状态接着推理，但不会恢复原 Java 线程、网络请求或工具内部执行进度，也不会回滚工具已经产生的外部效果。普通工具所需的凭据、工作文件和外部资源也必须仍然可用。

当前只能继续最新的未完成任务。已经完成或取消的任务不能 `resume`；有待答交互时应先 `respond`。如果之前通过 `agent.call` 或 `agent.streamEvents` 直接执行，中断仍使用运行实例上的 `agent.interrupt`；日志负责保存状态，会话入口负责安排后续任务，二者不是互相替代的机制。

### 服务重启后继续

重启后用相同的 `agentId`、`userId`、`sessionId` 和日志后端重新构建 Agent，再取得 `session`。此时可以直接读取历史和待办；读取本身不会触发模型调用。

```java
session.transcript().messages()
        .forEach(message -> System.out.println(message.getTextContent()));
session.start(); // 应用启动时，重新开启已接收任务的调度。
```

`start()` 会检查持久队列，但不会擅自恢复已中断的任务；由应用决定何时调用 `resume`，或等待用户回答待办。`submit`、`resume`、`respond` 本身会自动开启调度，因此正常交互不必每次手工调用 `start()`。

关闭所属 `agent` 会停止调度并中断其持有的执行，已提交日志和队列由持久后端保留。如果进程被强制终止，可能还需要等待旧执行的写入租约释放或到期，新的执行才能安全接手。

### 核对结果未知的工具

假设工具已经在订单系统创建了订单，但进程在记录工具结果之前退出。日志能说明工具曾被调用，却不能证明订单创建成功还是失败；直接重试可能重复创建订单。

这种情况下，先通过 `session.inspect().uncertainToolCalls()` 找出需要核对的工具调用，再查询真实业务系统。确认结果后，把对应的工具调用 ID、工具名称和实际结果交回框架：

```java
import io.agentscope.core.message.TextBlock;
import io.agentscope.core.message.ToolResultBlock;
import io.agentscope.core.message.ToolResultState;
import java.util.Map;

System.out.println(session.inspect().uncertainToolCalls());

// ID 与名称来自待核对调用，结果来自对订单系统的实际查询。
var result = ToolResultBlock.builder()
        .id("call-id-from-inspection").name("create_order")
        .output(TextBlock.builder().text("已核实订单 ORD-42 创建成功").build())
        .state(ToolResultState.SUCCESS).build();
agent.reconcileToolOutcomes(ctx, Map.of(result.getId(), result), "已核对订单系统记录");
```

结果集合需要恰好覆盖检查中列出的未知调用。这个操作只保存核对结果和 checkpoint，不再次调用工具；之后再显式 `resume` 原任务。实际结果仍未知时，不应填一个空的成功结果来绕过检查。

<span id="sessionturn-和-run" />

## session、turn 和 run 如何对应

可以把 session 理解为一段持续对话，turn 理解为用户提交的一项任务，run 理解为完成该任务的一次实际执行。一次 run 中可以多次推理、调用多个工具，并生成多条消息；它不等于一次模型调用或一条 AssistantMessage。

例如，用户在会话 `S1` 中要求“比较方案，发邮件前先让我确认”：

| 用户操作或执行状态 | turnId | runId |
| --- | --- | --- |
| 提交比较任务，Agent 开始推理并查询资料 | `T1` | `R1` |
| 刷新页面，恢复消息和工具卡片 | `T1` | 仍是 `R1` |
| 补充“重点比较成本”（`steer`） | `T1` | 仍是 `R1` |
| 中断任务，之后点击继续 | `T1` | `R1` 结束，新建 `R2` |
| Agent 挂起等待确认，用户答复后继续 | `T1` | `R2` 结束，新建 `R3` |
| 完成后提交另一项任务 | 新建 `T2` | 新建 `R4` |

表中的 `sessionId` 始终是 `S1`。这里展示的是挂起式 HITL；如果采用执行仍保持存活的在线等待方式，收到答复后也可以继续原 run。业务通过 `submit`、`steer`、`respond` 和 `resume` 表达意图，框架负责关联这些身份。

日志还使用稳定的 `agentId` 和 `userId` 区分 Agent 与用户。不要把提交请求的幂等 key、HITL 的 `requestId`、工具调用 ID 或事件 `eventId` 混作 turnId：它们分别用来防止重复提交、回答待办、关联工具结果和去重事件。

<span id="场景五排查执行导出记录和更换后端" />

<span id="历史事件与-checkpoint" />

## 事件结构与读取

前面的页面恢复、待办展示和中断续做，都依赖同一份 Session Log。它保存“执行中发生了什么”，再从记录中生成不同视图：`transcript()` 用于消息历史，`pending()` 用于待办，`inspect()` 用于恢复检查。checkpoint 保存继续执行所需的工作状态；上下文压缩后，界面历史仍可保留更早的消息，模型后续使用的工作上下文则可能已经缩短。

`AgentEvent` 是 `streamEvents` 产生的实时通知，适合展示当前请求；`SessionEvent` 是已持久保存的记录，适合历史读取、恢复和导出。实时通知到达不代表对应日志已经提交，需要可靠重放时应以 Session Log 的已提交内容为准。Service 的公共 SSE 还会对事件做面向客户端的整理，它的 cursor 与 SDK 原生序号不是同一个协议。

排查某次任务时，可以按 `turnId` 查看它跨多次执行的记录，再按 `executionRunId` 细看一次执行。下面从当前日志末尾确定读取范围，不会触发模型或工具：

```java
var log = session.log(); // 也可以使用 agent.sessionLog(ctx)。
long through = log.head().seq();
for (var event : log.scan(0, through)) {
    System.out.printf("%d %s turn=%s run=%s%n",
            event.seq(), event.type(), event.turnId(), event.executionRunId());
}
```

`seq` 是当前会话内从 1 开始递增的持久序号，排序和续读都使用它。`scan(0, through)` 读取截至 `through` 的固定范围；需要分批续读时，使用 `readAfter(lastSeq, limit)`，处理完成后再保存最后一条事件的 `seq`。`eventId` 用于接收端去重，不能当作顺序号。

这些日志读写和 `AgentSession` 的同步操作会访问存储。在 WebFlux handler 中，应使用 `Mono.fromCallable(...).subscribeOn(Schedulers.boundedElastic())` 调度，避免阻塞网络事件线程；`await` 自身已安排好状态轮询。

### 事件目录

普通聊天应用不需要逐项处理下面所有事件。需要构建时间线、查询工具结果或做诊断时，再按关注的内容读取对应类型。

| 关注什么 | 事件类型 |
| --- | --- |
| 执行开始、结束与停止请求 | `run/start`、`run/end`、`run/stop_requested` |
| 任务开始、接续与输出 | `turn/start`、`turn/resumed`、`turn/output` |
| 任务完成、挂起、失败、中断或取消 | `turn/completed`、`turn/suspended`、`turn/failed`、`turn/interrupted`、`turn/cancelled` |
| 推理步骤 | `step/start`、`step/end` |
| 输入接收、应用或丢弃 | `input/received`、`input/applied`、`input/discarded` |
| 系统、用户和 Agent 消息 | `message/system`、`message/user`、`message/assistant` |
| 模型请求、返回片段、用量与重试 | `request/prepared`、`model/dispatch`、`model/chunk`、`model/end`、`model/retry` |
| 工具请求、权限判断、参数、进度与结果 | `tool/requested`、`tool/decision`、`tool/dispatch`、`tool/chunk`、`tool/result` |
| 工具内具体动作的边界 | `action/start`、`action/end` |
| 人工待办及其答复 | `interaction/requested`、`interaction/resolved` |
| 上下文构建、替换与压缩 | `context/build`、`context/replaced`、`compaction/start`、`compaction/end` |
| 任务、计划、权限与验证状态 | `task/changed`、`plan/changed`、`permission/changed`、`verification/result` |
| 子 Agent 与父会话的关联 | `subagent/spawned`、`subagent/completed`、`subagent/linked`；完整子 Agent 过程在其自己的日志中 |
| 状态快照、恢复核对与导入 | `state/checkpoint`、`state/restored`、`recovery/applied`、`migration/baseline` |
| 展示提示与应用扩展 | `presentation/hint`、应用自己定义的类型 |

记录范围是模型适配器可见的请求与输出、工具和框架执行过程，不包括模型服务内部过程，也不会自动备份被引用的文件。原始请求和工具结果可能包含业务数据，面向浏览器的接口应挑选需要展示的字段。

`compaction/end` 的载荷包含 `compactionId`、`status`、`beforeMsgCount` 和
`beforeTokenCount`。当 `status` 为 `completed` 时，还会包含根据压缩后有效对话计算的
`afterMsgCount` 和 `afterTokenCount`。压缩失败或取消时不会伪造压缩后指标，因为此时没有可用的压缩后上下文。

<Accordion title="集成时需要的 SessionEvent 字段">

每条 `SessionEvent` 的载荷都会冻结为 JSON，后续修改原对象不会改变已记录内容。

| 字段 | 用途 |
| --- | --- |
| `schemaVersion` | 事件结构版本 |
| `eventId` | 单条事件的稳定 ID，用于去重 |
| `seq` | 会话内的持久顺序 |
| `occurredAt` | 事件时间；排序仍使用 `seq` |
| `type` | 选择解析和处理方式 |
| `turnId` / `executionRunId` | 关联逻辑任务与实际执行；部分管理操作没有 turnId |
| `payloadJson` / `data()` | JSON 载荷及解析后的 Map |
| `required` | 恢复时是否必须识别该事件类型 |

</Accordion>

## 存储位置与后端配置

默认情况下，HarnessAgent 使用 `WorkspaceSessionLogStore`，日志跟随 Workspace 的 Filesystem 保存。**Workspace 可以使用本地磁盘，也可以使用分布式存储。** 创建新 Agent 实例或重启应用后，只要身份与存储配置保持一致，就能找到原会话。

| 配置 | 去哪里查找记录 |
| --- | --- |
| 本地 Filesystem | 当前身份解析后的 Filesystem 根目录下 `.agentscope-runtime/` |
| RemoteFilesystem | 对应 BaseStore namespace 下的 `__agentscope_session_log_v1__` 分区 |
| 单独配置日志后端 | `.sessionLogStore(store)` 指定的后端 |
| AgentScope Service 托管 | 由服务配置原生日志和公共事件存储，见 [Service 会话日志](/v2/zh/service/session-event-log) |

Linux 和 macOS 的本地存储会先同步临时文件，再通过原子替换提交记录，并同步父目录。Windows 的 Java 文件系统不能打开目录来执行这种同步，因此本地 Session 存储改用 `.agentscope-runtime/journal.sqlite3` 中的 SQLite 事务，启用回滚日志和 `synchronous=EXTRA`。写入仍需通过原子的版本比较，存储错误也会返回给调用方。应用继续通过 SDK 读取记录；备份 Windows 工作区时，应先暂停所有写入再复制数据库，或者使用 SQLite 的一致性备份。POSIX 文件和 Windows 数据库存放的是同一套逻辑对象，但物理格式不同，跨平台迁移历史时需要进行日志迁移，或者使用共享后端。

这里的“当前身份”由 `SessionKey(userId, agentId, sessionId)` 和 Filesystem 的 namespace 共同确定。开启用户或会话隔离后，实际根目录会随身份变化，因此不能把所有会话都理解为写入 Workspace 下同一个固定目录。多副本必须连接同一份日志存储，并使用一致的身份与 namespace；仅让每个副本使用同名的本地目录并不能共享会话。

### 将日志存到共享后端

如果工作文件放在本机或沙箱，但希望会话日志跨节点保存，可以单独配置日志后端。下面的 `sharedStore` 是应用已经配置好、支持原子版本写的 `BaseStore`：

```java
import io.agentscope.harness.agent.filesystem.remote.RemoteFilesystem;
import io.agentscope.harness.agent.session.WorkspaceSessionLogStore;
import java.util.List;

var logStore = new WorkspaceSessionLogStore(
        new RemoteFilesystem(sharedStore, List.of("my-app", "session-history")));
HarnessAgent.Builder sharedLogBuilder = HarnessAgent.builder()
        .name("方案助手").agentId("proposal-assistant")
        .model(model).workspace(Path.of("/data/proposal-workspace"))
        .sessionLogStore(logStore);
```

随后从这个 Builder 构建 Agent 即可，调用方式不变。可选后端及连接方式见[分布式存储集成](/v2/zh/integration/distributed/index)。内存后端适合测试，进程退出后不会保留历史。

共享存储不会让直接调用的同会话请求自动排队，这类请求仍需应用协调。使用 AgentSession 时，任务通过持久输入队列接收和排队，并由已启动的会话调度器执行。会话列表也受当前 namespace 范围限制，SESSION 隔离下的列表不能枚举其他会话。

### 自定义后端与备份

一般应用只需选择现成后端。需要接入自己的存储时，可以实现 `SessionLogStore`，或实现 `AtomicSessionStorage` 并复用 `JournalSessionLog`。它们需要支持原子版本比较写入、执行写入租约与旧写入者隔离、有序幂等提交及导出进度持久化；普通文件的读写接口不足以保证这些语义。

自定义 Filesystem 应提供 `sessionStorage` 能力，或者单独指定日志后端。使用 AgentSession 命令还要求 `SessionLog.inbox()` 支持持久输入队列；默认 Workspace 和内存日志已提供。需要列出会话时，使用 `SessionLogStore.list(RuntimeContext)`。

备份建议使用后端一致性快照，或先暂停写入，再保存会话记录。工作文件、沙箱内容、外部产物和工具依赖需要另外备份，只有日志不足以完整恢复所有外部环境。

<Accordion title="需要运维或实现后端时：日志对象与输入队列">

每个会话在日志根目录或分区下保存如下对象，身份段使用 Base64URL 编码：

```text
agents/s_<agent>/sessions/s_<user>/s_<session>/
  session.json
  head.json
  commits/<batch-hash>.json
  blobs/<sha256>
  exports/<sink-hash>.json
  inbox/
    session.json
    head.json
    commits/<batch-hash>.json
```

`commits` 保存提交批次，`blobs` 保存较大的载荷，`exports` 保存各导出目标的确认位置。备份需覆盖会话头、日志头及其可达的 commits、blobs 和导出记录，也要包含 `inbox/` 下的日志头及其可达对象，否则尚未执行的已接收输入可能丢失。这些对象并非供应用逐行解析的 JSONL；POSIX 本地对象带有版本前缀，Windows 则把逻辑路径、版本和载荷保存为 SQLite 数据行，应通过 SessionLog API 读取。

`inbox` 保存任务、引导、材料及回复的接收记录，使输入在执行前也可以持久保留。它有独立的序号，不与执行日志的 `seq` 或 SSE cursor 混用。输入在 checkpoint 提交后才标记为已应用，消费也不会删除原接收记录。

排查接收与执行衔接时，输入队列中的 `inbox/accepted`、`inbox/opened`、`inbox/closed`、`inbox/handled`、`inbox/rejected` 记录接收和处理状态；执行日志中的 `inbox/started`、`inbox/applied` 关联实际运行与输入应用。

同一 Agent 会复用相同身份的 AgentSession，并使用首次创建时的 RuntimeContext 副本调度。用户身份、namespace 和会话级配置应保持稳定；之后每条消息通过会话操作传入。

</Accordion>

## 导出到其他系统

如果要把执行历史送到审计系统或数据仓库，使用 `SessionLogExporter` 读取已提交事件，并交给应用实现的 `SessionExportSink`。导出只读取历史，不会重新运行 Agent。

```java
import io.agentscope.core.session.SessionLogExporter;

// sink 是应用实现的 SessionExportSink，负责持久写入目标系统。
var exporter = new SessionLogExporter(session.log(), sink);
exporter.drain();
```

`sink.accept` 返回应表示目标已经持久接收。导出失败后可能重复投递同一事件，目标端需按 `eventId` 去重；`sink.name()` 应跨部署保持稳定，框架用它保存各目标的导出进度。应用可以在启动、空闲时或定时任务中再次 `drain()` 补投，并协调多副本对同一会话和目标的导出。

需要执行时自动触发导出时，先将 sink 放入 RuntimeContext 的 `SessionExportSink.CONTEXT_KEY`，再首次调用当前 Agent 实例的 `agent.session(ctx)`。这不能替代失败后的补投安排。Service 已提供公共事件导出与 SSE，使用托管接口的业务通常不必自行实现这一层。

## 记录应用自己的事件

例如，人工复核或业务校验发生在工具或中间件中，希望它也出现在执行时间线上，可以从本次运行的 `RuntimeContext` 取得 `SessionRecorder`，写入带应用前缀的事件类型：

```java
import io.agentscope.core.agent.RuntimeContext;
import io.agentscope.core.session.SessionRecorder;
import java.util.Map;
import reactor.core.publisher.Mono;

static Mono<Void> recordReview(RuntimeContext context, String note) {
    SessionRecorder recorder = SessionRecorder.from(context);
    if (recorder == null) return Mono.empty();
    recorder.append("acme/review_note", Map.of("note", note), false);
    return recorder.flush();
}
```

将这个方法返回的 `Mono` 组合进工具或中间件的调用链，才能等待事件提交。这里的 `false` 表示该事件只提供诊断信息，恢复状态时不要求识别它。不要在调用结束后继续保留 recorder 写入。

如果自定义事件还要参与状态恢复，需要在运行和恢复前通过 `SessionEventCodecRegistry` 注册校验器与状态还原逻辑。标记为 `required` 却无法识别的事件会阻止恢复，因此普通业务诊断事件通常使用 `required=false`。

## 迁移与分支会话

已有应用若保存的是 `AgentState`，可以把它作为一份起始状态导入新的空会话。需要从当前对话尝试另一条路线时，则可以建立新的分支会话，保留源会话不变。

| 需求 | API |
| --- | --- |
| 将完整状态导入空目标，并记录来源 | `SessionMigration.importBaseline(target, state, source, sourceVersion)` |
| 导出状态及其日志位置 `asOfSeq` | `SessionMigration.exportSnapshot(source)` |
| 只导出工作状态 | `SessionMigration.exportState(source)` |
| 用新身份从当前状态建立会话 | `SessionMigration.fork(source, destination, destinationKey, sourceReference)` |

导出或分支前应停止源执行，并处理未知工具结果、尚未结束的执行和待答交互。导入保留的是当时的状态，不会补造更早的执行历史；分支也不会自动复制原会话的完整历史、沙箱或外部资源。

<span id="完整示例可恢复的-web-chat" />

## 把这些能力接到完整应用中

[可恢复聊天示例](/v2/zh/blogs/best-practices/session-chat)可以离线运行，也可以接入真实模型。建议先启动它，发送多步任务，在工具执行和文本生成期间刷新页面；再试一次待办答复、中断与继续。这样可以直接看到：提交接口负责表达用户意图，后台会话负责执行，快照与事件负责恢复页面，checkpoint 负责继续任务。

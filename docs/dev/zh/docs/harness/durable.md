---
title: "Durable 持久执行"
description: "将持久 Turn、AgentScope 原生恢复与独立工具执行嵌入现有应用。"
en_link: /dev/en/docs/harness/durable
---

Durable 为已有 Agent 增加持久任务受理与恢复。Agent 保留自己的推理循环、
权限检查、ThreadJournal 和工作区。宿主重启后，可以从兼容的原生恢复点继续原 Turn。

当前接口处于源码开发阶段，尚未发布制品。使用以下坐标前，请构建同一版本的源码。

## 选择任务边界

当任务需要跨越 HTTP 请求、观察连接或执行进程的生命周期时，使用 Durable。
如果调用方自行管理执行和状态持久化，仍可直接调用 `ReActAgent` 或 `HarnessAgent`。

| 资源 | 用途 |
| --- | --- |
| Session | 一次业务会话及其 Thread 目录、公共事件时间线 |
| Thread | 一个 Agent 的持续上下文、固定定义和有序任务队列 |
| Turn | 一次提交的任务；应用保存、观察和控制的对象 |
| Run / Step | 排查一次真实调用及其中的推理、工具处理过程 |

恢复保留 Session、Thread 和 Turn 身份。替换宿主发起调用时创建新的 Run 和 Step，
不会新建用户任务。普通应用使用 Session 的主 Thread 即可；需要独立上下文或持久子 Agent
时再创建其他 Thread，不要为每次重试或重连创建 Thread。

## Java 模块与部署边界

库 JAR 位于 `durable-runtime`、`durable-worker` 和 `durable-adapters`。adapter 聚合模块包含
AgentScope Harness adapter、Claude Managed Agents 事件 adapter 与 AG-UI 事件 adapter；
`agentscope-examples/durable` 是独立示例聚合模块。库由宿主打包，引入依赖不会自动启动服务。
AgentScope adapter 依赖 runtime、Core 和 Harness；
worker 仅依赖 runtime（以上不含测试依赖）。不再有独立的 codec 制品。

- Brain 使用 `AgentScopeAdapter` 驱动原生 Agent，`DurableRuntime` 管理 Turn 调度与恢复。
- 只处理 checkpoint 的后端使用 `agentscope-durable-adapter-harness-agentscope` 中的
  `io.agentscope.durable.codec.agentscope.AgentScopeCheckpoints` 和
  `AgentScopeCheckpointCodec.STATE_VERSION`。这些 codec 操作不构造或启动 Agent，
  但引入制品会带入 Harness 依赖。codec 准备并校验原生 baseline，
  `SessionBackend` 负责事务安装及目标空闲/位置检查。
- `agentscope-durable-worker` 同时提供调用侧可靠交付和 Hands 执行管理。
  `QueuedExecutor` 部署在 Brain 或 Backend 服务中，持久化命令、接收结果并交给 Backend；
  `WorkerRuntime` 配合 `ExecutionStore` 部署在 Hands，管理工具执行、恢复、取消和输出。
  `BatchExecutor` 组合子工具执行。引入 worker JAR 不等于启动 Hands。
- 本地宿主可将这些对象放在同一 JVM。独立部署时，由 HTTP/RocketMQ 扩展连接边界；
  RocketMQ 扩展两侧都依赖 worker，分别装配 `RocketMqRemoteExecutor` 和 `RocketMqWorker`。
  不需要为了区分部署角色再拆分库模块。

## 嵌入宿主

使用与 Core/Harness 同版本的
`io.agentscope:agentscope-durable-adapter-harness-agentscope` 和
`io.agentscope:agentscope-extensions-durable-jdbc`。
SQLite 是默认本地持久后端；PostgreSQL 为多个兼容宿主提供共享存储。
两种方式都不要求部署 Worker 或消息队列。

```java
import io.agentscope.core.ReActAgent;
import io.agentscope.durable.DurableRuntime;
import io.agentscope.durable.DurableTypes.SessionKey;
import io.agentscope.durable.agentscope.AgentScopeAdapter;
import io.agentscope.extensions.durable.jdbc.LocalBackend;
import java.nio.file.Path;
import java.util.Map;

var backend = LocalBackend.open(Path.of("./durable"));
var adapter = AgentScopeAdapter.forReAct(binding ->
        ReActAgent.builder().name("assistant").model(model), Map.of());
var runtime = DurableRuntime.builder().backend(backend)
        .harness("assistant", "definition-v1", adapter).build();
var session = new SessionKey("application", "conversation-1");
runtime.createSession(session, "assistant");
var receipt = runtime.getSession(session).submit("request-1", "Describe this workspace");
runtime.start();
```

应用使用已有配置提供 `model`。在宿主生命周期内保持 Runtime 运行，关闭宿主时调用
`close()`。相同请求 ID 与输入再次提交时返回原 Turn；改变输入会发生冲突。
通过 `receipt.status()` 查询状态。

使用 `AgentScopeAdapter.forHarness` 提供返回全新 `HarnessAgent.Builder` 的工厂，并配置工作区。
两种工厂都返回 Builder；adapter 注入本次调用的 Thread Journal 后构建 Agent。
公开 Binding 只提供定义、运行时上下文和 Durable workspace。Runtime 持有 Durable 租约和任务调度；
ThreadJournal 负责 Agent 原生历史，Durable 调度仍由 Durable Runtime 负责。
namespace 映射为 `RuntimeContext.userId`，身份限定在每次调用内。

### 通过 Durable Worker 执行模型推理

使用 `withModelExecutions` 将 ReAct 或 Harness Agent 的推理交给 `ModelWorker`。
Agent 先保存恢复位置，再提交模型请求。Worker 按路由认领请求、持有租约、调用模型并按序提交分片批次。
首批分片提交后，原 Turn 即可恢复。共享同一后端的兼容 Runtime 可用原 Session、Thread、Turn 和模型调用 ID，
回放已提交批次并读取后续分片。同一 Thread 的后续 Turn 等当前 Turn 结束后再执行。

本地部署可以用 SQLite 保存状态、索引和分片正文。

```java
var records = JdbcRecordStore.sqlite(Path.of("./durable.db"));
var objects = new RecordObjectStore(records);
var wakeups = Wakeups.local();
var backend = new TransactionalSessionBackend(records, objects).withWakeups(wakeups);
var executions = new TransactionalModelExecutionStore(
        records, new ObjectModelOutputStore(objects), wakeups);
var adapter = AgentScopeAdapter.forReAct(
        binding -> ReActAgent.builder().name("assistant").model(model), Map.of())
        .withModelExecutions(executions, "reasoning");
var runtime = DurableRuntime.builder().backend(backend)
        .harness("assistant", "definition-v1", adapter).build();
var worker = new ModelWorker(executions, "reasoning", ModelExecutor.using(name -> model));
worker.start();
runtime.start();
```

在宿主运行期间保持 Worker 和 Runtime 运行，并在退出时关闭。
`ModelExecutor.using` 根据请求中的模型名，从 Worker 自己的模型配置解析模型。

分布式部署时，Session 后端、Agent 和 Worker 共享事务数据库，例如通过 `JdbcRecordStore` 接入 PostgreSQL。
数据库保存执行状态、分片序号和存储引用。为 `TransactionalSessionBackend` 和 `ObjectModelOutputStore`
配置同一套 `S3ObjectStore` 或 `OssObjectStore`，将交接调用的分片正文及其 Journal 内容保存到对象存储。
直调的 Journal 分片继续按原有的大小阈值存储。
通过 `RocketMqWakeups` 通知 Worker 和 Runtime。通知延迟或丢失时，轮询仍可从持久状态发现变化。

自定义 `ModelOutputStore` 可以将正文写入支持回放的消息队列。`write` 返回稳定的引用，例如 topic、
partition 和 offset。`read` 必须允许任意实例按该引用读回同一批次，保留期应覆盖 Turn 的恢复窗口。
写入成功后，Runtime 才提交引用。如果 Worker 在启动模型调用后丢失租约，执行状态会结束为 `UNKNOWN`，
Runtime 可处理这一结果，避免自动发起第二次模型调用。

### 通过消息队列执行模型推理

将 `adapter.withQueuedModel(publisher)` 注册为当前定义的 Harness。ReAct 和 Harness 工厂均支持。
发布方收到的 `QueuedModelRequest` 包含目标 Thread、Turn、调用 ID、模型名、已准备的消息、工具定义和生成参数。
发布方应在队列确认接收后返回。如果发送成功但确认丢失，恢复时可能按原调用 ID 重投，并导致额外模型请求。
Worker 使用自身配置的模型凭据。Harness 的记忆和收尾阶段沿用各自的模型调用路径。

适配器在投递前保存已准备好的请求和 Agent 恢复位置。投递完成后，Turn 进入等待并释放本次 Runtime 执行。
共享同一后端的兼容 Runtime 可以接续原 Turn。同一 Thread 的后续 Turn 可以提交，但仍会在等待中的 Turn 之后排队。
回包消费者通过原请求定位等待中的 Turn：

```java
var turn = runtime.getSession(request.thread().session())
        .getThread(request.thread().threadId()).getTurn(request.turnId());
String deliveryId = java.util.UUID.randomUUID().toString();
boolean accepted = QueuedModelRequest.begin(turn, request.callId(), deliveryId);
```

每次生成前只调用一次 begin。accepted 为 false 时确认 MQ 投递并返回。同一 generation 的所有分片沿用同一 deliveryId。

```java
var signal = QueuedModelRequest.reply(
        request.callId(), deliveryId, sequence, chatResponse, last);
boolean stored = QueuedModelRequest.accept(turn, signal);
```

stored 为 false 表示后端已确认忽略该回包，也可以确认投递。

序号从零开始。整体返回只发一个终态帧；流式返回按连续序号发分片，并在最后一帧设置终态。
已发完内容时可用空响应结束流。远端失败使用 `QueuedModelRequest.failure`。
消费者在信号写入成功后确认 MQ 消息，重投时使用相同的信号 ID 和内容。
第零帧唤醒可用 Runtime；接手的实例按序展示分片，在成功终态后推进原 ReAct 循环。

适配器默认使用五分钟空闲期限，从队列确认接收开始计时，可通过
`withQueuedModel(publisher, Duration.ofMinutes(15))` 调整。新消费的有序分片续期，
期限会持久化，更换 Runtime 后重放已消费分片不会续期。到期时若仍缺帧，Turn 进入失败状态，
错误包含调用 ID 和所缺序号。
接手时已到齐的回包仍可处理。若接流实例在展示部分回复后退出，新实例以同一消息 ID 和
attempt、更高的 revision 回放已保存分片。客户端收到新 revision 时替换之前的部分文本。
回包格式无效时，Turn 会失败，后续分片中的工具调用不会执行。
配置备用模型时，主模型在首个响应前明确回传失败，可以触发独立的备用模型调用。
接手的 Runtime 会继续这次备用调用。正常挂起、回包格式错误、模型已输出分片后的失败均不会触发模型切换。

## 重连与控制任务

### 重新打开原任务

将 `SessionKey`、Thread ID 和 Turn ID 保存到应用的任务记录中。刷新页面或应用重启后，
用这些身份查询原任务。以下 `runtime` 已连接原 Backend 或公共 HTTP 端点：

```java
var conversation = runtime.getSession(new SessionKey("application", "conversation-1"));
var thread = conversation.getThread("main");
var turn = thread.getTurn(savedTurnId);
var current = turn.status();
var history = thread.history(null, 100);
```

查询不会启动执行；推进未完成任务需要兼容的 Brain Runtime 正在运行。
重新建立[观察订阅](#独立观察任务)可以获取快照与后续事件。等待超时或流断开不会取消 Turn。

如果提交响应丢失，尚未保存 Turn ID，使用原 requestId 和原输入重试。
requestId 在 Session 内去重；同一个 ID 改变目标 Thread、操作或输入会产生冲突。
新动作使用新 ID，其他 Thread 上的动作也遵守此规则。

### 排队跟进任务或修改当前任务

以故障调查为例，将后续总结提交为新 Turn，用 `steer` 向正在执行的调查补充约束：

```java
var investigation = conversation.submit("investigate-1", "调查导出超时的原因");
var summary = conversation.submit("summary-1", "总结调查结果");
investigation.steer("constraint-1", "重点检查数据库访问，不要修改 API");
```

总结在主 Thread 中排在调查之后。队首 Turn 处于 WAITING 或 PAUSED 时也会阻挡后续任务。
`steer` 和 `inject` 在安全边界向原 Turn 加入输入，不会在模型输出一半时强行打断，
也不会撤销已发出的工具调用。`withdraw` 只能撤回尚未应用的输入。
控制与任务完成发生竞争时，应重新读取 Turn，不能假定控制已生效。

编辑或转换尚未开始的排队输入时，传入客户端读取到的输入版本。

```java
var thread = conversation.mainThread();
var edit = thread.editQueuedInput(
        "edit-summary-1", summary.turnId(), summary.status().inputRevision(),
        Input.text("只总结数据库调查中的发现"));
thread.convertQueuedToSteer(
        "redirect-summary-1", summary.turnId(), investigation.turnId(), edit.inputRevision());
```

编辑保留消息 ID 和历史位置。转换后，来源 Turn 进入 `CONVERTED`，目标 Turn 在模型上下文中
沿用原消息 ID。输入版本过期、来源已开始或目标已完成时返回冲突。响应不确定时，使用同一
操作 ID 和请求内容重试。

控制回执表示已受理；通过 `signal.applied` 或最终 Turn 状态确认后续处理。
排队中的任务可以立即取消；活动任务需要结清外部操作，受理取消不代表远端进程已经停止。

### 主动暂停和恢复

调用 `turn.pause("pause-1")` 后，观察到 PAUSED 才能向用户确认 Agent 已停止推进。
在之后的请求中，使用观察到的版本恢复：

```java
import io.agentscope.durable.DurableTypes.TurnStatus;

var paused = turn.status();
if (paused.status() == TurnStatus.PAUSED) {
    turn.resume("resume-1", paused.revision());
}
```

暂停不会取消已经派发的 Hands 操作；工具结果和交互答案仍可持久化。
恢复只解除显式暂停，不会绕过审批、运行中的工具或 UNKNOWN 结果。
终态 Turn 不能恢复；用户需要再次执行任务时，应提交新 Turn。

### 用另一个 Thread 执行独立工作

```java
var research = conversation.createThread("research", "assistant");
research.submit("research-1", "查找相关故障案例");
// 后续请求复用这个 Agent 的上下文。
conversation.getThread("research").submit("research-2", "比较这些案例的处理方案");
```

不同 Thread 可在宿主容量范围内并行运行，各自拥有历史、检查点和队列。
能在 Session 事件中观察其他 Thread，不代表其对话会自动进入当前 Agent 的上下文。
父 Agent 需要等待子任务结果时，使用 [Durable 子委派](#durable-子委派)。
共享文件、凭据和 Sandbox 需要显式配置与授权；Thread 隔离不会将共享资源写入串行化。
任务结清后再关闭 Thread 或 Session；存在未完成任务时，关闭操作会被拒绝，不会隐式取消任务。

## 统一用户 API

`DurableRuntime` 是线程安全的装配、生命周期和业务入口。会话和任务句柄只保存身份，任意业务实例
都能重新查找；权威状态在 Backend 中，查询不会启动 Agent。

| 对象 | 常用操作 |
| --- | --- |
| Runtime | `createSession(key, definition)`、`getSession(key)`、`listSessions(namespace, cursor, limit)`、`capabilities()` |
| Session | `submit/call` 与 `getTurn/listTurns/history` 默认操作主 Thread；`snapshot()` 聚合会话，`mainThread()`、`createThread(id, definition)`、`getThread(id)`、`listThreads(cursor, limit)` 用于显式 Thread |
| Thread | `submit(requestId, Input)`、`getTurn(turnId)`、`listTurns(cursor, limit)`、`history(cursor, limit)`、`view()`、`close()` |
| Turn | `status()`、`result()`、`await(timeout)`、`steer`、`inject`、`withdraw`、`cancel`、`pendingInteractions`、`respond`、`pause`、`resume` |
| Session / Thread / Turn | `listEvents(cursor, limit)`、`streamEvents(options)`、`subscribeEvents(options, listener)`；过滤不创建新事件序号 |
| Thread | `checkpoints(after, limit)`、`restore(position, expectedPosition, requestId)`、`fork(position, targetThreadKey, requestId)`、`forkSession(position, targetSessionKey, requestId)` |

从保留的已结束 checkpoint 分支到独立 Session 时，在源 Thread 上指定同一
namespace 内的新 Session key。

```java
var branch = conversation.mainThread().forkSession(
        checkpointPosition, new SessionKey("demo", "alternate"), "fork-alternate");
branch.submit("alternate-1", "探索另一种方案。");
```

新 Session、主 Thread 和恢复基线一起可见。主 Thread 继承源绑定和 checkpoint 中的
私有资源。响应丢失后，可用同一源 checkpoint、目标 key 和请求 ID 重试。
已由其他请求创建的目标会返回冲突。对已有 Session 中的 Thread 使用 `fork`。
外部文件和 Sandbox 生命周期遵循其配置的存储契约。

一个 DurableTurn 对应一次持久逻辑任务。原生 AgentRun 是一次进程内调用，与 Durable 的
RunView 使用同一个 runId；StepView 记录该 Run 内的步骤。恢复保留 turnId，创建新 Run/Step。
通过 `turn.listRuns(cursor, limit)`、`turn.getRun(runId)`、`turn.listSteps(runId, cursor, limit)`
和 `turn.getStep(runId, stepId)` 查询执行历史；这些查询不启动 Agent。

模型明细通过 `turn.listModelCalls(runId, stepId, cursor, limit)` 与
`turn.getModelCall(runId, modelCallId)` 查询；stepId 为 null 时列出 Run 的全部调用。
每条调用包含独立请求、重试次数、来源步骤、耗时和已知 token 用量，恢复回放不重复计量。
`usageComplete=false` 表示只知道部分用量，缺失计数保留 null；details 是总 token 的子集。
`PROVIDER_ATTEMPTS` 表示共用模型请求边界的观测；自定义模型若只暴露外层调用，则标记
`MODEL_CALL`，不推断隐藏重试。没有结束事实的失联请求保留 LOST 和已知部分，不伪造完整用量。

同一 Turn 的累计推理位置随原生 checkpoint 保存，重建 Runtime 不会重置 `maxIters`。
应用已有工具结果的恢复 Step 不额外消耗一次推理次数；新 Turn 重新计算预算。
恢复推理、工具结果应用和 finalization 都会记录属于新 Run 的 Step，并可通过
`resumedFromStepId` 关联中断前的步骤。Step 阶段可以推进到 FINALIZATION，阶段变化可从事件中查看。
当前原生格式为 `agentscope-thread-journal-v2`；旧开发格式不自动转换。

Core/Harness 升级保留 AgentScope 2.0 的状态契约。缺少 `thread_id` 的状态映射到 `main`。
空的主 Thread 首次执行时，会从配置的 `AgentStateStore` 导入已有 `agent_state`，先提交
`migration/baseline` 再继续运行。命名 Thread 不会导入该状态。主 Thread 已提交状态会
镜像回同一个 `agent_state`，因此回滚后 2.0 runtime 可以读取最近一次已落稳的状态。
2.1.0-BETA1 的 `SessionLog` API 和存储格式不属于升级兼容契约。

Session 创建时原子创建主 Thread；每个 Thread 固定自己的 Agent 定义版本。
同 Thread 按持久化受理顺序执行；当前任务等待时，后续任务不能越过它。同 Session 的不同 Thread 可并行执行。
`turn.pause(requestId)` 先受理暂停意图，活动调用在安全边界从 PAUSING 进入 PAUSED。
暂停也会阻塞同 Thread 的后续任务；外部结果和交互答案可继续落库，但不自动推进。
原生日志通过 run/output、run/end 报告调用结果，任务终态只由 Durable 提交。
排队任务可立即取消。取消活动任务时，Brain 可以中断在途的直连 Executor 调用，
并依据执行器报告的实际结果结清。无法核实的工具效果保留为 `UNKNOWN`。

AgentScope adapter 会结清尚未开始的工具计划，并保留取消前已收到的结果。结果按配置
完成规范化后进入下一 Turn 的上下文。取消与完成由先提交的决定确定 Turn 终态。

```java
var handle = runtime.getSession(new SessionKey("application", "conversation-1"));
var turn = handle.submit("request-2", "继续研究");
var current = turn.status();
var history = handle.history(null, 100);
// 后续请求凭 SessionKey、threadId 和 turn.turnId() 获取原任务。
```

`submit` 返回持久 Turn 句柄。相同 requestId/输入重试返回原 Turn，内容不同返回 CONFLICT。
`steer/inject/respond/cancel/pause/resume/withdraw` 返回 CommandReceipt，表示命令已原子受理；
`signal.applied`、`interaction.resolved` 或 Turn 终态才表示后续处理结果。
`submit/steer/inject/respond` 同时写入公开用户消息，history 只读公开投影，
不需要私有原生日志；消息携带 requestId/inputKind，响应额外携带 interactionId。
事件清理不删除已投影的历史消息。

同一 Turn 可以产生多条 assistant 消息，不同推理轮次分别保留。消息的逻辑 ID 不随
Run/Step 更换而改变；重新调用模型时增加 attempt，重放已提交模型结果时用 revision
替换未完成文本，避免重连后重复拼接。`message.completed` 是权威持久事实；
`message.delta` 与 `tool.arguments.delta` 是由 middleware 处理后展示事实产生的尽力而为
`LiveEvent` 预览，没有 sequence/cursor，也不进入 history、snapshot 或重放。模型私有推理
仍不公开。history 按首次公共 sequence 排序；直接读取 snapshot 的 messages 映射时也应
使用 sequence，不能依赖对象字段顺序。已有存储中的 `message.delta` 和
`tool.call.arguments` 仍可读取，但 AgentScope 的新写入不再创建它们。

工具调用统一保存在 snapshot 的 tools 投影中，按 Thread、Turn 和 callId 关联参数分片、
进度与结果。参数分片可以省略尚未知晓的工具名称；最终完整参数到达后停止拼接。
本地工具与远程 Hands 共用调用视图，只有真实外部执行携带 executionId。工具执行完成、
结果接收和结果应用分别记录；恢复后仍展示真实工具输出，UNKNOWN 保留到结果被明确核实。

`session.call(requestId, input, timeout)` 是 submit + await，返回 CompletionStage。消费事件时应先
订阅或打开事件流，再调用 `submit`，这样快速完成的 Agent 也不会漏掉实时预览。旧的
submit-and-observe `stream(...)` 快捷方法因为名称含混且只能在受理后连接，现已 deprecated。
同一 requestId 可安全重试；重复观察原 Turn 不重复提交。超时错误包含 Thread/Turn 身份。
关闭订阅、取消 Future 或关闭 Runtime 都不会取消持久任务，只有显式 cancel 才会发起取消。
这些短操作可能访问数据库/网络，Web 宿主须在非事件循环线程调用。

## 独立业务 API 与 HTTP

API-only Runtime 只需访问后端，创建新会话时注册定义元数据或 definitionResolver，
无需本地模型、Agent 工厂或 Executor。已有会话的查询不要求重新注册定义。

```java
import io.agentscope.durable.codec.agentscope.AgentScopeCheckpointCodec;
import io.agentscope.durable.codec.agentscope.AgentScopeCheckpoints;

var api = DurableRuntime.builder().backend(backend)
        .definition("assistant", "definition-v1", AgentScopeCheckpointCodec.STATE_VERSION)
        .checkpoints("assistant", AgentScopeCheckpoints::restore)
        .build();
// API-only：不调用 start；这个 Runtime 不认领任务。
```

checkpoint 恢复使用 `agentscope-durable-adapter-harness-agentscope` 内的 codec，不构造 Agent，
但制品仍依赖 Harness。原生状态在内部校验和复制，恢复目标必须空闲且位置匹配；
fork 指向同 namespace 下的其他 Thread，并继承源定义版本；目标 Session 必须已存在。
目标 Thread 的恢复事件进入目标 Session 的时间线；继承的原生对话历史属于私有恢复状态，不伪造历史公开事件。

HTTP 服务可以挂载 `DurableRuntimeHandler(api, authorizer)` 和
`SessionEventHandler(api, authorizer)`。authorizer 必须校验可信身份、namespace 和 Session；
fork 分别授权源与目标。公开 handler 只接受用户操作，拒绝 claim/commit/原生状态等内部操作。
其他 Web 框架可直接调用 Runtime；HTTP 并非必选组件。

```java
import io.agentscope.extensions.durable.http.DurableHttp;
import java.net.URI;

var web = DurableRuntime.builder()
        .transport(new DurableHttp(URI.create("http://127.0.0.1:8080/api")))
        .build();
var sameSession = web.getSession(new SessionKey("application", "conversation-1"));
```

本地 Backend 与公共 transport 二选一。`HttpSessionBackend` 是受信任执行宿主使用的
完整内部 Backend 适配，不应作为公网用户操作面。无执行配置的 Runtime 调用 start 会报错。
Brain 另行装配 Harness/Executor 并 start，持续扫描、认领和恢复；MQ 提示可以降低延迟，
提示缺失仍靠持久状态扫描推进。关闭 API 或 SSE 不影响其他 Brain。

## Java 协议与后端选择

公共 Java 契约定义在 `DurableTypes`、`DurableMessages` 和 `DurableProtocol` 中。
Backend/Executor 的 HTTP 调用使用 Runtime JAR 内同一套带版本 JSON Schema 校验，
无效请求在执行前拒绝。损坏的成功响应仍视为结果不确定，使用原请求或提交回执恢复。

私有 Frame 携带检查点元数据：Harness、定义/状态版本、独立字符串位置和规范化摘要。
适配器在原生恢复前校验绑定；公共快照和事件不包含该状态。
已经提交的原生检查点不能通过 null 丢弃。

| 实现 | 持久性与用途 |
| --- | --- |
| `LocalBackend.open(directory)` | 本地持久存储，供同机兼容进程使用 |
| `PostgresBackend.builder().dataSource(dataSource).build()` | 兼容宿主共享 PostgreSQL，应用需引入 optional PostgreSQL 驱动 |
| `HttpSessionBackend(endpoint)` | 远程访问同一后端语义，能力来自服务端 |
| `TransactionalSessionBackend(recordStore)` | 通过 RecordStore 接入自定义原子存储，显式声明持久性与共享范围 |

`runtime.executionCapabilities()` 返回后端保证、Harness 恢复/事件边界及 Executor 查询/取消/输出能力。
动态解析器列在 `resolvedHarnesses`；使用 `runtime.capabilities(definition)` 查询冻结定义的能力，
不会认领任务或调用模型。静态宿主按定义/状态版本索引扫描，其他版本的积压不会阻挡当前任务。
AgentScope 声明原生 TOOL_BOUNDARY 恢复和 DELTA 交付。
它不重放任意 Java 调用栈，也不保证无法查询的本地副作用恰好一次。

可提交字符串，或使用通用 Java 类型构造结构化内容：

```java
import io.agentscope.durable.DurableMessages;
import java.util.List;

var input = new DurableMessages.Input("1", List.of(
        new DurableMessages.Text("描述这张图片"),
        new DurableMessages.Image(
                new DurableMessages.Url("https://example.org/image.png", "image/png"))));
var receipt = runtime.getSession(session).submit("picture-1", input);
```

支持文本、图片、音频、视频及 URL/base64 媒体源。任意原生对象会被拒绝。
最终 Turn 输出使用公共 Message 格式，完成事件和快照保留结构化内容，排除原生推理。
一个 Turn 可以包含多条助手消息；同一消息的恢复版本替换临时文本，不会创建重复消息。
自定义事件使用 `x.<namespace>.<name>`；保留的生命周期、工具、消息和制品事件有载荷约束。

## 展示事件与协议 adapter

Durable 内部区分两类事件存储语义，对 SDK 用户提供一条强类型事件流：

| 交付类型 | 契约 | 常见用途 |
| --- | --- | --- |
| `PublicEvent` | 持久化、有序号、有游标、可重放 | 任务状态、完成消息、工具状态、审计与恢复 |
| 内部 `LiveEvent` | 尽力而为、只在连接期间交付、无游标和重放 | 文本与工具参数预览 |

应用统一消费 sealed `SessionStreamEvent`：`Committed` 包装持久 `PublicEvent`，
`PreviewStarted` 表示预览开始，`EventDelta` 携带显式订阅的增量，`SnapshotReset` 用于游标
失效后的状态替换。只有 `Committed` 包含 Durable cursor。每条连接通过
`EventStreamOptions.deltas(...)` 显式启用消息或工具参数预览，默认只交付持久事实。

```java
import io.agentscope.durable.EventStreamOptions;
import io.agentscope.durable.EventStreamOptions.EventDeltaType;

var options = EventStreamOptions.builder()
        .after(savedCursor)
        .deltas(EventDeltaType.MESSAGE)
        .build();

try (var stream = session.streamEvents(options)) {
    stream.forEach(event -> render(event));
}
```

`listEvents(cursor, limit)` 永远只返回持久 `PublicEvent`。Durable 应用不需要消费
Core/Harness 的 `AgentEvent`、`JournalEvent` 或内部实时传输类型。

`agentscope-durable-adapter-claude` 将完成消息和显式订阅的实时消息增量映射为 Claude
Managed Agents 事件格式。为当前连接启用 `EventDeltaType.MESSAGE` 后，可将
`ClaudeEventStreamSink` 传给 `SessionEventStream.forEach`。使用已授权的 Session 句柄调用
`new ClaudeSessionEvents(session).sendMessage(requestId, event)`，可提交包含文本或 URL/base64
图片的 Claude `user.message`。用相同请求 ID 重试会沿用原受理结果。回执包含持久消息 ID，
受理时的 `processed_at` 为空。输入进入 AgentScope 原生检查点后，事件流交付含处理时间的同一消息。
审批时将投影的 `agent.tool_use` ID 作为 `tool_use_id`，
通过 `sendToolConfirmation(requestId, event)` 将 `allow` 或 `deny` 写回原 Thread 和 Turn。
应答应用后，事件流沿用回执 ID 并交付处理时间。使用 `sendToolResult` 发送
`user.custom_tool_result`，为手动工具提交文本、图片和可选的 `is_error`。
使用 `sendInterrupt` 提交 `user.interrupt`，有 `session_thread_id` 时只针对指定 Thread，
省略时选中所有打开 Thread 中已开始的 Turn。相同请求 ID 重试时沿用原目标。
Java 原生入口为 `session.cancelActiveTurns(requestId, threadId)`，全部目标结清后事件流交付处理时间。
使用 `listEvents(page, limit, types)` 按提交顺序读取 Claude 格式的历史。
返回的 `next_page` 延续首屏的历史水位和事件类型筛选条件。
`agentscope-durable-adapter-agui` 通过
`DurableAguiEventStreamSink` 将同一规范化事件流映射到现有 AG-UI 1.0 `AguiEvent` 类型。

### Claude 事件端点

在 JDK `HttpServer` 的 `/v1/sessions/` 挂载
`new ClaudeSessionEventHandler(runtime, recordStore, authorizer)`。授权回调将请求中的 Session
解析为调用者有权访问的命名空间。Handler 在 `managed-agents-2026-04-01` beta 下提供
`POST /v1/sessions/{id}/events`、`GET /v1/sessions/{id}/events` 和
`GET /v1/sessions/{id}/events/stream`。API 副本共享持久 `RecordStore`。
携带 `Idempotency-Key` 可重试发送批次。批次按传入顺序处理，若后续项失败，已受理项的回执
仍可用原键重试取得。

历史支持 `order=asc` 或 `order=desc`、`types[]` 及基于处理时间的 `created_at` 区间筛选。
当各打开的 Thread 停在手动工具应答上时，Session 以 `requires_action` 返回尚待应答的事件 ID。
新流从当前水位开始。断线后可将最后一条已提交 SSE 帧的 `id` 放在 `Last-Event-ID` 中续接。
使用 `event_deltas[]=agent.message` 启用消息预览。预览帧不分配续接 ID。
[Claude 事件流](https://platform.claude.com/docs/en/managed-agents/events-and-streaming)

### 分离部署的实时预览传输

默认 `LiveEvents.local()` 只连接同一个 Runtime 装配内的生产者与观察者。Brain/API 分离部署
而且需要实时预览时，两侧必须配置同一个共享 `LiveEvents` 传输。即使该传输缺失或丢事件，
Durable 执行、历史和最终结果仍保持正确。

可通过 `agentscope-extensions-redis` 的 `RedisLiveEvents` 使用 Redis Pub/Sub。Brain 和各 API
宿主通过 `DurableRuntime.builder().liveEvents(...)` 配置同一个应用专用频道。Brain 使用
`new RedisLiveEvents(channel, redisConnection.sync(), null)`，API 宿主使用
`new RedisLiveEvents(channel, null, redisPubSubConnection)`。为订阅分配专用连接，将频道访问
限制在受信任的应用角色。缓冲区满或单条消息超过 64 KiB 时丢弃预览。关闭时先停止 Runtime
和传输，再关闭应用持有的连接。

## Hands Worker 与 RocketMQ 执行

Hands 请求的 `call.origin` 保留最初产生工具调用的 Run/Step，重投、换实例和结果回传不会改变它。
`ToolExecution.appliedAt` 与公共工具视图的 `data.appliedAt` 则记录结果进入 Agent 状态的位置，
恢复后可以属于新的 Run/Step；该位置随 checkpoint 一起提交。调用结束后的宿主结清使用空位置，
不会额外创建 Run。视图的 `data.origin` 保留原始来源。
批量子工具和 Sandbox 路由继续携带父请求来源；AgentScope Hands 工具可从 RuntimeContext 的
`durable.origin` 取得 `RunStepRef`，`getRunId()` 对应原始 Brain Run。

Hands 是可嵌入的独立组件，不要求每个应用部署一个工具服务：

`WorkerRuntime` 负责 Hands 的调度、恢复和生命周期，本身不再实现 `Executor`。Brain 与
Hands 同 JVM 部署时，把借入的 `worker.localExecutor()` 注册到 `DurableRuntime`；该端点把
执行协议调用委托给其所属的 WorkerRuntime。
默认本地装配直接接收 Hands 的 `RecordStore`，并在内部创建事务执行账本：

```java
var worker = WorkerRuntime.builder()
        .recordStore(handsRecords)
        .executor("hands", toolExecutor)
        .build();
```

只有自定义 Hands 账本时才使用 `executionStore(customStore)`。Brain 继续接收
`SessionBackend`，底层 JDBC RecordStore 不负责创建该领域对象。

| 部署方式 | 接线与存储 |
| --- | --- |
| 最简本地 | 直接注册 Executor，不启用 Worker 或 MQ |
| 同 JVM Worker | 注册 `worker.localExecutor()`，WorkerRuntime 的执行账本可使用 SQLite |
| HTTP Sidecar / 独立服务 | HttpExecutor → ExecutorHandler → `worker.localExecutor()` → WorkerRuntime |
| 独立 MQ Worker | RocketMqRemoteExecutor → 命令队列 → RocketMqWorker → 执行更新队列 |

Brain 将同一轮模型行动中获准派发、发往同一 Hands 路由的工具放在一条
RocketMQ 消息里。Hands 按各自的 `executionId` 登记全部子调用后才确认消息。
重投会补登记缺失项，复用已有的执行记录。取消、查询、输出和结果继续按调用交付。
Brain 汇齐终态结果后，按模型原有的调用顺序交回结果。

MQ Worker 的 `authorize` 用于校验命令身份和作用域。宿主通过 RocketMqWorker 或
WorkerRuntime 的 `authorizeStart` 检查单项工具的当前业务权限。返回
`Optional.empty()` 允许开始；返回可对调用方展示的理由会持久记录该调用的 `DENIED`
结果，同批其他工具按各自的权限继续。授权服务异常沿用 Worker 重试策略。已开始的
操作继续按原执行身份查询和取消。

`worker.statistics(limit)` 区分可领取的新调用、持租调用、等待 Provider 的调用和退避项。
对比 `oldestReadyAgeMillis` 与结果发布积压年龄，可定位延迟发生在开始前还是结果提交后。
`truncated` 为 true 时，计数和年龄反映本次检查的子集。

所有 Worker 形态复用相同执行内核。进程内线程隔离不能代替进程或 Sandbox 隔离。
Brain 会话状态与 Hands 执行账本可以使用不同数据库；同一池的副本必须共享其账本，
不能使用每个容器独立的 SQLite 文件冒充跨主机共享存储。

引入同版本 `agentscope-extensions-durable-rocketmq`，在 Brain 侧装配：

```java
var executor = RocketMqRemoteExecutor.builder()
        .clientConfiguration(clientConfiguration)
        .build();
var runtime = DurableRuntime.builder().backend(backend)
        .harness("assistant", "v1", adapter)
        .executor("hands", executor).build();
runtime.start();
```

AgentScopeAdapter 的工具绑定指向 `"hands"`。在独立 Hands 进程中装配：

```java
var worker = RocketMqWorker.builder()
        .executor("hands", toolExecutor).recordStore(handsRecords)
        .clientConfiguration(clientConfiguration)
        .authorize(command -> policy.check(command))
        .parallelism(4).build();
worker.start();
```

Brain 的传输账本与 ObjectStore 自动复用 SessionBackend 的本地存储，Runtime 负责绑定、
启动和关闭远程 Executor；用户不再重复传入 store、backend 或 route name。Worker 的执行
账本、工具 Executor 和授权策略由宿主提供。命令入库、Worker 受理和 Outbox 事件提交后按
execution 触发处理。索引补漏默认每 2 秒运行。Brain 按 `retryInterval` 重试未确认命令，
默认 5 秒。发现序列缺口时立即请求重放，连续 `responseTimeout` 未收到新进度时请求补查，
默认 30 秒。可分别配置 `RocketMqWorker` 的 `discoveryInterval`、`publicationRepairInterval`
和 `providerPollInterval`，控制账本发现、Outbox 补漏及 provider 重试间隔。
用户只需要装配两侧适配；命令待发送记录、结果去重、补发与消费回调由内部管理。
Runtime 和 Worker 会关闭根据 ClientConfiguration 创建的 MQ 客户端；存储、SessionBackend
和底层工具 Executor 仍由宿主持有。

在支持 LiteTopic 的 Broker 和独立 Cluster Proxy 上创建 LITE 父 Topic，并分别配置
Shared 消费组。默认 MQ namespace 是 `durable`。

| 父 Topic | 消费组 | LiteTopic 作用域 |
| --- | --- | --- |
| `durable-turn-dispatch` | `durable-turn-runner` | Session + Thread |
| `durable-turn-signal` | `durable-turn-signal` | Session + Thread |
| `durable-turn-owner` | `durable-turn-owner` | Brain 实例，显式订阅 |
| `durable-hands-commands` | `durable-hands-worker` | Session |
| `durable-hands-controls` | `durable-hands-worker-controls` | Session |
| `durable-hands-updates` | `durable-hands-brain` | Session + execution |

将 `durable` 替换为部署 namespace，将 `hands` 替换为执行 route。
对每一组父 Topic 和消费组执行以下配置。owner 组使用 `SUBSCRIPTION_MODE=EXPLICIT`，
其他组使用 `WILDCARD`。RocketMQ 5.5.1 的显式订阅组省略 `lite.sub.wildcard` 属性。

```sh
mqadmin updateTopic -n "$NAMESRV" -c "$CLUSTER" -t "$PARENT" -a '+message.type=LITE'
attributes="+lite.bind.topic=$PARENT,+lite.sub.model=Shared"
if [ "$SUBSCRIPTION_MODE" = WILDCARD ]; then
  attributes="$attributes,+lite.sub.wildcard=true"
fi
mqadmin updateSubGroup -n "$NAMESRV" -c "$CLUSTER" -g "$GROUP" -o true -m true \
  --attributes "$attributes"
```

每个 Worker 池使用独立命令路由及消费组；同池副本共用该组与执行账本。
Brain 结果消费者使用另一个组，同组副本必须共享 Brain 交付记录和 SessionBackend。
不同部署若使用不同数据库，应使用独立 namespace，避免互相消费无法识别的任务。
派生后的 Topic、消费组、ACL、TLS、凭证和消息保留期由应用部署管理；Worker 的 authorize
在账本访问前执行。
Cluster Proxy 保持 `enableProxyAutoRenew` 开启。Lite PushConsumer 接收消息时使用
Proxy 配置的消息不可见时长。

接入使用 [RocketMQ LiteTopic](https://rocketmq.apache.org/docs/domainModel/03litetopic/)。
Worker 持久受理后才确认命令；Brain 持久去重并向 SessionBackend 提交后才确认事件。
Broker ACK 不代表工具完成。控制命令乱序、重复消息和发布响应丢失不改变执行身份。
取消先于执行到达会留下墓碑；查询只重发原状态，不创建工具副作用。
已保存结果的补发不会重跑工具，后台扫描修复交付中断和会话提交失败。

执行消息使用 ExecutionMessages 和随 Runtime 打包的 JSON Schema，包含版本、请求摘要、
命令/事件 ID 和源序号。收件箱只应用连续前缀，重复消息幂等，缺失事件补发后再推进。
输出页携带字节偏移和明确缺口，终态发布前先封闭输出。通过
`turn.toolOutput(executionId, offset)` 查询已受理的 Hands 输出，从零开始并使用每页返回
的下一偏移。实际可用字节取决于 Provider 能力。持久事件记录封闭范围与缺口。
订阅 `EventDeltaType.TOOL_OUTPUT` 可预览本地工具文本和 Hands 输出。预览携带 Base64
字节范围，无事件游标。AG-UI 适配器将关联到 Run 的输出预览映射为
`agentscope.tool_output_delta` 自定义事件。原生 SSE 通过逗号分隔的 `deltas` 查询参数
启用所需预览，例如 `deltas=MESSAGE,TOOL_OUTPUT`。
不承诺任意外部副作用 exactly-once，不可查询的未知结果保持 UNKNOWN。

信封上限为 1 MiB。Brain 自动使用 SessionBackend 的 ObjectStore，Worker 可通过
`objects(...)` 配置同一可访问的 ObjectStore，将大型信封转为对象引用；引用校验作用域和
摘要，不可访问或无效载荷不会被确认。

要启用 Turn 通道，创建 `RocketMqTurnScheduler` 并通过
`PostgresBackend.builder().notifications(scheduler)` 传入，也可以对本地后端调用
`backend.withWakeups(scheduler)`。执行 Runtime 启动消费，宿主在 Runtime 停止后关闭
scheduler。后端通过 `messageDrivenScheduling` 报告此装配。数据库保留 FIFO 顺序和调度代次。
提交方在事务成功后发布已到期的 dispatch，仅提供 API 的宿主也可以发布。消息携带生成时的
Turn 修订号和调度原因。消费者校验父 Topic、LiteTopic 和消息键，后端在领取事务中校验
具体 Turn 和代次。Runtime 执行期间，dispatch 消费者续期消息的不可见时间。在执行阶段
持久提交或后端判定投递已过期后确认消费。容量不足或暂时失败时保留后续投递。
Turn 租约续期会推迟恢复时间，保留对应的派发身份。Thread 信号提示读取已提交状态。
Brain 显式订阅自己的 owner LiteTopic，处理控制提示前核对当前 Turn 和租约代次。
活动调用收到取消后可立即中断。Steer 在下一原生推理边界应用。

`DurableRuntime.Builder.repairInterval` 控制 MQ 执行宿主的补漏扫描，默认 30 秒。
其他宿主通过 `pollInterval` 配置数据库发现。收到 Session 提示时，只查询该 Session
的待运行工作。`releaseDelay` 控制未完成工作释放租约后的重新调度间隔。调度器为已知
期限设置本地定时器，到期时重读数据库。持久索引负责故障和重启后的补漏。
使用 `RocketMqTopology.turns(namespace).subscriptions()` 和
`RocketMqTopology.route(namespace, executor).subscriptions()` 读取运行时使用的
LITE 父 Topic、绑定的消费组和订阅模式，再由部署工具创建资源。

创建调度器时传入 `RocketMqTurnScheduler.Options`，可以配置 Outbox 补漏间隔、
未领取 dispatch 的重发间隔，以及 dispatch 和 signal 各自的消费缓存大小。
默认值分别为 2 秒、5 秒和每个消费者 32 条。第五个参数 `receiveWait`
设置 dispatch 消费者的长轮询等待时间，默认 1 秒。取值需符合 Proxy 的轮询限制。Hands 可用
`RocketMqWorker.Builder.controlCacheSize` 独立设置控制通道缓存。

滚动发布时先将宿主摘流，Brain 调用 `runtime.drain(timeout)`，Hands 调用
`worker.drain(timeout)`，再关闭适配器和存储。排空会停止新受理。AgentScope adapter 在
进行中调用的下一个可恢复边界交还租约，Turn 仍可由替换 Brain 从保存的位置继续。已持久化的
重试时间继续生效。
Brain 排空时停止拉取新 dispatch，继续续期正在处理的投递，保留信号消费和 Outbox 发布。
Hands 还会等待待发送结果。
发布终止窗口需覆盖排空和 SDK 关闭时间。替换实例可在租约到期后接管剩余工作。

通过 `runtime.schedulingStatistics()` 导出 Turn 调度指标。扫描次数按发现来源分组，
取一段时间内的 `total` 增量即可计算扫描 QPS。`emptyRatio()` 以完成的扫描为分母，
排除失败的扫描。领取结果区分新领取、复用租约、队列为空、租约占用、等待外部事件、
未到期和陈旧投递。用同一采样区间内各结果的增量除以全部结果的增量，可计算对应比例。
领取异常单独计数。发现延迟使用存储时钟，统计调度意图到期可领取至取得新租约的毫秒数。
直方图按区间独立计数，最后一档记录超过最大上界的样本。引入该指标前已生成的
调度意图可能缺少时间戳，领取时没有延迟样本。

`activeDispatchDuplicates` 统计同一 Runtime 对正在执行或已排队的相同调度意图的去重次数。
`scheduler.statistics()` 提供 Turn Outbox 补漏扫描次数、已确认补发次数、被发送资格检查
拦下的次数和发送失败次数。计数在进程内累计，标签固定，不包含 Session 或 Turn ID。
尚未出现的来源或结果按零处理。


## SessionBackend 部署适配

应用选择一套完整 Backend，自定义集成才需要了解底层存储 SPI。

`LocalBackend.open(directory)` 创建 `directory/durable.db`，完成 SQLite 初始化和进程内
通知装配。重启时打开同一目录即可恢复原 Session，无需数据库服务或消息队列。

生产 PostgreSQL 接入已有 DataSource：

```java
import io.agentscope.extensions.durable.jdbc.PostgresBackend;

// 部署阶段使用有 DDL 权限的身份初始化，先于应用启动执行：
PostgresBackend.initializeSchema(dataSource);

// 常规启动检查已有表结构，默认不执行 DDL。
var backend = PostgresBackend.builder()
        .dataSource(dataSource)
        .notifications(rocketMqWakeups) // 可选，默认使用进程内提示。
        .build();
```

开发环境也可以在 builder 上显式设置 `initializeSchema(true)`。
数据库/schema 选择和连接池由 DataSource 管理。两种适配均返回 `SessionBackend`，
使用相同 Runtime、AgentScope 工厂和事件回调。切换部署适配不会自动迁移已有数据。

关闭时先关闭订阅和 Runtime，再关闭 Backend。使用 try-with-resources 时，
按 backend、Runtime、subscription 的顺序声明，资源会按相反顺序关闭。
Backend 关闭自己拥有的记录存储访问；PostgreSQL 不关闭传入的 DataSource 或通知传输。
所有使用者停止后，由宿主关闭 `RocketMqWakeups` 和对应 SDK 客户端。

## 扩展基础设施

| 扩展点 | 契约 |
| --- | --- |
| `SessionBackend` | 状态、命令、检查点/意图/结果原子提交、公共快照和事件重放 |
| `RecordStore` | 存储实现 SPI，提供原子 Session 记录及权威存储时间 |
| `Wakeups` | 可选 Session 变更提示，不替代已提交状态和事件重放 |

完整适配负责组合这些组件。高级集成仍可使用 `TransactionalSessionBackend(recordStore)`
和 `backend.withWakeups(transport)`，或为不同物理布局实现完整 `SessionBackend`。
更换存储时，必须保持检查点与意图、结果与恢复条件之间的原子关系。

所有写入口、Runtime 和 HTTP handler 都使用装配后的 Backend。
远程写入也需配置权威服务端；只给某个 HTTP 客户端配置通知，无法观察其他客户端的写入。
`wakeups()` 是本地装配能力，不是 HTTP 操作。

通知在提交成功后发布，通知或错误回调失败不改变已确认的写入。
轮询补偿提交后、发布前丢失的提示，以及被消费组交给其他宿主的提示。
唤醒回调只调度或唤醒工作。RocketMQ 在本机监听者均成功返回后 ACK；
失败允许重投，之前已经成功的监听者也可能再次收到提示。

组合方式参考
[durable-harness](https://github.com/vercel-labs/durable-harness/blob/badd648db763e7d9121917b5c35732cd334e01be/packages/harness-world/src/session-log.ts)
的存储/队列边界，Java 继续使用现有事务与重放契约。

## 恢复工具与等待

将外部工具注册为只有 Schema 的原生工具，并将工具名映射到 Runtime 注册的 Executor。
原生权限检查通过后才提交工具意图。原生恢复点与意图一起保存；
外部结果接收后唤醒原 Turn，应用确认与包含该结果的原生恢复点一起提交。

模型在同一步请求多个工具时，获批的 Hands 调用各自保留独立的执行 ID，可以按任意顺序完成。
整组结果结清后，下一次推理按模型发起调用的原始顺序接收结果，其中包括拒绝和失败。

用 `turn.getToolCall(callId)` 查询原生工具，用 `turn.getToolExecution(executionId)`
查询外部执行。已路由的工具支持两种 ID。查询返回已提交的状态、最近进度和结果应用状态。
`DISPATCHED` 表示已记录派发尝试，`RUNNING` 表示已收到运行观察。接管后，原工具调用和
结果应用可以属于不同的 Run/Step。

派发开始后，查询还会返回已登记的 Executor 能力。对于消息驱动执行器，这些能力描述传输层
提供的控制。执行者无法核实结果时，状态仍可为 `UNKNOWN`。如果查询返回输出摘要，
从零偏移调用 `turn.toolOutput(executionId, offset)`，再用每页的 `nextOffset` 续读。
输出缺口标明无法读取的范围。

`agentscope-extensions-durable-sandbox` 提供 `DirectSandboxExecutor`，
使用已存在且固定身份的 `HarnessSandbox` 连接。POSIX/Python 操作日志让 Agent
进程退出后仍能查询独立运行的命令。`agentscope-durable-worker` 提供独立执行账本
和 Worker，适合需要独立容量或隔离的场景。`DelegationExecutor` 使用同一边界实现可恢复的异步子 Agent。

无法核实的远程结果或取消状态保留为 `UNKNOWN`。普通本地工具保留原生恢复边界。
凭证、环境创建和保留策略由应用负责。

通过 `turn.pendingInteractions()` 获取交互，再调用
`turn.respond(requestId, interactionId, response)`；审批 response 为 boolean，手动外部工具为 JSON。
框架原子校验 Turn、交互状态及响应类型，并拒绝代替自动 Hands 写入结果。
`turn.steer`/`inject` 在原生推理安全边界应用，`withdraw` 与应用原子竞争。
自定义适配器可提交 `WaitCondition(key, absoluteDeadlineMillis)`；零表示只等待信号。
观察到 PAUSED 后使用 `turn.resume(requestId, expectedRevision)`，修订号取自该暂停状态。
它只解除显式暂停；未完成的审批、工具和信号条件仍需满足，也不会重启终态任务。
通用扩展信号仅允许 `x.*` key，不能冒充框架内部控制。

### 工具策略与结果 Hooks

使用 `AgentScopeAdapter.withToolHooks(new AgentScopeToolHooks(version, timeout, policies,
normalizers))` 配置有序的工具策略和结果规范化回调。可以运行同一固定 Agent 定义的宿主应
使用相同的 Hook 版本和配置。策略可以改写参数、拒绝调用或要求审批。Adapter 在审批或工具
执行前将决定和实际参数冻结在原生检查点中，原生权限依然生效。策略报错或超时会拒绝该调用。

结果回调在本地工具、Hands 及手动外部工具的结果进入模型上下文前处理内容。回执按执行身份与
原始结果摘要保存规范化内容，恢复时可沿用。Hands 执行账本继续保留原始结果。结果回调失败时
沿用 Runtime 的调用恢复策略。策略和结果回调应避免执行外部副作用。

需要向外部投递时，使用 Session 句柄、稳定的订阅 ID、事件过滤条件和持久 `RecordStore`
构造 `CommittedEventOutbox`。调用 `capture(limit)` 原子保存一页事件和游标，再由应用 Worker
调用 `deliver(receiver)`。投递失败后使用原事件 ID 重试，接收方可用该 ID 去重。投递超时应
小于配置的租期。若尚未保存的历史已被裁剪，采集会报告游标过期，由应用决定如何重新同步。
通过 HTTP 投递时，可将 Durable HTTP 扩展的 `DurableWebhookPublisher` 传给 `deliver`。
它只发送事件 ID、类型、资源 ID、序号、游标和提交时间，并添加 Standard Webhooks
请求头和 HMAC-SHA256 签名。接收方可通过授权 API 取资源当前状态，或从上次已应用
游标继续补读历史。先验证签名和时间，再用重试时保持稳定的 `webhook-id` 去重。
[Standard Webhooks 验签](https://www.standardwebhooks.com/verify)

## 调用失败与自动恢复

需要宿主替换时，让兼容的 Brain 实例连接同一持久 Backend。原所有者租约到期后，
另一实例可以认领未完成的 Thread，从已提交状态继续。宿主必须能解析 Thread 固定的
Agent 定义和状态版本、工具绑定，并访问原工作区与外部操作。
PostgreSQL 可提供跨宿主共享存储，各容器独立的 SQLite 文件不能。
Fencing 拒绝过期所有者提交，定期扫描补偿丢失的唤醒通知，无需用户重新提交任务。
如果所有 Brain 都已停止，需要进程管理器重新启动实例。

调用失败由 Runtime 分类并交给 Backend 记录。默认对 I/O、超时及内部 UNAVAILABLE/CONFLICT
进行有界重试，未知程序错误不自动重试；AgentScope adapter 使用 Core 的模型错误分类，
429/5xx 和网络故障可重试，明确的客户端请求错误不可重试。模型自身的请求重试与这里的新 Run
恢复是两个层次，各自记录实际执行。

```java
import io.agentscope.durable.DurableTypes.RecoveryPolicy;

var runtime = DurableRuntime.builder()
        .backend(backend)
        .harness("agent", "v1", adapter)
        .recoveryPolicy(new RecoveryPolicy(3, 1000, 30000))
        .build();
```

上述也是默认策略：最多额外重试 3 次，指数退避从 1 秒开始，上限 30 秒。第一次失败时将策略、
累计失败次数和下一次可重试时间持久化到 Turn；更换 Runtime、修改宿主配置、暂停/继续或收到
新信号都不能重置计数或绕过退避。`turn.status().recovery()` 和 `turn.recovery` 事件提供恢复信息；
没有失败记录时 recovery 为 null。必要时通过 builder 的 `retryableFailure` 配置宿主错误分类，
该判断不能授权重做 UNKNOWN 操作。

重试不依赖用户连接或手工信号。不可重试或预算耗尽后停止新 Agent 调用，已发出的工具仍按原
executionId 查询或取消；UNKNOWN 保持 WAITING，效果结清后才 FAILED。`HarnessAdapter.settle` 统一失败与取消结清；
AgentScope 在同一提交中保存原生清理状态、确认结果应用和 Turn 终态，不启动新的 Run，
同 Thread 的后续任务可使用已清理的上下文。暂停仍优先，取消可
越过退避进入结清。宿主关闭、所有权失效和其他宿主可能提供的 INCOMPATIBLE 定义不消耗此失败
预算。Backend 无法提交失败记录时不能声称已持久化，原租约到期后由新宿主核实最后有效状态。

## 独立观察任务

普通应用直接使用 Runtime 管理的强类型事件订阅：

```java
import io.agentscope.durable.EventStreamOptions;
import io.agentscope.durable.EventProjection;
import io.agentscope.durable.SessionStreamEvent;

var projection = new EventProjection(session);
var subscription = runtime.getSession(session).subscribeEvents(
        EventStreamOptions.defaults(),
        event -> {
            if (event instanceof SessionStreamEvent.SnapshotReset reset) {
                projection.reset(reset.snapshot());
            } else if (event instanceof SessionStreamEvent.Committed committed) {
                projection.accept(committed.event());
            }
        });
// 无需编写轮询循环或配置观察线程。
// 连接存在期间保留订阅，观察者断开时调用 subscription.close()。
```

默认先重放保留的持久历史，再持续接收新事件。恢复消费进度时传入
`EventStreamOptions.builder().after(savedCursor).build()`；游标过期时交付
`SnapshotReset`。只有当前连接需要渲染预览时才在同一个 options 中启用 delta。

创建和关闭 Session/Thread 会产生持久的 `session.created/closed`、`thread.created/closed`
事件。创建 Session 同时创建主 Thread；重复相同操作不重复追加生命周期事件。
`snapshot.projection.session` 和 `snapshot.projection.threads` 提供同一游标下的资源状态，
事件回放与直接查询一致，日志裁剪后仍可通过快照恢复。

按 Thread、Turn 或 Run 观察时，使用同一 `EventFilter` 读取事件和快照：

```java
import io.agentscope.durable.DurableTypes.EventFilter;

var filter = new EventFilter("main", turnId, runId);
var scopedProjection = new EventProjection(session, filter);
var scoped = runtime.getSession(session)
        .subscribeEvents(filter, EventStreamOptions.defaults(), event -> apply(event));
// 从已有游标继续：EventStreamOptions.builder().after(savedCursor).build()
```

turnId 必须有 threadId，runId 必须有 turnId；错误归属会被拒绝。
`sessionHandle.listEvents(filter, cursor, limit)` 和 `snapshot(filter)` 使用相同范围；
Thread/Turn 句柄的快捷方法也遵守这些规则，Turn 快照不包含其他任务。
Thread 过滤只保留该 Thread 的资源视图；Turn/Run 过滤不包含资源生命周期事件或资源投影。
过滤快照中的 `projection.session` 为 null，需要父资源当前状态时显式调用 `view()`。
Run 快照仍带所属 Turn 的逻辑状态，投影只保留当前关联该 Run 的记录。
limit 限制扫描的 Session 事件数：空的过滤页仍可能推进游标，应比较 cursor 与
highWatermark 判断本次是否追平。过滤流的 sequence 可以不连续；
`EventProjection(session, filter)` 接受这种稀疏流，完整 Session 投影仍检查序号连续性。

每个订阅的回调串行运行，使用独立于 Agent 执行的观察线程。Session 变更提示会唤醒
`await()` 和事件订阅。`observationInterval` 控制低频补读，有通知时默认为 2 秒，
纯轮询传输默认为 100 毫秒。`observationRetryInterval` 控制事件读取或回调失败后的重试。
只有回调成功返回才推进游标。错误交给 Runtime 的 `onError`，
订阅提供 `cursor()`、`lastFailure()` 和 `isClosed()`；
错误报告失败不会阻断重试。`observerParallelism` 配置共享回调容量，默认为 2。
容量耗尽时慢回调可能延迟其他观察者，但不占用 Agent 执行线程。

关闭单个订阅只解除该监听，不影响其他观察者，也不取消 Turn。
关闭 Runtime 会关闭全部订阅；已经执行中的回调可能完成，Runtime 关闭会协作式中断观察线程。
订阅立即启动，独立于启动 Agent 调度的 Runtime `start()`。

跨消费进程重启恢复时，在回调中将事件/快照游标与自己的投影一起保存。
交付语义为至少一次，外部副作用需要幂等。
需要自行管理轮询或执行器的高级宿主仍可使用 `SessionEventClient`。

`turn.subscribeEvents(options, listener)` 会在同一串行观察线程交付持久事件和显式订阅的 delta。
只有 `Committed` 会推进 `subscription.cursor()`；delta 断线后不重试，也不能用于更新持久
投影。客户端即使遗漏部分或全部预览，最终 `message.completed` 仍是权威结果。

通过 `runtime.getSession(session)` 重新取得会话句柄，再使用
`mainThread().getTurn(turnId)` 获取主 Thread 的任务；查询、历史和事件均不认领或执行任务。
快照包含 Turn 视图和公共投影，私有原生恢复点保留在后端内部。

HTTP 扩展提供 Backend/Executor 客户端与可挂载 handler。
`SessionEventHandler` 复用同一套重放/确认客户端；
`SseEventWriter(OutputStream)` 可用于其他 Java HTTP 服务器。
写入/flush 失败不确认该事件。初始游标过期返回 HTTP 410，
流中途过期发送 `resync`，客户端重新获取同一过滤范围的快照后连接。
SSE 查询支持 threadId/turnId/runId，Last-Event-ID 优先于 cursor 参数。过滤后的
扫描位置通过 `cursor` 控制帧续传。`durable` 帧是可重放公共事件，可交给 EventProjection；
`live` 帧只携带预览且刻意不设置 SSE `id`，因此 Last-Event-ID 不会确认或请求它。
认证、操作/Session 授权和阻塞 I/O 线程由宿主提供。
租约、原生恢复点、结果接收和全局发现属于特权操作。

## 排查任务为什么没有继续

先调用 `turn.progress()`，HTTP 使用 `getTurnProgress`。它从同一个事务返回 Turn、Session
游标、租约状态和类型化阻塞原因。`QUEUE_HEAD` 指明队首 Turn，`APPROVAL` 和
`MANUAL_TOOL_RESULT` 指明可用 `turn.respond` 回应的交互。`PAUSED` 使用观察到的版本恢复。
等待外部工具时返回 execution ID，`NATIVE_TOOL_RECONCILIATION` 返回待核实的原生调用 ID。
定时阻塞项附有截止或重试时间。

同一查询返回每条验收标准在对应产物版本上最近提交的检查结论。配置了 Harness 检查器时，
通过 `task.verification.completed` 发布检查结果与证据版本。验收投影在事件裁剪后仍可查询，
Turn 的执行终态和验收结论各自独立。

通过 `turn.listRuns` 和 `turn.listSteps` 查看调用明细。

| 现象 | 检查或处理方式 |
| --- | --- |
| QUEUED，前面有 WAITING 或 PAUSED 任务 | 处理或取消队首 Turn；独立工作可以提交到另一个 Thread |
| QUEUED，没有执行记录 | 确认 Brain 已调用 `start()`、有可用容量、能访问存储并解析 Thread 固定的定义 |
| WAITING，等待审批或手动结果 | 读取原 interaction，通过 `turn.respond` 回答；自动 Hands 结果走 Executor 通道 |
| 工具结果到达后仍然 PAUSED | 用户准备继续时，使用观察到的版本显式 resume |
| 正在恢复退避 | 查看 `turn.status().recovery()`；替换宿主或发送信号不会重置重试次数 |
| 外部结果 UNKNOWN 或取消一直未结束 | 查询并核实原 execution 的结果，不要重新提交有副作用的操作来推进队列 |
| 观察连接断开或游标过期 | 重新观察，按需通过快照恢复，不要重新提交任务 |

## 恢复边界

Thread 固定 Harness 和原生状态版本，重启需要匹配的工厂、工具绑定与执行环境。
共享后端不意味着可以切换原生内核。当前提供 Java 实现；其他语言接入需要自己的适配器和协议一致性检查。

恢复需要活动 Runtime 和可访问的存储。关闭 Runtime 不会取消持久任务，
也不会关闭调用方拥有的后端和 Provider。全部宿主停止后，需要外部进程管理器重新启动宿主。

参考后端通过 Session 级事务保存分段记录。不同 Thread 可以并行执行，但短暂的状态提交
仍共享这一事务范围。历史量较大或 Session 较繁忙时，需要测量事务竞争和存储增长。
恢复不能重建已删除的工作区，也不能确认不可查询的外部效果。应在实际部署环境中验证
Provider 的查询、取消能力和存储持久性。


## Java 平台接入能力

Agent 工厂返回全新的 `ReActAgent.Builder` 或 `HarnessAgent.Builder`；adapter 自动注入本次调用的 Thread Journal 后构建 Agent。公开的 `Binding` 只提供定义、`RuntimeContext` 和 Durable workspace，不向应用暴露日志存储。adapter 同时在 RuntimeContext 中绑定 `AgentExecutionHost`，统一执行边界检查、输入持久化应用和 finalization 恢复，不再要求安装额外输入 middleware。工具映射会将对应原生工具转为外部执行工具，保留原生权限校验。使用 `DelegationExecutor` 将已声明的子 Agent 工具路由到独立子 Turn；目标解析器必须按父 Thread 冻结的定义版本解析配置。

同一 Harness 名称可注册多个定义版本，或使用 `resolver` 按 `ThreadSpec` 动态解析。已有 Thread 始终使用其固定版本。Runtime 通过就绪索引发现工作，`parallelism` 限制接纳；`drain` 停止接纳并等待当前执行，超时停止本宿主后由新宿主恢复。

状态采用分段事务记录：租约、原生日志增量、检查点、工具意图和输入回执在同一 Session 事务中提交。`CommitPolicy` 是事务内的宿主授权扩展；平台可基于同一事务中的策略记录拒绝提交。不要在该回调内调用外部服务。

`LocalBackend` 同时装配 SQLite、文件对象存储和本地通知。生产环境可向 PostgreSQL Backend 提供 `ObjectStore`。`agentscope-extensions-durable-objects` 提供 S3/MinIO 与 OSS，采用不可变、按会话隔离的摘要引用并在读取时校验。SDK 依赖为 optional，应用显式选择所用 SDK；客户端由宿主管理。大检查点、原生日志和私有资源自动转为对象引用。HTTP 支持有界二进制对象读写；MQ 的大消息可配置两侧共享的对象存储，原始内容不进入公共事件。

`binding.workspace()` 实现 Harness `BaseStore`，通过 Backend 执行带版本的 GET/SEARCH/PUT/CAS/DELETE；Brain 不需要数据库凭据。可使用 `new RemoteFilesystem(binding.workspace(), List.of("workspace"))` 接入 Harness。该存储已按 Session 隔离，内部命名空间使用稳定相对名称。外部共享文件或 Sandbox 内容由相应挂载负责，不自动成为私有检查点的一部分。

`AgentScopeCheckpoints.restore` 仅允许恢复已结束执行的原生 baseline，同时复制该检查点的私有资源；fork 重绑定原生身份，要求相同 namespace、Harness 和定义/状态版本。目标必须空闲，使用位置校验防止覆盖并发写入。

模型完成结果在状态应用前持久化。恢复时可重放已完成结果；辅助模型调用、Finalization 阶段和该阶段的工作区 IO 都有稳定回执。工具意图/结果应用继续沿原生 ThreadJournal 边界恢复，不保证任意自定义回调或不可查询外部副作用恰好一次。

Hands 使用命令、控制、执行事件三条独立通道。每个执行事件有递增序号；接收方只推进连续前缀，重复不重复应用，缺口通过查询重放。输出按字节游标提交，先封闭输出再发布终态；裁剪用显式 `gap` 表示。SSE 通知仍只是读取持久事件的提示，与工具执行链路分离。

`BatchExecutor` 持久化子调用清单与稳定身份，恢复时查询原操作，取消后不接纳新子调用。`agentscope-extensions-durable-mcp` 的 `McpExecutor` 保留 MCP 内容块和结构化结果；普通 MCP 协议没有可靠的操作查询/取消确认，因此故障时返回 UNKNOWN，由 Worker 账本防止盲目重放。

当前源码树仅提供 Java 实现，没有 TypeScript/Pi 客户端。语言中立协议不代表已经实现其他语言客户端。

## UNKNOWN 的受控核实

`ExecutionStore` 为运维宿主提供核实入口。先查询 `reconciliation(request, requestId)`，
无回执时通过 `claim(request, owner, lease)` 认领原执行，再提交
`ExecutionReconciliation(requestId, verifiedTerminalResult, evidenceRef)`。
`reconcile(work, command)` 仅接受 UNKNOWN，原子保存审计回执并写入原 outbox；
缺失输出以显式 gap 封口，Brain 仍通过原结果链路继续。核实失败或取消请求不等于已经停止。
宿主负责授权和外部证据核实；此入口没有自动开放为 HTTP/MQ 命令或模型工具。

原生 Harness 工具已派发但结果未知时，当前 Turn 会等待证据。即使收到取消请求，
同一 Thread 的后续 Turn 也会继续排队。授权宿主核实该 Turn 所有待确认调用后，
可通过 AgentScope adapter 提交原调用 ID 和已核实的终态结果。

```java
var result = ToolResultBlock.text("verified output")
        .withIdAndName(callId, toolName)
        .withState(ToolResultState.SUCCESS);
new NativeToolReconciliation(requestId, evidenceRef, List.of(result))
        .submit(backend, threadKey, turnId);
```

重试提交时复用原请求 ID 和证据。执行实例领取后校验证据是否覆盖所有待确认调用，
在原生检查点应用核实结果，随后继续执行 Turn 或结清已受理的取消。原工具调用不会重跑。

通用协议拒绝重复 JSON 字段、多根值正文和规范化后会丢精度的数字，精确大数用字符串。
当前 Java Session 存储格式为 21。开发版不提供旧格式自动迁移。

## Brain 与 Hands 共用冻结工具

AgentScope Adapter 与 Worker 使用同一份 `ToolBinding` 目录。绑定固定 schema、
定义版本、路由和私有目标，调用携带其摘要。目标保存凭据引用；宿主解析密钥并授权 Worker 边界。

原生工具通过 `AgentScopeToolExecutor.binding(tool, version, route, target)` 生成绑定，
交给 `AgentScopeAdapter.forReAct(factory, bindings)` 或 `forHarness`；
WorkerRuntime 注册 `new AgentScopeToolExecutor(toolkit, bindings, timeout)`。
Brain 工厂可使用空 Toolkit，由 Adapter 注册 schema。Hands 保留 Toolkit 参数校验、
预设参数和默认上下文；Executor 使用期间保持工具注册不变。Brain 权限和宿主 Worker 授权均须生效。

原生内容、元数据和显式结果状态在交接中保留；普通已完成返回值遵循 AgentScope 终态判定。
调用异常、空返回或挂起仍表示结果未确认。`ToolCallParam.propagateErrors(true)` 使 Toolkit
和注解工具透传异常，不启用重试，默认行为不变。普通 Java 方法不能跨 Worker 进程存活，
也不会自动获得原操作查询、确认取消或持久进度流。

`BatchExecutor` 支持 ORDERED 与有界 PARALLEL，受理时固定模式、并发上限及子调用身份；
替换实例继续原批次，可回放输出关联原子调用及执行。宿主逐项授权冻结清单。

## MCP 连接与显式恢复

`McpConnections` 管理有界连接池，初始化中和退休中的连接也计入容量。发现工具或执行调用
期间持有 Lease；配置轮换后，旧连接等待最后一个使用者释放再关闭，空闲连接自动淘汰。
关闭池后拒绝新借用，已有 Lease 可完成调用。连接身份按租户和服务器隔离，凭据解析由宿主负责。

通过 `McpExecutor.binding(alias, version, route, server, tool, catalog)` 冻结发现结果，
使用 `new McpExecutor(binding, resolver, timeout)`。解析器可以返回
`McpExecutor.Target.leased(pool.borrow(identity, resolvedConfiguration), remoteTool, arguments)`。
连接配置支持 streamable_http 或 sse、headers/queryParams、ISO-8601 格式的 timeout
及 initializationTimeout，以及 propagateMeta。不要在绑定中持久化解析后的凭据。

普通 MCP 不保证原操作查询或取消确认。远端工具可通过
`_meta["io.agentscope.durable/execution"]` 显式声明 Durable 扩展：version 为 1，
queryTool、可选 cancelTool 及明确的 idempotentStart。查询工具须声明 readOnlyHint。
发现时冻结主工具与控制工具描述。调用携带稳定执行身份及请求摘要，控制响应须匹配身份
并通过共用 JSON Schema 校验；无效观察保持 UNKNOWN。取消受理与确认停止分别表达。
主工具发生变化后，原操作仍可使用未变化的控制描述。完整协议位于源码中的
`agentscope-durable/protocol/README.md`。

## 持久 Sandbox 绑定

`agentscope-extensions-durable-sandbox` 的可选组件 `SandboxBindings` 管理现有
`SandboxClient / Sandbox`。绑定包含 namespace、稳定资源标识、定义版本和冻结配置引用。
同一 namespace 的 Session 可经宿主授权共享绑定；调用仍保留独立执行身份和冻结 `ToolBinding` 摘要。

```java
var local = new LocalSandboxClient(Path.of("./data/sandboxes"));
var workspace = new WorkspaceSpec();
workspace.getEntries().put("README.txt", new FileEntry("Durable workspace"));
var environment = new SandboxBindings.Binding(
        "demo", "workspace-1", "v1", DurableJson.value(Map.of("provider", "local")));
var sandboxes = SandboxBindings.builder()
        .stateStore(records)
        .resolver(binding -> local.target(workspace))
        .canDelete(binding -> allRelatedExecutionsSettled(binding))
        .build();
sandboxes.start();
Executor executor = sandboxes.executor(environment, shellBinding);
```

`records` 是宿主的 RecordStore；`shellBinding` 是私有目标包含 `operation: "sandbox.exec"`
的冻结工具。其他操作包括 `fs.upload`、`fs.download` 和 `artifact.publish`。
Executor 可直接注册，也可接入 WorkerRuntime/RocketMqWorker。resolver 只解析配置、凭据并构造
Target，不能创建资源。云供应商可使用 `Target.using(client, workspace, options, physicalIdentity)`，
按需提供 `withKeepAlive` 和 `withDestroy`。resume 必须连接原实例且不能重建工作区；
destroy 必须幂等并确认资源已不存在。

创建身份在调用供应商前持久化，回包丢失不会自动新建替代环境。初始化失败仍会保存已知连接状态；
重连同时校验物理身份和工作区标记。创建结果无法确认时，需要宿主授权的 `reconcileCreation`，
提交原状态及独立核实的证据；该操作不会创建资源或重写身份标记。
`protect(encrypt, decrypt)` 用于连接状态加密，默认直接保存。绑定配置中不要放入明文凭据。

`requestDeletion(binding)` 先持久化删除意图并拒绝新启动。清理等待宿主确认
**所有共享绑定的 Session 和执行均已收尾**，再持有租约进行幂等删除和重试。
默认屏障拒绝删除；等待收尾期间仍可查询与取消原操作。`close()` 仅停止维护并清空连接缓存，
不会销毁资源。保活待办持久化，因此缓存淘汰或宿主替换不会遗弃已有环境。

LocalSandboxClient 是显式选择的开发适配，需要 POSIX shell 和 Python 3，命令具有宿主权限。
内联 FileEntry/DirEntry 和环境变量只初始化一次，副本进程必须共享原文件系统。
它拒绝快照恢复、不支持的条目以及重建已丢失目录。生产隔离与独立资源生命周期应使用云 Sandbox。

Worker 的 `drain` 停止受理并等待当前控制调用结束，不等待所有远程工具结束。
RocketMqWorker 还会等待已受理的 MQ 回调和待发布事件。宿主应先 drain，再 close，最后关闭
借用的 SDK 客户端和存储。强制 close 中断自有线程、释放排队容量，并最多等待五秒，不会取消
外部副作用。宿主回调需要响应中断或使用有界 IO；回调关闭自身宿主时不能等待自身线程。


## 运行示例

Java 示例位于 `agentscope-examples/durable`，归属 Examples Maven 聚合模块。
Durable 的 `durable-standalone` profile 也显式纳入这些示例，用于独立构建和检查。
每个场景的启动器位于 `agentscope-examples/durable/{integrated|brain-hands}/{scenario}/run.sh`。
integrated 包含 quickstart、http-server 和 tool-in-sandbox；brain-hands 包含 echo-tool 与 tool-in-sandbox。
从仓库根目录运行：

```sh
bash agentscope-examples/durable/integrated/quickstart/run.sh
bash agentscope-examples/durable/integrated/http-server/run.sh
bash agentscope-examples/durable/integrated/tool-in-sandbox/run.sh
bash agentscope-examples/durable/brain-hands/echo-tool/run.sh --compile-only
bash agentscope-examples/durable/brain-hands/tool-in-sandbox/run.sh --compile-only
```

一体式 tool-in-sandbox 启动器依次运行 `start`、`resume` 两个 JVM，每个 JVM 内同时装配 Brain 和 Hands。
每个 brain-hands 模块打包 `${artifact}-brain.jar` 与 `${artifact}-hands.jar`，分别使用独立 Java main 和 runner，
Java 入口不接受 role 参数，也不使用角色枚举。启动器接受 `<brain|hands> <endpoint> [data-directory]` 来选择 JAR，
不支持 `worker` 别名；先启动 Hands，再在另一终端启动 Brain。

需要 Java 17+ 与 Maven。正常检查使用 `make -C agentscope-durable check`；隔离依赖检查使用
`make -C agentscope-durable independent`，另需 Python 3。真实队列执行需要 RocketMQ 5 服务及
Topic/消费组配置；`--compile-only` 只打包应用，不连接 Broker 或执行工具。

## 运行 Durable 基础验收测试

修改 Durable 后，在仓库根目录运行功能验收。

```sh
make -C agentscope-durable acceptance
```

验收覆盖 Java 和 HTTP 用户 API、Session 和 Thread 隔离、Turn 排队、steer 和取消、审批、checkpoint fork、
SSE 断线重放，以及 Brain 和 Hands 的故障恢复。API 重启用例通过 Durable Worker 和本地传输，
验证工具执行期间重启 API 后的续接。基础验收使用临时 SQLite 数据库和预设行为的模型与工具，无需模型密钥或 RocketMQ。

修改 RocketMQ 拓扑、调度或传输后，运行扩展验收。它用客户端测试替身验证响应丢失、确认失败和消息重复投递等场景。

```sh
make -C agentscope-durable acceptance-rocketmq
```

任一命令设置 `DURABLE_TEST_JDBC_URL` 后，支持 PostgreSQL 的 JDBC 用例会在指定测试库上运行。
测试账号需要有创建和删除 schema 的权限。

```sh
DURABLE_TEST_JDBC_URL='jdbc:postgresql://localhost:5432/durable_test?user=durable_test' \
  make -C agentscope-durable acceptance
```

## Durable 子委派

子任务需要跨重启恢复、独立观察或接受持续上下文中的后续请求时，使用持久委派。
普通 Core/Harness 子 Agent 调用不会自动创建 Durable 子 Thread；
将声明的工具路由到 `DelegationExecutor`，才能获得持久子任务调度和结果交接。

`DelegationExecutor` 默认按父 executionId 创建同 Session 的独立子 Thread。需要连续复用子 Agent
上下文时，用目标解析器显式指定已有 Thread；其 ThreadBinding 必须一致：

```java
var children = new DelegationExecutor(backend, request ->
        new DelegationTarget(
                new ThreadSpec(
                        new ThreadKey(request.session(), "research"),
                        new ThreadBinding("research", "v1", AgentScopeCheckpointCodec.STATE_VERSION)),
                DurableJson.value("research request")));
```

将该 Executor 注册到 Runtime，并把父 Agent 的委派工具路由到它；子 Agent 按普通 Harness 注册。
创建 Thread、受理子 Turn 和保存映射共享一次 Backend 事务。重复执行及重启后读取已冻结的映射，
不会重新解析目标，也不会新增子任务。`TurnView.parent` 记录父 Thread/Turn/executionId 与原始 Run/Step；
父工具投影 `data.child`、内部 `ToolExecution.delegation` 指向对应子 Thread/Turn。

父任务暂停不会暂停子任务；父任务取消只取消映射中的子 Turn，不影响复用 Thread 中的其他任务。
取消先于受理时只保存取消记录，后续迟到的 start 不会创建子任务。事务内拒绝跨 Session、父 Thread
自身及未完成委派形成的 Thread 依赖环。`turn.delegated`、`tool.delegated` 和
`tool.delegation.cancel_requested` 是统一公开事件；它们不替代真实子任务结果及父任务的结果应用。

### 独立运行的 Job

模型需要在子任务受理后继续推理时，使用 `DelegatedJobExecutor`。在 Brain 上注册该执行器和
Session 后端，注册子 Harness，再将以下三个工具的 Schema 路由到该执行器。启动目标使用
`DelegationExecutor.childThread(request)` 作为固定 Thread。

| 工具 | 输入和结果 |
| --- | --- |
| `start_job` | 应用定义的子任务输入。返回已提交的受理回执，包含 `jobId`、`child` 和 `status: ACCEPTED`。 |
| `get_job` | `jobId`。返回子任务状态以及已有的输出或错误。 |
| `await_job` | `jobId` 和正整数 `timeoutMillis`。等待子任务结清，或返回等待超时。 |

发起 Thread 的后续 Turn 可以继续使用同一个 Job ID。Brain 接管后沿用原等待期限，子 Turn
结清时提交唤醒等待 Turn 所需的事实。等待工具的进度包含 Job ID 和毫秒时间戳格式的期限。
受理后的 Job 独立运行，取消调用方 Turn 或等待只结束
调用方的工作。停止 Job 时，以回执中的子 Turn 为取消目标。

队列模型的空闲期限随新消费的有序分片续期，重放已消费分片不会续期。发布器抛出普通 RuntimeException 表示 MQ 接收结果未知，按宿主有界恢复策略以相同 callId 重投。明确拒绝应抛出带分类码的 DurableException。回包消费者可调用 QueuedModelRequest.accept 持久化回包，并安全确认终态或已取消 Turn 的晚包。Provider 已接收请求但确认丢失时仍可能重复计费。

Worker 每轮生成前调用 `QueuedModelRequest.begin(turn, callId, deliveryId)`，接手时使用新的唯一 deliveryId，
重试同一次 begin 命令时沿用原 ID。返回 false 时确认 MQ 消息即可。后续使用带 deliveryId 的 reply 和 failure 重载。
后端在事务内为 begin 分配单调递增的 epoch。新 attempt 替换原 Turn 已展示的部分文本，旧 attempt 晚包返回 ignored，
可直接 ACK。收齐有序终帧后，collector 在派发工具前原子封存胜出的 attempt。并发接手触发恢复，封存后拒绝继续接手。

Throwable failure 重载将 provider HTTP 429、5xx、I/O 和超时归为瞬时失败，其余 HTTP 状态及未分类异常归为永久失败。
Worker 也可显式指定 retryable。瞬时失败消耗持久化的宿主重试预算，并在原 Turn 投递后续模型调用。
永久失败沿用已配置的 fallback 语义。队列 collector 通过 core request observer 输出标准 started、usage、ended 事件。
这些事件记录 collector 对回包的观测，collector 更换后会为持久化回包建立新的观测。

Collector 在读取回包前订阅已配置的 backend wakeups，完成、失败或取消时释放订阅。丢失的提示由最长一秒的退避轮询补偿。Worker 开始 generation 后，Turn 继续挂起，直到首个响应到达。

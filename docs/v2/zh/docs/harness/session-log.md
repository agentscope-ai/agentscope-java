---
title: 会话操作、事件与恢复
description: 使用 AgentSession 提交任务、引导执行、回复待办，并实现历史展示、断线续传与状态恢复。
en_link: /v2/en/docs/harness/session-log
---

如果你已经按[快速开始](/v2/zh/docs/quickstart)使用 `agent.call` 获取回复，或使用 `agent.streamEvents` 展示实时内容，可以继续用它们完成普通多轮聊天。HarnessAgent 默认也会保存这些调用的历史和 checkpoint；保存会话不要求改用 `AgentSession`。

当应用需要“关闭页面后继续执行”“忙时排队”“运行中补充要求”或“中断后继续同一任务”时，再通过 `AgentSession` 管理会话。它接收任务并持有后台执行，前端独立读取消息、进度和待办。本文按这些场景介绍用法；完整应用见[可恢复聊天示例](/v2/zh/docs/harness/session-chat)。

如果 Agent 由 AgentScope Service 托管，直接使用 [Service Agent API](/v2/zh/service/session-event-log) 的 HTTP/SSE 接口即可。

## 基本概念与 API

`AgentSession` 由已配置的 `HarnessAgent` 提供，使用与直接调用相同的会话身份：

```java
import io.agentscope.core.agent.RuntimeContext;

var ctx = RuntimeContext.builder()
        .userId("alice").sessionId("conversation-001").build();
var session = agent.session(ctx);
```

取得对象、读取历史都不会启动 Agent。即使此前一直使用 `agent.call(input, ctx)`，也可以通过 `session.transcript()` 读取同一会话的历史。只查原始日志时，还可以直接使用 `agent.sessionLog(ctx)`。

同一会话选择一套执行入口：由应用调用 `call` / `streamEvents`，或通过 `session.submit` 等操作交给框架调度；两种方式都可以读取日志。对于已经提交的任务，用日志观察进度，不要再调用 `streamEvents`，因为它会启动另一次执行。

| API | 用途 |
| --- | --- |
| `session.submit(input)` | 提交新任务；忙时排队，框架分配 turnId |
| `session.submit(requestKey, input)` | 同上；网络重试沿用相同 key 和输入，避免重复提交 |
| `session.steer(input)` | 引导正在执行的任务，在下一推理步骤生效；无运行任务时报错 |
| `session.inject(context)` | 持久接收补充材料，在后续推理步骤应用，不主动启动执行 |
| `session.respond(requestId, answer)` | 回复待办，自动关联原 turn 和工具调用 |
| `session.interrupt()` / `session.resume(turnId)` | 中断当前执行／从已保存状态继续原任务 |
| `session.tasks()` / `session.await(task)` | 查询任务／等待本次执行完成、挂起、中断或失败 |
| `session.transcript()` / `session.log()` | 读取已提交消息／原始事件 |
| `session.pending()` / `session.inspect()` | 查看待答交互／恢复状态 |

输入已接收不代表已经执行。`submit` 返回任务回执，排队时 runId 可以为空。`await(task)` 只观察，不启动、重试或取消执行；返回 `suspended` 时仍需答复待办。

### 历史、事件与 checkpoint

Session Log 同时记录直接调用和会话调度产生的执行过程。消息历史用于展示“发生过什么”；checkpoint 保存后续执行所需的工作状态。恢复从已提交状态开始，不恢复原线程、网络连接或工具内部进度。原有中断和 HITL 机制仍控制执行，Session Log 负责持久记录。

`AgentEvent` 是执行中的实时通知，`SessionEvent` 是持久历史。原生日志以会话内递增的 `seq` 分页，以 `eventId` 去重；Service 公共 SSE 使用自己的 cursor，不能混用。实时通知到达不代表日志已提交。

## 场景一：聊天、排队与重启恢复

以一个后台处理材料的聊天应用为例：提交后立即返回任务回执，页面可以离开，用户也可以继续提交待处理任务。下面假设 `model` 已按[模型文档](/v2/zh/docs/building-blocks/model)配置：

```java
import io.agentscope.core.agent.RuntimeContext;
import io.agentscope.harness.agent.HarnessAgent;
import java.nio.file.Path;

HarnessAgent agent = HarnessAgent.builder()
        .name("Chat Assistant")
        .agentId("chat-assistant")
        .model(model)
        .workspace(Path.of("/data/chat-workspace"))
        .build();
var session = agent.session(RuntimeContext.builder()
        .userId("alice").sessionId("conversation-001").build());

var task = session.submit("request-001", "介绍这份材料");
System.out.println(task.turnId());
var outcome = session.await(task).block(); // 命令行用法；Web 应用直接返回提交回执。
System.out.println(outcome.status());
```

`await` 返回任务状态，回复内容通过 `session.transcript()` 或下节的事件视图读取。前端断开观察不会取消后台任务；主动停止使用 `session.interrupt()`。

再次 `submit` 表示新任务。如果当前任务正在执行，新任务按接收顺序排队；当前任务中断或等待 HITL 时，队列会保留，先继续原任务或回复待办。相同 key 和输入重复提交返回已有回执，不能用同一个 key 提交不同内容。

重启后重新构建同样配置的 Agent，并取得相同 session，即可读取历史：

```java
var history = session.transcript();
history.messages().forEach(message -> System.out.println(message.getTextContent()));
session.start(); // 应用启动时显式开启已接收任务的调度；不会自动恢复已中断任务。
```

`submit`、`resume` 和 `respond` 会自动开启调度。只查看历史不需要 `start()`。应用退出时关闭 `agent`；框架会中断其正在执行的任务并保留持久队列。

## 场景二：运行中补充信息与前端续传

任务仍在运行时，从处理用户新输入的请求中补充要求。`steer` 应在执行期间调用，而不是等 `await` 结束后调用。

```java
session.steer(task, "重点比较运维成本"); // 绑定已知任务，防止会话切换后误投。
session.inject("补充材料：团队只有两名运维人员。");
```

`steer` 在当前任务下一推理步骤生效，不修改已经发出的模型请求或正在执行的工具参数。`inject` 只补充上下文：空闲时等待后续任务，当前执行已无后续步骤时保留到下一次执行。中断前尚未消费的引导信息仍绑定原 turn，在恢复时读取。

前端按以下步骤读取持久视图：

1. 加载消息、工具状态和待办快照，以及对应水位。
2. 读取该水位之后的已提交事件，按消息或工具 ID 更新已有内容。
3. 普通断线从最后成功应用的游标继续；刷新丢失界面状态时先重取快照。

```java
long after = 0; // 取最后成功应用的 seq。
for (var event : session.log().readAfter(after, 100)) {
    System.out.printf("%d %s%n", event.seq(), event.type());
}
```

不要在浏览器重连时重新提交任务。只保存游标不能恢复游标之前的页面内容。[聊天示例](/v2/zh/docs/harness/session-chat)从已提交片段重建完整消息、进行中的文本和工具卡片，并提供快照及 SSE；Service 用户使用其公共事件协议。

这些 SDK 存储操作是阻塞调用。WebFlux 中应安排到 `Schedulers.boundedElastic()`，避免占用网络事件线程。

## 场景三：回复 HITL 待办

通过会话入口提交的任务若挂起等待用户输入，页面从持久待办展示问题，再通过 `respond` 交回答案。直接调用场景也可以使用[原有 HITL API](/v2/zh/docs/building-blocks/agent#人机交互)，不必仅为人工确认切换执行入口。

```java
session.pending().forEach((requestId, request) ->
        System.out.println(requestId + " " + request.data().get("kind")));

session.respond("external-request-id", "用户选择简洁风格"); // 外部执行或 ask_user 的真实结果。
session.respond("confirmation-request-id", true);          // 批准权限确认。
```

字符串用于 `external_execution`，布尔值用于 `confirmation`。框架从持久待办查询原 turn 和工具 ID，组装既有 HITL 输入，拒绝未知、已解决或类型不匹配的请求。

拒绝时可使用 `SessionAnswer.reject(reason)`；自定义结果内容使用 `SessionAnswer.Output`，需要权限规则或修改后的参数时使用 `SessionAnswer.Confirmation`。多个并行工具的答案可通过 `respond(Map<String, SessionAnswer>)` 一起提交，必须属于同一 turn。在线等待式 Service 交互应通过其所属服务的 actions 接口回复。

## 场景四：中断后继续原任务

```java
session.interrupt();
// 等 tasks() 显示 interrupted 后，可以重启应用，再取得同一个 session。
var continued = session.resume(task.turnId());
```

`resume` 校验任务状态，读取 checkpoint 和尚未应用的原输入，再启动新的执行。无需手工构造空输入、重放用户消息或设置 turnId。已完成任务不能恢复，有待答交互时应调用 `respond`。

```java
var inspection = session.inspect();
System.out.println(inspection.uncertainToolCalls());
```

强制退出后，需要等待旧 writer 租约释放或到期。若工具结果未知，先核对外部系统并按[恢复参考](/v2/zh/docs/harness/session-log-reference#核对结果未知的工具)提交真实结果，再继续。恢复还需要相同工具配置、凭据和工作文件；不等于任意历史回滚。

## session、turn 和 run

一个 session 包含多轮逻辑请求（turn）；一个 turn 可以经过多次实际执行（run）才完成。一个 run 内可以多次调用模型和工具，并产生多条消息。

| 身份 | 表示什么 | 何时变化 |
| --- | --- | --- |
| `sessionId` | 一段持续的会话 | 开始另一段对话时 |
| `turnId` | 一项逻辑任务 | `submit` 创建；引导、回复待办和恢复保留它 |
| `runId` | 一次实际执行 | 每次重新执行时变化 |

例如，在会话 `S1` 中请求“整理报告，发布前让我确认”：

| 操作或状态 | turnId | runId |
| --- | --- | --- |
| 提交任务，推理并调用工具 | `T1` | `R1` |
| 刷新页面，补齐已生成内容 | `T1` | 仍是 `R1` |
| 补充“重点比较成本”（steer） | `T1` | 仍是 `R1` |
| 用户中断 | `T1` | `R1` 结束 |
| 继续原任务 | `T1` | 新建 `R2` |
| Agent 挂起等待确认；用户答复后继续 | `T1` | `R2` 结束，新建 `R3` |
| 发布完成后提交另一项任务 | 新建 `T2` | 新建 `R4` |

表中 sessionId 始终为 `S1`。在线等待式 HITL 在原执行仍存活时可以继续原 run；表中展示的是挂起式 HITL。业务代码通过操作表达意图，无需手工填写 turnId 元数据。

完整存储身份还包括 `userId` 和稳定的 `agentId`，重启时需要保持身份与日志后端一致。模型调用、工具调用、消息和交互请求各有自己的 ID，用来关联片段、结果或答案。

## 场景五：排查执行、导出记录和更换后端

排查某轮请求时，按 turnId 聚合多个 run，按 runId 查看模型、工具和状态变化：

```java
var log = session.log();
long through = log.head().seq();
for (var event : log.scan(0, through)) {
    System.out.printf("%d %s turn=%s run=%s%n",
            event.seq(), event.type(), event.turnId(), event.executionRunId());
}
```

`scan(0, through)` 读取固定的已提交前缀，适合检查或导出。需要持续导出到数据库、审计系统或公共 UI 时，使用 `SessionExportSink` 和 `SessionLogExporter`，让目标端按 eventId 幂等接收。事件目录、导出示例和扩展方式见 [API 与存储参考](/v2/zh/docs/harness/session-log-reference)。

默认日志通过 Workspace 的 Filesystem 保存：

| 使用方式 | 日志位置 |
| --- | --- |
| 本地 Workspace | 当前身份解析后的 Filesystem 根目录下 `.agentscope-runtime/` |
| 分布式 Workspace | 对应 BaseStore namespace 下的 `__agentscope_session_log_v1__` 分区 |
| 自定义日志后端 | `HarnessAgent.builder().sessionLogStore(store)` 指定的位置 |
| Service 托管 Agent | 原生日志使用共享后端；公共事件在 Data Plane 数据库，见 [Service 文档](/v2/zh/service/session-event-log) |

Workspace 不局限于本地磁盘。可以直接使用具备原子版本写能力的分布式 Filesystem，也可以让工作文件和日志使用不同后端。多副本需要共享日志存储，并使用相同的身份与 namespace。具体配置见[存储参考](/v2/zh/docs/harness/session-log-reference#存储位置与后端配置)。

## 完整示例：可恢复的 Web Chat

[agentscope-chat 示例](/v2/zh/docs/harness/session-chat)将以上流程连起来：

- 逐步展示消息、工具参数和执行进度，以及当前 turn/run。
- 已提交事件时间线与原始载荷查看。
- 页面刷新、断开重连后的历史恢复。
- 重启服务后继续同一会话。
- 任务排队、运行中引导、上下文注入、待答请求和中断后继续。

示例可离线运行，也可以配置真实模型。按示例中的操作步骤，可以直接观察恢复前后的 turnId、runId 和事件水位。

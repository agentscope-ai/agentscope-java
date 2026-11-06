---
title: Agent 会话操作：提交、引导、中断与恢复
description: 使用 AgentSession 管理任务排队、运行中补充、HITL 和断线续传，理解 session、turn 与 run。
---

用户点击“停止生成”后又输入一条要求，应用需要继续原任务，还是提交新任务？Agent 正在调用工具时补充“预算改为 5000 元”，模型什么时候能看到？浏览器断开 SSE，后台是否继续执行？

普通多轮聊天和当前请求内的流式展示，可以继续使用[快速开始](/v2/zh/docs/quickstart)中的 `call` / `streamEvents`；它们也会保存 Harness 会话记录。本文聚焦后台执行、任务排队和中断续做。

使用 `HarnessAgent` 构建这类应用时，通过 `agent.session(context)` 取得 `AgentSession`，用不同操作表达用户意图。框架负责执行与持久队列；前端读取历史和事件。完整 API 见[会话操作、事件与恢复](/v2/zh/docs/harness/session-log)，可运行应用见[聊天示例](/v2/zh/blogs/best-practices/session-chat)。

## 先选择用户真正想做的操作

| 用户意图 | API | 生效方式 |
| --- | --- | --- |
| 发起一个新任务 | `session.submit(input)` | 创建 turn；忙时按接收顺序排队 |
| 纠正或补充当前任务 | `session.steer(input)` | 当前 turn/run 的下一推理步骤读取 |
| 保存参考材料，不主动唤醒 Agent | `session.inject(context)` | 在后续推理步骤读取，空闲时不执行 |
| 停止当前执行 | `session.interrupt()` | 通过既有协作中断机制停止，保留原任务 |
| 中断后继续原任务 | `session.resume(turnId)` | 恢复 checkpoint，保留 turnId，新建 runId |
| 回答工具问题或审批 | `session.respond(requestId, answer)` | 查询待办归属，使用原有 HITL 机制继续 |
| 刷新页面或断线重连 | 读取快照和水位后的持久事件 | 补齐界面，不重新提交或启动执行 |

同一 session 可以包含多个 turn；一个 turn 可以跨多个 run。模型和工具调用是 run 内部的步骤，不要求应用每次创建一个 turn。

## 取得会话并提交任务

下面假设 `agent` 是配置了稳定 agentId 和持久日志后端的 `HarnessAgent`，`ownerId` 来自认证身份，`sessionId` 已完成访问权限检查。

```java
import io.agentscope.core.agent.RuntimeContext;

var session = agent.session(RuntimeContext.builder()
        .userId(ownerId).sessionId(sessionId).build());
var task = session.submit("request-001", "比较三个出差方案");
System.out.println(task.turnId());
```

取得会话不会执行 Agent。`submit` 同步持久接收输入并开启后台调度，返回回执；排队时 runId 可以为空。网络重试沿用相同 request key 和输入，避免生成重复任务。

Web 接口可直接返回回执，页面独立读取进度。命令行若需要等待本次执行结束，可使用：

```java
var outcome = session.await(task).block();
System.out.println(outcome.status());
```

`await` 只观察本次执行，不负责启动或取消。返回 `suspended` 表示仍有待办或挂起原因，不能当作任务完成；对等待操作使用 timeout 也只会停止等待。

这些存储操作是阻塞调用。WebFlux 集成应安排到 `Schedulers.boundedElastic()`，不要占用网络事件线程。

## A 正在执行，下一条消息应该怎么办

独立的新需求用 `submit`，当前任务的修正用 `steer`，纯参考材料用 `inject`：

```java
session.submit("request-002", "之后再比较退改签政策");
session.steer(task, "预算改为 5000 元");
session.inject("参考材料：会议地点在市中心。");
```

第二个任务会排队。引导绑定当前 task，防止延迟请求误投到后来开始的任务；无运行任务时 `steer` 会报错。它不会修改已经发出的模型请求或正在执行的工具参数，而是在下一推理步骤生效。

`inject` 不主动增加一次推理：如果当前任务已无后续步骤，材料保留到下一次执行。输入接收、执行开始和输入应用分别记录，接收成功不等于模型已经采用。中断前未消费的引导仍属于原 turn，在恢复时读取。

任务等待 HITL 或被中断时，后续任务保留在队列中。先答复待办或继续原任务，原任务结束后才调度下一项任务。

## 中断和继续不需要重新拼装输入

```java
session.interrupt();
var stopped = session.await(task).block();
if ("interrupted".equals(stopped.status())) {
    var continued = session.resume(task.turnId());
}
```

界面已绑定某次 run 时，使用 `session.interrupt(runId)`，可以拒绝误中断后续执行的过期请求。中断信号通过既有检查点生效，不会回滚已产生的外部副作用；工具长期阻塞时仍可能需要等待。

恢复使用已提交工作状态和尚未应用的原输入，无需手工重放全部消息。它保留 turnId、创建新的 runId，不恢复原 Java 线程或工具内部进度。若工具结果未知，先核对外部系统，再按照[恢复参考](/v2/zh/docs/harness/session-log#核对结果未知的工具)继续。

## HITL 答复关联原请求

```java
session.pending().forEach((requestId, event) ->
        System.out.println(requestId + " " + event.data().get("kind")));

session.respond("external-request-id", "选择方案 B");
session.respond("confirmation-request-id", true);
```

字符串用于外部执行结果，布尔值用于工具确认。请求 ID 必须来自持久待办；框架查找原 turn 和工具调用，拒绝未知或不匹配的请求。拒绝某项工具行动使用对应答复，不等于中断整个任务。

这里使用挂起式 HITL：答复后创建新 run，继续原 turn。在线等待式 Service 交互仍应通过所属服务的 actions 接口处理。

## 断线续传与应用重启

关闭浏览器或取消事件订阅，不会取消框架持有的后台执行。前端刷新时先读取完整快照和对应水位，再订阅后续事件；仅保存游标无法补回游标之前的文字和工具卡片。

```java
var history = session.transcript();
var events = session.log().readAfter(lastAppliedSeq, 100);
```

原生日志的 seq、实时 AgentEvent 的 ID 与 Service 公共 cursor 是不同概念，不能混用。只有已提交记录可用于可靠续传；消息、工具参数和结果按稳定 ID 更新，避免重复追加。

重启后使用相同 userId、agentId、sessionId 和日志后端取得会话。查看历史不启动推理；应用显式调用 `session.start()` 恢复已接收任务的调度，中断任务仍需 `resume`。退出时关闭 `agent`，框架负责中断活动执行并保留持久队列。

## AgentScope Service 的对应入口

托管 Agent 使用 [Agent API](/v2/zh/service/session-event-log)：创建 `/api/v1/agent-sessions`，向 session 的 `/turns` 提交任务，通过 `/snapshot` 和 `/events/stream` 恢复界面，使用 actions/resume 接口继续原任务。它有自己的持久命令、鉴权和公共事件协议。

SDK `AgentSession` 与 Service 表达相同的会话和任务概念，但不是同一个调度器。Service 已持有自己的任务租约和命令队列，不应在一个请求中再嵌套 SDK 会话调度。旧 `/api/sessions` 事件接口和 Endpoint SSE 的行为见[SSE 格式](/v2/zh/service/sse-events)，不能套用新 Agent API 的排队与 cursor 约定。

需要后台排队和中断续做的应用可以使用上述会话操作。已有工作流或服务调度器时，仍可使用 `call` / `streamEvents` 驱动执行；更底层的执行控制用于自定义调度器和协议适配。简洁的配套代码见 [SessionRunGuide.java](https://github.com/agentscope-ai/agentscope-java/blob/main/docs/examples/execution-control/SessionRunGuide.java)。

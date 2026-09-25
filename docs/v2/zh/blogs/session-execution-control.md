---
title: Agent 执行控制最佳实践：取消、中断与运行中补充消息
description: 使用 AgentRun 管理单次执行，正确处理排队取消、消息追加、实时补充、超时、断线重连和服务端跨实例中断。
---

用户点击“停止生成”后又输入一条新要求，应用需要停止哪一次执行？Agent 已经开始调用工具，此时补充“预算改为 5000 元”，模型什么时候能看到？浏览器断开 SSE，后台任务应该继续还是取消？

这些问题都需要应用明确区分**会话、执行和消息**。本文以 [PR #3279](https://github.com/agentscope-ai/agentscope-java/pull/3279) 引入的 `AgentRun` API 为准，面向在 Java SDK 上构建应用的开发者，并说明 AgentScope Service 的对应调用方式。使用前请确认依赖包含这些 API；本文不表示所有已发布的 2.0 版本都已具备它们。

完整辅助代码见 [SessionRunGuide.java](https://github.com/agentscope-ai/agentscope-java/blob/main/docs/examples/execution-control/SessionRunGuide.java)。它接收应用已经配置好的 `ReActAgent`，无需绑定某个模型提供方。正文中 `agent`、`ownerId`、`sessionId` 分别表示该 Agent、认证后确定的用户 ID 和已授权的会话 ID。

## 先选择用户真正想做的操作

| 用户意图 | 建议做法 | 生效边界 |
| --- | --- | --- |
| 立即停止某次生成 | `run.cancel()` | 取消该执行的响应式订阅；不撤销外部副作用 |
| 让当前执行在检查点结束并保留中断恢复回复 | `run.interrupt()` | 已运行时协作中断；尚未开始时取消该次执行 |
| 取消还在排队的消息 B | 根据 B 的 `runId` 找到句柄并取消 | 不影响正在运行的 A，也不会让后面的 C 提前执行 |
| 保持当前任务，追加下一轮问题 | 同一 session 创建并订阅新的执行 | 在同一个 Agent 实例内按实际入队顺序串行运行 |
| 不停当前任务，补充一条参考信息 | Harness `MessageBus.inboxPush` | Inbox 在后续推理步骤开始前读取；不是即时修改本次模型请求 |
| 纠正当前目标，希望后续执行使用新要求 | 协作中断旧执行，再把新要求作为新调用的输入 | 新要求必须显式提交；中断本身不会启动下一轮 |
| 为整个执行设置时限 | 对唯一执行订阅使用 `timeout` | 超时会取消上游；调用者收到 `TimeoutException` |
| 浏览器断线，但后台任务继续 | 服务端持有执行订阅，客户端读取独立事件流 | 断开观察者不等于取消执行 |
| 拒绝一项工具审批 | 使用审批或工具确认接口 | 拒绝某项行动不等于取消整个 run |

不要把 SDK 的 session 排队等同于 Service 的 HTTP 行为。Service 通过 turn lease 控制执行准入：会话忙时提交 `user.message` 可能返回冲突，并不承诺自动排队。应用需要明确展示“待发送”，在前一 turn 释放后重试准入。

## Session 保存历史，run 标识一次执行

`RuntimeContext` 携带本次调用的用户、session 和业务上下文。同一 session 可以先后执行许多 run；同一 Agent 实例可以同时服务不同 session。每次执行使用一个新的 `RuntimeContext`，不要在并发请求之间复用并修改同一个对象。

```java
import io.agentscope.core.agent.AgentRun;
import io.agentscope.core.agent.RuntimeContext;
import io.agentscope.core.message.Msg;
import io.agentscope.core.message.UserMessage;
import java.util.List;

RuntimeContext ctx = RuntimeContext.builder()
        .userId(ownerId)
        .sessionId(sessionId)
        .build();

AgentRun<Msg> run = agent.prepareCall(
        List.of(new UserMessage("比较三个出差方案")), ctx);

String runId = run.runId();
// prepareCall 只创建句柄；下面这次订阅才启动执行。
run.stream().single().subscribe(
        reply -> System.out.println(reply),
        error -> System.err.println(error.getMessage()));
```

`prepareCall` 返回最终回复，`prepareRun` 返回 `AgentEvent` 事件流。`ReActAgent` 和 `HarnessAgent` 都提供这两个入口。需要结构化输出等其他调用形式时，可以用 `AgentRun.create(agent.getAgentId(), () -> agent.call(...))` 包装实际调用。

取消或中断 run 不会删除 session 的历史。这里没有将同一个句柄“暂停后继续”的 `pause/resume` API；继续对话应创建新 run，并使用同一个 session。

一个句柄只允许一次执行订阅。不要先 `subscribe()` 启动，又对同一个 `run.stream()` 调用 `block()`、`single().toFuture()` 或返回给 HTTP 框架让它再订阅。需要最终回复时，直接选择 `single().toFuture()` 作为唯一订阅即可；需要多个观察者时，使用后文的事件分发方式。

| 状态 | 含义 |
| --- | --- |
| `CREATED` | 已准备句柄，还没有订阅 |
| `QUEUED` | 已订阅，但未进入 Core 执行位置；也包含此前的准备阶段 |
| `RUNNING` | 已获得 Core 执行位置 |
| `COMPLETED` | 执行流正常完成；协作中断返回恢复回复也可能是这个状态 |
| `FAILED` | 执行流以错误结束 |
| `CANCELLED` | 执行被取消，包括下游取消订阅 |

`run.status()` 是即时快照，不能当作跨线程锁。`run.termination()` 可重复订阅，只观察终态，不启动 Agent。`COMPLETED` 不一定表示原始任务已经达成：还应检查回复的 `GenerateReason`；被用户协作中断的回复会标记为 `INTERRUPTED`。

## 先登记，再启动：让另一条请求能找到 run

应用通常有两条请求链路：一条发送消息，另一条根据用户点击的“停止”按钮取消执行。后者应携带界面当时对应的 `runId`，由服务端验证用户与 session 归属后查找句柄。

辅助代码中的 `SessionRunGuide.Runs` 是一个单进程示例，提供 `register`、`find`、`cancel` 和 `interrupt`。它在终态自动删除对应条目：

```java
SessionRunGuide.Runs runs = new SessionRunGuide.Runs(); // 应用级对象，不是每请求创建

AgentRun<Msg> run = runs.register(ownerId, sessionId,
        agent.prepareCall(List.of(new UserMessage("比较三个出差方案")),
                SessionRunGuide.context(ownerId, sessionId)));

String runId = run.runId(); // 返回给前端，与这一轮输出绑定。
run.stream().single().subscribe(
        reply -> System.out.println(reply),
        error -> System.err.println(error.getMessage()));

// 另一条请求：ownerId 来自认证身份，sessionId 已经过访问权限检查。
boolean accepted = runs.cancel(ownerId, sessionId, runId);
```

登记必须发生在订阅之前，否则很短的执行可能先结束、后登记，或在取消请求到达时尚无可查句柄。例子处理了登记时已经终止的句柄，也不会让旧执行的清理删除其他执行。

`cancel()` 返回 `true` 表示本次取消被接受，不代表所有远程工作都已停止；返回 `false` 可能是已经结束或已经取消。上面的管理器对查不到、归属不匹配的 ID 也返回 `false`。业务层可据此实现幂等响应，并用持久化事件保存历史；活动登记表不是历史查询库。

多个应用实例之间不能只共享这个 Java Map。需要把取消请求路由到持有句柄的实例，并保留 run 身份匹配条件。AgentScope Service 已提供这种协调，见后文。

## 立即取消与协作中断怎样选择

### 立即取消：只停止这一次执行

```java
run.cancel();
```

如果执行尚未订阅，它不会启动；如果正在 session 队列中等待，它不会进入推理循环；如果正在运行，则取消其响应式订阅。仍在观察执行流的订阅者收到 `CancellationException`。如果是观察者主动 `dispose()` 了唯一执行订阅，观察者已经退出，不会再收到取消异常；可通过句柄状态或 `termination()` 观察结果。

立即取消不保证走协作中断的回复生成和状态保存路径。已写入数据库、已经发出的邮件或不响应取消的阻塞工具不会自动回滚。对这类操作，工具实现需要自己的超时、幂等键、远程取消接口和结果核对。run 的终态不是所有外部进程已经退出的证明。

### 协作中断：让推理循环经过中断处理路径

```java
run.interrupt();
```

当前执行会在后续中断检查点观察信号，例如推理开始或模型流的新 chunk。没有新输出且底层调用长期阻塞时，协作中断可能需要等待。

ReActAgent 的用户中断处理会补齐待完成工具调用的错误结果，生成带 `GenerateReason.INTERRUPTED` 的恢复回复，并尝试保存会话状态。保存失败可能只记录日志后仍返回回复，因此高可靠场景仍应检查状态存储错误和监控。

也可以调用 `run.interrupt(new UserMessage("停止原来的任务"))` 附带中断消息，但**这不是一条自动注入模型的新用户输入**。当前默认处理逻辑不会自动围绕这条消息继续推理。要让模型执行新要求，必须另起一次调用，并明确传入新消息。

### 按 session 中断是另一个明确的操作

```java
agent.interrupt(ownerId, sessionId);
```

它选择该 session **此刻正在执行**的调用，空闲时不产生效果。它无法选中某个排队 run，也不是“取消该 session 的全部待办”。用户点击旧消息上的停止按钮时，优先使用该消息绑定的 runId；按 session 中断可能选中后来已经开始的新执行。

## A 正在运行、B 和 C 排队时，取消 B

在同一个 Agent 实例中，同一 `(userId, sessionId)` 的 Core 调用串行执行：

```text
A 正在运行 → B 等待 → C 等待
                 ↓ 取消 B
A 继续运行 ─────────→ A 结束后 C 才能进入
```

B 的句柄拥有自己的控制信号。取消 B 不会向 A 写入中断标志，B 的取消也不会提前释放 A 占用的执行位置。这个保证以 Core 实际订阅入队顺序为准，不是 HTTP 到达时间；如果产品要求严格按消息提交顺序执行，应在应用层串行安排同一 session 的准入。

这也不是跨 Agent 实例或跨进程的全局锁。自行部署多个 SDK 实例时，需要会话路由、分布式租约或其他准入协调来保护同一份会话状态。

如果“停止”按钮代表“取消当前执行及所有尚未发送的消息”，应用应在同一个 session 的准入协调中取消已登记的相应句柄，并清空自己管理的待发送队列。不能只取消 A，然后假设 B、C 也会消失。

## 补充消息：三种不同的体验

### 方式一：作为下一轮输入，保留当前工作

创建一个使用相同用户和 session 的新句柄并订阅：

```java
AgentRun<Msg> next = runs.register(ownerId, sessionId,
        agent.prepareCall(List.of(new UserMessage("下一轮请再比较退改签政策")),
                SessionRunGuide.context(ownerId, sessionId)));
next.stream().single().subscribe(
        reply -> System.out.println(reply),
        error -> System.err.println(error.getMessage()));
```

在同一个 Agent 实例内，它会等待前序同 session 调用释放执行位置。这条消息不会修改 A 已经发送给模型的请求。若使用 Service HTTP API，请遵守 turn lease 的冲突响应，由应用管理“待发送”消息，而不是假设服务已帮你排队。

### 方式二：不结束当前 run，通过 Inbox 补充信息

Harness 可以在每个推理步骤开始前读取 session inbox。先在构建 Harness 时提供同一个 MessageBus；Builder 会自动安装 `InboxMiddleware`：

```java
import io.agentscope.harness.agent.HarnessAgent;
import io.agentscope.harness.agent.bus.MessageBus;
import io.agentscope.harness.agent.bus.WorkspaceMessageBus;
import io.agentscope.harness.agent.filesystem.local.LocalFilesystem;
import java.nio.file.Path;

Path workspace = Path.of("./workspace").toAbsolutePath();
MessageBus bus = new WorkspaceMessageBus(
        new LocalFilesystem(workspace), workspace.resolve(".agent-bus").toString());
HarnessAgent harness = HarnessAgent.builder()
        .name("travel-assistant")
        .model(model) // 应用已配置好的 io.agentscope.core.model.Model
        .workspace(workspace)
        .messageBus(bus)
        .build();
```

生产者使用相同 bus 和 session ID 入队；下面的辅助方法返回冷 `Mono`，需要由调用链订阅：

```java
SessionRunGuide.supplement(bus, sessionId, "参考信息：酒店优先靠近会场")
        .subscribe(ignored -> {}, error -> System.err.println(error.getMessage()));
```

其实际负载为 `{"id":"唯一消息ID","source":"user-supplement","hint":"补充内容"}`。Inbox 会将其转换为 `HintBlock`，并在读取时发出 `HintBlockEvent`。

当前实现还有一个需要保守处理的边界：Core 在进入 `onReasoning` 前已经准备了本轮 `ReasoningInput`，而 Inbox 更新会话历史后仍把原来的 input 传给后续链路。当模型输入已经复制成独立列表时，新提示不会自动补进该列表。因此，即使收到了 `HintBlockEvent`，也只能确认提示已被取出并写入历史，不能承诺它已包含在紧接着的模型请求中；它可能要等再下一步推理或下一次调用才进入模型输入。

所以 Inbox 适合可延后采纳的参考信息。预算上限变更、撤销下单授权等必须立即生效的要求，应选择“中断旧执行 + 新消息”，并同步落实工具侧的约束，不依赖提示的消费时机。

需要明确告知用户：

- 入队成功不表示模型已经看见或遵循这条信息。它不能改写正在生成的模型请求，也不撤销已经发出的工具调用。
- 如果当前 run 在下个推理步骤前已经结束，消息可能要等下一次调用才被读取。`inboxPush` 本身不负责启动一个新 run。
- Inbox 按 session 路由，不绑定某个 runId；不得用它表达“仅对旧 run 有效”的取消指令。紧急撤销授权应由工具权限与取消链路处理。
- 当前 inbox 键只包含 sessionId，不包含 userId。共享 bus 时，应使用服务端生成的全局唯一 sessionId，或由应用隔离不同租户的 bus 存储，并先做访问权限校验。`source` 只是消息标签，不是身份凭证。
- `WorkspaceMessageBus` 示例用于单进程场景；不要把同一个文件目录当作已经提供分布式准入与消息可靠性保证。

应用关闭时再关闭 Harness 和 bus，不要每发送一条补充消息就关闭它们。

### 方式三：中断旧任务，用新要求开启下一次执行

例如用户说：“不订机票了，改查高铁。”应当显式停止旧方向，再提交新方向。辅助方法会先请求协作中断，等待最多三秒，超过宽限期则取消旧 run，随后以新的 RuntimeContext 提交用户消息：

```java
SessionRunGuide.interruptThenFollowUp(
        agent, runs, currentRun, ownerId, sessionId,
        "不订机票了，改查高铁；请先核对之前是否已产生订单。",
        Duration.ofSeconds(3))
    .subscribe(
        reply -> System.out.println(reply),
        error -> System.err.println(error.getMessage()));
```

这里的 `currentRun` 必须是按认证身份和 session 查到的目标句柄，不能信任请求中任意声称的当前 run。辅助方法不会删除已经排队的其他调用；若产品规定“最新要求取代所有旧待办”，需要先通过应用层的 session 准入协调取消这些待办。等待终态也不能替代外部订单、Shell 进程等实际结果的核对。

不要在另一线程直接写 `agent.getAgentState().contextMutable()`，也不要把 `agent.observe()` 当成多 session 的实时注入入口。当前 `observe()` 实现通过默认状态入口追加历史，没有这里所需的显式 session 路由和执行队列保护。使用新调用或 Inbox，让消息在明确的执行边界进入上下文。

## 超时、断线和重新连接

### 总执行时限

```java
SessionRunGuide.replyWithDeadline(run, Duration.ofSeconds(60))
    .subscribe(
        reply -> System.out.println(reply),
        error -> System.err.println(error.getMessage()));
```

它实际执行 `run.stream().single().timeout(...)`：只订阅一次，并将排队时间计入总时限。超时取消上游，句柄转为 `CANCELLED`，调用者收到 `TimeoutException`。

不要把 `run.stream().timeout(...)` 的事件流超时直接理解成总时限：持续到达的事件会影响它的超时语义。仅对 `run.termination()` 使用 `timeout`，也只会停止等待终态，不会自动取消正在执行的 run；需要像上面的中断后续调用示例一样显式调用 `cancel()`。

### 断开的是观察者，还是执行所有者？

如果把 `run.stream()` 直接作为 HTTP 响应流，客户端断连可能取消唯一执行订阅。这适合“页面关闭就停止”的产品，但必须明确约定。

如果任务需要后台继续运行，服务端应持有执行订阅，浏览器只订阅独立的事件源：

```java
AgentRun<AgentEvent> eventRun = runs.register(ownerId, sessionId,
        agent.prepareRun(List.of(new UserMessage("生成出差报告")),
                SessionRunGuide.context(ownerId, sessionId)));
SessionRunGuide.LiveRun live = SessionRunGuide.startDetached(eventRun);

// HTTP 层向浏览器提供 live.events()，不要再次订阅 eventRun.stream()。
// 浏览器离线只解除对 live.events() 的订阅。
// 用户明确停止时：
runs.cancel(ownerId, sessionId, eventRun.runId());
```

辅助代码使用最多保留 256 个事件的内存 replay sink，仅用于展示订阅所有权。它不支持跨进程恢复，也不保证重连时补回全部事件。生产环境应持久化事件并提供序号游标；重新连接读取事件，不要重新执行原来的 `AgentRun`。如果确实需要重试任务，要创建新的 run，并在业务层防止重复副作用。

示例中的 `subscribe` 也可能经过同步准备工作；不要在 Netty 事件循环上执行阻塞的模型、工具或资源初始化。使用应用的后台执行设施，并由同 session 的准入层保证需要的消息顺序。

## AgentScope Service 的实际 HTTP 调用

下面使用数据面的 session 事件 API。`BASE_URL` 应指向部署中暴露该 API 的网关或数据面地址，`TOKEN` 是有权访问该 session 的用户凭据。

### 1. 提交消息，记录这次 SDK run 的 ID

```bash
curl -X POST "$BASE_URL/api/sessions/$SESSION_ID/events" \
  -H "Authorization: Bearer $TOKEN" \
  -H 'Content-Type: application/json' \
  -d '{"events":[{"type":"user.message","payload":{"text":"生成出差报告"}}]}'
```

从后续 `session.run_started` 事件的 `payload.run_id` 获取 SDK 执行 ID。它不是 sessionId、编排 Run ID、托管 Attempt ID，也不是事件中的 replyId。将这个 ID 与前端这次执行绑定；不要用“当前页面里最后看见的任意 ID”替代。

### 2. 精确取消这一次执行

```bash
curl -X POST "$BASE_URL/api/sessions/$SESSION_ID/events" \
  -H "Authorization: Bearer $TOKEN" \
  -H 'Content-Type: application/json' \
  -d '{"events":[{"type":"user.interrupt","payload":{"run_id":"替换为SDK执行ID"}}]}'
```

这个 Service 入口执行的是精确 **cancel**，不是 SDK 的协作 `run.interrupt()`。服务会校验 session 访问权限、本地执行归属，并在远程请求中携带 run 匹配条件。旧 run 已结束或被替换时，过期请求不能取消新的 run。

HTTP 请求成功表示请求已处理或已进入协调链路，不保证工具进程已经停止。跨实例取消需要持有执行的实例接收请求；客户端应继续查看相关 session 事件及业务结果。不要因为没有立刻看到终态，就改发不带 runId 的中断请求。

省略 `run_id` 的 `user.interrupt` 明确表示“停止这个 session 的当前 turn”，可能选中后续执行。显式提供空字符串、null 或非字符串 `run_id` 会报参数错误，不会回退成 session 中断。

### 3. 补充下一轮消息与重连

Service 的 `user.message` 是新一轮调用，不是对正在运行的 SDK run 的 Inbox 注入。忙时应处理冲突，在旧 turn 释放后再次提交。不要把“取消 + 新消息”放在一个 HTTP 批次里当成跨实例原子替换：远程取消尚未生效时，新消息仍可能无法准入。

读取或续接事件可以使用序号游标：

```bash
curl "$BASE_URL/api/sessions/$SESSION_ID/events?after=42" \
  -H "Authorization: Bearer $TOKEN"

curl -N "$BASE_URL/api/sessions/$SESSION_ID/events/stream?after=42" \
  -H "Authorization: Bearer $TOKEN"
```

`system.message` 在这个 API 中修改的是 session 的 agent overrides，不是当前模型调用的热更新或 Inbox 消息。需要运行中补充提示时，应在自己的 Harness 集成中使用前面的 Inbox 能力；本 PR 没有新增一个通用的 HTTP 实时注入端点。

## 审批、子任务与应用停机的边界

审批只决定一项工具行动是否可以继续。如果用户只是拒绝该工具，应使用对应的工具确认接口；Service 的个人 session 可发送 `user.tool_confirmation`，托管 AgentTask 则应通过控制面的 Approvals 处理。个人 session 拒绝一次工具调用的请求示例：

```json
{"events":[{"type":"user.tool_confirmation","payload":{"tool_use_id":"待确认的工具调用ID","allow":false,"denyMessage":"请使用只读查询，不要创建订单"}}]}
```

将它提交到同一个 `POST /api/sessions/{id}/events`。`tool_use_id` 必须来自实际待确认请求，不能用 runId 替代。

用户要求停止整个任务时，才取消执行，并由服务清理相关等待状态。手动取消 `CompletableFuture`、修改审批记录或只关闭前端弹窗都不能替代执行取消。

同步嵌套调用有自己的执行控制；它的订阅若处于父执行链中，取消父订阅通常会向下传播。独立启动的后台子任务、远程工具和外部进程则需要各自的句柄、任务取消协议和收尾确认，不应只凭父 run 已取消就将整棵任务树标为已停止。

应用停机时，停止接收新的工作，让现有执行在宽限期内完成或协作中断。当前优雅停机逻辑绑定每次执行的控制和该次调用解析出的状态，排队调用不会借用另一执行的状态进行中断或保存。对于还没有订阅的已登记句柄，应用也应主动取消并清理；仅 `CREATED` 的句柄尚未进入 Core 的活跃请求跟踪。

SDK 进程停机时可在应用的阻塞关闭线程中调用：

```java
import io.agentscope.core.shutdown.GracefulShutdownConfig;
import io.agentscope.core.shutdown.GracefulShutdownManager;
import io.agentscope.core.shutdown.PartialReasoningPolicy;
import java.time.Duration;

GracefulShutdownManager shutdown = GracefulShutdownManager.getInstance();
shutdown.setConfig(new GracefulShutdownConfig(
        Duration.ofSeconds(10), PartialReasoningPolicy.SAVE));
shutdown.performGracefulShutdown();
boolean drained = shutdown.awaitTermination(Duration.ofSeconds(15));
// drained 为 false 时仍有未退出请求；继续按应用的外部任务关闭策略处理。
```

这个管理器是进程级单例，影响进程内使用它的 Agent 请求；不要用它实现某个用户的“停止”按钮。停机宽限期与用户请求自己的超时是两类不同控制。

## 应用交付前应验证的用户流程

- A 运行、B/C 排队时取消 B：A 不受影响，C 不越过 A。
- 旧 run 的停止请求延迟到达：不能终止当前新 run；重复取消保持幂等。
- 中断后补充新目标：新目标作为显式输入出现，旧待办按产品约定保留或取消。
- Inbox 补充：区分已发送、已读取与实际采用，当前 run 结束后仍能解释消息的去向。
- 超时与断线：界面行为和后台执行所有权一致，重连不会重复执行任务。
- 跨用户、跨 session 和跨实例取消：归属检查及匹配条件有效，终态后活动登记被清理。
- 取消涉及外部副作用的工具：能检查真实结果，不能仅根据 run 状态推断回滚或远程退出。

进一步阅读：[Agent 调用与执行控制](/v2/zh/docs/building-blocks/agent)、[上下文与状态](/v2/zh/docs/building-blocks/context)、[Service API](/v2/zh/service/api-reference)。

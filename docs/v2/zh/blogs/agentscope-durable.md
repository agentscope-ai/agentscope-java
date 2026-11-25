---
title: "AgentScope Durable：让企业 Agent 持续、可靠地运行"
description: "面向企业长程任务的 Durable 执行设计，支持中断恢复、会话回放、执行中插话与独立沙箱异步执行。"
author: 刘军、朱志宇
---

AgentScope 是一款企业级 Harness 框架，帮助开发者构建分布式企业 Agent。面向企业场景中普遍存在的长程任务，AgentScope 进一步抽象出一套 Durable 能力，支持任务中断后的自动恢复、会话回放、执行中插话，以及工具在独立沙箱中的异步执行。即使用户断开连接、服务重启或执行实例被替换，任务仍能保留进度并继续推进，为企业构建各类 Agent 服务和 Agent 平台提供可靠的执行基础。

## 01 企业 Agent 为什么需要 Durable

一个研发 Agent 接到任务：定位故障、修改代码、运行测试，并在获得批准后提交结果。模型已经完成分析，测试需要运行二十分钟，审批可能第二天才到。期间，用户关闭了页面，服务进行滚动发布，最初执行任务的实例被替换。

这项工作应该继续。已经启动的测试应该能够被找到，已经确认的结果应该能够被复用，审批到达后应该由具备执行条件的实例接着处理。用户再次打开应用，看到的仍然是同一项任务。

![一项任务跨越用户连接、应用、Agent 进程和外部执行系统](/imgs/v2/agentscope-durable-01.png)

*图 1：一项任务跨越用户连接、应用、Agent 进程和外部执行系统。橙色标记指出断线、实例替换、结果交接、审批等待与重新进入时需要解决的连续性问题。*

这类问题会随着 Agent 承担长期工作而变得普遍。一次研发任务包含分析、修改、测试和审批；一次业务处理可能经过多个系统，并等待外部反馈。任务的持续时间超过了浏览器连接、服务请求和执行实例的寿命，应用就需要在这些边界之间保持连续性。

保存会话历史能让模型重新获得上下文，但“已经决定运行测试”与“测试已经完成”是不同的事实。连接中断后，系统还要知道测试是否被受理、原操作在哪里、结果是否已经接回，以及 Agent 是否已经消费了这个结果。仅凭一条“正在测试”的消息，无法决定下一步应该查询、等待还是执行。

图中的五个问题指向同一个要求：让任务的身份、执行进度与继续条件保存在进程之外，并为它们建立明确的恢复规则。用户离线后任务仍然存在；实例被替换后，兼容执行者能够接管；工具完成或审批到达后，系统能够找到原任务，继续尚未完成的工作。

这就是本文所说的 Durable：将 Agent 的执行过程组织为可持久保存、可确认和可恢复的状态，在原生检查点、工具交接和等待条件等位置建立接续边界。恢复从已提交的逻辑状态继续，复用已确认结果，并核实尚未完成的外部操作。

这个定义也确定了能力边界。恢复需要兼容的执行环境和可访问的持久状态；外部动作能否安全重试，还取决于工具提供的查询与幂等能力。例如邮件已经发出、回执却丢失时，系统需要核实原操作。Durable 保存处理这种不确定性所需的身份和状态，不会自动让任意业务动作恰好执行一次。

## 02 AgentScope Durable 定义与架构

### 1. 总体架构

AgentScope 的三层核心抽象分别承载推理循环、上下文管理与持久执行。应用可以按需要组合这些能力。

![ReActAgent、HarnessAgent 与 Durable 三层核心抽象](/imgs/v2/agentscope-durable-02.png)

*图 2：ReActAgent 提供推理循环，HarnessAgent 组织上下文，Durable 管理持久任务生命周期。*

ReActAgent 提供 Reasoning–Acting 推理循环：模型依据当前输入决定下一步，通过工具行动，再将观察结果带入后续推理。

HarnessAgent 在这些基础能力上组织上下文与执行环境，包括上下文构建和压缩、长期记忆、工作区、Skills 与子 Agent。它负责把任务所需的信息和能力带到每一次模型决策中。

Durable 进一步管理工作在时间和实例之间的延续：输入如何受理、任务如何排队、哪个实例可以推进、在哪里恢复、等待什么结果，以及应用如何重新观察和控制任务。它通过 HarnessAdapter 衔接原生状态，当前可适配 ReActAgent 或 HarnessAgent。

在框架之上，开发者可以构建企业 Agent、Personal Agent 或托管平台，通过 API、HTTP/SSE 和 AG-UI 事件适配等方式接入业务界面。AG-UI 负责事件格式适配，业务服务仍负责请求入口、身份认证与资源授权。

展开 Durable 的内部职责，可以看到以下关系：

![Durable 内部职责与五个连续性问题](/imgs/v2/agentscope-durable-03.png)

*图 3：五个编号对应图 1 的问题。Durable 连接原生 Harness、持久状态与独立工具执行，形成可接续的任务链路。*

一次请求进入系统后，DurableRuntime 先保存任务，再安排 Agent 执行。HarnessAdapter 将 Agent 的原生检查点接入 SessionBackend，使执行位置能够被下一次调用读取。遇到需要独立执行的工具时，Runtime 通过 Executor 把工作交给 Hands，等结果回来后继续原任务。

图中展示的是职责边界。它们可以装配在同一 JVM，也可以按需要拆分部署。只处理提交、查询和观察的 API 服务可以不启动 Agent 执行循环；执行宿主显式启动 Runtime，认领并推进持久任务。Durable 可以独立嵌入应用，不依赖 Managed Agents 平台服务。

### 2. 请求排队与执行中插话

用户让 Agent 调查订单服务超时，又补充了一句“调查结束后整理一份报告”。随后，他想起这次任务有一个约束：“先不要改生产配置。”这三条消息需要不同的处理方式：报告是后续工作，应该排队；约束则应该进入正在执行的调查。

Durable 用 Turn 表示一次任务，用 Thread 保存持续上下文和任务队列。新的工作通过 submit 创建 Turn，在同一个 Thread 中按受理顺序执行；对当前工作的补充通过 steer 交给原 Turn。应用因此可以同时支持“接下来做什么”和“这件事请调整一下”。

插话会先被保存，再在原生执行的安全边界进入 Agent。已经发出的工具调用不会因此被撤回，正在生成的模型响应也不会被强行改写。应用可以通过后续事件确认要求已经应用。用户关闭页面或服务重启后，已经受理的请求和补充信息仍然可以被找到。

这也让等待期间的行为更清晰：当前调查正在等测试或审批时，后面的报告继续排队。如果另一件工作不需要等待调查完成，可以交给独立 Thread。需要先停下来检查时，应用还可以暂停原 Turn，确认暂停后再补充要求或恢复。

### 3. Session 断线重连与会话重放

用户在测试运行期间关掉页面，第二天重新打开时，期待看到昨天的任务、执行记录和当前结果。应用需要找回原 Session，而不是重新创建一段对话并再次发起测试。

Durable 将任务执行与客户端连接分开管理。Session 汇集一次业务会话，Thread 保留其中各自的上下文，Turn 标识具体任务。应用保存这些身份，重新连接后就能查询原任务。若第一次提交的响应丢失，还可以用原 requestId 和原输入重试，取得同一个 Turn。

为了恢复界面，Durable 提供消息历史、状态快照和带游标的持久事件。页面可以从上次消费的位置接着读取进度；历史游标失效时，使用快照恢复当前视图。这种会话重放重建的是用户看到的过程，不会重新调用模型或执行工具。

生成中的文字可以实时展示，最终消息和任务状态则以持久记录为准。即使连接期间丢了部分文字预览，用户重新进入后仍能取得已确认的内容。浏览器断线、观察超时和关闭订阅，都不会隐式取消后台任务。

### 4. 长耗时工具异步执行与精准恢复

运行测试、生成报表或处理大批文件，可能比一次模型调用长得多。Agent 发起这些工具后，需要保留“正在等哪项工作”的信息，并允许工具独立推进，直到结果具备接回条件。

Durable 为外部工具保存稳定的 executionId、固定参数和执行状态。工具开始前，Agent 检查点与调用意图一起提交；工具结果返回后先被保存，再交给原 Agent；结果已经进入上下文的确认，与新的检查点一起提交。这样，中断后就能定位究竟缺少哪一步。

以二十分钟的测试为例，恢复方式取决于原操作的实际状态：

| 中断后查到的状态 | 如何继续 |
| --- | --- |
| 测试仍在运行 | 按原执行身份继续查询，等待结果 |
| 测试已结束，结果尚未交付 | 补交原结果，不重跑测试 |
| 结果已交付，Agent 尚未应用 | 恢复原检查点，将该结果接回上下文 |
| 测试明确失败 | 把失败结果交给 Agent，决定修复或发起新测试 |
| 原操作结果无法确认 | 保留 UNKNOWN，继续核实或进入业务处理流程 |

这里的“精准恢复”体现在找到原操作和缺失的交接步骤。测试本身报错、工具服务失联、结果传输中断，是不同的问题；把它们全部处理成“再执行一遍工具”，容易重复工作甚至产生重复副作用。

恢复粒度还取决于工具的实现。外部服务提供查询和幂等能力时，可以接回原执行；一个内部发送五封邮件的黑盒工具，要恢复到第四封，需要自己保留逐封操作身份。Durable 负责衔接这些执行事实，不猜测工具内部未记录的进度。

### 5. 长任务中断后的事件唤醒与自动认领

测试还没有结束，承载 Agent 的服务却开始滚动发布。实例 A 退出后，任务应该由其他可用实例接着处理，用户不需要再发送一句“继续”。

在多实例部署中，可以通过 RocketMQ 传递会话变更通知。新请求、审批响应或工具结果落库后，通知唤醒 Runtime，促使它读取当前状态并寻找可运行的工作。工具命令与结果使用独立的执行通道交接，分别处理持久受理、去重与补发。

认领任务时，Runtime 通过共享 Backend 获取执行租约。实例 A 失联、租约到期后，兼容的实例 B 可以认领未完成工作，加载检查点并继续原 Turn；新调用会留下新的 Run 和 Step，便于排查这项工作经历过哪些执行尝试。旧实例即使恢复，其过期执行权也会被 Fencing 拦住，不能覆盖新进度。

MQ 通知帮助系统及时发现变化，持久状态和租约决定谁能从哪里继续。实例突然退出时未必能发出通知，因此 Runtime 还会定期扫描，发现过期租约和遗漏的可运行任务。这样，通知延迟、重复或丢失都不会成为恢复的唯一障碍。

跨主机接管需要共享存储、兼容的 Agent 定义和工具绑定，以及对原工作区的访问权限。对于可重试的调用失败，重试次数和退避时间也会保存下来，避免每换一个实例就从零开始重试。

### 6. Brain–Hands 分离，让工具在不同 Sandbox 中执行

同一个研发 Agent 可能既要编译 Java 项目，又要运行 Python 数据分析。两个工具需要的依赖、资源和权限不同，也不一定适合与推理进程放在同一个环境中。

Durable 将推理侧称为 Brain，将工具执行侧称为 Hands。Brain 保留 Harness、上下文和决策过程，通过工具绑定选择 Executor；Hands 接收调用，管理原执行的状态与输出，并在对应 Sandbox 或外部系统中完成工作。模型发起工具调用前，仍然经过原生权限检查。

应用可以把构建工具路由到具备 JDK 和构建缓存的 Hands，把分析工具路由到具备 Python 依赖的另一组 Hands。两侧通过 HTTP 或 RocketMQ 交接请求与结果，可以分别扩容、更新环境和设置权限。一个 Hands 也可以按照明确的资源绑定访问多个 Sandbox。

SandboxBindings 将逻辑工作区与实际 Sandbox 关联并保存。恢复时使用原绑定连接原资源，继续查询之前的命令和产物。Brain 被替换，不意味着要重建测试环境；Hands 进程重启，也不能把新的空目录当作原工作区。

这种分离让计算资源跟随工作特点配置：推理按请求推进，长耗时工具在独立环境中运行。Sandbox 的创建、共享、保留与清理由宿主明确管理，未完成任务依赖的环境和执行记录需要保留到工作收尾。

### 7. 用 Thread 同时推进修复与独立审查

回到故障处理场景：主 Agent 已经生成补丁，正在等待测试；与此同时，团队希望另一个 Agent 检查这份补丁是否引入安全问题。如果审查请求进入同一队列，就要等当前任务结束；如果把两项工作的上下文混在一起，又容易让调查过程和审查意见互相干扰。

一个 Session 可以包含多个 Thread。主 Thread 继续修复和测试，审查 Thread 读取指定版本的补丁，独立检查风险。两者拥有各自的历史、检查点和任务队列，可以在宿主容量允许时并行推进，应用则在同一 Session 中观察整体进展。

审查结束后，用户还可以在原审查 Thread 中追问“再检查异常处理路径”，沿用此前的审查上下文。需要把结论反馈给主任务时，由应用明确转交；如果是父 Agent 发起并等待子 Agent 的工作，可以使用 DelegationExecutor，将父调用与持久子 Turn 关联起来，恢复后继续等待原来的子任务。

Thread 解决的是上下文和任务组织。文件、凭据与 Sandbox 是否共享，需要另外配置。在这个例子中，让审查 Agent 读取固定版本的补丁产物，可以避免它一边审查、一边读到主 Agent 尚在修改的文件。

## 03 AgentScope Durable 使用

下面继续以故障调查助手为例，先接入持久任务，再逐步增加排队、插话、重连与独立执行能力。代码片段展示关键调用，模型、凭据和宿主生命周期由应用提供。

先看一套组件较完整的 Queued 部署：Brain 推进 Agent 任务，Hands 管理工具执行，两侧通过 RocketMQ 交接命令和结果。下图展开的是框架内部的组件关系，后续示例再从本地接入逐步扩展到这一部署方式。

![AgentScope Durable Queued 模式内部组件与执行链路](/imgs/v2/agentscope-durable-04.png)

*图 4：Queued 模式下的 Brain、Queue 与 Hands。*

图中存储表示职责边界，可以按部署需要复用数据库。

**① 派发工具。** DurableRuntime 通过 HarnessAdapter 驱动 AgentScope Harness，将任务进度和检查点保存在 SessionBackend。需要远程执行工具时，它调用已注册的 Executor。RocketMQ 模式使用 RocketMqRemoteExecutor，内部的 QueuedExecutor 通过 RecordStore 保存待发送命令与已接收事件，支持重试、去重和补发。

**② 在 Sandbox 中执行。** RocketMqWorker 接收并校验命令，写入 ExecutionStore 后确认消息。内部的 WorkerRuntime 根据执行账本认领工作，通过对应的工具 Executor 发起、查询或取消操作。图中的 Sandbox Executor 表示由 SandboxBindings 提供的执行适配；应用为不同工具配置资源绑定，就能分别使用 Java 构建与 Python 分析环境。恢复时沿用持久保存的 Sandbox 身份，连接原资源。

**③ 将结果接回原任务。** 工具输出和结果先进入 Hands 的执行账本，再由 RocketMqWorker 通过 updates 通道回传。Brain 侧的 QueuedExecutor 持久接收事件、按连续序号交付给 SessionBackend，随后 Runtime 恢复原 Turn，让 Harness 消费结果并继续推理。同一 executionId 关联整条链路，重复收到消息时可以核对原执行。

这里有三条独立传输通道：commands 负责执行，controls 负责查询与取消，updates 负责输出与结果。它们与 Thread 内用户请求的排队是两个层次。会话唤醒通知也独立于这些工具通道，MQ 接管部分会继续介绍；任务恢复的依据仍保存在两侧账本和检查点中。

### 1. 把一次调查交给 Durable

最小接入需要 Agent 工厂、持久 Backend 和 Runtime。下面使用应用已有的 model，将第一次调查保存为一个 Turn：

```java
import io.agentscope.core.ReActAgent;
import io.agentscope.durable.DurableRuntime;
import io.agentscope.durable.DurableTypes.SessionKey;
import io.agentscope.durable.agentscope.AgentScopeAdapter;
import io.agentscope.extensions.durable.jdbc.LocalBackend;
import java.nio.file.Path;
import java.util.Map;

var backend = LocalBackend.open(Path.of("./durable-data"));
var adapter = AgentScopeAdapter.forReAct(
        binding -> ReActAgent.builder()
                .name("dev-assistant")
                .model(model),
        Map.of());
var runtime = DurableRuntime.builder()
        .backend(backend)
        .harness("dev-assistant", "v1", adapter)
        .build();
var session = runtime.createSession(
        new SessionKey("demo", "incident-42"), "dev-assistant");
var investigation = session.submit(
        "investigate-1", "分析订单服务超时的原因");
runtime.start();
var turnId = investigation.turnId();
```

应用保存 Session、Thread 和 Turn 身份，宿主保持 Runtime 运行，退出时依次关闭 Runtime 与 Backend。同一请求重试时沿用原 requestId 和原输入；新的工作使用新的 requestId。

这里先展示任务受理，模型所需的日志、代码与工具由应用继续配置。接入 HarnessAgent 时使用 AgentScopeAdapter.forHarness，提供新的 Builder 并设置工作区。完整离线 Quickstart 可以从仓库根目录运行：

```bash
agentscope-examples/durable/integrated/quickstart/run.sh
```

它启动两个独立进程，复用同一请求身份。第二次取得原 Turn 的结果，不再调用模型，便于直接观察任务跨重启保留的效果。

### 2. 调查还在执行时，排队报告并补充约束

在调查仍在进行时，将报告作为下一项任务提交，再把约束送入当前调查：

```java
var report = session.submit(
        "report-1", "根据调查结果整理一份故障报告");
investigation.steer(
        "constraint-1", "重点检查数据库访问，先不要修改生产配置");
```

report 在主 Thread 中等待前面的任务结束；steer 指向原调查，不会创建另一项排队任务。插话被受理后，应用可以通过 signal.applied 等事件确认它已在安全边界生效。

如果后续报告已经不需要，可以取消尚在排队的 Turn：

```java
report.cancel("cancel-report-1");
```

审批同样应回应原任务。应用通过 pendingInteractions() 找到待处理审批，用户作出决定后调用 respond(requestId, interactionId, approved)。这样，第二天到达的批准也能接到原操作上。

### 3. 页面刷新后，找回调查和已经看到的进度

重新打开页面时，使用保存的身份连接原任务：

```java
var session = runtime.getSession(
        new SessionKey("demo", "incident-42"));
var thread = session.getThread("main");
var investigation = thread.getTurn(savedTurnId);
var current = investigation.status();
var history = thread.history(null, 100);
```

这些调用只读取原任务，不会重新发起调查。展示完整会话时可以订阅 Session；只展示这项调查时，可以订阅原 Turn，并从保存的游标继续：

```java
import io.agentscope.durable.EventStreamOptions;

var options = EventStreamOptions.builder()
        .after(savedCursor)
        .build();
try (var stream = investigation.streamEvents(options)) {
    stream.forEach(event -> render(event));
}
```

render 代表应用自己的界面更新逻辑。它需要处理持久事件和游标失效后的快照替换；保存消费游标后，下次连接就能继续。关闭观察连接时关闭 stream，后台任务仍由运行中的 Brain 推进。

### 4. 让测试异步执行，并接回中断前的结果

将测试工具配置为外部 Executor 后，Agent 发起调用，Durable 保存工具意图并进入等待；工具完成后，结果被交回原 Turn。业务代码不需要为了等待这二十分钟，一直保留最初的 HTTP 请求。

仓库的 Sandbox 示例展示了这条链路：

```bash
agentscope-examples/durable/integrated/tool-in-sandbox/run.sh
```

第一个进程让 Agent 发起 sandbox.exec，命令启动后等待继续信号。示例确认 Turn 为 WAITING、工具执行为 RUNNING，随后正常退出。

第二个进程读取同一份持久数据，找到原 Turn、原执行和原 Sandbox，再释放继续信号。Hands 封存命令输出，Brain 恢复上下文并消费结果，最终得到 Sandbox result applied: recovered。

这个过程检查 Turn ID、Sandbox 身份和工具输出能否接续，并确认示例中的副作用只发生一次。它展示的是接回原操作；如果测试本身明确失败，Agent 会获得失败结果，是否修改代码后重新测试属于下一步业务决策。

### 5. 接入 MQ，让可用实例及时接手工作

跨主机运行时，多个 Brain 连接同一份共享状态，并注册兼容的 Agent 定义。下面展示 PostgreSQL 与会话唤醒通知的装配关系：

```java
import io.agentscope.extensions.durable.jdbc.PostgresBackend;

var backend = PostgresBackend.builder()
        .dataSource(dataSource)
        .notifications(rocketMqWakeups)
        .build();
var runtime = DurableRuntime.builder()
        .backend(backend)
        .harness("dev-assistant", "v1", adapter)
        .build();
runtime.start();
```

这里的 dataSource 指向已经初始化表结构的共享数据库；rocketMqWakeups 是使用已配置 RocketMQ 客户端和 Topic 构造的 RocketMqWakeups，负责传递 Session 变更提示；adapter 对应前面的 Agent 定义。各 Brain 启动后会监听通知并扫描任务，在容量允许时认领工作。

审批到达或任务状态变化，可以通过通知加快调度；实例突然失联，则由租约到期和后台扫描发现需要接管的任务。应用不用遍历会话并重新提交用户请求。替换实例仍需访问原工作区、工具绑定和必要资源。

工具的远程执行需要另外配置 RocketMqRemoteExecutor 和 RocketMqWorker，它们负责工具命令、控制和结果通道。RocketMqWakeups 只传递唤醒提示，不承担工具结果传输。

### 6. 把构建和分析工具放到不同 Sandbox

假设应用已经准备好构建与分析工具的 Schema，并分别部署了两组 Hands。Brain 侧通过工具绑定和 Executor 名称选择执行位置：

```java
import java.util.List;

var routedAdapter = AgentScopeAdapter.forReAct(
        binding -> ReActAgent.builder()
                .name("dev-assistant")
                .model(model),
        List.of(
                buildTool.route("build"),
                analysisTool.route("analysis")));
var runtime = DurableRuntime.builder()
        .backend(backend)
        .harness("dev-assistant", "v1", routedAdapter)
        .executor("build", buildExecutor)
        .executor("analysis", analysisExecutor)
        .build();
runtime.start();
```

这是部署时替换前面装配的片段：buildTool、analysisTool 是双方一致的 ToolBinding，包含工具 Schema 和目标配置；两个 Executor 是已经配置好、分别连接对应 Hands 的远程执行端点。

在 Hands 侧，为构建任务绑定带有 JDK 的 Sandbox，为分析任务绑定带有 Python 依赖的 Sandbox。通过 SandboxBindings 保存各自的资源身份，命令执行和重连都沿用该身份。多个工具也可以有意共享工作区，是否共享及其权限由应用明确配置。

独立进程的完整装配可参考 agentscope-examples/durable/brain-hands/tool-in-sandbox。该示例需要真实 RocketMQ 服务、Topic 和访问权限；本地 Sandbox 使用宿主权限执行命令，生产环境应配置实际的隔离环境。

### 7. 在等待测试时，另开一个 Thread 审查补丁

主调查还在等待测试，应用已经得到一份固定版本的补丁。此时可以在同一 Session 中创建独立的审查 Thread：

```java
var reviewThread = session.createThread("review", "dev-assistant");
var review = reviewThread.submit(
        "review-1", "审查已提供的补丁快照，重点检查安全与兼容性问题");
// 后续追问继续使用审查线程自己的上下文。
session.getThread("review").submit(
        "review-2", "再检查这份补丁的异常处理路径");
```

补丁快照及读取权限由应用配置到审查工具或工作区。这个示例复用同一个 Agent 定义，但审查 Thread 拥有独立上下文，不会被主 Thread 中等待测试的 Turn 阻塞；同一审查 Thread 内的两次请求仍按顺序处理。

应用可以统一展示两个 Thread 的状态，也可以把已经确认的审查结论作为补充信息交给原调查。普通 Thread 创建不会自动把审查对话放进主 Agent 的上下文；需要 Agent 自主委派并等待结果时，再把委派工具接入 DelegationExecutor，由 Durable 维护父子任务关联。

## 04 从持久执行到持续服务

当 Agent 能够可靠完成一项长程任务，下一步便是让它持续承担一项职责。例如，一个研发 Agent 可以从“处理这次故障”扩展为“持续关注服务运行情况，在异常出现时调查、推动修复，并跟进验证结果”。这时，产品需要组织多次工作之间的联系：什么时候开始，什么时候等待，哪些信息应该保留，以及何时需要用户参与。

Anthropic 在 [Building always-on agents](https://claude.com/resources/articles/building-always-on-agents) 中将这种体验描述为一个反复发生的周期：事件到达后唤醒 Agent，完成当前工作、保存信息，再等待下一次行动。事件收件箱、定时器、记忆、权限和消息发件箱共同支撑这个周期。“持续在线”由这些组件协作形成，Agent 可以在没有工作时休眠。

结合 AgentScope 的设计，可以将这一周期建立在 Durable 的持久任务之上。以持续跟进故障为例，告警、用户消息和外部回调先进入应用的事件入口。应用保存事件及其处理状态，将事件关联到具体的业务会话，再用稳定的请求身份提交给 Durable。相同事件重投时可以找到原 Turn；新的工作进入对应 Thread 排队，执行中的补充要求则通过插话送入当前任务。事件入口负责判断业务关联，Durable 负责持久受理与执行，避免每个事件来源各自启动一份难以追踪的工作。

任务开始后，Harness 组织日志、代码、记忆和工具，推进分析与决策。耗时测试交给 Hands 在 Sandbox 中执行，Durable 保存等待位置和原执行身份。需要用户批准的操作，则保留对应的待处理交互，审批响应到达后接回原任务。等待期间无需维持最初的推理调用，兼容的 Brain 可以在结果具备接回条件时继续推进；操作能否执行，仍由应用的授权策略和 Harness 的权限机制决定。

持续跟进还需要表达未来的行动。例如修复完成后，Agent 希望三十分钟后检查错误率是否回落。应用可以将检查时间与业务目标持久保存，由调度器在到期后生成事件，再交给 Durable 执行新的检查任务。这里要区分两种继续方式：测试结果、审批响应接回尚未完成的 Turn；定时巡检则创建新的 Turn。业务定时器负责安排下一次行动，Durable 的执行重试、租约与恢复机制负责让已受理的工作可靠推进。

跨越多次任务时，Harness 的长期记忆可以保留服务背景、用户偏好和故障处理经验，并在后续决策中按需加载。Durable 则记录具体任务执行到了哪里、哪些工具结果已经确认、还在等待什么。持续服务同时需要这两类信息：前者帮助 Agent 理解职责和业务背景，后者为任务恢复提供明确的执行依据。已经存在于工单、代码仓库或监控系统中的事实，也可以通过引用和工具查询重新获得。

工作结果还需要交付给用户。应用可以根据业务需要设置消息发件箱，保存待发送内容与投递状态，在发送失败后继续交付。故障已修复、风险升级、需要批准，都是值得通知的时刻；常规检查也可以只更新状态。这里的消息投递与 Queued 模式中工具结果的 Outbox 各有职责：工具结果接回 Agent，面向用户的消息则由产品选择渠道和通知节奏。“任务完成”和“用户已收到通知”应分别记录。

由此，AgentScope 的推理与上下文能力、Durable 的持久执行能力，以及应用提供的事件接入、业务调度和消息交付，可以组成完整的持续服务链路。部署时，团队可以先将这些组件嵌入已有业务系统，再按规模拆分 Brain、Hands 和 Sandbox，并集中管理凭据、配额、资源与审计。无论最终表现为企业业务助手、Personal Agent，还是由 Managed Agents 平台承载的服务，都可以复用这套设计：让每次行动有来源、执行有进度、等待有归属，后续工作能够在需要时继续。

## 结语

企业 Agent 在长程任务中面临的核心问题，是执行进度容易依附于一次连接、一个进程或某个实例：连接断开后难以追踪结果，服务重启后难以接续任务，耗时工具与审批等待又让执行跨越更长的时间。AgentScope Durable 通过持久化任务状态、异步工具执行和中断恢复，让工作能够跨越连接与实例的生命周期，在具备执行条件时继续推进，同时支持请求排队、执行中插话和会话回放。围绕这一目标，AgentScope 形成了三层核心抽象：ReActAgent 负责推理与行动循环，HarnessAgent 负责上下文管理及工作环境的组织，Durable 负责持久执行与恢复。三层协作，为开发者构建可交互、可恢复、可持续运行的分布式企业 Agent 提供统一基础。

[AgentScope Durable 开源实现](https://github.com/agentscope-ai/agentscope-java)

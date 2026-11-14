---
title: Agent 执行控制：直接调用与持久任务
description: 使用 AgentRun 控制单次直接调用，使用 Durable 管理跨进程排队、暂停、恢复和 HITL。
---

用户停止生成、补充要求或断开页面时，应用需要区分 Session、Thread、Turn 和 Run：

- Session 是用户级工作容器。
- Thread 隔离一条连续上下文，默认 Thread 为 `main`。
- Turn 是 Durable 中可持久调度的逻辑任务。
- Run 是一次实际执行；恢复同一 Turn 时会产生新的 Run。

原生 `ThreadJournal` 保存消息、Run/Step 事实和 checkpoint，但不启动任务 dispatcher。
2.1.0-BETA1 的 `AgentSession`/`SessionLog` 不属于升级兼容契约，当前版本不再提供这些入口。

## 直接调用：用 AgentRun 控制一次执行

直接调用的订阅和生命周期由应用负责。`prepareCall` 只创建句柄，订阅后才开始执行：

```java
RuntimeContext context = RuntimeContext.builder()
        .userId(ownerId)
        .sessionId(sessionId)
        .threadId("main")
        .build();

AgentRun<Msg> run = agent.prepareCall(
        List.of(new UserMessage("比较三个出差方案")), context);

String runId = run.runId();
run.stream().single().subscribe(
        reply -> System.out.println(reply.getTextContent()),
        error -> System.err.println(error.getMessage()));
```

`run.cancel()` 取消这次响应式执行；`run.interrupt()` 请求推理循环在后续检查点协作中断。
两者都不会撤销已经发生的外部副作用。需要从另一条 HTTP 请求控制运行时，应用应先按
用户、Session、Thread 和 runId 登记句柄，再启动唯一一次订阅。

同一 Agent 实例中，同一 Thread 的直接调用会串行进入执行位置。这个进程内顺序不等于
跨实例任务队列；多副本服务需要持久准入和执行所有权。

## 持久任务：使用 Durable Turn

需要跨进程排队、自动接管、暂停、恢复、HITL 或断线后继续运行时，使用 Durable：

```java
var session = runtime.getSession(sessionKey);
var turn = session.submit("request-001", "比较三个出差方案");

turn.steer("request-002", "预算改为 5000 元");
turn.pause("request-003");
```

暂停回执只表示请求已受理。观察到 `PAUSED` 后，使用该状态的 revision 调用
`turn.resume(requestId, revision)`。回答待办使用 `turn.respond`，取消任务使用
`turn.cancel`。页面查询、等待超时或 SSE 断连不会取消后台 Turn。

Durable 会把 Turn 映射到原生调用的 `turnId`，每次实际执行使用新的 Run。工具结果和
checkpoint 结清后，Durable 才把 Turn 标记为成功；原生 `run/end` 本身不决定持久任务状态。

## 历史与恢复

直接调用可通过 `agent.threadTranscript(context)` 读取消息，通过
`agent.inspectThread(context)` 检查原生状态和执行事实。Durable 页面应通过
Session/Thread/Turn 的 snapshot、history 和事件接口恢复，不应解析私有 journal 来推断任务状态。

AgentScope 2.0 的 `agent_state` 会自然映射到 `main` Thread。空的主 Thread 首次运行时导入
该状态，之后每个已提交 checkpoint 都镜像回同一 2.0 状态项；命名 Thread 不读取或覆盖它。

完整的日志、恢复和 Durable 配置见[Thread 执行日志与恢复](/dev/zh/docs/harness/session-log)
和 [Durable 指南](/dev/zh/docs/harness/durable)。

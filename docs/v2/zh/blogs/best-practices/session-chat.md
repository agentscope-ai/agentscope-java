---
title: 可恢复聊天示例
description: 运行 agentscope-chat，体验聊天历史、SSE 续传、用户补充与 checkpoint 恢复。
en_link: /v2/en/blogs/best-practices/session-chat
---

历史源码见 [v2.1.0-BETA1 的 agentscope-chat](https://github.com/agentscope-ai/agentscope-java/tree/v2.1.0-BETA1/agentscope-examples/agents/agentscope-chat)。本共享文章描述的是后来尚未发布的 DurableRuntime 实现；tag 中的示例使用 AgentSession，两种实现均不包含在 v2.0.4 中。

一个使用 HarnessAgent 和 agentscope-durable 的本地聊天应用。它演示持久任务、排队、暂停恢复、交互回答，以及公共消息和工具进度的 SSE 续传。

普通单次问答仍可使用 Agent 的 `call` / `streamEvents`。本例需要在浏览器关闭或应用重启后继续任务，因此通过 `DurableRuntime` 装配本地后端与 AgentScope adapter，业务请求统一使用 `runtime`。概念与通用 API 见 [Durable 持久任务](/dev/zh/docs/harness/durable)。

## 启动

需要 JDK 17+ 和 Maven；默认离线模型无需 API Key、数据库服务或 Node.js。SQLite 和对象目录由 LocalBackend 在本地创建。

在仓库根目录运行：

```bash
mvn -B -T1 -Dagentscope.frameworkOnly=true -pl agentscope-examples/agents/agentscope-chat -am package -DskipTests
java -jar agentscope-examples/agents/agentscope-chat/target/agentscope-chat.jar
```

打开 [http://127.0.0.1:8087](http://127.0.0.1:8087)。示例只监听本机。默认数据目录为 `~/.agentscope/session-chat`，其中 `durable/durable.db` 保存状态与事件，`durable/objects/` 保存对象。重启时保留整个数据目录。

```bash
CHAT_WORKSPACE=/absolute/path/to/chat-workspace CHAT_PORT=8088 \
  java -jar agentscope-examples/agents/agentscope-chat/target/agentscope-chat.jar
```

## 按顺序体验

### 聊天与历史

新建会话会显式创建 Session 和主 Thread。发送“请记住我喜欢简洁的回答”，完成后刷新或重启，再选择同一会话继续聊天。浏览器只保存选择和未确认请求的幂等 ID；消息、任务与结果从公共快照读取。查询不存在的会话返回 404，不会偷偷创建会话。

### 多步执行与断线续传

发送 `/slow`。离线模型生成三轮文字，先并行调用两次 `demo_lookup`，再调用 `demo_verify`。这些是通过 Toolkit 运行的本地只读工具，每次报告四段进度。

在文字生成、参数生成或工具运行时刷新、切换会话或断开事件连接，再回来检查所有消息和工具卡片。快照的 `projection.messages` 保存逻辑消息及版本，`projection.tools` 保存参数、状态、进度和结果。页面直接展示这些公共投影，不解析模型原始片段或私有 checkpoint。

快照的 `cursor` 与投影对应同一提交水位。SSE 从该 cursor 继续，EventSource 重连时以 Last-Event-ID 为准。cursor 是不透明字符串，客户端不拼接或解析；`sequence` 仅用于排序与去重。游标过期时重新读取快照。断连只结束观察，后台任务继续。

### 回答待办与同一任务续接

发送 `/ask`，Agent 通过 schema-only 的 `ask_user` 请求答案，Turn 进入 WAITING。刷新或重启后待答表单仍在。提交答案关联明确的 turnId 与 interactionId，原 Turn 继续，通过新 Run 执行。答案不会被当成另一个任务。

WAITING 由具体等待条件驱动，不能通过“继续”跳过待答请求。若先暂停该任务，回答仍可保存，但只有显式继续后才能推进。

### 暂停、继续与取消

发送 `/slow`，点击“暂停任务”，等待 PAUSING 变成 PAUSED，再重启应用。原任务仍暂停，后续任务保持排队。点击“继续原任务”携带观察到的 revision，沿用 Turn、创建新 Run/Step，从已提交状态继续。

暂停在可保存状态的安全边界生效，不等同于立即杀死正在执行的工具。取消是独立的终止操作：进入 CANCELLING 并核实已发出的操作，结清后成为 CANCELLED。结果未知的副作用不能通过再次运行工具来猜测。

Runtime 启动后自行发现可执行任务；浏览器无需在线。异常退出后的接管需要等待原租约失效，持久暂停则始终需要显式继续。

### 新任务、引导与材料

任务执行中选择“新任务”会创建另一个 Turn，并在同一 Thread 排队。“补充当前任务”和“向当前任务补充材料”都指向明确的未完成 Turn，携带独立 requestId，在安全边界应用。没有未完成任务时，这两个入口不可用；提交新任务即可开始下一轮。

暂停或等待的队首阻止后续任务越过。页面始终对队首未完成 Turn 显示控制按钮，不能因为新任务排到队尾而误操作它。响应丢失后重试使用相同 requestId、目标和内容。

### 公共事件与执行记录

右侧显示本次连接后最近 80 条公共事件。点击查看 sessionId、threadId、turnId、runId、stepId、sequence 和 data；完整历史可以通过分页 events 接口读取。Run 列表通过独立查询接口获取。Turn 表示任务，Run 表示一次调用，Step 属于一个 Run；正常结束一次调用不必然完成任务。

## 使用真实模型

```bash
export DASHSCOPE_API_KEY=your_api_key
export CHAT_MODEL=qwen-plus
java -jar agentscope-examples/agents/agentscope-chat/target/agentscope-chat.jar
```

真实模型使用自然语言，`/slow` 和 `/ask` 仅是离线演示命令。可以请模型调用 ask_user 询问偏好。切回离线模式设置 `CHAT_MODEL=demo`。示例将模型名称纳入定义版本；切换模型请新建会话，恢复旧会话执行仍需提供原定义版本。

## 应用 HTTP 接口

以下是示例的 Web 路由，内部调用通用 Durable Client；它们不另建任务状态机。

| 操作 | 路由与请求 |
| --- | --- |
| 创建 Session 与主 Thread | POST /api/sessions/{session} |
| 列举 / 查询快照 | GET /api/sessions；GET /api/sessions/{session} |
| 提交任务 | POST /api/sessions/{session}/turns，`{requestId,message}` |
| 引导 / 注入 | POST /api/sessions/{session}/turns/{turn}/steer 或 inject，`{requestId,message}` |
| 暂停 / 取消 | POST /api/sessions/{session}/turns/{turn}/pause 或 cancel，`{requestId}` |
| 继续暂停任务 | POST /api/sessions/{session}/turns/{turn}/resume，`{requestId,expectedRevision}` |
| 回答交互 | POST /api/sessions/{session}/turns/{turn}/answers，`{requestId,interactionId,output}` |
| 事件分页 / SSE | GET /api/sessions/{session}/events 或 stream，`after={cursor}`；events 支持 limit |
| 调用记录 | GET /api/sessions/{session}/turns/{turn}/runs，支持 after、limit |

建议依次阅读 ChatSessions.java 的 Runtime/工厂装配、ChatController.java 的薄 HTTP/SSE 层、static/chat-view.mjs 的公共投影展示，以及 static/chat.js 的操作和重连流程。每次实际调用返回新的 HarnessAgent Builder，由 adapter 安装原生日志并构建 Agent；业务 API 不访问该日志。

## 范围与验证

这是本机、单用户示例。生产应用可以使用相同 Client 和协议装配其他 Backend、独立 Brain 与 Hands，再由业务层提供鉴权和资源策略。Durable 不依赖 Managed Agents 平台。本例使用本地工具；独立队列 Worker 的完整流程见 Durable 的 Java examples。

```bash
mvn -B -T1 -Dagentscope.frameworkOnly=true -pl agentscope-examples/agents/agentscope-chat -am test \
  -Dtest=ChatSessionsTest,ChatControllerTest -Dsurefire.failIfNoSpecifiedTests=false
node --test agentscope-examples/agents/agentscope-chat/src/test/js/*.test.mjs
```

Java 测试使用临时 SQLite 数据目录和确定性模型，覆盖任务排队、输入去重、持久暂停、重启后回答、原 Turn 新 Run、工具进度、快照与事件回放一致性、HTTP 控制和 SSE 断连。前端测试检查公共投影、队首控制目标和大序号排序，不需要真实模型或外部服务。

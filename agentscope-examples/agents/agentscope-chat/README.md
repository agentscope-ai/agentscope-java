# AgentScope Session Chat

A runnable HarnessAgent Web Chat demonstrating durable conversations, live events, SSE replay and checkpoint continuation. The page includes chat, pending input and a committed event timeline.

This example builds on the [Quick Start](https://java.agentscope.io/v2/en/docs/quickstart). Use `call` / `streamEvents` for ordinary replies and streaming within the current request. This application uses `AgentSession` because work must continue after the page closes, queue while busy, accept answers and resume after interruption. Follow [Session operations, events and recovery](https://java.agentscope.io/v2/en/docs/harness/session-log) for the API walkthrough.

The default deterministic offline model needs no API key, database or Node.js. Requirements: JDK 17+ and Maven. The demo UI uses Chinese labels; its workflows and APIs are described below.

## Run

From the agentscope-java repository root:

```bash
mvn -pl agentscope-examples/agents/agentscope-chat -am package -DskipTests
java -jar agentscope-examples/agents/agentscope-chat/target/agentscope-chat.jar
```

Open [http://127.0.0.1:8087](http://127.0.0.1:8087). The server listens on loopback only. The default Workspace is ~/.agentscope/session-chat; its .agentscope-runtime/ directory contains native history. Preserve the directory across restarts.

Use another data directory or port:

```bash
CHAT_WORKSPACE=/absolute/path/to/chat-workspace CHAT_PORT=8088 \
  java -jar agentscope-examples/agents/agentscope-chat/target/agentscope-chat.jar
```

## Walkthrough

### 1. Conversation history after restart

Send a message asking the assistant to remember a preference. Wait for completion and refresh: messages remain. Stop and restart the application, select the same conversation, and send another message. The demo model reports the earlier messages in its restored context.

The browser remembers which session was selected; messages come from server history. Creating a new conversation changes sessionId without deleting earlier conversations.

### 2. Refresh and reconnect during a multi-step run

Send `/slow` using the 多步执行 · 刷新续传 preset. The agent streams an introduction, calls `demo_lookup` twice for different topics, streams another message, calls `demo_verify`, and finally streams a summary. The tools run local read-only fixtures through the real Toolkit and report incremental progress.

Refresh, switch conversations, or disconnect and reconnect at any of these stages:

- While any AssistantMessage is growing: its displayed prefix is restored before more text arrives.
- While ToolCall arguments are being generated: the card retains committed argument fragments.
- During tool execution: recover the name, arguments, status and committed progress.
- After several steps occurred while away: recover every intermediate message, ToolCall and ToolResult, together with the currently active item.

The browser always renders one durable view. Snapshot `items` includes finished and unfinished messages and tool cards; its `cursor` covers exactly that committed prefix. `messages` remains the complete-message transcript. Model chunks rebuild text and arguments; tool chunks rebuild progress. Full messages and results replace their corresponding items by stable ID without duplication.

SSE observes committed events and triggers view refreshes. EventSource reconnects with Last-Event-ID. A page reload fetches a snapshot, then subscribes after its watermark. Display updates follow log commits, so unreplayable temporary text is never joined to a durable prefix. Closing the page does not stop execution.

### 3. Continue a suspended turn

Send /ask. The demo calls the external ask_user tool, enters suspended state and displays an answer form. Refresh or restart the application before answering: the request is reconstructed from the log.

Submit an answer with 提交并继续 (submit and continue). turnId stays unchanged and runId changes. The log records interaction/resolved, turn/resumed and a new run/start before completing the original turn.

### 4. Interrupt and continue from a checkpoint

Send /slow, click 中断执行 (pause execution), and wait for 已中断 (paused). Restart if desired, then click 继续原任务 (continue from checkpoint). The application restores committed state, preserves turnId and creates a new runId. Execution continues from working state; the UI retains earlier committed message fragments and tool records.

After a forced process exit, allow approximately two minutes for the old writer lease to expire. Unknown tool outcomes require explicit reconciliation rather than assumed success; see the session-log reference for that workflow.

### 5. Queue work, steer and inject context

The input selector distinguishes three operations. **新任务（忙时排队）** submits another task with a new turnId; **补充当前任务** guides the next reasoning step without changing the active turn or run; **只补充材料** persists context without starting execution. Start `/slow`, submit another task, then add guidance or reference material to observe the queue and message history.

Steering requires an active task. Interruption or pending user input parks queued work until the original task continues. Accepted commands survive application restart.

### 6. Inspect execution

Click a timeline row to inspect its native SessionEvent, including turnId, executionRunId and payloadJson. Observe model requests/chunks, messages, interactions and checkpoints.

The page also shows historical and working-message counts. Working context can contain system messages or compacted history, so these counts need not match.

## Use a real model

Set these variables and restart:

```bash
export DASHSCOPE_API_KEY=your_api_key
export CHAT_MODEL=qwen-plus
java -jar agentscope-examples/agents/agentscope-chat/target/agentscope-chat.jar
```

Chat naturally with the real model; /slow and /ask are offline demo commands. Ask the model to collect a preference through ask_user to exercise suspension. Set CHAT_MODEL=demo to switch back. Model selection does not change storage; start a new conversation if you want separate history.

## API and reading the code

| Operation | Example endpoint |
| --- | --- |
| List conversations | GET /api/sessions |
| Snapshot, messages, pending requests and state | GET /api/sessions/{session} |
| Submit a turn | POST /api/sessions/{session}/turns with `{request_id,message}` |
| Guide the active task | POST /api/sessions/{session}/steer with `{message}` |
| Inject reference material | POST /api/sessions/{session}/inject with `{message}` |
| Observe/replay SSE | GET /api/sessions/{session}/stream?after={cursor}, supporting Last-Event-ID |
| Read native records | GET /api/sessions/{session}/events?after={cursor}&limit=100 |
| Submit external output and continue | POST /api/sessions/{session}/answers with `{request_id,output}` |
| Cooperative interruption | POST /api/sessions/{session}/interrupt with `{run_id}` |
| Checkpoint continuation | POST /api/sessions/{session}/turns/{turn}/resume |

The example uses agent.session(context): submit accepts and schedules tasks, steer guides the next step of the active task, inject saves context without starting work, and respond/resume resolve the original turn. The framework owns background execution; SSE only reads committed history. Retry submission with the same request_id and input; the framework assigns turnId.

Read these files in order:

1. ChatApplication.java: model and Workspace configuration.
2. ChatSessions.java: HarnessAgent construction, execution, external results and continuation.
3. ChatHistory.java: messages, pending actions, state and cursor from one committed prefix.
4. ChatItems.java: model chunks, messages, tool arguments, progress and results as stable UI items.
5. ChatController.java: HTTP and read-only SSE with resumable event IDs.
6. static/chat.js: snapshot replacement, event notifications, stable session order and reconnection.

## Scope

This is a local single-process, single-user SDK example. POST acknowledges durable acceptance, which may still be queued. The inbox shares the session backend; application startup calls session.start() to dispatch accepted work without automatically resuming interrupted tasks. The example exposes raw events for local inspection. Hosted applications should use Service Agent API for authentication, public event projection and managed operation.

## Verify

```bash
mvn -pl agentscope-examples/agents/agentscope-chat -am test \
  -Dtest=ChatSessionsTest,ChatItemsTest -Dsurefire.failIfNoSpecifiedTests=false
```

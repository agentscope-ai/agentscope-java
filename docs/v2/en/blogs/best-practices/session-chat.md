---
title: Recoverable chat example
description: Run agentscope-chat to explore history, SSE replay, user input and checkpoint continuation.
zh_link: /v2/zh/blogs/best-practices/session-chat
---

Historical source: [agentscope-chat at v2.1.0-BETA1](https://github.com/agentscope-ai/agentscope-java/tree/v2.1.0-BETA1/agentscope-examples/agents/agentscope-chat). This shared post describes a later, unreleased DurableRuntime implementation; the tagged example uses AgentSession instead. Neither implementation is included in v2.0.4.

A local chat application built with HarnessAgent and agentscope-durable. It demonstrates persistent tasks, queuing, pause/resume, interaction answers, and SSE replay of public messages and tool progress.

Ordinary single-request applications can still use Agent `call` / `streamEvents`. This example must continue after browser disconnects or application restarts, so `DurableRuntime` assembles the local backend and AgentScope adapter while business requests use `runtime`. See [Durable tasks](/dev/en/docs/harness/durable) for the concepts and general APIs.

## Run

Requires JDK 17+ and Maven. The default offline model needs no API key, database server or Node.js. LocalBackend creates SQLite and object storage locally.

From the repository root:

```bash
mvn -B -T1 -Dagentscope.frameworkOnly=true -pl agentscope-examples/agents/agentscope-chat -am package -DskipTests
java -jar agentscope-examples/agents/agentscope-chat/target/agentscope-chat.jar
```

Open [http://127.0.0.1:8087](http://127.0.0.1:8087). The example binds only to localhost. The default data directory is `~/.agentscope/session-chat`: `durable/durable.db` holds state/events and `durable/objects/` holds objects. Preserve the entire directory across restarts. The demo UI uses Chinese labels.

```bash
CHAT_WORKSPACE=/absolute/path/to/chat-workspace CHAT_PORT=8088 \
  java -jar agentscope-examples/agents/agentscope-chat/target/agentscope-chat.jar
```

## Try the workflows

### Chat and history

Creating a conversation explicitly creates a Session and its primary Thread. Send a preference, wait for completion, refresh or restart, then select the same Session and continue. The browser retains only selection and unacknowledged request IDs; messages, tasks and results come from public snapshots. Querying an absent Session returns 404 without creating it.

### Multiple steps and reconnecting

Send `/slow`. The offline model produces three rounds of text, invokes two `demo_lookup` tools in parallel, and then invokes `demo_verify`. These are local read-only tools executed through Toolkit; each emits four progress chunks.

Refresh, switch Sessions, or disconnect while text, arguments or tools are running. Return and inspect every message and tool card. Snapshot `projection.messages` holds logical messages and versions; `projection.tools` holds arguments, states, progress and results. The page renders these public projections without parsing raw model chunks or private checkpoints.

The snapshot cursor corresponds to the same committed prefix as its projection. SSE starts after that cursor; EventSource's Last-Event-ID takes precedence on reconnect. Cursors are opaque strings: clients neither construct nor decode them. Sequence is only for ordering and deduplication. Expired cursors trigger a fresh snapshot. Disconnecting stops observation while tasks continue.

### Answer an interaction and continue the task

Send `/ask`. The Agent requests an answer through the schema-only `ask_user` tool and the Turn becomes WAITING. Its form survives refreshes and restarts. An answer targets explicit turnId and interactionId, continuing the same Turn through a new Run rather than creating another task.

WAITING is governed by its waiting condition; resume cannot bypass an unanswered interaction. If the task is paused first, answers can still be recorded, but execution waits for an explicit resume.

### Pause, resume and cancel

Send `/slow`, click “暂停任务”, wait for PAUSING to become PAUSED, and restart. The task stays paused and later Turns remain queued. “继续原任务” submits the observed revision, retaining the Turn and creating a new Run/Step from committed state.

Pause takes effect at a safe state-saving boundary rather than immediately killing an executing tool. Cancel is a separate terminal operation: CANCELLING reconciles issued operations before CANCELLED. An unknown side effect cannot be resolved by executing it again to guess the outcome.

Runtime startup discovers eligible tasks without a browser connection. Takeover after an abrupt exit waits for the prior lease to expire; persistent pauses always require explicit resume.

### New tasks, steering and additional material

“新任务” creates another Turn queued in the same Thread. “补充当前任务” and “向当前任务补充材料” target an explicit unfinished Turn with independent requestIds, applied at safe boundaries. Without an unfinished task these controls are unavailable; submit a new task to start another turn.

A paused or waiting queue head prevents later tasks from overtaking. Controls target the first unfinished Turn, even when a newly queued task is the most recent entry. Retrying an unacknowledged operation preserves its requestId, target and content.

### Public events and execution records

The timeline shows the latest 80 public events received since connecting. Click one to inspect sessionId, threadId, turnId, runId, stepId, sequence and data; use paginated events for full history. A separate endpoint queries Runs. Turn is the task, Run is one invocation, and each Step belongs to one Run. Invocation completion does not necessarily complete a task.

## Use a real model

```bash
export DASHSCOPE_API_KEY=your_api_key
export CHAT_MODEL=qwen-plus
java -jar agentscope-examples/agents/agentscope-chat/target/agentscope-chat.jar
```

Use natural language with real models; `/slow` and `/ask` are offline commands. Ask the model to use ask_user for a preference. Set `CHAT_MODEL=demo` to switch back. The example includes the model name in its definition version: create a new Session when changing models; executing an old Session still requires its original definition version.

## Application HTTP routes

These example Web routes call the general Durable Client without another task state machine.

| Operation | Route and request |
| --- | --- |
| Create Session and primary Thread | POST /api/sessions/{session} |
| List / snapshot | GET /api/sessions; GET /api/sessions/{session} |
| Submit task | POST /api/sessions/{session}/turns, `{requestId,message}` |
| Steer / inject | POST /api/sessions/{session}/turns/{turn}/steer or inject, `{requestId,message}` |
| Pause / cancel | POST /api/sessions/{session}/turns/{turn}/pause or cancel, `{requestId}` |
| Resume paused task | POST /api/sessions/{session}/turns/{turn}/resume, `{requestId,expectedRevision}` |
| Answer interaction | POST /api/sessions/{session}/turns/{turn}/answers, `{requestId,interactionId,output}` |
| Events / SSE | GET /api/sessions/{session}/events or stream, `after={cursor}`; events supports limit |
| Invocation history | GET /api/sessions/{session}/turns/{turn}/runs, with after and limit |

Read ChatSessions.java for Runtime/factory assembly, ChatController.java for thin HTTP/SSE handling, static/chat-view.mjs for public projection rendering, and static/chat.js for controls and reconnection. Every actual invocation returns a fresh HarnessAgent builder; the adapter installs the native journal and builds the Agent, so business APIs never access that journal.

## Scope and verification

This is a local, single-user example. Production applications can use the same Client and protocol with other Backends and independent Brain/Hands processes, adding business authorization and resource policies. Durable does not depend on Managed Agents. This example uses local tools; Durable Java examples demonstrate queued Workers.

```bash
mvn -B -T1 -Dagentscope.frameworkOnly=true -pl agentscope-examples/agents/agentscope-chat -am test \
  -Dtest=ChatSessionsTest,ChatControllerTest -Dsurefire.failIfNoSpecifiedTests=false
node --test agentscope-examples/agents/agentscope-chat/src/test/js/*.test.mjs
```

Java tests use temporary SQLite directories and deterministic models for queuing, input deduplication, persistent pause, answers after restart, the same Turn with new Runs, tool progress, snapshot/replay consistency, HTTP controls and SSE disconnect. Frontend tests check public projections, queue-head control targets and large-sequence sorting without real models or external services.

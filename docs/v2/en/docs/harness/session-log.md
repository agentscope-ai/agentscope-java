---
title: Session operations, events and recovery
description: Submit, steer, inject, answer and resume with AgentSession, then build history and reconnectable views.
zh_link: /v2/zh/docs/harness/session-log
---

If you followed the [Quick Start](/v2/en/docs/quickstart), keep using `agent.call` for replies or `agent.streamEvents` for live output in ordinary multi-turn chat. HarnessAgent also saves history and checkpoints for those calls by default. Persistence alone does not require `AgentSession`.

Introduce `AgentSession` when work should survive a page closing, queue while busy, accept guidance during execution, or continue the same task after interruption. It accepts tasks and owns background execution; the frontend independently reads messages, progress and pending actions. This guide walks through those scenarios. See the [recoverable chat example](/v2/en/docs/harness/session-chat) for a complete application.

If AgentScope Service hosts your Agent, use the HTTP/SSE interfaces in [Service Agent API](/v2/en/service/session-event-log).

## Concepts and basic APIs

Obtain `AgentSession` from your configured `HarnessAgent`, using the same conversation identity as direct calls:

```java
import io.agentscope.core.agent.RuntimeContext;

var ctx = RuntimeContext.builder()
        .userId("alice").sessionId("conversation-001").build();
var session = agent.session(ctx);
```

Obtaining the handle or reading history never starts inference. You can read the history of earlier `agent.call(input, ctx)` invocations through `session.transcript()`. Use `agent.sessionLog(ctx)` when you only need raw records.

Choose one execution entry for each conversation: application-owned `call` / `streamEvents`, or framework scheduling through operations such as `session.submit`. Both can read logs. Observe a submitted task through its log; calling `streamEvents` would start another execution.

| API | Purpose |
| --- | --- |
| `session.submit(input)` | Submit a new task, queue it if busy, and allocate its turnId |
| `session.submit(requestKey, input)` | Use the same key and input for network retries |
| `session.steer(input)` | Guide the running task at its next reasoning step; reject when no task is running |
| `session.inject(context)` | Persist context for a later reasoning step without starting execution |
| `session.respond(requestId, answer)` | Answer an interaction, resolving its original turn and tool identity |
| `session.interrupt()` / `session.resume(turnId)` | Interrupt execution / continue the original task from saved state |
| `session.tasks()` / `session.await(task)` | Query tasks / observe completion, suspension, interruption or failure |
| `session.transcript()` / `session.log()` | Read committed messages / native events |
| `session.pending()` / `session.inspect()` | Read pending interactions / recovery state |

Acceptance is not execution. `submit` returns a receipt; queued tasks may have no runId yet. `await(task)` only observes, without starting, retrying or cancelling work. A suspended outcome still needs an answer.

### History, events and checkpoints

Session Log records execution from both direct calls and session scheduling. History describes what happened; checkpoints preserve working state for subsequent execution. Recovery does not recreate threads, network requests or a tool's internal progress. Existing interruption and HITL mechanisms control execution; Session Log persists their state.

AgentEvent carries live notifications; SessionEvent carries persistent history. Native seq increases within a session, and eventId supports deduplication. Service public SSE has its own cursor; do not interchange them. A live notification is not a commit acknowledgment.

## Scenario 1: Chat, queueing and restart recovery

Consider a chat application that processes material in the background. It returns a task receipt immediately, lets the user leave the page, and accepts further tasks into a queue. Assume model is configured as described in [Models](/v2/en/docs/building-blocks/model):

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

var task = session.submit("request-001", "Describe this material");
System.out.println(task.turnId());
var outcome = session.await(task).block(); // CLI usage; web applications return the receipt.
System.out.println(outcome.status());
```

`await` returns task status. Read the reply through `session.transcript()` or the event view in the next section. Disconnecting an observer does not cancel background work; use `session.interrupt()` to stop it.

Another submit creates a new task. New tasks queue in acceptance order while busy. Interruption or HITL preserves the queue: continue the unfinished task or answer its interaction first. Repeating the same key and input returns the existing receipt; a key cannot carry different input.

After rebuilding the Agent with the same configuration, obtain the same session and read history:

```java
var history = session.transcript();
history.messages().forEach(message -> System.out.println(message.getTextContent()));
session.start(); // Explicitly dispatch accepted work after startup; does not resume interrupted tasks.
```

Submit, resume and respond start dispatch automatically. History readers do not need start(). Close the owning agent on application shutdown; it interrupts active work and preserves the durable queue.

## Scenario 2: Guide execution and reconnect the frontend

While the task is running, send guidance from the request handling the user’s new input. Call `steer` during execution, before `await` completes.

```java
session.steer(task, "Focus on operating cost"); // Reject if the session has moved to another task.
session.inject("Additional material: the team has two operators.");
```

Steering applies at the next reasoning step, without changing an already-sent model request or running tool arguments. Injection only supplies context: while idle it waits for future work, and if execution has no further step it stays pending. Unconsumed steering remains bound to its original turn across interruption.

Frontend readers:

1. Load a snapshot of messages, tools and pending actions, with its watermark.
2. Read committed events after that watermark and update items by stable IDs.
3. Reconnect from the last applied cursor; if a refresh lost view state, load a new snapshot first.

```java
long after = 0; // Last successfully applied seq.
for (var event : session.log().readAfter(after, 100)) {
    System.out.printf("%d %s%n", event.seq(), event.type());
}
```

Never resubmit work on reconnect. A cursor alone cannot restore the content before it. The [chat example](/v2/en/docs/harness/session-chat) rebuilds complete messages, partial text and tools from committed chunks and exposes snapshots and SSE. Service applications use its public event protocol.

SDK storage operations block. Schedule them on `Schedulers.boundedElastic()` in WebFlux applications.

## Scenario 3: Answer HITL requests

When a submitted task suspends for user input, display its persisted pending request and return the answer with `respond`. Direct calls can also use the [existing HITL APIs](/v2/en/docs/building-blocks/agent#human-in-the-loop); human confirmation alone does not require switching execution entries.

```java
session.pending().forEach((requestId, request) ->
        System.out.println(requestId + " " + request.data().get("kind")));

session.respond("external-request-id", "The user prefers concise output");
session.respond("confirmation-request-id", true);
```

Strings answer external_execution requests; booleans answer confirmation requests. The framework resolves the original turn and tool from durable pending state and constructs existing HITL inputs. Unknown, resolved and mismatched requests are rejected.

Use SessionAnswer.reject(reason) for a denial, SessionAnswer.Output for custom result blocks, or SessionAnswer.Confirmation for permission rules or edited tool arguments. Batch parallel answers with `respond(Map<String, SessionAnswer>)`; all must belong to one turn. Reply to inline Service interactions through their owning service's actions endpoint.

## Scenario 4: Continue after interruption

```java
session.interrupt();
// Wait for tasks() to show interrupted. You can then restart and obtain the same session.
var continued = session.resume(task.turnId());
```

Resume validates task state, restores checkpoints and unapplied original input, and starts a new execution. Applications do not construct empty inputs, replay messages or set turn metadata. Completed tasks cannot resume; outstanding interactions require respond.

```java
var inspection = session.inspect();
System.out.println(inspection.uncertainToolCalls());
```

After a forced process exit, wait for the previous writer lease to release or expire. For uncertain tool outcomes, verify external state and supply real results using the [recovery reference](/v2/en/docs/harness/session-log-reference#reconciling-uncertain-tool-outcomes) before continuing. Recovery also needs the same tools, credentials and working files; it is not arbitrary history rollback.

## Sessions, turns and runs

A session contains logical requests (turns). One turn may need several executions (runs). A run can make many model and tool calls and produce multiple messages.

| Identity | Meaning | Changes when |
| --- | --- | --- |
| sessionId | One continuing conversation | Starting another conversation |
| turnId | One logical task | Created by submit; steering, answers and continuation preserve it |
| runId | One execution | Starting another execution |

For example, in session `S1`, request "Prepare a report and ask before publishing":

| Action or state | turnId | runId |
| --- | --- | --- |
| Submit, reason and use tools | `T1` | `R1` |
| Refresh the page and restore generated content | `T1` | Still `R1` |
| Steer: focus on cost | `T1` | Still `R1` |
| Interrupt | `T1` | `R1` ends |
| Continue the original task | `T1` | New `R2` |
| Suspend for approval, then answer | `T1` | `R2` ends; new `R3` |
| Submit another task after publication | New `T2` | New `R4` |

The sessionId stays `S1`. This example uses suspended HITL; inline confirmation can continue the same live run. Applications select an operation rather than manually setting turn metadata.

Storage identity also includes userId and a stable agentId. Preserve the identities and backend across restarts. Model calls, tool calls, messages and interaction requests have their own IDs for correlating chunks, results and answers.

## Scenario 5: Inspect execution, export records or change storage

Group events by turnId to inspect a request across multiple runs, then examine model/tool/state facts within each run:

```java
var log = session.log();
long through = log.head().seq();
for (var event : log.scan(0, through)) {
    System.out.printf("%d %s turn=%s run=%s%n",
            event.seq(), event.type(), event.turnId(), event.executionRunId());
}
```

scan(0, through) reads a fixed committed prefix. For continuous export to a database, audit system or public UI, use SessionExportSink and SessionLogExporter, with idempotent eventId handling at the destination. See [API and storage reference](/v2/en/docs/harness/session-log-reference) for event types, export examples and extensions.

Default storage follows the Workspace Filesystem:

| Configuration | Location |
| --- | --- |
| Local Workspace | .agentscope-runtime/ under the Filesystem root resolved for the current identity |
| Distributed Workspace | __agentscope_session_log_v1__ partition in the corresponding BaseStore namespace |
| Custom log backend | Location configured through HarnessAgent.builder().sessionLogStore(store) |
| Service-managed Agent | Shared native backend plus public events in the Data Plane database; see [Service](/v2/en/service/session-event-log) |

Workspace supports distributed storage. Use a Filesystem with atomic versioned writes, or configure a separate backend for logs and working files. Replicas must share the log backend, identities and namespace. See [storage configuration](/v2/en/docs/harness/session-log-reference#storage-and-backend-configuration).

## Complete example: recoverable Web Chat

The [agentscope-chat example](/v2/en/docs/harness/session-chat) brings these scenarios together:

- Incremental messages, tool arguments and progress, with visible turn/run identities.
- Committed event timeline and on-demand payload inspection.
- History restoration after page refreshes and event-stream disconnects.
- Conversation continuation after application restart.
- Queued tasks, steering, context injection, pending input and continuation after interruption.

It runs offline by default and can use a real model. Follow its walkthrough to observe turnId, runId and watermarks before and after recovery.

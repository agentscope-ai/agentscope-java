---
title: Session operations, events and recovery
description: Build background conversations, restore the UI, handle user input and continue interrupted tasks with AgentSession.
zh_link: /v2/zh/docs/harness/session-log
---

If you followed the [Quick Start](/v2/en/docs/quickstart), keep using `agent.call` for replies or `agent.streamEvents` for live output in ordinary multi-turn chat. HarnessAgent saves history and checkpoints for those calls by default. Persistence alone does not require `AgentSession`.

Use `AgentSession` when your application accepts a task now and lets it run in the background: the user can close the page, submit more work, add guidance, answer a question or continue after interruption. The application submits commands; the frontend independently reads committed progress. This guide follows a cost-comparison assistant through those interactions.

If AgentScope Service hosts your Agent, use its [Service Agent API](/v2/en/service/session-event-log). For a complete SDK application, see the [recoverable chat example](/v2/en/blogs/best-practices/session-chat).

<span id="concepts-and-basic-apis" />

<span id="scenario-1-chat-queueing-and-restart-recovery" />

## Start a background conversation

Configure a shared Builder, then let your application's session manager build and retain the runtime that owns the conversation. The following assumes `model` is configured as described in [Models](/v2/en/docs/building-blocks/model):

```java
import io.agentscope.core.agent.RuntimeContext;
import io.agentscope.harness.agent.HarnessAgent;
import java.nio.file.Path;

HarnessAgent.Builder chatBuilder = HarnessAgent.builder()
        .name("Cost Assistant")
        .agentId("cost-assistant")
        .model(model)
        .workspace(Path.of("/data/chat-workspace"));

// The application session manager retains this runtime.
HarnessAgent agent = chatBuilder.build();
var ctx = RuntimeContext.builder()
        .userId("alice").sessionId("conversation-001").build();
var session = agent.session(ctx);

var task = session.submit("request-001", "Compare the operating costs of plans A and B");
System.out.println(task.turnId());
```

Return the task receipt from the HTTP handler. `submit` durably accepts the input and allocates a `turnId`; execution can still be queued, so its `runId` may be absent. Retry a failed submission request with the same request key and identical input to obtain the existing receipt. A different task needs a different key.

Keep the Agent alive after that response: closing it stops scheduling and interrupts active work. Close it when its owning session manager shuts down or deliberately releases the runtime. This differs from ordinary direct requests, whose caller builds and closes the Agent per request; see [Instance lifecycle](/v2/en/docs/building-blocks/agent#instance-lifecycle).

Further submissions queue in acceptance order while the session is busy. Interruption or pending human input parks the queue until the unfinished task continues. To observe a task from a CLI or worker:

```java
var outcome = session.await(task).block();
System.out.println(outcome.status());
session.transcript().messages().forEach(msg -> System.out.println(msg.getTextContent()));
```

`await` observes the submitted command's execution until completion, suspension, interruption or failure. It does not start, retry or cancel execution; a suspended task still needs an answer. `respond` and `resume` return new receipts to observe with `await`. Use `session.tasks()` to query task state. Disconnecting an observer does not cancel background work.

Choose one execution entry for a conversation: application-owned `call` / `streamEvents`, or `AgentSession` scheduling. Reading a submitted task's log observes it; calling `streamEvents` would start another execution. Obtaining `agent.session(ctx)` or reading history never starts inference, and the transcript includes earlier direct calls made with the same identity.

<span id="scenario-2-guide-execution-and-reconnect-the-frontend" />

## Add guidance or material while work is running

Suppose the assistant is comparing two plans and the user adds, "We only have two operators; prioritize staffing cost." This refines the current task instead of asking for another report. Send guidance while that task is running:

```java
session.steer(task, "Prioritize staffing cost; we only have two operators.");
session.inject("Additional material: plan A requires weekly maintenance; plan B is managed.");
```

`steer(task, ...)` binds the guidance to the expected task and rejects it if that task is no longer running. `steer(input)` instead targets whichever task is currently running. Guidance becomes available at the next reasoning step; it does not rewrite an already-sent model request or change a tool call's running arguments. Unconsumed guidance remains bound to the original turn across interruption.

`inject` persists reference material without starting execution. While idle, it waits for future work; if the running task has no further reasoning step, the material remains pending. Use `submit` when the user wants a separate comparison queued after the current one.

## Restore the UI after refresh or reconnection

Now imagine the assistant has finished looking up plan A, called a tool for plan B, and is streaming its comparison. Refreshing the page should restore the earlier messages, both tool calls, committed results and the current text prefix before new output appears. Reconnecting an event stream from a cursor alone cannot restore the content that preceded that cursor.

Build the frontend around a snapshot and a stream of subsequent committed events:

1. Read one committed prefix and build a snapshot containing complete messages, partial text, tool names and arguments, progress, results and pending interactions. Return a cursor covering exactly that prefix.
2. Render that snapshot, then observe events after its cursor. Update existing items by stable message/tool IDs, or refresh the snapshot when new commits arrive.
3. On a stream reconnect, continue after the last successfully applied cursor. If a page refresh lost the view, fetch a new snapshot before subscribing again.

The SDK provides the records from which your application builds that view:

```java
var log = session.log();
long watermark = log.head().seq();
var committed = log.scan(0, watermark);
// Build the application's snapshot from committed, and return watermark with it.

for (var event : log.readAfter(watermark, 100)) {
    System.out.printf("%d %s%n", event.seq(), event.type());
    // Apply or publish this committed event before advancing the consumer cursor.
}
```

`session.transcript()` returns complete committed messages and their `asOfSeq`; it does not reconstruct unfinished text or tool argument fragments. Those require projecting `model/chunk` and `tool/chunk` along with messages and results. Do not assemble a snapshot from separate reads at different watermarks and then attach the newest cursor: that can skip content.

The [chat example](/v2/en/blogs/best-practices/session-chat) implements this application layer in `ChatHistory` and `ChatItems`. Its snapshot includes `items`, `messages`, `pending` and a consistent `cursor`. Its SSE endpoint sends filtered `committed` notifications, and the browser throttles snapshot refreshes in response; it does not apply raw model chunks directly. These snapshots and HTTP/SSE endpoints belong to the example, not a unified SDK snapshot API.

Restoring a page only reads committed history. Do not resubmit the task, replay old input or call the Agent to reconstruct the UI. SDK storage operations block; WebFlux applications should schedule them on `Schedulers.boundedElastic()`.

<span id="scenario-3-answer-hitl-requests"></span>

## Answer HITL requests

If the cost comparison needs a missing quote or approval to purchase, the Agent can suspend and persist an interaction request. Display that request from `session.pending()` and submit the user's answer using its request ID:

```java
session.pending().forEach((requestId, request) ->
        System.out.println(requestId + " " + request.data().get("kind")));

session.respond("external-request-id", "The annual quote is USD 12,000.");
session.respond("confirmation-request-id", true);
```

These illustrate two request kinds: strings answer `external_execution` requests; booleans answer confirmation requests. Use the actual persisted IDs for your interaction. The framework resolves the original turn and tool identity, constructs the existing HITL input and continues that turn. Unknown, resolved or mismatched requests are rejected.

For a confirmation denial, use `SessionAnswer.reject(reason)`. `SessionAnswer.Output` accepts custom result blocks, and `SessionAnswer.Confirmation` supports permission rules or edited tool arguments. Parallel answers can be submitted with `respond(Map<String, SessionAnswer>)`; they must belong to one turn.

Direct calls can also use the [existing HITL APIs](/v2/en/docs/building-blocks/agent#human-in-the-loop); human confirmation alone does not require session scheduling. Reply to inline Service interactions through their owning service's actions endpoint.

<span id="scenario-4-continue-after-interruption"></span>

## Continue after interruption

To pause the comparison, request interruption and wait for the running execution to stop. `interrupt()` is a cooperative request, not a completion acknowledgment. A CLI or worker can wait and then continue the original task:

```java
session.interrupt();
var stopped = session.await(task).block();
if ("interrupted".equals(stopped.status())) {
    var continued = session.resume(stopped.turnId());
}
```

In a web application, return after requesting interruption and let the UI observe task state before offering continuation. If the page knows the active run ID, use `session.interrupt(runId)` to avoid stopping a newer execution from a stale page. `resume` restores the checkpoint and unapplied original input, preserves the `turnId` and starts a new run. Applications do not construct empty input, replay messages or set turn metadata. Only the latest unfinished task can continue; completed or cancelled tasks cannot resume, and pending interactions require `respond` first.

After an application restart, build the same Agent configuration and obtain the session with the same `userId`, stable `agentId`, `sessionId` and storage namespace. Read its history immediately, then explicitly start dispatching accepted work:

```java
session.start(); // Dispatch queued work; interrupted tasks still need resume(turnId).
```

`submit`, `respond` and `resume` start dispatch automatically. Read-only history pages do not need `start()`. Durable recovery needs the same tools, credentials and working files as the original task.

A checkpoint restores Agent working state. It does not recreate an operating-system thread, resume a network request or restore a tool's internal progress. After a forced process exit, the previous writer lease must release or expire before another runtime can write. Inspect the saved state before continuing if a tool may have changed external data.

### Reconciling uncertain tool outcomes

Suppose `create_order` created an order, but the process stopped before committing the tool result. `session.inspect().uncertainToolCalls()` identifies calls whose outcomes need verification. Check the order system, then record the result you actually found:

```java
import io.agentscope.core.message.TextBlock;
import io.agentscope.core.message.ToolResultBlock;
import io.agentscope.core.message.ToolResultState;
import java.util.Map;

System.out.println(session.inspect().uncertainToolCalls());
var result = ToolResultBlock.builder()
        .id("call-id-from-inspection").name("create_order")
        .output(TextBlock.builder().text("Order ORD-42 confirmed created").build())
        .state(ToolResultState.SUCCESS).build();
agent.reconcileToolOutcomes(ctx, Map.of(result.getId(), result), "Verified order-system records");
var continued = session.resume(task.turnId());
```

Supply results covering exactly the uncertain call IDs. Reconciliation saves the verified results and checkpoint without executing those tools again; continuation is explicit. If the outcome is still unknown, investigate it rather than inventing an empty successful result. Recovery is not arbitrary history rollback.

## Sessions, turns and runs

A session is the continuing conversation. A turn is one logical request, and a run is one execution of that request. One run can contain many model calls, tool calls and messages. In conversation `S1`, the workflow might be:

| Action | turnId | runId |
| --- | --- | --- |
| Submit the cost comparison | New `T1` | New `R1` once dispatched |
| Refresh the page or steer toward staffing cost | `T1` | Still `R1` |
| Interrupt execution | `T1` | `R1` ends |
| Continue the comparison | `T1` | New `R2` |
| Suspend for approval, then answer | `T1` | `R2` ends; new `R3` |
| Submit a separate report | New `T2` | New `R4` once dispatched |

The `sessionId` remains `S1`. Submission idempotency keys, interaction request IDs and event IDs serve different purposes: retrying a submission, answering a pending request and deduplicating a record. They are not interchangeable with turn IDs. This example uses suspended HITL; inline confirmation can continue within the same live run. Choose the operation that matches the user's intent and let the framework assign identities. Model calls, tool calls, messages and interaction requests also have their own IDs for correlating fragments, results and answers.

<span id="history-events-and-checkpoints" />

<span id="scenario-5-inspect-execution-export-records-or-change-storage" />

<span id="event-envelope-and-reads" />

## Inspect execution and read events

Use `session.log()` or `agent.sessionLog(ctx)` to inspect a request across its runs. Group by `turnId`, then examine the model, tool and state events within each `executionRunId`:

```java
var log = session.log();
long through = log.head().seq();
for (var event : log.scan(0, through)) {
    System.out.printf("%d %s turn=%s run=%s%n",
            event.seq(), event.type(), event.turnId(), event.executionRunId());
}
```

`scan(0, through)` reads a fixed committed prefix; `readAfter(seq, limit)` reads a batch after a cursor. Neither executes models or tools. History records what happened, while checkpoints preserve working state for later execution; compacting working context does not erase earlier history.

`AgentEvent` carries live notifications; `SessionEvent` carries durable facts. A live notification is not a commit acknowledgment. Native `seq` starts at 1 and increases within a session; use it for ordering and `eventId` for deduplication. Service public SSE has its own cursor, which must not be interchanged with native sequence numbers.

### Event types

The catalog below helps locate the facts needed for a timeline, diagnosis or application projection:

| Types | What they describe |
| --- | --- |
| `run/start`, `run/end`, `run/stop_requested` | Execution boundaries, outcomes and interruption requests |
| `turn/start`, `turn/resumed`, `turn/output` | Initial invocation, continuation and final output |
| `turn/completed`, `turn/suspended`, `turn/failed`, `turn/interrupted`, `turn/cancelled` | Logical outcomes and paused state |
| `step/start`, `step/end` | Reasoning steps |
| `input/received`, `input/applied`, `input/discarded` | Input admission and consumption |
| `message/system`, `message/user`, `message/assistant` | Message history |
| `request/prepared`, `model/dispatch`, `model/chunk`, `model/end`, `model/retry` | Adapter requests, output fragments, usage and retries |
| `tool/requested`, `tool/decision`, `tool/dispatch`, `tool/chunk`, `tool/result` | Tool arguments, decisions, progress and results |
| `action/start`, `action/end` | Individual tool-action boundaries |
| `interaction/requested`, `interaction/resolved` | Pending interactions and applied answers |
| `context/build`, `context/replaced`, `compaction/start`, `compaction/end` | Working-context changes |
| `task/changed`, `plan/changed`, `permission/changed`, `verification/result` | Task, plan, permission and verification state |
| `subagent/spawned`, `subagent/completed`, `subagent/linked` | Child links; full child execution lives in its own log |
| `state/checkpoint`, `state/restored`, `recovery/applied`, `migration/baseline` | Saved state, restoration, reconciliation and imported state |
| `inbox/started`, `inbox/applied` | Accepted commands linked to execution and checkpointed input |
| `presentation/hint`, application types | Presentation hints and custom facts |

Events cover adapter-visible requests and output, not provider internals or backups of referenced files. Keep raw requests, tool results and checkpoints in an authorized diagnostic interface; filter what you expose to a browser.

The `compaction/end` payload includes `compactionId`, `status`, `beforeMsgCount`, and
`beforeTokenCount`. When `status` is `completed`, it also includes `afterMsgCount` and
`afterTokenCount`, calculated from the effective post-compaction conversation. Failed or cancelled
compactions include only the before metrics because no post-compaction state is available.

<Accordion title="SessionEvent envelope">

Events are immutable, with payloads frozen as JSON when accepted.

| Field | Meaning |
| --- | --- |
| `schemaVersion` | Envelope version |
| `eventId`, `seq` | Deduplication identity and durable order within the session |
| `occurredAt` | Timestamp; use `seq` for ordering |
| `type` | Event type |
| `turnId`, `executionRunId` | Logical task and execution; administrative events may have no turn |
| `required` | Whether recovery must recognize this type |
| `payloadJson`, `data()` | Frozen JSON and its parsed map |

</Accordion>

## Storage and backend configuration

Harness uses `WorkspaceSessionLogStore` by default, so storage follows the Workspace Filesystem. With `LocalFilesystem`, records live in `.agentscope-runtime/` under the root resolved for the current identity. With `RemoteFilesystem`, they occupy the `__agentscope_session_log_v1__` partition in the corresponding `BaseStore` namespace.

On Linux and macOS, local storage commits forced temporary files through atomic replacement and syncs their parent directories. On Windows, the Java filesystem provider cannot open directories for this sync, so local Session storage uses SQLite transactions in `.agentscope-runtime/journal.sqlite3`, with a rollback journal and `synchronous=EXTRA`. Writers still perform an atomic version comparison, and storage errors propagate to the caller. Read records through the SDK; when backing up a Windows workspace, pause all writers before copying the database, or use a consistent SQLite backup. The POSIX file layout and the Windows database hold the same logical objects but are different physical formats, so moving history between them requires a log migration or a shared backend.

`SessionKey(userId, agentId, sessionId)` identifies a conversation inside that backend. Preserve both the key and namespace across restarts. Within one Agent, the same identity reuses an `AgentSession` with a copy of its initial `RuntimeContext`; establish stable session-level configuration when first obtaining the session.

<span id="shared-storage" />

Working files and session records can use separate backends. For example, keep files local while storing logs and accepted commands in an application-configured `sharedStore` with atomic versioned writes:

```java
import io.agentscope.harness.agent.filesystem.remote.RemoteFilesystem;
import io.agentscope.harness.agent.session.WorkspaceSessionLogStore;
import java.util.List;

var logStore = new WorkspaceSessionLogStore(
        new RemoteFilesystem(sharedStore, List.of("my-app", "session-history")));
HarnessAgent.Builder sharedLogBuilder = HarnessAgent.builder()
        .name("Cost Assistant").agentId("cost-assistant")
        .model(model).workspace(Path.of("/data/chat-workspace"))
        .sessionLogStore(logStore);
```

Replicas must share this backend, namespace and session identity. Shared storage does not automatically queue concurrent direct calls to the same conversation; the application must coordinate those. AgentSession receives and queues tasks through its durable inbox, with execution owned by the started session scheduler. In-memory storage is process-local and cannot survive restart. Discovery through `SessionLogStore.list(ctx)` follows the current namespace: a session-isolated namespace does not enumerate other sessions. See [Workspace](/v2/en/docs/harness/workspace) for filesystem and namespace configuration.

<span id="custom-backends-and-backups" />

For a custom backend, implement `SessionLogStore`, or implement `AtomicSessionStorage` and reuse `JournalSessionLog`. Preserve compare-and-set writes, writer leases, stale-writer fencing, ordered idempotent commits and durable export cursors. A custom Filesystem must supply `sessionStorage` or use a separate log store; ordinary file reads and writes cannot provide atomic journal commits. A custom `SessionLog` also needs `inbox()` for `AgentSession` commands; `JournalSessionLog` already supports it.

<Accordion title="Physical layout, command journal and backups">

The logical object layout below is useful for operating a backend. Identity segments use Base64URL. POSIX local objects include a version prefix; Windows stores their logical paths, versions and payloads as SQLite rows. Read them through the SDK rather than treating them as application JSONL files.

```text
agents/s_<agent>/sessions/s_<user>/s_<session>/
  session.json
  head.json
  commits/<batch-hash>.json
  blobs/<sha256>
  exports/<sink-hash>.json
  inbox/
    session.json
    head.json
    commits/<batch-hash>.json
```

The inbox durably records tasks, steering, injected material and answers in the same backend. It has its own sequence and short writer lease, separate from execution `seq` and public SSE cursors. `inbox/accepted` records acceptance; `inbox/opened` and `inbox/closed` govern execution admission; `inbox/handled` and `inbox/rejected` record control handling. Execution events link commands through `inbox/started` and `inbox/applied`. Consumption preserves the acceptance record, and input is marked applied only after its checkpoint commits.

Back up headers, heads, reachable commits/blobs, inbox and export watermarks using a consistent backend snapshot or paused writers. Working files, artifacts and external dependencies need their own backups. Retention and archiving belong to the deployment.

</Accordion>

<span id="exporting-records" />

## Export records to another system

To feed an audit database or application event view, implement `SessionExportSink`. Its `accept` method should return only after durable acknowledgment, and duplicate `eventId` deliveries must be harmless. Drain committed records at startup or during idle periods:

```java
import io.agentscope.core.session.SessionLogExporter;

// sink is your SessionExportSink implementation.
new SessionLogExporter(session.log(), sink).drain();
```

Keep `sink.name()` stable across deployments because it identifies the saved destination watermark. `drain()` exports committed events without re-executing the Agent. Arrange retries and coordinate replicas exporting the same session and sink. Hosted Service provides its own public event export and SSE endpoints.

To also export during execution, put the sink under `SessionExportSink.CONTEXT_KEY` in the `RuntimeContext` used for the direct call or for the first `agent.session(ctx)` lookup. Adding it to a later lookup does not replace the cached session's initial context.

<span id="custom-events" />

## Append application events

A business tool or middleware may want to record a review note alongside Agent events. Obtain the invocation's `SessionRecorder` from its `RuntimeContext` and compose the commit into execution:

```java
import io.agentscope.core.agent.RuntimeContext;
import io.agentscope.core.session.SessionRecorder;
import java.util.Map;
import reactor.core.publisher.Mono;

static Mono<Void> recordReview(RuntimeContext context, String note) {
    SessionRecorder recorder = SessionRecorder.from(context);
    if (recorder == null) return Mono.empty();
    recorder.append("acme/review_note", Map.of("note", note), false);
    return recorder.flush();
}
```

Await the returned `Mono` as part of the tool or middleware pipeline. `required=false` suits diagnostic facts that do not participate in recovery. For recovery-relevant types, register validators and reducers through `SessionEventCodecRegistry` before execution or restoration; an unknown required type prevents recovery. Do not retain the recorder for writes after its invocation ends.

<span id="migration-and-forking" />

## Export state or fork a conversation

For an offline migration or a new conversation starting from an existing result, use `SessionMigration`. Stop source execution and resolve uncertain tools, unfinished runs and pending interactions first. `exportSnapshot(source)` returns the saved state and `asOfSeq`; `exportState(source)` returns state alone, and `importBaseline(target, state, source, sourceVersion)` imports a complete `AgentState` into an empty target with provenance.

To fork a completed comparison into a separate conversation:

```java
import io.agentscope.core.agent.RuntimeContext;
import io.agentscope.core.session.SessionMigration;

var forkContext = RuntimeContext.builder(ctx).sessionId("comparison-alternative").build();
SessionMigration.fork(
        session.log(),
        agent.sessionLog(forkContext),
        agent.sessionKey(forkContext),
        "conversation-001");
```

The destination starts from an imported baseline. It does not copy the source event history, sandbox or external resources, and it does not invent earlier execution events.

## Complete example: recoverable Web Chat

The [agentscope-chat walkthrough](/v2/en/blogs/best-practices/session-chat) connects these operations to HTTP handlers and a browser: submit a background task, restore partial messages and tools, reconnect SSE, answer pending input and continue after interruption or restart. It runs offline by default and can also use a real model. Follow its source to adapt the application projection and runtime ownership to your own chat or task UI.

---
title: Session log API and storage reference
description: Native event types, backend configuration, exports, custom events, migration and session forks.
zh_link: /v2/zh/docs/harness/session-log-reference
---

Use this reference when inspecting full execution history, configuring storage or building an integration. Start with the [session operations guide](/v2/en/docs/harness/session-log) for common scenarios.

HarnessAgent saves execution records through Session Log by default, whether you use `call` / `streamEvents` or `AgentSession` scheduling. Reading records does not require session scheduling or re-execute the Agent:

```java
var log = agent.sessionLog(ctx); // Use the same conversation identity as execution.
var events = log.readAfter(0, 100);
```

## Event envelope and reads

SessionEvent is immutable. Payloads are frozen as JSON when accepted.

| Field | Meaning |
| --- | --- |
| schemaVersion | Envelope version |
| eventId | Native event identity for deduplication |
| seq | Increasing durable sequence within a session, starting at 1 |
| occurredAt | Timestamp; use seq for ordering |
| type | Event type |
| turnId / executionRunId | Logical request and execution; administrative operations may have no turnId |
| required | Whether recovery must recognize this type |
| payloadJson / data() | Frozen JSON / parsed Map |

readAfter(seq, limit) reads committed events after seq. scan(after, through) reads a fixed prefix; obtain through from log.head().seq(). Reading history does not execute models or tools.

### Event types

| Types | Contents |
| --- | --- |
| run/start, run/end, run/stop_requested | Execution start, outcome and stop request |
| turn/start, turn/resumed, turn/output | First invocation, continuation and final output |
| turn/completed, turn/suspended, turn/failed, turn/interrupted, turn/cancelled | Logical outcomes and paused state |
| step/start, step/end | Reasoning steps |
| input/received, input/applied, input/discarded | Input admission, application or discard |
| message/system, message/user, message/assistant | Message history |
| request/prepared, model/dispatch, model/chunk, model/end, model/retry | Final adapter request, dispatch, chunks, usage and retries |
| tool/requested, tool/decision, tool/dispatch, tool/chunk, tool/result | Tool requests, permission decisions, dispatch and results |
| action/start, action/end | Individual tool-action boundaries |
| interaction/requested, interaction/resolved | Pending interactions and applied responses |
| context/build, context/replaced, compaction/start, compaction/end | Context construction, replacement and compaction |
| task/changed, plan/changed, permission/changed, verification/result | Task, plan, permission and verification state |
| subagent/spawned, subagent/completed, subagent/linked | Parent/child links; complete child execution lives in its own log |
| state/checkpoint, recovery/applied, migration/baseline | Restorable state, reconciliation and imported baseline |
| inbox/started, inbox/applied | Link accepted commands to execution and checkpointed input |
| presentation/hint, application types | Presentation hints and custom facts |

Coverage includes adapter-visible requests and output, not provider internals or automatic backups of referenced files. Raw requests, tool results and checkpoints belong in an authorized diagnostic interface; public browser events should be filtered.

## Storage and backend configuration

Harness defaults to WorkspaceSessionLogStore, routed by the Workspace Filesystem. Local records live in .agentscope-runtime/ under the Filesystem root resolved for the current identity. RemoteFilesystem uses the __agentscope_session_log_v1__ partition in the corresponding BaseStore namespace.

SessionKey(userId, agentId, sessionId) identifies the conversation. The logical object layout is:

```text
agents/s_<agent>/sessions/s_<user>/s_<session>/
  session.json
  head.json
  commits/<batch-hash>.json
  blobs/<sha256>
  exports/<sink-hash>.json
  inbox/                       # durable command journal
    session.json
    head.json
    commits/<batch-hash>.json
```

Identity segments use Base64URL. Commits contain batches, blobs contain larger payloads, and exports store acknowledged destination watermarks. These are storage objects, not an application-facing JSONL format; local objects include a version prefix. Access them through SessionLog APIs.

Session operations also use `inbox/` in the same backend for tasks, steering, injection and answers. This command journal has its own sequence and short writer lease; never mix it with native execution seq or public SSE cursors. inbox/accepted records acceptance, inbox/opened and inbox/closed control execution admission, and inbox/handled and inbox/rejected record control handling and rejection. The execution journal links commands through inbox/started and inbox/applied. Consumption retains the original acceptance record.

Custom SessionLog backends must implement inbox() for AgentSession commands. JournalSessionLog, including Workspace and in-memory stores, supports it. Inputs are marked applied only after their checkpoint commits.

Within an Agent, the same identity reuses one AgentSession, which schedules tasks with a copy of the initial RuntimeContext. Use stable session-level configuration and keep user identity and storage namespace consistent.

### Shared storage

Working files and logs can use separate backends. Here sharedStore is an application-configured BaseStore with atomic versioned writes:

```java
import io.agentscope.harness.agent.filesystem.remote.RemoteFilesystem;
import io.agentscope.harness.agent.session.WorkspaceSessionLogStore;
import java.util.List;

var logStore = new WorkspaceSessionLogStore(
        new RemoteFilesystem(sharedStore, List.of("my-app", "session-history")));
// Pass logStore to HarnessAgent.builder().sessionLogStore(logStore).
```

Local, Redis, JDBC, MySQL, PostgreSQL, Mongo and ControlPlane have corresponding atomic storage implementations. InMemory is process-local and does not survive restarts. A custom Filesystem must provide sessionStorage or use a separate SessionLogStore; ordinary file reads/writes alone cannot guarantee atomic journal commits.

Replicas need the same SessionKey and namespace in shared storage. Discovery follows the current namespace: SESSION-isolated discovery does not enumerate other sessions.

### Custom backends and backups

Implement SessionLogStore, or implement AtomicSessionStorage and reuse JournalSessionLog. Preserve atomic compare-and-set writes, writer leases, stale-writer fencing, ordered idempotent commits and durable export cursors. SessionLogStore.list(RuntimeContext) supports discovery.

Back up headers, head, reachable commits/blobs and exports using a consistent backend snapshot or a paused writer. Working files, artifacts and external dependencies need their own backups. The deployment manages retention and archiving.

## Exporting records

SessionExportSink delivers committed events. accept should return only after durable destination acknowledgment. Duplicate eventId deliveries must be harmless.

```java
import io.agentscope.core.agent.RuntimeContext;
import io.agentscope.core.session.SessionExportSink;
import io.agentscope.core.session.SessionLogExporter;

// sink is your SessionExportSink implementation.
RuntimeContext exporting = RuntimeContext.builder(rc)
        .put(SessionExportSink.CONTEXT_KEY, sink).build();
// Execute with exporting. Also drain committed history at startup or during idle periods:
new SessionLogExporter(agent.sessionLog(exporting), sink).drain();
```

Keep sink.name() stable across deployments; it identifies the persistent watermark. drain does not re-execute the Agent. Arrange retries and coordinate replicas exporting the same session/sink. Hosted Service provides public event export and SSE endpoints.

## Custom events

Get SessionRecorder from the RuntimeContext supplied to a running middleware or tool:

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

Compose the returned Mono into execution to await its commit. required=false is suitable for diagnostic facts that do not participate in recovery. Register validators and reducers through SessionEventCodecRegistry before execution/recovery for recovery-relevant types. Unknown required types prevent restoration. Do not retain a recorder for writes after its invocation ends.

## Reconciling uncertain tool outcomes

If a tool produced an external effect before its result committed, recovery reports uncertainToolCalls. Verify the actual outcome and supply results covering exactly those IDs:

```java
import io.agentscope.core.message.TextBlock;
import io.agentscope.core.message.ToolResultBlock;
import io.agentscope.core.message.ToolResultState;
import java.util.Map;

var result = ToolResultBlock.builder()
        .id("call-id-from-inspection").name("create_order")
        .output(TextBlock.builder().text("Order ORD-42 confirmed created").build())
        .state(ToolResultState.SUCCESS).build();
agent.reconcileToolOutcomes(rc, Map.of(result.getId(), result), "Verified order-system records");
```

This saves the result and checkpoint without executing the tool again. Continue the original turn explicitly afterward. Do not invent a successful empty result when the real outcome remains unknown.

## Migration and forking

| API | Use |
| --- | --- |
| SessionMigration.importBaseline(target, state, source, sourceVersion) | Import a complete AgentState into an empty target with provenance |
| SessionMigration.exportSnapshot(source) | Export state and asOfSeq |
| SessionMigration.exportState(source) | Export state only |
| SessionMigration.fork(source, destination, destinationKey, sourceReference) | Start a new identity from current state |

Stop source execution and resolve uncertain tools, unfinished runs and pending interactions before exporting or forking. An imported baseline provides state without inventing prior execution history. A fork does not copy the source history, sandbox or external resources.

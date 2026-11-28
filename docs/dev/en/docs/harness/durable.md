---
title: "Durable execution"
description: "Embed persistent Turns, native AgentScope recovery and independently executed tools."
zh_link: /dev/zh/docs/harness/durable
---

Durable adds persistent task admission and recovery around an existing Agent.
Your Agent keeps its native reasoning loop, permissions, ThreadJournal and workspace.
A host can restart and continue the original Turn from a compatible native checkpoint.

This is a development API in the source tree. Artifacts have not been published;
build the matching checkout before using the coordinates below.

## Choose the right task boundary

Use Durable when a task must continue after its HTTP request, observer or execution
process ends. Ordinary `ReActAgent` and `HarnessAgent` calls remain suitable when
the caller manages invocation and state persistence itself.

| Resource | Use it for |
| --- | --- |
| Session | One business conversation, its Threads and public event timeline |
| Thread | One Agent's continuing context, pinned definition and ordered task queue |
| Turn | One submitted task; this is the identity to save, observe and control |
| Run / Step | Diagnosing actual invocations and reasoning/tool processing within a Turn |

Recovery preserves Session, Thread and Turn identity. A replacement invocation creates
a new Run and new Steps; it does not create another user task. Most applications use
the Session's primary Thread. Create additional Threads for independent contexts or
durable child Agents, not for every retry or reconnect.

## Java modules and deployment boundaries

Library JARs live in `durable-runtime`, `durable-worker` and `durable-adapters`. The adapter
aggregator contains the AgentScope Harness adapter plus Claude Managed Agents event and AG-UI
event adapters; `agentscope-examples/durable` is a separate example aggregator. Hosts assemble
the libraries; dependencies do not start services.
The AgentScope adapter depends on runtime, Core and Harness; worker depends only on
runtime (excluding test dependencies). There is no separate codec artifact.

- Brain uses `AgentScopeAdapter` to invoke the native Agent and `DurableRuntime`
  to schedule and recover Turns.
- Backend-only checkpoint operations use
  `io.agentscope.durable.codec.agentscope.AgentScopeCheckpoints` and
  `AgentScopeCheckpointCodec.STATE_VERSION` from `agentscope-durable-adapter-harness-agentscope`.
  These codec operations do not construct or start an Agent, but the artifact brings
  in Harness dependencies. The codec prepares and validates native baselines;
  `SessionBackend` installs them transactionally and checks the target's idle state
  and expected position.
- `agentscope-durable-worker` contains both caller-side reliable delivery and Hands
  execution management. `QueuedExecutor` runs in Brain or a backend service,
  persisting commands and delivering received results to the Backend.
  `WorkerRuntime` and `ExecutionStore` run on Hands, managing execution, recovery,
  cancellation and output. `BatchExecutor` composes child tool executions.
  Depending on the worker JAR does not start Hands.
- A local host can embed these objects in one JVM. Separate hosts connect through
  HTTP/RocketMQ extensions. Both sides of the RocketMQ extension depend on worker,
  wiring `RocketMqRemoteExecutor` and `RocketMqWorker` respectively. Deployment roles do
  not require additional library modules.

## Embed a host

Use `io.agentscope:agentscope-durable-adapter-harness-agentscope` and
`io.agentscope:agentscope-extensions-durable-jdbc` at the same version as Core/Harness.
SQLite is the default local persistent backend. PostgreSQL provides shared storage
for multiple compatible hosts. Neither requires a Worker or message queue.

```java
import io.agentscope.core.ReActAgent;
import io.agentscope.durable.DurableRuntime;
import io.agentscope.durable.DurableTypes.SessionKey;
import io.agentscope.durable.agentscope.AgentScopeAdapter;
import io.agentscope.extensions.durable.jdbc.LocalBackend;
import java.nio.file.Path;
import java.util.Map;

var backend = LocalBackend.open(Path.of("./durable"));
var adapter = AgentScopeAdapter.forReAct(binding ->
        ReActAgent.builder().name("assistant").model(model), Map.of());
var runtime = DurableRuntime.builder().backend(backend)
        .harness("assistant", "definition-v1", adapter).build();
var session = new SessionKey("application", "conversation-1");
runtime.createSession(session, "assistant");
var receipt = runtime.getSession(session).submit("request-1", "Describe this workspace");
runtime.start();
```

The application supplies `model` using its existing configuration. Keep the Runtime
alive for the host lifetime and close it at shutdown. Reusing a request ID with the
same input returns the original Turn; changing the input is a conflict. Query
`receipt.status()` to observe its status.

Use `AgentScopeAdapter.forHarness` for a factory that returns a fresh `HarnessAgent.Builder`
with a configured workspace. Both factories return builders; the adapter injects the
invocation's Thread Journal and builds the Agent. The public binding exposes the definition,
runtime context and Durable workspace. The Runtime owns the durable lease and task scheduling.
ThreadJournal owns native Agent history; Durable scheduling remains in the Durable Runtime.
The namespace maps to `RuntimeContext.userId`; identity is invocation-scoped.

### Run model reasoning in a Durable Worker

Use `withModelExecutions` to hand off reasoning from a ReAct or Harness Agent to a
`ModelWorker`. The Agent saves its recovery position before submitting a model
request. The Worker claims a lease for the request's route, runs the provider and
commits output in ordered batches. The first committed batch makes the original
Turn runnable. Any compatible Runtime can replay committed batches and read
subsequent ones using the same Session, Thread, Turn and model call IDs. Later
Turns on the same Thread remain queued until the current Turn settles.

For a local deployment, use SQLite for both records and output bytes:

```java
var records = JdbcRecordStore.sqlite(Path.of("./durable.db"));
var objects = new RecordObjectStore(records);
var wakeups = Wakeups.local();
var backend = new TransactionalSessionBackend(records, objects).withWakeups(wakeups);
var executions = new TransactionalModelExecutionStore(
        records, new ObjectModelOutputStore(objects), wakeups);
var adapter = AgentScopeAdapter.forReAct(
        binding -> ReActAgent.builder().name("assistant").model(model), Map.of())
        .withModelExecutions(executions, "reasoning");
var runtime = DurableRuntime.builder().backend(backend)
        .harness("assistant", "definition-v1", adapter).build();
var worker = new ModelWorker(executions, "reasoning", ModelExecutor.using(name -> model));
worker.start();
runtime.start();
```

Keep the Worker and Runtime running for the lifetime of their hosts and close
them at shutdown. The resolver passed to `ModelExecutor.using` resolves the
requested model name using the Worker's provider configuration.

In a distributed deployment, share a transactional record database, such as
PostgreSQL through `JdbcRecordStore`, across the Session backend, Agent and Worker.
The database stores the execution state, output sequence and storage references.
Supply a shared `S3ObjectStore` or `OssObjectStore` to both
`TransactionalSessionBackend` and `ObjectModelOutputStore` so handed-off model
chunks and their journal content live in object storage. Direct calls use the
Journal's size-based storage rule. Use `RocketMqWakeups` to notify
Workers and Runtimes of changes. They also check durable state periodically to
recover from delayed or lost notifications.

A custom `ModelOutputStore` can use a replayable message broker as its output
store. Its `write` returns a stable locator, such as a topic, partition and offset.
Its `read` must be able to retrieve that exact batch from another instance until
the Turn no longer needs to resume. The runtime commits the locator only after
the write succeeds. If a Worker loses its lease after starting a provider call,
the execution settles as `UNKNOWN` and the Runtime can handle that outcome
without automatically issuing a second provider call.

### Dispatch reasoning to a model queue

Register `adapter.withQueuedModel(publisher)` as the harness for this definition. Both
ReAct and Harness factories support this option. The publisher receives a
`QueuedModelRequest` with the target Thread and Turn, call ID, model, prepared
messages, tool schemas and generation options. Acknowledge only after the broker accepts the
request. Recovery can repeat a send whose acknowledgement was lost, using the same call ID,
and may trigger another provider request. Workers use their configured provider credentials.
Harness memory and finalization model calls keep their own execution path.

The adapter commits the prepared request and Agent recovery position before dispatch. Once
dispatched, the Turn waits without holding its Runtime invocation. Replies resume the same
Turn on any compatible Runtime that shares the backend. Later Turns on the same Thread
can be submitted and remain queued behind it. To wake the waiting Turn, the reply consumer
uses the original request's Thread and Turn:

```java
var turn = runtime.getSession(request.thread().session())
        .getThread(request.thread().threadId()).getTurn(request.turnId());
String deliveryId = java.util.UUID.randomUUID().toString();
boolean accepted = QueuedModelRequest.begin(turn, request.callId(), deliveryId);
```

Call begin once before generation. If accepted is false, acknowledge the MQ delivery and return. Every frame from an accepted generation uses the same delivery ID.

```java
var signal = QueuedModelRequest.reply(
        request.callId(), deliveryId, sequence, chatResponse, last);
boolean stored = QueuedModelRequest.accept(turn, signal);
```

A false stored result is an authoritative ignored reply and can also be acknowledged.

Start at sequence zero. A complete response uses a single terminal frame. For streaming,
use consecutive sequence numbers and mark the last frame terminal. A terminal frame may
omit the response if all chunks were sent earlier. Use `QueuedModelRequest.failure` for
a terminal error. Acknowledge each broker delivery after the signal is persisted. Retrying
the same frame uses the same signal ID and payload. The first frame wakes a compatible
host, which displays ordered deltas and advances the ReAct loop after the terminal frame.

By default, the adapter uses a five-minute idle timeout, initially measured from broker acceptance.
Each newly consumed ordered frame renews the persisted deadline. Replaying frames after a Runtime replacement does not renew it.
Set a different budget with `withQueuedModel(publisher, Duration.ofMinutes(15))`. The deadline
is persisted across Runtime replacements. At the deadline, a missing frame fails the Turn with
the call ID and expected sequence. A replacement can still process replies already available.
If a collector dies after publishing a partial reply, its replacement replays the persisted
chunks with the same message ID and attempt and a higher presentation revision. Clients replace
the previous partial text when that revision starts.
Malformed reply frames fail the Turn before later chunks can trigger tools.
With a configured fallback model, a provider failure before the first response can dispatch the
fallback as a separate call. A replacement host resumes that call. Suspending the host, an invalid
frame, or a failure after the first response does not trigger another model dispatch.

## Reconnect and control tasks

### Reopen an existing task

Persist the `SessionKey`, Thread ID and Turn ID with your application's task record.
After a page refresh or application restart, use those identities to query the same
task. Here `runtime` is connected to the original Backend or public HTTP endpoint:

```java
var conversation = runtime.getSession(new SessionKey("application", "conversation-1"));
var thread = conversation.getThread("main");
var turn = thread.getTurn(savedTurnId);
var current = turn.status();
var history = thread.history(null, 100);
```

These reads do not start execution. A compatible Brain Runtime must be running to
advance unfinished work. Reattach an [observer](#observe-independently) to receive
the snapshot and subsequent events. A wait timeout or disconnected stream does not
cancel the Turn.

If the submit response was lost before you saved the Turn ID, retry with the original
request ID and input. Request IDs are deduplicated within the Session; changing the
target Thread, operation or input under the same ID produces a conflict. Use a new
ID for a new action, including actions on another Thread.

### Queue follow-ups or change the current task

For an incident investigation, submit the follow-up summary as another Turn and send
new constraints to the running investigation with `steer`:

```java
var investigation = conversation.submit("investigate-1", "Investigate the export timeout");
var summary = conversation.submit("summary-1", "Summarize the investigation");
investigation.steer("constraint-1", "Focus on database access; do not change the API");
```

The summary waits behind the investigation in the primary Thread. A WAITING or PAUSED
head also blocks later Turns. `steer` and `inject` add input to the existing Turn at
a safe boundary; they do not interrupt a model mid-token or undo a dispatched tool.
`withdraw` can remove input only before it is applied. If completion wins a race with
a control, re-read the Turn rather than assuming the control took effect.

Read the queued Turn's input revision before editing or redirecting its input.
Both operations require the revision observed by the caller.

```java
var thread = conversation.mainThread();
var edit = thread.editQueuedInput(
        "edit-summary-1", summary.turnId(), summary.status().inputRevision(),
        Input.text("Include only findings from the database investigation"));
thread.convertQueuedToSteer(
        "redirect-summary-1", summary.turnId(), investigation.turnId(), edit.inputRevision());
```

The edit preserves the message ID and history position. Conversion changes the source Turn
to `CONVERTED` and uses the same message ID in the target Turn's model context. A stale input
revision, a started source, or a finished target returns a conflict. Retry an uncertain
response with the same operation ID and payload.

A control receipt confirms admission. Observe `signal.applied` or the resulting Turn
state to confirm processing. A queued Turn can be cancelled immediately. When active
work is cancelled, the Brain can interrupt an in-flight direct Executor call and then
settles that execution using the provider's observed outcome. Unconfirmed effects
remain `UNKNOWN`.

The AgentScope adapter closes planned calls that never started and preserves results
received before cancellation. Result hooks run before those results are carried into
the next Turn. The first committed decision determines the terminal Turn outcome.

### Pause and resume deliberately

Call `turn.pause("pause-1")`, then observe PAUSED before reporting that the Agent has
stopped advancing. In a later request, resume using the observed revision:

```java
import io.agentscope.durable.DurableTypes.TurnStatus;

var paused = turn.status();
if (paused.status() == TurnStatus.PAUSED) {
    turn.resume("resume-1", paused.revision());
}
```

Pause does not cancel dispatched Hands operations. Tool results and interaction
answers can still be recorded while paused. Resume removes the explicit pause but
does not bypass approvals, running tools or UNKNOWN outcomes. Terminal Turns cannot
be resumed; submit a new Turn when the user wants another task.

### Run independent work in another Thread

```java
var research = conversation.createThread("research", "assistant");
research.submit("research-1", "Find related incidents");
// A later request reuses that Agent's context.
conversation.getThread("research").submit("research-2", "Compare their mitigations");
```

Different Threads can execute concurrently within the host's capacity. Their histories,
checkpoints and queues are independent. Session event visibility does not copy one
Thread's conversation into another Agent's context. Use [durable delegation](#durable-child-delegation)
when the parent Agent must wait for a child's result. Shared files, credentials and
Sandboxes need explicit configuration and access control; Thread isolation does not
serialize writes to shared resources. Close a Thread or Session after its tasks settle;
closure rejects unfinished work and does not implicitly cancel it.

## Unified user API

`DurableRuntime` is the thread-safe assembly, lifecycle and business entry point.
Session/Turn handles retain identity only and can be rehydrated by any application
instance. The backend holds authoritative state; queries never start an Agent.

| Object | Operations |
| --- | --- |
| Runtime | `createSession(key, definition)`, `getSession(key)`, `listSessions(namespace, cursor, limit)`, `capabilities()` |
| Session | `submit/call`, `getTurn/listTurns/history`, `snapshot()`, `close()` default to or aggregate the primary Thread as appropriate; `mainThread()`, `createThread(id, definition)`, `getThread(id)`, `listThreads(cursor, limit)` expose explicit Threads |
| Thread | `submit(requestId, Input)`, `getTurn(turnId)`, `listTurns(cursor, limit)`, `history(cursor, limit)`, `view()`, `close()` |
| Turn | `status()`, `result()`, `await(timeout)`, `steer`, `inject`, `withdraw`, `cancel`, `pendingInteractions`, `respond`, `pause`, `resume` |
| Session / Thread / Turn | `listEvents(cursor, limit)`, `streamEvents(options)`, `subscribeEvents(options, listener)`; filtering retains the Session sequence |
| Thread | `checkpoints(after, limit)`, `restore(position, expectedPosition, requestId)`, `fork(position, targetThreadKey, requestId)`, `forkSession(position, targetSessionKey, requestId)` |

To branch from a retained, settled checkpoint into an independent Session, use the
source Thread and a new Session key in the same namespace.

```java
var branch = conversation.mainThread().forkSession(
        checkpointPosition, new SessionKey("demo", "alternate"), "fork-alternate");
branch.submit("alternate-1", "Explore another approach.");
```

The new Session, its main Thread and the restored baseline become visible together.
The main Thread inherits the source binding and checkpointed private resources.
Retry with the same source checkpoint, target key and request ID after a lost reply.
A destination created by another request causes a conflict. Use `fork` for a Thread
in an existing Session. External files and Sandbox lifetimes follow their configured stores.

DurableTurn represents a persistent logical task. Native AgentRun is one in-process
invocation sharing runId with Durable's RunView; StepView records its steps. Recovery
retains turnId and creates a new Run/Step. Query history through `turn.listRuns(cursor, limit)`,
`turn.getRun(runId)`, `turn.listSteps(runId, cursor, limit)` and `turn.getStep(runId, stepId)`;
queries never start an Agent.

Inspect models with `turn.listModelCalls(runId, stepId, cursor, limit)` and
`turn.getModelCall(runId, modelCallId)`; null stepId selects all calls in the Run.
Each call includes observed requests/retries, Step provenance, elapsed time and known token usage.
Recovery replay adds no request or usage. `usageComplete=false` means accounting is partial;
missing counts remain null and details are subsets of total tokens. `PROVIDER_ATTEMPTS` observes
the shared model request boundary. Custom models exposing only the outer call use `MODEL_CALL`,
without inferring hidden retries. Lost requests retain LOST and known usage without invented completion.

Cumulative reasoning position is saved with native checkpoints, so rebuilding Runtime does not
reset a Turn's `maxIters`. A continuation Step applying existing tool results consumes no additional
reasoning iteration; a new Turn receives a fresh budget. Reasoning, result application and
finalization recovery record Steps in the new Run and can link interrupted steps through
`resumedFromStepId`. A Step can advance to FINALIZATION; events retain phase changes.
The current native format is `agentscope-thread-journal-v2`; old development formats are not converted.

Core/Harness upgrades preserve the AgentScope 2.0 state contract. State without `thread_id` maps
to `main`. When an empty main Thread first runs, it imports the existing `agent_state` entry from
the configured `AgentStateStore` and commits a `migration/baseline` before continuing. Named
Threads never import that entry. Committed main-Thread state is mirrored back to the same
`agent_state` slot so a 2.0 runtime can read the latest settled state after a rollback. The
2.1.0-BETA1 `SessionLog` API and storage format are not an upgrade contract.

Session creation atomically creates its primary Thread, and each Thread pins its Agent definition version.
Each Thread executes in durable admission order; waiting tasks block its later tasks. Different Threads
in one Session can run concurrently. `turn.pause(requestId)` admits pause intent; active calls move from PAUSING
to PAUSED at a safe boundary. Pause blocks later Turns in the same Thread. External results and answers can
still be recorded without automatically advancing execution. Native journals report invocation outcomes through run/output
and run/end; only Durable commits task outcomes. Queued tasks
can be cancelled immediately; active cancellation requires execution outcome verification.

```java
var handle = runtime.getSession(new SessionKey("application", "conversation-1"));
var turn = handle.submit("request-2", "Continue the research");
var current = turn.status();
var history = handle.history(null, 100);
// Later requests use the same SessionKey, threadId and turn.turnId().
```

`submit` returns a durable Turn handle. Retrying the same requestId/content returns
the original Turn; different content produces CONFLICT. Controls return a CommandReceipt
for atomic admission, while `signal.applied`, `interaction.resolved` or a terminal Turn
confirm subsequent processing. submit/steer/inject/respond also commit public user
messages carrying requestId/inputKind; responses add interactionId. History reads the
public projection, never private native logs. Event pruning preserves projected history.

A Turn can produce several assistant messages, with distinct reasoning rounds retained separately.
Logical message IDs survive Run/Step changes. A new model request increments attempt; replaying a
committed response replaces incomplete text through revision, preventing duplicated text after
reconnection. `message.completed` is the authoritative durable fact. `message.delta` and
`tool.arguments.delta` are best-effort `LiveEvent` previews produced from middleware-processed
presentation facts; they have no sequence or cursor and never enter history, snapshots or replay.
Private reasoning remains excluded. History uses the first public sequence for ordering; consumers
reading the snapshot messages map should also sort by sequence rather than relying on object field
order. Existing stored `message.delta` and `tool.call.arguments` events remain readable for
development-data compatibility, but new AgentScope writes do not create them.

The snapshot tools projection associates argument fragments, progress and results by Thread,
Turn and callId. Fragments may omit an unknown tool name; full arguments end fragment assembly.
Local tools and remote Hands share a call view, while only external executions carry executionId.
Execution completion, result receipt and result application remain separate. Recovery preserves
the actual output, and UNKNOWN remains until the outcome is explicitly reconciled.

`session.call(requestId, input, timeout)` combines submit + await as a CompletionStage. For event
delivery, subscribe or open the event stream before `submit`; this preserves live previews even for
a fast Agent. The old submit-and-observe `stream(...)` shortcut is deprecated because its name was
ambiguous and it could only attach after admission.
Retries use the same requestId; observing an existing Turn never resubmits. Timeout errors
include Thread/Turn identity. Closing a subscription, cancelling a Future or closing
Runtime never cancels durable work; cancellation is explicit. Short operations can
perform database/network I/O; Web hosts must call them off event-loop threads.

## Separate API hosts and HTTP

An API-only Runtime needs backend access and definition metadata/definitionResolver
for new sessions. It needs no Agent factory, model or Executor. Looking up an existing
session requires no definition registration.

```java
import io.agentscope.durable.codec.agentscope.AgentScopeCheckpointCodec;
import io.agentscope.durable.codec.agentscope.AgentScopeCheckpoints;

var api = DurableRuntime.builder().backend(backend)
        .definition("assistant", "definition-v1", AgentScopeCheckpointCodec.STATE_VERSION)
        .checkpoints("assistant", AgentScopeCheckpoints::restore)
        .build();
// API-only: do not call start(); this Runtime does not claim tasks.
```

Checkpoint restore uses the codec within `agentscope-durable-adapter-harness-agentscope`
without constructing an Agent; the artifact still depends on Harness. Native state is
validated/copied internally; the target must be idle at the expected position. Fork targets another Thread in the same
namespace with the source's pinned definition; its target Session must already exist.
Restoration appears in the target Session timeline. Inherited native conversation history
is private recovery state, not fabricated past public events.

Mount `DurableRuntimeHandler(api, authorizer)` and `SessionEventHandler(api, authorizer)`
for HTTP. The authorizer checks trusted identity, namespace and Session, including both
fork source and target. The public handler rejects claim/commit/native-state operations.
Other Web frameworks can call Runtime directly; HTTP is optional.

```java
import io.agentscope.extensions.durable.http.DurableHttp;
import java.net.URI;

var web = DurableRuntime.builder()
        .transport(new DurableHttp(URI.create("http://127.0.0.1:8080/api")))
        .build();
var sameSession = web.getSession(new SessionKey("application", "conversation-1"));
```

Configure either a local backend or public transport. `HttpSessionBackend` exposes
trusted internal Backend access for execution hosts, not a public user endpoint.
Calling start without execution configuration fails explicitly. Brain separately
assembles Harness/Executor and starts autonomous scans/claims/recovery. Optional MQ
hints reduce latency; scans of durable state preserve progress when hints are lost.
API/SSE shutdown does not stop other Brain hosts.

## Java protocol and backend selection

The public Java contracts live in `DurableTypes`, `DurableMessages` and
`DurableProtocol`. Backend and Executor HTTP calls validate the same versioned JSON
Schema shipped in the runtime JAR. Invalid requests are rejected before execution.
Malformed successful replies remain uncertain outcomes; recover using the original
request or commit receipt.

A private Frame includes checkpoint metadata: Harness, definition/state versions,
an independent string position and a canonical digest. The adapter verifies this
binding before native recovery. Public snapshots and events never include that state.
An existing native checkpoint cannot be discarded by sending null.

| Implementation | Persistence and use |
| --- | --- |
| `LocalBackend.open(directory)` | Local durable storage; compatible processes on one host |
| `PostgresBackend.builder().dataSource(dataSource).build()` | Shared PostgreSQL storage for compatible hosts; add the optional PostgreSQL driver |
| `HttpSessionBackend(endpoint)` | Remote access to the same Backend semantics; capabilities come from the server |
| `TransactionalSessionBackend(recordStore)` | Custom atomic storage via RecordStore; explicitly declare persistence and sharing |

`runtime.executionCapabilities()` reports Backend guarantees, Harness recovery/event boundaries
and Executor query/cancel/output support. AgentScope declares native TOOL_BOUNDARY
recovery and DELTA delivery. This does not replay arbitrary Java stacks or make
unqueryable local effects exactly once.

Submit a string or structured content using the neutral Java records:

```java
import io.agentscope.durable.DurableMessages;
import java.util.List;

var input = new DurableMessages.Input("1", List.of(
        new DurableMessages.Text("Describe this picture"),
        new DurableMessages.Image(
                new DurableMessages.Url("https://example.org/image.png", "image/png"))));
var receipt = runtime.getSession(session).submit("picture-1", input);
```

Text, image, audio and video content support URL/base64 media sources. Arbitrary native
objects are rejected. Final Turn output uses the public Message format; completed
events and snapshots retain its structured content. Native reasoning is excluded.
A Turn can contain multiple assistant messages. Replayed versions of the same message
replace provisional text rather than creating another message. Custom events use
`x.<namespace>.<name>`; reserved lifecycle, tool, message and artifact events have
constrained payloads.

## Presentation events and protocol adapters

Durable stores two event classes internally and exposes one typed SDK stream:

| Delivery | Contract | Typical use |
| --- | --- | --- |
| `PublicEvent` | Persisted, sequenced, cursor-addressable and replayable | task state, completed messages, tool state, audit and recovery |
| internal `LiveEvent` | Best effort, connection-time only, no cursor or replay | text and tool-argument previews |

Applications consume the sealed `SessionStreamEvent` union: `Committed` wraps a `PublicEvent`,
`PreviewStarted` announces a preview, `EventDelta` carries an opted-in increment, and
`SnapshotReset` replaces local durable state after resynchronization. Only `Committed` contains a
Durable cursor. `EventStreamOptions.deltas(...)` enables message or tool-argument previews for one
stream connection; the default delivers durable facts only.

```java
import io.agentscope.durable.EventStreamOptions;
import io.agentscope.durable.EventStreamOptions.EventDeltaType;

var options = EventStreamOptions.builder()
        .after(savedCursor)
        .deltas(EventDeltaType.MESSAGE)
        .build();

try (var stream = session.streamEvents(options)) {
    stream.forEach(event -> render(event));
}
```

`listEvents(cursor, limit)` always returns only persisted `PublicEvent` values. Durable applications
do not consume Core/Harness `AgentEvent`, `JournalEvent` or the internal live transport type.

`agentscope-durable-adapter-claude` maps completed messages and opted-in live message deltas to the
Claude Managed Agents event shape. Enable `EventDeltaType.MESSAGE`, then pass a
`ClaudeEventStreamSink` to `SessionEventStream.forEach`. To submit a Claude `user.message` with text
or URL/base64 images, call `new ClaudeSessionEvents(session).sendMessage(requestId, event)` with an
authorized Session handle. The same request ID retries the original admission. The returned
event has the persisted message ID and a null `processed_at` at admission. The event stream emits
the message with its processing time after the AgentScope native checkpoint applies the input.
For `user.tool_confirmation`, pass a projected `agent.tool_use` ID as `tool_use_id` to
`sendToolConfirmation(requestId, event)`. It sends `allow` or `deny` to the original
Thread and Turn through native approval. The stream reports the response with its original ID once
the response is applied. Pass `user.custom_tool_result` to `sendToolResult` to supply text
or images and an optional `is_error` for a manual tool request.
Pass `user.interrupt` to `sendInterrupt`. Supply `session_thread_id` to target one Thread,
or omit it to target the started Turn of every open Thread. Retries keep the originally selected
Turns. Use `session.cancelActiveTurns(requestId, threadId)` for the same operation from Java.
The stream reports the interrupt's processing time when all selected Turns have settled.
Use `listEvents(page, limit, types)` to read Claude-shaped history in commit order. The returned
`next_page` token retains the first page's history boundary and event-type selection.
`agentscope-durable-adapter-agui` maps the same typed stream to the existing AG-UI 1.0
`AguiEvent` types through `DurableAguiEventStreamSink`.

### Claude event endpoints

Mount `new ClaudeSessionEventHandler(runtime, recordStore, authorizer)` at `/v1/sessions/` on a
JDK `HttpServer`. The authorizer resolves the requested Session into the caller's authorized
namespace. The handler serves `POST /v1/sessions/{id}/events`, `GET /v1/sessions/{id}/events`
and `GET /v1/sessions/{id}/events/stream` under the `managed-agents-2026-04-01` beta.
Use a persistent shared `RecordStore` across API replicas to preserve batch identity. Supply
`Idempotency-Key` to retry a send batch. The handler runs its events in order, and completed
items keep their original receipts if a later item fails.

History supports `order=asc` or `order=desc`, `types[]` and `created_at` bounds on processing time.
Session status reports `requires_action` with the IDs of unanswered manual tool events when the
open Threads are waiting for those responses. A fresh stream starts from the current high-water
mark. Reconnect with the last committed SSE `id` in `Last-Event-ID`. Opt in to message previews
with `event_deltas[]=agent.message`. Preview frames have no resume ID.
[Claude event streams](https://platform.claude.com/docs/en/managed-agents/events-and-streaming)

### Preview transport for separate hosts

The default `LiveEvents.local()` bus connects producers and observers only inside one Runtime
assembly. A split Brain/API deployment must provide the same shared `LiveEvents` transport to both
sides when live previews are required. Durable execution, history and final results remain correct
when that transport is absent or loses an event.

With Redis Pub/Sub, use `RedisLiveEvents` from `agentscope-extensions-redis`. Configure both
Runtimes through `DurableRuntime.builder().liveEvents(...)` with the same application-specific
channel. Pass `new RedisLiveEvents(channel, redisConnection.sync(), null)` on Brain and
`new RedisLiveEvents(channel, null, redisPubSubConnection)` on each API host. Use a dedicated
Pub/Sub connection and restrict channel access to trusted application roles. The transport
drops previews when its buffers fill or a payload exceeds 64 KiB. Close the Runtime and
transport before closing the application-owned connections.


## Hands Workers and RocketMQ execution

Hands requests retain the original tool-generating Run/Step in `call.origin` across redelivery,
host replacement and result delivery. `ToolExecution.appliedAt` and the public tool view's
`data.appliedAt` identify where the result entered Agent state, possibly a new Run/Step after recovery;
this position commits with the checkpoint. Host settlement outside an invocation uses a null position
without creating another Run. `data.origin` retains the initial position.
Batch children and Sandbox routing preserve the parent origin. AgentScope Hands tools can read
`RunStepRef` from RuntimeContext's `durable.origin`; `getRunId()` identifies the original Brain Run.

Hands is an embeddable component, not a mandatory separate service:

`WorkerRuntime` owns Hands scheduling, recovery and lifecycle; it is not an `Executor`. When Brain
and Hands share a JVM, register the borrowed `worker.localExecutor()` endpoint with
`DurableRuntime`. The endpoint delegates execution protocol calls to its owning WorkerRuntime.
The default local assembly accepts the Hands `RecordStore` directly and creates its transactional
execution ledger internally:

```java
var worker = WorkerRuntime.builder()
        .recordStore(handsRecords)
        .executor("hands", toolExecutor)
        .build();
```

Use `executionStore(customStore)` only for a custom Hands ledger. Brain continues to receive a
`SessionBackend`; the low-level JDBC RecordStore does not create that domain object.

| Deployment | Wiring and storage |
| --- | --- |
| Minimal local | Register an Executor directly, without Worker or MQ |
| Embedded Worker | Register `worker.localExecutor()`; SQLite can hold WorkerRuntime's ledger |
| HTTP sidecar / service | HttpExecutor → ExecutorHandler → `worker.localExecutor()` → WorkerRuntime |
| MQ Worker service | RocketMqRemoteExecutor → command queue → RocketMqWorker → execution updates |

Brain sends one RocketMQ dispatch message for all approved tools from the same
model action going to the same Hands route. Hands records each tool under its
original `executionId` before acknowledging the message. Redelivery fills any
missing records and reuses admitted work. Cancellation, queries, output and
results use individual execution IDs. Brain delivers the completed results to the
model in its original call order.

Use the MQ Worker's `authorize` callback to validate command identity and scope.
Use `authorizeStart` on RocketMqWorker or WorkerRuntime for current per-tool
business permissions. Return `Optional.empty()` to allow a new start, or a
display-safe reason to produce a durable `DENIED` result. Siblings use their own
permission decisions. Authorization errors use the Worker retry policy. Recovery
and cancellation of an already attempted execution keep the original identity.

Use `worker.statistics(limit)` to distinguish calls ready to start, leased
calls, calls awaiting provider evidence, and work deferred by retry. Compare
`oldestReadyAgeMillis` with the publication backlog age to locate delays before
start or after result commits. When `truncated` is true, the counts and ages
describe the inspected subset.

All Worker deployments reuse the same execution engine. Thread isolation does not
provide process or Sandbox isolation. Brain and Hands may use separate databases;
replicas of one pool share its ledger. Per-container SQLite files are not shared
cross-host storage.

Add the matching-version `agentscope-extensions-durable-rocketmq`. Wire Brain:

```java
var executor = RocketMqRemoteExecutor.builder()
        .clientConfiguration(clientConfiguration)
        .build();
var runtime = DurableRuntime.builder().backend(backend)
        .harness("assistant", "v1", adapter)
        .executor("hands", executor).build();
runtime.start();
```

Map AgentScopeAdapter tool bindings to `"hands"`. In the Hands process:

```java
var worker = RocketMqWorker.builder()
        .executor("hands", toolExecutor).recordStore(handsRecords)
        .clientConfiguration(clientConfiguration)
        .authorize(command -> policy.check(command))
        .parallelism(4).build();
worker.start();
```

The Brain transport ledger and ObjectStore automatically reuse the SessionBackend's local storage;
Runtime binds, starts, and closes the remote Executor. Users do not repeat the store, backend, or
route name. The Worker host supplies its execution ledger, tool Executor, and authorization policy.
Committed commands, Worker admissions, and outbox events trigger delivery by execution.
Indexed repair defaults to two seconds. Brain retries unconfirmed commands after
`retryInterval` (five seconds by default). It requests replay immediately for a known
sequence gap or after `responseTimeout` without new progress (30 seconds by default).
On `RocketMqWorker`, configure ledger discovery with `discoveryInterval`, event outbox
repair with `publicationRepairInterval`, and provider retry delay with `providerPollInterval`.
Runtime and Worker close MQ clients created from ClientConfiguration. Stores, SessionBackend, and
tool providers remain host-owned.

Provision LITE parents and individual Shared consumer groups on a LiteTopic-capable Broker
and Cluster Proxy. The default MQ namespace is `durable`.

| Parent Topic | Consumer group | LiteTopic scope |
| --- | --- | --- |
| `durable-turn-dispatch` | `durable-turn-runner` | Session + Thread |
| `durable-turn-signal` | `durable-turn-signal` | Session + Thread |
| `durable-turn-owner` | `durable-turn-owner` | Brain instance, explicit subscription |
| `durable-hands-commands` | `durable-hands-worker` | Session |
| `durable-hands-controls` | `durable-hands-worker-controls` | Session |
| `durable-hands-updates` | `durable-hands-brain` | Session + execution |

Replace `durable` with your deployment namespace and `hands` with the execution route.
For each parent and group, set these attributes with the deployment's NameServer and cluster.
Use `SUBSCRIPTION_MODE=EXPLICIT` for the owner group and `WILDCARD` for the others.
In RocketMQ 5.5.1, omit `lite.sub.wildcard` entirely for explicit subscriptions.

```sh
mqadmin updateTopic -n "$NAMESRV" -c "$CLUSTER" -t "$PARENT" -a '+message.type=LITE'
attributes="+lite.bind.topic=$PARENT,+lite.sub.model=Shared"
if [ "$SUBSCRIPTION_MODE" = WILDCARD ]; then
  attributes="$attributes,+lite.sub.wildcard=true"
fi
mqadmin updateSubGroup -n "$NAMESRV" -c "$CLUSTER" -g "$GROUP" -o true -m true \
  --attributes "$attributes"
```

Configure ACLs, authentication, TLS, and retention for those resources. Replicas of a
tool pool share its execution ledger. Brain replicas share its delivery and Session
records. Deployments with independent databases use separate MQ namespaces.
Worker authorization runs before ledger access.
Keep `enableProxyAutoRenew` enabled on the Cluster Proxy. The Lite push consumer uses
the Proxy's invisibility settings when receiving messages.

With [RocketMQ LiteTopic](https://rocketmq.apache.org/docs/domainModel/03litetopic/),
Hands acknowledges after durable admission. Brain acknowledges after inbox persistence and SessionBackend
acceptance. Broker ACK is not tool completion. Cancellation before execution leaves
a tombstone. Queries replay original state without creating tool effects.
Republishing saved results never reruns tools. Scans repair failed publication and
inbox-to-session commits.

ExecutionMessages and the Runtime-packaged schema define versions, request digests,
command/event IDs and source sequences. The inbox accepts a continuous prefix;
duplicates are idempotent and missing events are replayed before newer events apply.
Output pages carry byte offsets and explicit gaps, and are sealed before terminal
publication. Read accepted Hands bytes with `turn.toolOutput(executionId, offset)`,
beginning at zero and following each page's next offset. Provider capabilities determine
which bytes are available. The committed event stream records sealed ranges and gaps.
Select `EventDeltaType.TOOL_OUTPUT` for best-effort previews of local tool text and
Hands output. Each preview carries a Base64 byte range and has no event cursor. In
the AG-UI adapter, run-scoped output previews use the `agentscope.tool_output_delta` custom event.
Native SSE enables selected previews with a comma-separated `deltas` query parameter,
such as `deltas=MESSAGE,TOOL_OUTPUT`.
Arbitrary external effects are not exactly-once; unqueryable outcomes remain UNKNOWN.

Wire envelopes are limited to 1 MiB. Configure the same accessible ObjectStore via
`objects(...)` on both MQ adapters to externalize larger envelopes. Object references
are scoped and digest-checked; inaccessible or invalid payloads are not acknowledged.

To enable the Turn channels, create a `RocketMqTurnScheduler` and pass it to
`PostgresBackend.builder().notifications(scheduler)`, or use `backend.withWakeups(scheduler)`
with a local backend. The execution Runtime starts the consumers. The host closes the
scheduler after its runtimes. The backend reports this setup through
`messageDrivenScheduling`. The database retains FIFO order and dispatch generations.
The submitting host publishes the due dispatch after commit, including on an API-only host.
The envelope records the Turn revision and the reason at dispatch creation. Consumers check
the parent, LiteTopic, and message key. The backend validates the exact Turn and generation
when claiming. The dispatch receiver renews the delivery's invisibility while the Runtime
executes the phase. It acknowledges after the phase commits or the backend establishes that
the delivery is obsolete. Temporary failures and capacity limits leave the delivery available
for retry. Renewing a Turn lease reschedules recovery without changing its dispatch identity.
Thread signals notify readers of a committed change. A Brain explicitly subscribes to its
own owner LiteTopic. It verifies the current Turn and lease epoch before acting on a
control hint. An active cancellation can interrupt the invocation immediately. Steer
remains effective at the next native reasoning boundary.

`DurableRuntime.Builder.repairInterval` controls fallback scanning on MQ execution hosts
(30 seconds by default). `pollInterval` controls database discovery on other hosts.
Session hints scope discovery to the changed Session. `releaseDelay` controls how long
unsettled work waits after its lease is released. The scheduler arms a local timer for
known deadlines and rereads the database when the timer fires. The durable index covers
outstanding deadlines after outages and restarts.
Use `RocketMqTopology.turns(namespace).subscriptions()` and
`RocketMqTopology.route(namespace, executor).subscriptions()` to read the LITE parent topics,
bound consumer groups and subscription modes used by the runtime. Provision those resources
with your deployment tooling.

Pass `RocketMqTurnScheduler.Options` to `create` to configure the Outbox repair interval,
the wait before republishing an unclaimed dispatch, and separate dispatch and signal cache
sizes. The defaults are 2 seconds, 5 seconds, and 32 messages per consumer. The fifth
argument, `receiveWait`, sets the dispatch consumer's long-poll wait and defaults to
1 second. Choose a value compatible with the Proxy's polling limits. Hands can set
`RocketMqWorker.Builder.controlCacheSize` independently of its command consumer cache.

During a rolling restart, take the host out of service, then call `runtime.drain(timeout)`
for Brain and `worker.drain(timeout)` for Hands before closing their adapters and stores.
Drain stops admission. The AgentScope adapter hands off an in-flight call at its next durable
boundary without pausing the Turn. The replacement Brain can continue from the saved position
subject to its persisted retry schedule.
The Brain stops pulling new dispatches and keeps active receipts renewable while draining.
The signal consumer and Outbox remain available until shutdown.
Hands also waits for pending result publications. Allow time for both drain and SDK close in
the deployment termination window. A replacement can recover work left by an expired lease.

Use `runtime.schedulingStatistics()` to export Turn scheduling metrics. Scan counts are
grouped by discovery source. Take the change in `total` over an interval to calculate scan
QPS. `emptyRatio()` divides empty scans by completed scans, excluding failures. Claim
counts distinguish acquired or recovered leases, empty queues, held leases, work that is
waiting or due later, and stale dispatches. Divide each outcome's change by the change
across all outcomes over the same interval to compute its rate. Claim errors have a
separate counter. The discovery latency histogram measures
milliseconds from a dispatch becoming eligible to a new lease being acquired, using the
storage clock. Its bucket counts are per bucket and the final bucket holds values above
the largest bound. Dispatches created before eligibility timestamps were recorded may
have no latency sample.

`activeDispatchDuplicates` counts exact duplicates of active or already queued dispatches
on that Runtime. `scheduler.statistics()` reports Turn outbox repair scans, confirmed repair
publications, publications skipped by the reservation check, and publication failures.
The counters are process-local and use a fixed set of labels without Session or Turn IDs.
Treat a source or outcome that has never occurred as zero.


## Choose a deployment adapter

Applications select a complete backend. Storage-provider SPIs are only needed for
custom integrations.

`LocalBackend.open(directory)` creates `directory/durable.db`, initializes SQLite
and wires process-local change notifications. Reopen the same directory to recover
the original sessions. No database server or broker is required.

For PostgreSQL, provide your existing DataSource:

```java
import io.agentscope.extensions.durable.jdbc.PostgresBackend;

// Deployment-time setup with a DDL-capable identity, before application startup:
PostgresBackend.initializeSchema(dataSource);

// Ordinary startup checks the existing schema; no DDL is issued by default.
var backend = PostgresBackend.builder()
        .dataSource(dataSource)
        .notifications(rocketMqWakeups) // Optional; defaults to process-local hints.
        .build();
```

Development deployments may instead opt into `initializeSchema(true)` on the builder.
The DataSource selects the database/schema and owns pooling. Both adapters return
`SessionBackend` and support the same Runtime, AgentScope factory and event callbacks.
Changing deployment adapters does not migrate existing data automatically.

Close subscriptions and Runtime before closing the backend, for example with
try-with-resources in that order of declaration: backend, Runtime, subscription.
A backend closes its own record-store access. PostgreSQL never closes a supplied
DataSource or notification transport. The host closes `RocketMqWakeups` and its SDK
clients after all runtimes/observers using them have stopped.

## Extend infrastructure

| Extension point | Contract |
| --- | --- |
| `SessionBackend` | State, commands, atomic checkpoints/intents/results, public snapshots and event replay |
| `RecordStore` | Storage-provider SPI for an atomic session record and authoritative storage time |
| `Wakeups` | Optional session-change hints; notifications never replace committed state or event replay |

Complete adapters assemble these pieces. Advanced hosts can still use
`TransactionalSessionBackend(recordStore)` and `backend.withWakeups(transport)`,
or implement the full `SessionBackend` for a different physical layout.
Preserve atomic checkpoints/intents and result/wake eligibility when changing storage.

All writers, Runtime and HTTP handlers must use the assembled backend. Remote writers
need the authoritative server configured too: attaching notifications to one HTTP client
does not observe other clients' writes. `wakeups()` is local wiring, not an HTTP operation.

Notifications follow successful commits. Notification/reporting failure cannot undo an
acknowledged write. Polling repairs hints lost between commit and publication or routed
to another consumer group member. Wakeup callbacks only schedule or signal work.
RocketMQ acknowledges after its local listeners return successfully; listener failure
allows redelivery, including to listeners that previously succeeded.

The composition references the storage/queue boundary in
[durable-harness](https://github.com/vercel-labs/durable-harness/blob/badd648db763e7d9121917b5c35732cd334e01be/packages/harness-world/src/session-log.ts);
Java retains its existing transaction and replay contracts.

## Recover tools and waits

Register external tools as schema-only native tools and map their names to Executors
registered on the Runtime. Native permissions run before the adapter commits a tool
intent. Native checkpoint and intent are saved together. A completed external result
wakes the original Turn; application is recorded with the native checkpoint containing it.

When a model asks for several tools in one step, approved Hands calls keep separate
execution IDs and may finish in any order. Once the batch is complete, the next model
call receives the results in the original tool-call order, including denials and failures.

Use `turn.getToolCall(callId)` for native calls and `turn.getToolExecution(executionId)`
for routed executions. A routed call can be looked up by either ID. The view reports
the last committed status, progress and result application. `DISPATCHED` records a handoff
attempt. `RUNNING` reflects a running observation. The original Run/Step and application
Run/Step can differ after a takeover.

The view includes the registered executor's capabilities once dispatch begins. For queued
executors, these describe the transport's controls. An outcome can stay `UNKNOWN` when the
provider cannot confirm it. When the view reports retained output, use
`turn.toolOutput(executionId, offset)` starting at zero. Continue from each page's
`nextOffset`. A gap identifies output that is unavailable.

`agentscope-extensions-durable-sandbox` supplies `DirectSandboxExecutor` for an existing,
pinned `HarnessSandbox` connection. Its POSIX/Python operation journal keeps detached
commands discoverable after the Agent process exits. `agentscope-durable-worker`
provides an independent execution ledger and Worker for separate capacity or isolation.
`DelegationExecutor` uses the same boundary for durable asynchronous child Agents.

An unverifiable remote outcome or cancellation remains `UNKNOWN`. Ordinary local
tools retain their native recovery limits. Credentials, environment provisioning and
retention remain application responsibilities.

Read `turn.pendingInteractions()` and call `turn.respond(requestId, interactionId,
response)`: boolean for approval, JSON for a manual external tool. The backend atomically
validates the Turn, interaction and response type, and rejects forged automatic Hands results.
`turn.steer`/`inject` apply at native safe boundaries; withdrawal races atomically with application.
Custom adapters can commit `WaitCondition(key, absoluteDeadlineMillis)`; zero means signal-only.
After observing PAUSED, use `turn.resume(requestId, expectedRevision)` with that state's revision.
It removes explicit pause only: outstanding approval, tool and signal conditions still apply,
and terminal tasks cannot restart. Generic extension signals require an `x.*` key.

### Tool policies and result hooks

Use `AgentScopeAdapter.withToolHooks(new AgentScopeToolHooks(version, timeout, policies,
normalizers))` to configure ordered policies and result normalizers for an adapter. Keep the hook
version and configuration consistent across hosts that can run the same pinned Agent definition.
Policies can replace input arguments, deny a call or require approval. The adapter freezes the
decision and effective arguments in the native checkpoint before approval or execution. Native
permissions still apply. A policy error or timeout denies the call.

Normalizers process completed local, Hands and manually supplied tool results before they enter
model context. The receipt stores the normalized content against the execution and raw result
digest, so recovery can reuse it. Hands keeps the original result in its execution ledger.
Normalizer failures use the Runtime's invocation recovery policy. Run policy and normalization
callbacks without external side effects.

For external delivery, create a `CommittedEventOutbox` with a Session handle, a stable
subscription ID, an event filter and a persistent `RecordStore`. Call `capture(limit)` to save an
event page and its cursor atomically. Call `deliver(receiver)` on an application worker. A failure
schedules a retry with the original event ID. Use that ID for receiver deduplication and keep the
delivery timeout within its configured lease. If the uncaptured history has expired, capture
reports cursor expiry so the application can choose its resynchronization policy.
To send HTTP webhooks, pass a `DurableWebhookPublisher` from the Durable HTTP extension to
`deliver`. It sends the event ID, type, resource IDs, sequence, cursor and commit time
with Standard Webhooks headers and an HMAC-SHA256 signature. Fetch the current
resource through the authorized API, or read event history after the receiver's
last applied cursor to catch up. Verify the signature and timestamp before
deduplicating by `webhook-id`, which stays stable across retries.
[Standard Webhooks verification](https://www.standardwebhooks.com/verify)

## Invocation failures and automatic recovery

For host replacement, run compatible Brain instances against the same persistent Backend.
After an owner's lease expires, another instance can claim its unfinished Thread and
continue from committed state. Hosts need the pinned Agent definition/state version,
tool bindings and access to the original workspace and external operations. PostgreSQL
can provide shared storage across hosts; separate container-local SQLite files cannot.
Fencing rejects stale owners' commits, while periodic scans recover missed wakeups.
No user resubmission is required. If every Brain stops, a process supervisor must restart one.

Runtime classifies invocation failures and records them through Backend. By default, I/O errors,
timeouts and internal UNAVAILABLE/CONFLICT failures receive bounded retries; unknown programming
errors do not. The AgentScope adapter uses Core's model-error classification: 429/5xx and network
failures are retryable, while definite client request errors are not. Model request retries and
Durable recovery through a new Run are separate layers with their own execution facts.

```java
import io.agentscope.durable.DurableTypes.RecoveryPolicy;

var runtime = DurableRuntime.builder()
        .backend(backend)
        .harness("agent", "v1", adapter)
        .recoveryPolicy(new RecoveryPolicy(3, 1000, 30000))
        .build();
```

This is also the default: at most 3 additional retries, exponential backoff starting at 1 second,
capped at 30 seconds. The first failure persists the policy, cumulative failure count and next
retry time on the Turn. Replacing Runtime, changing host configuration, pausing/resuming or sending
signals cannot reset that count or bypass backoff. `turn.status().recovery()` and `turn.recovery`
events expose recovery information; recovery is null before any recorded failure. The builder's
`retryableFailure` can customize host classification without authorizing repetition of UNKNOWN effects.

Retries require no user connection or manual signal. Permanent failures or exhausted budgets stop
new Agent invocations while existing tools are queried or cancelled by their original executionId.
UNKNOWN keeps the Turn WAITING until effects settle, then it becomes FAILED. `HarnessAdapter.settle`
unifies failed/cancelled settlement. AgentScope commits native cleanup, confirmed result application
and the terminal Turn atomically without starting a new Run, so the next Turn can reuse the cleaned
Thread context. Pause still takes
priority; cancellation bypasses backoff for settlement. Host shutdown, ownership loss and an
INCOMPATIBLE definition that another host may supply do not consume this failure budget. If Backend
cannot commit a failure record, its persistence is unconfirmed; a replacement host reconciles the
last valid state after lease expiry.

## Observe independently

Use Runtime-managed typed subscriptions for normal application integration:

```java
import io.agentscope.durable.EventStreamOptions;
import io.agentscope.durable.EventProjection;
import io.agentscope.durable.SessionStreamEvent;

var projection = new EventProjection(session);
var subscription = runtime.getSession(session).subscribeEvents(
        EventStreamOptions.defaults(),
        event -> {
            if (event instanceof SessionStreamEvent.SnapshotReset reset) {
                projection.reset(reset.snapshot());
            } else if (event instanceof SessionStreamEvent.Committed committed) {
                projection.accept(committed.event());
            }
        });
// No polling loop or observer executor setup is needed.
// Keep the subscription while connected; close it when the observer disconnects.
```

The default subscription replays retained committed history, then tails new events. To restore a
saved consumer position, pass `EventStreamOptions.builder().after(savedCursor).build()`; an expired
cursor emits a `SnapshotReset`. Enable deltas on the same options only when the connection renders
previews.

Creating or closing a Session/Thread produces persisted `session.created/closed` and
`thread.created/closed` events. Session creation also creates its primary Thread; identical retries
do not append another lifecycle event. `snapshot.projection.session` and `snapshot.projection.threads`
provide resource state at the same cursor. Replay agrees with direct queries, and snapshots retain
resource state after event pruning.

Use the same `EventFilter` for snapshots and events when observing a Thread, Turn or Run:

```java
import io.agentscope.durable.DurableTypes.EventFilter;

var filter = new EventFilter("main", turnId, runId);
var scopedProjection = new EventProjection(session, filter);
var scoped = runtime.getSession(session)
        .subscribeEvents(filter, EventStreamOptions.defaults(), event -> apply(event));
// Resume a saved cursor with EventStreamOptions.builder().after(savedCursor).build()
```

turnId requires threadId, and runId requires turnId; incorrect ownership is rejected.
`sessionHandle.listEvents(filter, cursor, limit)` and `snapshot(filter)` use the same scope,
as do Thread/Turn shortcuts. Turn snapshots exclude other tasks. Run snapshots retain the
parent Turn's logical state and select projection records currently associated with that Run.
A Thread filter retains only that Thread's resource view. Turn/Run filters exclude resource
lifecycle events and resource projections. Filtered snapshots have null `projection.session`;
explicitly call `view()` when current parent resource state is needed.
limit bounds scanned Session events: an empty filtered page can still advance its cursor.
Compare cursor with highWatermark to determine whether that page caught up. Filtered sequences
can be sparse; `EventProjection(session, filter)` accepts them while whole-Session projection
continues to check sequence continuity.

Callbacks run serially per subscription on dedicated observer workers, separate from
Agent execution. Session-change hints wake both `await()` and event subscriptions.
`observationInterval` controls periodic reads for missed hints. The default is two seconds
with notifications and 100 milliseconds for polling-only transports.
`observationRetryInterval` controls retries after a failed event read or callback.
Only successful callback return
advances the cursor. The Runtime `onError` handler receives failures; the subscription
exposes `cursor()`, `lastFailure()` and `isClosed()`. Failed error reporting does not
stop retries. `observerParallelism` controls shared callback capacity (default 2);
a slow callback can delay other observers at capacity, but not Agent execution.

Closing one subscription detaches it without stopping other observers or cancelling
a Turn. Closing Runtime closes all its subscriptions. A callback already executing
may finish; Runtime shutdown interrupts observer workers cooperatively. Subscriptions
begin immediately, independently of Runtime `start()`, which starts Agent scheduling.

For consumer restart recovery, persist the event/snapshot cursor with your projection
inside the callback. External side effects need idempotency because delivery is at
least once. `SessionEventClient` remains available for hosts needing manual polling
or an externally managed executor.

`turn.subscribeEvents(options, listener)` delivers committed events and opted-in deltas on the same
serialized observer worker. Only a `Committed` item advances `subscription.cursor()`; deltas are
not retried after disconnect and must not update a durable projection. A final
`message.completed` remains authoritative if a client missed some or all previews.

Rehydrate the Session with `runtime.getSession(session)`, then use
`mainThread().getTurn(turnId)` to access a task in the primary Thread. Queries/history/events never claim or execute
work. Snapshots contain Turn views and public projections; native checkpoints remain private.

The HTTP extension provides Backend/Executor clients and mountable handlers.
`SessionEventHandler` uses the same replay/acknowledgement client.
`SseEventWriter(OutputStream)` supports other Java HTTP servers. Failed writes/flushes
leave the event unacknowledged. Initial cursor expiry returns HTTP 410; expiry during
streaming emits `resync`, requiring a new snapshot with the same filter before reconnecting.
SSE queries accept threadId/turnId/runId, with Last-Event-ID taking precedence over cursor.
A `cursor` control frame advances the filtered scan position. `durable` frames are replayable
public events to pass to EventProjection; `live` frames carry previews and deliberately omit the
SSE `id`, so reconnecting with Last-Event-ID never acknowledges or requests them.
Hosts supply authentication, operation/session authorization and blocking-I/O threads.
Claims, native checkpoints, result intake and global discovery are privileged operations.

## Diagnose a task that is not advancing

Start with `turn.progress()`, or `getTurnProgress` over HTTP. The result contains
the current Turn, the Session cursor, whether a lease is held, and typed blockers
from one transaction. `QUEUE_HEAD` names the earlier Turn. `APPROVAL` and
`MANUAL_TOOL_RESULT` identify interactions for `turn.respond`. Use the observed
revision to resume a `PAUSED` Turn. Execution wait blockers identify the routed
execution, and `NATIVE_TOOL_RECONCILIATION` identifies the native call awaiting
host evidence. Timed blockers report their deadline or retry time.

The same read returns the most recently committed verification for each criterion
and subject version. A configured Harness verifier publishes
`task.verification.completed` with its outcome and pinned evidence version. The
verification view survives event retention. The Turn's execution status remains
independent of the verification outcome.

Inspect `turn.listRuns` and `turn.listSteps` for invocation details.

| Observation | What to check or do |
| --- | --- |
| QUEUED behind a WAITING or PAUSED Turn | Resolve or cancel the head Turn, or submit independent work to a different Thread |
| QUEUED with no execution | Confirm Brain called `start()`, has capacity, can reach storage and resolves the Thread's pinned definition |
| WAITING for approval or a manual result | Read the original interaction and respond through `turn.respond`; automatic Hands results use the Executor path |
| PAUSED after a tool result arrives | Explicitly resume with the observed revision when the user is ready |
| Recovery backoff | Read `turn.status().recovery()`; replacing a host or sending another signal does not reset retries |
| UNKNOWN external outcome or cancellation that does not finish | Query the original execution and verify its outcome; do not resubmit a side effect to make the queue advance |
| Observer disconnected or cursor expired | Reopen observation; restore from a snapshot when required, without resubmitting the task |

## Recovery limits

Threads pin Harness and native-state versions. Restarts need matching factories,
tool bindings and execution environments. A shared backend does not permit switching
native kernels. The supplied implementation is Java; other language integrations require
their own adapter and protocol conformance checks.

Recovery requires an active Runtime and accessible storage. Closing the Runtime does
not cancel durable work or close caller-owned backends/providers. If all hosts stop,
an external process manager must restart one.

The reference backend stores partitioned records within Session-scoped transactions.
Threads can execute concurrently, but their short state commits share that transaction
scope. Measure contention and storage growth for large histories or busy Sessions.
Recovery cannot recreate a deleted workspace or establish the outcome of an unqueryable
external effect. Validate provider query/cancel behavior and storage durability in the
deployment environment.


## Java platform integration

Agent factories return a fresh `ReActAgent.Builder` or `HarnessAgent.Builder`; the adapter injects the invocation's Thread Journal and builds the Agent. The public `Binding` exposes only the definition, `RuntimeContext` and Durable workspace, not journal storage. The adapter also binds AgentExecutionHost in RuntimeContext for boundary checks, durable input application and finalization recovery; no extra input middleware is required. Tool mappings route native tools through external execution while preserving native permission checks. Use `DelegationExecutor` for declared child Agent tools; resolve targets from the parent's frozen definition version.

Register multiple definition versions under one Harness name or use `resolver` to resolve each `ThreadSpec`. Existing Threads keep their pinned versions. Runtime discovers work through ready indexes, limits admission with `parallelism`, and supports `drain`: stop admission, wait for active execution, then stop this host on timeout so another host can recover.

Partitioned records commit lease checks, incremental native journal, checkpoints, tool intents and input receipts in one Session transaction. `CommitPolicy` lets hosts enforce policy against records in that transaction. Do not make external service calls inside this callback.

`LocalBackend` assembles SQLite, filesystem objects and local notifications. PostgreSQL accepts an `ObjectStore`. The `agentscope-extensions-durable-objects` extension supplies S3/MinIO and OSS stores with immutable session-scoped digest references and verified reads. SDK dependencies are optional; applications select the SDK and own its client lifecycle. Large checkpoints, journal entries and private resources use object references. HTTP provides bounded binary object IO; MQ endpoints can share an object store for large messages. Private bodies do not enter public events.

`binding.workspace()` implements Harness `BaseStore` using versioned Backend GET/SEARCH/PUT/CAS/DELETE calls, so Brain needs no database credentials. Wire `new RemoteFilesystem(binding.workspace(), List.of("workspace"))` into Harness. This store is already session-scoped; use stable relative namespaces. External shared files and Sandbox data belong to their mounts and are not automatically part of private checkpoints.

`AgentScopeCheckpoints.restore` accepts settled native baselines and copies the checkpoint's private resources. Fork rebinds native identities and requires the same namespace, Harness and definition/state versions. The target must be idle; an expected position prevents overwriting concurrent changes.

Completed model outcomes persist before state application and replay during recovery. Auxiliary model calls, Finalization and its workspace IO have stable receipts. Tool intents and result application still use native ThreadJournal boundaries. Arbitrary callbacks and unqueryable external effects are not promised exactly-once execution.

Hands separates commands, controls and execution events. Events have per-execution sequences; receivers advance only contiguous prefixes, deduplicate delivery and query missing events for replay. Output uses byte cursors and seals before a terminal result; explicit `gap` pages report truncation. SSE notifications remain hints to read persisted public events, separate from tool execution.

`BatchExecutor` persists child manifests and identities, queries original operations during recovery and stops admitting children after cancellation. The `McpExecutor` in `agentscope-extensions-durable-mcp` preserves MCP content and structured results. Ordinary MCP has no reliable operation lookup or cancellation confirmation, so uncertain effects remain UNKNOWN; the Worker ledger prevents blind retries.

The current source tree provides the Java implementation only; it contains no TypeScript/Pi client. A language-neutral protocol is not a claim of implemented cross-language clients.

## Pinned capabilities and verified UNKNOWN outcomes

The global capability view lists dynamic `resolvedHarnesses`. Use
`runtime.capabilities(definition)` to inspect one frozen definition without claiming work
or invoking a model. Static hosts discover work through an exact definition/state index,
so an unrelated version's backlog cannot block their Turns.

ExecutionStore provides a controlled host operations entry point. First check
`reconciliation(request, requestId)`. If no receipt exists, claim the original identity with
`claim(request, owner, lease)`, then submit an
`ExecutionReconciliation(requestId, verifiedTerminalResult, evidenceRef)` through
`reconcile(work, command)`. Only UNKNOWN can be resolved this way. The audit receipt and
ordinary result outbox commit atomically; missing output is sealed with an explicit gap.
Brain still receives and applies the result through the original path. Hosts must authorize
the operator and independently verify the fact. No HTTP/MQ command or model tool is exposed
automatically; requesting cancellation does not prove the operation stopped.

For native Harness tools, a dispatched call with an unknown outcome parks its Turn.
The same Thread's next Turn stays queued, even if cancellation is requested. After verifying
every unresolved call in that Turn, use the AgentScope adapter's `NativeToolReconciliation`
to submit the original call IDs and terminal results from an authorized host.

```java
var result = ToolResultBlock.text("verified output")
        .withIdAndName(callId, toolName)
        .withState(ToolResultState.SUCCESS);
new NativeToolReconciliation(requestId, evidenceRef, List.of(result))
        .submit(backend, threadKey, turnId);
```

Reuse the request ID and evidence when retrying submission. An acquired worker checks that
the evidence covers the unresolved calls, applies the verified results at the native checkpoint,
and continues the Turn or settles its pending cancellation. Resumption reuses the verified results
and runs only subsequent work.

The neutral protocol rejects duplicate JSON keys, multiple root values and numbers that
lose decimal precision when canonicalized. Encode exact large numbers as strings.
The Java Session storage layout is format 21. Prior development formats are not auto-migrated.

## Frozen tools on Brain and Hands

Use one `ToolBinding` catalog for the AgentScope adapter and Worker. A binding freezes the
schema, definition version, route and private target; the call carries its digest. Targets
hold credential references. Hosts resolve secrets and authorize the Worker boundary.

For native tools, create bindings with
`AgentScopeToolExecutor.binding(tool, version, route, target)`. Pass them to
`AgentScopeAdapter.forReAct(factory, bindings)` or `forHarness`, and register
`new AgentScopeToolExecutor(toolkit, bindings, timeout)` behind WorkerRuntime. The Brain
factory may use an empty Toolkit; the adapter registers schemas. Hands retains Toolkit
validation, preset parameters and default context. Keep registrations immutable while
the executor is in use. Brain permissions and host Worker authorization both apply.

Native content, metadata and explicit result states survive the handoff. Normal completed
returns use AgentScope's terminal-state interpretation. Invocation exceptions and empty or
suspended returns remain unconfirmed. `ToolCallParam.propagateErrors(true)` preserves
exceptions through Toolkit and annotated tools without enabling retries; default Toolkit
behavior is unchanged. Arbitrary Java methods cannot survive their Worker process and do
not gain operation lookup, confirmed cancellation or a durable progress stream.

`BatchExecutor` supports ORDERED and bounded PARALLEL modes. Its admitted mode, limit and
child identities survive replacement. Replayable output identifies the child call and
execution. Hosts authorize each child in the frozen manifest.

## MCP connections and explicit recovery

`McpConnections` owns a bounded pool of clients, including initializing and retiring
connections. Borrow a Lease for the entire discovery or call. Configuration rotation
retires the old client after its last borrower returns; idle clients are evicted. Closing
the pool rejects new borrowers while active leases can finish. Scope connection identities
by tenant and server. Credential resolution remains with the host.

Freeze discovery with `McpExecutor.binding(alias, version, route, server, tool, catalog)`.
Use `new McpExecutor(binding, resolver, timeout)`; the resolver may return
`McpExecutor.Target.leased(pool.borrow(identity, resolvedConfiguration), remoteTool, arguments)`.
Connection configuration supports streamable_http or sse transport, headers/queryParams,
ISO-8601 timeout and initializationTimeout, and propagateMeta. Do not persist resolved
credentials in bindings.

Ordinary MCP has no guaranteed operation lookup or cancellation confirmation. An optional
Durable extension is advertised in tool `_meta["io.agentscope.durable/execution"]`, with
version 1, queryTool, optional cancelTool and an explicit idempotentStart flag. The query
descriptor must declare readOnlyHint. Discovery freezes the main and control descriptors.
Calls carry a stable execution identity and request digest; control results must match
that identity and the shared JSON Schema. Invalid observations remain UNKNOWN. Cancellation
admission is distinct from confirmed termination. Existing operations can use unchanged
control descriptors after the main tool changes. The complete wire contract is in the
source repository's `agentscope-durable/protocol/README.md`.

## Persistent Sandbox bindings

The optional `SandboxBindings` component in `agentscope-extensions-durable-sandbox`
manages existing `SandboxClient` / `Sandbox` providers. A binding contains a namespace,
stable resource key, definition version and frozen configuration references. Sessions in
the same namespace can share a host-authorized binding; calls retain independent execution
identities and frozen `ToolBinding` digests.

```java
var local = new LocalSandboxClient(Path.of("./data/sandboxes"));
var workspace = new WorkspaceSpec();
workspace.getEntries().put("README.txt", new FileEntry("Durable workspace"));
var environment = new SandboxBindings.Binding(
        "demo", "workspace-1", "v1", DurableJson.value(Map.of("provider", "local")));
var sandboxes = SandboxBindings.builder()
        .stateStore(records)
        .resolver(binding -> local.target(workspace))
        .canDelete(binding -> allRelatedExecutionsSettled(binding))
        .build();
sandboxes.start();
Executor executor = sandboxes.executor(environment, shellBinding);
```

`records` is the host's RecordStore; `shellBinding` is a frozen tool whose private target
contains `operation: "sandbox.exec"`. Other operations are `fs.upload`, `fs.download` and
`artifact.publish`. Register the Executor directly or behind WorkerRuntime/RocketMqWorker.
The resolver only resolves configuration/credentials and constructs a Target, without
creating a resource. Cloud providers can use `Target.using(client, workspace, options,
physicalIdentity)` with `withKeepAlive` and `withDestroy`. Resume must attach to the original
instance without reconstructing its workspace. Destroy must be idempotent and confirm absence.

Creation identity is persisted before provider creation. Lost responses never cause automatic
replacement. Known connection state survives initialization failures; reattachment verifies
physical identity and a workspace marker. Unresolved creation requires host-authorized
`reconcileCreation` with original state and independently verified evidence; it neither creates
a resource nor writes a replacement marker. Use `protect(encrypt, decrypt)` to encrypt connection
state; the default stores it directly. Keep plaintext secrets out of binding configuration.

`requestDeletion(binding)` persists intent and rejects new starts. Cleanup waits for the host's
barrier covering **all executions and sessions sharing the binding**, then retries idempotent
destruction under a lease. The default barrier refuses deletion. Query/cancel remain available
while executions settle. `close()` stops maintenance and drops cached handles without destroying
resources. Persistent keepalive discovery survives cache eviction and host replacement.

LocalSandboxClient is an explicit development option requiring POSIX shell and Python 3.
Commands run with host permissions. Inline FileEntry/DirEntry and environment variables are
staged once; replica processes must share the original filesystem. Snapshot restoration,
unsupported entries and recreation of missing directories are rejected. Use a cloud Sandbox
for isolation and independent resource lifetime.

Worker `drain` stops admission and waits for active control calls, not detached tool lifetimes.
RocketMqWorker additionally drains admitted MQ callbacks and the event outbox. Drain before
close, then close borrowed SDK clients and stores. Forced close interrupts owned threads,
releases queued capacity and waits up to five seconds; it does not cancel external effects.
Host callbacks must honor interruption or use bounded IO. A callback closing its own host
cannot wait for itself.


## Run the examples

Java examples live in `agentscope-examples/durable`, under the Examples Maven
aggregator. Durable's `durable-standalone` profile also includes them for standalone
builds and checks. Each scenario has its own launcher at
`agentscope-examples/durable/{integrated|brain-hands}/{scenario}/run.sh`.
Integrated scenarios are quickstart, http-server and tool-in-sandbox; brain-hands scenarios are
echo-tool and tool-in-sandbox. Run from the repository root:

```sh
bash agentscope-examples/durable/integrated/quickstart/run.sh
bash agentscope-examples/durable/integrated/http-server/run.sh
bash agentscope-examples/durable/integrated/tool-in-sandbox/run.sh
bash agentscope-examples/durable/brain-hands/echo-tool/run.sh --compile-only
bash agentscope-examples/durable/brain-hands/tool-in-sandbox/run.sh --compile-only
```

The integrated tool-in-sandbox launcher runs sequential `start` and `resume` JVMs, each
colocating Brain and Hands. Each brain-hands module packages `${artifact}-brain.jar` and
`${artifact}-hands.jar` with separate Java main classes and runners, without a Java role
argument or enum. Its launcher accepts `<brain|hands> <endpoint> [data-directory]` to
select the JAR; `worker` is not an alias. Start Hands first, then Brain in another terminal.

Java 17+ and Maven are required. Use `make -C agentscope-durable check` for normal checks
and `make -C agentscope-durable independent` for isolated dependency checking, which also
requires Python 3. Real queued execution needs RocketMQ 5 services and configured topics/groups;
`--compile-only` packages the applications without connecting to a broker or running tools.

## Run the Durable acceptance tests

Run the functional baseline from the repository root after changing Durable.

```sh
make -C agentscope-durable acceptance
```

The baseline exercises the Java and HTTP user APIs, session and thread isolation, turn queues,
steering and cancellation, approvals, checkpoint forks, SSE reconnect and replay, and Brain and
Hands recovery. The API restart test runs Durable's Worker and a local transport while a tool
call is active. The baseline uses temporary SQLite databases and scripted models and tools. It
runs without model keys or RocketMQ.

Run the RocketMQ extension suite when changing its topology, scheduling, or transport. It uses
a client test fixture to exercise failures such as lost replies, failed acknowledgements, and
duplicate deliveries.

```sh
make -C agentscope-durable acceptance-rocketmq
```

Set `DURABLE_TEST_JDBC_URL` with either command to run supported JDBC cases against a PostgreSQL
test database. The test account needs permission to create and drop schemas.

```sh
DURABLE_TEST_JDBC_URL='jdbc:postgresql://localhost:5432/durable_test?user=durable_test' \
  make -C agentscope-durable acceptance
```

## Durable child delegation

Use delegation for child work that must survive restarts, be observed independently
or accept follow-ups in a continuing context. Ordinary Core/Harness subagent calls do
not automatically create Durable child Threads. Route the declared tool to
`DelegationExecutor` to obtain persistent child scheduling and result handoff.

`DelegationExecutor` defaults to an isolated child Thread in the same Session, derived from the parent's
executionId. To reuse a child Agent's context across tasks, explicitly select its Thread in the resolver;
the ThreadBinding must match:

```java
var children = new DelegationExecutor(backend, request ->
        new DelegationTarget(
                new ThreadSpec(
                        new ThreadKey(request.session(), "research"),
                        new ThreadBinding("research", "v1", AgentScopeCheckpointCodec.STATE_VERSION)),
                DurableJson.value("research request")));
```

Register this Executor with Runtime and route the parent's delegation tool to it; register the child Agent
as an ordinary Harness. Thread creation, child Turn admission and the mapping share one Backend transaction.
Retries and replacement hosts read the frozen mapping without resolving another target or admitting a second
task. `TurnView.parent` records the parent's Thread/Turn/executionId and original Run/Step. The parent's tool
projection `data.child` and internal `ToolExecution.delegation` identify the corresponding child Thread/Turn.

Pausing a parent does not pause its child. Cancellation affects only the mapped child Turn, preserving other
tasks in a reused Thread. Cancellation before admission stores a tombstone; a later start cannot create work.
The transaction rejects cross-Session targets, the parent Thread itself and Thread dependency cycles formed
by unfinished delegations. `turn.delegated`, `tool.delegated` and `tool.delegation.cancel_requested` are common
public events; they do not replace the actual child result or its application by the parent.

### Detached jobs

Use `DelegatedJobExecutor` when the parent model should continue after admission. Register it on
the Brain with the Session backend, register the child Harness, and route the three tool schemas to
this executor. The start target uses `DelegationExecutor.childThread(request)` as its Thread key.

| Tool | Input and result |
| --- | --- |
| `start_job` | Application-defined child input. Returns a committed receipt with `jobId`, `child` and `status: ACCEPTED`. |
| `get_job` | `jobId`. Returns the current child status and its available output or error. |
| `await_job` | `jobId` and a positive `timeoutMillis`. Waits for the child's terminal outcome or reports that the wait expired. |

The job ID remains valid for later Turns on the originating Thread. A replacement Brain uses the
original wait deadline. Child completion commits the fact that makes the awaiting Turn ready.
The waiting tool's progress includes the Job ID and deadline in epoch milliseconds.
After admission, cancelling the caller or its wait leaves the Job running. To stop the Job,
cancel its child Turn using the reference returned in the receipt.

Publisher RuntimeExceptions represent unknown broker acceptance and use the bounded host recovery policy with the same call ID. Throw a DurableException with an explicit code for definite rejection. Reply consumers can use QueuedModelRequest.accept to persist replies and safely acknowledge late deliveries to completed or cancelling Turns. Duplicate provider billing after uncertain provider acceptance remains possible.

Workers start each generation with `QueuedModelRequest.begin(turn, callId, deliveryId)`.
Use a unique delivery ID for each replacement generation, and reuse it when retrying that
generation's begin command. Check its boolean result before calling the provider. A false
result means the call is sealed or the Turn has ended and the delivery can be acknowledged.
Pass the delivery ID to the attempt-aware `reply` and `failure` overloads. The backend assigns
begin commands a monotonic epoch inside its transaction. New attempts replace previously
displayed partial text on the original Turn. Old-attempt replies return an ignored receipt
and can be acknowledged, including conflicting late content. After receiving the ordered
terminal frame, the collector atomically seals the winning attempt before allowing tool dispatch.
A concurrent takeover forces collector recovery, and a sealed call rejects further takeovers.

The Throwable failure overload classifies provider HTTP 429 and 5xx, I/O failures and timeouts
as transient. Other HTTP statuses and unclassified errors are permanent. Workers can specify
retryability explicitly. Transient errors consume the persisted host retry budget and dispatch
a successor model call on the same Turn. Permanent errors retain the configured fallback
behavior. The queue collector emits the standard model request started, usage and ended events
through the core request observer. These events describe collector observation, and a collector
replacement starts a new observation of persisted replies.

Collectors subscribe to the configured backend wakeups before reading replies and unsubscribe on completion, failure or cancellation. Lost hints are repaired by bounded backoff polling up to one second. Beginning a worker generation keeps the Turn suspended until its first response arrives.

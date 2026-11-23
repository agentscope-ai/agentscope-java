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
summary.cancel("cancel-summary-1");
```

The summary waits behind the investigation in the primary Thread. A WAITING or PAUSED
head also blocks later Turns. `steer` and `inject` add input to the existing Turn at
a safe boundary; they do not interrupt a model mid-token or undo a dispatched tool.
`withdraw` can remove input only before it is applied. If completion wins a race with
a control, re-read the Turn rather than assuming the control took effect.

A control receipt confirms admission. Observe `signal.applied` or the resulting Turn
state to confirm processing. A queued Turn can be cancelled immediately. Cancellation
of active work requires settlement of external operations; accepted cancellation does
not prove a remote process has stopped.

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
| Thread | `checkpoints(after, limit)`, `restore(position, expectedPosition, requestId)`, `fork(position, targetThreadKey, requestId)` |

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
`ClaudeEventStreamSink` to `SessionEventStream.forEach`. `agentscope-durable-adapter-agui` maps the
same typed stream to the existing AG-UI 1.0 `AguiEvent` types through
`DurableAguiEventStreamSink`. These artifacts are event adapters, not complete hosted HTTP APIs;
authentication, resource routes and transport framing remain application concerns.

The default `LiveEvents.local()` bus connects producers and observers only inside one Runtime
assembly. A split Brain/API deployment must provide the same shared `LiveEvents` transport to both
sides when live previews are required. Durable execution, history and final results remain correct
when that transport is absent or loses an event.


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
Polling defaults to 100ms; Brain retries unconfirmed commands or queries admitted
work every 5s. Configure pollInterval/retryInterval as needed. Adapters internally
manage pending publications, inbox deduplication, repair and consumer callbacks.
Runtime and Worker close MQ clients created from ClientConfiguration. Stores, SessionBackend, and
tool providers remain host-owned.

The default `durable` namespace and `hands` route derive commands, controls, and updates topics plus
their consumer groups. Both sides require no physical topic configuration. Keeping controls separate
prevents execute backlog from delaying cancellation and query. Updates carry execution state and
results. Each capability pool has its own command routing and consumer group;
pool replicas share the group and ledger. Brain result consumers use another group,
whose replicas share Brain delivery records and SessionBackend. Separate deployments
with independent databases use separate namespaces.
The host provisions the derived topics/groups, ACLs, TLS, credentials and retention. Worker
authorization runs before ledger access.

Using [RocketMQ SimpleConsumer](https://rocketmq.apache.org/docs/featureBehavior/06consumertype/),
Hands ACKs after durable admission; Brain ACKs after inbox persistence and SessionBackend
acceptance. Broker ACK is not tool completion. Cancellation before execution leaves
a tombstone. Queries replay original state without creating tool effects.
Republishing saved results never reruns tools. Scans repair failed publication and
inbox-to-session commits.

ExecutionMessages and the Runtime-packaged schema define versions, request digests,
command/event IDs and source sequences. The inbox accepts a continuous prefix;
duplicates are idempotent and missing events are replayed before newer events apply.
Output pages carry byte offsets and explicit gaps, and are sealed before terminal
publication. The MQ Executor supports output reads; provider capabilities determine
whether bytes are available. Public SSE remains a SessionBackend projection.
Arbitrary external effects are not exactly-once; unqueryable outcomes remain UNKNOWN.

Wire envelopes are limited to 1 MiB. Configure the same accessible ObjectStore via
`objects(...)` on both MQ adapters to externalize larger envelopes. Object references
are scoped and digest-checked; inaccessible or invalid payloads are not acknowledged.

RocketMqWakeups remains a separate session/subscription hint transport. SSE reads
public SessionBackend events. MQ execution does not require RocketMqWakeups:
single-host Brain can use local hints, and persistent scanning also repairs
cross-process notification gaps.


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
Agent execution. Hints coalesce, polling repairs missed hints, and callback/backend
failures retry after the Runtime's `pollInterval`. Only successful callback return
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

Start with `turn.status()`, `turn.pendingInteractions()` and the Thread's ordered
`listTurns` result. Inspect `turn.listRuns` and `turn.listSteps` for invocation details;
a stopped Run is not necessarily a terminal Turn.

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

The neutral protocol rejects duplicate JSON keys, multiple root values and numbers that
lose decimal precision when canonicalized. Encode exact large numbers as strings.
The Java Session storage layout is format 8; prior development formats are not auto-migrated.

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

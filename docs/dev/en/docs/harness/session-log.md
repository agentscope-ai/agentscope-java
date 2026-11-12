---
title: Thread journals and recovery
description: Read messages and Run/Step facts, persist checkpoints and explicitly recover native calls; Durable schedules persistent tasks.
zh_link: /dev/zh/docs/harness/session-log
---

HarnessAgent records execution facts and checkpoints in a native Thread journal by default. This supports continuous conversations, history and explicit recovery. Use [Durable](/dev/en/docs/harness/durable) for background queues, pause, persistent controls and autonomous takeover. A journal does not start a task scheduler.

## Choose the invocation model

Direct `agent.call(input, ctx)` and `agent.streamEvents(input, ctx)` leave subscription and invocation lifetime to the application. Calls on the same Thread reuse context, while each new invocation gets a new Run. Native `turnId` may be absent; journaling does not create persistent tasks.

Background applications obtain Sessions through `runtime` and submit Turns on the primary or another Thread. Controls target the Turn; Run/Step describe actual execution. The following assumes a Runtime configured using the [Durable guide](/dev/en/docs/harness/durable):

```java
var session = runtime.getSession(sessionKey);
var turn = session.submit("request-1", "Compare proposals A and B.");
turn.steer("request-2", "Focus on operating costs.");
turn.pause("request-3");
```

A pause receipt acknowledges intent. After observing `PAUSED`, call `turn.resume(requestId, revision)` using that state's revision. Answer interactions with `turn.respond` and cancel tasks with `turn.cancel`. Queries, wait timeouts and SSE disconnection do not cancel durable tasks. Cancelling a native Agent subscription ends that invocation.

## Read one Thread's messages and execution facts

`ThreadKey(userId, agentId, sessionId, threadId)` identifies the context. Preserve this identity and its storage namespace. Threads in one Session have independent working state and journals. Applications coordinate concurrent direct calls on the same Thread; Durable provides persistent FIFO and ownership.

```java
import io.agentscope.core.agent.RuntimeContext;
import io.agentscope.core.journal.JournalProjection;
import io.agentscope.core.journal.ThreadHistory;

RuntimeContext ctx = RuntimeContext.builder()
        .userId("alice")
        .sessionId("conversation-001")
        .threadId("main")
        .build();

var journal = agent.threadJournal(ctx);
var transcript = ThreadHistory.transcript(journal);
transcript.messages().forEach(message ->
        System.out.println(message.getTextContent()));
var projection = JournalProjection.read(journal);
var events = journal.readAfter(0, 100);
```

Reads neither claim a writer nor repair state or invoke a model. Harness also supplies `threadTranscript(ctx)` and `inspectThread(ctx)`. Native queries and storage SPIs are blocking; WebFlux applications must schedule them on suitable blocking workers rather than network event loops.

`ThreadHistory.Transcript` contains complete messages and `asOfSeq`, not a public UI snapshot with task controls, partial text and tool progress. Durable applications restore the UI through Session/Thread/Turn `snapshot()`, `history()` and event APIs instead of inferring Turn state from native logs. See the runnable [recoverable chat example](/v2/en/blogs/best-practices/session-chat).

## Event envelope and reads

`AgentEvent` carries live invocation notifications; `JournalEvent` carries committed native facts. Live emission is not a persistence acknowledgment. Native `seq` increases within one Thread and is not interchangeable with Durable's Session-wide public cursor.

| Field | Meaning |
| --- | --- |
| `schemaVersion` | Current native event version: 2 |
| `eventId / seq` | Event identity and Thread-local commit ordering |
| `occurredAt / type` | Timestamp and native event type |
| `executionRunId / turnId` | Invocation and optional host-task correlation; state initialization invents no Run |
| `required / payloadJson` | Recovery requirement and frozen JSON payload; relevant payloads contain Step identity and phase |

| Event family | Contents |
| --- | --- |
| `run/start`, `run/end`, `run/output` | Invocation start, outcome and output |
| `step/start`, `step/phase`, `step/end` | Steps and continuation phases within a Run |
| `input/received`, `input/applied` | Invocation input reception and application |
| `model/dispatch`, `model/chunk`, `model/end` | Logical model calls/output; end retains last reported usage and observation scope |
| `model/request_started`, `model/request_usage`, `model/request_ended` | Independent attempt identity, provenance, partial usage and outcome at the shared request boundary |
| `tool/requested`, `tool/dispatch`, `action/end`, `tool/result` | Tool decisions, execution and results |
| `interaction/requested`, `interaction/resolved` | Native external requests and answers |
| `state/initialized`, `state/checkpoint`, `state/restored`, `recovery/applied` | Current-format initialization, state and reconciliation facts |

A normal Step includes a model decision and zero or more tool calls. A recovery Step may only apply an existing tool result. It belongs to the new Run and references its source Step. Native `run/end` does not declare a persistent Turn successful; Durable decides that after checking input and external-operation settlement.

`ModelUtils` observes inside retry through the call-scoped `ModelRequestObserver`, preserving each
transport attempt. Custom models outside this boundary report logical calls without inferred request
counts. Usage can commit before request completion; failure/loss cannot turn missing usage into zero.
Recording failures do not trigger provider retries, and committed-result replay creates no request.

## Storage and backend configuration

Harness defaults to `WorkspaceThreadJournalStore`, scoped by its Workspace Filesystem. POSIX local storage uses atomic file commits under `.agentscope-runtime/`; Windows uses `journal.sqlite3` in that directory. Remote storage uses a reserved BaseStore partition. Do not edit internal files or keys manually.

Override storage with `.threadJournalStore(store)`. Custom backends implement `ThreadJournalStore`; atomic storage implementations can supply `AtomicJournalStorage` and reuse `AtomicThreadJournal`. Preserve CAS, writer fencing, ordered/idempotent batches and export cursors. Filesystems expose this through `journalStorage(context)`; ordinary file reads/writes cannot implement atomic journal commits.

`ThreadJournalStore.list(ctx)` discovers Threads only within the caller's storage scope; SESSION isolation does not enumerate other Sessions. In-memory stores are process-local. Shared persistence alone provides neither background claiming nor autonomous recovery; use a Durable Backend for those capabilities.

The authoritative `head.json` carries the native journal format version, currently 2. Missing/unsupported versions and corruption fail explicitly without conversion, clearing or silently creating a fresh journal over old data. Recovery follows the committed chain rather than guessing committed segments through directory listing.

For state snapshots without execution history, use `.journalEnabled(false).stateStore(snapshotStore)`. Snapshot mode does not provide complete historical events. With journaling enabled, checkpoints are the native recovery authority. For Durable-managed execution, factories return Agent builders and the adapter installs the invocation Journal so it and the Backend commit state and facts together.

## Explicit recovery and tool reconciliation

Direct-call recovery uses the saved native phase, input and tool results. A new invocation after completion reuses context but starts fresh execution counters; continuation retains accumulated progress and provenance. This does not restore arbitrary Java stacks or automatically execute after restart.

`ThreadInteractions.pending(journal)` reads native pending requests; `continuationRequests` validates selected requests and returns their original Run/Step provenance. See the ordinary [Agent HITL APIs](/dev/en/docs/building-blocks/agent#human-in-the-loop). Durable interactions use their Turn's `respond`; applications must not bypass Backend by writing managed journals directly.

For uncertain tool outcomes, query the original operation and obtain real results before invoking `agent.reconcileToolOutcomes(ctx, outcomes, reason)` or `ThreadRecovery.reconcile`. Reconciliation commits results and checkpoint together while preserving execution provenance. Re-executing a side effect is not a way to discover an unknown result.

## Export records to another system

`ThreadTranscriptExport` exports message views. `JournalExporter` and `JournalExportSink` export native events. The persistent cursor advances only after sink acknowledgment; failures can be drained again, so destinations deduplicate by event identity.

Install a sink under `JournalExportSink.CONTEXT_KEY` in the invocation RuntimeContext to trigger export during execution. Applications still arrange redelivery after failure. Public SSE and business consumers use Durable events rather than exposing journals containing private state.

## Append application events

Obtain `InvocationRecorder.from(ctx)` inside the current tool or middleware invocation, call `record(type, payload)` and await its `Mono`. Diagnostic facts can use `append(type, payload, false)` followed by `flush()`. Do not write through a recorder after its invocation ends.

Register validation and state application through `JournalEventCodecRegistry` before executing or restoring recovery-relevant extension events. An unknown required type blocks recovery rather than being silently discarded. Application facts obey the same commit and writer checks.

## Host-managed iteration budgets

`AgentExecutionPosition` records accumulated logical reasoning progress separately from the current Run's Step index. Durable preserves the budget across Runs of one Turn; applying an existing tool result consumes no additional model reasoning iteration. A fresh direct call after completion starts a new budget.

## Host-managed finalization

Core's `AgentExecutionHost` cooperates with its host at reasoning, model, acting and finalization boundaries. The interface stays in Core and the Durable implementation in its adapter; Harness does not depend on Durable. When a final answer exists but finalization is unfinished, save the answer and continuation position before confirming pause so recovery does not regenerate it.

## Initialize, export state or fork

`ThreadCheckpoints` supplies current-format `initialize`, `exportSnapshot`, `exportState` and `fork`. The source must have no active writer; first reconcile uncertain tools and settle unfinished executions and interactions. `exportSnapshot` returns state and `asOfSeq`. `initialize(target, state, source, sourceSequence)` writes a provenance-bearing `state/initialized` into an empty target without inventing Run history.

Fork preserves valid working state while replacing destination user/session/thread identity. It cannot undo external effects or copy executable old tasks. Durable-managed Threads use public checkpoint/restore/fork APIs to retain task and ownership checks.

## Journal persistence scheduling

Commits use the bounded `agentscope-session-commit` executor rather than model-request threads. Each recorder serializes commits; concurrent `flush()` calls await their respective accepted prefixes, and cancelling one waiter does not cancel a shared commit. Commit or ownership failure stops the execution boundary instead of permitting new effects.

JVM properties `agentscope.journal.commitThreadCap`, `maintenanceThreadCap`, `leaseThreadCap` and `taskQueueCapacity` configure the corresponding lanes. Larger queues do not increase storage throughput; size workers against backend capacity. `ModelCallPolicy` supplies model admission hooks, without scheduling empty work when no hook is needed.

<span id="concepts-and-basic-apis" />
<span id="scenario-1-chat-queueing-and-restart-recovery" />
<span id="scenario-2-guide-execution-and-reconnect-the-frontend" />
<span id="scenario-3-answer-hitl-requests" />
<span id="scenario-4-continue-after-interruption" />
<span id="history-events-and-checkpoints" />
<span id="scenario-5-inspect-execution-export-records-or-change-storage" />
<span id="event-envelope-and-reads" />
<span id="shared-storage" />
<span id="custom-backends-and-backups" />
<span id="exporting-records" />
<span id="custom-events" />
<span id="migration-and-forking" />

# Durable implementation design

[中文](migration-plan.md)

This implements the four boundaries in chapter 2, public-event requirements in
section 4.3 and staged delivery in section 5.2 of the
[original proposal](https://aliyuque.antfin.com/ad3swf/gg3g1v/qecdop0f9bdu5irs).
The first import is preserved in `source-manifest.json`; it is not the current
architecture or a compatibility commitment.

## Runtime and native ownership

Runtime owns task admission, discovery, one active Run per Session, leases, external
execution recovery and cancellation. HarnessAdapter advances its native kernel
under that lease. AgentScope uses its own SessionRecorder/SessionProjection and
private SessionLog. Pi Durable uses its own MemoryStorage, commit preparation,
request deduplication and task recovery. Neither adapter forks the inference loop.

Factories and executor registries are host composition. They replace Brain's
platform model/catalog/environment builders. Session definitions pin Harness,
definition and native-state versions. Hosts sharing a backend skip Harness types
they do not serve. Unknown state formats and missing pinned versions never
silently start a fresh conversation.

A stable logical Run can have multiple execution attempts. Native run IDs change
on each AgentScope invocation; the durable run ID remains its logical turn ID.
A native SessionLog writer delegates to the outer lease instead of creating another
scheduler. Raw native events and private reasoning are not public transport events.

## Java protocol implementation

`DurableTypes` defines execution facts and recovery commands; `DurableMessages`
defines public input, output and artifact references. `DurableProtocol` validates the
JSON Schema shipped in the JAR: Backend and Executor operations have distinct request
and response schemas; the state machine validates commits and reserved event payloads.
Embedded and HTTP calls share Java interfaces and transaction semantics.

Frame CheckpointMetadata binds Harness, definition/state versions, position and digest.
Only native state changes advance that position. HarnessContext verifies the binding
before native recovery; missing, corrupt or incompatible state never starts a fresh
session silently. Null cannot erase an existing native checkpoint.

AgentScope converts neutral text/image/audio/video input into native messages.
Completed events and Run output share a public Message, excluding native reasoning and
private metadata. Projections retain complete messages, tool facts and artifact references
after event pruning. Changed nonterminal tool observations produce durable events;
RUNNING → UNKNOWN → RUNNING is not incorrectly deduplicated.

Capabilities distinguish recovery boundaries, event delivery, input content,
query/cancel/output, persistence and sharing. SQLite/PostgreSQL and HTTP access to both
inherit `SessionBackendContract`. These contracts do not depend on Managed Agents
configuration, tables or DTOs. The bilingual Durable pages document Java usage.

## Persistence and atomicity

`TransactionalSessionBackend` owns one state machine on `RecordStore`.
`JdbcRecordStore` supplies cross-process transactions and database time:
SQLite acquires its write lock before reading; PostgreSQL locks the record with
FOR UPDATE. Each Session currently occupies one JSON row. Worker executions use
separate rows and a separate state machine on the same storage SPI.

| Durable transaction | What becomes visible together |
| --- | --- |
| Admission | Request digest, original receipt, Run and work eligibility |
| Native commit | Checkpoint, tool intents, dispatch eligibility and public facts |
| Result intake | Frozen terminal result and immediate recovery eligibility |
| Native application | Accepted-result application marker and native checkpoint |
| Signal/cancel | Idempotent command, state change and recovery eligibility |
| Public projection | Event sequence, projection changes and snapshot cursor |

Request/commit IDs reject different content. Commit receipts are readable after
lease loss. An uncertain native commit queries its receipt and retries the same
command; unresolved outcomes stop further native writes until a new owner reloads
the authoritative state. No I/O runs inside transaction callbacks.

Ownership uses epoch, owner and storage-clock expiry. Every native write and
dispatch admission checks ownership. Result intake is a separately authorized
operation tied to an existing execution identity; it cannot invent new tools.
Terminal results are immutable. A cancellation admitted before dispatch prevents
the initial external call.

Wait keys, absolute deadlines and early signals are durable. Timer recovery scans
storage rather than relying on an in-memory callback. Stable child sessions and
admission receipts let RunExecutor coordinate asynchronous subagents without another
workflow database.

## Executor and Worker

The Runtime keeps logical tool intents and accepted/applied results. The optional
Worker keeps physical admission, provider attempts, original handles, cancellation
intent and frozen outcomes. These are different facts. Polling/query provides
durable result handoff without a distributed transaction or mandatory broker.

A first dispatch is recorded before invoking a provider. Replacement hosts query
the original execution ID/handle. A provider may retry a missing operation only
when it explicitly guarantees idempotent start. UNKNOWN remains pending; lease
expiry does not prove an external action failed. Cancellation completes only after
all external operations are terminal and the native adapter settles its state.

The Sandbox implementation adapts the source's detached operation journal and
process identity checks. It retains file/command/artifact operations, removes
platform workspace snapshot workflows, and syncs admission-directory entries before
launch. Its control process may exit while the remote runner continues. Missing
journals are not replay authorization. Worker/HTTP expose independent output byte
cursors and gaps; they do not turn stdout into confirmed public text deltas.

## Public events and transports

Committed events are the sole reliable source for replay and SSE. A snapshot and
its cursor are read in one transaction. Retention preserves the projection while
expiring old cursors. Clients resynchronize from snapshot instead of silently
jumping over missing events. SSE repeatedly reads this same log; there is no
history/live-bus handoff window. Disconnect only closes observation.

AgentScope publishes durable text deltas and completed messages with attempt and
revision metadata; a replacement attempt supersedes earlier partial text.
Pi publishes committed root assistant messages and excludes private reasoning.
Native positions, commit revisions, public sequence/cursors and tool byte offsets
remain separate.

HTTP handlers perform decode, authorization, delegate and response mapping. They
contain no second business state machine. Host applications own authentication,
tenant/namespace permissions, request policy and deployment. RocketMQ supplies
optional latency hints: a hint may be lost or duplicated, while backend polling
still finds durable work. It is not the authoritative Run or public-event ledger.

## Dependency direction and source extraction

```text
application / future Managed Agents composition
  -> Durable Runtime -> HarnessAdapter -> native Core/Harness or Pi
  -> SessionBackend -> RecordStore -> SQLite / PostgreSQL
  -> Executor -> direct Sandbox / child Run / HTTP Worker
                              WorkerRuntime -> ExecutionStore -> provider
```

No Durable build, test, classpath or execution uses Managed Agents artifacts,
source roots, fixtures, platform tables or services. The dependency closure formerly
coming from common is now neutral identities, receipts, transactions and execution
ledgers, rather than a renamed common module. Referenced implementation sources and
the adapted Sandbox program are recorded in [extraction-record.json](extraction-record.json).

The original platform protocol, Spring/JPA/catalog wiring, Brain applications and
inactive Rust/Codex host were removed. Codex and ordinary Pi are not advertised as
supported kernels. The current second kernel is Pi Durable 1.4.0. Optional Context,
MCP-specific executors, cloud object stores and more database dialects remain
separate provider work, not hidden dependencies.

## Acceptance and limits

The [verification record](../verification/verification.en.md) distinguishes contract tests, real
process failures, PostgreSQL tests and real provider acceptance. The Java and Pi
adapters share the HTTP/state-machine contract, not native checkpoint formats.

The implementation is usable from an independent host. It is not a claim of cloud
production certification: real E2B/AgentRun account acceptance, real RocketMQ broker
failure tests, throughput/cost benchmarks and retention/compaction sizing remain
explicit release gates. Large native histories are presently stored inline, and
Pi has completed-message events rather than reliable incremental text.

Managed Agents adoption is the next round. Agent Service will compose backend and
public routes; Brain will compose Runtime/factories; Tool Worker will compose
WorkerRuntime/providers. Gateway, tenant auth, quotas, credentials, resource pools,
catalog, billing and operations stay on the platform. The detailed
[platform plan](platform-boundaries.en.md) remains the future adaptation inventory.

# Brain, Session Service and Hands protocol design

[中文](brain-session-hands-protocol.md)

Date: 2026-10-28. Protocol and initial runtimes are implemented. Field contracts live in [protocol](../../agent-harness-runtime/protocol/README.en.md); actual support and remaining acceptance are tracked in the [implementation map](../../agent-harness-runtime/protocol/implementation-map.en.md).

See [multilanguage Brain layout and deployment](brain-runtime-layout-and-deployment.en.md) for directories, languages and processes. Session Service initially lives in a separate package inside Agent Service, sharing its image and JVM; the platform Session Store is its commit core. Each deployment selects one Harness. Cross-Harness delegation and state migration are outside scope.

AgentScope commits and native storage use the prepared service profile; PI uses the generic protocol. Hands keeps its existing reliable MQ transport. Codex is gated before claim until its durability requirements are verified.

## 1. Existing capabilities and required changes

| Area | Existing capability | Target change |
| --- | --- | --- |
| Execution commits | `SessionStore.commit / receipt / head`, idempotent receipts, ownership checks, object preparation and database commits | Cross-language APIs committing native state together with explicit platform changes |
| Native interpretation | `SessionCommit` is converted back to AgentScope `SessionEvent`; storage recognizes native events | Move interpretation into Adapters; Session Service must not depend on native event formats |
| Tool dispatch | Platform transactions associate tasks, recovery references and Outbox, followed by reliable delivery | Preserve guarantees; explicitly associate native calls with multiple platform operations and remove Java type coupling |
| Hands execution | Independent execution records, durable admission, original-execution lookup, result Outbox and uncertainty handling | Retain Hands ownership; specify command, result, query and cancellation contracts |
| Results and recovery | Atomic result/durable-wakeup handling and AgentScope-specific reinjection/recovery | Move tool-result consumption into Session Service; expose generic recovery data, with native application owned by Adapters |
| Public events | Reliable projections and SSE cursors separated from live previews | Retain projection delivery; do not reuse native log positions as public sequences |

Code evidence: [SessionStore](../../agent-harness-runtime/java/adapter-agentscope/src/main/java/io/agentcore/managed/server/runtime/managed/SessionStore.java), [native commit conversion](../../agent-harness-runtime/java/adapter-agentscope/src/main/java/io/agentcore/managed/server/runtime/managed/ManagedSessionCommitAdapter.java), [commit core](../../agent-service/src/main/java/io/agentcore/managed/server/runtime/managed/SessionCommitTransactions.java), [Hands store](../../tool-worker/src/main/java/io/agentcore/managed/server/runtime/managed/ManagedToolWorkerStore.java). This was a source review; tests were not rerun for this documentation change.

## 2. Interfaces and ownership

Initially use **internal HTTP/JSON APIs and existing MQ command/event paths**. Authenticate callers and authorize scope access; tenant, owner or executionToken fields do not substitute for authorization. Brains do not directly implement platform database transactions.

| Interface / message | Direction | Inputs, outputs and constraints |
| --- | --- | --- |
| `claimExecution` | Brain → Session Service | Scope, Turn, instance identity, stable claimId and scheduling/Resume identity → owner, epoch, executionToken and expiry; retrying one claim must not mint multiple owners; capacity and takeover eligibility still apply |
| `renewExecution` | Brain → Session Service | Original owner and execution credentials → renewed expiry or ownership loss; retain lease/fencing guarantees without turning each renewal into a checkpoint commit |
| `deferDelivery` | Brain → Session Service | Original Ready/Resume identity and backoff → durable deferral receipt; ACK only afterward, without releasing a running owner |
| `loadRecovery` | Brain → Session Service | Scope and Turn → `RecoveryBundle`; reads do not grant write authority; execution requires valid ownership |
| `putNativeObject / readNativeObject` | Adapter through Runtime → Session Service | Native bytes, format and digest → `ObjectRef`; scope-isolated access; uploading does not commit execution |
| `commitBoundary` | Brain → Session Service | `CommitBoundary` → `CommitReceipt`; return success only after durable commit, never mere acceptance |
| `getCommitReceipt` | Brain → Session Service | Scope and commitId → original receipt or not found; query/retry the original request after timeout, without creating another tool execution |
| `ExecuteTool / CancelTool / ReplayToolEvents` | Session Service Outbox → MQ → Hands | Immutable tool identity/configuration, cancellation or output retransmission request; replay retransmits original output and cannot start a tool |
| `ToolEvent` | Hands Outbox → MQ → Session Service | Output, progress, gaps or final results under an independent tool sequence; ACK durable receipt, schedule Resume only when result completeness and wait conditions permit |
| `getToolState` | Brain → Session Service | taskId and executionId → accepted result or observed state; Hands queries the original external execution when needed; a platform cache miss does not prove nonexecution |
| `Resume` | Session Service Outbox → MQ → Brain | Stable resumeId, Turn, suspensionId and original suspension version; validate applicability, then durably take over or defer |

Platform APIs continue to admit inputs, interaction answers, cancellation and withdrawal. These changes share the commit core's consistency rules. Platform APIs retain SSE; do not create another public Brain event service.

**Hands retains its execution store.** Session Service owns platform authorization, result acceptance and wakeups; Hands owns external actions, handles, execution state and result delivery. Stable identities link the two sides without cross-service database transactions.

## 3. Data formats: shared handoffs, native state

### 3.1 Identities, versions and references

| Data | Proposed fields and rules |
| --- | --- |
| Message header | `protocolVersion, messageId, kind, scope`; Turn messages add turnId, while Session Ready binds a Turn at claim time; tracing stays in transport metadata and is not a deduplication key |
| Scope | `tenant, namespace, sessionId`; validate caller permissions and resource ownership server-side |
| Ownership | `owner, epoch, executionToken`; retain existing incarnation and lease rules; separate these from logical tool identity |
| Tool association | `nativeCallId ↔ operationId/taskId/executionId`; one native call may have multiple operations; batches require explicit membership; redelivery/recovery retains executionId |
| Object reference | `backendId, key, sha256, size, formatVersion`, with scope validated server-side; stable references contain neither temporary signed URLs nor credentials |
| Separate positions | `brainRevision` tracks Brain commits; Adapters interpret `nativeCursor`; `publicSequence` tracks public events. Tool output uses its own `sourceSeq` |

Cross-language encoding baseline: IDs are strings; counters, epochs and sequences that may exceed JavaScript's safe integer range use decimal strings; native content is bytes or object references. Arrays are ordered, required collections may be empty, and omitting an optional field means no update. Clearing state requires an explicit operation rather than language-dependent null handling.

Keep `protocolVersion` separate from native `stateFormatVersion` and existing projection schemas. Reject unsupported versions or unknown required operations rather than silently dropping fields. Request digests require shared canonical encoding. Freeze field coverage, number/Unicode encoding and tool-ID derivation only with Java/TypeScript input/output fixtures; do not hash each language's default JSON serialization independently.

### 3.2 Execution commits

See [commit.schema.json](../../agent-harness-runtime/protocol/schemas/commit.schema.json) for complete fields and [tool-handoff.json](../../agent-harness-runtime/protocol/examples/tool-handoff.json) for synthetic fixtures with real digests. Version 0.1.0 uses structured owners, scope-bound references, nativeCallIds arrays and inline/ref content wrappers; the duplicate draft-1 JSON example has been removed.

| Commit section | Contents and validation |
| --- | --- |
| `nativeState` | Checkpoint and/or journal references, native format and cursor; a recoverable extension of the committed prefix, not necessarily a full snapshot on every commit |
| `toolCalls / toolOperations` | Native call records and platform execution intents; validate stable identity, arguments, association and recovery basis; changed arguments under the same identity conflict |
| `inputApplications` | Input ID, applied/rejected status, reason and native application position; an applied receipt must reference state that recovers the input |
| `resultApplications` | nativeCallId, applied resultIds and native application position; commit only once the results are recoverable, not merely received |
| `modelUpdates / interactionUpdates` | Model call identity, budget reservation/settlement and result references; interaction requests and answer associations. Preserve existing model/interaction capabilities without duplicate accounting when removing native interpretation |
| `suspension / terminal` | Suspension ID, pending tasks/actions and recovery phase, or terminal state and output references; suspension requires consistent recovery state and completion checks outstanding work |
| `publicFacts` | Public changes with stable fact IDs; Adapters convert native events, Session Service produces operation results; deduplicate by ownership instead of emitting the same fact twice |

`CommitReceipt = {commitId, contentHash, outcome: committed, brainRevision, nativeCursor}`. Repeated commits return the original receipt without advancing versions again; conflicting content under one ID is rejected. Old receipts remain queryable after an epoch change, without restoring the old owner's write authority.

The new `brainRevision` controls concurrency of Brain recovery positions only; Hands output and public projections do not advance it. Concurrent withdrawal, cancellation and tool results still require authoritative transaction-time validation; the version alone is insufficient.

Do not add three independent `InputApplied / ToolAuthorized / ToolResultApplied` event journals. Input/result application fields express the corresponding facts; task/Outbox commits express tool authorization. These changes may share a commit with native state.

### 3.3 Tool messages, recovery data and errors

| Structure | Minimum business content |
| --- | --- |
| `ExecuteTool` | commandId, taskId, executionId, native association, toolName, arguments or reference, sealed configuration reference and authorization commit reference; logical identity excludes the current Brain's physical lease |
| `CancelTool / ReplayToolEvents` | commandId and original taskId/executionId; cancellation reason or replay starting position. An early control message cannot create a new execution |
| `ToolEvent` | eventId, taskId, executionId, sourceSeq, type and data; retain output/progress/gap/final-result meanings and reject conflicting content under one identity |
| Final result | resultId, taskId, executionId, `succeeded/failed/cancelled`, result or reference, artifact references and output endpoint/gap information; `unknown` is an observation, not a confirmed terminal result |
| `RecoveryBundle` | snapshotId, brainRevision, native recovery references, pending inputs/application receipts, native-call/operation associations, accepted results/application records, interaction/model recovery information, current suspension and terminal state |

Build `RecoveryBundle` from a consistent metadata snapshot. Large objects and pages use stable references from that snapshot, not unrelated reads assembled into recovery state. Results may arrive afterward; incrementally refresh or reload by version rather than treating notifications as durable data.

Errors share `{code, message, retryable, retryAfterMs?}`:

- Lost ownership: stop writes and local engine driving; a valid owner takes over.
- Version conflict: reload recovery data, never overwrite blindly.
- Commit/result content conflict, invalid references or unsupported versions: reject and diagnose, without bypassing the conflict through a fresh ID.
- Capacity or transient unavailability: retry with backoff; after commit timeout query the original receipt. Not found does not establish that an external action never happened.
- Unknown tool outcome: retain the original execution for recovery, rather than converting it into an automatically retryable failure.

## 4. Commit and ACK boundaries

```mermaid
sequenceDiagram
    participant B as Brain / Adapter
    participant S as Session Service
    participant H as Hands
    B->>S: Prepare native objects; commit recovery references and tool intents
    S->>S: Atomically record recovery position, task, Outbox and receipt
    S-->>B: committed
    S-->>H: Deliver ExecuteTool through MQ after commit
    H->>H: Persist admission and claim; execute after confirmed ACK
    H->>H: Persist result and result Outbox
    H-->>S: ToolEvent
    S->>S: Durable receipt; accept complete result and eligible wakeup
    S-->>B: Notification / Resume
    B->>B: Engine applies original result
    B->>S: Commit updated native position and resultApplications
```

**Tool call guarantees authorization only with a recoverable basis.** Native logs may precede authorization; objects, database and MQ do not share a transaction. Once a recoverable native prefix exists, the platform transaction commits its reference, task, Outbox and receipt together. An Adapter must not let a tool bypass Hands after a failed commit.

**Execute guarantees durable identity and recovery of the original execution, not exactly-once arbitrary external actions.** A starting record cannot commit atomically with an external effect. An action may happen before its handle/result is stored, leaving an unknown outcome. Retry is safe only when the provider explicitly supports idempotent start with stable identity.

**Result guarantees have two layers: durable receipt and recoverable native application.** Session Service accepts results without waiting for the engine to apply them. Brain does not treat notification as application. Results remain readable by a replacement Brain while unapplied.

| Handoff | Confirmation condition | What confirmation does not establish |
| --- | --- | --- |
| `commitBoundary` response | Referenced objects are available; metadata and platform changes committed | Enqueuing in memory or returning accepted is insufficient |
| Execute MQ ACK | Hands durably admits and claims within capacity; normal delivery prepares/starts external execution only after ACK confirmation | The tool is not necessarily complete; Hands retains durable recovery responsibility |
| ToolEvent MQ ACK | Event durably received and deduplicated; out-of-order events may be durably buffered | Output need not be contiguous, the result accepted, or the engine updated. Distinguish existing buffered and committed outcomes |
| Final-result acceptance | Output coverage/gap contract is satisfied; result and any eligible Resume Outbox commit together | A successful transient Redis notification cannot replace durable wakeup |
| Resume / Ready ACK | Durable claim or durable deferral of the same intent; validated stale/duplicate messages may finish | Receiving a message or starting a local Future is insufficient; preserve channel-specific claim semantics |
| Projection MQ ACK | Query-store facts form a visible contiguous prefix with validated identity | Does not wait for the browser to read SSE; previews do not participate in execution commits |

Native storage methods must also honor their promised durability before returning committed. Do not acknowledge locally and persist asynchronously to save remote calls. Internal records may be combined without crossing required barriers such as tool execution, input application confirmation or finalization.

## 5. Recovery and uncertainty

A replacement Brain obtains valid ownership, reads a consistent `RecoveryBundle`, invokes native recovery through its Adapter, reconciles original tools/result applications, and continues or suspends again. Brain exit does not prove Hands stopped. A fenced Brain stops local driving and child processes without automatically cancelling valid Hands tasks.

| Crash or race | Recovery action |
| --- | --- |
| Object uploaded, commit unsuccessful | No tool authorization; the unreferenced object is not authoritative; recover from committed state |
| Commit succeeded, response lost | Query/retry the original commitId and verify content without generating another operation |
| Authorization committed, MQ delivery pending | Republish the original Outbox command with stable commandId/executionId |
| Hands admitted, external outcome uncertain | Query the original executionId; retry only with idempotent start, otherwise retain unknown. NOT_FOUND alone does not prove nonexecution |
| Brain replaced, original tool completes later | Accept a valid original task/result identity despite Brain epoch changes; still reject conflicting outcomes |
| Result arrives before suspension | Suspension transaction rechecks result/input readiness and creates the required wakeup |
| Result accepted, not yet applied by engine | Read and reinject the original result by nativeCallId; commit native position/application association without rerunning the tool |
| Native result persisted, platform application receipt uncertain | Query the original commit first; reconcile separate storage paths by stable result ID and native record, then complete the association. Stop if uncertain rather than append unconditionally |
| Output reordering, gaps or lost ACK | Deduplicate, complete coverage or record an explicit gap, then accept terminal state under the contract; durable buffering is not a complete result |
| Cancellation races completion | Cancellation is a request, not fabricated cancelled state; retain confirmed original results and never restart the original tool after cancellation |

## 6. Adapter admission and Durable Harness lessons

Adapters must demonstrate stable call identity, native storage integration, interception before execution, persistence confirmation, result reinjection/application-position recognition, recovery entry points and stopping after ownership loss. Native formats remain engine-owned; the protocol carries opaque references and explicit platform associations.

Where native storage supports complete commits, commit platform changes and native references together. For file/log-only external processes, establish a verifiable persistence barrier before authorizing Hands. Tailing native logs without blocking execution beforehand cannot provide full reliable execution. Explicitly reject unsupported modes instead of assuming parity merely because an Adapter exists.

Durable Harness's [tool bridge](https://github.com/vercel-labs/durable-harness/blob/ea8aa4060cf4fb8cd39737ce8ab21c4e0f40c741/packages/harness-process/src/tools.ts) associates effects with stable callIds and waits for the native journal barrier before non-replayable tools; [input placement](https://github.com/vercel-labs/durable-harness/blob/ea8aa4060cf4fb8cd39737ce8ab21c4e0f40c741/packages/harness-process/src/inbox.ts) durably records placement before sending to the engine. Borrow these handoffs without requiring matching platform event names.

The [PI durable storage integration](https://github.com/vercel-labs/durable-harness/blob/ea8aa4060cf4fb8cd39737ce8ab21c4e0f40c741/packages/pi-world/README.md) preserves PI's own recovery: safe tools can rerun; other interrupted tools produce interrupted results. Hands can continue independently here. The PI Adapter must demonstrate association/waiting for original tasks and reinjection of original results; Brain process loss cannot automatically imply interruption or rerun. Codex also needs tool handoff/reinjection validation; RPC connectivity is not capability acceptance.

## 7. Work packages, implementation order and acceptance

These are proposed work packages, not assigned people or completed deliverables:

| Work package | Deliverables | Dependencies |
| --- | --- | --- |
| Protocol and shared cases | APIs, schemas, errors, identity/digest rules and positive/negative examples under `agent-harness-runtime/protocol/`; behavior cases under `testing/conformance/` | Joint agreement by Session Service, Hands and Brain implementers; no independently invented fields or deduplication rules |
| Session Service | `sessionservice/api` and `store` inside Agent Service; claims, commits, recovery reads, result consumption, Outbox and wakeups | Preserve SQL guarantees, remove native interpretation and return durable receipts |
| Hands | Reuse admission/execution storage; map commands/events, queries/cancellation, replay and unknown handling | Execute under original executionId, maintain output sequence, never interpret native Context |
| Java Runtime / AgentScope Adapter | Move `ManagedMqSessionLog` and native checkpoint/tool recovery interpretation into the Adapter; Runtime handles coordination and platform clients | Java becomes the first complete reference; retain model, budget, interaction and cancellation capabilities |
| TypeScript Runtime / PI | Implement the same service protocol and native PI storage/tool integration; verify original-task recovery for PI durable | Reuse protocol/failure fixtures rather than SQL transactions |
| Codex Adapter | Verify pre-execution interception, stable IDs, log durability and reinjection; manage child processes | Reuse TypeScript Runtime and promise support only after capability acceptance |

Implementation order:

1. **Develop against the 0.1.0 baseline.** APIs, schemas, identity/digest fixtures, model/interaction/input structures, limits and error mappings now live under protocol and pass offline validation. Update contracts and fixtures jointly when changes are needed. Database, MQ and real-engine fault acceptance remain unimplemented; static fixtures do not establish runtime guarantees.
2. **Refactor the Java reference path.** Move native interpretation into the Adapter and drive the commit core with explicit platform changes. Retain behavior tests before large directory moves.
3. **Expose Session Service.** Move it into Agent Service, expose remote APIs and migrate tool-result consumption. Route Java Brain through the complete remote path first, validating lost responses and ownership changes. Preserve one authoritative commit/consumer implementation during cutover instead of maintaining two transaction engines.
4. **Integrate in parallel.** Hands adopts the frozen contract; PI completes execution/wait/recovery; Codex validates capabilities first. Finish with separate Brain images and failure tests against real dependencies.

Acceptance must cover:

- Repeated commits do not reauthorize; conflicting content under one ID is rejected.
- Duplicate commands recover the original execution; uncertain external effects are not blindly rerun.
- Results before suspension and concurrent results produce correct recovery intents.
- Old Brains cannot add commits, while valid late results from original Hands executions remain acceptable.
- Crashes between result receipt and native application lose neither results nor execution identity and do not append native results twice.
- Reordered output, gaps and lost ACKs converge; cancellation never fabricates external outcomes.
- Java/TypeScript agree on encoding, identity, digests, errors and version fixtures.
- Input application/withdrawal races, model results/budgets, interaction recovery, finalization and public projections retain existing capabilities.

Existing [tool reliability tests](../../agent-service/src/test/java/io/agentcore/managed/server/runtime/managed/ManagedToolWorkerReliabilityTest.java) cover result/Resume rollback, reordered results, late results and stable message identity. Promote those semantics into cross-language acceptance; this does not claim the new protocol already passes.

## 8. Document boundaries

This document owns protocol rationale, commit/ACK boundaries, recovery and work division. Executable fields/examples are maintained in [protocol](../../agent-harness-runtime/protocol/README.en.md); the [layout/deployment design](brain-runtime-layout-and-deployment.en.md) owns modules, languages and processes. Detailed existing mechanisms remain in [current Session Store](../session-store.en.md), [commit design](../../../docs/session-store-oss-and-direct-commit-design.en.md) and [execution/projection design](../../../docs/execution-commit-and-projection-delivery-redesign.en.md). Version 0.1.0 is a development baseline, not implemented runtime APIs or a production protocol cutover.

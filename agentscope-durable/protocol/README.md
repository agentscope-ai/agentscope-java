# Durable v1 Protocol

This directory defines the language-neutral contract for Durable user operations,
persistent execution, public messages and events, and communication between Brain
and Hands. Brain coordinates native agent execution; Hands performs independently
queryable tool work. The protocol does not require Managed Agents, a particular
message broker, or AgentScope-native Java objects.

The current wire version is `durable.v1`. Durable is under active development:
implementations must follow the current schema and reject incompatible versions.
Historical development formats are not migrated or supported through fallback paths.

## Start here

- Application and SDK authors: read [Public operations](#public-operations), then the
  [public interaction contract](public-interaction-contract.en.md) for exact lifecycle
  and control semantics.
- Backend and execution-host authors: read [Backend operations](#backend-operations)
  and [Commits and recovery](#commits-and-recovery).
- Tool Worker and transport authors: read [Executor operations](#executor-operations),
  [Queued execution](#queued-execution), and [MCP recovery](#mcp-recovery).
- Independent implementations: use the [shared fixtures](#authoritative-files-and-packaging)
  and [verification guidance](#verification) to check wire and behavioral conformance.

See the [library README](../README.md) for modules, assembly, and build instructions,
and the [examples](../../agentscope-examples/durable/README.md) for runnable applications.
The [Java usage guide](../../docs/dev/en/docs/harness/durable.md) explains task controls,
deployment choices and recovery limits.

## Authoritative files and packaging

| File | Purpose |
| --- | --- |
| [schema.json](schema.json) | JSON Schema 2020-12 definitions for requests, responses, domain records, checkpoints, public events, and execution messages |
| [vectors.json](vectors.json) | Canonical digests, execution identities, valid contract values, and invalid JSON or number cases |
| [projection-vector.json](projection-vector.json) | Message/tool projection and replay, including sequences above JavaScript's safe integer range |
| [user-workflows.json](user-workflows.json) | Shared user-operation and lifecycle fixtures |
| [public-interaction-contract.en.md](public-interaction-contract.en.md) | Detailed public operations, controls, state conditions, messages, and event semantics |

This directory is not a Maven module. The
[durable-runtime POM](../durable-runtime/pom.xml) packages the four JSON files under
`durable-protocol/` in the `agentscope-durable-runtime` JAR. Markdown files are
documentation only. Java loads the schema from that classpath location; its
`$id` identifies the schema and does not require a network fetch.

The schema defines accepted shapes; implementations must also enforce transaction,
identity, ownership, and lifecycle rules. Passing structural validation alone does
not prove that a commit, cancellation, or replay is correct.

## Boundaries and Java bindings

| Boundary | Responsibility | Java entry points |
| --- | --- | --- |
| Public user API | Session/Thread/Turn operations, controls, history, and observation | `DurableRuntime` and its Session/Thread/Turn handles; `DurableProtocol.Endpoint.USER` |
| Backend | Durable facts, admission, leases, atomic commits, checkpoints, and replay | `SessionBackend`; `Endpoint.BACKEND` |
| Native Harness | Interpretation and restoration of native agent state | `HarnessAdapter` |
| Executor | Independently queryable side effects, cancellation, and output | `Executor`; `Endpoint.EXECUTOR` |
| Queued Brain–Hands transport | Immutable execution commands and result/output delivery | `ExecutionMessages`, `QueuedExecutor`, and Worker components |

`DurableTypes` defines execution and storage records. `DurableMessages` defines
typed Input, Message, Content, Source, and Artifact records. `DurableProtocol`
defines and validates request, response, and error envelopes.

`DurableRuntime` is the Java assembly, business-operation, and lifecycle entry
point. An API-only Runtime can serve requests without an Agent, model, Executor,
or local task-claiming loop. Execution hosts explicitly start their configured
Brain loop. Closing a Runtime, subscription, or Future does not cancel durable work.

Public handlers must not expose Backend claim/commit operations, private Frames,
native checkpoints, or result-write APIs. Hosts authorize namespace and Session
access; fork requires authorization for both source and target. A cursor or
execution identity is not an access credential.

## Resource and execution model

| Resource | Meaning and scope |
| --- | --- |
| Session | Identified by `{namespace, sessionId}`; owns metadata, lifecycle, and a Thread directory |
| Thread | Identified by `{session, threadId}`; owns context, FIFO admission, checkpoints, and an execution lease |
| Turn | A persistent logical task admitted to one Thread |
| Run | One native invocation within a Turn |
| Step | One execution step within a Run |
| Execution | One independently tracked external tool operation |
| Message | A public conversation item with a stable logical message ID |

Session creation atomically creates the primary Thread, `main`. Each Thread pins
a `ThreadBinding` containing Harness, definition version, and state version.
Different Threads in one Session can execute concurrently; a waiting or paused
head Turn blocks later work in the same Thread.

Sessions and Threads are `OPEN` or `CLOSED`. Nonterminal work prevents closure.
Closing does not erase history, and retrying creation does not reopen a closed
resource. Turn terminal states are `SUCCEEDED`, `FAILED`, and `CANCELLED`.
Recovery preserves `turnId` while creating new Run/Step identities.

## Envelopes and validation

JSON RPC operations use HTTP POST to the endpoint configured by the host. The
protocol does not prescribe one deployment URL. USER, BACKEND, and EXECUTOR have
separate operation allowlists even when operation names coincide.

A public submission uses this complete request envelope:

```json
{
  "version": "durable.v1",
  "operation": "submit",
  "payload": {
    "session": {"namespace": "demo", "sessionId": "session-1"},
    "threadId": "main",
    "requestId": "submit-1",
    "input": {
      "schemaVersion": "1",
      "content": [{"type": "text", "text": "Summarize the latest report."}]
    }
  }
}
```

The Session must already exist. A successful admission returns a receipt, not the
completed agent response:

```json
{
  "version": "durable.v1",
  "result": {
    "thread": {
      "session": {"namespace": "demo", "sessionId": "session-1"},
      "threadId": "main"
    },
    "requestId": "submit-1",
    "turnId": "turn-1"
  }
}
```

Errors use the same version with a code and message on a non-200 HTTP response:

```json
{
  "version": "durable.v1",
  "code": "CONFLICT",
  "message": "Request identity conflicts with previously admitted content"
}
```

Protocol objects reject unknown fields, unsupported operations, missing required
fields, and incorrect types. Required nullable fields must be present as `null`;
do not replace them with omission or empty values unless the schema permits it.
Extension data is allowed only where the schema explicitly accepts it.

The HTTP JSON request limit is 16 MiB. Public and Executor requests exceeding this
limit are rejected. The Backend transport can transfer oversized requests through
its private object-store path and `invokeObject`; this is not a public upload API.
Native state remains private regardless of its transport representation.

### Errors and uncertain outcomes

| Code | Default HTTP status | Client action |
| --- | --- | --- |
| `INVALID` | 400 | Correct the payload or operation; unchanged retries cannot fix validation |
| `INCOMPATIBLE` | 400 | Use the supported protocol, definition, and state versions |
| `NOT_FOUND` | 404 | Check the requested resource or receipt identity |
| `CONFLICT` | 409 | Re-read state and resolve a content or lifecycle conflict |
| `FENCED` | 409 | Stop using the stale lease; ownership must be acquired again |
| `CURSOR_EXPIRED` | 410 | Replace the local projection with a snapshot and resume from its cursor |
| `UNAVAILABLE` | 503 | Treat write outcome as unconfirmed and resolve it using the original identity |

The HTTP handlers return 403 for authorization failures. Transport failures,
lost responses, and malformed success responses do not prove that a write failed.
Keep the same request, commit, or execution identity. Query the corresponding
receipt/state where available, or repeat the same idempotent command; generating
a new identity can create duplicate work.

## Identity and canonical JSON

Admission and public controls deduplicate within Session + `requestId`, including
the target Thread, Turn, operation, and content. An identical retry returns the
original receipt; changed content under the same identity conflicts. Creation is
also idempotent for identical identities and parameters.

Canonical digests use SHA-256 over RFC 8785 canonical JSON of
`{"value": command}`. Arrays retain order. JSON must contain exactly one root
value and no duplicate object keys. Numbers must survive canonical IEEE-754
serialization without changing their decimal value. Represent exact integers
or decimals outside that range as strings; do not round them before hashing.

An external tool execution ID is `exec_` followed by the canonical digest of
`[thread, turnId, callId]`. Its complete frozen request determines the request
digest. Retrying, redelivering, or recovering an operation must preserve both
identity and content.

Public event sequences and checkpoint positions are decimal strings; event and
list cursors are opaque. Native journal positions, public event sequences, Hands
source sequences, and tool-output byte offsets belong to different domains and
must never be substituted for each other.

## Public operations

The following operations belong to the USER endpoint. Payload summaries omit the
common envelope; consult `userRequest` and the operation-specific definitions in
[schema.json](schema.json) for exact required fields and nullability.

| Operations | Payload and result |
| --- | --- |
| `capabilities` | Empty payload; returns protocol version, public operations, registered definitions, and checkpoint Harnesses |
| `createSession` | `session, definition, metadata`; returns SessionView and creates `main` atomically |
| `getSession / closeSession` | `session`; returns SessionView |
| `listSessions` | `namespace, cursor, limit`; returns SessionPage |
| `createThread` | `session, threadId, definition`; returns ThreadView with pinned versions |
| `getThread / closeThread` | `session, threadId`; returns ThreadView |
| `listThreads` | `session, cursor, limit`; returns ThreadPage |
| `submit` | `session, threadId, requestId, input`; returns TurnReceipt |
| `getTurn / listTurns` | Thread scope plus `turnId` or `cursor, limit`; returns TurnView or TurnPage |
| `history` | `session, threadId, cursor, limit`; returns projected public message history |
| `getRun / listRuns` | Thread/Turn scope plus `runId` or pagination; returns RunView or RunPage |
| `getStep / listSteps` | Thread/Turn/Run scope plus `stepId` or pagination; returns StepView or StepPage |
| `getModelCall / listModelCalls` | Thread/Turn/Run scope plus `modelCallId` or nullable `stepId, cursor, limit`; returns model-call diagnostics |
| `control` | `session, threadId, command`; returns a CommandReceipt |
| `interactions` | `session, threadId, turnId`; returns pending interactions |
| `snapshot / events` | `session` and optional `filter`; events also takes `cursor, limit` |
| `checkpoints` | `session, threadId, after, limit`; lists checkpoint metadata |
| `restore` | `session, threadId, position, expectedPosition, requestId`; returns CommitReceipt |
| `fork` | `session, threadId, position, target, requestId`; returns the target ThreadView |

Public list limits are 1–1000. Cursors bind their query scope; pagination is not a
cross-page snapshot. Public requests always identify the target Thread explicitly.
Java Session convenience methods target `main`.

Controls cover `STEER`, `INJECT`, `WITHDRAW`, `RESPOND`, `CANCEL`, `PAUSE`,
`RESUME`, and extension `SIGNAL`. A control receipt acknowledges admission;
subsequent events confirm application, pause, or terminal outcome. A cancellation
request does not prove that a dispatched side effect stopped. Pause preserves
in-flight Hands work and durable results; resume checks the original waiting
conditions again. See the [public interaction contract](public-interaction-contract.en.md)
for state preconditions and race semantics.

Restore and fork require valid, settled checkpoints and compatible bindings.
Fork inherits the source binding and stays within the same namespace. A
cross-Session fork requires the target Session to exist first.

## Backend operations

The BACKEND endpoint is privileged infrastructure. These operations include
private state and execution authority and must not be exposed as public user RPC.

| Operations | Purpose |
| --- | --- |
| `capabilities` | Report actual persistence and sharing guarantees |
| `create / session / closeSession` | Create, read, and close Session resources |
| `createThread / thread / threads / closeThread` | Manage Thread resources |
| `sessions / listSessions` | Internal storage-key discovery / namespace-scoped public listing |
| `submit / admission` | Admit work and query the original Session-scoped receipt |
| `turn / turns / history / interactions / control / withdraw / cancel / signal` | Task reads and transactional controls |
| `ready` | Select runnable work for supported Harness/version bindings |
| `claim / renew / release` | Manage fenced Thread ownership; durations and delays are milliseconds |
| `prepareRun / run / runs / step / steps` | Prepare native invocations and read invocation history |
| `modelCall / modelCalls` | Read model-call provenance and usage |
| `frame / journal` | Read private recovery state under a lease |
| `commit / receipt / recordFailure` | Atomically commit progress, resolve commit receipts, and record recovery failures |
| `execution / beginDispatch / acceptResult / acceptExecutionEvent` | Read persisted intent, fence dispatch, and accept execution results/events |
| `delegate / delegation / cancelDelegation` | Atomically admit child work, retain its mapping, and persist cancellation |
| `snapshot / events / pruneEvents` | Read public projections and replay, or prune retained events |
| `checkpoints / checkpoint / checkpointJournal / checkpointResources / restore` | Inspect and restore retained recovery boundaries |
| `resource` | Resolve recovery resources under fenced access |
| `putObject / readObject / invokeObject` | Transfer private objects and invoke referenced request envelopes |

A `HarnessSelector` contains `harness, definitionVersion, stateVersion`.
Static registrations select an exact version pair before limiting results.
Both versions may be null only for a resolver that accepts that Harness's
definitions. Unsupported work must not be converted into `runtime.error` merely
because a particular host cannot execute it.

`runtime.executionCapabilities()` reports Backend, Executor, static Harness,
and dynamic resolver availability. `runtime.capabilities(definition)` validates
a pinned definition without claiming work or invoking a model. SQLite provides
local-host durable storage; PostgreSQL supports shared storage across hosts.
`HttpSessionBackend` forwards the server's guarantees. Custom RecordStores must
declare persistence and sharing explicitly.

## Commits and recovery

A Backend commit carries `commitId`, `expectedRevision`, structured Run/Step
transitions, checkpoint state, tool intents, applied results, consumed signals,
events, and Turn state/output/error. The Backend must atomically persist the
checkpoint and its associated intents and facts under a valid fenced lease.
A lost reply is resolved using the original commit receipt.

`ToolCall.origin` is an explicit nullable `{runId, stepId}` position. It enters
the request digest and remains unchanged during recovery. `appliedResults`
contains `{executionId, appliedAt}` records committed with the checkpoint.
An active invocation requires an application position; host settlement outside
an invocation uses null. Result acceptance and native application are separate
facts, and application may occur in a later Run.

A non-null checkpoint is bound by `Frame.checkpointMetadata` to schema version
`1`, Harness, definition version, state version, a decimal-string position, and
its canonical digest. The position advances only when native state changes.
Recovery validates the binding and digest before interpreting native state.
A null checkpoint cannot erase committed state. Missing or incompatible metadata
and corrupt state are rejected.

AgentScope declares `TOOL_BOUNDARY` recovery and `DELTA` event delivery.
Recovery operates on native journal boundaries, not on a serialized Java stack.
Private Frames and native checkpoints never appear in public snapshots.

The current Java transactional Session layout is format **14**. The Worker
ledger and queued transport layouts are format **3**. These are internal storage
formats, distinct from the `durable.v1` wire version; old unreleased layouts are
rejected rather than silently upgraded.

## Public content, events, and replay

AgentScope accepts a plain string or structured
`{"schemaVersion":"1","content":[...]}` input. Content supports text, image,
audio, and video. Media sources are URLs with optional MIME type, or base64 data
with a required MIME type. Arbitrary native objects are not accepted as prompts.

The final AgentScope Turn output is a public Message containing schema version,
message ID, role, and content. Native reasoning and private metadata are excluded.
Artifact events publish references; URI access, storage, and retention remain
host responsibilities, and publishing a reference does not upload a file.

Public event families include:

- Session/Thread lifecycle and Turn admission, waiting, controls, recovery, and completion.
- Run/Step lifecycle and model-call/request diagnostics.
- Signal acceptance/application/withdrawal and interaction request/resolution.
- `message.delta` and `message.completed`.
- Tool argument generation, call updates/progress, dispatch, output, result acceptance,
  and result application.
- `artifact.published` and `checkpoint.restored`.

Reserved events have schema-constrained payloads. Application events use
`x.<namespace>.<name>` with an object payload and cannot replace core lifecycle
facts. Model-call diagnostics retain observed request identities and usage;
missing usage stays unknown, and the records do not expose raw prompts or private reasoning.

Snapshots contain Session information, Turns, projection, sequence, and cursor.
The sequence is a string, including `"0"`. A projection resets from the snapshot,
ignores already applied sequences, and resynchronizes when replay continuity is lost.

Messages are keyed by `messageId`. Newer attempt/revision replaces older partial
presentation; completion replaces accumulated deltas, and late deltas cannot
modify the completed version. Distinct reasoning rounds produce distinct assistant
messages within one Turn. Logical IDs survive Run/Step changes. A new model request
increments attempt; replay of a committed response retains attempt and advances
revision when replacing partial presentation.

Tool views merge by `tool:` plus the canonical digest of
`[threadId, turnId, callId]`. Native tool facts and external execution facts share
one view; only external executions have `executionId`. Views retain their first
public sequence, available Run/Step links, original intent position, and result
application position. `UNKNOWN` progress never implies terminal success.

### SSE observation

The event handler accepts GET parameters `namespace`, `sessionId`, and `cursor`
on the host-configured route. `Last-Event-ID` takes precedence over the query
cursor. Each committed event uses its opaque cursor as the SSE `id` and
`durable` as the event name.

An expired cursor before streaming returns HTTP 410. Expiry during streaming
emits `resync` and closes the connection. Fetch a snapshot, replace the local
projection, and reconnect from its cursor. Connections periodically close to
bound resource use, so clients must reconnect. Heartbeats carry no durable
cursor; disconnecting does not cancel a Turn.

## Executor operations

| Operation | Payload | Meaning |
| --- | --- | --- |
| `capabilities` | Empty object | Report `idempotentStart`, `lookupByExecutionId`, `cancellation`, and `output` |
| `start` | Frozen `request`, optional nullable `handle` | Start or recover the same logical execution |
| `query` | Frozen `request`, optional nullable `handle` | Observe the original execution |
| `cancel` | Frozen `request`, optional nullable `handle` | Request cancellation and report the observed outcome |
| `output` | Frozen `request` and byte `offset` | Read an output page when supported |

Capabilities describe actual guarantees. `NOT_FOUND` is a query observation,
not a persisted execution outcome. An unconfirmed external outcome is `UNKNOWN`;
it must not be converted to failure or used as permission to repeat a side effect.

### Frozen tool bindings

`ToolBinding` contains the name, definition version, executor route, schema, and
private target. Its canonical digest is carried in `ToolCall.binding`; null is
reserved for direct, unbound Executors. Catalog-backed Workers verify the frozen
binding before dispatch. Brain schema registration and Hands target resolution
use the same definition. Private targets carry credential references rather than
resolved secrets.

### Queued execution

`executionCommand` and `executionEvent` define broker-neutral Brain–Hands
messages. `ExecutionMessages` validates their schema, canonical identity, and
request digest. Commands carry `EXECUTE`, `CANCEL`, or `QUERY` and the original
request. Command IDs derive from request digest, command kind, and replay position.

Events carry a positive safe-integer source sequence and either output or an
execution result. Event IDs derive from request digest and sequence. Events are
immutable: receivers persist out-of-order events but apply only a contiguous
prefix. `QUERY` requests replay after the acknowledged source sequence; it never
creates work. Delivery need not be FIFO.

Cancel-before-execute persists a cancellation tombstone. `NOT_FOUND` is not an
event outcome. Queued output must be sealed before the terminal result is applied.
Hands source sequences are not public SSE cursors.

### MCP recovery

MCP recovery is an opt-in Durable extension, not a guarantee of ordinary MCP.
A remote tool advertises this metadata under `_meta["io.agentscope.durable/execution"]`:

```json
{
  "version": 1,
  "idempotentStart": false,
  "queryTool": "execution_status",
  "cancelTool": "execution_cancel"
}
```

`cancelTool` is optional. The query tool must declare
`annotations.readOnlyHint=true`. Discovery freezes the main descriptor and all
control descriptors. New dispatch verifies them; existing operations can use
unchanged control descriptors even if the main tool changes.

Metadata propagation must be enabled. Start receives
`{version: 1, executionId, requestDigest}` under the same metadata key.
Query/cancel receive that identity as arguments and metadata. The external ID is
the canonical hash of `[namespace, durableExecutionId]`; the request digest hashes
the complete frozen ExecutionRequest. No callback URL or credentials are transmitted.

The remote service must reject the same identity with different input, preserve
operation identity across restarts, and retain records for its advertised recovery
period. `idempotentStart=true` additionally guarantees that repeated admission
cannot start another effect.

Start/query/cancel return a normal MCP CallToolResult whose `structuredContent`
conforms to `mcpExecutionObservation`. `SUCCEEDED` and `FAILED` require a nested
MCP result with content and matching `isError`; other states have no result.
Top-level MCP errors, wrong identities, invalid observations, and lost replies
leave the outcome unconfirmed. `NOT_FOUND` permits another start only under the
frozen idempotent-start guarantee. Pending cancellation remains `PENDING` or
`RUNNING` until the provider confirms a terminal outcome.

### Verified UNKNOWN reconciliation

`executionReconciliation` carries a stable request ID, a terminal ExecutionResult,
and an evidence reference. This is a host operations contract. The host verifies
the external fact and authorizes the operator, claims the original execution with
`ExecutionStore.claim(request, owner, lease)`, then calls `reconcile(work, command)`.

Only `UNKNOWN` can be settled through this path. Audit evidence, outcome, and
outbox commit together; missing output receives an explicit terminal gap before
the result. Identical request ID/content is idempotent; changed content conflicts.
After an uncertain reply, query `reconciliation(request, requestId)`. Evidence
references remain private to the Hands ledger. Result delivery and native
application continue through their normal paths.

## Verification

Run commands from the repository root with Java 17+ and Maven. These focused
checks validate the runtime schema, canonical vectors, projection replay, and
formatting:

```bash
mvn -T1 -f agentscope-durable/pom.xml -pl durable-runtime -am \
  -Dtest=DurableProtocolTest,EventProjectionTest \
  -Dsurefire.failIfNoSpecifiedTests=false test

mvn -T1 -f agentscope-durable/pom.xml -pl durable-runtime -am spotless:check
```

The Surefire option allows upstream modules without the named tests to participate;
confirm that both named test classes actually run. For HTTP boundaries and public
operations, run the existing HTTP extension tests:

```bash
mvn -T1 -f agentscope-durable/pom.xml -Pdurable-standalone \
  -pl :agentscope-extensions-durable-http -am test
```

For the broader Durable build and isolated dependency check, use the
[Makefile](../Makefile):

```bash
make -C agentscope-durable check
make -C agentscope-durable independent
```

Independent implementations should validate request/response shapes, reproduce
canonical identities from `vectors.json`, replay `projection-vector.json`, and
exercise `user-workflows.json`. Behavioral checks must also cover identical and
conflicting retries, stale leases, uncertain responses, result acceptance versus
application, event pruning/resynchronization, and out-of-order execution delivery.

Validate broker delivery, host replacement, provider query/cancel behavior and
output recovery in the deployment environment. Schema and fixture checks alone
do not establish those runtime guarantees.

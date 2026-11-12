# Durable public interaction contract: operations, messages and events

[中文](public-interaction-contract.md)

Executable formats are in [schema.json](schema.json); the
[Java usage guide](../../docs/dev/en/docs/harness/durable.md) covers application integration.
Only the current contract is supported: no old entry points, conversion, dual protocol or
compatibility paths. Reject version mismatches while preserving idempotency, fencing and recovery.

## 1. Shared concepts and lifecycle

The user API, Input/Message and PublicEvent/Snapshot form one language-neutral contract.
Java binds that contract; Future/Promise, callbacks and iterators can vary by language,
but operation scope, state conditions, identities, idempotency, events and errors cannot.

- SessionKey is namespace + sessionId. Session holds metadata, lifecycle and a Thread directory; creation atomically creates the `main` Thread.
- ThreadKey is SessionKey + threadId. ThreadBinding pins harness/definitionVersion/stateVersion. Each Thread owns its context, FIFO, checkpoints and execution lease; different Threads in one Session can run concurrently.
- Session / Thread lifecycle is OPEN/CLOSED. Nonterminal work prevents closure. A closed Session rejects new Threads, submissions and checkpoint restoration, while historical queries remain available.
- DurableTurn is a persistent logical task; Run is one native invocation, and each Step belongs to one Run.
  Native AgentRun shares runId with the persistent Run record. Recovery retains turnId and creates new runId/stepId.
  Pause persists intent; PAUSED confirms a safe boundary. Resume requires the observed revision.
- requestId identifies admission/control. turnId/runId/stepId/messageId/eventId/interactionId/executionId
  have distinct scopes and are not interchangeable.
- Session idle is derived from queue/Turn state. QUEUED/RUNNING/WAITING/PAUSING/PAUSED/CANCELLING are nonterminal;
  SUCCEEDED/FAILED/CANCELLED are terminal. TurnView.output is public JSON; AgentScope returns Message.
- DurableRuntime is Java's sole assembly, business-operation and lifecycle entry. It creates, gets
  and lists Sessions directly. Session/Thread/Turn handles retain rehydratable identities.
- API-only Runtime needs no Agent/model/Executor, scans or claims. Explicit start with execution
  configuration starts Brain; missing execution configuration fails. Closing Runtime/subscription/
  Future never cancels durable tasks or closes borrowed infrastructure.
- Managed Agents uses the same contract as other applications; Durable has no platform dependency.

Public requests, privileged Backend requests, Hands ExecutionMessages and native JournalEvent
are separate boundaries. Public facts are not commands to execute. MQ is not required for user requests.

## 2. Envelopes, scopes and operations

Protocol is `durable.v1`. Requests are `{version, operation, payload}`; success is
`{version, result}`; errors are `{version, code, message}`. One schema validates fields,
nulls, portable numbers and event types. Reject unknown fields/operations/reserved types,
version mismatches and duplicate JSON keys. RFC 8785 digests use [vectors.json](vectors.json);
never round a number before computing identity.

Public HTTP accepts only userRequest, never claim/commit/Frame/object contents/result writes.
All page limits are 1..1000; cursor is explicitly string or null. List cursors differ from
event cursors and bind query scope. Pagination is not a cross-page snapshot; newly admitted
entries can appear on subsequent pages.

| operation | payload (required unless schema says otherwise) | result |
| --- | --- | --- |
| capabilities | `{}` | protocolVersion, operations, registered definitions, checkpointHarnesses |
| createSession | session, definition (registered name), metadata (nullable) | SessionView; atomically creates the primary Thread and pins its binding |
| getSession / closeSession | session | SessionView; missing lookup is NOT_FOUND, never implicit creation |
| listSessions | namespace, cursor, limit | SessionPage(items, cursor), namespace-scoped |
| createThread | session, threadId, definition | ThreadView with pinned definition versions |
| getThread / closeThread | session, threadId | ThreadView |
| listThreads | session, cursor, limit | ThreadPage(items, cursor) |
| listTurns / history | session, threadId, cursor, limit | TurnPage / HistoryPage, Thread admission order / first-message order |
| submit | session, threadId, requestId, input | TurnReceipt(thread, requestId, turnId) |
| getTurn | session, threadId, turnId | TurnView |
| getRun / listRuns | session, threadId, turnId, runId / cursor, limit | RunView / RunPage; read-only invocation history |
| getStep / listSteps | session, threadId, turnId, runId, stepId / cursor, limit | StepView / StepPage scoped to the Run |
| getModelCall / listModelCalls | session, threadId, turnId, runId, modelCallId / nullable stepId, cursor, limit | ModelCallView / ModelCallPage; read-only request provenance and usage |
| control | session, threadId, command | CommandReceipt(requestId, turnId, operation, status, cursor) |
| interactions | session, threadId, turnId | Pending Interactions for that Turn |
| snapshot | session, optional filter | Scoped Snapshot consistent with sequence/cursor |
| events | session, cursor, limit, optional filter | EventPage(events, cursor, highWatermark) |
| checkpoints | session, threadId, after, limit | CheckpointMetadata list; after is a Thread checkpoint position, not event cursor |
| restore | session, threadId, position, expectedPosition, requestId | CommitReceipt |
| fork | session, threadId, position, target (ThreadKey), requestId | ThreadView; another Thread in the same namespace, inheriting the source binding |

Creation with identical identity/parameters is idempotent; changed definitions or Session metadata conflict.
Retrying creation after closure does not reopen the object. Admission and controls share Session + requestId
deduplication, including target Thread/Turn, operation and content in the digest; any change is CONFLICT.
SessionKey/threadId/turnId suffice for lookup from another process. Reads never claim/execute.
Session submit/call/stream are primary Thread conveniences, normalized to the same operation.
Session getTurn/listTurns/history also target the primary Thread by default. Callers obtain a
specific Thread handle before addressing any other Thread. Protocol requests always carry an
explicit threadId; Session conveniences do not change the storage scope.
Session/Thread creation needs registered definition/definitionResolver; existing lookup needs no local Agent.
Fork can create a target Thread in an existing Session, but never implicitly creates a Session;
create the target Session explicitly before a cross-Session fork.
Authorize namespace listings and every Session operation; fork
checks source and target separately. Cursors do not replace authorization.

Model calls are diagnostic records under Run/Step, with no new scheduling or control object.
`model.call.started/ended` describe logical calls; `model.request.started/usage/ended` describe
individually observed requests. They share the checkpoint/native-fact/public-event transaction.
Each transport retry has its own requestId and increasing attempt. Committed-result replay creates
no call or request; inspect the original Run. ModelCallView sums known token counts once per requestId,
including reported portions of failed requests. usageComplete requires a successful call and complete
input/output totals for every request. Missing counts stay null; details are subsets, never additional
tokens. These facts do not infer prices or final provider bills.
PROVIDER_ATTEMPTS observes the shared provider-request boundary. MODEL_CALL means a custom model
exposes only the logical call: an empty requests list does not mean no external request, and
usageComplete remains false. Opaque SDK retries are outside this boundary. Calls/requests lacking an
end fact become LOST in queries after their Run ends or loses ownership, retaining known usage
without fabricated completion times. Nullable stepId accommodates auxiliary calls outside a Step
without placeholder steps. List cursors bind Thread/Turn/Run/Step scope. These records/events expose
neither raw model input nor private reasoning.

## 3. Typed controls and state conditions

ControlCommand contains turnId, requestId, kind, target, value, expectedRevision.
Unused target/value are null; expectedRevision is 0 except RESUME. Java methods hide
these placeholders. Deduplicate Session + requestId using the target Thread and entire command
digest. Identical retries return the original receipt; changed content is CONFLICT.
Validation, signal, public input and receipt commit in one authoritative transaction.

| kind / Java method | target / value | Condition and effect |
| --- | --- | --- |
| STEER / `turn.steer(requestId, Input)` | null / Input | Active nonterminal Turn; instructions at the next native reasoning boundary |
| INJECT / `turn.inject(requestId, Input)` | null / Input | Same condition, context input; no system elevation or immediate model interruption |
| WITHDRAW / `turn.withdraw(requestId, signalId)` | Original control requestId / null | Existing unapplied signal; atomic race with apply, already applied is CONFLICT |
| CANCEL / `turn.cancel(requestId)` | null / null | QUEUED cancels immediately; active cancellation verifies outcome; terminal result stays unchanged |
| RESPOND / `turn.respond(requestId, interactionId, response)` | Interaction.id / JSON | Same Turn, pending, non-automatic; boolean confirmation or JSON external_execution |
| PAUSE / `turn.pause(requestId)` | null / null | Without an active owner, immediately PAUSED; active execution becomes PAUSING until a safe-boundary handoff |
| RESUME / `turn.resume(requestId, expectedRevision)` | null / null | Only PAUSED with the observed revision; unsatisfied tool/interaction/signal conditions remain WAITING; never reopens terminal work |
| SIGNAL / `turn.signal(requestId, key, value)` | `x.*` key / JSON | Extension only; cannot forge internal/input/interaction controls |

Except CANCEL and identical retries, terminal/cancelling controls conflict. A second
response with a different requestId also conflicts until the first is validly withdrawn.
User responses cannot overwrite automatic Worker results. Clients do not assemble internal
signal keys: Control.requestId is signalId; Interaction.id is the response target;
Interaction.requestId identifies the native request.

Receipt status=accepted acknowledges admission, with the Session cursor after that transaction.
signal.applied/withdrawn, interaction.resolved and Turn terminal events report subsequent facts.
A SUCCEEDED commit requires accepted steer/inject input to be applied or withdrawn. Input admission and success compete at the same transaction boundary: input admitted first cannot be discarded when an invocation returns normally, and a Turn completed first rejects new controls. AgentScope retains turnId and uses a new Run when more input needs processing.
Backend admission defines FIFO within each Thread: WAITING/PAUSING/PAUSED block later tasks. Queued cancellation need not wait for the head.
A PAUSE receipt acknowledges intent; observe PAUSED to confirm that the invocation stopped. Pause does not cancel
dispatched Hands operations. Tool results, signals and answers remain durable without automatically resuming.
RESUME no longer takes waitingKey or creates a wakeup signal: it removes explicit pause and checks the original
waiting conditions again. Pause and completion compete transactionally; cancellation prevents resume.

## 4. Operations, public messages and events

Input is `{schemaVersion, content[]}`; Message is `{schemaVersion, messageId, role, content[]}`.
Content supports text/image/audio/video with URL/base64 sources; a URL does not promise upload/access.
PublicEvent has schemaVersion/eventId/session/threadId/turnId/runId/stepId/sequence/cursor/type/data.
runId/stepId are null when inapplicable; task admission does not invent invocation identity, and Step events identify their Run. sequence is a decimal
string; cursor is opaque and Session-bound. Checkpoint positions, output byte offsets and Hands
sequences are separate. Public views never expose native checkpoints or reasoning state.

| Operation/fact | Public messages and events |
| --- | --- |
| submit | Atomic turn.queued + user message.completed, then started/waits/results/terminal outcome |
| steer / inject | signal.accepted + user message.completed; later applied or withdrawn |
| respond | Same, with interactionId in message data; later interaction.resolved |
| cancel | turn.cancel_requested; queued cancelled in the same transaction, active cancellation verifies real outcome |
| pause / resume | turn.pause_requested, turn.paused, turn.resumed; no user chat message |
| extension signal | signal.accepted; execution advances only after its waiting condition is satisfied |
| restore / fork | checkpoint.restored after settled/idle/version/expectedPosition validation |

Public input message ID is `input:` + hash([turnId, requestId, inputKind]) using the current
DurableJson canonical digest. inputKind is submit/steer/inject/respond and requires requestId
and Message; respond requires interactionId. Text responses retain their text; other JSON responses
are represented as JSON text in history. These messages mean admitted: withdrawal retains history,
with signal.withdrawn marking non-application. History reads the backend's public projection,
not republished native user logs. Event pruning preserves projected messages.

One Turn can have multiple assistant messages. AgentScope identifies reasoning messages by logical
reasoning position within the Turn: a new Run/Step does not change messageId, while a new reasoning
round creates a different message. Actually requesting the model again at the same position increments
attempt. Replaying a committed model response retains attempt and replaces incomplete presentation
text with a higher revision. Final content edits also increment revision; recording the same completed
message again emits no duplicate. Deltas accumulate within a version; newer versions replace older
text; completed replaces accumulated content and carries full Message. Late deltas cannot change a
completed version. Completed-only Harnesses need no deltas. AgentScope public text comes from native
presentation/text after middleware processing, committed atomically with the journal, rather than raw
model chunks or private reasoning. Message projections may include runId/stepId; their first committed
sequence orders history, not iteration order of snapshot object fields. blockId does not imply arbitrary
multi-block patches. See [projection-vector.json](projection-vector.json).

Tool-call views share snapshot.projection.tools, keyed by `tool:` + hash([threadId, turnId, callId])
using the same DurableJson canonical digest. Local and Hands tools use the same call view; only real
external executions carry executionId. Views retain threadId, turnId, available Run/Step links, first
sequence and merged data. In data, name/arguments describe the public request, content contains the
result, and progress contains committed progress content. Native execution parameters must not expose
host-injected credentials or configuration. Received results remain distinct from applied results.

Hands `ExecutionRequest.call.origin` identifies the original tool intent with `{runId, stepId}`.
Custom execution outside an invocation may explicitly use null; auxiliary operations may have a null stepId.
The AgentScope adapter takes provenance from each native interaction, so a batch covering multiple Steps
never attributes every call to its last Step. Origin participates in the full request digest and survives
redelivery, takeover, batch expansion and Sandbox routing. Changing it under the same executionId is rejected.
`tool.requested/dispatching/progress/result.accepted/output` correlate with that original position.

Result application commits use `appliedResults: [{executionId, appliedAt: {runId, stepId}}]`, stored atomically
with the checkpoint. An active invocation requires an application position; host settlement after an invocation
ends uses null without inventing a Run/Step. `ToolExecution.appliedAt`,
`tool.result.applied` and the tool projection's `data.appliedAt` identify where the result actually entered
native state, often a new Run/Step after recovery; `data.origin` remains unchanged. Receipt does not mean
application, and a stopped original invocation does not prevent accepting late Hands results. This contract
replaces string appliedResults directly, without old-format readers or writers.

| Call event | Payload and behavior |
| --- | --- |
| tool.call.arguments | callId, messageId, attempt, revision, text; include name when known and retain it across unnamed fragments; assemble argumentsText by message version and stop appending once full arguments arrive |
| tool.call.updated | callId, status; optional name, arguments, messageId, content, reason. Status is REQUESTED/RUNNING/WAITING/SUCCEEDED/FAILED/CANCELLED/DENIED/UNKNOWN |
| tool.call.progress | callId, content; append public progress without treating it as a model tool result; late progress does not reopen a terminal call |

The argument-generation view has status GENERATING_ARGUMENTS. Existing tool.requested/dispatching/
progress/result.accepted/result.applied/output facts describe external execution and merge by callId
into the same view rather than creating another tool card. UNKNOWN means the execution outcome is
unconfirmed, never that retrying is safe or cancellation is complete.

Core event families: session.created/closed; thread.created/closed; turn.*; run.prepared/started/ended/lost; step.started/phase_changed/ended;
signal.accepted/applied/withdrawn; interaction.requested/resolved;
message.delta/completed; tool.call.arguments/updated/progress; tool.requested/dispatching/progress/result.accepted/result.applied;
tool.output/output.gap; artifact.published; checkpoint.restored; turn.recovery.
Application extensions use `x.<namespace>.<name>` and cannot replace core lifecycle facts.

Session/Thread creation and closure emit events within the resource transaction. Creating a Session
also emits thread.created for its primary Thread. Repeated creation/closure adds no lifecycle event;
failed commits leave neither resources nor events. Retry a lost receipt with the same creation spec
or closure target. Payloads are SessionView/ThreadView, OPEN for created and CLOSED for closed.
These events carry no Turn/Run/Step; Session events also have null threadId. Agent commits cannot
inject them. Notifications follow commit, with polling compensating for lost notifications.

Snapshot projection.session contains the session resource view; projection.threads maps threadId
to ThreadView. They share sequence/cursor with message projection. Resource replay and snapshots
agree with direct queries; event pruning retains resource state. A Thread filter retains only that
Thread resource and sets projection.session to null. Turn/Run filters exclude resource lifecycles,
with projection.session null and projection.threads empty. Query getSession/getThread for current
parent resources rather than treating a view without parent-event subscriptions as live parent state.

session.observe(callbacks) delivers a consistent snapshot followed by events after its cursor.
The explicit-cursor overload replays from that cursor; null starts at the retained beginning.
Expired cursors resynchronize with snapshots. Successful callbacks acknowledge; failures redeliver,
so external effects must be idempotent. `filter` is `{threadId, turnId, runId}`, with nullable fields; omitting it selects the entire Session.
A non-null turnId requires threadId, and runId requires turnId. Validate full ownership: wrong targets
return NOT_FOUND and missing parent identities return INVALID. Selection does not extend the host's
Session authorization. Java uses EventFilter; Thread/Turn shortcuts delegate to the same public
operation, with identical local and HTTP behavior.

limit bounds scanned Session events, not matching events. Even an empty selection advances the
original cursor; highWatermark remains the Session watermark. An empty list is not end-of-stream:
this page has caught up only when cursor equals highWatermark. Snapshots retain Session sequence/cursor
and select the matching projection. Turn snapshots exclude sibling Turns. Run snapshots retain their
Turn's logical state and projection records currently associated with that Run; they are not complete
historical task snapshots. Select messages, tools, artifacts and interactions by explicit associations
rather than inferring cross-Run ownership.

Use `new EventProjection(sessionKey, filter)` and the same filter for resynchronization. Whole-Session
streams still require consecutive sequences; filtered streams are sparse and rely on server cursors
for completeness. Sequence gaps alone cannot prove loss. Changing a filter while retaining a cursor
selects subsequent events only; explicitly fetch the new scope's snapshot to backfill excluded history.

SSE accepts namespace/sessionId, cursor and optional threadId/turnId/runId. Reject duplicate and
unknown parameters. Last-Event-ID takes precedence; validate ownership and the initial cursor before
HTTP 200. `durable` frames carry PublicEvent. When a page ends with excluded records, a `cursor` frame
uses the scanned cursor as its id and `{}` as data to advance reconnection. This is transport control,
not a public event, and allocates no sequence. Midstream expiry sends `resync`; fetch a snapshot using
the same filter before reconnecting. Disconnecting never controls the Turn.

session.call is submit + await; session.stream is submit + Turn observation. await/result return
full TurnView, retaining failures/cancellations; nonterminal result is CONFLICT. Timeout ends only
the wait and retains task identity. Fork records the target Thread restoration in the target Session timeline;
inherited native history is private recovery state, never fabricated past public events.

## 5. Delivery guarantees

Public `message.delta` and `message.completed` events are persisted and replayable.
Consumers resume with the Session cursor and a consistent snapshot when necessary;
they do not reconstruct state from private native logs. A control receipt confirms
admission, while application events or the resulting Turn state confirm processing.
User input does not acquire system privileges, and public events exclude private reasoning.

## 6. Capabilities, errors and recovery

Public capabilities reports operations, registered definition names and checkpoint bindings;
it does not enumerate every dynamic resolver configuration. `runtime.executionCapabilities()` exposes
Harness recovery/media/events and Executor query/cancel/output. API-only Runtime can bind codecs;
unbound restore is INCOMPATIBLE. Native checkpoints must match harness/definitionVersion/stateVersion.
Portable public protocols do not imply native cross-Harness checkpoint conversion.

| Error | Handling |
| --- | --- |
| INVALID | Fix fields/types/ranges/cursors/operation rather than blind retry |
| NOT_FOUND | Session/Thread/Turn/interaction is absent in the requested scope |
| CONFLICT | State/revision/definition/idempotency mismatch; query before choosing a new action |
| INCOMPATIBLE | Current Harness/codec/protocol capability is unsupported; no downgrade or effect replay |
| CURSOR_EXPIRED | Obtain a consistent snapshot/new cursor, without task resubmission |
| UNAVAILABLE | Outcome can be uncertain; retry the same requestId/content or query, never assume non-admission |
| FENCED | Internal ownership lost; stale executor stops writing, not a request to resubmit user work |

Authentication/authorization belongs to the HTTP host and must prevent unauthorized invocation.
Durable state is authoritative; notifications only prompt checks. Brain scans/lease takeover run
independently of queries/SSE/resume and progress without users. Hands UNKNOWN requires checking the
original executionId, not issuing a new start to invent success.

Invocation failure recovery is part of the public Turn view: nullable `recovery` contains
`failures`, the frozen `policy` (`maxRetries`, `initialDelayMillis`, `maxDelayMillis`), `retryable`,
nullable `retryAt` (absolute Backend time in milliseconds), and `message`. The `turn.recovery`
event carries the same record. `maxRetries` counts additional attempts; a retryable failure allows
another invocation only while `failures <= maxRetries`. `retryAt` is null when recovery has stopped.
The first recorded failure fixes policy for the Turn; successful intermediate work, new hosts and
pause/resume do not clear its cumulative failure count. Backoff does not bypass existing waits.

Privileged Backend operation `recordFailure(lease, failure)` records at most one failure per lease
epoch. Same content returns the original receipt even after owner replacement; changed content
conflicts, and an unrecorded stale owner is fenced. The failure report contains `retryable`, `message`
and `policy`. Counters, invocation facts, recovery/wait events and ready scheduling commit together.
This operation is not a public user control or a license to re-execute an external effect.
Stopped recovery reconciles/cancels original tools and reaches FAILED only after their outcomes
are known; pause/cancel still take precedence. WAITING may therefore have no `waiting` condition
when a recovery record or outstanding tools explain it. Java defaults to 3 retries with 1s initial
and 30s maximum delay. Other implementations must preserve the persisted policy and these semantics.

HarnessAdapter uses settle(context, status, error) for failed/cancelled native settlement. AgentScope atomically commits interaction cleanup, real result application, checkpoint and terminal Turn; a failed write cannot acknowledge applied early.

## 7. Conformance vectors and Harness checklist

[user-workflows.json](user-workflows.json) covers executable admission/retry/history/events/queued
cancellation/content conflicts/unknown fields/internal-operation rejection. `$turn` binds the actual
generated identity. [vectors.json](vectors.json) covers digests/numbers/identities;
[projection-vector.json](projection-vector.json) covers attempt replacement, late deltas, large
sequences and public input. Java local/HTTP share UserApiContract, including control races,
interactions, recovery and lifecycle. Shared vectors are conformance targets for other implementations;
the supplied language binding is Java. Other implementations must validate the same contract independently.

| Harness concern | AgentScope Java mapping/constraint |
| --- | --- |
| Input/output | DurableMessages ↔ Msg; final public Message, no native objects in user protocol |
| Scheduling identity | ThreadKey isolates state; Durable Turn → turnId; native AgentRun shares RunView's runId, no AgentSession dispatcher |
| steer/inject | Invocation-scoped AgentExecutionHost applies/records consumption before reasoning, retaining user role |
| Approval/manual tools | Pending interaction → atomic respond → native recovery; no forged automatic results |
| Tool handoff | Atomic checkpoint/intent; Backend result resumes original Turn; native checkpoint precedes applied |
| Recovery | Codec checks versions/settled and restores logs/state/allowed resources; no replay of unverifiable effects |
| Public projection | Input published once; assistant attempt/revision replacement, no private state |
| Unsupported capability | Explicit rejection, never changed semantics or fabricated terminal outcomes |

## Child delegation protocol

Delegation uses the Executor boundary. Backend `delegate`, `delegation` and `cancelDelegation` are privileged
infrastructure operations. `delegate` atomically persists the target Thread, child Turn, frozen target/input
and parent/child mapping. The target may be new or explicitly reused, within the parent's Session and with a
matching ThreadBinding. One parent executionId identifies one mapping; retries consult persisted facts.

`TurnView.parent` is `{thread, turnId, executionId, origin}` or null; origin uses RunStepRef.
`ToolExecution.delegation` and the public tool projection's `data.child` are `{thread, turnId}` or null.
A null child in DelegationView is valid only for cancellation before admission with cancelRequested=true;
it is neither a successful result nor an omitted child. Parent pause does not propagate to children. Parent
cancellation affects only the mapped child Turn. Cancellation arriving first resolves no target and creates
no Thread/Turn. Original Run/Step and request digests stay fixed. Reject cross-Session targets, same-Thread
queues and dependency cycles formed by unfinished delegations.

| Event | Scope and payload |
| --- | --- |
| tool.delegated | Parent Thread/Turn and original Run/Step; executionId, callId, child |
| tool.delegation.cancel_requested | Parent scope; executionId, callId, nullable child. Acknowledges intent, not proof that the child stopped |
| turn.delegated | Child Thread/Turn with null Run/Step; payload is the parent reference, never the parent's Run misattributed to the child |

The actual child result still enters the parent through Executor query/result handoff and commits its
application with the parent's checkpoint. Queries reconstruct the relationship from a child's parent or a
parent tool's child, without process-local handles or platform databases.

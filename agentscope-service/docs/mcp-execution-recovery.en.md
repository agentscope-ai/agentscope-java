# MCP execution takeover and result recovery

[中文](mcp-execution-recovery.md)

AgentScope Service recovery extension v1 uses tools/list, tools/call and _meta to query the original
execution without modifying AgentScope Java binaries. Third-party servers must implement this
contract; ordinary tools keep their protection against replaying uncertain operations.

## MCP call batches and a single completion notification

After Harness and Core permission checks, a wave containing only available, schema-valid managed MCP
calls becomes one `mcp.batch` submitted through one `TOOL_EXECUTE`. A batch contains at most 128 calls;
the Worker executes them in list order and retains each original call ID, descriptor hash, arguments,
recovery declaration, and raw result. Mixed local tools, invalid arguments, inactive groups, external
overrides, and MCP tools with presets or custom parameter extensions retain the SDK execution path,
preserving validation and parameter injection before execution. Auxiliary skill registrations through
SDK builders do not disable MCP batches. An isolated read-only metadata probe checks each MCP tool;
unrecognized SDK layouts fall back to SDK execution, with compatibility tests preventing silent
regressions. Managed Harness sets `parallel_tool_calls=true` so providers may return multiple calls in
one response; the Worker still executes the original list in order. Separate model waves stay separate
batches.

One batch creates one `ma.tool_task` and one schedulable `ma.hands_execution`. Runtime metadata in
`ma.mcp_batch_call` preserves one Runtime tool-view row per native call, its terminal status, and cursor pagination;
it does not create additional ToolTasks or notifications. On first processing, the
Worker transactionally creates internal call rows with `batch_parent_id` / `batch_ordinal`. These rows
are excluded from independent dispatch, result publication, and MQ consumption. Each confirmed call
result is persisted before advancing; only a complete ordered result set finishes the parent batch.
An MCP error remains the original per-call error result while subsequent calls are collected. Parent
`succeeded` means the complete result set was collected; each call retains its own success/error status.

Takeover acquires the parent batch lease. A replacement reuses completed calls, queries in-flight
calls by their original execution IDs, and starts untouched calls in order. Only a trustworthy recovery
`not_found` permits resubmission with the original idempotency ID. Calls without a recovery contract
remain uncertain rather than being replayed. Parent `TOOL_CONTROL` cancellation prevents subsequent
starts, preserves completed results, and waits for confirmation of in-flight outcomes. Closing an MCP
connection is not proof of physical cancellation. Untouched calls receive individual cancelled results.

The Worker publishes only the parent completion through existing `TOOL_EVENTS`, retaining shared-object
references for large results. In one transaction the Runtime persists the result, checks pending work,
and creates a durable `dispatch_mode=direct` continuation intent. This path sends no second notification
to `TURN_RESUMES`. The completion callback wakes a bounded continuation pool; a 500ms database scan
repairs missed notifications and replica failure. Restoration acquires the same Session Redis execution
lease and a new database execution epoch, using the shared checkpoint and original batch results rather
than the ingesting Runtime's memory. The intent becomes applied only after a terminal Turn or a new
durable suspension, so a crash after activation remains recoverable. Input waits and legacy single calls
retain their existing `TURN_RESUMES` path during rollout.

The original TURNS 60-second Suspend, terminal proactive ACK, Session settled sequence and predecessor
checks remain intact. Batch completion, notification ACK and continuation claim cannot release T2;
T1's terminal commit remains required. Append `managed-mcp-batch.sql` through the schema job before
updating Java services that strictly validate the migration ledger. Grant Session Portal and Runtime
SELECT/INSERT/UPDATE/DELETE on the new `ma.mcp_batch_call` table; Workers retain access only to their private state. Complete the Worker rollout before
admitting batch-producing Runtime traffic, and avoid routing batches to older Workers/Runtimes during rollout. Local regressions cover two-call
real-Harness restoration, cross-Runtime recovery, partial-result takeover, cancellation, both result/
suspension arrival orders, no additional Resume message, intents surviving activation failure, and
T1/T2 ordering. Remote acceptance additionally inspects actual Pod logs and physical server invocation counts.

## Advertisement and invocation

A recoverable Tool advertises metadata:

```json
{"io.agentscope/execution-recovery":{"version":1,"queryTool":"managed_execution_get"}}
```

The same server exposes queryTool with readOnlyHint=true; it is excluded from the model catalog.
The recovery declaration is frozen in the descriptor hash and durable mcp.call arguments. Legacy
descriptors omit the new null field and retain their hashes. The platform adds invocation metadata:

```json
{"io.agentscope/execution-recovery":{"version":1,"execution_id":"mcp_exec_..."}}
```

Identity derives from Session scope and original task.execution_id and stays stable across Workers,
connections and deliveries. User metadata cannot override it. Disabling metadata propagation rejects
recoverable calls before any external effect.

## Server contract

- Atomically persist execution_id, tool identity and an argument fingerprint before starting an operation. Concurrent matching submissions refer to the original execution.
- Reject changed arguments for an admitted identity without overwriting it.
- Continue execution and result capture after caller disconnects. Connections and replicas share the same durable facts.
- Persist the complete original CallToolResult before returning it; authenticate lookups as well.
- Retain facts while takeover is possible. Expired/uncertain records return unknown; not_found must mean no admission exists.

Lookup calls the frozen queryTool with `{"execution_id":"mcp_exec_..."}`. Its ordinary CallToolResult
contains structuredContent:

```json
{"version":1,"execution_id":"mcp_exec_...","state":"succeeded","result":{"content":[{"type":"text","text":"original result"}],"isError":false}}
```

| state | Takeover behavior |
| --- | --- |
| running | Retain identity; the lease-owning executor queries the original execution |
| succeeded / failed / cancelled | Validate identity and complete result, capture it, then continue reliable tool-event/Resume publication |
| not_found | Resubmit with the same identity only under the advertised atomic idempotency contract |
| unknown, unavailable lookup, invalid response/identity | Keep unknown without repeating the operation |

Lookup never launches, retries or cancels an operation. Cancellation prohibits another start even
after not_found. Original and queried results use stable object-key ordering to avoid hash conflicts
from JSONB or replica ordering. Tool settlement precedes effective Resume; T2 follows T1 completion.

## Direct discovery in Runtime

Brain initializes an MCP client and reads `tools/list` before inference, pinning descriptors in
its per-Turn `ma.mcp_catalog` snapshot. Discovery uses bounded blocking threads and connection capacity,
respects initialization/request timeouts, checks cancellation while awaiting responses, and closes connections
afterwards. It never blocks Reactor I/O threads. Required server failures still reject execution; optional failures
are pinned only for the current Turn and retried on the next. Tool filters, read-only recovery lookup contracts,
descriptor hashes, and the explicit local-environment restriction for stdio remain unchanged.

New requests no longer create `mcp.describe` Worker commands or wait for discovery through `TOOL_EVENTS`
and 500ms Turn redelivery. Real `mcp.call` operations retain the durable Worker admission, execution and
recovery flow below, grouped into batches for eligible MCP waves. In-flight discovery commands and results from older deployments are accepted only to
drain rolling upgrades; historical serialized formats remain compatible.

## Durable admission and execution ownership

A successful `TOOL_EXECUTE` receipt means that the immutable task and sealed configuration are committed
to `hands_execution`, not that the tool has completed. The callback only admits and wakes work; database
unavailability still returns Suspend. A bounded `ManagedHandsLoop` recovers from a missed wakeup,
a crash after ACK, or capacity exhaustion by scanning durable tasks, without periodic Execute redelivery.

The background execution owns a Redis lease for its whole lifetime (60 seconds by default), renews it
every 15 seconds, and refreshes `last_dispatched_at` using the same worker_token. This timestamp also
schedules recovery; a stale token cannot postpone its successor's recovery. Each replica runs a bounded,
indexed due scan every 500ms. Healthy owners keep their tasks out of the due set, so peers do not repeatedly
claim them. The default retry interval for `ready`/`unknown` is 30 seconds; `starting`/`running`/`result_pending`
become recoverable after the ownership heartbeat stops and expires. Redis remains the mutual-exclusion
authority; database time only schedules an attempt. Crashes before or after claim retain the same identity.

Execution capacity defaults to 16 per replica. Admission can still ACK when capacity is full; pending work
stays in the database rather than an unbounded executor queue. MCP HTTP responses complete asynchronously.
Connection preparation, JDBC capture, and ownership checks run on bounded background threads, not MQ
callbacks or Reactor I/O threads waiting for a long HTTP response. An owner retains a bounded waiting slot;
query/cancellation checks default to one second. Confirmed outcomes are captured reliably and the result
Outbox independently retries `TOOL_EVENTS`; losing a lease does not invalidate an original confirmed fact.

Cancellation and replay still use the independent `TOOL_CONTROL` consumer. Takeover queries the original
execution_id. Only a recovery contract proving no admission permits resubmission with the same idempotency
identity. Unverifiable ordinary calls remain unknown. Original `TURNS` FIFO and durable suspension/resumption
conditions are unchanged: ACKing a tool command does not finish a Turn, and T2 still waits for T1's terminal state.

## Implementation and scope

The two [HTTP fixture](../tests/http-mcp-long-tool/README.md) replicas share a PostgreSQL mcp_fixture
ledger without Sandbox or NAS. Its ordinary account selects/inserts and only updates state/result;
initialization runs separately as owner. Checks cover caller disconnects, cross-replica lookup,
concurrent deduplication, argument conflicts, wrong identity, unavailable lookup, original failure/
cancellation, key ordering and real Worker Pod failure.

The fixture only performs timed waits. This acceptance covers Worker failure, not automatic
completion after the MCP execution process itself dies. Old tasks lacking recovery identity are
not replayed or assigned fabricated results.

## Long Runtime Turn suspension and proactive ACK

While waiting for tools or input, the original `TURNS` message retains the Session's MQ FIFO head
with a default 60-second Suspend (`builder.managed.turn-suspend-seconds`). Tool results are
still durably ingested through `TOOL_EVENTS`; MCP batches resume directly from durable intents, while legacy operations and input waits use independent `TURN_RESUMES` messages.
A tool result alone does not ACK that Turn. Short retries remain for capacity and lease contention.

After the SDK confirms every Suspend RPC for the same cached batch, a bounded blocking worker stores
the private delivery capability in `ma.turn_ack_receipt`. The journal transaction commits the terminal
checkpoint/status and ACK-ready marker together. Capture takes the same Session row lock, so completion
before capability capture cannot lose the ACK intent. Each Runtime scans a bounded set of ready ACK
records every 200ms instead of redelivering every waiting Turn through MQ. No database transaction is
held across an ACK RPC, and neither Reactor nor SDK RPC threads perform blocking database work.

After T1 terminal commit, any Runtime replica can use the original queue routing and a valid receipt
handle to shorten the queue suspension to 50ms, then ACK T1 with the handle returned by that response.
This also restores visibility for cached successors before MQ releases T2. Both RPCs share a six-second
budget. If redelivery refreshes the handle first, the terminal Turn callback returns SUCCESS normally. A 10-second claim window permits recovery after a lost response or a crashed
drainer. Expired handles are discarded; timeout redelivery obtains a fresh capability. Each generation
has a separate identity, so an old response cannot replace or remove a newer capability. Database,
capture or ACK outages leave the original message intact; 60-second timeout redelivery remains the
recovery fallback. Processing and ACKs retain at-least-once semantics.

This uses an isolated internal adapter for RocketMQ Java SDK 5.2.2. Rerun cached-batch and ACK-status
contract tests when upgrading the SDK. Before deployment, append `managed-turn-ack.sql` through the
existing schema job, reapply business-role grants, and deploy service versions compatible with that
migration. Capabilities remain private and short-lived and never enter public APIs or logs. Regression
entry points are `ManagedTurnAcknowledgementsTest`, `ManagedPostgresTurnAcknowledgementsIntegrationTest`,
`ManagedLitePushConsumerTest`, and `RocketMqTurnAcknowledgementsIntegrationTest`.

## Runtime command Outbox publication leases

Each Execute, Cancel or Replay command retains one immutable Outbox identity. Runtime replicas
claim one immediately sendable command with a conditional database update. The claim checks the
confirmed broker receipt, retry time, lease expiry and observed attempt generation. Ownership uses
a private random token, a 30-second lease and database time; it never changes the command payload
or Worker/MCP execution identity. Candidates selected before another publisher's confirmation
cannot be sent. Commands are not reserved in batches while waiting for earlier sends.

MQ sends run outside database transactions on the publication thread. A separate timer renews every
five seconds. After a 30-second send budget, renewal stops and the sending thread is interrupted;
an SDK that ignores interruption can only return a fenced receipt, while another Runtime can
recover after lease expiry. Confirmation requires the same token and an unexpired lease. Renewal,
retry backoff and release also check ownership, so a late publisher cannot overwrite its successor.
An unconfirmed send releases only its own claim with a one-second retry delay; a database
outage leaves the durable claim recoverable by expiry. Structured `outbox.command.*` logs include
the command, attempt, publisher instance and broker receipt, never the private token or arguments.

MQ acceptance followed by a crash before the receipt commits can still cause physical redelivery.
Worker durable admission, execution leases/fencing, per-call results and stable MCP idempotency
identities remain mandatory. The original Turn FIFO, suspension/resumption and T1/T2 terminal
ordering are unchanged. Regression entry points are `ManagedToolTransportTest` and
`ManagedPostgresToolTransportIntegrationTest`.

Deploy the additive `managed-tool-publication-lease.sql` migration through the existing schema job.
Stop all old Runtime publishers before migration and restart them with the compatible image; old
publishers do not honor the new lease fields and must not overlap with new publishers. Existing
pending commands and confirmed broker receipts are retained. Deploy compatible Session Portal and
Hands images as their shared schema validator also knows the new migration.

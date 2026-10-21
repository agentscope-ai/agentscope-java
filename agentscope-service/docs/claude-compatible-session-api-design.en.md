# AgentScope Service Claude-compatible Session API design — Draft 3

Confirmed design, first-release implementation. The full Chinese specification is
[Draft 3](claude-compatible-session-api-design.md). This English companion defines the same release
boundary and implementation obligations. Compatibility remains a candidate until the documented
SDK and behavioral checks pass for an explicitly named subset.

## Protocol and release boundary

Use one public interaction endpoint: `POST /v1/sessions/{session_id}/events`. An event keeps Claude's
`type` and content-block array. Platform intent belongs to `agentscope_service` in the event body.
`AgentScope-Extensions: turn-inputs-v1` selects additional fields and notifications on reads and
streams. It does not select a different execution intent or change business idempotency.

First release supports single-agent text Sessions: create/retrieve, ordinary messages while idle,
interrupt or interrupt followed by one ordinary message, explicit new_turn/steer/inject/cancel,
Turn/Input reads, event history, a live SSE stream, and independent final output.

Session listing, updates, lifecycle writes, client tool confirmations/results, threads, outcomes,
files, preview deltas, reliable delivery and snapshots are unavailable in this release. Return an
explicit unsupported-capability error; do not accept and ignore an operation. Management of Agents
and Environments stays with the existing resource APIs. Platform tools keep their actual permission
checks; configurations requiring unsupported client interactions must be rejected, never approved
automatically.

## Identities, admission and execution

A Session pins Agent and Environment configuration. A Turn is one independent request. An Input is
a steer, inject or cancellation directed to an existing Turn. A public Event is allocated on admission,
before a runtime message exists. Tool event IDs, native tool-call IDs, Action IDs and worker task IDs
are separate identities with durable mappings. Internal activations are not Claude Workflow Runs.

Each new_turn creates an independent Turn. Only one primary Turn progresses per Session; suspension
preserves ownership until its real result or terminal settlement. Execution order is the persisted
publication order. Concurrent HTTP arrival does not define a total order. Multiple new_turn events
within one batch publish in array order.

Steer/inject share the safe reasoning-boundary append mechanism. They do not replace system context,
interrupt a physical tool or create a new Turn. While waiting for a tool, they apply after the real
result and resume. Inputs accepted before closure must be applied or durably rejected. Cancellation
settles the actual operation; accepted does not mean stopped, and applied can resolve as already
completed when completion won the race.

## Atomicity and idempotency

Explicit intents require a stable Idempotency-Key, 1–256 characters. Ordinary requests may omit it;
there is no cross-attempt deduplication promise without a caller-retained key. The platform SDK
creates a key once per helper invocation; callers retry an uncertain invocation with the retained key.

Authenticate the current principal and resource scope, validate immutable syntax, then serialize
on the durable request key. Replay an existing matching request before mutable target, lifecycle,
quota or inputs-open checks. Different content is a 409 idempotency_conflict. Only a new request
performs mutable validation. Commit request digest, Event IDs, Turn/Input identities, quota changes,
frozen receipts and pending delivery as one transaction. A failed item rolls back the entire extended
batch. Model/tool work starts after commit. Publication recovery reuses the original identities.

Return HTTP 200 with data in request order. Persist the receipt inside admission, so the first response
and retries retain the accepted status even when execution has progressed. GET returns current state.
Legal output selection changes presentation only. Authentication is still required on every retry.
Metadata and event IDs cannot change the caller, Tenant or Namespace.

## Public facts and observation

Project only committed, public-filtered native facts. The admitted input keeps its public Event ID
when processed_at becomes available. Input accepted notifications have separate identities. A final
output marker associates an existing assistant message rather than emitting another complete answer.
Tool suspension is not tool completion. Model usage comes from actual measurements; do not invent
cache, cost, activity or timing values. Private thinking, checkpoints and credentials stay private.

History returns data/next_page and supports page, limit, order, repeated types[] and
created_at[gt/gte/lt/lte], compared with processed_at. A page freezes the read upper bound and
projection versions, including pending input transitions. Cursors bind scope, identity and query.
The official SDK's beta=true transport flag is accepted without becoming a business filter.

GET /events/stream starts after a boundary captured on connection. It does not replay history and
rejects after/Last-Event-ID or preview options. Capture the boundary before flushing the initial
comment, and read committed changes after it without a notification race. Disconnecting an observer
does not cancel work. Reconnect by opening and buffering a new stream, reading history, then deduplicating
by public Event ID. Periodically recheck read permission. A Turn ending does not close the Session stream.

Session states are idle/running/rescheduling/terminated. Queueing is not an automatic retry. Preserve
idle with end_turn after a completed primary response or standard interrupt, even when another Turn
can immediately start. Precise execution status remains on Turn and the platform extension.

## Independent output and future reliable observation

GET /turns/{turn_id}/output returns only the immutable, committed final message collection, in output
order, with output_available, data, output_event_ids and next_cursor/has_more. It needs read permission
and no SSE, output-selection header, snapshot or durable capability. Before final output is committed,
return 200 with output_available=false and empty data. Terminal without output is distinct from a
projection that exists but is temporarily unreadable; the latter is retryable 503. SDK final_output
waits through Turn queries and pages this endpoint, without submitting work.

Reliable delivery, consistent snapshots and preview are separate future capabilities. Their cursors
must not be interchangeable with history or output pagination, and they must not become prerequisites
for first-release result retrieval.

## Component and upgrade boundaries

Session Portal owns HTTP validation, original-caller authorization, admission and public reads. Agent
Brain consumes the existing Turn/Input protocol and commits native state. Hands
executes and settles real tools. Gateway routes and clears internal credentials; Control Plane owns
resource configuration and scope. Runtime code does not depend on public HTTP headers.

Only the standalone agentscope-service repository changes. AgentScope Java stays a binary dependency;
existing database table names, MQ groups and required native contracts are retained. New protocol
persistence uses an additive migration. Before a separate upgrade, drain accepted work or preserve its
execution ledger and identities. The initial implementation record remains in verification.md; the follow-up audit and isolated upgrade are recorded in draft3-audit.md. Existing records are retained without backfilling old public history.

Pinned SDKs: Python anthropic 1.15.0, TypeScript @anthropic-ai/sdk 0.135.0 and Java anthropic-java 2.73.0.
Frozen schemas, runnable fixtures and actual results live in `../tests/session-protocol/`.


## Follow-up Draft 3 audit

Public Agent projection and admission resolve the scoped sealed configuration through the existing catalog; missing or mismatched definitions do not become blank configuration. Private MCP authentication stays private.
Agent toolsets expose complete configs, default_config, enabled and effective permission_policy.
Native defaults are always_allow; omitted MCP policies default to always_ask. Enabled configurations
that require client confirmation, unmapped skills/tools and non-URL MCP servers are rejected.
The three platform SDKs share input_event_id, status and event_id filters. Final-output helpers only
read, retry 503 with the same query, bound pagination by the wait deadline, reject invalid/cyclic
pages and finish with an empty collection for terminal Turns without output. Console polls selected
nonterminal Turns independently of SSE, retries unavailable projections and retains cancellation keys
across uncertain responses. See `../tests/session-protocol/draft3-audit.md` for audit evidence.

Hands publishes durable tool events. Brain applies complete results and atomically
stages a Resume outbox intent when the current suspension is ready; its independent Resume consumer
restores inference. Worker completion notifications are retired, while the Resume Topic is retained.

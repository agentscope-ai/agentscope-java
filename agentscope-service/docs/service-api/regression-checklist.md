# Service API integration regression checklist

Historical engineering checklist for the pre-unification API. See [Session API migration](session-api-migration.md) for the current interface and validation scope. User documentation is in `docs/v2/{zh,en}/service/service-api.md`.

The 2026-10-02 concentrated campaign exercised the running Gateway/CP/DP, PostgreSQL, SDK, Console and failure-recovery paths. See [the consolidated results and remaining boundaries](../../../internal/implementation/session-service-regression-20261002.md). This checklist preserves the original scope; use that report to distinguish completed evidence from capacity, deployment and exhaustive fault scenarios that remain open.

The original implementation checks were focused unit/package, build and contract checks. The following defined the broader running-service campaign; current outcomes are recorded in the report above. Control-plane migrations include `0112_service_api_contract` and `0113_service_application_identity` Application ownership changes; Managed Java persistence also requires `service-dataplane/src/main/resources/db/manual/agent-event-mirror-outbox-postgresql.sql`. Apply the supplied SQL for each store before running the new API. No migration has been applied to a user database as part of these edits.

## Before deploying

- Apply migrations to a copy of an existing database and to a fresh database. Verify the new release/invocation contracts, Application foreign keys, JSON state and migration rollback on seeded new-format data. This unpublished API does not promise legacy release compatibility.
- Run the example against Agent, Team and Workflow Job endpoints. Include at least one nested Team and a child Workflow. Confirm root completion, member failures, partial success, cancellation and artifact ownership follow the complete invocation graph.
- Run Managed Conversation submit/steer/action/cancel/resume against a real runtime. Confirm user/platform/API-key scopes, designated-human tool confirmation, native turn mapping and lost-response replay. Failed invocations remain terminal; checkpoint restoration uses the native Managed API.
- Disconnect the UI during assistant text, tool argument generation, tool execution and a required action. Rebuild from snapshot, then `as_of`; compare against the uninterrupted view. Repeat with multiple agents sharing the same native tool ID and with more than one event page.
- Kill a control-plane replica after reservation, after each materialization write, during journal commit, and after a command side effect but before its receipt. Start another replica. Verify deterministic child IDs, deduplication, committed cursor boundaries and no duplicate logical work.
- Run concurrent submissions with the same key, conflicting payloads and changed releases. Enforce one active invocation per conversation, endpoint concurrency, principal isolation and current credential revocation. Check published static Agent/Team definitions remain pinned after edits and rollback.
- Disconnect either HTTP exchange or ASDP before the final event ACK, restart an external worker with its original outbox, and redeliver the attempt. Confirm events remain replayable and stale-generation reports are fenced. Agent/tool side effects still require business idempotency; a process crash cannot make arbitrary external effects exactly once.
- Cancel while a tool is cleaning up or a child Run is active. Distinguish cancellation requested from actual stop. Verify deadline and token-budget cancellation with delayed/missing usage and per-model versus aggregate usage; token budgets are based on reported consumption.
- Deliver webhooks to a controlled public HTTPS receiver: verify HMAC over raw bytes, timestamp checks, duplicate delivery, backoff, disable/retry and restart. Exercise rejected private addresses, DNS changes, redirects and cross-principal subscription access.

## Capacity and long-running regression

- Measure long transcripts, large tool outputs, many artifacts, many completed invocations, slow PostgreSQL and multiple replicas. Exercise the indexed due-work queue, journal checkpoints every 64 events and terminal-only event retention; quantify latency, connection use, snapshot tail reconstruction and storage growth.
- Exercise both PostgreSQL advisory-lock connections and nested repository calls under a small pool. Verify connection cleanup on cancellation and that one slow invocation cannot prevent other active invocations from making progress.
- Run soak tests for command retries, expired approvals, signal delivery, worker shutdown and webhook backlogs. Check recovery after a Gateway timeout and session archival/deletion while a client is observing.

Focused tests already cover cursor scope, committed-prefix recovery, accumulated text/tool arguments, durable cancellation after a server restart, schema pinning/replay, external-worker cleanup and event-ACK ordering. The TypeScript reducer test covers snapshot restoration and cross-agent tool ID isolation; the Managed resume test covers repeated delivery after another interruption.

## Newly added execution and application paths

- Run `bootstrap.py` without Console over HTTP transport, without exposing the ASDP gRPC port. Exercise Agent, actual Team delegation, declared Workflow and optional Managed member/approval. Confirm command delivery does not require an inbound worker port.
- Deliver HTTP commands more than once and out of order, including cancel before dispatch. A late dispatch must replay the cached terminal report, not execute cancelled work. Lose an exchange response and restart a control-plane replica; mailbox ACK and event ACK remain separate.
- Kill the Managed mirror between native log commit and Service mirror confirmation. Recover the committed suffix without duplicate public items or missing tool results. Pause a process after binding a run but before native writer acquisition, expire its admission, complete the terminal mirror barrier on another replica, then resume the process. The sealed run must never acquire or commit; a new run ID in the same session must still work. Race final sealing against enqueue and final delivery ACK, including a failure between native freeze, committed export and SQL sealing. No public terminal event may overtake the original Attempt's committed suffix.
- Expire a terminal invocation's delta history while retaining its snapshot. Verify 410/cursor_expired, snapshot replacement, as_of continuation, deduplication and artifact access. Verify active invocations are never pruned.
- Use two credentials in the same Application across multiple Endpoints. Exercise shared ownership, key rotation, maxConcurrent and cumulative tokenBudget; disable the Application and remove a delegated approver while a request is waiting.
- Check resultMapping JSON Pointer escaping (~0, ~1), arrays, missing paths, schemas, release changes and partial_succeeded outputs. Compare capabilities before and after binding selection; unavailable commands must not be offered in Console.

Focused new SDK tests cover fresh async runner instances, AgentScope hook cleanup on cancellation, HTTP message retry, ACK only after command acceptance and cancel-before-dispatch. Real framework/model integration and multi-replica soak tests remain separate work; PostgreSQL migration results are now recorded in the concentrated regression report.

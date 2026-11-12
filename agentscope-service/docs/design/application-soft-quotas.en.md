# Soft application quotas and eventual usage totals

[中文](application-soft-quotas.md)

Application quotas are traffic protection with permitted burst overshoot, stale snapshots and aggregation delay. Session FIFO, execution epochs, cancellation, idempotency and per-model-call accounting facts retain their consistency. This change applies to the Managed path only; generic-backend `builder_portal_quota` is unchanged.

## Request path

- Read `ma.admission_quota` without `FOR NO KEY UPDATE` or `FOR SHARE`.
- Conditionally update policies only when their version or effective fields change. Older versions cannot replace newer ones and baseline tokens remain monotonic. Identical same-version policies perform no update. Actual changes still take PostgreSQL implicit write locks, without repeated writes on every request.
- Application concurrency and queued counts use per-Store one-second snapshots, bounded to 4096 window entries. Replicas have independent snapshots; bursts can exceed configured caps, and completed work can still appear full for up to one window. Configuration, cache misses and query failures are not silently ignored. Skip application active-Turn counting when concurrency is unlimited.
- Session queues and pending Inputs retain checks under the Session lock. Cancellation can still enter a full ordinary Input queue.
- Application RPS uses Redis Lua `INCR` with `PEXPIRE` on first use. Keys are `ma:admission-rate:<quotaKey>:<unixSecond>`, expire after two seconds and isolate applications. Client clock skew and second boundaries permit bursts. Rate checks wait at most 200ms; Redis failures/timeouts admit the request with a warning. Failed database admissions do not refund the rate count. Thread interruption still propagates. Other Redis operations, including execution leases and cancellation, retain strict checks.
- Embedded constructors without Redis use an approximate process-local second window for isolated operation/tests. Production explicitly injects the shared Redis limiter.

## Accounting and background aggregation

Each `model/end` rechecks `ma.model_call` under its Session-tree lock, updates call usage and Session budget, and inserts a durable `ma.application_usage_delta` within the original native-fact transaction. Identity derives from root scope and call ID. Replay does not create a second delta; transaction rollback also rolls back the delta. Soft application enforcement does not discard or arbitrarily overwrite per-call usage.

Only Agent Harness Runtime starts the aggregator: every second, claim up to 512 deltas with PostgreSQL `FOR UPDATE SKIP LOCKED`, group and sort applications, increment `managed_tokens_used`, and delete claimed deltas in the same transaction. Replicas may process concurrently. Per-application total updates still briefly lock a row per batch, outside Turn commits. Failures roll back the entire batch and retain deltas for retry, including after process restart.

Application Token checks observe aggregated totals. Delay normally spans one or more aggregation cycles and can grow under load or persistent faults; overshoot has no fixed bound. Totals catch up when aggregation recovers. Session-tree budgets continue to use durable per-call facts; execution idempotency and recovery fencing remain intact.

## Upgrade and validation

Before release, run the additive `managed-application-usage.sql` through the versioned Schema Job, then reapply `deploy/postgres-roles.sh` for Runtime/Agent Service permissions. Ordinary services validate the schema without creating tables. Existing migration checksums remain unchanged. No new RocketMQ Topic or group is required.

Stop old Runtime replicas and drain their commits before starting new replicas, avoiding transitions between synchronous and asynchronous accounting for the same calls. Do not roll back to a version unaware of the delta table while deltas remain pending. Existing `managed_tokens_used` is preserved; new deltas add to that baseline without rebilling historical calls.

Regressions cover quota writers not blocking independent Session admission/model reservation/settlement, identical policies avoiding writes, permitted concurrency overshoot, budget enforcement after aggregation, rollback without billing, concurrent same-call replay, parallel aggregators without double charging, skipping locked deltas, recovery after aggregation failure, and Redis rate fail-open without weakening execution leases.

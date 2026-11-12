# Short-transaction admission for explicit new Turns

[中文](new-turn-admission.md)

Optimize only batches consisting entirely of explicit `user.message` / `new_turn` events without `turn_id`. Ordinary messages, cancellation, injection, steering, interrupts, mixed batches and execution recovery retain their coordinated paths. No API, persistence format, schema, Topic or consumer group changes are introduced.

## Flow

Previously, admission locked the Session before policy reads, input validation, rate checks, application quota queries and Turn serialization. Slow external calls or counts delayed other operations on the same Session.

The new path has two stages:

1. Before locking, read any committed idempotency receipt. Return the original receipt or reject a mismatched digest. API authentication and authorization still happen before this lookup.
2. For new requests, validate policy/input, prepare stable Turn identity and canonical input, and run soft application quota/rate checks without the Session lock. Pure validation and conditional policy refresh may repeat for concurrent same-key requests; they do not deduplicate execution side effects.
3. Enter a short transaction, lock the Session and recheck the receipt: another submit may have committed during preflight. Return that original receipt instead of appending again.
4. Under the lock, recheck Session lifecycle, checkpoint administration holds, child admission eligibility and Session queue capacity. Allocate sequence numbers and atomically persist Turns, public events and the request receipt. Explicit new Turns do not require an idle Session and remain automatically queued. Any item failure rolls back the whole registration batch.
5. After commit, retain the existing MQ publication and durable outbox recovery flow. Preflight order does not promise HTTP arrival order; registration determines admission sequence and the existing publication/FIFO protocol determines execution order.

A business preflight rejection triggers a final short locked receipt lookup. If a concurrent same-key submit just committed, its receipt takes precedence over a stale rejection. Otherwise the original error is returned. Database access failures propagate; clients retry with the original idempotency key.

## Boundaries

Session locks still protect atomic state changes, without spanning inference or tool waits. Necessary database writes and protocol projections remain inside the transaction; admission is not lock-free. Application quota changes between preflight and registration follow soft-limit semantics. Lifecycle and Session queue capacity are rechecked under lock.

Cancellation, injection and recovery depend on mutable target/execution state and do not use the fast preflight path. Mixed batches containing such operations remain coordinated as a whole. Legacy direct Turn submission and internal recovery interfaces retain their original calling paths.

## Regressions

Real PostgreSQL tests pause validation or rate checking while injection into the same Session still completes. The current Turn keeps execution ownership while the new Turn queues. Further tests cover lifecycle closure during preflight, unique sequences for concurrent distinct keys, same-key replay and content conflicts, concurrent capacity checks, whole-batch rollback, and a concurrent committed receipt taking precedence over stale rejection. Real lock-timeout probes confirm ordinary messages, cancellation, injection and mixed batches retain their original coordination.

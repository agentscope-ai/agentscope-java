# Deferred concentrated regression pass

Per user direction (2026-09-30), implement the complete capability first. Current pass uses compilation and a small number of critical contract checks; the following need a dedicated later regression campaign.

- Fault injection after blob/commit writes, head CAS, lost ACK, export ACK, public DB commit, inbox admission and worker dispatch; verify committed prefix, idempotency and no tool replay.
- Cross-process writer fencing/lease expiry, clock skew, takeover during long tool/model calls, local/NAS semantics, Redis persistence configuration, PostgreSQL/MySQL/Mongo/ControlPlane CAS contract.
- Crash between action result persistence and context checkpoint; explicit uncertain-tool reconciliation; denial/suspension/synthetic tools; delayed/non-cancellable side effects.
- Full context equivalence before/after compaction/eviction, structured output, fallback, custom strategy, auxiliary summarization, verification/task/plan changes and migration baseline.
- Session identity and namespace isolation (tenant/user/agent/session), path traversal/symlink, protected journal paths, sandbox lifetime and configured external journal.
- Durable API admission/repeated idempotency key/concurrent retries; queue order, worker death, cancel-before-start/cancel-in-flight, stable public turn on explicit resume; interactions repeated/wrong-session/expired.
- SSE history/live race, Last-Event-ID reconnect, cross-session/future/expired cursor, preview after completion, bounded slow-consumer queues, filters/heartbeats, dropped notifications.
- Native/public/Aistio/Console golden trace equivalence; export retry on process restart, all public resources and required actions, parent/child sessions with parallel subagents.
- Long sessions, millions of chunks, large multimodal payloads, blob dedup/retention/GC, compaction cost, replay memory use and IO amplification.
- Legacy compatibility, existing builders/custom filesystems, custom required event codecs/version skew, multiple SDK versions, all affected Java/Go/TypeScript suites and end-to-end hosted service deployment.

## Additional integration cases from implementation review

- Service `always_ask` confirmation tickets versus Core PERMISSION_ASKING: admission before delivery, lost reply, timeout, managed Approval rejection, repeated answers and multi-answer partial delivery.
- Pending interaction cancellation, interrupted external execution with unknown outcome, delayed reactive cleanup, and cancel racing an already committed completion.
- Inbox admission sequence ordering with clock skew; more than 100 queued/running rows across replicas; lease renewal for every locally active command; one blocked session must not starve unrelated sessions.
- Parent-configured distributed session store and history mode inherited by built-in child factories; child exporter/turn/state must not inherit the parent's identity; direct-child trace authorization.
- Final post-call middleware changes versus working context; iteration limit / middleware stop / interrupted reply must not become a false completed turn.
- Offline baseline/fork identity rewriting, source waterline held under lease, pending-interaction rejection, unsupported authority/version, and protected runtime storage on macOS paths with system symlink ancestors.
- Production DDL for new command/action/export-source tables and indexes, JWT trace role enablement, gateway lifecycle aliases, and permission matrix for owner/shared/managed-task sessions.

## AgentSession follow-up regression (2026-10-01)

Implemented API: `agent.session(context)` owns durable `submit` / `steer` / `inject` / `respond` / `interrupt` / `resume` operations. AgentRun remains the underlying invocation handle and is only documented as an advanced escape hatch. The SDK and Service retain separate scheduling owners.

Focused checks cover command idempotency, queue order, restart, injection without wakeup, steering within the same turn/run including final-response admission, external HITL and permission confirmation, interrupted continuation, identity propagation, and reconstruction of partial messages and tool cards.

For the concentrated regression pass:

- Kill the process before/after inbox acceptance, main `inbox/started`, message checkpoint and `inbox/applied`; replay must neither lose accepted input nor repeat applied messages or tools.
- Multiple SDK owners sharing distributed storage: simultaneous submit/steer/interrupt, competing resume/respond, stale leases and clock skew; shared native writer must fence duplicate inference.
- More complete partial/mixed approval batches, edited tool parameters/rules, return-direct answers, shutdown before Core admission, and parent/child session isolation.
- Large inbox and execution journals: query cost, dispatcher scan frequency, materialized views/indexes, retention and bounded resource use across many sessions.
- Auth/runtime context is bound when the session runtime is created. Verify tenant namespace routing and application lifecycle policies for credential/context changes; avoid changing the identity's backend namespace under a cached runtime.
- Hosted Service end-to-end regression remains a separate campaign; SDK submission and Service command workers must not both own one execution.

Validation recorded for this change: 20 focused tests across execution identity, session commands, chat reconstruction and subagent context tests passed; Maven packaged the chat executable. Documentation checks passed for 456 pages and 504 redirects; the companion Java snippet compiled. A local browser/HTTP smoke run verified injection without wakeup, two ordered tasks, steering in the first run, three successful tools, and reload restoration of all 11 messages (cursor `smoke:416`), with no browser console errors. The temporary validation server was stopped.

## Agent API completion follow-up (2026-10-01)

Focused implementation checks are recorded in [agent-api-completion-20261001.md](agent-api-completion-20261001.md). Extend the later hosted-service campaign with:

- Gateway-to-Data-Plane HTTP admission, authentication and archived/deleted-session behavior for every new route; PostgreSQL manual DDL on upgrade fixtures, including action/source indexes and child parent_session_id.
- Real model tool-argument deltas (multiple calls, interleaving, fallback and provider-specific usage), large files and all media adapters; verify snapshot + suffix equals uninterrupted rendering at every disconnect point. Legacy public histories remain readable without invented old deltas.
- Action command crash before/after confirmation delivery and transactional outcome append; multiple worker polls, partial batches, rejected then corrected answers, timeout/resolved races and browser refresh.
- Steer admission at terminal boundaries across replicas, cancellation with unconsumed steering, inject while idle, checkpoint restore/fork racing queued/new submissions, unknown side effects and post-restore fresh execution.
- Linked nested children, async children after parent release, distinct cursors and owner isolation; child enumeration limits and export registration after process restart.
- Budget reservations versus model dispatch/finish crashes; missing usage, unknown pricing, parallel children, lease loss during reconciliation and configured custom pricing. Strict model-call reservations and reported-token/cost pre-call checks have different guarantees.
- Real HTTPS webhook receiver: raw-body signature, timestamp freshness, duplicate delivery, 2xx/4xx/5xx/timeouts, lease takeover, eight-failure pause, explicit retry, allowlist changes and session deletion. No outbound receiver was contacted by the focused tests.
- Shared BaseStore backends for immutable files, accounting, projection caches and resource-page expiration. Long histories, high event rates, slow readers, cold projection rebuild, cache CAS conflicts and retention/cleanup need capacity testing.

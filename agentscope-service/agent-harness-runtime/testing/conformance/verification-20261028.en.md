# Verification record

[中文](verification-20261028.md)

Checkout `/Users/ken/agentcore/agentcore-managed-agents`, branch `master`. Changes remain in the main directory, uncommitted, unpushed and undeployed.

| Check | Result |
| --- | --- |
| Agent Service / Java Brain / Hands Maven package, Java 17 target, `-T1` | Passed |
| `mvn -B -ntp -T1 spotless:check`, `git diff --check` | Passed |
| Focused Java tests | 99 passed: commits/recovery, tool ordering/late results, receipts, adapter isolation, JCS vectors, service transactions/budget and service wiring |
| TypeScript | Five packages build; ten tests pass |
| Protocol | Eight schemas, 14 HTTP operations, 82 positive cases, 23 negative cases and identity/JCS vectors pass |
| PI durable | Real 1.4.0 Harness/Storage reopens its root; writes remain invisible before ACK, failed persistence stops writes, restored IDs are not reused |
| Codex | Local 0.163.0-alpha.11.1 app-server initialized and exited; no model call or durable tool capability verification |
| Node production package | Local `pnpm deploy --legacy --prod` output successfully loads the PI adapter |

Service fault tests cover stale leases, original receipts, atomic native state/tool authorization, original tool results arriving after Brain exit, takeover recovery, process leases/fixed deadlines and idempotent model reservations. These checks do not establish real MQ ACK fault acceptance.

The naming audit still fails on three pre-existing frozen SQL digests and four stale source links. Those SQL files were not modified here. Migration-related source paths and route inventory were updated without rewriting historical hashes to hide failures.

Full-repository `clean verify`, Docker builds, real PostgreSQL/MQ/Redis fault injection and model end-to-end acceptance were not run. See the [implementation map](../../protocol/implementation-map.en.md) for Java scheduler DB coupling, PI scope/snapshot limits and Codex gating.

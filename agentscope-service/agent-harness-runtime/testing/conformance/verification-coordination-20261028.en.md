# AgentScope coordination migration verification

[中文](verification-coordination-20261028.md)

2026-10-28; primary checkout `/Users/ken/agentcore/agentcore-managed-agents`, branch `master`. Not committed or deployed.

## Changes and retained guarantees

This closes an access-boundary migration gap; the core guarantees already existed. Brain accesses coordination ledgers through Session Service's `/prepared/coordination` endpoint, reusing the original transactions and state transitions.

| Scope | Ownership after migration | Retained guarantees |
| --- | --- | --- |
| Process leases, Turn claims and input closure | Session Service validates process identity and runs transactions; Brain registers, renews and requests claims | Lost-response retries keep the original identity; expired processes cannot revive; takeover fences old epochs; renewal does not extend the fragment's fixed deadline |
| Session queue and input reads | Brain uses remote coordination | Queue generations, dispatch tokens and input-delivery records remain intact |
| FIFO ACK | Session Service owns `SessionTurnAckLedger`; the Brain consumer sends Broker ACKs | Only terminal Turns can be ACKed; no ledger transaction holds locks across Broker RPCs; replacement consumers can retry after exit or lost responses |
| MCP and children | Service stores frozen MCP descriptions and child relations for remote reads | MCP discovery/connections stay in Brain, preserving local commands/workspaces; committed tool intents create children |

Atomic tool-call commits, durable Hands intake and ACK ordering, unknown-outcome handling, and atomic result/resume publication remain unchanged. Interfaces, schemas, examples and bilingual ownership documentation are aligned.

## Results

- `mvn -B -ntp -T1 clean verify`: passed; 1,378 tests in this reactor, 1,001 passed, 377 skipped, zero failures/errors. Counts come from this build's summaries, excluding historical reports under old directories.
- `mvn -B -ntp -T1 spotless:check` and `git diff --check`: passed.
- Protocol `make check`: 9 schemas, 17 HTTP operations, 102 positive and 29 negative cases passed; Node.js passed 4 JCS and 4 identity vectors.
- All 7 new `SessionCoordinationHttpTest` cases passed: lost registration/claim responses, process takeover, fixed deadlines, ACK recovery, queue generations, Brain-local MCP discovery with remote pinning, and child relations. Tests use real HTTP and H2; Brain coordination clients receive no DataSource.
- Both short-tool and suspended/resumed `AgentScopeRemoteFlowTest` paths passed with real AgentScope and HTTP, including remote claim/input coordination. Model, message transport and external tools use test doubles.
- Packaging: the ACK ledger implementation is in the Session Service jar; common retains its interface. Brain excludes Session Service/Tool Worker, Agent Service excludes Adapter/Tool Worker, and Tool Worker excludes Adapter/Session Service.

## Remaining acceptance

Real PostgreSQL, RocketMQ, Redis and Sandbox deployment/failure acceptance did not run in this iteration. Local JPA catalog/memory and shared infrastructure wiring still require a database in Brain. Redis execution leases/input delivery and MQ consumption/Broker ACK remain runtime responsibilities. Completing these AgentScope coordination paths does not make every Brain database-free.

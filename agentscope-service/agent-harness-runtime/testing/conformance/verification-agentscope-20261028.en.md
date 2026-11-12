# AgentScope flow verification

[中文](verification-agentscope-20261028.md)

Completed on 2026-10-28 in `/Users/ken/agentcore/agentcore-managed-agents`, working branch `master`. No commit, push or deployment. The previous cross-language implementation record remains in [verification-20261028](verification-20261028.en.md).

## Results

AgentScope execution and recovery pass local integration regression. Fixes are listed in the [implementation map](../../protocol/implementation-map.en.md#agentscope-flow-verification).

- `AgentScopeRemoteFlowTest`: real kernel, HTTP, H2 transactions and production Hands admission/execution-ledger code. Short tools return inline; long tools use the Session queue, suspension, Resume and a rebuilt Agent. Both assert one business-tool start, two model calls, applied input, distinct model reservations, a final checkpoint and public projections.
- Inject HTTP 503 after a successful commit and verify original-receipt lookup. Remote workspace checks cover complete timestamp persistence, frozen reads, no repeated mutation, CAS conflicts and stale-owner rejection.
- `AgentServiceAppTest`: a real Spring HTTP application verifies internal Bearer authentication, protocol JSON codecs and a 2 MiB native-object round trip.
- Existing regression covers business versus infrastructure failures, cancellation and external replies, budget denial, old owners, checkpoint/tool atomicity, unknown effects and late results.

**102 focused Java cases passed** across targeted runs, not a full-repository suite: `ManagedSessionStoreTest` 50, `HarnessProtocolStoreTest` 5, `AgentScopeRemoteFlowTest` 2, `ManagedFailureIntegrationTest` 16, `ManagedToolRecoveryTest` 6, `ManagedToolWorkerReliabilityTest` 17, `AgentServiceAppTest` 4, and 2 Brain wiring tests.

| Check | Result |
| --- | --- |
| Agent Service / AgentScope Brain / Hands Maven package, `-T1`, Java 17 target | Passed |
| Repository `spotless:check`, `git diff --check` | Passed |
| Static Compose parsing and local startup script syntax | Passed |
| Production JAR inspection | AgentScope Adapter is packaged only in Brain; Agent Service depends on it only for tests |

Quick HTTP regression from the repository root:

```sh
mvn -B -ntp -T1 -pl managed-agents/agent-service -am test \
  -Dtest=AgentScopeRemoteFlowTest,AgentServiceAppTest \
  -Dsurefire.failIfNoSpecifiedTests=false
```

## Remaining acceptance

Models, sandbox and MQ/Redis transports are doubles; the database is H2. Full `clean verify`, container deployment, real PostgreSQL/RocketMQ/Redis/Sandbox, OSS and model-provider acceptance were not run. Prioritize real instance recovery, SSE reconnect, Brain/Hands termination, service outages and late results. Java scheduling, MCP and child coordination still depend on shared DB/Redis/MQ; these dependencies are retained. PI/Codex were not expanded.

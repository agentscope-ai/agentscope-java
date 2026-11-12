# MCP recovery extension v1 cluster acceptance

[中文](recovery-v1-acceptance.md)

Passed on 2026-10-19 at 22:39:33 Asia/Shanghai. The lost in-flight result after Worker failure is
addressed by the [recovery contract](../../docs/mcp-execution-recovery.en.md) and shared server-side
persistence. The [initial failure record](dual-replica-recovery.en.md) is preserved unchanged.

## Deployment

- Code: `/Users/ken/agentcore/agentcore-managed-agents`, branch `master`.
- Includes the latest workspace Runtime/MQ changes as explicitly confirmed by the user. No original AgentScope Java project or binary dependencies were changed.
- Application images: `2.1.0-build.20261019-213410-7a94c5bc`, ACR namespace `agentcore`, amd64/arm64.
- Cluster namespace `agencore-ma`, Helm release `ma` revision 9; two replicas per component.
- Two HTTP MCP replicas, pinned to `sha256:a66b99e8cf06b862e67d0a87cdf364ad39b787e6e13a2fbe35f54478dd00be3d`.
- Independent `mcp_fixture.execution` table and DML account. Platform SQL baselines/table names/MQ group names are unchanged; no Sandbox or NAS was added.

## Results

| Case | Result | Physical tool starts/completions | Final state |
| --- | --- | --- | --- |
| Real 90-second tool, Suspend/Resume, injection and queued T2 | Passed | 1 / 1 | T1/T2 completed |
| Delete the actual launching Worker Pod | Passed | 1 / 1 | Original remote result recovered; T1/T2 completed |
| Delete the original Runtime Pod | Passed | 1 / 1 | Another instance recovers T1; T2 follows |
| Interrupt/follow-up with queued T2 | Passed | Outside this script's physical-count assertions | T1 cancelled; T2/T3 completed |

Worker fault Session: `bdd31f70-49f2-502b-ab3e-b89df30a3c82`. After deleting
`ma-managed-agents-tool-worker-5b4cdf6f7d-fndq9`, instance
`ma-managed-agents-tool-worker-5b4cdf6f7d-jd57x` queried the original execution and received SUCCEEDED.
The original task_id/execution_id were retained; call_id was `cc8493a2-295d-4128-bee7-d6d636ed91d6`.
Database facts confirm one Suspension, one resumed record and one Resume notification. Injected
input became applied, both final replies referenced the same real call_id, and T1 completion
preceded T2 execution.

Runtime fault Session: `a03431d2-8b84-5951-8e6a-d1616cbfb978`; instance
`ma-managed-agents-agent-harness-runtime-fdbc6df77-8m5l4` recovered the original T1. Faults delete
the actual execution owner with a one-second grace while the real MCP call is active and
T1/T2 are waiting_tool/accepted.

## Checks and evidence

- Maven clean verify: 724 discovered, 567 passed, 157 environment-gated skips, no failures/errors; includes recovery, wrong identities, lookup outages, original failure/cancellation and key-order cases.
- Go checks passed; console 189 tests, release tooling 43 tests, and seven real HTTP checks across two processes sharing PostgreSQL passed.
- [Summary and image digests](../../release/dist/mcp-recovery-deploy/summary.json).
- [Baseline](../../release/dist/mcp-recovery-deploy/baseline/report.json), [Worker fault](../../release/dist/mcp-recovery-deploy/worker-restart/report.json), [Runtime fault](../../release/dist/mcp-recovery-deploy/runtime-restart/report.json), [interrupt](../../release/dist/mcp-recovery-deploy/interrupt/report.json).
- Local ignored evidence includes API output, physical MCP records, original Pod identities, recovery logs, database facts and publication manifests.
- Final state: all five components 2/2 Ready, two ready Service backends each, two independent MCP replicas, public health checks passing. Infrastructure and other namespace workload specifications are unchanged; the prepared manual Session remains empty.

## Scope

Servers must advertise and implement durable admission, stable-identity deduplication and result
lookup. Ordinary MCP servers without this contract and older executions lacking recovery identity
retain unknown-result protection and are not automatically replayed. This acceptance covers Worker/
Runtime Pod failure, not failure of the MCP execution process or PostgreSQL itself.

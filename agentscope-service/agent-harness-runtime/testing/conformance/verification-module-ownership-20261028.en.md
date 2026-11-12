# Java module ownership verification

[中文](verification-module-ownership-20261028.md)

Layout update (2026-11-02): the structure below describes the original implementation. The former Session Service submodule now lives under `agent-service/src/`, together with the relevant cross-role integration tests. Historical verification results do not verify this later consolidation.

2026-10-28; primary checkout `/Users/ken/agentcore/agentcore-managed-agents`, branch `master`. No commit or deployment.

## Changes

- AgentScope SessionLog integration, execution/recovery, checkpoint preparation, model capture and tool adaptation now belong to `java/adapter-agentscope`. Common no longer discovers adapters through SPI.
- The internal `agent-service/session-service` library owns the existing prepared transaction, publication/result repair, resume notifications, lifecycle and projection processing. It remains in the Agent Service image/JVM.
- Tool Worker owns consumption, control, Sandbox bindings and its execution ledger. Shared execution code uses `ManagedToolWorkStore` for cancellation policy and result finalization rather than identifying a concrete Hands class.
- Integration tests explicitly compose implementations without adding production dependencies. Tool Worker deploys with `*-exec.jar`; startup and Docker selection are updated.
- Transaction algorithms, native payloads, tool identities and ACK ordering are preserved. Shared ledger access remains in common; the legacy Java scheduler still needs DB/Redis/MQ.

## Results

- `mvn -B -ntp -T1 clean verify`: passed. The current reactor ran 1,359 cases: 985 passed, 374 skipped, zero failures/errors. Historical reports under the old `agent-harness-runtime/target` were excluded.
- `mvn -B -ntp -T1 spotless:check`, `git diff --check`, and `bash -n managed-agents/scripts/dev-up.sh`: passed.
- Jar inspection confirms ownership: common contains neither AgentScope execution, the Hands ledger nor prepared transactions. Production Brain excludes Session Service/Tool Worker; Agent Service excludes Adapter/Tool Worker; Tool Worker excludes Adapter/Session Service.
- Regression coverage includes atomic commits, original receipts, stale owners, short/long tools, lost responses, suspension/recovery, inputs/budgets, child tasks, MCP, private workspace and application wiring.

The full check corrected an outdated MQ assertion: after Session Service takes over publication, both queue modes must allow tool commands and resume notifications.

The additional naming audit retains seven existing issues: three SQL files differ from frozen hashes, and four bilingual links reference removed `ManagedPostgresObjects`/`ManagedContinuationLoop` sources. This change neither modified those SQL files nor weakened the audit; links affected by this relocation were updated. Real-infrastructure fault acceptance, container image builds and deployment remain outstanding.

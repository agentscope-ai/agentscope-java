# Harness behavior regression baseline

[中文](README.md)

Current Java HTTP platform implementation/verification: [phase record](verification-java-platform-http-20261030.en.md), including storage-free assembly, task callbacks, shared leases, native tools/recovery, and remaining rollout/owner-directed control gaps.

See the [AgentScope model record](verification-agentscope-models-20261029.en.md) for prepared selection, factory wiring, and native OpenAI / Anthropic requests.

See the [model-binding record](verification-model-bindings-20261029.en.md) for generic catalog resolution, PI credential references, and native Codex parameters.

Versioned configuration identity, digest checks, and PI construction from sealed definitions are covered in the [configuration verification record](verification-configuration-20261029.en.md).

See [verification-public-events-20261029](verification-public-events-20261029.en.md) for cross-kernel durable messages, model spans, reported usage, and large-content object references. See [verification-previews-20261029](verification-previews-20261029.en.md) for targeted online-text verification.

Shared behavior acceptance for Java, Session Service and TypeScript. Offline field/example checks remain in [protocol](../../protocol/README.en.md).

During iteration, default to affected-module compilation, contract validation and focused path tests. The full-repository and real-infrastructure commands below are for milestone/final acceptance, not every edit. Managed Agents is unreleased; do not add historical-format compatibility tests.

The latest AgentScope results are in [verification-agentscope-20261028](verification-agentscope-20261028.en.md).


Cross-component Brain/Hands/platform integration tests live in Agent Service tests. Test dependencies compose the roles without adding production implementation dependencies.

Latest module ownership verification: [verification-module-ownership-20261028](verification-module-ownership-20261028.en.md).

Latest coordination migration verification: [verification-coordination-20261028](verification-coordination-20261028.en.md).

Boundary convergence verification: [verification-boundary-convergence-20261028](verification-boundary-convergence-20261028.en.md).

## Required behavior

Platform transaction and cross-role integration tests primarily live in `managed-agents/agent-service/src/test/java/`. Adapter unit tests that do not depend on the Session Service implementation remain in `managed-agents/agent-harness-runtime/java/adapter-agentscope/src/test/java/`.

| Scenario | Test entry points | Required result |
| --- | --- | --- |
| AgentScope flow across HTTP | Agent Service tests `AgentScopeRemoteFlowTest`, `AgentServiceAppTest` | Real kernel and HTTP, short/long tools, original-result recovery, lost receipts, input/budgets, private storage and authentication |
| Remote runtime coordination | Agent Service test `SessionCoordinationHttpTest` | Lost-response retries, expired-process fencing, fixed deadlines, queue generations, ACK recovery, Brain-local MCP discovery and child relations |
| Duplicate commit, lost response, old owner | `ManagedSessionStoreTest`, `ManagedPostgresSessionStoreIntegrationTest` | Original receipt, one commit; changed content rejected; takeover after preparation still fences the old writer |
| Adapter/transaction separation | `ManagedSessionStoreTest.platformTransactionConsumesExplicitChangesWithoutReadingNativePayloads` | Explicit platform changes commit without interpreting AgentScope native payloads |
| Checkpoint/task/Outbox rollback | `ManagedSessionStoreTest.adapterEvidenceCannotOverrideDurableToolIdentityAndRollsBackTheWholeBoundary`, `ManagedJournalStoreTest` | Adapter evidence cannot override durable execution identity; no partial commit |
| Execution/ACK, unknown, cancellation | `ManagedToolWorkerReliabilityTest`, `ManagedToolWorkerLoopTest` | Durable intake before ACK; normal delivery starts after confirmed ACK; no blind retry of unknown effects |
| Reordered/late results, wakeups | `ManagedToolWorkerReliabilityTest`, `ManagedToolResultDeliveryIntegrationTest` | Buffered final results are not ready; preserve original results and atomic result/Resume |
| Result-before-suspension and handoff | `ManagedSuspensionsTest`, `ManagedResumeHandoffTest`, `ManagedPostgresResumeHandoffIntegrationTest` | No lost wakeup; durable takeover or deferral before ACK |
| Native recovery, inputs, model and budget | `ManagedSessionStoreTest`, `ManagedToolRecoveryTest`, `ManagedJournalStoreTest`, `ManagedBudgetStoreTest` | No rerun of confirmed tools; recoverable result application; no missing/double input, model or usage settlement |
| Finalization, private workspace, projections | `ManagedSessionStoreTest` | Recoverable frame/model/private-I/O receipts; public projections do not move native recovery positions |
| Cross-language identities/fixed-byte digests | `HarnessProtocolVectorsTest` + protocol `make check` | Shared UTF-16 framing, UTF-8 and SHA-256 vectors; not proof of a Java JCS encoder |

Other languages must pass equivalent behavior, not replicate Java class names. PI/Codex additionally validate their native recovery, tool interception and result reinjection.

## Codex Rust

From the repository root, with Rust 1.95.0 and the Python environment described in protocol/README:

```sh
make -C managed-agents/agent-harness-runtime rust-check
cd managed-agents/agent-harness-runtime/rust
MANAGED_CODEX_TRANSCRIPT="$PWD/target/codex-transcript.json" cargo test --locked -p managed-adapter-codex --test durable_roundtrip
/tmp/managed-agents-protocol-venv/bin/python scripts/validate-transcript.py target/codex-transcript.json
```

`durable_roundtrip` uses real codex-core for partial results from two tools, owner takeover, lost receipts, SIGKILL followed by cross-process recovery, and subsequent user Turns after success/cancellation. It checks native thread/Turn/call identities and original results. Model HTTP, Session Service transport and Hands results are test doubles. `runtime/tests/recovery.rs` covers authorization, confirmations, budgets, late receipts and identity checks; `proxy` rejects unauthorized local tools before Core consumes them. Container, real MQ and external-tool acceptance remain separate requirements.

## Commands

From the repository root:

```sh
mvn -B -ntp -T1 clean verify
mvn -B -ntp -T1 spotless:check
make -C managed-agents/agent-harness-runtime/protocol check PYTHON=/tmp/managed-agents-protocol-venv/bin/python
```

Protocol environment setup is in its README. Focused Java regression:

```sh
mvn -B -ntp -T1 -pl managed-agents/agent-service,managed-agents/agent-harness-runtime/java/adapter-agentscope -am \
  -Dtest=HarnessProtocolStoreTest,HarnessProtocolVectorsTest,SessionStoreProtocolTest,ManagedSessionStoreTest,ManagedJournalStoreTest,ManagedBudgetStoreTest,ManagedSuspensionsTest,ManagedToolWorkerReliabilityTest,ManagedToolRecoveryTest \
  -Dsurefire.failIfNoSpecifiedTests=false test
```

PostgreSQL tests use `MANAGED_DATABASE_TEST_URI` and create/drop isolated temporary databases; they skip when it is absent. Use a dedicated test instance; credentials and infrastructure setup are described in the [infrastructure test guide](../../../tests/managed-mq-redis/README.md). Real MQ/Redis/process-failure suites have their own environment gates. Report passes and skips separately; Maven success does not imply those gated scenarios ran.

```sh
cd managed-agents/agent-harness-runtime/typescript
pnpm -r build
pnpm -r test
```

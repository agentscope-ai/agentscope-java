# AgentScope boundary convergence verification

[中文](verification-boundary-convergence-20261028.md)

Layout update (2026-11-02): the structure below describes the original implementation. The former Session Service submodule now lives under `agent-service/src/`, together with the relevant cross-role integration tests. Historical verification results do not verify this later consolidation.

2026-10-28; main checkout `/Users/ken/agentcore/agentcore-managed-agents`, branch `master`. Not committed or deployed.

## Changes

- Added `java/codec-agentscope`, a pure native-format library implementing `NativeCheckpointCodec` for Service checkpoint validation/baselines. Its private registry explicitly includes finalization events without relying on Adapter initialization in another process; unknown required events still fail.
- Moved checkpoint transactions into `agent-service/session-service`; only admission holds remain shared. Native TaskRepository, JPA AgentStateStore and SkillRepository implementations belong to the adapter. Preview transport accepts neutral text deltas, and child results carry opaque native JSON.
- Brain proposal publication, proposal/MCP batch reads, Resume status and deferral use Session Service. Only Service starts cancellation forwarding. HTTP 409 preserves platform conflict codes used in recovery decisions.
- Removed the unreleased legacy Hands-notice upgrade path; only canonical Service Resume remains. Added prepared interfaces, schemas, OpenAPI, positive/negative examples and route inventory; authorization/execution/result and ACK boundaries are preserved.

## Verification

- Codec module isolation: the execution adapter is absent, the SDK global registry still rejects finalization events, and the private codec validates them. Unknown required and malformed finalization events remain rejected.
- Checkpoint regressions: finalization records validate and fork; unsupported kernels cannot acquire holds; existing permission, workspace import, atomic baseline and rollback cases remain.
- HTTP regressions: short/long tools and a rebuilt Agent; lost proposal response retries with one unauthorized ledger entry; stale owners/missing Resume retain conflict codes. Proposal clients use a DataSource that rejects connections to prove no Brain SQL access.
- Offline protocol checks: 8 schemas, 16 OpenAPI operations, 86 positive/25 negative cases, plus JCS and identity vectors.

- After cleaning, `mvn -B -ntp -T1 verify` passed all 13 modules: 1,368 current-reactor cases, 991 passed, 377 skipped, zero failures/errors. The initial `clean verify` stopped on a workspace formatting change in HarnessRunConfiguration; logic was preserved, formatting fixed, and full verify passed.
- `mvn -B -ntp -T1 spotless:check` and `git diff --check` passed.
- Production package checks passed: Agent Service adds only the lightweight codec, without execution Adapter/Hands. Brain excludes Session Service/Hands. Hands excludes codec/Adapter/Session Service. Common contains none of the moved checkpoint transactions, native repositories/state store, or legacy notice class.
- Logs: `/tmp/managed-converge-final-verify.log`, `/tmp/managed-converge-spotless.log`, `/tmp/managed-converge-protocol.log`.

## Remaining

Legacy Turn activation/takeover, process leases, FIFO ACK, MCP catalog and child coordination still access shared ledgers. Some native envelopes remain engine-specific. Real infrastructure, container faults and deployment acceptance were not run; offline success does not replace them.

Naming audit retains existing frozen-SQL hash, stale source-link and workspace PI test naming issues. Unrelated SQL/PI behavior was not changed. The new route inventory is current.

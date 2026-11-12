# Durable Java verification

> Original dates, results, numbers and commands remain evidence for that historical version, not current acceptance claims.
> Historical platform-repository and live-service checks have not been rerun here; see the [library README](../../README.md) for current checks.
> The former queued/queued-hands example now lives at brain-hands/echo-tool. See the [examples overview](../../../agentscope-examples/durable/README.en.md) for current names and independent Brain/Hands JAR entry points; historical verification does not establish that those new entries pass.

Verified on 2026-11-08 in `/Users/ken/agentscope-3/agentscope-java`, branch `automatic-acr-release-durable`. Managed Agents is implemented separately in `/Users/ken/agentcore/agentcore-managed-agents`, branch `durable-ma`. No checkout copy, commit, push or deployment was made. Imported source manifests are provenance, not the current module list.

## Evidence

| Check | Result and scope |
| --- | --- |
| Durable standalone reactor | 12 reactor modules (10 JARs); Java 17 target; compile/package/install and Spotless passed. 161 tests reported: 160 passed, one optional Pi HTTP test skipped for the Java-only run. |
| State/recovery contracts | Shared SQLite and HTTP contracts cover receipts, incremental journals, fencing, claim-response loss, checkpoints/restore/fork, private resources, immutable object references, inputs, signals, cancellation and parent-bound child Runs. PostgreSQL executes the same contracts against a real isolated PostgreSQL 17 instance. |
| Native AgentScope | Real Harness/ReAct agents with deterministic models cover replay, permissions, external results, workspace and child recovery, fixed finalization context and inline durable memory maintenance. These are not arbitrary Java stack replay. |
| Hands and MQ | Worker receipts, cancellation before start, lost publication/ACK, duplicates, independent controls, continuous output/gap handling and sealed terminal results. A real RocketMQ 5.3.2 Broker/Proxy and separate JVMs also completed the recovery examples. |
| Provider adapters | MCP and parallel batches; local POSIX Sandbox processes; database/file/S3/OSS object adapters. S3/OSS tests use controlled fixtures, not paid cloud services. Unqueryable effects retain UNKNOWN. |
| Independence | Dependency trees contain no Managed Agents, AgentCore or Service artifacts; original clean-cache bootstrap built framework dependencies from this checkout. No platform classes are required. BOM/shade sync passes (57 extensions / 71 BOM modules). |
| Managed platform | Independent Java reactor, Go control-plane tests/build, Console tests/lint/build, SDK and deployment tests pass; detailed scope is in the Managed repository verification record. |
| Process acceptance | Three packaged JVMs + real PostgreSQL/RocketMQ: kill Brain after dispatch, restart Hands, recover original Run; two primary model calls, one auxiliary call and one tool side effect. Event replay, binary files, artifact publication/deletion, budget, restore/fork, Go/Python/DSH clients, archive/reactivate and deletion waiting for cancellation were exercised. |
| Protocol | Shared versioned schema and projection fixtures preserve string sequences and structured content. TypeScript projection tests pass; other-language runtime parity is not part of this work. |

## Reproduce

```sh
python3 agentscope-durable/scripts/verify-independent.py --java-only
# With current Core/Harness artifacts installed:
mvn -B -ntp -T1 -f agentscope-durable/pom.xml -Pdurable-standalone clean verify
# Same contracts against an isolated PostgreSQL database:
mvn -B -ntp -T1 -f agentscope-durable/pom.xml -Pdurable-standalone \
  -Dtest=DurableBackendTest,HttpBackendContractTest,BackendAssemblyTest,QueuedExecutorTest,RocketMqExecutionTest \
  -Dsurefire.failIfNoSpecifiedTests=false \
  '-Ddurable.test.jdbcUrl=jdbc:postgresql://localhost:5432/test?user=test' test
bash .github/scripts/check-shade-and-bom-sync.sh
```

See the [echo-tool example (current location of the former queued Hands example)](../../../agentscope-examples/durable/brain-hands/echo-tool/README.en.md) and implementation plan (`../archive/platform-implementation-plan.en.md`, retained in Git history). Managed process fixture: `managed-agents/tests/durable-platform/run.py` in the platform repository. Use only isolated test databases/brokers.

## Limits

Root AgentScope `clean verify` remains blocked during POM resolution by the existing duplicate `agentscope-service` parent mismatch. That directory is excluded by user instruction and was not repaired. Therefore the root reactor and final aggregate distribution are not claimed to pass.

No real cloud Sandbox, OSS/S3 account, Kubernetes deployment, production failover/load benchmark or artifact publication was performed. Provider UNKNOWN reconciliation and data-retention/GC policies remain explicit operational responsibilities. Platform-specific unsupported configuration is rejected at admission, as documented in Managed Agents. No cross-language runtime completion or legacy data migration is claimed.

## Incremental acceptance for this design review

The review matrix (`../archive/platform-implementation-plan.en.md#review-against-the-original-design-2026-11-08`, retained in Git history)
maps requirements to implementation. This review adds 19 behavioral checks (including the
shared Backend contract exercised through SQLite and HTTP). The complete Java reactor reports
161 tests, 160 passed, with only the optional Pi HTTP test skipped.

- Static readiness selects the definition/state index before limiting results; incompatible hosts do not quarantine another host's recoverable Run.
- Dynamic/multiversion capability queries neither claim work nor invoke a model, reject incompatible versions and conform to the shared schema.
- Direct Brain and Worker share recovery decisions: recovered handles survive cancellation and lost replies; query failure does not block independent cancellation; an idempotent provider without lookup can reuse its original start identity; uncertain non-idempotent effects are never repeated.
- UNKNOWN reconciliation covers ownership, durable receipts, changed-content conflicts, stale-owner rejection, output gaps, the original queue inbox/outbox, Backend acceptance and native result application. The same tests run on real PostgreSQL.
- An independent Node JSON implementation generates an execution identity fixture validated by Java. Duplicate keys, multiple root values and lossy numbers are rejected. All five shared TypeScript projection/runtime fixture tests still pass; its Runtime is not claimed to implement the new Java APIs.
- The 12-module rebuild uses the existing isolated cache with no platform artifacts. All 12 dependency trees exclude Managed/AgentCore/Service. BOM/shade, 16 documentation tests, documentation build and link checks pass.

Reconciliation is a framework host API; the platform has no operator API/approval UI yet.
Evidence verification, operator authorization, object retention/GC and real cloud acceptance remain deployment responsibilities.

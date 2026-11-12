# Java Durable and Managed Agents implementation plan

[中文](platform-implementation-plan.md)

Status: Java execution path implemented; external environment acceptance is listed separately. The user authorized completing Durable first, then replacing the Managed Agents implementation directly. No old execution kernel, dual protocol or compatibility path is retained. Scope: Java / AgentScope only.

Checkouts: `/Users/ken/agentscope-3/agentscope-java` (`automatic-acr-release-durable`) and `/Users/ken/agentcore/agentcore-managed-agents` (`durable-ma`). Preserve existing changes; ignore the duplicate AgentScope `agentscope-service`. Do not commit, push or deploy.

## Implementation and acceptance

| Stage | Scope | Acceptance |
| --- | --- | --- |
| D1 | Partitioned atomic storage, incremental native journal, ready index, version resolution, claim receipts, capacity and drain | Shared SQLite/PostgreSQL/HTTP contracts; ordinary writes do not rewrite history; lost claim responses; admission/shutdown tests |
| D2 | Object references, historical checkpoints, restore/fork, input application/withdrawal, transactional authorization | Integrity/isolation; immutable commits; idle guards; recovery; competing input/withdrawal |
| D3 | Hands source events, contiguous prefix, output gaps/replay, separate control channel, large results | Duplicates/reordering, early cancellation, output sealing, lost publication/ACK, real broker |
| D4 | AgentScope structured results, model/finalization recovery, workspace, child agents, MCP batches, previews | Native Harness recovery/cancellation, permissions, partial batch takeover, original content preservation |
| D5 | Independent build, dependency audit, process examples, protocol/docs | Java 17; no Managed dependencies; tests, format, BOM and full build with external blockers reported |
| M1 | Platform hosts Durable Backend, Java Brain and Hands; retains product configuration/policy | Cross-service text/tool/cancel/recovery/SSE; no Brain DB/MQ; one execution state machine |
| M2 | Remove replaced kernels, adapters, protocols, configuration and tests; update deployment/docs | No legacy activation switches or dual execution; platform build/integration checks |

Runtime, HarnessAdapter, SessionBackend and Executor remain the four main concepts. Storage/transport providers are extensions; identity, policy, pricing, credentials and environment management stay in the platform. Persistent facts authorize work and recovery; notifications only accelerate discovery.

## Outcome

D1–D5 and M1–M2 are implemented for the Java/AgentScope execution path. Verification and deployment limits are recorded in [verification](../verification/verification.en.md).

- Durable owns state, native journal/checkpoints, immutable object references, recovery/fork, inputs, events, fencing, queued execution and Worker receipts. Runtime, HarnessAdapter, SessionBackend and Executor remain the four entry concepts.
- Managed Agents directly composes the framework: Agent Service hosts Backend and policy, Java Brain uses HTTP, Hands owns provider execution, and Control Plane keeps product resources/authorization. There is no second platform execution kernel or Redis coordination layer.
- MCP, Sandbox, parallel leaf batches, workspace child Runs and transactional shared-memory operations are integrated. Resource definitions freeze at admission; live policy and credentials stay platform responsibilities.
- The replaced Java engine, TypeScript/Rust Brain implementations, old protocols, startup switches and obsolete tests have been removed. Console, Java/Python/DSH clients, Compose, Helm, schema jobs and MQ provisioning use the new path.
- Real PostgreSQL + RocketMQ + three packaged JVMs passed restart/cancellation/resource/recovery verification with one tool side effect. Public SDK calls were exercised against the packaged service.
- No compatibility migration, production deployment or cloud-provider acceptance is claimed. JEV, external skill repositories, catalog multiagent references and self_hosted environments are explicitly rejected by the platform until their adapters exist. Workspace skills/child definitions and cloud/local Sandbox are supported.
- The duplicate `agentscope-service` directory remains untouched. Its pre-existing parent-POM mismatch blocks the root reactor; the Durable standalone and Managed Agents reactors build independently.

## Review against the original design (2026-11-08)

This review compares the Java implementation and actual Managed Agents wiring with the [original Durable design](https://aliyuque.antfin.com/ad3swf/gg3g1v/qecdop0f9bdu5irs?singleDoc). Keep the four main concepts. Do not add a common inference loop or move platform identity, credentials and environment provisioning into the kernel.

| Design requirement | Implementation and changes in this review |
| --- | --- |
| Separate native state, execution facts and public events | The Adapter owns private journal/checkpoints; Backend stores admissions, intents, outcomes and application acknowledgements; public projection/cursors exclude native state. Native positions, output byte offsets and public sequences remain distinct. |
| One Backend entry point for adoption | LocalBackend assembles SQLite, objects and notifications. Production can use PostgreSQL/object storage and optional hints. RecordStore is an advanced SPI; MQ is not authoritative state. HTTP forwards the same backend contract. |
| Brain/Hands separation | Service hosts Backend and queue inbox/outbox; Brain uses HTTP; Hands owns a separate execution ledger and providers. Commands, controls and execution events use three topics. SSE reads persisted public events. |
| Persist before execution; reconnect after an uncertain reply | Atomic checkpoint+intent, commit receipts and fencing already exist. Runtime and Worker now share ExecutionRecovery: preserve a recovered handle, independently attempt supported cancellation, and reuse the original ID for an idempotent provider without lookup. Never repeat an uncertain non-idempotent effect. |
| A verification path for UNKNOWN | ExecutionStore.reconcile requires a valid Hands lease, an UNKNOWN effect, a verified terminal outcome and evidenceRef. It atomically records an audit receipt and outbox events. Same-ID retries reuse the result; conflicts fail; missing output is sealed with an explicit gap before the ordinary result delivery resumes Brain. This is a host operations API, not a new public RPC, MQ control command or model tool. |
| Discover compatible work | HarnessSelector selects an indexed definition/state pair before limiting results. Other-version backlogs cannot starve supported work. Incompatible hosts leave work recoverable instead of committing runtime.error waits. Storage format is now 3; old development layouts are rejected. |
| Capabilities reflect the pinned definition | runtime.capabilities(definition) resolves dynamic Harness definitions and rejects incompatible state. The global view lists resolvedHarnesses. Queries neither claim Runs nor invoke models. A Managed Brain HTTP integration test covers this path. |
| Language-neutral identity | Schema, canonical SHA-256, stable execution IDs and projection fixtures do not depend on Java classes. Reject duplicate JSON keys, multiple root values and numbers that lose precision during canonicalization; encode exact large numbers as strings. Future languages may define their own interfaces but must reproduce the same identities and failure semantics. |
| Harness-owned recovery boundaries | AgentScopeAdapter uses native logs, middleware and tool-result boundaries; Core/Harness do not depend on Durable. Shared backend contracts and recovery/reconciliation tests use plain Harness/Executor fixtures without AgentScope. A second production Harness still needs its own adapter and acceptance evidence. |
| Policy, budgets and environments | The platform freezes definitions and checks/reserves budget in admission/commit transactions. Credentials and Sandbox identity remain platform-owned. Durable does not migrate running processes or automatically replace an unknown Sandbox. |
| Public reads, output and retention | Snapshot+cursor, persistence before publication, reconnection and expired-cursor resync already have contracts. MQ delivers a contiguous source prefix; output/status events cannot be coalesced across gaps. A direct Executor's output capability means a byte-reading API; it does not automatically mirror every direct output page into Session SSE. Hosts choose retention/GC policy. |

To reconcile UNKNOWN, the host first queries `reconciliation(request, requestId)`. If no receipt exists, claim the same Hands ledger item, independently verify the original provider operation, then call `reconcile(work, command)`. The host owns operator authorization and evidence verification; timeout alone never proves success, failure or cancellation. MQ hosts use `TransactionalExecutionStore(records, executorName)` with the same pool as RocketMqWorker. Ordinary source events deliver the verified result through the existing queue; the platform must not separately overwrite Run or Session outcomes.

Managed Agents keeps product admission, tenant authorization, model/tool definitions, Vault, charging policy, Sandbox provisioning/lifecycle, UI and product APIs. Generic recovery decisions, state machines, Worker ledgers, reliable delivery and event protocols have one implementation in Durable. The platform does not yet expose an operator reconciliation API/approval UI; this review supplies the framework entry point for a controlled operations host.

Interfaces do not establish cloud acceptance. Real cloud Sandbox/OSS/S3 accounts, production load/failover, other language runtimes, a second production Harness and running migration between Harnesses still require separate evidence. Results are recorded in [verification](../verification/verification.en.md).

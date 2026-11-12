# Java Brain platform interface refactor: implementation and verification

[中文](verification-java-platform-http-20261030.md)

Date: 2026-10-30. Directory `/Users/ken/agentcore/agentcore-managed-agents`, branch `master`. Existing changes were preserved; no commit, push or deployment. This records the first implementation of the [platform interface design](../../../docs/design/brain-platform-interfaces.en.md), not feature parity across kernels or production fault acceptance.

Later deployment update: the undeployed statements below describe the implementation checkpoint. ACS deployment and basic acceptance subsequently completed on the same date; see the [deployment record and open P1](../../../../docs/acs-java-http-acceptance-20261030.en.md).

## Implemented basic flow

| Capability | Implementation and boundary |
| --- | --- |
| Configuration and construction | Reuse the admitted configuration, model binding, definition material and AgentScope factory through Session Service. Model providers and configured MCP endpoints remain kernel calls. Memory documents now use a Service proxy constrained to the Session's admitted mounts. |
| Ownership | Java `ExecutionAccess` / `SessionExecutionAccess` use shared claim/renew/release. Uncertain claim/renew responses retry the same identity; local validity uses conservative monotonic time. Prepared commits check the same lease. An old owner cannot write or release a successor. |
| Task notification | Service subscribes to existing after-commit hints and sends authenticated notifications to a configured stable Brain address. Capacity rejection is 429; 202 only admits a hint. Claim alone grants ownership. Durable queue facts and a 30-second repair scan remain authoritative; no second delivery ledger or Brain registration is required. |
| Ordinary API | Java HTTP mode puts ordinary newly created Sessions on the internal durable queue without a public extension header. Public queue extension enablement remains separate. Obsolete Brain Ready/Resume MQ hints are suppressed in HTTP mode; Hands MQ remains. |
| Native state and logs | SessionLog, checkpoints, model outcomes, objects and private workspace access use existing prepared/object ports. Brain assembly creates no JDBC/JPA/Redis/MQ clients. Native Sessions recover from their journal; unused JPA AgentStateStore is omitted rather than double-written. Native scratch directories may remain local. |
| File input and tools | Service resolves file references. Prepared commits admit tool intents; Service persists Hands outcomes; Brain reads them over HTTP before applying them. Stable task/execution IDs, deduplication and recovery boundaries remain. |
| Inputs and cancellation | Read accepted input, closure and lifecycle facts from Service. Active control currently uses bounded periodic reads. **Owner/incarnation-directed control callbacks are still pending**; a load-balanced task hint does not prove cancellation delivery. |
| Events and delta | Durable events retain Service projection. Native model requests carry purpose so previews use shared active-reasoning checks. Preview publication has its own bounded queue, no retries, and Unicode-safe protocol chunks. Preview loss does not change durable outcomes. |
| Native SDK bus | `ManagedSessionMessageBus` exposes an empty native inbox and rejects unsupported native messaging writes. Managed input uses platform facts. This avoids the SDK's WorkspaceMessageBus trying remote filesystem operations without execution context; it does not enable multiagent support. |

No blanket language rewrite: Java adds the execution capability interface and reuses existing narrow clients/adapters elsewhere. TypeScript/Rust algorithms remain unchanged. Added schema fields/operations are optional or additive; old vectors remain valid.

## Deployment wiring

The five physical roles remain Gateway, Control Plane, Agent Service (including Session Service), Brain and Tool Worker.

- Service in Java mode sets `MANAGED_AGENTS_BRAIN_HTTP_ENABLED=true` and `MANAGED_AGENTS_BRAIN_CALLBACK_URL=http://<brain-service>:8082/api/internal/brain/notify`. The URL comes from deployment/service discovery, never arbitrary request input. Service retains DB, Redis, MQ, objects and Control Plane connections.
- Java Brain receives `MANAGED_AGENTS_SESSION_SERVICE_URL=http://<agent-service>:8084/internal/session-service/v0`, matching internal authentication, compatible JWT/Vault settings and model credentials. Concurrency is 1..256. Object backend-id must match Service, but Brain needs no object provider credentials.
- Compose/Helm remove Brain infrastructure envFrom, DB/Redis/MQ settings and shared persistent mounts. Helm projects explicit authentication keys and names in `agent-harness-runtime.modelCredentialEnv`; custom credentialEnv bindings require matching entries and Secret keys.
- `scripts/dev-up.sh` is aligned. PI/Codex Compose overrides disable Java callback dispatch and retain their existing discovery/claim implementation. PI/PI Durable Helm deployment remains unchanged.
- Existing build entry: `mvn -B -ntp -T1 -pl managed-agents/agent-harness-runtime/java/brain-agentscope,managed-agents/agent-service -am package -DskipTests`. Java image uses `managed-agents/docker/Dockerfile.service --build-arg MODULE=agent-harness-runtime` or `agent-harness-runtime/images/agentscope-java/Dockerfile`. No image build, push or real deployment was performed.
- Upgrade Service first, drain and stop the old Java MQ Brain, then enable HTTP mode/new Brain. Do not run old/new Brain owners concurrently. Existing `broker_fifo_v1` Sessions and unbound Sessions with native history have no automatic migration. This phase accepts newly created Sessions; legacy migration requires a separate plan.
- Historical schema jobs, roles, JPA classes and some JDBC jars remain for existing migration/test paths. They are excluded from the new Brain Spring assembly. Dependency slimming and historical table deletion were not part of this phase.

## Targeted verification

No clean verify, full Java/TS/Rust suite or infrastructure load test was run.

| Check | Result and scope |
| --- | --- |
| `ManagedHttpPlatformTest` | 3 pass: same identity after lost claim response, expired renewal cannot attach, Unicode delta chunk/sequence schema checks. |
| `HarnessProtocolStoreTest` | 26 current cases passed (25 in the class run, then the original HTTP case expanded into two targeted modes), including existing PI/PI Durable/Codex cases and new Java HTTP cases. Real native Harness over loopback HTTP uses Service-side H2/file objects and deterministic model/Hands fixtures: lost prepared response/receipt recovery, one model tool invocation, suspension followed by fresh-Agent claim/continuation, history restored across fresh Agent instances, cancellation facts, stale owner fencing, 429 retry followed by claim. |
| `SessionApiContractTest#ordinaryCreationUsesDurableQueueForHttpBrainWithoutPublicExtension` | 1 pass: ordinary creation/message admission uses the internal queue without extension headers. |
| `AgentHarnessRuntimeAppContextLoadTest` / `HarnessRuntimeConfigTest` | 2 pass: unreachable DB/Redis/MQ addresses do not prevent assembly; no DataSource, AgentStateStore, Redis, MQ or process-lease bean; remote Workspace/SessionLog. |
| Protocol | 10 schemas, 21 operations, 117 positive/50 negative examples, Node 4 JCS/4 identity vectors pass. Added release, callback hint, resources, Java next and optional purpose. |
| Deployment | 4 targeted Helm cases pass, including Java isolation and PI variants; dev-up shell syntax passes. |

Reproduce the Java checks:

```bash
mvn -B -ntp -T1 -pl managed-agents/agent-harness-runtime/java/brain-agentscope,managed-agents/agent-service -am   -Dspotless.skip=true   '-Dtest=ManagedHttpPlatformTest,AgentHarnessRuntimeAppContextLoadTest,HarnessRuntimeConfigTest,HarnessProtocolStoreTest,SessionApiContractTest#ordinaryCreationUsesDurableQueueForHttpBrainWithoutPublicExtension'   -Dsurefire.failIfNoSpecifiedTests=false test
```

Scoped Spotless and git diff --check passed; unrelated migration files were not formatted. Protocol checks use the existing Python environment containing rfc8785/jsonschema/openapi-spec-validator. Missing dependencies in system Python are not a protocol failure. Maven used locally available AgentScope SNAPSHOT artifacts, so this does not prove clean-container dependency availability.

## Remaining work and stop boundary

| Priority | Follow-up | Impact |
| --- | --- | --- |
| Before deployment | Real PostgreSQL/MQ/Redis/Hands/model/SSE acceptance; Service-first rollout/drain; network and Secret checks | Local tests do not establish production deployment success. |
| Before legacy cutover | Old broker Session/native binding migration and rollback | New Sessions work; blindly replacing the old Brain would strand old work. |
| P1 | Owner/incarnation addressing and directed control hints; cross-Service notification efficiency and large-queue fairness | HTTP fact reads and durable repair support basic control/continuation; scale/latency remain unverified. |
| P1 | Real model cancellation, forced shutdown, partition, process restart, long-duration suspension and Service restart repair | Current evidence covers protocol fencing and reconstructed Agents, not real process/container faults. |
| P2 | Full native/public SSE comparison, slow consumers/preview pressure, multimodal/large-file payload sizing, real Control Plane memory proxy, log retention | Separate boundary acceptance; previews are optional and never durable truth. |
| P2 | Remove legacy Java MQ/JPA assembly code/jars; formal interface alignment in other languages | Not required for the current HTTP Brain. |

Basic Java D0/D1/D2 wiring and D3 templates are implemented. D3 actual rollout, owner-directed controls and full fault acceptance remain open. D4 is limited to additive schema/deployment compatibility; TS/Rust kernels were not refactored. Stop expansion at this phase boundary.

## Basic functionality closure before images (2026-10-30 follow-up)

This batch stays in the main directory on `master`, preserving existing changes without commits, pushes or deployment. It addresses concrete basic-execution gaps, without adding owner registration, notification connections or a complete fault matrix.

| Item | Implementation | Evidence boundary |
| --- | --- | --- |
| Ordinary PI / PI Durable / Codex Sessions | Select the durable internal queue from the configured harness type, without requiring a public queue extension or the Java callback switch | Ordinary public creation and message submission are tested for all three modes; disabling explicit queue creation still works. TS/Rust execution algorithms are unchanged |
| Java callback recovery and queue progress | One connection failure no longer aborts the batch. Failed or admitted-but-unclaimed candidates are deferred. Add the missing required `reason` so schema validation accepts the deferral | Real loopback callbacks cover 429 followed by claim, and a broken head connection while the next Session still claims. Active claimed work is excluded from discovery |
| Input-resolution request size | Allow `max(public request limit, 1 MiB) + 64 KiB` for `prepared/query` to carry an accepted payload plus its internal envelope; other control requests stay at 1 MiB | Declared-length and streaming byte-count cases cover acceptance and rejection. This is not complete multimodal or large-file acceptance |
| Cancellation during native Java execution | Extend the native HTTP fixture with a model stream that never completes: Service accepts CANCEL, Brain disposes the stream and commits cancelled, then a rebuilt Agent successfully executes another Turn | Both inline-tool and suspend/resume variants retain uncertain-commit receipt recovery and context restoration. Real providers, Pod kills and partitions still need cluster acceptance |

Verification and logs:

- Eight targeted basic-fix cases passed: Session API 4, request bounds 2, callbacks 2. Log: `/tmp/managed-functional-retest.log`. The first regression run exposed the missing `reason`; the corrected persistent-deferral assertion passes. Parameterized API fixtures use distinct idempotency keys to avoid reusing another case's Session.
- Two native cancel/recovery variants and two Brain assembly cases passed, for 12 targeted cases in this batch. The `package` reactor succeeded, producing executable Agent Service, Java Brain and Tool Worker JARs with verified Start-Class values. Log: `/tmp/managed-functional-package.log`. This uses compatible local dependencies; it is not container or real-infrastructure acceptance.
- Scoped Java Spotless checks passed (`/tmp/managed-functional-format-check.log`), as did the scoped `git diff --check`. No `clean verify` or full TS/Rust suites.

Reproduce packaging and the key HTTP regressions without full suites:

```bash
mvn -B -ntp -T1 -pl managed-agents/agent-harness-runtime/java/brain-agentscope,managed-agents/agent-service -am \
  -Dspotless.skip=true \
  '-Dtest=HarnessProtocolStoreTest#javaNativeExecutionAndNextTurnRestoreUseHttpWithoutBrainDatabase,AgentHarnessRuntimeAppContextLoadTest,HarnessRuntimeConfigTest' \
  -Dsurefire.failIfNoSpecifiedTests=false package
```

### Next: images and cluster acceptance

The five physical roles remain unchanged. Java Brain receives Session Service addressing, internal authentication and model configuration. DB, Redis, MQ and object storage connections remain with their appropriate Service/Hands roles; do not restore infrastructure credentials to Brain to make a container boot.

1. **Before building:** select registry, tag, target CPU architecture and a reachable Maven binary repository. Current AgentScope `2.1.0-SNAPSHOT` artifacts are locally Maven-installed. Local package success does not prove a clean Docker builder can download them. Supply compatible binary dependencies and build authentication, keep `revision` separate from `agentscope.version`, and do not copy legacy SDK source into this repository.
2. **Build entry points:** Java Brain uses `managed-agents/docker/Dockerfile.service --build-arg MODULE=agent-harness-runtime`; Agent Service uses the same file with `MODULE=agent-service`, both with repository-root context. PI uses its Node image and Codex its Rust image. Full Codex Helm wiring is still open; Java/PI Chart readiness does not imply Codex Chart readiness.
3. **Fresh-environment acceptance:** provision infrastructure and schema/grants, then Service, Hands, Brain and other roles. Use new Sessions for text, one tool call, unified SSE/deltas, in-flight cancellation, another Turn after cancellation, and suspended-tool recovery in a new Brain. Include B1 configuration rejection/effectiveness and B2 tool declaration/invocation checks; retain session/turn/task IDs and logs.
4. **Existing-environment cutover:** broker Session migration and rollback remain separate. Upgrade Service first, drain and stop the old MQ Brain. Do not silently change old scheduler/native formats. Fresh-Session acceptance does not require closing migration work first.

The local Docker server probe did not return within the bounded observation window and was stopped. No images were built/pushed and no cluster release was started. Owner-directed notifications, cross-Service signal optimization, queue capacity, real CP memory integration, multimodal input and full fault acceptance remain in the priority list. Stop this development batch after these targeted checks; proceed to image/environment acceptance next.

# PI Brain implementation plan

[中文](PI-IMPLEMENTATION.md)

2026-10-28. Workspace: `/Users/ken/agentcore/agentcore-managed-agents`, branch `master`. This plan follows the PI / PI Durable review. Completion requires implementation and the verification recorded here; designs and unit tests do not constitute production acceptance.

2026-11-02 update: the historical Codex probe and its tests have been deleted. The TypeScript workspace now contains only Runtime, PI Adapter and PI Brain. Test counts below preserve each batch's historical results; see [Rust Brain](../rust/README.en.md) for the current Codex implementation and acceptance.

## Target and ownership

One standalone Node 22 Brain image contains the TypeScript Runtime and both PI adapters, selecting one kernel at startup. Sessions remain bound to a kernel and native format. Brain owns reasoning, native context/recovery, tool decisions, inputs and coordination. Session Service owns fencing, transactions, budgets, objects, Outbox and result acceptance. Hands owns external tool actions. Brain requires neither infrastructure credentials nor a Java Brain intermediary.

Implementation is concentrated in `typescript/`. Preserve concurrent, uncommitted repository work. Use a separate Java integration test for the cross-component flow and narrowly fix server defects demonstrated by that test; do not implicitly change shared protocol, Helm or release contracts. Record cross-component dependencies separately and mark them complete only after integration. Work in the current primary checkout; do not commit or push.

## Baseline and findings

Real PI SessionManager / Durable Harness integration, claiming, renewal, fencing, receipts, stable tool identities, native result application, confirmation inputs and Node/Docker scaffolding exist. Durable Storage exposes writes only after remote acknowledgement and preserves ID allocation.

Confirmed defects: ordinary PI extension handlers swallow admission failures; Durable passes a message array to a single-message output converter; neither adapter subscribes to user cancellation; ordinary PI omits tool `isError`; JSON HTTP 5xx/408/429 bypass uncertain-commit recovery. Existing tests mainly cover protocol and Storage rather than actual adapter inference.

Further gaps: suspending long tools/approvals, growing full snapshots, auxiliary model accounting, streaming/steering, platform skills/memory/resources/subagents/checkpoints, deployment integration and real fault acceptance.

## Implementation stages

| Stage | Work | Acceptance | Status |
| --- | --- | --- | --- |
| A: correctness | Blocking model admission, output, tool errors, cancellation, uncertain receipts | Real SDK with local model doubles for both adapters; zero dispatch after denial; consistent recovery output; cancellation settles while commits remain available | Implemented and component-tested; real environment acceptance pending |
| B: coordination | Suspend long tools/approvals, release kernel/capacity, resume original execution; classify failures, back off recovery, diagnostic logs | Waiting releases capacity; stale owners stop; no replacement external actions; distinguish cancellation, shutdown and suspension | Suspension/rebuild/approval and correlated logs implemented; HTTP wakeup, new-process recovery and capacity release verified; retry governance and bounded shutdown implemented; real infrastructure acceptance pending |
| C: storage/models | Segmented journals and periodic snapshots; recovery pagination/limits; account for all model paths including compaction; model parameters/credential scope | Long-session growth/recovery benchmarks; failed commits invisible; compaction accounted; IDs never reused | Segmented snapshots, recovery paging, shared model admission and sealed installation configuration implemented; native compaction, GC and tenant credentials pending |
| D: platform capabilities | Skills, memory/resources, artifacts, multimodal input, public deltas, steering, subagents, checkpoint management | Explicit capability matrix; unsupported features rejected rather than silently ignored | Integration pending |
| E: delivery | Image verification; kernel-aware Helm probes/Secrets/schema jobs; release build, metrics/traces, rolling upgrades | Both kernels boot independently; target Linux architectures; real dependencies and fault acceptance | Production dependency packaging, Helm/Compose, PI release entry and basic metrics implemented; Linux image builds, full tracing and rollout acceptance pending |

Ordinary PI uses native session snapshots and adapter-managed boundaries. PI Durable uses native Storage/task recovery. Share platform coordination without conflating native formats or guarantees.

## Invariants

- Admit before actual model dispatch. Notifications that swallow exceptions cannot be the admission barrier. Coordination must observe fatal failures even when the SDK converts them to model/tool errors.
- Apply results only after native recoverable state contains them. Unknown external outcomes never authorize a replacement action.
- User cancellation interrupts inference/waiting while preserving settlement access. Ownership loss/shutdown stops new commits. Cancel Hands through platform execution semantics, never by replacing tasks.
- Resolve uncertain submissions with the same commit ID. Advance native visibility only after durable acknowledgement.
- Suspend only after committing native recovery and continuation. An ordinary SDK tool error is not a safe suspension boundary.
- Segmentation preserves committed prefixes, hashes, scope and native IDs; truncating history is insufficient. Include Service boundary-count limits in the design.
- Keep local automatic skill/plugin/tool discovery disabled. Load platform resources explicitly; auxiliary models require budgeting too.

## Cross-component dependencies

1. Session Service: segmented/paged recovery, model parameters/credential references, public events, resource/skill descriptors, checkpoint operations, consistent with pinned Turn configuration.
2. Hands / Service: long-tool and confirmation wakeups, cancellation, late results, unknown reconciliation, artifact mapping.
3. Helm / release: Java actuator probes, Java schema jobs and Java Brain Dockerfile cannot be reused unchanged for PI; select configuration/artifacts by kernel.
4. Operations: model Secrets, controlled writable temporary directories, resource limits, shutdown deadline, correlated session/turn/task/modelCall logs, metrics and traces.

## Acceptance matrix

- Both kernels: plain inference, multiple Turns, short/failed/cancelled tools, multiple calls, allow/deny confirmation, additional input, cancellation and budget rejection.
- Restart before/after authorization, external side effect, result acceptance, native result persistence and lost final receipt. Preserve original task/execution IDs and results.
- Model interruptions/timeouts, exhausted budgets, compaction, Service 5xx/408/429/unavailability, lease loss, SIGTERM/SIGKILL and competing owners.
- Long histories, segmentation, object/recovery limits, concurrent waits, drain and backpressure.
- Local checks: `pnpm -r build`, `pnpm -r test`, `pnpm -r check`. Real SDK with doubles verifies component contracts, not real PostgreSQL/MQ/Redis/Hands/model or container fault acceptance.

## Verification record

Review baseline: five packages build/type-check; ten tests (Runtime 3, PI Storage 4, Codex 3). Minimal reproductions confirmed empty Durable array output, swallowed PI provider-handler exceptions and no receipt lookup on JSON HTTP 503. No image build or deployment.

### First implementation batch, 2026-10-28

- `adapter-pi/src/models.ts`: reserve and commit before actual `stream/streamSimple/complete/completeSimple` dispatch, with a shared response-accounting queue. Do not rely on PI notifications that swallow errors. Preserve fatal admission/persistence failures and stop the kernel even if the SDK converts them into ordinary responses. Native compaction's `completeSimple` uses this gate too. Deferred, image generation and classification lack platform contracts and explicitly reject instead of bypassing admission.
- `pi.ts`: propagate tool `isError`; insert denied recovered calls as native error results; use in-memory Settings with cache warming disabled; never persist infrastructure failure as a native business result.
- `pi-durable.ts`: extract the final assistant from the current root Conversation context instead of scanning historical message arrays; settle user cancellation through Conversation.abort and stop fatal failures separately, preserving recoverable state.
- `runtime/src/index.ts`: separate work cancellation from settlement; recover original receipts after 5xx/408/429; suspend long tools/approvals using the existing protocol and freeze commits immediately after acknowledgement; recognize suspension without emitting a terminal commit.
- `runtime/src/worker.ts`: `BRAIN_INLINE_WAIT_MS` defaults to 1000, range 0..600000 (zero still checks once); Turn `inlineWaitMs` takes precedence. Include scope, turn ID and kernel in execution failure logs. Returning from suspension releases the active slot.

Tests cover both real SDKs: normal output, completed recovery without another model call, multi-Turn context, zero-dispatch budget denial, failed tools, cancellation during inference/before startup, infrastructure failure recovery, and real RunHost suspension/reconstruction for unknown tools and allowed/denied approvals while preserving call/execution IDs and final settlement. HTTP tests cover uncertain receipts and identical retry after a missing receipt. Runtime tests cover settlement after cancellation, no writes after suspension acknowledgement, and no successful-handoff claim after failed suspension.

Batch results: `pnpm -r build`, `pnpm -r test`, and `pnpm -r check` pass. All 39 tests pass (Runtime 11, PI 25, Codex 3; 29 more than baseline). Edited TS/MJS files pass Prettier 3.6.2. Protocol checks using the existing `/tmp/managed-agents-protocol-venv/bin/python` pass: 8 schemas, 14 APIs, 82 positive and 23 negative cases, plus Node identity/JCS vectors. System Python lacks rfc8785; the system environment was not modified.

The first batch uses real PI SDKs, local model Providers and an in-memory protocol peer, not real models, MQ, Redis, databases or Hands. Segmented storage, platform resources, retry governance, container/Helm/release and real-dependency fault acceptance remain outstanding. As subsequently confirmed by the user, first verify the cross-process flow below, then failure classification, recovery backoff and storage growth; coordinate Service recovery pagination with concurrent work.

### Second batch: cross-process Brain → Hands → Brain acceptance

Run `pnpm test:http` from `typescript/`. The script builds TypeScript and runs `PiBrainHttpFlowTest` and `HarnessProtocolStoreTest` from the repository root with `-T1`. Prerequisites: Node 22, pnpm, Java/Maven, Python 3 and a shell. Regular Java tests skip this opt-in suite unless `-Dmanaged.pi.http=true` is supplied. Failures include logs from each Brain process.

The test launches separate Node processes using the real `runWorker`, PI/PI Durable SDK, HTTP SessionClient, Java SessionServiceApi/HarnessProtocolStore, Hands Command Publisher, Worker Store/Loop, Result Publisher and production Sandbox executor. Session Service and Hands have independent H2 databases. Shell commands execute real side effects recorded in a counter file. The model Provider is deterministic; the server's sealed configuration cache is seeded, so Control Plane/Vault configuration generation is outside coverage. MQ and lease backends are in memory; objects use temporary local storage.

Four cases cover a short tool and long-tool takeover for each kernel:

1. Worker polls `executions/next`, claims and restores/runs over HTTP without directly invoking `runClaimed`.
2. PI generates the call; Service atomically persists native state and authorization; Hands consumes the Outbox and starts the shell.
3. For a long tool, Brain commits suspension. Assert Turn=`waiting_tool`, no server lease and `/readyz` `active=0`, wait for the first Hands side effect, then SIGKILL the original Brain.
4. Release the shell wait. Hands Publisher delivers the real result to Service. A new Node process automatically uses `next → claim → recovery/read`, restores the original tool result and continues model inference.
5. Assert one physical execution/side effect, two model calls, successful Turn settlement and final public output. Replace every successful boundary response with HTTP 503, covering receipt recovery after authorization, suspension, native result application and terminal commits.

Integration exposed two scheduling defects:

- `HarnessProtocolStore.next()` selected resumes with `t.resume_id = r.resume_id`, but that ID is only assigned after claiming, hiding every initial resume. Match the Turn's current `suspension_id` to the resume Outbox instead, discovering unclaimed resumes while excluding old suspensions.
- The query applied `LIMIT` before excluding unready suspended Sessions. An older long-tool wait could consume the polling window and starve runnable Sessions. Filter runnable/resume-ready work in SQL before pagination.

A focused Service regression checks no delivery before a result, selection of another runnable Session with a limit of one, discovery after the result but before activation, and exclusion while another owner holds the claim.

Production acceptance still requires real PostgreSQL/Redis/RocketMQ, remote Sandbox control, HTTP model/real credentials, container restart/rolling deployment, competing-owner fencing and cross-component approval/cancellation. Fixture ports were not listening and Docker daemon queries did not respond on this host. No image build or deployment has been performed.


Batch acceptance: all four PI / PI Durable × short-tool / new-process takeover cases pass (12.41 seconds in the final full build). All six Service protocol regressions pass, including polling fairness for an unready Session and visibility of an unactivated resume. Five TypeScript packages build/type-check; all 39 existing tests, Prettier and shell syntax checks pass. All 12 modules pass `mvn -B -ntp -T1 clean verify -Dmanaged.pi.http=true` (6 minutes 31 seconds); `mvn -B -ntp -T1 spotless:check` passes. External-infrastructure tests remain gated by their environment switches and do not constitute real-dependency acceptance. Changes remain in the primary checkout on `master`; nothing has been committed, pushed or deployed.


### Batch 3: recovery governance, large state, model configuration and delivery

1. **Failure handling and shutdown.** Worker distinguishes ownership, configuration/authentication and transient failures. Ownership conflicts never defer another owner's work; configuration failures stop admission and exit. Transient failures use bounded jittered exponential backoff, reusing the same delivery/deferralId after uncertain responses. Owned deferrals carry a fence; Service checks it and updates both HTTP scheduling and resume outbox timing without releasing ownership. Exhausting the process-local retry budget triggers maximum-interval cooldown, never an invented terminal outcome for uncertain tools. SIGTERM stops admission, drains, then aborts at the deadline, with a bounded failure if the kernel remains unresponsive. Readiness depends on Service polling; low-cardinality Prometheus metrics are available.
2. **Paged recovery.** Optional `pageSize=1..16` and `cursor={afterRevision,throughRevision}` on `recovery/read` produce optional `nextCursor`. Legacy requests retain their complete-read behavior and limits. PI uses 16 boundaries per page, pins native head/brainRevision/snapshotId and rejects mixed heads while renewing ownership during reads. Bundles larger than 512 KiB use object references. Tool results/inputs are observations at read time and continue to reconcile through existing APIs. Client limits are 512 MiB / one million facts; pending inputs remain unpaged with a 256-item limit.
3. **Segmented native checkpoints.** Small state remains complete JSON. PI `entries` and Durable `commits` switch to immutable segments at 128 items or 256 KiB. Each segment holds at most 128 items with a 512 KiB target; individual large records may reach 8 MiB. A manifest records metadata, ordering, counts and total size; every dependency is explicit in `nativeState.journalRefs`. Content hashes reuse unchanged prefixes, including after recovery. Each checkpoint represents complete logical state without truncation or ID reassignment. Limits are 1024 segments, 512 MiB data and 512 KiB metadata. Durable ID allocation no longer deep-copies history; commits clone only new writes and freeze reused prefixes, advancing local state only after remote acknowledgement.
4. **Sealed model configuration.** Service accepts `MANAGED_AGENTS_PI_MODEL_OPTIONS` with api/baseUrl/credentialEnv/contextWindow/outputLimit/cost/parameters, caching these with the admitted Agent model ID for the Turn. Schema validation rejects credential values and unknown fields. Brain uses an in-memory CredentialStore without local auth/models discovery. Custom endpoints require explicit `PI_MODEL_` credential names; custom models require explicit pricing and token limits. Temperature, reasoning and maxTokens affect normal/auxiliary requests and their budget facts. Durable also reapplies the Turn's sealed definition after restoring its root: SDK `root(options)` only configures creation, so this fixes model changes being ignored on subsequent Turns.
5. **Delivery.** [Image/deployment instructions](../images/pi/README.en.md) document configuration and upgrade ordering. Helm supports `brain.type=pi|pi-durable` with a dedicated Secret, read-only root, ephemeral directories, Node probes and sufficient shutdown grace. Java remains the default; PI omits the Java Brain dataplane migration Job. Compose adds shutdown/model settings. `release.py images-pi` builds only the PI image and retains digest/source/platform validation and SBOM/provenance support, with separate metadata. No image was pushed.

**Compatibility and limits:** New readers accept old complete snapshots, retaining the logical native `stateFormatVersion`. Object encodings are `managed-pi-snapshot-v1` / `managed-pi-segment-v1`. Manifest `version` deliberately differs from native numeric version 1, making old adapters reject segmented state. Do not roll back such Sessions to readers lacking this encoding. Upgrade Service before Brain. These changes reduce repeated uploads/deep copies; SDKs still reconstruct full history in memory and Service still validates all referenced segments at commit. Bounded-memory storage, native compaction, historical object GC and periodic archival are not complete.

**Remaining:** Linux image builds, both architectures and rolling rollback; real PostgreSQL/Redis/MQ/remote Sandbox/model/credential validation; durable cumulative retry budgets, alerts and operator quarantine; native automatic compaction, GC and large-scale recovery benchmarks; Control Plane per-model configuration and tenant Vault credential delivery; public streaming events/live steering, Skills/memory/resources/artifacts/multimodal/subagents/platform checkpoints; full tracing and cross-component approval/cancellation/dual-owner failure tests. The original suspend → Hands execution → accepted result → Brain takeover flow remains a regression requirement.

Current phase status after batch 3: A implemented and component-tested; B suspension/recovery/retry/shutdown implemented, real-infrastructure validation pending; C segmented transport/paging/installation model configuration implemented, native compaction/GC/tenant credentials pending; D platform integrations pending; E production dependency packaging/Helm/Compose/release entry/basic metrics implemented, Linux containers/tracing/production rollout pending.


Batch 3 acceptance:

- All five TypeScript packages build/typecheck; 60 tests pass (Runtime 25, PI 32, Codex 3). Coverage includes Worker failure/backoff/shutdown, 5001-boundary recovery, segment reuse/integrity, model parameters/credentials, a local real HTTP model stream and model changes between Turns. Modified TS/MJS files pass Prettier.
- All 14 focused Java tests pass: nine protocol, one model-configuration and four cross-process PI cases. Long-tool tests now include 300 KB of native content, assert segmented objects, SIGKILL/rebuild recovery and exactly one tool effect. Every successful boundary acknowledgement still becomes HTTP 503 to exercise receipt recovery. The standalone WebFlux fixture explicitly matches production's 90 MiB object-transport codec limit.
- All 21 Helm and 26 release-focused tests pass. Current protocol checks pass nine schemas, 17 APIs, 102 positive/29 negative cases and Node vectors. The broader 65-test release suite has one existing failure: `test_helm_cloud_connections_override_stale_workload_and_schema_secrets` omits required `queryStore.existingSecret`; its cloud Secret ordering assumption also predates the current template. That parallel scope was left untouched.
- Standalone production deployment with the image's pinned corepack pnpm 10.28.2 includes project dist files and production dependencies. Both packaged entrypoints become ready and exit 0 on SIGTERM. This host is macOS; Docker did not respond within eight seconds. No Linux image was built/pushed and nothing was deployed.
- Root `mvn -B -ntp -T1 clean verify -Dmanaged.pi.http=true` was attempted and failed in Java adapter Javadoc because `ManagedTurnAckLedger` could not be resolved during concurrent directory changes; later modules did not run. Root spotless also encountered formatting violations in parallel common-module changes. Focused tests used `-DspotlessFiles=.*(HarnessProtocolStore|HarnessProtocolStoreTest|HarnessRunConfiguration|HarnessRunConfigurationTest|PiBrainHttpFlowTest).java`, verifying this batch's Java files without formatting others' changes. This batch does not claim a green whole-repository build.

Logs: `/tmp/pi-batch3-ts.log`, `/tmp/pi-batch3-http-scoped.log`, `/tmp/pi-batch3-helm.log`, `/tmp/pi-batch3-release-scoped.log`, `/tmp/pi-batch3-full.log`, `/tmp/pi-batch3-spotless.log`. Source remains in the primary checkout on master, without a worktree, commit, push or deployment.

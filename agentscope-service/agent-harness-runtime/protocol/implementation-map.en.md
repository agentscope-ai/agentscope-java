# Protocol implementation map

[中文](implementation-map.md)

2026-10-30: the Java App now uses shared HTTP claim/renew/release with the native prepared profile and no platform DB/Redis/MQ assembly. Process registration, FIFO ACK and JPA dependency descriptions below belong to retained legacy paths, not the current App. Task callbacks are implemented; owner-directed controls remain open. See the [implementation and boundaries](../testing/conformance/verification-java-platform-http-20261030.en.md).

See the [model-binding record](../testing/conformance/verification-model-bindings-20261029.en.md) for generic catalog resolution, PI credential references, and native Codex parameters.

Versioned configuration identity, digest checks, and PI construction from sealed definitions are covered in the [configuration verification record](../testing/conformance/verification-configuration-20261029.en.md).

2026-10-29 update: generic model commits generate public spans. PI / PI Durable / Codex commit intermediate native text facts, and terminal `messageId` selects an existing message. Large text uses object references. Codex reports usage and PI normalizes cached input. PI / PI Durable / Codex now connect to the independent online-text channel; see the [preview verification record](../testing/conformance/verification-previews-20261029.en.md). Reconciliation of unfinished model calls after faults remains outstanding. [Verification scope](../testing/conformance/verification-public-events-20261029.en.md).

2026-10-28 development baseline. Each deployment selects one kernel; each Session binds a kernel and native format. This unpublished feature has no compatibility or dual-write requirement.

## Call path

AgentScope uses shared execution leases and the Java prepared commit profile. PI/PI durable and Codex Rust use the generic 0.1.0 profile. All reach Session Service inside Agent Service and call the same `SessionCommitTransactions.commitPrepared` transaction core. Adapters interpret native state; the platform handles fencing, receipts, budget, Outbox and result ordering.

| Step | Implementation |
| --- | --- |
| 3 | `agent-service/.../sessionservice/api` and `store`, in the Agent Service image/JVM. Remote commits, receipts, native reads/objects and private workspace. Existing Java process leases and fixed execution deadlines remain enforced. |
| 4 | Result consumers, command/resume publishers and lifecycle workers move to Agent Service. Hands retains admission, execution ledger, unknown reconciliation, ordered output, late results and ACK ordering. |
| 5 | Java runtime/adapter/brain modules, an independent pnpm workspace, build entry points and per-Brain images. |
| 6 | Plain PI uses native SessionManager; PI durable injects Storage. Each runs in one Node process and delegates external tools to Hands. |
| 7 | `rust/` directly links a pinned codex-core in an independent Rust process/image. Native suspension, original-call result injection and original-Turn cross-process recovery have tests. The TypeScript workspace serves only PI / PI Durable. Real infrastructure acceptance remains pending. |


## Java implementation ownership

- `java/codec-agentscope`: pure native-format plugin with a private event registry, checkpoint inspection and restore/fork baselines; no executor.
- `java/adapter-agentscope`: native SessionLog, execution/recovery, model capture, tool conversion and checkpoint preparation. Brain composes it explicitly; common no longer discovers adapters through ServiceLoader.
- `agent-service`: APIs and commit implementations share `src/`, owning `SessionCommitTransactions`, command publication, result repair, resume notifications, lifecycle, checkpoint administration and projection processing in one Maven module and JVM. Cross-role integration tests also live here, composing Adapter and Tool Worker through test dependencies while production dependencies remain separate.
- `tool-worker`: tool message consumption, execution control and Sandbox bindings. Its ordinary jar supports integration dependencies; `*-exec.jar` is the deployment entry.
- `common`: shared entities, protocol structures, transport, leases, object storage and ledger access still used by multiple roles. It does not own single-role execution orchestration or load concrete adapters. AgentScope scheduling coordination now calls Service as described below; shared storage code and local JPA assembly are not fully separated.

The commit path is `AgentScopePreparation → ManagedCommitPlan → HTTP → SessionCommitTransactions`. Embedded regression fixtures explicitly inject the same transaction through `PreparedCommitter`. Production Brain has no dependency on Session Service or Tool Worker implementation artifacts. Atomic commits, idempotent receipts, ownership checks, Outbox and ACK boundaries retain their semantics.



### Concrete ownership of common implementations

Production references and Spring wiring place these implementations outside `common`:

| Owner | Implementation and purpose |
| --- | --- |
| `tool-worker` | `ManagedAsyncToolExecution` and the extracted `ManagedAsyncToolExecutions` factory: asynchronous execution, renewal, ACK handoff, output collection and shutdown cleanup. |
| `agent-service` | `ManagedToolExecutions`: synchronous execution for child control on bounded worker threads. It no longer creates Hands async executions. |
| `java/adapter-agentscope` | `SkillRepositorySupport`, `LoadTestModel`, `AgentStateEntity/Repository`: native skill repositories, load-test model and state persistence. The Git Skill backend dependency moves with its factory. |
| `java/brain-agentscope` | `SessionAgentBuildSpec`, JPA wiring, catalog/version/memory access and their dedicated entities, repositories and interfaces: currently used only for local Brain agent assembly, with the associated tests. |

`ManagedToolExecutor`, `ManagedToolWorkStore` and `ManagedToolResultPublisher` remain in `common`: both external tool execution in Hands and child control in Session Service use these contracts and result publication. Internal token validation also remains shared: Agent Service imports it explicitly while Brain discovers it through component scanning. Explicit Java references alone do not establish ownership.

This changes ownership and factory wiring while preserving execution identities, claim → ACK → invocation ordering, leases/cancellation, unknown outcomes, result repair and atomic commits. Moving code does not make legacy catalog/memory reads remote; shared ledger access and infrastructure remain within the boundaries above.

## Tool boundaries

- **Call:** native state, native call binding, task and command Outbox commit together. Lost responses query the original receipt; retries retain ID and content.
- **Execute:** Hands durably admits before ACK and starts provider work after ACK confirmation. A repeated executionId does not create another action. An external effect without a durable result can remain unknown and must not be blindly repeated.
- **Result:** Service ACKs durable receipt/deduplication; final result acceptance waits for contiguous output or explicit gaps and records an eligible resume intent in the same transaction. Adapter commits resultApplications only with the native result record.

Service continues accepting valid original Hands results while Brain is absent. A replacement Brain reads those results under the same task/execution IDs.

## Profiles and actual wire

`openapi.json` defines 14 generic operations plus two prepared Java handoff operations. Generic operations include: claim, renew, queue hints, pinned configuration, commit/receipt, recovery, object write/read, tool state, input read/close and model reserve.

AgentScope posts explicit `ManagedCommitPlan` to `/prepared/commits` (`prepared.schema.json`). `/prepared/receipt` reads its receipt; `/prepared/query` is a whitelist of semantic recovery reads; `/prepared/model` reserves budget; `/prepared/objects` accesses native objects; `/prepared/workspace` handles native Store operations and atomic finalization receipts. This Java profile uses numeric native positions; generic counters are decimal strings. They are not interchangeable DTOs or separate transaction implementations.

Actual Hands broker bodies remain `ManagedBatchCodec.Envelope(version=2)`, carrying scope, identity, digest and inline bytes or a private object reference. Decoded records are `ManagedToolCommand / ManagedToolEvent`. `messages.json` and `tools.schema.json` describe logical handoffs, not literal broker bytes. Service compiles generic operations into existing commands, preserving submission epoch/token, authorization commit, sealed configuration and stable execution IDs. Existing result intake projects generic results and actual output offsets/gaps. Native artifact references remain in result content; generic artifacts are not yet populated.

## Issues resolved during implementation

- Turn configuration is persisted once, preventing resealing from changing identity during recovery.
- Renewal reports server time; a kernel renews before starting and measures expiry with a monotonic clock.
- Full JCS request digest, object integrity/scope, native positions, duplicate IDs, model request hashes and interaction/input associations are checked.
- Objects are staged before metadata references commit. New writes require a current owner; successful retries return their original receipt.
- PI durable exposes native writes only after acknowledgement, preserves allocation state, and reserves ID 1 for the native root.
- Request/response bounds and boundedElastic workers keep blocking persistence off Reactor event loops.

## AgentScope flow verification

This section records AgentScope flow verification. The independent Codex Rust implementation is described below; each kernel preserves the platform responsibilities and recovery boundaries:

```text
Agent Service admits Turn / Input → MQ or Session queue → AgentScope Brain
  → native recovery, model calls, tool intents → HTTP Session Service commit
  → task and command outbox → Hands durable admission, execution after ACK
  → result outbox → Agent Service durable receipt → Redis notification or Resume
  → Brain applies original results → final checkpoint / Turn settlement → public projection
```

| Integration gap | Fix |
| --- | --- |
| The default HTTP decoder cannot decode the protocol's JsonNode type, returning 500 | HTTP explicitly uses the persistence protocol's Jackson 2 mapper. A real Spring application verifies internal authentication and large object transfer. |
| A committed request followed by 5xx skipped receipt recovery | Treat 5xx / 408 / 429 as unconfirmed; query the original receipt before retrying. Remote infrastructure errors cannot become Agent business-failure decisions. |
| Remote workspace writes lost timestamps | Exclude regenerated timestamps from replay identity, preserve complete values in the first write. Mutation and receipt remain atomic; replay returns the original read. |
| Remote reads and budgets depended on Writer initialization order | Bind each client when its own Bean is created, before consumers use it. |
| Deployment and local startup gaps | Fix Compose indentation, derive the local Session Service URL from the Agent Service port, and check for a missing Brain JAR before skipping the build. |

`AgentScopeRemoteFlowTest` uses the real AgentScope kernel, HTTP and H2 transactions through production tool admission, ACK, result ledger and recovery code. Models, external sandbox and MQ/Redis transports are doubles. It covers short tools, suspension and rebuilding the Agent, injected input, model reservations, lost commit responses, final projections, private IO replay and stale-owner rejection. This proves component connectivity; **real PostgreSQL / RocketMQ / Redis / sandbox and container fault acceptance remains outstanding**.

## Verification and limits

Focused Java recovery/tool regressions, H2 service transactions, lost HTTP receipts, concurrent tool deduplication, real durable Harness reopening and Codex Rust cross-process recovery have automated coverage. See [conformance](../testing/conformance/README.en.md). Real PostgreSQL/MQ/Redis, container faults and model end-to-end acceptance remain pending.

Java commits, native storage and the coordination below now call Service. Brain retains Redis execution leases/input delivery and MQ consumption/ACKs. Local JPA catalog/memory and some infrastructure wiring still require a database; do not remove all database configuration.

The initial PI profile offers execute, file read/write and declared MCP tools, with automatic local tools/plugins disabled. Multi-call batches, cross-language equivalents of AgentScope private finalization IO and platform checkpoint management still need dedicated integration. PI durable currently snapshots its full write log, bounded by 64 MiB; segmentation/compaction is future work.

## Boundary convergence

- Session Service owns checkpoint transactions and delegates native semantics through `NativeCheckpointCodec` to `codec-agentscope`. Its private registry includes `managed/finalization_*`, independent of Brain initialization; unknown required events still fail. Unsupported kernel bindings are rejected before placing holds.
- Native TaskRepository, JPA AgentStateStore and SkillRepository implementations belong to the adapter. It converts ChatResponse into neutral text deltas. Service passes child output as opaque native JSON without constructing an AgentScope Msg.
- `/prepared/tool-publication` durably proposes under the current owner and returns the Broker receipt, **not execution authorization**. A later `/prepared/commits` atomically authorizes it. Lost responses retry the original command/execution identity. Brain no longer writes proposal rows or starts cancellation forwarding.
- `/prepared/query` adds `tool-proposals`, `mcp-batches` and `resume-settled`; `/prepared/resume-defer` durably defers the same Resume before releasing its handoff. Schemas, OpenAPI and [handoff examples](examples/prepared-handoffs.json) are aligned.
- As this is unreleased, the legacy Hands completion-notice upgrade path is removed. Brain only accepts the canonical Service Resume. Durable result intake, result/resume atomicity, execution ACK and unknown protections are preserved.


## AgentScope scheduling and coordination now use Session Service

The missing piece was ownership migration; the core guarantees already existed. Brain now calls the existing ledger algorithms through `/prepared/coordination`. This is a closed operation allowlist, not SQL or arbitrary storage access. See the [schema](schemas/coordination.schema.json) and [handoff examples](examples/coordination-handoffs.json).

| Boundary | Session Service owns | Brain retains |
| --- | --- | --- |
| Process and Turn claim | Registration/renewal/retirement; incarnation, fixed deadline, publication, predecessor and Resume checks; atomic epoch and queue claim. | One process identity, short Redis execution leases, local cancellation and retries with the original identity. |
| Inputs and Session queue | Input closure/cutoff, queue generation, dispatch token, Ready validation and deferral. | Notification consumption, reads and kernel execution. |
| FIFO ACK | `SessionTurnAckLedger` stores each private receipt generation under the same Session lock as terminal commits. Only terminal receipts can be claimed; failed/lost claims are repairable. | `ManagedTurnAcknowledgements` calls the Broker and deletes the receipt after confirmation. No database transaction spans the Broker RPC. |
| MCP | Catalog lookup, first-description pinning, public catalog binding and cancellation reads. Concurrent discovery reuses the first committed description. | HTTP/stdio connections and discovery, local commands/workspace and cache isolation. Model/credential configuration is not sent through catalog RPCs. |
| Children | Child admission from committed ToolTasks; frozen definitions, identity, root relationships, budget and cancellation semantics; relationship reads. | Native delegation intents, frozen identity reads and application of returned results. |

Recovery boundaries:

- **Lost registration/claim response:** retry the same incarnation or process/token/publication. Never mint a replacement identity merely because a response was lost. Expired incarnations cannot register or renew again.
- **Lost Brain or expired execution fragment:** Service rejects stale owners. Takeover preserves lock ordering and cannot steal a live owner. Process renewal does not extend a fixed Turn deadline, configured by `managed-agents.runtime.fragment-deadline-seconds` on Agent Service.
- **Lost ACK response or consumer:** retain the receipt and retry after its bounded claim. Expired receipts require a new Broker delivery. Timeout is not successful ACK; suspension alone does not acknowledge an unfinished FIFO Turn.

Java Brain registers with Session Service before starting consumers. Service outages or expired leases never select a local-ledger fallback. `SessionCoordinationHttpTest` covers RPC recovery; `AgentScopeRemoteFlowTest` uses remote claims/input operations for short tools, suspension, fresh Agent construction and continuation. Real infrastructure fault acceptance remains separate.

### Remaining boundaries

- Remaining database dependencies are primarily local JPA catalog/memory and shared infrastructure assembly. The coordination above no longer writes ledgers from Brain; Redis and MQ retain their lease/transport responsibilities.
- Shared SessionEvent envelopes and checkpoint object layout still contain AgentScope format assumptions. Interpretation is isolated, but not every shared DTO is kernel-neutral yet.
- Real PostgreSQL/RocketMQ/Redis/sandbox, container faults and image deployment acceptance remain outstanding. PI scope is unchanged; Codex Rust implementation and verification are described below.

## Codex Rust integration

`rust/` directly depends on a pinned open-source codex-core without upstream patches, CLI/app-server processes or Node. `ScratchStore` implements the native ThreadStore staging interface; authoritative checkpoints still commit through Session Service. Each native instance advances one model response. At a tool boundary it captures rollout before `suspend_turn_and_shutdown`; a new instance uses `inject_response_items` with the original call IDs and `recover_turn_if_idle` for the original Turn.

Tests cover partial results from two native tools, owner takeover, lost commit receipts and SIGKILL followed by recovery in a new process. Service accepts explicit `codex` bindings; actual Rust requests also pass shared schema validation. Model and Session Service transports are doubles, so this does not establish real MQ/Hands integration. Child agents, multimodal content, additional input, incremental snapshots and a platform checkpoint administration codec remain beyond the single-root text/dynamic-tool profile. See [Rust Brain](../rust/README.en.md) for build, image, configuration and limitations.

AgentScope explicit model bindings now use prepared `model-configuration` to read per-Turn sealed selection. After identity checks, the factory creates OpenAI Chat Completions / Responses or Anthropic clients; named/default native models retain an explicit native mode. See [model verification](../testing/conformance/verification-agentscope-models-20261029.en.md) for targeted behavior and remaining child-Agent, admission, and full-flow scope.

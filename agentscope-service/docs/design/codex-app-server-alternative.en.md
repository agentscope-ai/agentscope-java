# Alternative: Codex release app-server integration

[中文](codex-app-server-alternative.md)

Recorded: 2026-10-31. Status: **alternative for future validation; no implementation, switching or deployment now.**

## Decision and background

Retain the current Rust Brain linking a pinned `codex-core`. This document records durable-harness's alternative: an official Codex release binary, app-server and an external durability adapter.

This change adds documentation only. Dependencies, images, configuration, Session bindings, checkpoints and cluster deployments remain unchanged. Future experiments should use new Sessions and a separate experimental deployment, without taking over existing Sessions.

The old TypeScript app-server probe gated execution because durable tool barriers and pending callback recovery were unverified. That was a validation gap in that adapter, not proof that official releases cannot support them. The historical probe was deleted on 2026-11-02; future validation requires a separate implementation, and its old tests do not count as acceptance. No equivalent comparison has established source linking as the only viable option.

## Verified reference

Reference: durable-harness `ea8aa4060cf4fb8cd39737ce8ab21c4e0f40c741`. On 2026-10-31, `main` matched this revision for `harness-sandbox/src/clis.ts` and `harness-codex/src/harness.ts`. Recheck versions/interfaces when experiments begin.

- **Distribution:** downloads the official `@openai/codex@0.160.0-linux-x64` npm binary and verifies its pinned SHA-512 before Sandbox installation. This path does not build or modify the Codex core. [Installation](https://github.com/vercel-labs/durable-harness/blob/ea8aa4060cf4fb8cd39737ce8ab21c4e0f40c741/packages/harness-sandbox/src/clis.ts#L32)
- **Journal barrier:** mirrors native rollout into the World log. Before a `replay: never` tool, it verifies the call record is durable, then records intent and executes. Missing asynchronous rollout records cause bounded waiting followed by refusal. [Barrier](https://github.com/vercel-labs/durable-harness/blob/ea8aa4060cf4fb8cd39737ce8ab21c4e0f40c741/packages/harness-codex/src/harness.ts#L627)
- **Recovery:** restores native logs and uses `thread/resume`; `thread/inject_items` inserts results under original call IDs. Continuing an interrupted task can add a recovery note and start a new native Turn through `turn/start`. [Recovery](https://github.com/vercel-labs/durable-harness/blob/ea8aa4060cf4fb8cd39737ce8ab21c4e0f40c741/packages/harness-codex/src/harness.ts#L1061)
- **Unknown outcomes:** completed calls reuse recorded results; unexecuted calls are identified; uncertain calls may receive an interrupted/may-have-taken-effect result. This differs from waiting for an independently running remote task. [Repair](https://github.com/vercel-labs/durable-harness/blob/ea8aa4060cf4fb8cd39737ce8ab21c4e0f40c741/packages/harness-codex/src/harness.ts#L1250)

These facts justify testing the release route. They do not establish full compatibility with our contracts or count as tests passing in our environment.

## Candidate scope

Adopting the integration mechanism does not automatically mean adopting all durable-harness infrastructure.

| Scope | Approach | Open issues |
|---|---|---|
| **A: own app-server adapter using the reference mechanisms** | Official binary plus rollout barriers, result repair and RPC recovery; retain Session Service, Runtime and Tool Worker responsibilities. | Maintain protocol/log mappings while avoiding competing authoritative stores and schedulers. Recommended first experiment. |
| **B: reuse durable-harness adapter packages** | Assess whether its Codex/process adapter can connect to this platform. | Investigate World, Sandbox, relay and lifecycle dependencies, required World implementation and topology changes. Do not assume the Vercel Sandbox path can run unchanged on Kubernetes. |

Logical structure for A:

```text
Session API → Agent Service / Session Service
                         ↕
               Own Runtime / Codex RPC adapter
                  ↕                    ↕
         Official codex app-server   Tool Worker
                  ↕                    ↕
          Controlled model egress   External execution
```

This is a responsibility diagram; process/Pod placement remains a future experiment decision.

- Session Service retains leases, fencing, checkpoint commits, tool Outbox, budgets and accepted results. Rollout is recovery material, not a competing authoritative platform ledger.
- Persist call associations and recovery state before tool dispatch; require platform commit acknowledgement. Preserve call/task/execution/result identities and argument validation.
- Tool Worker tasks already accepted continue under the existing lifecycle contract. A replacement Brain must query/wait for the original task and inject its result, rather than automatically treating Brain loss as tool interruption.
- Model requests use controlled egress with common admission, budgets, parameters, credentials and accounting. Logging alone does not make model requests exactly replayable; interrupted streams and unknown usage remain explicit.
- Disable undeclared native shell, files, search, skills and user configuration discovery. Enable capabilities through explicit contracts and permissions.

## Comparison with the current Rust route

| Item | Current Rust Brain | app-server candidate |
|---|---|---|
| Dependency | Pinned core commit compiled from source; no current core patches | Pinned official version, platform and digest; no core compilation |
| State access | Core history interfaces and own `ThreadStore` | Rollout synchronization and RPC; handle async writes, truncation, rotation and corruption |
| Native recovery | Inject results and call `recover_turn_if_idle`, preserving the Turn without a new user recovery message | Reference adds a recovery note and new native Turn; investigate other applicable release interfaces |
| Platform Turn | Bound to committed recovery state | May map one platform Turn to multiple native Turns; define inputs, cancellation, output and budgets |
| Long tools | Implemented association/wait/result-application path for original Worker tasks | Revalidate equivalent behavior; do not close still-running original tasks as unknown outcomes |
| Upgrade cost | Rust build, internal API and state compatibility | Binary distribution, app-server and rollout compatibility; still pin versions |

Preserving the native Turn is a clear current advantage, but whether it is a non-negotiable product requirement remains a decision. Separate platform and native Turn identity. Independent Tool Workers and external model budgeting alone do not prove that source linking is necessary.

## Future validation

All items remain pending. Reference tests do not count as this project's acceptance.

| Stage | Coverage | Acceptance |
|---|---|---|
| V0 Version/isolation | Pin release, platform digest, RPC/log formats; establish an experimental target | Reproducible clean installation, no core build/implicit patches, isolation from current Sessions |
| V1 Basic behavior | Text, multiple Turns, dynamic tools, result injection, streaming and cancellation with current DashScope configuration | Comparable public API and effective model/tool behavior; no reliance on lost in-process callbacks |
| V2 Pre-tool durability | Delayed rollout, lost receipts, rejected commits, lease loss and conflicting arguments | No execution before confirmed durability/authorization; reconcile uncertain commits under original identity; fence stale owners |
| V3 Long-tool takeover | Kill Brain before/after completion; partial A/B completion; duplicate result delivery | Original tasks continue with stable identities; accepted results are injected without duplicate side effects for the same managed call |
| V4 Recovery/lifecycle | Undelivered input, recovery notes/new native Turns, cancellation races, damaged logs and version incompatibility | No lost inputs or duplicate public output; explicit Turn mappings; stop rather than blindly replay uncertain state |
| V5 Budgets/effectiveness | Broken streams, 429, native retries, compaction, auxiliary calls and recovery overhead; paired tasks | All attempts counted; recovery prompt changes observable; equal samples and predefined metrics under the consistency plan |
| V6 Operations/rollback | Cold start, memory, image size, upgrades, isolation and credentials | Reproducible metrics, explicit old/new routing and safe experiment shutdown without affecting Rust Sessions |

Answering prompts and resuming threads alone are insufficient. Use real release binaries, Session Service and Tool Worker in cross-process fault tests, not only fake app-server or in-memory protocol peers. Cover Pod failures, duplicate delivery and competing owners explicitly.

## Future decision and rollback

Only compare maintenance cost, performance and success after correctness gates, acceptable platform/native Turn mapping and assessment of recovery-prompt effects. Predeclare thresholds and failure classifications; retain all attempts.

- Passing release-route tests permits proposing a switch separately; this document does not authorize one.
- For gaps, record the exact RPC/state boundary/version and reproducible case. Consider adapter work or upstream interface upgrades before retaining source linking as necessary.
- Default to new Sessions only; existing Sessions stay on Rust. Do not cross-read checkpoints or change existing bindings without migration validation.
- Rollback first stops new experimental Sessions and completes or terminates existing experiments appropriately. A direct image change does not establish checkpoint interchangeability.

Related: [current Rust implementation](../../agent-harness-runtime/rust/README.en.md), [Session/Hands protocol](brain-session-hands-protocol.en.md), [evaluation consistency plan](../../tests/general-agent-eval/consistency-plan.en.md).

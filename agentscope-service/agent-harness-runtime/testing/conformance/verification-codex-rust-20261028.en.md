# Codex Rust initial implementation and verification

[中文](verification-codex-rust-20261028.md)

Implementation directory: `/Users/ken/agentcore/agentcore-managed-agents`, branch `master`. Work stayed in the main checkout without worktrees, commits, pushes or deployment. Existing and concurrent changes were preserved.

## Implementation

`rust/` contains the protocol runtime, native Codex adapter and single-binary Brain. It directly links codex-core at `e742db9f7ba98e63e502b310857d49d047888518`, with no upstream patches or Node/CLI/app-server dependency. Session Service accepts explicit codex bindings and neutral model-provider configuration; image files live in `images/codex/`.

Tool boundaries capture native history before suspending/shutting down the kernel. Session Service commits the checkpoint and authorization, then existing Outbox processing dispatches to Hands. Results are injected into the idle thread under original call IDs and committed with resultApplications before resuming the original Turn. Partial completion preserves A while B remains pending; shutdown cleanup never overwrites the recovery point. A subsequent user Turn retains Session history and starts its own native Turn.

## Executed checks

| Check | Result |
| --- | --- |
| `cargo test --locked --workspace` | Passed: 17 test entries including one subprocess helper used by the crash test. Real-core coverage includes A/B partial results, owner takeover, lost receipts, SIGKILL cold recovery, successive user Turns and a new Turn after cancellation. |
| `cargo clippy --locked --workspace --all-targets --no-deps -- -D warnings` | Passed. Upstream still reports a PTY deprecation and an unused crossterm patch; upstream was not modified. |
| `cargo fmt -- --check` for the three local crates | Passed. |
| `cargo build --locked -p managed-brain-codex` | Passed; native macOS debug binary, not a Linux image build. |
| `scripts/smoke-worker.py` | Passed: version, authenticated queue polling, liveness/readiness, recovery from unavailability/malformed replies, health CLI and SIGTERM. |
| Independent schema validation of actual requests | All 41 Rust/native-core flow requests passed shared protocol 0.1.0 schemas. |
| protocol `make check` | 8 schemas, 16 APIs, 86 positive and 25 negative cases; Node verified four JCS and four identity vectors. Node is used only by shared contract checks, not by the Codex image. |
| `HarnessProtocolStoreTest` | Seven tests passed in the full build log, including `codexClaimsAndCommitsItsOwnNativeState`. |
| Docker Compose configuration merge | Passed using test placeholders: Rust image/health check, no inherited DB/MQ configuration or Hands mounts. |

## Failed or unexecuted checks

- Root `mvn -B -ntp -T1 clean verify`: Java compilation and executed tests passed, then Agent Service Javadoc failed to resolve project dependency packages.
- Supplemental `verify -Dmaven.javadoc.skip=true` and final `spotless:check`: concurrent changes to `HarnessProtocolStore.java` failed formatting checks. Other work was not overwritten or reformatted; root verification cannot be marked successful.
- Docker daemon did not answer the bounded check. No Linux image was built/run, and no deployment occurred.
- Tests use real codex-core with model, Session Service transport and Hands-result doubles. The Rust binary has not completed full-chain fault/capacity acceptance against real Java Session Service, PostgreSQL, RocketMQ, Redis and external Hands.

See [Rust Brain](../../rust/README.en.md) for supported scope and follow-up work. These results establish the initial core loop and local binary, not production acceptance.

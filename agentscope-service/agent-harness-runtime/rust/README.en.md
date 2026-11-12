# Codex Rust Brain

[中文](README.md)

This standalone Rust Brain links the open-source `codex-core` library directly. It needs no Node.js and launches neither the Codex CLI nor app-server. Session Service remains authoritative for transactions, leases, tool outboxes and accepted results. Existing Tool Workers execute Hands operations.

## Modules and build

- `crates/runtime`: protocol 0.1.0, JCS/deterministic IDs, verified HTTP objects, receipt reconciliation, leases, tool/confirmation suspension, result application, budgets and worker.
- `crates/adapter-codex`: native ThreadManager, dynamic tools, rollout checkpoints, original call_id injection, original Turn recovery and an in-process Rust model HTTP endpoint.
- `crates/brain-codex`: configuration, executable entry point, health checks and SIGTERM/SIGINT.

Upstream is pinned to `e742db9f7ba98e63e502b310857d49d047888518`, using Rust 1.95.0. `scripts/prepare-codex.sh` verifies the archive SHA-256 and extracts it into ignored `target/upstream`. There are no core patches. `Cargo.lock` pins dependency resolution. Do not replace that source directory independently or accept checkpoints from another revision.

```sh
cd managed-agents/agent-harness-runtime/rust
sh scripts/prepare-codex.sh
cargo test --locked --workspace
cargo build --locked -p managed-brain-codex
python3 scripts/smoke-worker.py
cargo fmt -p managed-brain-runtime -p managed-adapter-codex -p managed-brain-codex -- --check
```

Source and Cargo dependencies require network access. Linux builds require C/C++ tooling, CMake, pkg-config, OpenSSL headers and protoc. The dependency graph is large: incremental compilation is disabled and build concurrency defaults to three. Prepare sources again after `cargo clean`. Test threads and service workers use 16 MiB stacks for the core's debug futures.

## Durability boundaries

1. Before inference, check ownership, reserve the model call, and commit its dispatch with native history. Only a confirmed receipt permits the provider request.
2. The in-process Rust HTTP endpoint validates the complete response before Core sees it. Only configured `hands_*` dynamic tools are allowed. The native environment manager uses `without_environments`.
3. Once all dynamic calls in the response appear, capture raw rollout history, invoke `suspend_turn_and_shutdown`, then await ThreadManager shutdown. Synthetic failure records from cancellation cleanup remain in that incarnation's scratch store and never replace the captured recovery point.
4. Resolve tool policy while the native thread is idle. `always_ask` persists a confirmation and suspends. Session Service can dispatch Hands only after tool calls, deterministic taskId/executionId and the checkpoint commit together.
5. If A completed and B is running, inject A's original result into an idle thread and commit its `resultApplications`. Only B remains pending. The inline wait deadline commits a suspension and releases ownership.
6. A new instance loads the committed checkpoint and accepted results, verifies identity, injects each original `call_id` with `inject_response_items`, commits its application, then calls `recover_turn_if_idle`. It preserves the Turn without adding user input, relying on old oneshots or dispatching authorized tools again.
7. Checkpoints bind the platform Turn. A subsequent user message keeps the native thread history and starts a new native Turn with `start_turn_if_idle`. Calls left unresolved by a cancelled preceding Turn receive history closure records without dispatching to Hands again.

The adapter’s `ScratchStore` implements the native ThreadStore interface as staging for one incarnation. Fenced Session Service commits provide durability across processes. An uncertain response triggers lookup of the original commitId; only explicit `receipt_not_found` allows an identical retry. Lease loss and result identity conflicts stop that incarnation.

## Configuration

| Variable | Meaning |
| --- | --- |
| `SESSION_SERVICE_URL` | For example `http://agent-service:8084/internal/session-service/v0` |
| `MANAGED_AGENTS_INTERNAL_TOKEN` | Internal Session Service credential |
| `CODEX_MODEL_API_KEY` | Optional default-provider credential; not required with explicit credentialEnv |
| `CODEX_MODEL_BASE_URL` | Responses HTTP API base; default `https://api.openai.com/v1` |
| `CODEX_MODEL_PROVIDER` | Provider for the default binding; default `openai` |
| `BRAIN_CONCURRENCY` | Concurrent Sessions; default 4, range 1–64 |
| `BRAIN_INLINE_WAIT_MS` | Inline tool wait; default 1000, range 0–600000 |
| `BRAIN_INSTANCE_ID` | Instance name; each boot also creates a new incarnationId |
| `PORT` | Health port; default 8082 |

Configure Agent Service with `MANAGED_AGENTS_HARNESS_TYPE=codex`, `MANAGED_AGENTS_MODEL_PROVIDER`, and `MANAGED_AGENTS_RUNTIME_SESSION_QUEUE_ENABLED=true`. Existing Session harness bindings cannot be switched in place; use a new Session. Brain needs no platform database, Redis, MQ, object store or Hands credentials, and no Hands workspace mount.

Build from the repository root:

```sh
docker build -f managed-agents/agent-harness-runtime/images/codex/Dockerfile \
  -t managed-agents-brain-codex:dev .
```

Helm supports `brain.type=codex`. Set `brain.codex.existingSecret` to a dedicated Secret containing only the internal token and model credentials, and enable `runtime.sessionQueue.enabled=true`. `brain.codex.modelProvider`, `modelOptions`, and `modelBindings` configure Agent Service; model credentials go only to Brain. `modelBaseUrl` is Brain's default Responses endpoint. The rendered Brain uses the native Rust image, non-root UID 10001, a read-only root filesystem, and temporary directories. It receives no DB/Redis/MQ/OSS credentials and runs no Java dataplane schema Job. Service, Runtime, and Control Plane schema initialization is still required.

Use `images/codex/compose.override.yaml` with the existing deployment. `--version` reports the upstream revision and protocol. `--health-check` checks readiness. `/healthz` reports liveness; `/readyz` becomes ready after contacting Session Service and returns 503 while draining or disconnected. Stopping Brain leaves accepted Hands tasks available for platform recovery.

## Model bindings and field mapping

Agent Service's `MANAGED_AGENTS_MODEL_BINDINGS` is a JSON object mapping model aliases to complete `ModelConfiguration` values for the generic PI / PI Durable / Codex path. Unknown aliases in a nonempty catalog are rejected without falling back to a default provider. An empty catalog retains the definition's model ID, `MANAGED_AGENTS_MODEL_PROVIDER`, and `MANAGED_AGENTS_MODEL_OPTIONS`; the latter falls back to the existing `MANAGED_AGENTS_PI_MODEL_OPTIONS`. The first compiled configuration and its digest remain fixed for the Turn, including recovery after catalog changes. Connecting the AgentScope prepared factory to this catalog remains outstanding.

Control Plane `system` maps to native `base_instructions`, replacing the default Codex CLI persona. Native permission instructions for the disabled local execution/escalation tools are omitted; platform Hands still enforces actual tool policy. See the [independent ACS deployment record](../../../docs/acs-pi-codex-acceptance-20261030.en.md) for images and real functional acceptance.

For example, configure this nonsecret catalog in the Service and inject `MANAGED_MODEL_FAST` through a Brain deployment secret. Add custom names explicitly to the container environment; Compose does not forward every host environment variable automatically.

```json
{
  "managed-fast": {
    "provider": "managed",
    "id": "served-model",
    "api": "openai-responses",
    "baseUrl": "https://models.example.invalid/v1",
    "credentialEnv": "MANAGED_MODEL_FAST",
    "contextWindow": 32000,
    "outputLimit": 2048,
    "reasoning": false,
    "parameters": { "temperature": 0.2, "maxTokens": 512 }
  }
}
```

| Field | Codex behavior |
| --- | --- |
| provider / id | provider selects the credential binding; id configures the native model and must match its HTTP request |
| api | Only `openai-responses` is supported; other APIs are rejected |
| baseUrl / credentialEnv | Independent endpoint per instance; custom endpoints require restricted credential references and cannot reuse the default credential; accepts `MANAGED_MODEL_` and the existing `PI_MODEL_` prefixes |
| contextWindow | Updates the per-instance native catalog's context window and maximum; custom endpoints require both contextWindow and outputLimit |
| outputLimit / parameters.maxTokens | Maps to Responses `max_output_tokens`; maxTokens cannot exceed explicit limits, and a smaller native per-request cap is preserved |
| parameters.temperature / reasoning | Maps to `temperature` / `reasoning.effort`; native reasoning metadata is updated too; explicit reasoning=false removes reasoning parameters and corresponding include entries |
| cost | Validated and retained as model metadata; platform billing still uses its own pricing configuration, so this does not establish billing from that field |

Endpoints must be HTTP(S) URLs without user information, queries, or fragments. Credentials are resolved only in endpoint memory, never into configuration, budget requests, or native checkpoints. Missing credentials and inconsistent API, token-limit, or reasoning settings reject execution. Without an explicit binding, the default provider may use `CODEX_MODEL_BASE_URL` / `CODEX_MODEL_API_KEY`; other providers require their own endpoint and credential reference.

See the [verification record](../testing/conformance/verification-model-bindings-20261029.en.md) for tested behavior and remaining scope.

## Supported scope and limitations

The initial implementation supports a single root thread with successive user Turns, text Responses HTTP, dynamic function tools, complete native-history checkpoints, confirmation/denial, partial results, cold recovery of the same Turn, cancellation and model-call budgets. Each native instance advances one model response and is rebuilt at tool boundaries; no Tokio Future serialization is needed. Public model text streams through a bounded preview lane. Executable responses remain fully validated before reaching Core, with a 64 MiB response limit. See [preview protocol and verification](../testing/conformance/verification-previews-20261029.en.md).

Not yet covered: child-agent tree recovery, Code Mode, native local shell/filesystem, ChatGPT login, WebSocket/Realtime, multimodal tool content conversion, cross-version checkpoint migration and segmented/incremental history. Native auto-compaction and other paths requiring multiple consecutive model requests within an instance are not integrated. Additional messages/steering are not wired to native input yet; unapplied inputs are explicitly rejected at termination. Production still requires real Session Service + MQ + Hands container-failure, network-partition and capacity acceptance. Native-library and protocol tests do not replace that acceptance.

See the [Codex Rust verification record](../testing/conformance/verification-codex-rust-20261028.en.md) for this implementation run.

The Codex Runtime, Adapter and Brain entry point all live in this Rust workspace; the TypeScript workspace serves only PI / PI Durable.

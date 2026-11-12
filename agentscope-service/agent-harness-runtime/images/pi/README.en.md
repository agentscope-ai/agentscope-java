# PI Brain image and deployment

[中文](README.md)

One Node 22 image contains `pi` and `pi-durable`, selected by `MANAGED_AGENTS_HARNESS_TYPE`. Changing a deployment does not change an existing Session's kernel binding. Brain accesses Session Service and model endpoints; Service/Hands retain database, MQ and object-store credentials. See the [implementation plan](../../typescript/PI-IMPLEMENTATION.en.md) for platform capabilities and remaining work.

## Build and release

From the repository root, build and load a single-platform local image:

```sh
python3 managed-agents/release/release.py images-pi \
  --version 2.1.0-BETA1 --repository registry.example/team \
  --platforms linux/amd64 --output /tmp/pi-image-release
```

`images-pi` handles only `managed-agents-brain-pi`; ordinary `images` retains its five-component behavior. Builds include OCI version/revision labels and `image-brain-pi.json` metadata. Publishing requires explicit `--push`; use `linux/amd64,linux/arm64` for both architectures. Publishing retains the clean-checkout requirement and generates SBOM/provenance. `--skip-existing --push` verifies existing source/platforms and writes separate `images-pi.json`, preserving the five-component manifest. A Linux amd64 image was published on 2026-10-30 and basic `pi` acceptance passed in an independent ACS namespace; see the [deployment record](../../../../docs/acs-pi-codex-acceptance-20261030.en.md). This used a Linux build Pod and OCI assembly; it does not establish completion of the standard release pipeline, multiple architectures, or `pi-durable` acceptance.

The Dockerfile pins pnpm 10.28.2, installs locked dependencies, builds, and runs `pnpm deploy --legacy --prod`. The final non-root Node image requires no Maven/JVM, tool-execution binaries or local SDK credential files.

## Helm

Create a dedicated Secret containing only `MANAGED_AGENTS_INTERNAL_TOKEN` and selected model credentials, such as `PI_MODEL_KEY` or a built-in provider's `OPENAI_API_KEY`. The internal token must match Service. Example overrides:

```yaml
brain:
  type: pi-durable
  pi:
    existingSecret: managed-pi-brain
    modelProvider: managed-openai
    modelOptions:
      api: openai-completions
      baseUrl: https://models.example/v1
      credentialEnv: PI_MODEL_KEY
      contextWindow: 128000
      outputLimit: 4096
      cost: {input: 1, output: 2, cacheRead: 0.1, cacheWrite: 1}
      parameters: {temperature: 0.2, maxTokens: 2048}
runtime:
  sessionQueue:
    enabled: true
```

The model `id` still comes from the admitted Agent version's `model`. `cost` specifies prices per million tokens; configure actual provider prices. Custom models require explicit costs. Custom endpoints require a `credentialEnv` reference using the `PI_MODEL_` prefix, preventing references to the internal Service token. Credential values enter only the in-memory CredentialStore, not sealed Turn configuration, native snapshots or model request facts. `parameters.maxTokens` caps normal and auxiliary requests while preserving smaller native limits. These are installation defaults sealed on first Turn configuration load; Control Plane per-model settings and dynamic tenant Vault credentials remain outstanding.

The PI Deployment uses `/healthz`, `/readyz` and `/metrics`, without database/MQ/object-store Secrets, shared workspace PVCs or Java init containers. The root filesystem is read-only; `/workspace` and `/tmp` are ephemeral. PI omits the Java Brain dataplane schema Job; Service, Managed Runtime and Control Plane migrations remain. The default `brain.type=agentscope-java` preserves existing deployment behavior.

Default shutdown timing is 30 seconds of drain plus 2 seconds of abort grace, inside a 40-second Kubernetes termination window. Templates require the latter to exceed both deadlines combined. Replicas/resources still use `agent-harness-runtime` values. Upgrade Session Service's paging/deferral protocol before PI Brain. New readers support old complete snapshots; once segmented state is written, old Brain images reject it, so rollback requires an image that understands the segmented encoding.

## Compose and runtime settings

Merge `compose.override.yaml` with `managed-agents/deploy/compose.yaml` using a Compose version supporting `!override`. Set `MANAGED_AGENTS_PI_PROVIDER` and optionally `MANAGED_AGENTS_PI_MODEL_OPTIONS` (the same `modelOptions` as JSON), with credentials injected through environment/Secrets. The example forwards `PI_MODEL_KEY` and common built-in provider keys; explicitly add other credential names when needed.

| Setting | Default | Meaning |
| --- | --- | --- |
| `SESSION_SERVICE_URL` / `MANAGED_AGENTS_INTERNAL_TOKEN` | Required | Internal protocol URL and credential |
| `BRAIN_CONCURRENCY` | 4 | 1..64; suspension frees a slot |
| `BRAIN_INLINE_WAIT_MS` | 1000 | 0..600000; sealed Turn value takes precedence |
| `BRAIN_POLL_MS` / `BRAIN_HTTP_TIMEOUT_MS` | 500 / 30000 | Poll interval / individual HTTP timeout |
| `BRAIN_DRAIN_MS` / `BRAIN_ABORT_GRACE_MS` | 30000 / 2000 | Admission-stop drain and abort deadlines |
| `BRAIN_RETRY_BASE_MS` / `BRAIN_RETRY_MAX_MS` | 1000 / 300000 | Jittered exponential backoff range |
| `BRAIN_RETRY_BUDGET` | 8 | Maximum-interval cooldown after this many failures; uncertain tools are not marked failed |

Attempt counts live in a bounded process cache. Service persists not-before times; cumulative counts, alerts and an operator quarantine queue are not implemented. Deferrals reuse the delivery and deferralId, carry a fence when owned, and neither release the lease nor replace tool identities. Readiness depends on the latest successful Service poll; liveness reports process responsiveness. `/metrics` exposes active/ready gauges and claimed/completed execution, retry, ownership-conflict and deferral-failure counters. Complete model/tool tracing remains outstanding.

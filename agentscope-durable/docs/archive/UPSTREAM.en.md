# Agent Harness Runtime

> Historical import documentation for provenance only. Paths, build commands and
> support claims below belong to the source repository; use the [current README](README.en.md).

[中文](README.md)

All Brain implementations live here; select one kernel per deployment. See the [implementation map](protocol/implementation-map.en.md) for support and limits.

For future validation of the Codex release app-server route, see the [durable-harness integration alternative](../docs/design/codex-app-server-alternative.en.md). It is not implemented; the Rust Brain remains in use.

```text
protocol/                    HTTP, logical messages, schemas and handoff examples
java/runtime/                HTTP client, JCS and schema validation
java/codec-agentscope/        Native checkpoint/event codec, without execution or platform storage
java/adapter-agentscope/      Native interpretation and recovery regressions
java/brain-agentscope/        Java assembly, existing scheduler and service entry
typescript/packages/runtime/ Client, leases, commits, tool waiting, inputs and worker
  adapter-pi/                Plain PI SessionManager / PI durable Storage
  brain-pi/                  PI / PI Durable entry point
rust/                        Standalone Codex Rust core, platform runtime and Brain entry
images/                      Per-Brain images
testing/conformance/         Shared behavior acceptance entry
```

Agent Service explicitly uses the lightweight `codec-agentscope` for checkpoint administration; it does not load the execution adapter or start a Brain. Native TaskRepository, StateStore and SkillRepository implementations belong to the adapter; shared previews accept text deltas only.

Session Service APIs, transactions and background processing all live under `../agent-service/src/`: the `agentservice/sessionservice` package owns APIs and composition, while `runtime/managed` owns transactions and background processing. They share one Agent Service Maven module, image and JVM. Hands is in `../tool-worker/`, deployed with `*-exec.jar`. TypeScript is outside the Maven reactor.

`common` retains cross-role entities, tool contracts, result publication, infrastructure and ledger access. Hands async execution, Session Service child execution, native AgentScope helpers and local Brain assembly belong to their respective modules; see [implementation ownership](protocol/implementation-map.en.md#java-implementation-ownership) for concrete classes and remaining dependencies.

Build Java from the repository root:

```sh
mvn -B -ntp -T1 -pl managed-agents/agent-service,managed-agents/agent-harness-runtime/java/brain-agentscope,managed-agents/tool-worker -am package -DskipTests
```

Set Java Brain's `MANAGED_AGENTS_SESSION_SERVICE_URL=http://agent-service:8084/internal/session-service/v0` and the existing internal token. Startup registers a remote process lease; scheduling coordination uses Service. Redis/MQ transport, local JPA catalog/memory and some infrastructure assembly still require their existing settings.

For the AgentScope flow audit and fixes, see [implementation-map](protocol/implementation-map.en.md#agentscope-flow-verification). The independent Codex implementation is documented in [Rust Brain](rust/README.en.md); each kernel still needs infrastructure acceptance.

PI requires Node 22+ and pnpm 10.28.2:

```sh
cd managed-agents/agent-harness-runtime/typescript
pnpm install --frozen-lockfile --ignore-scripts
pnpm -r build
SESSION_SERVICE_URL=http://localhost:8084/internal/session-service/v0 \
  MANAGED_AGENTS_HARNESS_TYPE=pi node packages/brain-pi/dist/index.js
```

Also set `MANAGED_AGENTS_INTERNAL_TOKEN` and model provider credentials. Agent Service must use the same `MANAGED_AGENTS_HARNESS_TYPE` and enable `MANAGED_AGENTS_RUNTIME_SESSION_QUEUE_ENABLED=true`. `MANAGED_AGENTS_PI_PROVIDER` selects the provider; Agent definitions supply the model ID. Select `pi-durable` through the same Brain entry point. TypeScript Brain needs no DB, Redis, MQ or object-store credentials.

Merge `images/pi/compose.override.yaml` with deploy/compose.yaml when preparing deployment. Docker build context is the repository root; containers have not been built/deployed. The Codex image links the open-source core in an independent Rust executable. See [Rust Brain](rust/README.en.md) for build commands, configuration and supported scope. The TypeScript workspace contains only three packages: Runtime, PI Adapter and PI Brain.

Node health/readiness endpoints are `/healthz` and `/readyz` on port 8082. SIGTERM/SIGINT stop intake and cancel local kernel work; Hands retains original external executions.

See [conformance](testing/conformance/README.en.md) for focused checks. Real infrastructure and fault acceptance remain required before deployment.

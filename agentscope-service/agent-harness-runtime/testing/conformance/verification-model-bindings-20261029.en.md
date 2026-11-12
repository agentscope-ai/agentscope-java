# Model Binding and Codex Parameter Verification

[中文](verification-model-bindings-20261029.md)

Date: 2026-10-29. Directory `/Users/ken/agentcore/agentcore-managed-agents`, branch `master`, HEAD `ed482512993468e8dd68c9dac0a06f63b752fde5` plus existing working-directory changes. This covers W06's generic model catalog, PI credential compatibility, and Codex model-field consumption. W06 is not complete.

## Implementation and behavior

- Service adds `managed-agents.runtime.model-bindings` / `MANAGED_AGENTS_MODEL_BINDINGS`, selecting complete bindings by the effective definition's model alias. A nonempty catalog rejects unknown aliases without merging another provider's defaults. An empty catalog retains legacy defaults; neutral `model-options` falls back to the PI property.
- Bindings use the existing per-Turn sealed configuration and digest. Tests resolve aliases from encrypted definitions and Session overrides and verify native ID, provider, and credentialEnv. A new compiler with changed deployment defaults still returns the original configuration.
- The shared Java model contract adds endpoint, credential-reference, context/output-limit, and reasoning constraints beyond Schema validation. Codex rejects non-Responses APIs; custom endpoints require explicit context and output limits. Validation precedes MCP sealing and model dispatch, but is not complete Control Plane capability admission.
- Protocol and PI accept `MANAGED_MODEL_` and existing `PI_MODEL_` references. PI checks request output limits and native reasoning support; separate instances using the same provider remain isolated by endpoint and credential.
- Rust retains all declared ModelConfiguration fields and rejects unknown fields and invalid semantics. Each native instance resolves its own endpoint and credentials; missing explicit references never fall back to the process default key. Keys are absent from budget requests and durable objects.
- Codex's native catalog uses configured contextWindow and reasoning metadata. The HTTP endpoint maps temperature, max_output_tokens, and reasoning.effort before the budget gate, so accounting observes the dispatched request. Explicit reasoning=false omits corresponding parameters. Tool allowlisting, one response per native instance, previews, and recovery boundaries remain.
- Default-provider endpoint/key configuration remains supported. Explicit credentialEnv no longer requires CODEX_MODEL_API_KEY. Compose forwards catalog/options; deployments must explicitly inject custom secret environment names.

## Verification

| Check | Result and scope |
| --- | --- |
| Targeted Java Maven | 11 passed, no failures/skips: configuration 7, PI HTTP 4; affected-module build and formatting passed |
| TypeScript workspace | Build passed; Runtime 37 and PI Adapter 37 passed; 3 historical TS Codex probe cases do not count as Rust acceptance |
| Rust workspace | 31 passed: Adapter unit 7, native integration file 7, Runtime unit 5, previews 3, recovery 9 |
| Rust binary | Build and smoke passed without CODEX_MODEL_API_KEY and with a named credential environment; readiness recovery, authenticated polling, malformed responses, and SIGTERM worked |
| Protocol | 10 Schemas, 19 operations, 110 positive and 46 negative cases; 4 JCS and 4 identity vectors passed |
| Compose | `docker compose config --quiet` passed with synthetic configuration and an empty default key; no deployment |

A new real codex-core test runs two configurations concurrently through one Kernel, targeting separate HTTP paths. It checks credential selection, native model IDs, distinct temperatures/output limits, reasoning on/off, successful completion, and no keys in persisted objects. The model endpoints are deterministic local fixtures and Session Service is a protocol test double; this does not establish the full Rust-to-real-Service Control Plane flow.

The native catalog test reads through Core's ModelsManager and verifies that an explicit context window is not silently clamped to a default maximum. The first regression run rejected existing default-model paths by requiring limits for all fallback metadata. The requirement now applies to explicit custom endpoints, and the final suite passes. The pinned upstream kernel was not modified.

```sh
# Repository root
make -C managed-agents/agent-harness-runtime/protocol check PYTHON=/tmp/managed-agents-protocol-venv/bin/python
mvn -B -ntp -T1 -pl managed-agents/agent-service -am test -Dtest=HarnessRunConfigurationTest,PiBrainHttpFlowTest -Dmanaged.pi.http=true -Dsurefire.failIfNoSpecifiedTests=false

# typescript directory
pnpm -r build
pnpm -r test

# rust directory
/Users/ken/.cargo/bin/cargo test --locked --workspace
/Users/ken/.cargo/bin/cargo build --locked -p managed-brain-codex
python3 scripts/smoke-worker.py
/Users/ken/.cargo/bin/cargo fmt -p managed-brain-runtime -p managed-adapter-codex -p managed-brain-codex -- --check
```

Local logs: `/tmp/managed-brain-model-java.log`, `/tmp/managed-brain-model-ts.log`, and `/tmp/managed-brain-model-rust.log`. No repository-wide clean verify / spotless, image build, container deployment, commit, or push.

## Remaining scope

- Subsequent work connects AgentScope prepared shared model selection and its factory; see [AgentScope model verification](verification-agentscope-models-20261029.en.md). This record retains its historical test scope. Child models/options and native registered-model pinning still require follow-up.
- W07 still needs Control Plane admission by kernel version, capability queries, and complete field coverage. Current rejection occurs during generic configuration compilation, worker validation, or Adapter setup, not necessarily before Session creation.
- Cost remains model metadata; platform pricing is independent. Align model aliases with billing identities, cache pricing, and unknown-usage policy. Credentials currently use restricted environment references; model Vault binding and revocation semantics remain to be unified.
- Codex's native transport currently uses Responses. Supporting other APIs requires protocol translation or upstream extension rather than changing an enum. Unsupported APIs are explicitly rejected; multi-API equivalence is not claimed.
- Continue W05–W20 for real Control Plane/Service flows, actual provider parameter compatibility, public SSE, resources, long context, subagents, and release acceptance.

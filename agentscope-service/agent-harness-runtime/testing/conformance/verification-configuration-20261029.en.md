# Effective Run Configuration Identity and Native Construction Verification

[中文](verification-configuration-20261029.md)

Date: 2026-10-29. Directory `/Users/ken/agentcore/agentcore-managed-agents`, branch `master`, HEAD `ed482512993468e8dd68c9dac0a06f63b752fde5` plus the existing uncommitted working directory. This record covers W05 generic configuration identity and part of W19 construction verification. W05 / W07 remain incomplete overall.

## Implementation

- Service reads Agent ID / version from the encrypted sealed definition, applies overrides through the common merger, and compiles model, tools, and instructions. The generic response now carries a `managed-effective-agent-v1` identity, with RFC 8785 / SHA-256 digests for the source definition, effective definition, and entire response.
- The complete response is cached per Session / Turn. Reads validate Schema, digest, scope, Turn, and database kernel binding. Recreating the compiler or changing deployment model defaults cannot change that Turn. Unreleased development caches without identity are rejected rather than replaced during recovery.
- PI / PI Durable and Codex workers validate the envelope before reading recovery or constructing native instances. Modified model/tool content or metadata, wrong Session / Turn / kernel, and unknown formats are rejected without echoing configuration. This check relies on service authentication; it neither replaces authorization nor provides an independent signature.
- `PiBrainHttpFlowTest` no longer prefills run configuration. It uses actual `ManagedCatalog` decryption, common overrides, configuration compilation, HTTP, Node workers, and native PI SDKs. The provider verifies the actual model ID and transcript system messages. Standard and durable modes each cover continuous execution and process replacement after tool suspension.
- Existing real Hands shell, execute-once, lost receipt at every commit, public previews and final-message identity, large-message, and native-recovery assertions remain. Model and empty MCP discovery are fixtures; H2 and in-memory systems replace production storage/messaging. The sealed definition is constructed by the test; the real Control Plane creation API is not exercised.

## Verification Results

| Check | Result |
| --- | --- |
| Protocol `make check` | 10 Schemas, 19 operations, 109 positive / 44 negative cases, and four JCS / four identity vectors passed |
| TypeScript workspace | Build passed; Runtime 37 and PI Adapter 36 tests passed; the historical TS Codex probe has three separate tests and does not count toward Rust acceptance |
| Rust workspace | 27 tests passed: Adapter unit 5, real-core integration file 6, Runtime unit 4, previews 3, recovery 9; includes new configuration-vector and rejection cases |
| Targeted Java regression | 10 tests passed with zero failures/skips: configuration 6, PI HTTP 4; affected-module build and format checks passed |

All three languages verify the same protocol configuration example. Java additionally covers actual sealed definitions, effective overrides, permission defaults, modified caches, cross-Turn reuse rejection, and a replacement compiler reusing original configuration. The TS worker test proves that an invalid identity prevents recovery reads, native execution, model dispatch, and commits.

The new provider assertion initially read the pre-normalization `Context.systemPrompt` field, causing the fixture model to return an error. Checking system messages according to the installed SDK's `TranscriptContext` contract fixed the observation point, and all four HTTP cases passed. This failure was in the test assertion, not a newly discovered native adaptation defect.

```sh
# Repository root
make -C managed-agents/agent-harness-runtime/protocol check PYTHON=/tmp/managed-agents-protocol-venv/bin/python
mvn -B -ntp -T1 -pl managed-agents/agent-service -am test -Dtest=HarnessRunConfigurationTest,PiBrainHttpFlowTest -Dmanaged.pi.http=true -Dsurefire.failIfNoSpecifiedTests=false

# typescript directory
pnpm -r build
pnpm -r test

# rust directory
/Users/ken/.cargo/bin/cargo test --locked --workspace
/Users/ken/.cargo/bin/cargo fmt -p managed-brain-runtime -p managed-adapter-codex -p managed-brain-codex -- --check
```

Local logs: `/tmp/managed-brain-config-java.log`, `/tmp/managed-brain-config-ts.log`, `/tmp/managed-brain-config-rust.log`. Maven targets Java 17. No repository-wide clean verify / spotless, image build, or deployment was performed. No commit or push.

## Remaining Work

- W05 still requires complete field mappings, resource references, model bindings, kernel-version requirements, and common configuration semantics for the AgentScope prepared path. A digest covering a definition does not prove every field is consumed by an Adapter.
- W07 still requires versioned capability discovery and rejection of unmapped fields before dispatch; contextPolicy / jev must not remain silently ignored. W06 still requires complete Codex model-field mapping.
- W19 / W20 still require actual Control Plane creation/authorization, Java and Rust flows with the same definitions, real infrastructure, and shared public SSE HTTP acceptance. Current evidence does not replace those gates.

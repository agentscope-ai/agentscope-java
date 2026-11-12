# AgentScope Shared Model Configuration Verification

[中文](verification-agentscope-models-20261029.md)

Date: 2026-10-29. Directory `/Users/ken/agentcore/agentcore-managed-agents`, branch `master`, HEAD `ed482512993468e8dd68c9dac0a06f63b752fde5` plus existing uncommitted changes. This covers AgentScope model selection and construction under W05 / W06, not complete W07 / W19 / W20 admission or real-environment acceptance.

## Implementation

- Service's prepared `model-configuration` operation checks the current Turn owner, epoch, token, and process lease before and after resolution. Sealing fixes the Session's kernel binding. Generic PI / Codex configuration uses the same model-selection store without resolving the Agent definition twice per compilation.
- `ModelBindingResponse` carries Agent ID/version and source/effective/configuration digests. Cache reads verify scope, Turn, and kernel. Catalog/options must be objects; malformed values cannot fall back. Changed catalogs or deployment defaults do not reinterpret the original Turn.
- AgentScope's production execution factory calls `ManagedAgentModels`, checks response identity against the actual sealed definition, and constructs independent clients for explicit bindings. Parameters also reach Harness GenerateOptions; requests cannot replace routing or credentials, or relax output caps through additional fields.
- OpenAI Chat Completions, OpenAI Responses, and Anthropic Messages map endpoints, credentials, model IDs, temperature, output caps, and context windows. Unmapped Anthropic reasoning effort is rejected. Responses uses the existing local official extension. Native streaming verifies `max_output_tokens`, `reasoning.effort`, text output, and `store=false`, without upstream source edits.
- With no explicit catalog/options, AgentScope's `native` mode retains named/deployment-default models. Sealing a name or default selection does not pin endpoints, credentials, or parameters inside the legacy ModelRegistry.

## Completed targeted verification

| Check | Result and coverage |
| --- | --- |
| `HarnessRunConfigurationTest` | 7 passed: encrypted sealed definitions, overrides, permissions, pinned cache, and shared configuration vectors |
| `SessionCoordinationHttpTest` | 9 passed: new actual Service HTTP model selection, invalid owner/epoch/process, cross-kernel rejection, cache tampering, and malformed catalogs; H2 and actual encrypted definitions |
| `ManagedAgentModelsTest` | 3 passed: native AgentScope OpenAI/Anthropic clients concurrently send isolated bindings to local HTTP fixtures, checking parameters, credentials, and caps; invalid configuration/request overrides are rejected |
| `HarnessAgentBuildServiceExecutionTest` | 2 passed: actual factory reads a prepared HTTP fixture, constructs/reconstructs HarnessAgents, and executes native model calls; overrides and bindings reach requests, while a resealed mismatched definition is rejected |
| `AgentScopeRemoteFlowTest` | 2 passed: existing native-core, Service HTTP, tool execution, and recovery regressions |
| PI HTTP and Java application wiring | Four PI cases passed after explicitly enabling `managed.pi.http=true`; one AgentScope Spring wiring case passed. The initial disabled PI skip is not counted as a pass |
| Protocol | 10 Schemas, 19 operations, 112 positive and 50 negative cases, plus 4 JCS and 4 identity vectors passed |

These targeted Java runs cover 28 distinct passing cases. Service HTTP, native-client HTTP, and factory HTTP have separate coverage; the factory's Service is a protocol fixture. Combining their counts does not prove the real Control Plane and full production-service composition.

```sh
mvn -B -ntp -T1 -pl managed-agents/agent-harness-runtime/java/brain-agentscope,managed-agents/agent-service -am test -Dtest=ManagedAgentModelsTest,HarnessAgentBuildServiceExecutionTest,HarnessRunConfigurationTest,SessionCoordinationHttpTest,AgentScopeRemoteFlowTest,PiBrainHttpFlowTest -Dsurefire.failIfNoSpecifiedTests=false
mvn -B -ntp -T1 -pl managed-agents/agent-harness-runtime/java/brain-agentscope,managed-agents/agent-service -am test -Dtest=PiBrainHttpFlowTest,AgentHarnessRuntimeAppContextLoadTest -Dmanaged.pi.http=true -Dsurefire.failIfNoSpecifiedTests=false
make -C managed-agents/agent-harness-runtime/protocol check PYTHON=/tmp/managed-agents-protocol-venv/bin/python
```

Logs: `/tmp/managed-brain-agentscope-model-tests.log`, `/tmp/managed-brain-agentscope-model-wiring.log`, and `/tmp/managed-brain-agentscope-model-protocol.log`. The first full `clean verify` failed in public API tests whose historical fixtures used empty Agent snapshots. Fixtures now contain valid definitions, and a missing-definition rejection test was added. `SessionApiContractTest` has 34 passes / 4 conditional skips; `ManagedSessionTurnsApiTest` has 14 passes.

The second full run still failed because the HTTP fixture in `AgentServiceSessionCreationTest` omitted `agentSnapshot`. After supplying a valid sealed definition, both targeted cases passed. Production validation was not relaxed. Root `spotless:check` passed. No third full run was started under the user's phase-closure request: **full `clean verify` has not passed**. Gateway/Brain were skipped in the second reactor run, and targeted passes do not replace full acceptance. Work is paused, without deployment, commit, or push. Logs: `/tmp/managed-brain-agentscope-model-full-java-final.log`, `/tmp/managed-brain-agentscope-creation-regression.log`, and `/tmp/managed-brain-phase-close-spotless.log`.

```sh
mvn -B -ntp -T1 -pl managed-agents/agent-service -am test -Dtest=AgentServiceSessionCreationTest -Dsurefire.failIfNoSpecifiedTests=false
mvn -B -ntp -T1 spotless:check
```

The phase conclusion, remaining functional work, and boundary validation are recorded separately in [plan section 2.6](/Users/ken/agentcore/agentcore-managed-agents/docs/managed-brain-completion-plan.en.md#26-phase-closure-2026-10-29).

The initial Responses fixture omitted `cache_write_tokens`, which the native SDK rejected. It passes after matching the [current official response contract](https://developers.openai.com/api/reference/resources/responses/methods/retrieve). Compatible endpoints must follow the selected SDK’s response contract; missing usage was not silently converted to zero. Additional logs: `/tmp/managed-brain-agentscope-responses-native.log` and `/tmp/managed-brain-agentscope-model-responses.log`.

## Remaining scope

1. Child frozen definitions still apply later model/GenerateOptions settings. Compile and verify them together with independent bindings; root-Agent results do not establish child alignment.
2. Full field coverage, Control Plane capability admission, Vault binding/revocation, native registered-model pinning, and model-alias/platform-pricing identity remain incomplete. Auxiliary-call budget consistency remains under W14.
3. W19 / W20 still require the actual Control Plane, storage/messaging infrastructure, identical definitions across kernels, public SSE, deployment, and capacity acceptance.

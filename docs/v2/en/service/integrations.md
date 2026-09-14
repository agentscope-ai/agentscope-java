---
title: "SDK and component selection"
---

<Note>
This is preview documentation. The official release is not yet available.
</Note>

Before selecting an SDK, distinguish invoking a capability from connecting a runtime. An application calling an Endpoint needs an HTTP client, not runtime instrumentation.

| Goal | Component | Continue |
| --- | --- | --- |
| Invoke an Agent, Team or Workflow | HTTP client | [Endpoints](/v2/en/service/endpoints) |
| Register a Java application | `io.agentscope:agentscope-extensions-aistio` | [External Agents](/v2/en/service/external-agent) |
| Connect a Python framework through ASDP | `aistio-sdk` | [External Agents](/v2/en/service/external-agent) |
| Connect DeepSeek Harness | `@agentscope/dsh-aistio` plugin | Configure the plugin, HTTP contract and ASDP addresses |
| Connect a local Coding Agent | `agentscope` CLI and Runtime Host | [Hosted Agents](/v2/en/service/hosted-agent) |

## Packages and versions

Service, Java, Python and DSH packages have independent versions. Use `release-manifest.json` to select matching artifacts rather than copying the image version into every package manager.

```bash
python -m pip install "aistio-sdk==$AISTIO_SDK_VERSION"
npm install "@agentscope/dsh-aistio@$DSH_AISTIO_VERSION"
```

Run these in the corresponding application with versions from the manifest. Installing the DSH npm package only supplies plugin files. Add it to your DSH profile and configure control-plane HTTP, ASDP gRPC and a reachable contract address. DSH retains provider login and application lifecycle.

## Verify transport and capabilities

Complete Service uses standalone HTTP; Java offers HTTP registration and contracts. ASDP integrations additionally need an enabled listener. Python automatic registration depends on ASDP, so disabling gRPC does not replace this prerequisite.

Verify catalog identity, then Sessions/history, then supported dispatch, cancellation and reporting. Extend custom frameworks through adapters; changing a framework name alone does not add capabilities. See [External Agents](/v2/en/service/external-agent) for code, credentials and connectivity.

## Integration acceptance checks

Validate registration and business execution separately. Run the checks applicable to the adapter's actual capabilities; not every framework implements all of them.

1. **Directory:** check identity, execution mode, advertised capabilities, and control-plane reachability of the application address.
2. **Conversation:** submit a fixed question, inspect replies, tool events where applicable, and history; reopen the Session to check the context path.
3. **Tasks:** if Issues or Teams are needed, submit a small task with acceptance criteria and inspect dispatch, final output, and Artifacts. Conversation-only adapters do not pass task acceptance on that basis.
4. **Failure:** stop the application in a test environment and inspect unavailability and failure records. After recovery, validate an explicitly new execution instead of reading an old execution's status.
5. **Application calls:** follow the [fulfillment case](/v2/en/service/cases/order-fulfillment). Validate External task capabilities before publishing a Team Endpoint and checking input, SSE, output, and business authorization.

Record Service, SDK, and framework versions plus transport. A successful standalone HTTP test does not establish that a separate ASDP listener is deployed or reachable.

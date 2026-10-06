---
title: "SDK and component selection"
zh_link: /v2/zh/service/integrations
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

## Management and invocation clients

Python ManagementClient uses platform Bearer identity for Applications, Agents, Teams, Workflows, runtime policies, and Endpoint publishing. ServiceClient uses an Endpoint key or platform token for published services. These caller-side clients differ from the runtime SDK that registers workers, receives tasks, and reports execution.

```python
import os
from aistio import ManagementClient, ServiceClient

base = os.environ["BASE_URL"]
management = ManagementClient(
    base, os.environ["TOKEN"], os.environ["TENANT"], os.environ["NAMESPACE"]
)
print(management.agents())

service = ServiceClient(base, api_key=os.environ["ENDPOINT_KEY"])
receipt = service.submit(
    "notes-service", {"request": "Extract owners and open questions from the notes."},
    title="Review notes", idempotency_key="notes-request-001"
)
invocation_id = receipt["invocationId"]
snapshot = service.snapshot(invocation_id)
print(snapshot)
for event in service.stream(invocation_id, after=snapshot["as_of"]):
    print(event)
```

This uses the variables and schema from [Endpoint publishing](/v2/en/service/endpoints). Catch stream errors, retain the last applied cursor, and reconnect; reload a snapshot when local UI state is lost. The client does not automatically approve actions or resubmit failed work.

ManagementClient does not cover every resource API; some Channel, knowledge-file, and account operations use HTTP directly. The TypeScript Invocation client and reducer are in frontend/src/api/serviceInvocations.ts, with runnable examples in aistio/examples/service-api. Consult [API reference](/v2/en/service/api-reference) and resource guides for parameters.

## Packages and versions

Service, Java, Python and DSH packages have independent versions. Use `release-manifest.json` to select matching artifacts rather than copying the image version into every package manager.

```bash
python -m pip install "aistio-sdk==$AISTIO_SDK_VERSION"
npm install "@agentscope/dsh-aistio@$DSH_AISTIO_VERSION"
```

Run these in the corresponding application with versions from the manifest. Installing the DSH npm package only supplies plugin files. Add it to your DSH profile and configure control-plane HTTP, ASDP gRPC and a reachable contract address. DSH retains provider login and application lifecycle.

## Verify transport and capabilities

Complete Service uses standalone HTTP; Java offers HTTP registration and contracts. ASDP integrations additionally need an enabled listener. Python now registers over HTTP and defaults to outbound HTTP execution transport; ASDP is an explicitly selected alternative. See the [API-only executable example](/v2/en/service/service-api#runnable-example-and-clients).

Verify catalog identity, then Sessions/history, then supported dispatch, cancellation and reporting. Extend custom frameworks through adapters; changing a framework name alone does not add capabilities. See [External Agents](/v2/en/service/external-agent) for code, credentials and connectivity.

## Integration acceptance checks

Validate registration and business execution separately. Run the checks applicable to the adapter's actual capabilities; not every framework implements all of them.

1. **Directory:** check identity, execution mode, advertised capabilities, and control-plane reachability of the application address.
2. **Conversation:** submit a fixed question, inspect replies, tool events where applicable, and history; reopen the Session to check the context path.
3. **Tasks:** if Issues or Teams are needed, submit a small task with acceptance criteria and inspect dispatch, final output, and Artifacts. Conversation-only adapters do not pass task acceptance on that basis.
4. **Failure:** stop the application in a test environment and inspect unavailability and failure records. After recovery, validate an explicitly new execution instead of reading an old execution's status.
5. **Application calls:** follow the [document verification case](/v2/en/service/cases/document-verification). Publish a specialist Job Endpoint and verify fixed input, structured results, source evidence, and business authorization.

Record Service, SDK, and framework versions plus transport. A successful standalone HTTP test does not establish that a separate ASDP listener is deployed or reachable.

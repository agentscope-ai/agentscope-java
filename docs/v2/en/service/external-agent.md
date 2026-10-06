---
title: "External Agent: overview and integration"
zh_link: /v2/zh/service/external-agent
---

<Note>
This is preview documentation. The official release is not yet available.
</Note>

External Agents keep your application's process, framework and deployment while joining the catalog, Session diagnostics and collaboration. They are neither Service-started Managed Agents nor necessarily Runtime Host providers.

Start with the [practical guide](/v2/en/service/register-agentscope-agent) for creation or connection. This reference section collects detailed configuration, supported capabilities and execution principles.

## In this chapter

- [Registration and connections](/v2/en/service/external-agent-configuration)
- [Frameworks and custom adapters](/v2/en/service/external-agent-frameworks)
- [Execution and task dispatch](/v2/en/service/external-agent-execution)

## Choose a transport

| Path | Connectivity | Capabilities |
| --- | --- | --- |
| Java HTTP contract | Application reaches registration API; control plane reaches its contract | Registration, contract queries and adapter-supported commands |
| ASDP instrumentation | The same HTTP paths plus a reachable ASDP gRPC listener | Event reporting and adapter-supported ExecutionAttempt dispatch |

Python now supports standard Service over outbound HTTP: use `control_plane_http=base, transport="http"` (the default), without exposing an ASDP gRPC port or inbound worker port. See the [unified Service API executable example](/v2/en/service/service-api#runnable-example-and-clients) for Agent, Team and Workflow workers. The example below uses the default HTTP runtime transport.

Verify three separate levels: catalog visibility, Session functionality and work dispatch. An observation-only adapter does not acquire task execution merely by registering.

## Java HTTP integration

Add the published extension version to your application:

```xml
<dependency>
  <groupId>io.agentscope</groupId>
  <artifactId>agentscope-extensions-aistio</artifactId>
  <version>${agentscope.version}</version>
</dependency>
```

This fragment assumes an existing `agent`. Supply deployment environment variables; the control plane must reach `AGENT_CONTRACT_URL`:

```java
import io.agentscope.extensions.aistio.Aistio;
import io.agentscope.extensions.aistio.AistioConfig;
import io.agentscope.extensions.aistio.SessionBridge;

SessionBridge bridge = Aistio.instrument(agent,
    AistioConfig.builder("report-service")
        .controlPlaneHttp(System.getenv("AISTIO_CONTROL_HTTP"))
        .registrationCredential(System.getenv("AISTIO_REGISTRATION_CREDENTIAL"))
        .tenant(System.getenv("AISTIO_TENANT"))
        .namespace(System.getenv("AISTIO_NAMESPACE"))
        .instanceKey(System.getenv("AISTIO_INSTANCE_KEY"))
        .contractHttpPort(18090)
        .publicBaseUrl(System.getenv("AGENT_CONTRACT_URL"))
        .startHttpRegister(true)
        .startGrpc(false)
        .build());
// Call bridge.close() during application shutdown.
```

Obtain `registrationCredential` through the [registration guide](/v2/en/service/register-agentscope-agent) and supply it as `AISTIO_REGISTRATION_CREDENTIAL`. The current Java bridge skips automatic registration when both the registration credential and bootstrap setting are empty. The preview registration endpoint itself does not authenticate callers; restrict it to a controlled network or gateway. The returned registration credential authenticates subsequent runtime connections and does not protect registration itself. Contract history and commands depend on the adapter. Java event reporting also requires adapter middleware at Agent construction and an ASDP runtime connection.

<span id="python-with-asdp" />

## Python with HTTP runtime transport

Install the SDK version selected for your release:

```bash
python -m pip install "aistio-sdk==$AISTIO_SDK_VERSION"
```

The fragment assumes an existing framework `target`. Adapters include AgentScope, OpenAI Agents, LangChain and ADK; supported methods differ.

```python
import os
import aistio

bridge = aistio.instrument(
    target,
    agent_key="report-service",
    instance_key=os.environ["AISTIO_INSTANCE_KEY"],
    tenant=os.environ["AISTIO_TENANT"],
    namespace=os.environ["AISTIO_NAMESPACE"],
    transport="http",
    control_plane_http=os.environ["AISTIO_CONTROL_HTTP"],
    contract_http_port=18090,
    contract_http_base_url=os.environ["AGENT_CONTRACT_URL"],
    event_journal_dir="/var/lib/report-agent/events",
)
# Call bridge.stop() during application shutdown.
```

Python defaults to outbound HTTP exchange (`POST /api/v1/agent-runtime/exchange`) without a gRPC listener. To use gRPC, set `transport="grpc"` and `control_plane="host:port"`. Give each replica a distinct instance key and retain identity and the event journal across restarts. Automatically selected observation adapters do not execute Issue/Team work. For task execution, explicitly supply the SDK's `AsyncInvokeAdapter`, `AgentScopeRunnerAdapter`, or `ExecutableAdapter` through `adapter=`, or implement your own task entry point. See [Adapter selection](/v2/en/service/external-agent-frameworks).

## Execute Issue and Team work

The adapter needs a real task entry point, such as Python `FrameworkAdapter.handle_agent_task` or Java `AgentTaskStarter`. Isolate each Attempt, read injected context, use task-scoped credentials for comments and Artifacts, and report success, failure or cancellation through the protocol.

A final message is not an Attempt completion report. Never report another execution with a stale generation or credential. Coordinators must explicitly complete or fail their Run node; see [Team collaboration](/v2/en/service/team-collaboration).

## Extend and verify

Implement Python `FrameworkAdapter` and pass it through `adapter=` to add context, messages, commands or task execution. Advertise only implemented capabilities. Java provides `FrameworkAdapter` and `AgentScopeAdapter` extension points.

Verify registration and restart identity, an application-side conversation, history, contract reachability, supported task dispatch, failure and cancellation. Container localhost, one-way networking, incorrect gRPC ports and inaccurate capabilities are common integration problems.

Related: [SDK selection](/v2/en/service/integrations) · [API reference](/v2/en/service/api-reference).

## API entry points and identity

Use a platform account Bearer token for management, the registered instance identity for runtime connections, and an Endpoint credential for published business calls. These credentials serve different purposes.

| Operation | API and key parameters | Response |
| --- | --- | --- |
| Register Agent and replica | `POST /api/v1/agent-registrations` with `agentKey`, `instanceKey`, scope, `routingKey`, and `capabilities` | `agent`, `binding`, `instance`, `registrationCredential` |
| Read Agent | `GET /api/v1/agents/{agentId}` | `agent` |
| Read bindings and instances | `GET /api/v1/agents/{agentId}/bindings`, `/instances` | `items` |
| Read reported inventory | `GET /api/v1/agents/{agentId}/runtime-inventory` | `status`, `items`; `not_reporting` when no report exists |
| Update lifecycle | `PATCH /api/v1/agents/{agentId}` with current `version` and fields such as `status` | Updated `agent` |
| Rotate/revoke runtime registration credential | `POST /api/v1/agent-registrations/{agentId}/credentials/rotate`; `DELETE /api/v1/agent-registrations/{agentId}/credentials/{credentialId}` | New credential on rotation; 204 on revocation |

See [registration and connection settings](/v2/en/service/external-agent-configuration) for fields and SDK options. After implementing task execution, assign work through the [Issue API](/v2/en/service/issues), or publish an [Endpoint](/v2/en/service/endpoints) for results and SSE through the unified [Agent API](/v2/en/service/service-api).

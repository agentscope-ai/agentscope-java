---
title: "External Agents: integrate an independent application"
---

[简体中文](/v2/zh/service/external-agent)

External Agents keep your application's process, framework and deployment while joining the catalog, Session diagnostics and collaboration. They are neither Service-started Managed Agents nor necessarily Runtime Host providers.

## Choose a transport

| Path | Connectivity | Capabilities |
| --- | --- | --- |
| Java HTTP contract | Application reaches registration API; control plane reaches its contract | Registration, contract queries and adapter-supported commands |
| ASDP instrumentation | The same HTTP paths plus a reachable ASDP gRPC listener | Event reporting and adapter-supported ExecutionAttempt dispatch |

The standard Service Compose/Helm deployment runs standalone HTTP without ASDP. Python `instrument()` couples automatic registration to its ASDP connection; `start_grpc=False` is not a complete HTTP registration path. Ask the administrator for an ASDP-enabled Kubernetes-native Aistio deployment and its real gRPC address when using this integration.

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
        .internalToken(System.getenv("AISTIO_BOOTSTRAP_TOKEN"))
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

Use an administrator-provided trusted workload/bootstrap credential for first registration and a registration credential for an existing identity where appropriate. Do not distribute internal component tokens to browsers or ordinary callers. Available contract history and commands depend on the adapter. For event streaming, install adapter middleware while building the Agent and configure ASDP.

## Python with ASDP

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
    control_plane=os.environ["AISTIO_CONTROL_GRPC"],
    control_plane_http=os.environ["AISTIO_CONTROL_HTTP"],
    internal_token=os.environ["AISTIO_BOOTSTRAP_TOKEN"],
    contract_http_port=18090,
    contract_http_base_url=os.environ["AGENT_CONTRACT_URL"],
    event_journal_dir="/var/lib/report-agent/events",
)
# Call bridge.stop() during application shutdown.
```

`AISTIO_CONTROL_GRPC` is `host:port`, not an HTTP Gateway URL. Use a distinct instance key per replica and stable identity across its restarts. Persist the event journal for recovery.

## Execute Issue and Team work

The adapter needs a real task entry point, such as Python `FrameworkAdapter.handle_agent_task` or Java `AgentTaskStarter`. Isolate each Attempt, read injected context, use task-scoped credentials for comments and Artifacts, and report success, failure or cancellation through the protocol.

A final message is not an Attempt completion report. Never report another execution with a stale generation or credential. Coordinators must explicitly complete or fail their Run node; see [Team collaboration](/v2/en/service/team-collaboration).

## Extend and verify

Implement Python `FrameworkAdapter` and pass it through `adapter=` to add context, messages, commands or task execution. Advertise only implemented capabilities. Java provides `FrameworkAdapter` and `AgentScopeAdapter` extension points.

Verify registration and restart identity, an application-side conversation, history, contract reachability, supported task dispatch, failure and cancellation. Container localhost, one-way networking, incorrect gRPC ports and inaccurate capabilities are common integration problems.

Related: [SDK selection](/v2/en/service/integrations) · [API reference](/v2/en/service/api-reference).

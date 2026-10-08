---
title: "External SDKs and runtime protocol"
zh_link: /v2/zh/service/external-agent
---

<Note>
This is preview documentation. The official release is not yet available.
</Note>

External Agents keep your application's process, framework and deployment while joining the catalog, Session diagnostics and collaboration. They are neither Service-started Managed Agents nor necessarily Runtime Host providers.

Start with the [registration guide](/v2/en/service/register-agentscope-agent). Use this reference for SDK integration, settings, and execution protocols.


<span id="in-this-chapter"></span>
<span id="execute-issue-and-team-work"></span>
<span id="extend-and-verify"></span>
<span id="api-entry-points-and-identity"></span>

## Choose a transport

| Path | Connectivity | Capabilities |
| --- | --- | --- |
| Java HTTP contract | Application reaches registration API; control plane reaches its contract | Registration, contract queries and adapter-supported commands |
| ASDP instrumentation | The same HTTP paths plus a reachable ASDP gRPC listener | Event reporting and adapter-supported ExecutionAttempt dispatch |

Python now supports standard Service over outbound HTTP: use `control_plane_http=base, transport="http"` (the default), without exposing an ASDP gRPC port or inbound worker port. See the [unified Service API executable example](/v2/en/service/service-api#sdk-and-runnable-examples) for Agent, Team and Workflow workers. The example below uses the default HTTP runtime transport.

Verify three separate levels: catalog visibility, Session functionality and work dispatch. An observation-only adapter does not acquire task execution merely by registering.

## Java HTTP integration

Add the published extension version to your application:

```xml
<dependency>
  <groupId>io.agentscope</groupId>
  <artifactId>agentscope-extensions-controlplane</artifactId>
  <version>${agentscope.version}</version>
</dependency>
```

This fragment assumes an existing `agent`. Supply deployment environment variables; the control plane must reach `AGENT_CONTRACT_URL`:

```java
import io.agentscope.extensions.controlplane.ControlPlane;
import io.agentscope.extensions.controlplane.ControlPlaneConfig;
import io.agentscope.extensions.controlplane.SessionBridge;

SessionBridge bridge = ControlPlane.instrument(agent,
    ControlPlaneConfig.builder("report-service")
        .controlPlaneHttp(System.getenv("CONTROL_PLANE_HTTP"))
        .registrationCredential(System.getenv("CONTROL_PLANE_REGISTRATION_CREDENTIAL"))
        .tenant(System.getenv("CONTROL_PLANE_TENANT"))
        .namespace(System.getenv("CONTROL_PLANE_NAMESPACE"))
        .instanceKey(System.getenv("CONTROL_PLANE_INSTANCE_KEY"))
        .contractHttpPort(18090)
        .publicBaseUrl(System.getenv("AGENT_CONTRACT_URL"))
        .startHttpRegister(true)
        .startGrpc(false)
        .build());
// Call bridge.close() during application shutdown.
```

Obtain `registrationCredential` through the [registration guide](/v2/en/service/register-agentscope-agent) and supply it as `CONTROL_PLANE_REGISTRATION_CREDENTIAL`. The current Java bridge skips automatic registration when both the registration credential and bootstrap setting are empty. The preview registration endpoint itself does not authenticate callers; restrict it to a controlled network or gateway. The returned registration credential authenticates subsequent runtime connections and does not protect registration itself. Contract history and commands depend on the adapter. Java event reporting also requires adapter middleware at Agent construction and an ASDP runtime connection.

<span id="python-with-asdp" />

## Python with HTTP runtime transport

Install the SDK version selected for your release:

```bash
python -m pip install "agentscope-service-sdk==$CONTROL_PLANE_SDK_VERSION"
```

The fragment assumes an existing framework `target`. Adapters include AgentScope, OpenAI Agents, LangChain and ADK; supported methods differ.

```python
import os
import agentscope_service

bridge = agentscope_service.instrument(
    target,
    agent_key="report-service",
    instance_key=os.environ["CONTROL_PLANE_INSTANCE_KEY"],
    tenant=os.environ["CONTROL_PLANE_TENANT"],
    namespace=os.environ["CONTROL_PLANE_NAMESPACE"],
    transport="http",
    control_plane_http=os.environ["CONTROL_PLANE_HTTP"],
    contract_http_port=18090,
    contract_http_base_url=os.environ["AGENT_CONTRACT_URL"],
    event_journal_dir="/var/lib/report-agent/events",
)
# Call bridge.stop() during application shutdown.
```

Python defaults to outbound HTTP exchange (`POST /api/v1/agent-runtime/exchange`) without a gRPC listener. To use gRPC, set `transport="grpc"` and `control_plane="host:port"`. Give each replica a distinct instance key and retain identity and the event journal across restarts. Automatically selected observation adapters do not execute Issue/Team work. For task execution, explicitly supply the SDK's `AsyncInvokeAdapter`, `AgentScopeRunnerAdapter`, or `ExecutableAdapter` through `adapter=`, or implement your own task entry point. See [Adapter selection](/v2/en/service/external-agent#external-agent-frameworks).

<span id="external-agent-configuration"></span>
<span id="network-and-execution-requirements"></span>

## Registration API

`POST /api/v1/agent-registrations` accepts JSON to create or register an application replica under a logical Agent.

| Request field | Meaning |
| --- | --- |
| `agentKey`, `instanceKey` | Required logical application name and stable replica name |
| `tenant`, `namespace` | Registration scope; both default to `default` |
| `displayName`, `description` | Optional display name and description |
| `framework`, `frameworkVersion`, `sdkVersion` | Framework and SDK metadata |
| `routingKey` | Application HTTP contract address reachable from the control plane |
| `capacity` | Execution capacity advertised by this instance |
| `capabilities` | String array such as `context-query` or `agent-task`; advertise implemented behavior only |
| `labels` | Instance label object used for replica selection and management |
| `credentialTtlSeconds` | Positive values set the new credential lifetime in seconds; otherwise no explicit expiry is set |
| `ownerType`, `ownerRef` | Optional ownership metadata; not an authenticated caller identity or authorization |

A successful request returns 201 with `agent`, `binding`, `instance`, `credential`, and `registrationCredential`. Business resources reference `agent.id`. Runtime connections require matching Agent/Binding/Instance identity and `instance.generation`. `credential` contains metadata; `registrationCredential` is the plaintext value and belongs in protected server configuration.

The current registration endpoint does not authenticate callers or tokens in request headers. Restrict network access to registration. Runtime credential validation does not protect this initial endpoint; ownership, scope, and capability fields are not proof of identity.

## Identity queries and credential maintenance

Management calls below require a platform account Bearer token authorized for the target namespace, not an application key.

| Method and path | Parameters | Response |
| --- | --- | --- |
| `GET /api/v1/agents` | `tenant`, `namespace`; optional `status`, `includeArchived`, `limit` | `items` |
| `GET /api/v1/agents/{agentId}` | Agent UUID | `agent` |
| `GET /api/v1/agents/{agentId}/bindings` | Optional `includeDisabled=true` | `items` |
| `GET /api/v1/agents/{agentId}/instances` | Agent UUID | `items`, including capabilities and generation |
| `GET /api/v1/agents/{agentId}/runtime-inventory` | Agent UUID | `status`, `items`, including report time, health, Subagents, and Workspaces |
| `PATCH /api/v1/agents/{agentId}` | Current `version`; optional `displayName`, `description`, `status`, `labels`, `capabilities`, and other update fields | `agent`; stale versions conflict |
| `POST /api/v1/agent-registrations/{agentId}/credentials/rotate` | Optional `ttlSeconds` | 201; `credential`, new `registrationCredential` |
| `DELETE /api/v1/agent-registrations/{agentId}/credentials/{credentialId}` | Agent and credential UUIDs | 204 |

Agent status `disabled` prevents subsequent scheduling; `archived` archives the logical resource. Cancel active work through the [task API](/v2/en/service/issues). Changing catalog status alone is not proof that a running process has stopped.

## SDK settings

| Java `ControlPlaneConfig.Builder` | Python `instrument()` | Meaning |
| --- | --- | --- |
| `builder(agentKey)` | `agent_key` | Logical Agent name shared by replicas |
| `tenant` / `namespace` | `tenant` / `namespace` | Scope; both default to `default` |
| `instanceKey` | `instance_key` | Replica name; derived from hostname by default, set explicitly for multi-replica deployments |
| `controlPlaneHttp` | `control_plane_http` | Service HTTP address including scheme |
| `controlPlane` | `control_plane` | `host:port` when using gRPC |
| `publicBaseUrl` | `contract_http_base_url` | Reachable application contract URL |
| `contractHttpPort` | `contract_http_port` | Java defaults to 18090, Python to 8080; 0 selects an ephemeral port |
| `registrationCredential` | `registration_credential` | Credential returned by registration for subsequent runtime connections |
| `internalToken` | `internal_token` | Optional deployment-managed runtime/bootstrap setting; the current registration HTTP API does not validate it |
| `registeredIdentity(agentId, bindingId, generation)` | `agent_id` / `binding_id` / `generation` | Previously registered identity; retain a consistent set |
| `eventJournalDir` | `event_journal_dir` | Durable event outbox directory; not a replacement for application Session storage |
| `startHttp` | `start_http` | Start the application contract HTTP server; defaults to true |
| `startHttpRegister` | Automatic registration | Java follows whether `controlPlaneHttp` is configured when unset; Python registers when identity is missing and an HTTP address is configured |
| `startGrpc` | `start_grpc` | Java defaults to false and enables gRPC; Python defaults to true and enables the selected runtime transport |
| No equivalent | `transport` | Python defaults to `http`; `grpc` is optional |
| `enableEvents` | `enable_events` | Java follows `startGrpc` when unset; Python defaults to true; actual events require framework hooks |
| `sessionAffinity` | `session_affinity` | Session routing affinity hint |

The current Java bridge skips registration when both `registrationCredential` and `internalToken` are absent, even when HTTP registration is enabled. You can obtain a credential through the registration API before starting the bridge. This SDK startup condition does not mean the server authenticates first registration.

Python's `start_grpc` controls the selected runtime channel. Setting it to false disables task dispatch and event reporting even with `transport="http"`. Keep its default true when receiving platform work.

<span id="external-agent-frameworks"></span>
<span id="additional-task-acceptance-checks"></span>

## Java

`agentscope-extensions-controlplane` adapts AgentScope Java `Agent` objects. Its contract includes context, messages, session commands, abort and task queries. The underlying Agent still determines whether a specific operation can execute.

Optional extensions include `SessionHistorySource` for history, `AgentRuntimeSource` for Workspace/Subagent inventory and runtime details, and `AgentTaskStarter` for dispatched work. `HarnessAgentTaskStarter` can consume platform definitions through a Workspace factory. The adapter advertises `agent-task` only when a task starter is configured; creating a bridge alone does not create a work executor.

## Built-in Python adapters

| Framework | Typical target/integration | Implemented focus |
| --- | --- | --- |
| AgentScope | Agent instance and hooks | Context, messages, commands, abort and task queries |
| OpenAI Agents SDK | Session or object containing a Session | Session items, context and messages; commands depend on backend methods |
| LangChain / LangGraph | Framework objects and callbacks/state | Model/tool events, context and messages |
| Google ADK | Framework objects such as SessionService | Session events, context, messages and supported commands |
| Claude Agent SDK | Client / Session store objects | Session storage, context, messages and supported commands |
| OpenClaw | Gateway RPC client/connection | Context, messages, Subagent and Workspace inventory |

Automatic selection calls `can_handle(target)` and chooses the first match in registration order. Supply `adapter=` explicitly for unsupported wrappers or ambiguous matching.

The automatically selected adapters above provide observation and their implemented session capabilities; they do not execute dispatched Issue/Team tasks. For task execution, explicitly supply one of the SDK's execution adapters through `adapter=`:

| Execution adapter | Target and parameters |
| --- | --- |
| `AsyncInvokeAdapter` | Framework objects exposing async `ainvoke(input)`; supply `control_plane_http`, `factory`, `input_builder`, and optional `result_mapper` |
| `AgentScopeRunnerAdapter` | Async callable AgentScope agents with native observation hooks; accepts the same parameters |
| `ExecutableAdapter` | Custom async `runner(TaskContext)`; supply `control_plane_http`, `runner`, and optional `framework` |

The `factory` creates a fresh Agent or framework instance per task; `input_builder` maps platform task context to framework input. Custom runners should also isolate session state per task. Results must be JSON serializable; use `result_mapper` for custom types. See [Runnable example and clients](/v2/en/service/service-api#sdk-and-runnable-examples).

External and Hosted integrations are different even when names overlap: Claude Agent SDK integration is separate from running Claude Code CLI through Runtime Host.

## Implementing a custom adapter

| Extension point | Responsibility |
| --- | --- |
| `can_handle` | Recognize targets without starting work |
| `attach` / `detach` | Install and remove framework hooks or observers |
| `extract_context` | Extract session context |
| `list_messages` | Return available message history |
| `handle_command` / `abort` | Execute real commands/cancellation and report actual failures |
| `handle_agent_task` | Create isolated execution for dispatched work, including completion, failure and cancellation |

Subclass `FrameworkAdapter` and pass it to `agentscope_service.instrument(..., adapter=your_adapter)`, or register it with `register_adapter()`. The base class derives capabilities from overridden methods; empty implementations must not be used to claim support.

## Inspect adapter capabilities through the API

Request `GET /api/v1/agents/{agentId}/instances` with a platform account Bearer token and read each item's `capabilities`. `context-query` and `message-query` provide queries, `session-abort` provides cancellation, and `agent-task` provides platform task execution. Instance capabilities depend on adapter configuration, not just the `framework` name.

`GET /api/v1/agents/{agentId}/runtime-inventory` returns reported Workspace and subagent information. `not_reporting` means telemetry is unavailable, not that no work has occurred. Applications use the Session API and inspect Session and Turn capabilities to discover interactions supported by the adapter.

See [connection settings](/v2/en/service/external-agent#external-agent-configuration) for registration fields and [task dispatch](/v2/en/service/external-agent#external-agent-execution) for execution and reporting APIs.

<span id="external-agent-execution"></span>
<span id="verify-three-layers-separately"></span>
<span id="joining-a-team"></span>

## Query contract and runtime transport

HTTP registration establishes identity. The application contract exposes queryable capabilities and session operations. Control-plane callback reachability is separate from outbound registration; verify both directions.

Python defaults to HTTP exchange for execution and event reporting, with ASDP gRPC as an option. Java HTTP registration and query contracts operate independently; receiving platform tasks requires the current Java SDK’s gRPC runtime channel. See [connection settings](/v2/en/service/external-agent#external-agent-configuration) for parameters and network direction.

An event journal supports connection recovery but does not store all business state or replace the framework's Session store. The application retains responsibility for its data, tool connections and idempotent task handling.

## Dispatch lifecycle

The control plane selects an instance by binding capabilities and creates an Attempt. The application receives its identity, generation and task-scoped context. `AgentTaskStarter` or `handle_agent_task` creates isolated execution, reports progress/comments/Artifacts, maintains the required execution lease/state and reports success, failure or cancellation.

Associate results with that same Attempt. Respect stale-identity rejection rather than relabeling old results as another task's success. Propagate cancellation into the framework and inspect terminal state; an accepted HTTP request does not prove execution has stopped.

## Observe and control an execution through the API

Business applications create a Session targeting this Agent and submit Turns. Service dispatches through its runtime binding and records execution; callers do not create ExecutionAttempts. Work requiring manual assignment and acceptance can also start from the Issue interface.

| Caller | Operation | API and parameters |
| --- | --- | --- |
| Work manager | Read task and physical attempts | `GET /api/v1/agent-tasks/{taskId}`; `GET /api/v1/execution-attempts?tenant=...&namespace=...&taskId=...` |
| Work manager | Request cancellation or retry | `POST /api/v1/agent-tasks/{taskId}/cancel`, `/retry`; see [Issue API](/v2/en/service/issues) for version fields |
| Task executor | Context, start, progress, response, completion | `/api/v1/agent-tasks/{taskId}/context`, `/start`, `/progress`, `/respond`, `/complete`, `/fail`, using the assigned task token |
| Business application | Snapshot and resumable events | `GET /api/v1/agent-sessions/{sessionId}/turns/{turnId}/snapshot` and `/events/stream`, using invocation credentials |

Do not substitute a management token for the runtime-injected task token. After cancellation is accepted, read the task and Attempt to confirm the terminal state. External Agent Sessions expose cancellation when the selected instance advertises `session-abort`; query capabilities for other interactions.

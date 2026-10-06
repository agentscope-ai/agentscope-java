---
title: "External registration and connection settings"
zh_link: /v2/zh/service/external-agent-configuration
---

<Note>
This is preview documentation. The official release is not yet available.
</Note>

This reference covers registration, instance identity, and SDK connection settings. Start with [register an existing Agent](/v2/en/service/register-agentscope-agent), and see [integration examples](/v2/en/service/external-agent) for Java and Python.

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

Management calls below require a platform account Bearer token authorized for the target namespace, not an Endpoint key.

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

| Java `AistioConfig.Builder` | Python `instrument()` | Meaning |
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

## Network and execution requirements

Registration goes from application to Service; contract queries go from control plane to application. Python HTTP runtime transport uses outbound `POST /api/v1/agent-runtime/exchange`. gRPC requires an ASDP listener in the deployment. Setting `startGrpc(true)` does not turn a standalone HTTP address into a gRPC endpoint.

Java `AgentTaskStarter` or Python `handle_agent_task` provides task execution. Catalog registration and query endpoints alone do not implement `agent-task`; outcomes, failures, and cancellation need working handlers. External applications continue to own their models, tool connections, and business credentials.

Preserve each replica's identity and event journal. Do not increment generation to bypass stale-execution validation. Verify catalog visibility, runtime connection, Session queries, and an actual task separately. Business calls and SSE use the [unified Agent API](/v2/en/service/service-api); its cursors are separate from native application event logs.

---
title: "AgentScope framework: register an application"
---

<Note>
This is preview documentation. The official release is not yet available.
</Note>

Register a running AgentScope application as an External Agent while retaining its deployment. Establish catalog and Session access first, then verify the task capabilities you need.

## Prepare registration details

Obtain the registration scope, a reachable Service HTTP address and initial credentials from an administrator. Give each replica a stable instance key and provide an application HTTP contract URL reachable from the control plane.

| Detail | Check |
| --- | --- |
| tenant / namespace | Matches the intended Agent catalog scope |
| Credential | Trusted workload/bootstrap or subsequent registration credential, distinct from an Endpoint API key |
| Instance key | Different across replicas and stable across ordinary restarts |
| Contract URL | Reachable from the control plane; container localhost usually refers only to that container |

## Register a Java application

1. Add `agentscope-extensions-aistio` matching the application's SDK version.
2. Call `Aistio.instrument(agent, config)` after creating the Agent and retain the returned `SessionBridge`.
3. For standard Service deployments, set `controlPlaneHttp`, credentials, scope and `publicBaseUrl`. Enable `startHttpRegister(true)` and the HTTP contract, and set `startGrpc(false)`.
4. Start the application and inspect the registered Agent, instance and contract URL in **DESIGN → Agents**.
5. Run a conversation in the application and verify the Session information exposed by the adapter. Close the bridge when the application shuts down.

Copy the Maven and Java fragments from the [External Agent reference](/v2/en/service/external-agent).

## Receive platform tasks

To receive Issues or join Teams, configure a real task entry such as `AgentTaskStarter` and verify successful, failed and cancelled execution. Catalog registration and Session visibility alone do not provide task execution.

Python automatic registration is tied to ASDP initialization. Prepare an ASDP-capable deployment before calling `aistio.instrument()`. The [framework and adapter guide](/v2/en/service/external-agent-frameworks) describes implemented capabilities.

Once connected, use [Endpoints](/v2/en/service/endpoints) or [console Issues](/v2/en/service/issues) according to available capabilities. See the [External Agent reference](/v2/en/service/external-agent) for configuration and lifecycle details.

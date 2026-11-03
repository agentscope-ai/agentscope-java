---
title: "Register an External Agent"
zh_link: /v2/zh/service/register-agentscope-agent
description: Register an existing application, connect its runtime, and call it through Sessions or include it in orchestration
---

<Note>
This is preview documentation. The release is not yet generally available.
</Note>

Register an application you already operate as an **External Agent**. You continue to deploy and run the application; Service gives it a stable `agentId` that can join a Team, receive tasks, or become a target of the [Session API](/v2/en/service/service-api).

Registration records the Agent and its instances. A runtime integration must also receive requests, execute work, and report outcomes. Sending a registration request alone does not make an arbitrary HTTP application runnable by the platform.

External Agents can receive direct Session calls or contribute existing business capabilities to a Managed team; a Team is optional. Complete [platform setup](/v2/en/service/quickstart), then follow this guide to register the application and verify runtime integration. You remain responsible for deploying and operating the application process. Consult [External SDKs and runtime protocol](/v2/en/service/external-agent) when implementing or checking the adapter. Once integration works, follow [Create and run a Team](/v2/en/service/create-team) to include it in collaboration.

## Register the application and instance

Prepare the Service address, target `tenant` and `namespace`, application `agentKey`, and replica `instanceKey`. Replicas share the same `agentKey` and use different instance keys. Keep a replica's key stable across restarts.

The following request demonstrates the registration protocol. It registers identity without advertising execution capabilities. An integrated SDK should report the capabilities its adapter actually implements.

```bash
export SERVICE_URL="http://localhost:8081"

curl -sS "$SERVICE_URL/api/v1/agent-registrations" \
  -H 'Content-Type: application/json' \
  -d '{
    "tenant": "default",
    "namespace": "default",
    "agentKey": "report-service",
    "displayName": "Report assistant",
    "instanceKey": "replica-1",
    "framework": "agentscope-java",
    "routingKey": "http://report-agent:18090",
    "capacity": 1,
    "capabilities": []
  }' > registration.json
```

`routingKey` is the application's contract address reachable from the control plane. A container's `localhost` usually does not point to another container.

The response contains `agent`, `binding`, `instance`, and `registrationCredential`. Use `agent.id` when creating Teams or Sessions. The runtime connection also needs `binding.id`, `instance.id`, `instance.generation`, and the registration credential. Keep the credential and the complete response out of browser code.

<Note>
The current preview registration endpoint does not authenticate callers. Supplying a Bearer token does not add an identity check to this request. Expose registration within a controlled network or gateway. The returned registration credential is used by subsequent runtime connections; it does not mean registration itself is access controlled.
</Note>

## Connect the runtime

In an application integration, the SDK normally performs registration, heartbeat, and runtime communication. You do not need to reproduce the curl request on every application startup.

Java applications use `agentscope-extensions-controlplane` and obtain a `SessionBridge` from `ControlPlane.instrument(agent, config)`. Configure the Service HTTP address, scope, stable instance key, and reachable application contract URL. See the [External Agent reference](/v2/en/service/external-agent) for the dependency and setup snippet. HTTP registration and the contract provide discovery, queries, and supported adapter commands. Receiving platform AgentTasks also requires an `AgentTaskStarter` and the ASDP runtime channel supported by the current Java SDK. Close the bridge when the application exits.

Python's `agentscope_service.instrument()` supports HTTP runtime transport with `transport="http"` and `control_plane_http`, as well as an explicit gRPC option. Task execution is implemented by the framework adapter's `handle_agent_task`. Check [framework and adapter capabilities](/v2/en/service/external-agent#external-agent-frameworks) before relying on particular commands, events, or task behavior.

`capabilities` declares working behavior; it does not implement it. For example, `agent-task` means the application accepts platform tasks. The Java adapter declares it when a task starter is configured; Python checks the adapter implementation and runtime transport configuration. `session-abort` likewise requires an implementation that can interrupt execution.

## Verify registration through the API

Here, `TOKEN` is a platform account access token authorized for the target namespace. It is different from the registration credential.

```bash
AGENT_ID=$(jq -r '.agent.id' registration.json)

curl -sS "$SERVICE_URL/api/v1/agents/$AGENT_ID" \
  -H "Authorization: Bearer $TOKEN"

curl -sS "$SERVICE_URL/api/v1/agents/$AGENT_ID/instances" \
  -H "Authorization: Bearer $TOKEN"

curl -sS "$SERVICE_URL/api/v1/agents/$AGENT_ID/runtime-inventory" \
  -H "Authorization: Bearer $TOKEN"
```

The Agent record confirms the logical identity. Instance records describe actual replicas and their capabilities. `runtime-inventory` contains runtime reports and returns `status: "not_reporting"` when none are available. Registration success alone does not confirm that an instance can execute work.

## Verify a task, then publish a service

Assign a small task to the `agentId` through the [Issue API](/v2/en/service/issues). Verify start, progress, result, and failure reporting. Give each execution an isolated Agent instance and use task-scoped credentials for comments, artifacts, and completion. A final assistant message alone does not complete a platform task.

You can then add the Agent to a [Team](/v2/en/service/create-team) or call it through the [Session API](/v2/en/service/service-api). Business applications use the unified [Agent API](/v2/en/service/service-api) for results and SSE events. Query capabilities before depending on interactions that vary by runtime.

For the visual workflow, see [Console: Agent management](/v2/en/service/console/index#console-agents).

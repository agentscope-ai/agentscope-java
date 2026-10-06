---
title: "Hosted: connect and create an Agent"
zh_link: /v2/zh/service/connect-hosted-agent
description: Connect a Runtime Host, select a runtime through the API, create a Hosted Agent, and assign work
---

<Note>
This is preview documentation. The release is not yet generally available.
</Note>

A Hosted Agent makes an existing Coding Agent on your computer or server available to the platform. Runtime Host starts provider processes, prepares working directories, and reports results. Service manages and invokes the Agent through the same Agent, Issue, Team, and Endpoint APIs.

Connect a host once. You can then create multiple Hosted Agents, define their responsibilities, and assign work through the API without installing a separate Host for each Agent.

## Connect an execution host

Install and authenticate the provider on the target machine, and verify that it can complete a request. Install the CLI using the [Runtime Host guide](/v2/en/service/runtime-host), then run:

```bash
agentscope connect https://agentscope.example.com
agentscope runtime status
agentscope runtime probe
```

The CLI handles identity exchange, local configuration, and the daemon. For unattended servers, an authorized platform account can call `POST /api/v1/runtime-host-enrollment-tokens` with `tenant` and `namespace` to obtain a short-lived `enrollmentToken`. Supply it as `AGENTSCOPE_ENROLLMENT_TOKEN` on the target host before connecting. Host enrollment and execution also have APIs; business applications do not need to reimplement the daemon.

## Find an available runtime

The examples use a platform account token, `TOKEN`, authorized to manage the target namespace. Replace the Service address and scope with your deployment values:

```bash
export SERVICE_URL="http://localhost:8081"

curl -sS "$SERVICE_URL/api/v1/agents/runtime-options?tenant=default&namespace=default" \
  -H "Authorization: Bearer $TOKEN"
```

The response's `runtimes` array contains available options with `provider`, `runtimeProfileId`, `runtimePoolId`, `hostCount`, and advertised provider capabilities. Select an appropriate option and retain both IDs. The accompanying `profiles` and `pools` describe how to launch a provider and which hosts may run it.

If `runtimes` is empty, check whether the Host is online and the provider was detected. You can inspect hosts directly:

```bash
curl -sS "$SERVICE_URL/api/v1/runtime-hosts?tenant=default&namespace=default" \
  -H "Authorization: Bearer $TOKEN"
```

See the [provider reference](/v2/en/service/hosted-agent-providers) for differences in Workspace, tool, and recovery support.

## Create the Hosted Agent

Use the common `POST /api/v1/agents` operation with a `hosted-runtime` binding. The portable `definition` describes the Agent's name and instructions; the profile and pool determine how and where it runs.

Replace both ID placeholders with UUIDs returned by the previous request:

```bash
curl -sS "$SERVICE_URL/api/v1/agents" \
  -H "Authorization: Bearer $TOKEN" \
  -H 'Content-Type: application/json' \
  -d '{
    "tenant": "default",
    "namespace": "default",
    "agentKey": "code-reviewer",
    "displayName": "Code reviewer",
    "binding": {
      "kind": "hosted-runtime",
      "configuration": {
        "runtimeProfileId": "<runtime-profile-id>",
        "runtimePoolId": "<runtime-pool-id>"
      }
    },
    "definition": {
      "name": "Code reviewer",
      "system": "Review the supplied code, explain evidence and recommendations, and do not modify files without instruction."
    }
  }' > hosted-agent.json
```

The response contains `agent`, `binding`, `policy`, and `definition`. Use `agent.id` in later requests. This example leaves model selection to the provider's defaults. Support for other definition fields depends on the provider's advertised capabilities.

```bash
AGENT_ID=$(jq -r '.agent.id' hosted-agent.json)

curl -sS "$SERVICE_URL/api/v1/agents/$AGENT_ID" \
  -H "Authorization: Bearer $TOKEN"

curl -sS "$SERVICE_URL/api/v1/agents/$AGENT_ID/bindings" \
  -H "Authorization: Bearer $TOKEN"
```

Use `PATCH /api/v1/agents/{agentId}/definition` to update instructions. Provider execution options are available through `GET/PATCH /api/v1/agents/{agentId}/hosted-settings`. Read the current configuration and version before updating it; see the [Hosted Agent reference](/v2/en/service/hosted-agent) for the fields.

## Assign work and read the result

Create a small read-only task through the [Issue API](/v2/en/service/issues), setting `assigneeType: "agent"` and `assigneeRef` to the Agent ID. For example, include README text in the description and ask for three improvements. The platform creates an AgentTask and dispatches it asynchronously. Business code does not need to call the Host's claim protocol.

Use Issue, task, and ExecutionAttempt queries to follow progress and read result comments and artifacts. The Host prepares the working directory; a repository open on that machine does not automatically become task input. Explicitly associate a supported Workspace or supply the necessary materials.

After validating one task, organize Agents through the [Team API](/v2/en/service/create-team), or publish an Agent, Team, or Workflow as an [Endpoint](/v2/en/service/endpoints). Applications use the unified [Agent API](/v2/en/service/service-api) for invocation and SSE. Hosted conversations currently support cancellation, but do not offer all Managed input, approval recovery, or checkpoint operations. Check the Endpoint and invocation capabilities before using them.

For the visual workflow, see [Console: Agent management](/v2/en/service/console/agents). For publishing a Coding Agent as a business capability, see the [incident repair service](/v2/en/service/cases/incident-to-pr).

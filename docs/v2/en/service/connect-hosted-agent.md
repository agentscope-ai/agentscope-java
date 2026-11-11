---
title: "Connect a Hosted Agent"
zh_link: /v2/zh/service/connect-hosted-agent
description: Connect a Runtime Host, select a runtime through the API, create a Hosted Agent, and assign work
---

<Note>
This guide uses the `2.1.0-BETA1` prerelease.
</Note>

A Hosted Agent connects a Coding Agent on your computer or server. Runtime Host starts the provider, prepares its working directory, and reports results. Applications call the Agent through the Session API or use it as an executor in a Team or Workflow.

Connect a host once. You can then create multiple Hosted Agents, define their responsibilities, and assign work through the API without installing a separate Host for each Agent.

Hosted Agents work independently or join a team coordinated by a Managed Lead. Runtime Host runs Coding Agent providers; it differs from a Managed self_hosted tool Worker and from deploying the whole Service. Prepare the [platform](/v2/en/service/quickstart), then see [self-hosted architecture](/v2/en/service/kubernetes#self-hosting) for boundaries and [orchestration](/v2/en/service/orchestration) for collaboration.

## Connect an execution host

Install and authenticate the provider on the target machine, and verify that it can complete a request. Use `go install` with Go 1.26 or newer to install both CLI commands at `v2.1.0-BETA1`, following the [Runtime Host guide](/v2/en/service/runtime-host) to configure PATH. Then run:

```bash
as connect https://agentscope.example.com
as runtime status
as runtime probe
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

See the [provider reference](/v2/en/service/hosted-agent-configuration#hosted-agent-providers) for differences in Workspace, tool, and recovery support.

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

Use `PATCH /api/v1/agents/{agentId}/definition` to update instructions. Provider execution options are available through `GET/PATCH /api/v1/agents/{agentId}/hosted-settings`. Read the current configuration and version before updating it; see the [Hosted Agent reference](/v2/en/service/connect-hosted-agent#hosted-agent) for the fields.

## Assign work and read the result

Create a small read-only task through the [Issue API](/v2/en/service/issues), setting `assigneeType: "agent"` and `assigneeRef` to the Agent ID. For example, include README text in the description and ask for three improvements. The platform creates an AgentTask and dispatches it asynchronously. Business code does not need to call the Host's claim protocol.

Use Issue, task, and ExecutionAttempt queries to follow progress and read result comments and artifacts. The Host prepares the working directory; a repository open on that machine does not automatically become task input. Explicitly associate a supported Workspace or supply the necessary materials.

After validating one task, add the Agent to a [Team](/v2/en/service/create-team) or create a Session targeting it directly. Hosted interaction support depends on the provider and adapter; do not assume every Managed input, approval, or checkpoint feature is available. Read Session and Turn capabilities. See the [API guide](/v2/en/service/service-api).

For the visual workflow, see [Console: Agent management](/v2/en/service/console/index#console-agents). For publishing a Coding Agent as a business capability, see the [incident repair service](/v2/en/service/cases/incident-to-pr).

<span id="hosted-agent"></span>
<span id="in-this-chapter"></span>
<span id="prepare-the-machine"></span>
<span id="create-the-agent"></span>
<span id="deliver-a-small-task"></span>
<span id="extend-capabilities"></span>
<span id="interrupt-and-recover"></span>
<span id="publish-it-as-a-service"></span>

## Working directories and delivery

Runtime Host manages task directories; it does not automatically use your open Git checkout. Prepare the repository, branch, and inputs explicitly, and upload shared results as Artifacts. An online Host does not prove that the provider is authenticated or that its tools can execute.

Workspace instructions, Skills, and tools are mapped according to provider capabilities. Dependencies and third-party authentication remain on the Host. Native provider session recovery differs from Turn recovery commands; read invocation capabilities before showing controls.

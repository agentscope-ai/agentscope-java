---
title: "Hosted Agent: overview and usage"
zh_link: /v2/zh/service/hosted-agent
---

<Note>
This is preview documentation. The official release is not yet available.
</Note>

A Hosted Agent runs a Coding Agent installed on your machine or server. Service manages work, scheduling and collaboration records; Runtime Host starts the provider locally and reports results. You manage the provider account, models and tools.

Start with the [practical guide](/v2/en/service/connect-hosted-agent) for creation or connection. This reference section collects detailed configuration, supported capabilities and execution principles.

## In this chapter

- [Install and connect Runtime Host](/v2/en/service/runtime-host)
- [Host and Runtime settings](/v2/en/service/hosted-agent-configuration)
- [Providers and capability differences](/v2/en/service/hosted-agent-providers)
- [Execution and recovery](/v2/en/service/hosted-agent-execution)

## Prepare the machine

Install and sign in to a provider detected by the Host, such as Codex, Claude Code or Qoder. Run a simple request locally before [installing and connecting Runtime Host](/v2/en/service/runtime-host).

```bash
agentscope connect https://agentscope.example.com
agentscope runtime status
agentscope runtime probe
```

Confirm that the Host is online and provider detection succeeds. An online Host alone does not prove provider login or tool authorization.

## Create the Agent

Call `GET /api/v1/agents/runtime-options?tenant=...&namespace=...` and select a provider from `runtimes`, retaining `runtimeProfileId` and `runtimePoolId`. Create the Agent through `POST /api/v1/agents`, set `binding.kind: "hosted-runtime"`, put both IDs in `binding.configuration`, and supply a portable `definition`. See the [creation guide](/v2/en/service/connect-hosted-agent) for the complete request.

The returned `agent.id` is the stable reference for assignments, Team membership, and Endpoints. Read `/api/v1/agents/{agentId}/hosted-settings` for the binding and concurrency configuration. Management uses a platform credential authorized for the namespace; see [configuration](/v2/en/service/hosted-agent-configuration) for fields, responses, and version checks.

## Deliver a small task

Create a task through the [Issue API](/v2/en/service/issues), with `assigneeType: "agent"` and `assigneeRef: agent.id`. Ask for three improvements to supplied README text without editing files. Read `/api/v1/agent-tasks/{taskId}` and `/api/v1/execution-attempts?tenant=...&namespace=...&taskId=...`, then inspect Issue comments and Artifacts. Upload deliverable files as Artifacts so collaborators can access them outside the Host.

The Host manages task directories under its state directory by default. These are not automatically your open local Git checkout. Prepare repository content and branches through the task's workspace configuration.

## Extend capabilities

Workspace definitions supply portable guidance, skills and tool configuration where the adapter supports them. Install additional executables, external logins and permissions on the actual machine. Instructions do not install programs.

The Host supplies `agentscope-collaboration` MCP or task-scoped CLI access for context, progress, comments and artifact uploads. A Hosted implementation role can collaborate with Managed research or review roles in a [Team](/v2/en/service/team-collaboration).

## Interrupt and recover

Inspect active work before stopping a Host. Cancellation must reach the provider; confirm the final Attempt state. Preserve Host identity and state across restarts. Retried execution creates another Attempt and does not imply preservation of another backend's in-memory context.

If no Runtime is selectable, inspect `runtime probe`. For denied tools, inspect provider authentication and permission settings. For missing deliverables, inspect artifact upload and reporting logs.

## Publish it as a service

Publish the Agent, its Team, or a Workflow as an [Endpoint](/v2/en/service/endpoints). Applications invoke the unified [Agent API](/v2/en/service/service-api) for snapshots, Artifacts, and SSE. Native provider Session resume does not imply arbitrary checkpoint restoration through the unified API. Query Endpoint and invocation capabilities before using recovery features.

For the visual workflow, see [Console: Agent management](/v2/en/service/console/agents).

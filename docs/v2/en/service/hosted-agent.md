---
title: "Hosted Agents: connect your Coding Agent"
---

[简体中文](/v2/zh/service/hosted-agent)

A Hosted Agent runs a Coding Agent installed on your machine or server. Service manages work, scheduling and collaboration records; Runtime Host starts the provider locally and reports results. You manage the provider account, models and tools.

## Prepare the machine

Install and sign in to a provider detected by the Host, such as Codex, Claude Code or Qoder. Run a simple request locally before [installing and connecting Runtime Host](/v2/en/service/runtime-host).

```bash
agentscope connect https://agentscope.example.com
agentscope runtime status
agentscope runtime probe
```

Confirm that the Host is online and provider detection succeeds. An online Host alone does not prove provider login or tool authorization.

## Create the Agent

Under **DESIGN → Agents**, select a discovered Runtime instead of AgentScope Managed. Set responsibilities, an optional model override and a Workspace. Review the Runtime's supported instruction, tool, skill and subagent mappings before configuring them.

Runtime profiles and pools select execution capacity. The Runtime picker handles ordinary setup; administrators manage capacity and policy for multiple machines. A path on one Host is not necessarily available on every Host.

## Deliver a small task

Create an Issue asking the Agent to read an example repository README and return three suggestions without edits. Inspect its Execution, provider events and final comment. Then try a file-writing task and upload deliverables as Artifacts so collaborators can access them outside the Host.

The Host manages task directories under its state directory by default. These are not automatically your open local Git checkout. Prepare repository content and branches through the task's workspace configuration.

## Extend capabilities

Workspace definitions supply portable guidance, skills and tool configuration where the adapter supports them. Install additional executables, external logins and permissions on the actual machine. Instructions do not install programs.

The Host supplies `agentscope-collaboration` MCP or task-scoped CLI access for context, progress, comments and artifact uploads. A Hosted implementation role can collaborate with Managed research or review roles in a [Team](/v2/en/service/team-collaboration).

## Interrupt and recover

Inspect active work before stopping a Host. Cancellation must reach the provider; confirm the final Attempt state. Preserve Host identity and state across restarts. Retried execution creates another Attempt and does not imply preservation of another backend's in-memory context.

If no Runtime is selectable, inspect `runtime probe`. For denied tools, inspect provider authentication and permission settings. For missing deliverables, inspect artifact upload and reporting logs.

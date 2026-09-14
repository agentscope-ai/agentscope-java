---
title: "Hosted: connect and create an Agent"
---

<Note>
This is preview documentation. The official release is not yet available.
</Note>

Connect a computer or server with a Coding Agent so Service can dispatch work there. Install and authenticate the provider first, then install the `agentscope` CLI using the [Runtime Host guide](/v2/en/service/runtime-host).

## Connect the host

Replace the URL with a Service address reachable from the host:

```bash
agentscope connect https://agentscope.example.com
agentscope runtime status
agentscope runtime probe
```

Complete authentication through the connection flow. Confirm the Host is online and provider discovery succeeds, then verify that the provider itself can complete a request. See the [provider reference](/v2/en/service/hosted-agent-providers) for supported types and capabilities.

## Create a Hosted Agent

1. Open **DESIGN → Agents → New agent** and enter a name and responsibilities.
2. Select a discovered Coding Agent provider as Runtime.
3. Leave Model empty for defaults if appropriate; link only Workspace capabilities supported by the provider.
4. Save and check Runtime readiness.

## Dispatch and verify

Create a read-only [Issue](/v2/en/service/issues), such as “Suggest three documentation improvements based on the supplied README”, and select this Agent. Inspect Execution, result comments and deliverables to confirm the intended host performed the work.

Host manages the task directory. An already-open local Git repository does not automatically become task input. Prepare the actual material before requesting file operations and upload shared results as Artifacts.

If no Runtime is available, check Host and provider discovery. If claimed work fails, inspect login, parameters and dependencies. See the [Hosted Agent reference](/v2/en/service/hosted-agent) for configuration, definition mapping and recovery.

After single-task acceptance, continue with the [all-Hosted engineering case](/v2/en/service/cases/sdlc-team) for analysis, implementation, PR, review, CI, and approval.

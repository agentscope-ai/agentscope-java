---
title: "How Hosted execution and recovery work"
zh_link: /v2/zh/service/hosted-agent-execution
---

<Note>
This is preview documentation. The official release is not yet available.
</Note>

Hosted provider processes run on the Runtime Host machine. Service stores work and scheduling records; Host manages provider processes, task directories and event reporting.

```mermaid
flowchart TD
  A[Issue / Team / supported Chat] --> B[Select Profile, Pool and Attempt]
  B --> C[Host claims work and maintains lease]
  C --> D[Prepare task directory and definition]
  D --> E[Local provider executes]
  E --> F[Events, comments, Artifacts and outcome]
  F --> G[Control plane updates execution state]
```

## Registration and task selection

Host registers stable identity, scope, pool, provider descriptors and capacity. Agent bindings, capability requirements and runtime policy determine an Attempt. Host claims eligible work and maintains its lease. Host connectivity, provider discovery and Agent dispatch readiness are separate checks.

Runtime Profiles select provider parameters. Pools supply eligible Hosts. Host capacity and higher-level scheduling policies jointly constrain concurrency.

## Preparation and execution

Host prepares a task directory, translates supported platform instructions/capability files into provider formats and starts the provider in that directory. It does not automatically use the local checkout you are editing; specify repository, input material and branch during task preparation.

Task-scoped credentials and context are supplied through environment and MCP/CLI integration. Executors can read work, post comments and upload Artifacts. A file left on Host disk is not automatically accessible to collaborators; upload shared deliverables as Artifacts.

## Events, approval and recovery

Adapters convert provider events into execution records. Supported platform approval flows forward tool requests and await a decision. Other providers use their native permission mechanisms.

Host preserves journals, provider session identifiers and checkpoints for supported recovery paths. Keep state and Host identity across restarts. Resume support does not guarantee every interrupted execution can recover: the provider session must still exist and be accessible. The OpenClaw adapter currently has no Session resume.

## Retry and cancellation

Cancellation propagates through execution; check Attempt terminal state and provider process termination. A retry creates a new Attempt. Reuse a provider session only when recovery conditions hold. Cross-backend fresh fallback reconstructs context from persistent Issues, comments and Artifacts, without migrating process memory.

Use `agentscope runtime logs -f` with Task/Attempt diagnostics. If no work is claimed, check scope, pool, bindings, capacity and required capabilities. If claimed work fails, check provider login, parameters, task directory and tool dependencies.

Related: [installation](/v2/en/service/runtime-host), [providers](/v2/en/service/hosted-agent-providers) and [Team collaboration](/v2/en/service/team-collaboration).

## Track and control work through the API

Platform accounts assign work through Issues or Endpoints. Host credentials are for daemon claims, renewals, and reports. Business callers do not handle `leaseToken` values or provider processes.

| Scenario | API and parameters | Response/purpose |
| --- | --- | --- |
| Read AgentTask | `GET /api/v1/agent-tasks/{taskId}` | Task state and execution references |
| List physical attempts | `GET /api/v1/execution-attempts?tenant=...&namespace=...&taskId=...`; optional `state`, `limit` | `attempts`; retries have separate records |
| Read an Attempt | `GET /api/v1/execution-attempts/{attemptId}` | `attempt`, including backend, Host, lease, failure, and recovery data |
| Request cancellation or retry | `POST /api/v1/agent-tasks/{taskId}/cancel`, `/retry` | Submit version fields as described in [Issue API](/v2/en/service/issues); then inspect final state |
| Read Workflow progress | `GET /api/v1/orchestration-runs/{runId}/graph`, `/events` | Node graph and run events reflecting execution results |
| Restore an application view | `GET /invoke/v1/invocations/{invocationId}/snapshot`, then `/events/stream` | Resume SSE from the snapshot cursor with the invocation credential |

The Host protocol's `/checkpoint` operation saves `providerSessionId` and checkpoint data for supported adapter recovery. It is not an application API for restoring any backend from an arbitrary checkpoint. Unified invocations currently report `checkpoint_restore: false`. Hosted conversations support cancellation; do not assume Managed input, approval, or resume features are available. Read `available_commands` from `/invoke/v1/invocations/{invocationId}/capabilities` before offering interactions.

SSE reconnection restores recorded output. It does not rerun tools or recover a Host process. Upload durable deliverables as Artifacts; Host files, provider sessions, and framework state retain their own lifecycles. See [Runtime Host protocol](/v2/en/service/runtime-host#runtime-host-protocol) for API parameters.

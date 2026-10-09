---
title: "Execution reference: Sessions, Runs and Attempts"
zh_link: /v2/zh/service/sessions
---

<Note>
This is preview documentation. The official release is not yet available.
</Note>

Applications submit work through Agent API sessions, Issues, or Session API. This reference follows the request through Runs, Tasks, Attempts, and Sessions. Namespace, work-level, and operational permissions apply to diagnostic APIs.

## Trace work

Query the Runs associated with an Issue. Inspect input, mode and actual target, followed by nodes, AgentTasks and the latest Attempt. Session holds model or provider context. Retain these IDs for diagnosis rather than relying on reusable display names.

| Run mode | Shape |
| --- | --- |
| direct | Single-Agent work |
| adaptive | Lead-coordinated Team work |
| declared | Pinned Workflow revision |
| subrun | Child process invoked by a parent node |

## Execution query APIs

Use a user Bearer token and consistent `X-AgentScope-Tenant` / `X-AgentScope-Namespace` headers. List requests also require explicit `tenant` and `namespace`. See [Workflow APIs](/v2/en/service/workflows) and [task APIs](/v2/en/service/issues) for complete requests.

| Operation | API | Query / response |
| --- | --- | --- |
| Find Runs | `GET /api/v1/orchestration-runs` | `issueId`, `definitionId`, `state`, `active`, `offset`, `limit`; returns `{runs}` |
| Read Run | `GET /api/v1/orchestration-runs/{runId}` | `{run}` with `state`, `input`, `output`, `waitReason`, and failure information |
| Read graph | `GET /api/v1/orchestration-runs/{runId}/graph` | `run`, `nodes`, `edges`, `tasks`, `attempts`, optional `childRuns` |
| Read event history | `GET /api/v1/orchestration-runs/{runId}/events` | `after` sequence cursor and `limit`; returns `{events}` |
| Read task | `GET /api/v1/agent-tasks/{taskId}` | `{task,inputSummaries}` with results and input summaries |
| Find attempts | `GET /api/v1/execution-attempts` | `taskId`, `state`, `limit`; returns `{attempts}` |
| Read attempt | `GET /api/v1/execution-attempts/{attemptId}` | `{attempt}` with backend, execution state, result, and Session links |

`RunEvent.sequence` supports incremental reads within its Run. `type` identifies the event; `nodeId`, `agentTaskId`, and `attemptId` identify affected records. This events endpoint returns JSON rather than SSE. Published-service callers should prefer their Turn's returned statusUrl / eventsUrl; see [unified service APIs](/v2/en/service/service-api).

## States and controls

planned has not started, running is progressing and waiting awaits a condition, signal or external result. paused prevents new dispatch; cancelling awaits cancellation convergence. Terminal states are cancelled, succeeded, partial_succeeded and failed.

Pause does not freeze existing external processes. Cancel does not roll back files or external actions, nor accept the Issue. Inspect final node and Attempt states to confirm cancellation.

Control Runs with `POST /api/v1/orchestration-runs/{runId}/pause`, `/resume`, or `/cancel`, using `{}` and receiving `{run}`. After a terminal state, `/rerun` accepts required `idempotencyKey` and optional `input`. It returns a new `{run}` with `rerunOfRunId` lineage.

Cancel a task through `POST /api/v1/agent-tasks/{taskId}/cancel` with its current `expectedVersion`; retry through `/retry`. Do not pass Task IDs to Run endpoints or directly rewrite Attempt state to cancel work.

## Three retry levels

Infrastructure retries can create another Attempt for the same Task. Node-policy retries can create a new Task. Manual Rerun of terminal work creates a new Run with lineage. Inspect the actual input and target each time instead of conflating earlier failure with later success.

Explicit fresh fallback allows policy-driven reconstruction on another candidate backend from Issues, Comments and Artifacts, not from the original process memory.

## Waits and failures

Read waitReason/error to identify needed human input, Host, Worker, credentials or capacity. A Session in `requires_action` can be waiting for tool results rather than human approval.

Tool events, final replies, Attempt success and Issue acceptance are separate evidence. Review deliverables through the [review APIs](/v2/en/service/issues#inbox); see [Managed outcomes](/v2/en/service/issues#managed-harness-task-outcomes) for their semantics.

## Reconnect

Reopen the original work and query saved events and current state. SSE ending is not proof of failure. Proxies should forward events promptly. Do not submit duplicate work with a new idempotency key merely because the frontend disconnected.

## Session diagnostics

`GET /api/v1/sessions` lists authorized sessions; `GET /api/v1/sessions/{sessionRef}` reads one. Prefer the Attempt's `sessionRef` control-plane record ID. Do not interchange it with a runtime `sessionId` or `providerSessionId`. Include the correct tenant/namespace.

Diagnostic endpoints include `/messages` (`offset`, `limit`, `fromEnd`), `/context`, `/events`, `/events/stream`, and `/turns`. Availability depends on the runtime's observation and query capabilities. Registering an Agent does not imply full context access or recovery support. For business-facing session management, use the Agent API below.

## Managed Session log and recovery entry points

Agent API correlates session_id → turn_id → run_id; run_id is not an orchestration Run ID. Snapshot supplies items/tools/turns/required_actions and an as_of cursor for stream resumption. GET turns returns command states keyed by the submitted turn ID.

Native history holds checkpoints and execution facts; ordinary clients read public projections, while authorized administrators inspect trace/recovery. Reconnect after transport loss without posting another input. Inspect uncertain effects before actions/resume after execution interruption. See [Agent API and session logs](/v2/en/service/session-event-log).

## What to record during diagnosis

Practice with the [incident repair case](/v2/en/service/cases/incident-to-pr): query the parent Issue's Run graph, when using a Hosted target, locate its task and inspect the latest Attempt, and correlate its Session, logs, and files.

| Record | Purpose |
| --- | --- |
| Issue ID and acceptance criteria | Establish the deliverable and whether human acceptance is outstanding |
| Run ID, mode, and target revision | Identify the execution, orchestration shape, and definition |
| Node / Task / Attempt IDs | Locate the failing step and distinguish retry levels |
| Session ID and Host/provider where applicable | Locate execution context and machine |
| Status, error, time, and last event cursor | Distinguish a wait, terminal failure, and an observation disconnect |
| Artifacts and test logs | Evaluate delivery against requirements instead of status labels alone |

If a second Attempt succeeds, retain the first failure and associate delivery with the successful execution's files. For an SSE disconnect, resume observation of the original invocation with its cursor using the [SSE guide](/v2/en/service/sse-events); do not create another business task.

For execution-graph UI navigation, see [Console: Teams and orchestration](/v2/en/service/console/index#console-orchestration).

<span id="managed-agent-execution"></span>
<span id="building-runtime-context"></span>
<span id="model-and-tool-loop"></span>
<span id="persistence-and-recovery"></span>
<span id="observe-a-published-service-through-unified-apis"></span>
<span id="integrate-a-managed-agent-through-agent-api"></span>
<span id="execution-and-acceptance"></span>

## Managed execution and persistence

The control plane resolves the definition version, environment, knowledge and credential references. Dataplane materializes definition files in a session directory, builds Harness and connects persistent state. Definition snapshots, execution files and shared resources have different lifecycles: saving an Agent does not reconfigure every running instance.

Workspace holds capability definitions, Environment chooses where files and commands execute, Memory Store holds shared knowledge, and Vault resolves tool credentials. Their Managed usage is described in this category's resource pages.

Harness uses an explicit Model or the deployment default, reasons from instructions, requests tools and consumes results. `maxIters` limits iterations and tool policy controls operations. Execution can wait for user approval; resubmitting the same work while it waits can create additional execution.

Local tools execute in Dataplane, sandbox uses E2B, remote uses shared file storage, and self_hosted delegates tool work to a Worker. The model still runs in Dataplane for self_hosted environments; the Worker receives tool operations and returns results.

Session state, events and coordination records use the deployment's persistent stores. Coordination leases constrain execution across replicas. Recovery still depends on the database, working files, chosen environment and external tools. Restarting a service does not reverse an external side effect from a completed tool call.

Shared Memory is live platform knowledge accessed on demand, not a full copy inserted into every prompt. Maintain it as shared knowledge; an Agent definition version does not freeze all external knowledge.

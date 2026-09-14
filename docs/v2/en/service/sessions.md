---
title: "Execution reference: Sessions, Runs and Attempts"
---

<Note>
This is preview documentation. The official release is not yet available.
</Note>

Use Chat for conversation and Issues for work. Sessions and Executions are diagnostic views whose availability depends on operational permissions.

## Trace work

Open a Run from an Issue's Executions. Inspect input, mode and actual target, followed by nodes, AgentTasks and the latest Attempt. Session holds model or provider context. Retain these IDs for diagnosis rather than relying on reusable display names.

| Run mode | Shape |
| --- | --- |
| direct | Single-Agent work |
| adaptive | Lead-coordinated Team work |
| declared | Pinned Workflow revision |
| subrun | Child process invoked by a parent node |

## States and controls

planned has not started, running is progressing and waiting awaits a condition, signal or external result. paused prevents new dispatch; cancelling awaits cancellation convergence. Terminal states are cancelled, succeeded, partial_succeeded and failed.

Pause does not freeze existing external processes. Cancel does not roll back files or external actions, nor accept the Issue. Inspect final node and Attempt states to confirm cancellation.

## Three retry levels

Infrastructure retries can create another Attempt for the same Task. Node-policy retries can create a new Task. Manual Rerun of terminal work creates a new Run with lineage. Inspect the actual input and target each time instead of conflating earlier failure with later success.

Explicit fresh fallback allows policy-driven reconstruction on another candidate backend from Issues, Comments and Artifacts, not from the original process memory.

## Waits and failures

Read waitReason/error to identify needed human input, Host, Worker, credentials or capacity. A Session in `requires_action` can be waiting for tool results rather than human approval.

Tool events, final replies, Attempt success and Issue acceptance are separate evidence. Review deliverables in [Inbox](/v2/en/service/inbox); see [Managed outcomes](/v2/en/service/managed-harness-task-outcomes) for their semantics.

## Reconnect

Reopen the original work and query saved events and current state. SSE ending is not proof of failure. Proxies should forward events promptly. Do not submit duplicate work with a new idempotency key merely because the frontend disconnected.

## What to record during diagnosis

Practice with the [engineering case](/v2/en/service/cases/sdlc-team): open the Run from the parent Issue, locate the Hosted member in Task map, inspect its latest Attempt, and correlate its Session, logs, and files.

| Record | Purpose |
| --- | --- |
| Issue ID and acceptance criteria | Establish the deliverable and whether human acceptance is outstanding |
| Run ID, mode, and target revision | Identify the execution, orchestration shape, and definition |
| Node / Task / Attempt IDs | Locate the failing step and distinguish retry levels |
| Session ID and Host/provider where applicable | Locate execution context and machine |
| Status, error, time, and last event cursor | Distinguish a wait, terminal failure, and an observation disconnect |
| Artifacts and test logs | Evaluate delivery against requirements instead of status labels alone |

If a second Attempt succeeds, retain the first failure and associate delivery with the successful execution's files. For an SSE disconnect, resume observation of the original invocation with its cursor using the [SSE guide](/v2/en/service/sse-events); do not create another business task.

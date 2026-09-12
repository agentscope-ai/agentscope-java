---
title: "Execution reference: Sessions, Runs and Attempts"
---

[简体中文](/v2/zh/service/sessions)

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

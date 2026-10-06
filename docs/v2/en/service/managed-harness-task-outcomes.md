---
title: "Managed task outcomes and failures"
zh_link: /v2/zh/service/managed-harness-task-outcomes
---

<Note>
This is preview documentation. The official release is not yet available.
</Note>

A Managed durable task needs an explicit deliverable outcome, not merely the end of a model turn. Use this reference to decide what follows a wait, blockage or failure.

## Read outcomes and send feedback

Outcomes below describe runtime reporting, not arbitrary client-patchable states. Read results through the entry point that created the work:

| Entry point | Read and feedback APIs |
| --- | --- |
| Published service | `GET /invoke/v1/invocations/{id}` for invocation.status/result; feedback through actions/inputs commands |
| Issue | `GET /api/v1/issues/{id}/summary`; task details at `/api/v1/agent-tasks/{taskId}`, execution at `/api/v1/execution-attempts/{attemptId}` |
| Human acceptance | `POST /api/v1/issues/{id}/accept` with expectedVersion; `/reject` with expectedVersion and reason |
| Native Managed session | `GET /api/v1/agent-sessions/{sessionId}/turns/{turnId}`; actions/cancel/resume according to state |

Read the current Issue version and inspect deliverables before deciding. Invocation completed, execution succeeded, native turn completed, and Issue accepted describe different resources. Runtime complete/fail reports use restricted task or execution identity; see [Feedback APIs](/v2/en/service/inbox).

## Outcomes

| Outcome | Meaning | Next action |
| --- | --- | --- |
| succeeded | A deliverable exists and execution can finish | Inspect artifacts and follow Issue acceptance policy |
| waiting | A real, tracked dependency is outstanding | Inspect its ID and state |
| blocked | Information or conditions are missing; partial work is retained | Supply specific input and continue the work flow |
| failed | Execution failed | Read errors and partial results before retrying |

Text such as “I will continue later” is not success. Outstanding background work or abnormal termination cannot establish completion either. Runtime budgets bound automatic continuation and dependency waiting.

## Child work and deliverables

Child tasks use separate context and return through durable task records. Creating a subagent does not grant missing tools, network access or permissions. Unknown dependencies should produce an error rather than an indefinite wait.

The Lead should bring child results into the parent Issue and Artifacts, identifying partial output, failures and uncertain facts. Truncated file-search output requires narrower follow-up searches before claiming complete evidence.

## Recovery and acceptance

Blocked coordinator work can continue after new human input. Explicitly failed execution retains its terminal record and may require a new execution. Cancellation attempts to stop underlying work; inspect its final state before assuming it stopped.

Authorized Agents may record acceptance evidence but cannot rewrite human requirements or bypass review. Task or Run success does not establish factual accuracy; compare complete deliverables with criteria in [Inbox](/v2/en/service/inbox).

## Example: evaluate a presales deliverable

In the [CRM proposal case](/v2/en/service/cases/in-product-delivery), the Agent needs actual files and source evidence to deliver. If expanded into a Team, it also needs member results. These examples explain outcomes; natural-language claims do not directly change task states.

| Evidence | Action |
| --- | --- |
| The tracked quality-review task is running | Follow its dependency ID and wait before declaring completion |
| Proposal text exists but required files are missing | Identify missing deliverables and arrange follow-up |
| Product knowledge could not be read | Check grants/bindings, retain partial output, and continue after conditions are restored |
| Files, sources, and open questions are complete | Consolidate, finish coordination, and follow Issue acceptance policy |
| The proposal promises unsupported capabilities | Require revision despite successful execution |

Refer to the original Issue, Task, and missing item when providing input: “Deliver open-questions.md with unconfirmed conditions and their sources.” Recovery relies on durable records rather than a promise in an earlier turn.

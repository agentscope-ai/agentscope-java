---
title: "Managed task outcomes and failures"
---

[简体中文](/v2/zh/service/managed-harness-task-outcomes)

A Managed durable task needs an explicit deliverable outcome, not merely the end of a model turn. Use this reference to decide what follows a wait, blockage or failure.

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

---
title: "Managed task outcomes and failures"
---

<Note>
This is preview documentation. The official release is not yet available.
</Note>

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

## Example: evaluate a presales deliverable

In the [Managed presales case](/v2/en/service/cases/presales-team), the Leader needs member output and source evidence before completing coordination. These examples explain outcomes; natural-language claims do not directly change task states.

| Evidence | Action |
| --- | --- |
| The tracked quality-review task is running | Follow its dependency ID and wait before declaring completion |
| Proposal text exists but required files are missing | Identify missing deliverables and arrange follow-up |
| Product knowledge could not be read | Check grants/bindings, retain partial output, and continue after conditions are restored |
| Files, sources, and open questions are complete | Consolidate, finish coordination, and follow Issue acceptance policy |
| The proposal promises unsupported capabilities | Require revision despite successful execution |

Refer to the original Issue, Task, and missing item when providing input: “Deliver requirements.csv with capability and source version for each requirement.” Recovery relies on durable records rather than a promise in an earlier turn.

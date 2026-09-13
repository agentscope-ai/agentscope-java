---
title: "Issues: deliver and accept work"
---

[简体中文](/v2/zh/service/issues)

An Issue holds a work objective, owner, discussion, execution records and deliverables. It remains traceable across failed executions and service restarts. Use Issues for work that must be completed and accepted, such as a report or a bug fix.

## Create work

Open **WORK → Issues → New issue**. Write an outcome-oriented title, such as “Analyze sample logs and deliver an error report.” Include input locations, boundaries and expected deliverables in the description.

Choose **Sharing**: Private for yourself, or Namespace members for the shared scope. You can add individual collaborators later. Sharing includes execution records and attachments, so review it before adding material.

Choose an Agent, Team or Human owner, or a Workflow execution target. A Workflow starts its latest published revision and records the actual revision used; publish it first if no revision exists. Ownership can be assigned later. After creation, check **Executions** rather than assuming that a saved Issue means an Agent is already running.

## Define acceptance

Add Acceptance criteria in the detail view, for example:

```text
- Read only sample.log; do not access production logs.
- Deliver report.md with error categories, counts and three line-numbered examples.
- Mark unconfirmed causes as hypotheses.
```

Priority communicates importance; Due date communicates a deadline. Neither replaces the task description. Split large work into child Issues with their own owners and deliverables, then review the combined outcome in the parent.

## Discuss and exchange files

Add information in comments, reply to threads or mention an Agent that should participate. Check routing outcomes and execution records to see whether a message caused follow-up work. Resolving a comment thread does not accept the Issue.

Use Artifacts for files and deliverables. Subscribe to updates without changing access permissions. The Source area identifies the originating Chat, Channel or other entry point.

## Read the status

| State | What to check |
| --- | --- |
| Backlog / Todo | Complete requirements and assign responsibility |
| In progress | Review current execution, discussion and artifacts |
| Blocked | Identify missing information, authorization or dependencies |
| In review | Compare results and child Issues with acceptance criteria |
| Done | Work has completed according to its completion policy |
| Cancelled | Work was cancelled; retain its reason for reference |

A successful Run is not itself Issue acceptance. The `review` policy requires human acceptance, `automatic` permits automatic completion rules, and `external` leaves completion to the corresponding external process. Check Policy and the current Issue state.

## Accept or request changes

In **Inbox → Review result**, inspect the latest result, files and child Issues. **Accept result** completes the Issue. **Request changes** records specific feedback and returns it to In progress. Returning work does not automatically start another execution; arrange the follow-up explicitly. See [Inbox](/v2/en/service/inbox).

When execution fails, inspect the node, Attempt and error before retrying. A new execution preserves earlier failure evidence. Completed work can be reopened when needed; archive it to organize history.

Next: [Team collaboration](/v2/en/service/team-collaboration) · [Execution reference](/v2/en/service/sessions).

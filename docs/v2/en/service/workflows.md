---
title: "Workflows: repeatable processes"
---

[简体中文](/v2/zh/service/workflows)

**DESIGN → Workflows** is for work with explicit steps, dependencies and human gates. A definition has an editable draft; publishing creates an immutable revision. Each Run pins a revision, so later edits do not change its topology.

## Start with one Agent step

1. Create a Workflow, name it and choose the Initial Workflow Agent.
2. Set the node key and Agent in the designer. Keys must be unique within the Workflow.
3. Save and validate the draft. Resolve missing targets, unknown references and cycles.
4. Publish a version. Select it in the execution form, choose a new or existing Issue and supply JSON input.
5. Inspect the execution graph, node output and Issue result before adding more steps.

## Node types

| Type | Purpose |
| --- | --- |
| agent | Execute with one Agent |
| team | Delegate to a Team |
| condition | Choose a path using CEL conditions |
| join | Join branches according to the configured dependency policy |
| approval | Wait for a designated reviewer |
| timer | Wait for a duration |
| signal | Wait for a named external signal |
| subrun | Invoke a pinned, published Workflow revision |

For “draft → human approval → publication preparation,” connect agent, approval and agent nodes. Approval permits the next step; the Agent still needs actual tools and authorization for external operations.

## Map data and validate

Input mapping associates input names with CEL expressions such as `run.input.request`. Expressions can reference run input, variables, Issue, trigger and predecessor status, output and artifacts. Inspect real node output before mapping downstream fields.

CEL is a restricted expression language, not an arbitrary script runner. Workflows must be acyclic, and subruns pin a revision. Graphical and JSON editors modify the same definition; both require server-side validation before publication.

## Control execution

Pause prevents new node dispatch while running steps can still return results. Resume restarts dispatch. Cancel requests cancellation of nodes, tasks and subruns without accepting or deleting the Issue.

Rerun after a terminal state creates a new Run with lineage. Choose `fail_fast`, `continue` or `partial_success` according to whether partial output meets the business goal. Do not expect automatic compensation of external side effects.

## Expose the process

Select a specific revision when publishing an Endpoint. Publishing a new Workflow revision alone does not switch callers; update the Endpoint release and verify the invoked revision.

Next: [Endpoint integration](/v2/en/service/endpoints) · [Execution states](/v2/en/service/sessions).

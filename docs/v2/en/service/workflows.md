---
title: "Orchestrate Workflows through APIs"
zh_link: /v2/zh/service/workflows
---

<Note>
This is preview documentation. The official release is not yet available.
</Note>

Use a Workflow when business logic prescribes steps such as “draft a report → approve it → deliver.” Define, publish, and execute the process through APIs. Use a [Team](/v2/en/service/create-team) when its Leader should choose and delegate the steps dynamically. Both can serve as Job Endpoint targets.

A Workflow has an editable definition and immutable published revisions. Each Run pins a revision, so editing a draft does not change an existing execution.

## Create and validate the process

The examples use Bash, `curl`, and `jq`. Set `SERVICE_URL` to your Service address, `TOKEN` to a user Bearer token, and `TENANT` / `NAMESPACE` to your authorized scope; see [API authentication](/v2/en/service/api-reference). Define this request helper:

```bash
api() {
  curl --fail-with-body --silent --show-error \
    -H "Authorization: Bearer $TOKEN" \
    -H "X-AgentScope-Tenant: $TENANT" \
    -H "X-AgentScope-Namespace: $NAMESPACE" \
    -H 'Content-Type: application/json' "$@"
}
```


Set `AGENT_ID` to a task-capable Agent and `APPROVER_ID` to the reviewer's account identifier. This process drafts a report, then waits for approval:

```bash
defined=$(api "$SERVICE_URL/api/v1/orchestration-definitions" --data "$(jq -n \
  --arg tenant "$TENANT" --arg namespace "$NAMESPACE" \
  --arg agent "$AGENT_ID" --arg approver "$APPROVER_ID" \
  '{tenant:$tenant,namespace:$namespace,name:"Report review workflow",
    draftSpec:{nodes:[
      {key:"draft",type:"agent",agentId:$agent,input:{request:"run.input.request"}},
      {key:"review",type:"approval",approval:{approverType:"human",approverRef:$approver,prompt:"Review the report and evidence"}}
    ],edges:[{from:"draft",to:"review",on:["succeeded"]}]}}')")
WORKFLOW_ID=$(jq -r '.definition.id' <<<"$defined")
api "$SERVICE_URL/api/v1/orchestration-definitions/$WORKFLOW_ID/validate" --data '{}'
```

Each node `key` is unique. Edges connect an upstream state to downstream work. `input` maps field names to CEL expressions; here it passes the request's `request` field to the draft node. Expressions can read `run`, `issue`, `trigger`, and predecessor `nodes`, rather than execute arbitrary scripts. Validation checks types, references, expressions, and cycles; verify target availability separately.

## Publish and start execution

Publish the validated definition and save the revision ID. To edit later, call `PATCH /api/v1/orchestration-definitions/{definitionId}` with `draftSpec` and `expectedVersion`, then publish again.

```bash
published=$(api "$SERVICE_URL/api/v1/orchestration-definitions/$WORKFLOW_ID/publish" \
  --data "$(jq -n --argjson version "$(jq '.definition.version' <<<"$defined")" \
  '{expectedVersion:$version}')")
REVISION_ID=$(jq -r '.revision.id' <<<"$published")
started=$(api "$SERVICE_URL/api/v1/orchestration-definitions/$WORKFLOW_ID/runs" \
  --data "$(jq -n --arg revision "$REVISION_ID" \
  '{revisionId:$revision,idempotencyKey:"weekly-report-001",
    input:{request:"Summarize this weeks Workspace material into a report with sources"},
    issue:{title:"Weekly project report",description:"Deliver the reviewed report",access:{mode:"private"}}}')")
RUN_ID=$(jq -r '.run.id' <<<"$started")
ISSUE_ID=$(jq -r '.run.rootIssueId' <<<"$started")
```

Use `issue` to create work alongside execution, or `issueId` to attach existing work; supply exactly one. Reuse `idempotencyKey` when retransmitting the same submission. Save both IDs to follow execution and business acceptance separately.

## Follow nodes, output, and approval

```bash
api "$SERVICE_URL/api/v1/orchestration-runs/$RUN_ID"
api "$SERVICE_URL/api/v1/orchestration-runs/$RUN_ID/graph"
api "$SERVICE_URL/api/v1/orchestration-runs/$RUN_ID/events?after=0&limit=200"
```

Details include `run.state`, output, and failure information. The graph includes nodes, tasks, attempts, and child runs. Events are a JSON history query ordered by `sequence`; use the last processed sequence as the next `after`. This is not SSE. When exposing the process through an Endpoint, applications can follow the Invocation's [SSE stream](/v2/en/service/sse-events).

The review node creates an approval for the designated user to decide through the [Approval API](/v2/en/service/inbox#decide-an-execution-approval). Node approval permits the process to proceed, while final Issue review accepts the business deliverable. Subsequent publication or external modifications still require the executing Agent's tools and permissions.

## Add process capabilities gradually

| Node | Purpose and key fields |
| --- | --- |
| `agent` / `team` | Execute an Agent or Team through `agentId` / `teamRef` |
| `condition` | Evaluate `condition`; edges can also specify conditions |
| `join` | Join using `join.mode`: `all`, `any`, or `quorum` |
| `approval` | Designate a reviewer with `approval.approverRef` |
| `timer` | Wait with `timer.durationSeconds` or `timer.at` |
| `signal` | Wait for the named `signalName` |
| `subrun` | Invoke a pinned `definitionRevisionId` |

After adding a signal node waiting for `report.ready`, a business system can send:

```bash
api "$SERVICE_URL/api/v1/orchestration-runs/$RUN_ID/signals/report.ready" \
  --data '{"idempotencyKey":"report-upload-001","payload":{"artifactId":"YOUR_ARTIFACT_ID"}}'
```

Signals provide external process input; they do not replace approval. Run small examples and inspect real output before writing downstream mappings. Different runtimes do not necessarily return identical result structures.

## Pause, cancel, and rerun

`POST /api/v1/orchestration-runs/{runId}/pause` stops new node dispatch while running steps may still return. `/resume` resumes scheduling; `/cancel` requests cancellation of nodes, tasks, and child runs without deleting or accepting the Issue. Use `{}` as the request body.

After a terminal state, post `{"idempotencyKey":"weekly-report-retry-001"}` to `/rerun` to create a new Run with lineage. Include `input` to replace the input. Nodes support `timeoutSeconds`, `retry`, and `failurePolicy`, including `fail_fast`, `continue`, and `partial_success`. Retries do not undo existing external side effects.

Publish through the [Endpoint API](/v2/en/service/endpoints) with `targetType:"orchestration_revision"` and a specific revision ID. Publishing another Workflow revision requires an Endpoint release update before callers switch to it. See [Console: Teams and orchestration](/v2/en/service/console/orchestration) for designer and execution-graph operations.

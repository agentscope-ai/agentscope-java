---
title: "Orchestrate a Workflow"
zh_link: /v2/zh/service/workflows
---

<Note>
This is preview documentation. The official release is not yet available.
</Note>

Use a Workflow when the business prescribes steps such as drafting, approval, and delivery. Publish a revision, create a Session targeting that Workflow, and submit Turns. Use a [Team](/v2/en/service/create-team) when a Leader should choose steps dynamically. Both share the application invocation path.

A Workflow has an editable definition and immutable published revisions. Each Run pins a revision, so editing a draft does not change an existing execution.

Use a verified Managed Agent as a process node; call a Team when a step needs collaborative work. Start with [one working Managed Agent](/v2/en/service/create-managed-agent), then define the process and approver. See [orchestration choices](/v2/en/service/orchestration).

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
session=$(api "$SERVICE_URL/api/v1/agent-sessions" --data "$(jq -n \
  --arg workflow "$WORKFLOW_ID" --arg revision "$REVISION_ID" \
  '{target:{type:"workflow",id:$workflow,revisionId:$revision}}')")
SESSION_ID=$(jq -er '.id' <<<"$session")
turn=$(api "$SERVICE_URL/api/v1/agent-sessions/$SESSION_ID/turns" \
  -H 'Idempotency-Key: weekly-report-001' \
  --data '{"input":{"request":"Produce this week’s report with sources"}}')
TURN_ID=$(jq -er '.id' <<<"$turn")
```

Save the Session and Turn IDs for observation, actions, and cancellation. Service creates the workflow execution and work records. Retry unchanged work with the same idempotency key.

## Follow nodes, output, and approval

```bash
api "$SERVICE_URL/api/v1/agent-sessions/$SESSION_ID/turns/$TURN_ID"
api "$SERVICE_URL/api/v1/agent-sessions/$SESSION_ID/snapshot"
```

Turn detail returns the task status, result, and error. The Session snapshot restores the application view; follow [SSE replay](/v2/en/service/sse-events) for live updates. Open the associated Run graph in Console when diagnosing individual workflow nodes.

The review node appears in the Turn’s required_actions. Its designated approver uses their platform token to submit `request_id`, `expected_version`, and `decision` to the Turn’s `/actions`. Approval allows the workflow to continue; an application key cannot replace the designated human identity.

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
api "$SERVICE_URL/api/v1/agent-sessions/$SESSION_ID/turns/$TURN_ID/inputs" \
  -H 'Idempotency-Key: report-upload-001' \
  --data '{"request_id":"RETURNED_SIGNAL_REQUEST_ID","expected_version":1,"payload":{"artifactId":"YOUR_ARTIFACT_ID"}}'
```

Read the request ID and version from the current required_actions rather than using the placeholders. Signals provide external process input; they do not replace approval. Run small examples and inspect real output before writing downstream mappings. Different runtimes do not necessarily return identical result structures.

## Cancellation and operational control

Applications cancel a Turn through `/cancel`, check capabilities before `/resume`, and submit a new Turn to start fresh work. The Run APIs below serve Console operations and workflow diagnosis. Obtain the Run ID from the associated execution record; it is not the Turn ID.


`POST /api/v1/orchestration-runs/{runId}/pause` stops new node dispatch while running steps may still return. `/resume` resumes scheduling; `/cancel` requests cancellation of nodes, tasks, and child runs without deleting or accepting the Issue. Use `{}` as the request body.

After a terminal state, post `{"idempotencyKey":"weekly-report-retry-001"}` to `/rerun` to create a new Run with lineage. Include `input` to replace the input. Nodes support `timeoutSeconds`, `retry`, and `failurePolicy`, including `fail_fast`, `continue`, and `partial_success`. Retries do not undo existing external side effects.

Create a Session with `target:{"type":"workflow","id":"WORKFLOW_ID","revisionId":"REVISION_ID"}`. Omitting `revisionId` selects the latest published revision at creation. Existing Sessions do not switch automatically. See [Console](/v2/en/service/console/index#console-orchestration) for the designer and run graph.

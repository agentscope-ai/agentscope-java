---
title: "Orchestrate a Workflow"
zh_link: /v2/zh/service/workflows
---

<Note>
This page uses the `2.1.0-BETA1` prerelease.
</Note>

Use a Workflow when the business prescribes steps such as drafting, approval, and delivery. Publish a revision, create a Session targeting that Workflow, and submit Turns. Use a [Team](/v2/en/service/create-team) when a Leader should choose steps dynamically. Both share the application invocation path.

A Workflow has an editable definition and immutable published revisions. Each Run pins a revision, so editing a draft does not change an existing execution.

Use a verified Managed Agent as a process node; call a Team when a step needs collaborative work. Start with [one working Managed Agent](/v2/en/service/create-managed-agent), then define the process and approver. See [orchestration choices](/v2/en/service/orchestration).

## Create and validate the process

The examples use Bash, `curl`, and `jq`. Set `BASE_URL` to your Service address, and `TENANT` / `NAMESPACE` to the default scope; see [API authentication](/v2/en/service/api-reference). Run the following steps in the same Bash terminal:

```bash
set -euo pipefail
```

Set `AGENT_ID` to a task-capable Agent and `APPROVER_ID` to the reviewer's account identifier. This process drafts a report, then waits for approval:

```bash
WORKFLOW_JSON=$(
  curl -sS --fail-with-body "$BASE_URL/api/v1/orchestration-definitions" \
    -H "Content-Type: application/json" \
    --data-binary @- <<JSON
{
  "tenant": "$TENANT",
  "namespace": "$NAMESPACE",
  "name": "Report review workflow",
  "draftSpec": {
    "nodes": [
      {
        "key": "draft",
        "type": "agent",
        "agentId": "$AGENT_ID",
        "input": {
          "request": "run.input.request"
        }
      },
      {
        "key": "review",
        "type": "approval",
        "approval": {
          "approverType": "human",
          "approverRef": "$APPROVER_ID",
          "prompt": "Review the report and evidence"
        }
      }
    ],
    "edges": [
      {
        "from": "draft",
        "to": "review",
        "on": [
          "succeeded"
        ]
      }
    ]
  }
}
JSON
)
WORKFLOW_ID=$(jq -er '.definition.id' <<< "$WORKFLOW_JSON")
WORKFLOW_VERSION=$(jq -er '.definition.version' <<< "$WORKFLOW_JSON")
```

```bash
curl -sS --fail-with-body -X POST "$BASE_URL/api/v1/orchestration-definitions/$WORKFLOW_ID/validate"
```

Each node `key` is unique. Edges connect an upstream state to downstream work. `input` maps field names to CEL expressions; here it passes the request's `request` field to the draft node. Expressions can read `run`, `issue`, `trigger`, and predecessor `nodes`, rather than execute arbitrary scripts. Validation checks types, references, expressions, and cycles; verify target availability separately.

## Publish and start execution

Publish the validated definition and save the revision ID. To edit later, call `PATCH /api/v1/orchestration-definitions/{definitionId}` with `draftSpec` and `expectedVersion`, then publish again.

```bash
PUBLISHED_JSON=$(
  curl -sS --fail-with-body "$BASE_URL/api/v1/orchestration-definitions/$WORKFLOW_ID/publish" \
    -H "Content-Type: application/json" \
    --data-binary @- <<JSON
{
  "expectedVersion": $WORKFLOW_VERSION
}
JSON
)
REVISION_ID=$(jq -er '.revision.id' <<< "$PUBLISHED_JSON")
```

```bash
SESSION_JSON=$(
  curl -sS --fail-with-body "$BASE_URL/api/v1/agent-sessions" \
    -H "Content-Type: application/json" \
    -H "Idempotency-Key: workflow-session-001" \
    --data-binary @- <<JSON
{
  "target": {
    "type": "workflow",
    "id": "$WORKFLOW_ID",
    "revisionId": "$REVISION_ID"
  }
}
JSON
)
SESSION_ID=$(jq -er '.id' <<< "$SESSION_JSON")
SESSION_URL="$BASE_URL/api/v1/agent-sessions/$SESSION_ID"
```

```bash
TURN_JSON=$(
  curl -sS --fail-with-body "$SESSION_URL/turns" \
    -H "Content-Type: application/json" \
    -H "Idempotency-Key: weekly-report-001" \
    --data-binary @- <<'JSON'
{
  "input": {
    "request": "Produce this week’s report with sources"
  }
}
JSON
)
TURN_ID=$(jq -er '.id' <<< "$TURN_JSON")
```

Save the Session and Turn IDs for observation, actions, and cancellation. Service creates the workflow execution and work records. Retry unchanged work with the same idempotency key.

## Follow nodes, output, and approval

```bash
curl -sS --fail-with-body "$BASE_URL/api/v1/agent-sessions/$SESSION_ID/turns/$TURN_ID"
```

```bash
curl -sS --fail-with-body "$BASE_URL/api/v1/agent-sessions/$SESSION_ID/snapshot"
```

Turn detail returns the task status, result, and error. The Session snapshot restores the application view; follow [SSE replay](/v2/en/service/sse-events) for live updates. Open the associated Run graph in Console when diagnosing individual workflow nodes.

The review node appears in the Turn’s required_actions. Its designated approver uses their platform token to submit `request_id`, `expected_version`, and `decision` to the Turn’s `/actions`. Approval allows the workflow to continue; an application key cannot replace the designated human identity.

## Add process capabilities gradually

<Accordion title="Answer the review node approval">

Use the local URL and default scope variables from [API setup](/v2/en/service/create-managed-agent#api-setup).

Answer directly in local mode; see [production deployment](/v2/en/service/kubernetes#production-api-access) for approval identities. Read and review the pending action, select request_id and expected_version for the review node, and submit only after deciding to approve. Use decision:"rejected" to reject.

```bash
curl -sS --fail-with-body "$SESSION_URL/turns/$TURN_ID/actions"
```

```bash
REQUEST_ID="REVIEW_REQUEST_ID_FROM_ACTIONS"
EXPECTED_VERSION="VERSION_FROM_ACTIONS"
```

```bash
ACTION_JSON=$(
  curl -sS --fail-with-body "$SESSION_URL/turns/$TURN_ID/actions" \
    -H "Content-Type: application/json" \
    -H "Idempotency-Key: workflow-review-001" \
    --data-binary @- <<JSON
{
  "request_id": "$REQUEST_ID",
  "expected_version": $EXPECTED_VERSION,
  "decision": "approved"
}
JSON
)
COMMAND_ID=$(jq -er '.command.id' <<< "$ACTION_JSON")
```

```bash
curl -sS --fail-with-body "$SESSION_URL/turns/$TURN_ID/commands/$COMMAND_ID"
```

</Accordion>

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
REQUEST_ID="SIGNAL_REQUEST_ID_FROM_ACTIONS"
EXPECTED_VERSION="SIGNAL_VERSION_FROM_ACTIONS"
ARTIFACT_ID="YOUR_ARTIFACT_ID"
```

```bash
curl -sS --fail-with-body "$SESSION_URL/turns/$TURN_ID/inputs" \
  -H "Content-Type: application/json" \
  -H "Idempotency-Key: report-upload-001" \
  --data-binary @- <<JSON
{
  "request_id": "$REQUEST_ID",
  "expected_version": $EXPECTED_VERSION,
  "payload": {
    "artifactId": "$ARTIFACT_ID"
  }
}
JSON
```

Read the request ID and version from the current required_actions rather than using the placeholders. Signals provide external process input; they do not replace approval. Run small examples and inspect real output before writing downstream mappings. Different runtimes do not necessarily return identical result structures.

## Cancellation and operational control

Applications cancel a Turn through `/cancel`, check capabilities before `/resume`, and submit a new Turn to start fresh work. The Run APIs below serve Console operations and workflow diagnosis. Obtain the Run ID from the associated execution record; it is not the Turn ID.


`POST /api/v1/orchestration-runs/{runId}/pause` stops new node dispatch while running steps may still return. `/resume` resumes scheduling; `/cancel` requests cancellation of nodes, tasks, and child runs without deleting or accepting the Issue. Use `{}` as the request body.

After a terminal state, post `{"idempotencyKey":"weekly-report-retry-001"}` to `/rerun` to create a new Run with lineage. Include `input` to replace the input. Nodes support `timeoutSeconds`, `retry`, and `failurePolicy`, including `fail_fast`, `continue`, and `partial_success`. Retries do not undo existing external side effects.

Create a Session with `target:{"type":"workflow","id":"WORKFLOW_ID","revisionId":"REVISION_ID"}`. Omitting `revisionId` selects the latest published revision at creation. Existing Sessions do not switch automatically. See [Console](/v2/en/service/console/index#console-orchestration) for the designer and run graph.

<span id="curl-management"></span>

## Inspect and update a draft

Use the local URL and default scope variables from [API setup](/v2/en/service/create-managed-agent#api-setup).

```bash
curl -sS --fail-with-body -G "$BASE_URL/api/v1/orchestration-definitions" \
  --data-urlencode "tenant=$TENANT" \
  --data-urlencode "namespace=$NAMESPACE"
```

```bash
WORKFLOW_JSON=$(
  curl -sS --fail-with-body "$BASE_URL/api/v1/orchestration-definitions/$WORKFLOW_ID"
)
```

The following keeps the nodes and edges and changes only the workflow name. Preserve other settings when editing draftSpec, then validate and publish. A new revision does not change an active Run.

```bash
jq '.definition | {name,description,draftSpec,expectedVersion:.version}
  | .name = "Evidence review workflow"' <<< "$WORKFLOW_JSON" > workflow-update.json
```

```bash
curl -sS --fail-with-body -X PATCH "$BASE_URL/api/v1/orchestration-definitions/$WORKFLOW_ID" \
  -H "Content-Type: application/json" \
  --data-binary @workflow-update.json
```

```bash
curl -sS --fail-with-body -X POST "$BASE_URL/api/v1/orchestration-definitions/$WORKFLOW_ID/validate"
```

```bash
curl -sS --fail-with-body "$BASE_URL/api/v1/orchestration-definitions/$WORKFLOW_ID/revisions"
```

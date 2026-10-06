---
title: "Assign and track tasks through APIs"
zh_link: /v2/zh/service/issues
---

<Note>
This is preview documentation. The official release is not yet available.
</Note>

An Issue keeps the objective, owner, discussion, executions, and deliverables for a piece of work. An application can create “analyze these logs,” assign an Agent, show progress, and ask the user to accept the report—all through APIs.

This applies to registered, task-capable Agents across Managed, External, and Hosted runtimes. See [Agent creation and registration](/v2/en/service/agents). If your application only needs to invoke a published service and retrieve its result, use an [Endpoint](/v2/en/service/endpoints); Service associates the required work records with that invocation.

## Create your first work item

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


Set `AGENT_ID` to a task-capable Agent in the same namespace. Including an assignee creates work for asynchronous dispatch. A successful response means the work was accepted; query its state to follow execution.

```bash
created=$(api "$SERVICE_URL/api/v1/issues" --data "$(jq -n \
  --arg tenant "$TENANT" --arg namespace "$NAMESPACE" --arg agent "$AGENT_ID" \
  '{tenant:$tenant,namespace:$namespace,
    title:"Analyze sample logs and produce an error report",
    description:"Read sample.log in the Workspace. Produce report.md and identify uncertain causes.",
    acceptanceCriteria:["Group and count errors","Include evidence with line numbers"],
    assigneeType:"agent",assigneeRef:$agent,access:{mode:"private"}}')")
ISSUE_ID=$(jq -r '.issue.id' <<<"$created")
TASK_ID=$(jq -r '.agentTask.id // empty' <<<"$created")
printf '%s\n' "$created" | jq .
```

`issue.id` identifies the business work; `agentTask.id` identifies work assigned to an Agent. Retries, collaboration, and follow-up input can introduce additional tasks, so build the full work view around the Issue. For a Team, use `assigneeType:"team"` and its ID in `assigneeRef`; for a person, use `human`. Omit the assignee to save the work before assigning it.

`access.mode` defaults to `private`. Use `namespace` for namespace members or `shared` with `members` for selected collaborators. This scope also controls access to discussion and results. Access to `sample.log` depends separately on the Agent's Workspace and tools.

## Assign existing work and add input

Read the latest `version` and send it as `expectedVersion` before changing existing work. This avoids overwriting another participant's changes. For example, assign an existing Issue to a Team:

```bash
current=$(api "$SERVICE_URL/api/v1/issues/$ISSUE_ID")
api "$SERVICE_URL/api/v1/issues/$ISSUE_ID/assign" --data "$(jq -n \
  --arg team "$TEAM_ID" --argjson version "$(jq '.issue.version' <<<"$current")" \
  '{assigneeType:"team",assigneeRef:$team,expectedVersion:$version}')"
```

Normal scheduling does not require an extra `dispatch` call. Inspect the returned `agentTask` and its subsequent state. Assigning a human owner does not create an Agent execution.

During execution, add a comment with new information or requested changes. Use structured `mentions` when addressing an Agent explicitly:

```bash
api "$SERVICE_URL/api/v1/issues/$ISSUE_ID/comments" --data "$(jq -n \
  --arg agent "$AGENT_ID" \
  '{content:"Also count timeout errors and distinguish confirmed causes from hypotheses.",
    mentions:[{type:"agent",ref:$agent}]}')"
```

The response's `routes` explain whether input was queued, merged into existing work, or blocked by policy. Comments can trigger work. For an informational progress record, use `type:"progress"` without mentions. Include `parentId` to reply to a comment. Submit the same body to `POST /api/v1/issues/{issueId}/comments/preview-routing` to preview routing before posting.

## Read progress and deliverables

When a page opens again, read the Issue and its tasks, comments, and artifacts to reconstruct the work view:

```bash
api "$SERVICE_URL/api/v1/issues/$ISSUE_ID"
api "$SERVICE_URL/api/v1/agent-tasks" --get \
  --data-urlencode "tenant=$TENANT" --data-urlencode "namespace=$NAMESPACE" \
  --data-urlencode "issueId=$ISSUE_ID"
api "$SERVICE_URL/api/v1/issues/$ISSUE_ID/comments?limit=50"
api "$SERVICE_URL/api/v1/issues/$ISSUE_ID/artifacts"
api "$SERVICE_URL/api/v1/issues/$ISSUE_ID/activity?limit=50"
```

Task results, errors, and execution references support result displays and diagnostics. Continue comment pagination with the returned `nextCursor`. Artifact listings provide IDs; use `POST /api/v1/artifacts/{artifactId}/download` for download information. Upload through `POST /api/v1/artifacts/uploads`; see the [API reference](/v2/en/service/api-reference) for fields.

The platform work notification endpoint, `GET /api/v1/events?tenant=...&namespace=...`, uses **WebSocket** to signal that an application should refresh its data. It does not replay missed events. Refresh REST state after reconnecting. Requests made through a published Endpoint can instead follow their Invocation's [SSE stream](/v2/en/service/sse-events); these streams serve different purposes.

## Understand status and review results

An Issue moves from pending work into execution and may enter `in_review` for acceptance or `blocked` when input is missing. A successful AgentTask or Run means that execution ended. Business completion follows the Issue's completion policy. The ordinary Issue creation API currently uses `review`, requiring acceptance before `done`.

Read the latest Issue and deliverables before posting its version to `accept`. Use `reject` with a reason when changes are needed; see [Notifications, approvals, and reviews](/v2/en/service/inbox). Rejection records feedback and returns the Issue to `in_progress`, but does not start another execution. Follow up through comment routing to ask the owner to continue.

Retry a failed task through `POST /api/v1/agent-tasks/{taskId}/retry`, or cancel through `/cancel`. Inspect its current state first. Cancelling execution, accepting work, and archiving an Issue are separate actions. Re-execution retains previous records and can repeat external side effects.

## How Agents report progress

Managed Agents and integrated runtimes report progress, results, and errors through their execution adapters. Applications read those records. Custom runtimes can use task protocol endpoints such as `/agent-tasks/{taskId}/progress`, `/respond`, `/complete`, and `/fail`. These require an **AgentTask token** issued with the execution context, rather than a user's token. See the [runtime protocol](/v2/en/service/external-agent-execution).

To clarify requirements in a conversation first, use the [Agent API chat flow](/v2/en/service/agent-api-chat), then write the agreed objective into an Issue. Console creation, discussion, files, and Chat-to-Issue actions are covered in [Console: tasks and feedback](/v2/en/service/console/tasks).

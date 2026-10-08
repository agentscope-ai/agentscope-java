---
title: "Work, approvals, and acceptance"
zh_link: /v2/zh/service/issues
---

<Note>
This is preview documentation. The official release is not yet available.
</Note>

An Issue keeps the objective, owner, discussion, executions, and deliverables for a piece of work. An application can create “analyze these logs,” assign an Agent, show progress, and ask the user to accept the report—all through APIs.

This applies to registered, task-capable Agents across Managed, External, and Hosted runtimes. See [Agent creation and registration](/v2/en/service/api-reference#agents). If an application only needs to submit tasks, follow execution, and retrieve results, create a Session targeting an Agent, Team, or Workflow through the [Session API](/v2/en/service/service-api), without creating an Issue yourself first. Follow this guide when you need to manage assignees, discussion, and acceptance at the business level through the Issue API.

<span id="understand-status-and-review-results"></span>

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

The platform work notification endpoint, `GET /api/v1/events?tenant=...&namespace=...`, uses **WebSocket** to signal that an application should refresh its data. It does not replay missed events. Refresh REST state after reconnecting. Requests made through a Session API can instead follow their Turn's [SSE stream](/v2/en/service/sse-events); these streams serve different purposes.

## Status, outcomes, and acceptance

An Issue moves from pending work into execution and may enter `in_review` for acceptance or `blocked` when input is missing. A successful AgentTask or Run means that execution ended. Business completion follows the Issue's completion policy. The ordinary Issue creation API currently uses `review`, requiring acceptance before `done`.

<span id="managed-harness-task-outcomes"></span>
<span id="read-outcomes-and-send-feedback"></span>
<span id="outcomes"></span>
<span id="child-work-and-deliverables"></span>
<span id="recovery-and-acceptance"></span>
<span id="example-evaluate-a-presales-deliverable"></span>

The following Outcomes are runtime reports, not Issue states that clients may PATCH freely. Turn completed, native turn completed, Run succeeded, and Issue accepted belong to different resources and are not interchangeable.

| Outcome | Meaning | Next action |
| --- | --- | --- |
| succeeded | A deliverable exists and execution can finish | Inspect artifacts and follow Issue acceptance policy |
| waiting | A real, tracked dependency is outstanding | Inspect its ID and state |
| blocked | Information or conditions are missing; partial work is retained | Supply specific input and continue the work flow |
| failed | Execution failed | Read errors and partial results before retrying |

Text such as “I will continue later” is not success. Outstanding background work or abnormal termination cannot establish completion either. Runtime budgets bound automatic continuation and dependency waiting.

Child tasks use separate context and return through durable task records. Creating a subagent does not grant missing tools, network access or permissions. Unknown dependencies should produce an error rather than an indefinite wait.

The Lead should bring child results into the parent Issue and Artifacts, identifying partial output, failures and uncertain facts. Truncated file-search output requires narrower follow-up searches before claiming complete evidence.

<span id="inbox"></span>
<span id="find-actionable-items"></span>
<span id="review-a-deliverable"></span>

Continue with the `ISSUE_ID` created above. Load the result and confirm the Issue is currently `in_review`:

```bash
review=$(api "$SERVICE_URL/api/v1/issues/$ISSUE_ID")
api "$SERVICE_URL/api/v1/issues/$ISSUE_ID/artifacts"
api "$SERVICE_URL/api/v1/issues/$ISSUE_ID/comments?limit=50"
printf '%s\n' "$review" | jq '.issue | {title,status,version,acceptanceCriteria}'
```

Let the user inspect the acceptance criteria, final report, and relevant child results. Once they accept, submit the version they reviewed:

```bash
api "$SERVICE_URL/api/v1/issues/$ISSUE_ID/accept" --data "$(jq -n \
  --argjson version "$(jq '.issue.version' <<<"$review")" \
  '{expectedVersion:$version,reason:"The report and evidence meet the acceptance criteria"}')"
```

For requested changes, use `/reject` with `expectedVersion` and an explicit `reason`. Rejection returns work to `in_progress` without automatically executing it again. Follow the [comment and assignment flow](/v2/en/service/issues#assign-existing-work-and-add-input) to ask the owner to continue. Viewing a child or resolving a comment thread does not accept the current Issue.

On a version conflict, reload the result and let the user reconsider. Do not silently substitute the latest version and repeat an old review decision.

## How Agents report progress

Managed Agents and integrated runtimes report progress, results, and errors through their execution adapters. Applications read those records. Custom runtimes can use task protocol endpoints such as `/agent-tasks/{taskId}/progress`, `/respond`, `/complete`, and `/fail`. These require an **AgentTask token** issued with the execution context, rather than a user's token. See the [runtime protocol](/v2/en/service/external-agent#external-agent-execution).

To clarify requirements in a conversation first, use the [Agent API chat flow](/v2/en/service/agent-api-chat), then write the agreed objective into an Issue. Console creation, discussion, files, and Chat-to-Issue actions are covered in [Console: tasks and feedback](/v2/en/service/console/index#console-tasks).


## Inbox notifications and approvals

Inbox belongs to the authenticated user; a request parameter cannot turn it into another person's Inbox. Query it with a user identity:

```bash
api "$SERVICE_URL/api/v1/inbox" --get \
  --data-urlencode "tenant=$TENANT" --data-urlencode "namespace=$NAMESPACE" \
  --data-urlencode 'view=action' --data-urlencode 'limit=50'
api "$SERVICE_URL/api/v1/inbox/summary" --get \
  --data-urlencode "tenant=$TENANT" --data-urlencode "namespace=$NAMESPACE"
```

The list returns `items`, `hasMore`, and `nextCursor`. Pass `cursor` for the next page. Use `view=action` for unresolved decisions, `unread` for unread items, `attention` for items needing attention, or `all` for all unarchived items. Add `archived=true` to read archived notifications.

Read a selected item with `GET /api/v1/inbox/{inboxId}`, then follow its work or approval reference. Show the actual object, current result, and version before asking the user to decide.

### Decide an execution approval

An approval may originate from a Workflow's human gate or a runtime operation. Obtain `APPROVAL_ID` from the notification reference or query `GET /api/v1/approvals?tenant=...&namespace=...`. Read the requester, target, and reason first. Only the designated approver may decide:

```bash
approval=$(api "$SERVICE_URL/api/v1/approvals/$APPROVAL_ID")
printf '%s\n' "$approval" | jq .
```

After the user chooses to approve:

```bash
api "$SERVICE_URL/api/v1/approvals/$APPROVAL_ID/decide" --data "$(jq -n \
  --argjson version "$(jq '.approval.version' <<<"$approval")" \
  '{expectedVersion:$version,status:"approved",decision:{reason:"Operation scope reviewed"}}')"
```

Reject with `status:"rejected"`. The decision may resume or fail waiting execution; it does not accept the final deliverable. Reload expired requests or requests that no longer match the current execution.

Sessions invoked directly through Agent API may use pending session inputs for tool confirmations; see [session input and confirmation](/v2/en/service/session-event-log). Not every session confirmation is an Inbox Approval.

### Reading, archiving, and refreshing

Mark a notification read with `POST /api/v1/inbox/{inboxId}/read`. Archive it with `/archive` when it no longer belongs in the current list. These actions do not delete Issues, cancel execution, or replace decisions. Refresh counts through `/inbox/summary`.

Poll Inbox or refresh it when platform WebSocket notifications arrive. Inbox is a view of the user's pending work, not a complete execution log. Use [SSE and state APIs](/v2/en/service/sse-events) for execution streaming and reconnection.

For the platform UI workflow, see [Console: tasks and feedback](/v2/en/service/console/index#console-tasks).


Retry failed tasks with `POST /api/v1/agent-tasks/{taskId}/retry`; cancel with `/cancel`. Read the failure and current state first. Retries retain old records and may repeat external effects.

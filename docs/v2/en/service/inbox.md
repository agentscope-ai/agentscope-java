---
title: "Notification, approval, and review APIs"
zh_link: /v2/zh/service/inbox
---

<Note>
This is preview documentation. The official release is not yet available.
</Note>

Execution feedback can require two different decisions: accepting a completed deliverable, or authorizing an operation while work is running. An application can read the current user's Inbox, then call either Issue review or Approval decision APIs. Reading a notification does not make either decision.

## Find actionable items

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

## Review a deliverable

Continue with `ISSUE_ID` from the [task guide](/v2/en/service/issues). Load the result and confirm the Issue is currently `in_review`:

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

## Decide an execution approval

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

## Reading, archiving, and refreshing

Mark a notification read with `POST /api/v1/inbox/{inboxId}/read`. Archive it with `/archive` when it no longer belongs in the current list. These actions do not delete Issues, cancel execution, or replace decisions. Refresh counts through `/inbox/summary`.

Poll Inbox or refresh it when platform WebSocket notifications arrive. Inbox is a view of the user's pending work, not a complete execution log. Use [SSE and state APIs](/v2/en/service/sse-events) for execution streaming and reconnection.

For the platform UI workflow, see [Console: tasks and feedback](/v2/en/service/console/tasks).

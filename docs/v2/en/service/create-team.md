---
title: "Create and invoke Teams through APIs"
zh_link: /v2/zh/service/create-team
---

<Note>
This is preview documentation. The official release is not yet available.
</Note>

A Team combines registered Agents into one unit that applications can assign work to or invoke. Its Leader interprets the objective, delegates to members, and consolidates delivery. Members exchange work through tasks and discussion, so applications do not need to implement every internal delegation step.

Managed, External, and Hosted Agents can participate according to their capabilities. Verify registration, task execution, and the required collaboration protocol first. Team membership does not add tools or protocol support to a runtime.

## Create a review team

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


Set `LEADER_AGENT_ID` to a material-organizing Agent and `REVIEWER_AGENT_ID` to a reviewer. The Leader is not repeated in `members`; member Agent IDs and roles must each be unique.

```bash
created=$(api "$SERVICE_URL/api/v1/teams" --data "$(jq -n \
  --arg tenant "$TENANT" --arg namespace "$NAMESPACE" \
  --arg leader "$LEADER_AGENT_ID" --arg reviewer "$REVIEWER_AGENT_ID" \
  '{tenant:$tenant,namespace:$namespace,name:"Research review team",leaderAgentId:$leader,
    instructions:"The Leader drafts the report, delegates evidence checks to reviewer, then revises and consolidates delivery.",
    policy:{maxActiveTasks:3,maxFanout:2,requireReview:true},
    members:[{agentId:$reviewer,role:"reviewer",instructions:"Check facts, evidence, and unresolved information."}]}')")
TEAM_ID=$(jq -r '.team.id' <<<"$created")
api "$SERVICE_URL/api/v1/teams/$TEAM_ID/overview"
```

Team `instructions` explain coordination, member instructions define responsibilities, and `policy` bounds concurrency, delegation, and review. The overview shows configuration and activity. Creation does not prove that every runtime is online: verify member availability and execute a small task. See [Team configuration](/v2/en/service/team-configuration) for policy options.

## Assign an objective and read the result

Creating an Issue for a Team follows the same flow as a single Agent, with a different assignee type:

```bash
work=$(api "$SERVICE_URL/api/v1/issues" --data "$(jq -n \
  --arg tenant "$TENANT" --arg namespace "$NAMESPACE" --arg team "$TEAM_ID" \
  '{tenant:$tenant,namespace:$namespace,title:"Draft and review the weekly project report",
    description:"Read this weeks Workspace material. Produce report.md with sources and unresolved questions.",
    assigneeType:"team",assigneeRef:$team,access:{mode:"private"},
    acceptanceCriteria:["One consolidated Leader report","A disposition for every review finding"]}')")
ISSUE_ID=$(jq -r '.issue.id' <<<"$work")
api "$SERVICE_URL/api/v1/orchestration-runs" --get \
  --data-urlencode "tenant=$TENANT" --data-urlencode "namespace=$NAMESPACE" \
  --data-urlencode "issueId=$ISSUE_ID"
```

Service schedules the Leader asynchronously, creates delegated member tasks, and records execution relationships. Obtain a Run ID and call `GET /api/v1/orchestration-runs/{runId}/graph` for nodes, tasks, and attempts. Read progress and deliverables through the Issue's comment and Artifact APIs. After consolidation, [review the result](/v2/en/service/inbox) according to the Issue policy. A member finishing an execution does not mean the Team's objective is complete.

## Change membership and coordination

Read `GET /api/v1/teams/{teamId}` for current configuration. Add a member with `POST /api/v1/teams/{teamId}/members`, supplying `agentId`, `role`, and optional `instructions`. Use the returned `member.id` for subsequent changes or removal.

Update the Team name, instructions, or policy with `PATCH /api/v1/teams/{teamId}` with the current `expectedVersion`, `name`, `leaderAgentId`, and retained policy and description. Update a member role through `PATCH /api/v1/teams/{teamId}/members/{memberId}` with `expectedTeamVersion`. Follow existing tasks through their execution records; removing a member is not an operation to cancel running work.

## Expose the Team to applications

Publish a Job Endpoint when callers need a stable service address and separate invocation permissions. Use the [Endpoint management APIs](/v2/en/service/endpoints) to select `targetType:"team"` and the Team ID, create a release, and issue application credentials. Callers can then submit Invocations, subscribe to SSE, retrieve results, and cancel work without Agent or Team administration privileges.

Agent API therefore also serves Teams: the external invocation contract is shared, while internal delegation follows the Team's collaboration mechanism. Team execution is not a Managed Agent resumable session. Managed-specific checkpoint and file APIs do not automatically apply to every member runtime.

Use [Workflow APIs](/v2/en/service/workflows) when business logic fixes the steps, dependencies, and approval order. For UI operations, see [Console: Teams and orchestration](/v2/en/service/console/orchestration); for collaboration behavior, see the [Team reference](/v2/en/service/teams).

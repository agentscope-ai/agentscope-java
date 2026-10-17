---
title: "Team roles, members and policy settings"
zh_link: /v2/zh/service/team-configuration
---

<Note>
This page uses the `2.1.0-BETA1` prerelease.
</Note>

A Team has one Leader and a roster of available members. Use the [Team API guide](/v2/en/service/create-team) first, then maintain membership, coordination, and runtime policies with this reference.

## Management APIs

Paths are relative to Service. Local mode uses the default namespace without authentication headers. List requests require `tenant` and `namespace`; include them in the creation body. See the [Team API guide](/v2/en/service/create-team) for request examples.

| Operation | API | Request and response |
| --- | --- | --- |
| Create | `POST /api/v1/teams` | Require `tenant`, `namespace`, `name`, `leaderAgentId`; optionally include `members` and `policy`; returns `{team}` |
| List / read | `GET /api/v1/teams`, `GET /api/v1/teams/{id}` | Return `{items}` and `{team}` respectively |
| Update | `PATCH /api/v1/teams/{id}` | Require `name` and `leaderAgentId`; include current `expectedVersion`, retained `policy`, and `description`; returns `{team}` |
| Add member | `POST /api/v1/teams/{id}/members` | `agentId`, `role`, and optional member fields; returns `{member}` |
| Update member | `PATCH /api/v1/teams/{id}/members/{memberId}` | Require `role`, include `expectedTeamVersion`; update instructions, requirements, or runtime policy; returns `{member}` |
| Remove member | `DELETE /api/v1/teams/{id}/members/{memberId}` | Returns 204; does not cancel running tasks |

Team PATCH currently requires the name and Leader and applies the supplied policy, rather than merging arbitrary individual fields. Read the current definition first and retain fields you still need. Member updates likewise need retained configuration; they cannot change `agentId`. Remove and add a member to change its Agent.

Response `id`, `version`, and `members[].id` support later operations. `members[].agentId` identifies the registered Agent, not the membership record. Do not repeat the Leader in members; member Agents and roles must be unique.

## Team and member fields

| Level | Fields | Purpose |
| --- | --- | --- |
| Team | `name`, `description` | Identity and suitable work |
| Team | `leaderAgentId` | Receives work, coordinates and combines results |
| Team | `instructions` | Goals, delegation rules, failure handling and delivery requirements |
| Team | `status` | `active` or `disabled` |
| Member | `agentId` | Agent identity in the same scope |
| Member | `role` | Role label such as researcher or reviewer |
| Member | `instructions` | Responsibilities within this Team |
| Member | `capabilityRequirements` | Required dispatch capabilities |
| Member | `runtimeBindingPolicy` | Candidate runtimes and fallback policy for this member |

A Researcher should provide sources and findings; a Reviewer should identify evidence gaps. The Leader resolves disagreements, states unfinished work and submits one final result. Team roles do not replace the Agent's own model, tool or resource configuration.

## Coordination limits

| `policy` field | Controls | Zero-value meaning |
| --- | --- | --- |
| `maxActiveTasks` | Concurrent tasks | Default 32 |
| `maxHops` | Delegation hops | Default 8 |
| `maxFanout` | Recipients per delegation | No additional Team-specific limit |
| `maxChildDepth` | Child Issue depth | Default 4 |
| `maxChildIssues` | Child Issues per parent | No additional Team-specific limit |
| `maxTaskRetries` | Retries per task | No additional Team-specific limit |
| `allowExternalDelegation` | Delegation outside the roster | false |
| `allowMentionAll` | Delegation to the entire roster | false |
| `requireReview` | Human review according to execution policy | false |

Outside-roster delegation is unrelated to the External Agent runtime mode. An External Agent included in the roster is still a Team member.

The API can set explicit limits, and console forms can supply their own initial values. Inspect `team.policy` rather than treating every zero as unlimited or disabled. Numeric limits cannot be negative; maximum values are 64 for `maxHops`, 32 for `maxChildDepth`, and 256 for `maxFanout`. Platform limits still apply to individual operations.

A small-team policy fragment:

```json
{
  "policy": {
    "maxActiveTasks": 4,
    "maxFanout": 3,
    "maxHops": 4,
    "maxChildDepth": 2,
    "maxChildIssues": 8,
    "allowExternalDelegation": false,
    "allowMentionAll": false,
    "requireReview": true
  }
}
```

Team updates use API `expectedVersion` checks. Concurrency, depth and roster limits are not a hard cap on external model charges; manage usage through the actual providers and account policies.

Additional policy fields control delivery and resource consumption:

| Field | Meaning |
| --- | --- |
| `maxArtifactBytes`, `allowedArtifactMediaTypes` | Artifact size and allowed MIME types |
| `maxIssueTokens`, `maxIssueCostMicros` | Issue budget checks based on reported usage; cost is in micros |
| `issueSlaSeconds`, `taskTimeoutSeconds` | Default work deadline and task timeout |
| `secretPolicy`, `piiPolicy` | `allow` / `block` checks for collaboration content |

Budgets depend on runtime usage reporting. Content checks do not replace tool and data authorization. These fields are not instantaneous provider billing caps or comprehensive data-loss prevention.

## Mixing runtime modes

| Mode | Verify before adding |
| --- | --- |
| Managed | Model works, Environment/Memory/Vault bindings are correct, and a task completes |
| Hosted | Host is online, provider works, definition mapping passes and task MCP/CLI collaboration is available |
| External | Adapter implements dispatch and real completion/failure reporting; observation registration is insufficient |

Leaders need coordination capabilities: delegation, reading results and node completion/failure. Members need their assigned role's capabilities. Chat availability alone does not qualify an Agent as Leader.

## Runtime policy

<Accordion title="Retain candidates when updating the runtime policy">

Use the local URL and default scope variables from [API setup](/v2/en/service/create-managed-agent#api-setup).

Read the Agent policy first. This changes only fallback to disabled, retaining binding candidates, capability constraints, retries and concurrency settings. PUT replaces the complete policy and uses its current version. Member overrides use the member PATCH rather than replacing the global Agent policy.

```bash
POLICY_JSON=$(
  curl -sS --fail-with-body -G "$BASE_URL/api/v1/agent-runtime-policies/$AGENT_ID" \
    --data-urlencode "tenant=$TENANT" \
    --data-urlencode "namespace=$NAMESPACE"
)

jq '.policy | {tenant,namespace,agentId,candidates,selectionMode,fallbackMode,maxConcurrency,queueTimeoutSeconds,attemptTimeoutSeconds,retryPolicy,version}
  | .fallbackMode = "disabled"' <<< "$POLICY_JSON" > runtime-policy.json
```

```bash
curl -sS --fail-with-body -X PUT "$BASE_URL/api/v1/agent-runtime-policies/$AGENT_ID" \
  -H "Content-Type: application/json" \
  --data-binary @runtime-policy.json
```

</Accordion>

`runtimeBindingPolicy` contains ordered `candidates`, `selectionMode`, `fallbackMode` and optional `retryPolicy`. Each candidate's `binding` selects the backend; `requiredCapabilities` and `securityConstraints` constrain selection. Member overrides require explicit `selectionMode:"ordered"`, `fallbackMode:"disabled"` or `"fresh"`, and at least one valid candidate. Use actual runtime binding structures, not process IDs or arbitrary URLs.

Selection follows node override, member override and Agent policy layers. Explicit fresh fallback creates new execution context, so retain evidence in Issues, comments and Artifacts. Verify one candidate before adding fallbacks. Scaling and fallback do not resolve shared-file conflicts automatically.

Manage the Agent-level default with `GET/PUT /api/v1/agent-runtime-policies/{agentId}`. PUT accepts `tenant`, `namespace`, and nonempty `candidates`; each `binding.bindingId` must belong to an available binding of that Agent. Service resolves the actual backend. Optional controls include `selectionMode`, `fallbackMode`, `maxConcurrency`, `queueTimeoutSeconds`, `attemptTimeoutSeconds`, and `retryPolicy`; the response is `{policy}`. GET also requires tenant/namespace.

Next: [collaboration guide](/v2/en/service/create-team#team-collaboration) and [execution model](/v2/en/service/create-team#team-execution).

<span id="curl-management"></span>

## Maintain a team and its members

Use the platform identity and scope variables from [API setup](/v2/en/service/create-managed-agent#api-setup). Use resource IDs returned by creation or lookup.

```bash
curl -sS --fail-with-body -G "$BASE_URL/api/v1/teams" \
  --data-urlencode "tenant=$TENANT" \
  --data-urlencode "namespace=$NAMESPACE"
```

```bash
TEAM_JSON=$(
  curl -sS --fail-with-body "$BASE_URL/api/v1/teams/$TEAM_ID"
)
```

This changes only the team name, retaining its Leader, description and policy. Member changes also advance the version; reread before each save.

```bash
jq '.team | {name,description,instructions,status,leaderAgentId,policy,expectedVersion:.version}
  | .name = "Evidence review team"' <<< "$TEAM_JSON" > team-update.json
```

```bash
curl -sS --fail-with-body -X PATCH "$BASE_URL/api/v1/teams/$TEAM_ID" \
  -H "Content-Type: application/json" \
  --data-binary @team-update.json
```

Add a member using its Agent ID. Update and remove it using the returned member.id.

```bash
MEMBER_AGENT_ID="YOUR_MEMBER_AGENT_ID"
```

```bash
MEMBER_JSON=$(
  curl -sS --fail-with-body "$BASE_URL/api/v1/teams/$TEAM_ID/members" \
    -H "Content-Type: application/json" \
    --data-binary @- <<JSON
{
  "agentId": "$MEMBER_AGENT_ID",
  "role": "researcher",
  "instructions": "Collect evidence and cite sources."
}
JSON
)
MEMBER_ID=$(jq -er '.member.id' <<< "$MEMBER_JSON")
```

```bash
TEAM_JSON=$(
  curl -sS --fail-with-body "$BASE_URL/api/v1/teams/$TEAM_ID"
)

jq --arg member "$MEMBER_ID" '.team as $team | $team.members[] | select(.id == $member)
  | {role,instructions,capabilityRequirements,runtimeBindingPolicy,expectedTeamVersion:$team.version}
  | .instructions = "Collect evidence, cite sources and flag missing dates."' <<< "$TEAM_JSON" > member-update.json
```

```bash
curl -sS --fail-with-body -X PATCH "$BASE_URL/api/v1/teams/$TEAM_ID/members/$MEMBER_ID" \
  -H "Content-Type: application/json" \
  --data-binary @member-update.json
```

<Accordion title="Remove a member">

Run only when the member is no longer needed. Removal does not cancel its running tasks.

```bash
curl -sS --fail-with-body -X DELETE "$BASE_URL/api/v1/teams/$TEAM_ID/members/$MEMBER_ID"
```

</Accordion>

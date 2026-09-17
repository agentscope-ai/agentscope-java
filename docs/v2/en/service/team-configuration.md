---
title: "Team roles, members and policy settings"
---

<Note>
This is preview documentation. The official release is not yet available.
</Note>

A Team has one Leader and a roster of available members. [Create the Team](/v2/en/service/teams), then refine Roles & members, coordination policy and runtime policy.

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

The current creation form writes explicit values: concurrency 32, fanout 8, hops 8, child depth 8 and child issues 64. These creation values differ from zero-value handling above. Inspect the saved policy rather than interpreting every zero as unlimited or disabled.

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

## Mixing runtime modes

| Mode | Verify before adding |
| --- | --- |
| Managed | Model works, Environment/Memory/Vault bindings are correct, and a task completes |
| Hosted | Host is online, provider works, definition mapping passes and task MCP/CLI collaboration is available |
| External | Adapter implements dispatch and real completion/failure reporting; observation registration is insufficient |

Leaders need coordination capabilities: delegation, reading results and node completion/failure. Members need their assigned role's capabilities. Chat availability alone does not qualify an Agent as Leader.

## Runtime policy

`runtimeBindingPolicy` contains ordered `candidates`, `selectionMode`, `fallbackMode` and optional `retryPolicy`. Each candidate's `binding` selects the backend; `requiredCapabilities` and `securityConstraints` constrain selection. Use valid binding identities from the console/API, not process IDs or arbitrary URLs.

Selection follows node override, member override and Agent policy layers. Explicit fresh fallback creates new execution context, so retain evidence in Issues, comments and Artifacts. Verify one candidate before adding fallbacks. Scaling and fallback do not resolve shared-file conflicts automatically.

Next: [collaboration guide](/v2/en/service/team-collaboration) and [execution model](/v2/en/service/team-execution).

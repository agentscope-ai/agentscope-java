---
title: "Team: overview and creation"
zh_link: /v2/zh/service/teams
---

<Note>
This is preview documentation. The official release is not yet available.
</Note>

A Team organizes registered Agents into an assignable unit that can be published as an API. The Lead interprets the objective, chooses members and combines results. Members contribute specialist capabilities. Choose a [Workflow](/v2/en/service/workflows) for fixed ordering and branching rules.

Start with the [practical guide](/v2/en/service/create-team) for creation or connection. This reference section collects detailed configuration, supported capabilities and execution principles.

## In this chapter

- [Collaboration: delegation and delivery](/v2/en/service/team-collaboration)
- [Roles, members and policy](/v2/en/service/team-configuration)
- [Coordination and completion](/v2/en/service/team-execution)

## APIs and resource relationships

`POST /api/v1/teams` creates a Team, `GET /api/v1/teams/{teamId}` reads its definition, and `GET /api/v1/teams/{teamId}/overview` reads its overview. The list endpoint requires `tenant` and `namespace`. Management uses a user Bearer token and namespace authorization; save `team.id` for assignment and publication.

Assign work through `POST /api/v1/issues` with `assigneeType:"team"` and `assigneeRef`. Automation uses the same assignee form. A Workflow team node references `teamRef`. An Endpoint uses `targetType:"team"`, `targetRef`, and job mode. These field names belong to different resource contracts and are not interchangeable.

The Team roster defines available capabilities, not a fixed execution graph. For a report, a Researcher supplies facts and sources, a Reviewer checks evidence, and the Leader chooses when to involve them and consolidates delivery. Give each role independently verifiable output.

See the [Team API guide](/v2/en/service/create-team) for creation and invocation, [configuration parameters](/v2/en/service/team-configuration) for mutations, and [Console: Teams and orchestration](/v2/en/service/console/orchestration) for UI operations.

## Check readiness

| State | Meaning and next action |
| --- | --- |
| Ready | Configuration and member capabilities pass readiness checks; verify a small task |
| Degraded | Some members or capabilities are unavailable; inspect individual reasons |
| Unavailable | Effective collaboration cannot start; check the Lead and runtime dependencies |

Members can use different execution types. Before configuring Runtime policy or member overrides, check capabilities, runtime targets and security constraints. Additional candidates do not imply seamless session migration.

## Try and expose the team

Use the Issue API to assign a small task to the Team. Read discussion, Run graph, and execution results. Confirm that the Lead produces a combined deliverable and explains failures or missing information. Keep human acceptance for work needing review.

Use Teams from Issues, Automations or job Endpoints. After editing, verify a new execution; earlier executions retain their team snapshots for traceability.

Continue with [Team collaboration and extension](/v2/en/service/team-collaboration) · [Endpoints](/v2/en/service/endpoints).

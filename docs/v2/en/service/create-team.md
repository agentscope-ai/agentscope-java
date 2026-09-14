---
title: "Team: create and dispatch collaborative work"
---

<Note>
This is preview documentation. The official release is not yet available.
</Note>

Use a Team to let a Leader select members, delegate work and consolidate results. Verify each member can complete a small independent task before forming the Team.

## Create a review team

Prepare two task-capable Agents: a notes assistant to organize material and a reviewer to check evidence and missing information. Create a Team in **DESIGN → Teams**, select the notes assistant as **Leader Agent**, and add the reviewer under **Additional members**.

<Frame caption="Team creation with fixed demonstration data.">
  <img src="/imgs/service/teams.png" alt="Select a Team Leader and collaborating members" />
</Frame>

Use explicit coordination instructions:

```text
The Leader drafts the result, then delegates fact and completeness checks to the reviewer.
The reviewer returns issues and corrections. The Leader revises and delivers one consolidated result.
Identify unresolved information and complete team work after member tasks are handled.
```

Save and inspect readiness. See [Team configuration](/v2/en/service/team-configuration) when adjusting roles or policies.

## Dispatch from an Issue

[Create a console Issue](/v2/en/service/issues) with material, expected output and acceptance criteria, then select the Team. Inspect Task map and Executions for Leader delegation, member results and a consolidated final deliverable.

When human review applies, inspect and accept the result in [Inbox](/v2/en/service/inbox). A member completing an execution still requires the Leader to aggregate results and finish team work.

## Expose the Team to applications

Publish a Job Endpoint through **Connections → Publish as API**. Applications [submit an Endpoint request](/v2/en/service/endpoints), then [subscribe to SSE and query results](/v2/en/service/sse-events).

Use [Workflow](/v2/en/service/workflows) for fixed steps, branches or approval gates. The [Team reference](/v2/en/service/teams) covers member capabilities, delegation policies and execution principles.

Use [all-Hosted engineering](/v2/en/service/cases/sdlc-team) or [Managed presales](/v2/en/service/cases/presales-team) to verify delegation, review, and consolidation for code or knowledge deliverables.

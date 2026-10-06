---
title: "Team collaboration: delegate, combine and extend"
zh_link: /v2/zh/service/team-collaboration
---

<Note>
This is preview documentation. The official release is not yet available.
</Note>

Teams suit clear objectives whose implementation steps need adaptive decisions. The Lead chooses members, divides work and combines results. Workflows instead declare a fixed topology. Start with the [Team API guide](/v2/en/service/create-team).

## Design useful roles

For technical research, a Lead frames the question and combines the report, a Researcher supplies sourced facts, and a Reviewer checks evidence and omissions. Give each role a verifiable output and avoid unrestricted concurrent edits to the same files.

Members can mix Managed, Hosted and External execution when they support the required dispatch and collaboration capabilities. Chat availability does not prove coordinator capability. Verify members individually first.

## Follow collaboration through an Issue

Create a “Compare two deployment options” Issue through the API with constraints, material, acceptance criteria, and the Team assignee. Read the Lead's plan, member comments, child Issues, artifacts, and Run graph. Check how the Lead uses each result.

Issue holds objectives and acceptance; Run records the collaboration; Node represents a step; AgentTask is its assignment; Attempt is physical execution. Repeated executions retain their relationship to the same work and its evidence.

## Collaboration APIs for applications

Use a user Bearer token and authorization to the relevant work. These endpoints operate durable business records without requiring the caller to know each member's runtime.

| Operation | API and key parameters |
| --- | --- |
| Add input or request work | `POST /api/v1/issues/{issueId}/comments`: `content`, optional `parentId`, `type`, `mentions:[{type,ref}]` |
| Preview routing | `POST /api/v1/issues/{issueId}/comments/preview-routing`: the comment body; returns `targets` |
| Read discussion | `GET /api/v1/issues/{issueId}/comments`: `limit`, `cursor`, optional `threadId`, `rootsOnly`; returns `items`, `nextCursor` |
| Create a child objective | `POST /api/v1/issues/{issueId}/children`: title, description, assignee, and acceptance fields; returns `issue`, `agentTask` |
| Query child work | `GET /api/v1/issues`: `tenant`, `namespace`, `parentIssueId` |
| Deliver files | `POST /api/v1/artifacts/uploads`: multipart `tenant`, `namespace`, `issueId`, `relation`, `file` |
| Read artifacts | `GET /api/v1/issues/{issueId}/artifacts`, then `POST /api/v1/artifacts/{artifactId}/download` |

Set `mentions[].type` to `agent`, `team`, or `human`, with the corresponding identifier in `ref`. Names in prose are not structured routing. Inspect the returned `routes` for queued, merged, or blocked delivery; saving a comment alone does not prove new work started. Use `type:"progress"` without mentions for an informational record without implicit dispatch.

## Communicate durably

Use Comments and mentions for updates, questions and follow-ups, Artifacts for files and child Issues for independently tracked objectives. Do not keep the sole collaboration record inside a member's process. Track handled inputs so retries do not answer the same request twice.

Runtime Host injects scoped credentials and context into tasks. Shell-capable providers can use:

```bash
agentscope task context
agentscope issue current
agentscope task progress --content-file ./progress.md
agentscope task respond --content-file ./reply.md
agentscope artifact upload ./report.md
agentscope team current
agentscope task run graph
```

Run these inside the Host-created task environment, not an administrator shell using copied internal credentials. MCP providers use corresponding collaboration tools. Coordinators must explicitly complete or fail their node; an ordinary reply does not finalize orchestration.

## Policies and extension

Team Instructions define shared deliverables; member Instructions define specialist responsibilities. Runtime policy resolves node overrides before member overrides and Agent policy. Explicit fresh fallback reconstructs context from durable Issues, comments and artifacts; it does not migrate the original process or private session.

Add a member for a specific missing capability, then check that the Lead selects it correctly. Before increasing concurrency, consider shared-file conflicts, tool side effects and budgets.

## Finish and accept

Check that the Lead's final report incorporates necessary member results and identifies incomplete work. Run succeeded or partial_succeeded alone does not establish business completion. Human-review Issues still need acceptance through the [Issue review API](/v2/en/service/inbox).

For applications, publish a Team job [Endpoint](/v2/en/service/endpoints) and track its invocation status and result.

## Handoff template for independent review

When the [code repair service](/v2/en/service/cases/incident-to-pr) needs a multi-role Team, the Developer can use this structure with the PR and actual test records:

```text
Goal: implement order filtering and pagination; preserve checks and add boundary tests.
Changes: identify modified files and rules.
Validation: JDK, directory, command, exit code, logs, and CI links.
Delivery: GitHub Issue, PR, head SHA, review, and Artifact identifiers.
Open items: list unverified conditions, or state that none remain.
```

This is a delivery structure, not a record of execution. The Lead must open real Artifacts and inspect evidence before summarizing. Member working directories are not automatically shared; an absolute local path in a comment does not establish that another member can read it.

## Runtime reporting identity and fields

Execution adapters use task credentials issued by Service for protocol operations under `/api/v1/agent-tasks/{taskId}`. A user token does not represent the executing task. `GET .../context` returns current work context; `POST .../progress` accepts `content`, optional `parentId`, and `mentions`; `POST .../complete` can report `expectedVersion`, `summary`, `result`, `usage`, and processed input IDs. `POST .../fail` accepts `expectedVersion`, `code`, and `message`.

Hosted providers usually access these operations through injected CLI / MCP tools. External adapters follow the [task integration protocol](/v2/en/service/external-agent-execution). Coordinator node completion and failure require coordinator authority; reporting a member result does not grant Leader permissions.

For UI flows, see [Console: Teams and orchestration](/v2/en/service/console/orchestration) and [task feedback](/v2/en/service/console/tasks).

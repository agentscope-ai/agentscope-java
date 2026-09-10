---
title: "Team collaboration: delegate, combine and extend"
---

[简体中文](/v2/zh/service/team-collaboration)

Teams suit clear objectives whose implementation steps need adaptive decisions. The Lead chooses members, divides work and combines results. Workflows instead declare a fixed topology. Start with [Team configuration](/v2/en/service/teams).

## Design useful roles

For technical research, a Lead frames the question and combines the report, a Researcher supplies sourced facts, and a Reviewer checks evidence and omissions. Give each role a verifiable output and avoid unrestricted concurrent edits to the same files.

Members can mix Managed, Hosted and External execution when they support the required dispatch and collaboration capabilities. Chat availability does not prove coordinator capability. Verify members individually first.

## Follow collaboration through an Issue

Assign a “Compare two deployment options” Issue with constraints, material and acceptance criteria to the Team. Inspect the Lead's plan, member comments, child Issues, artifacts and Task map. Check how the Lead uses each result.

Issue holds objectives and acceptance; Run records the collaboration; Node represents a step; AgentTask is its assignment; Attempt is physical execution. Repeated executions retain their relationship to the same work and its evidence.

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

Check that the Lead's final report incorporates necessary member results and identifies incomplete work. Run succeeded or partial_succeeded alone does not establish business completion. Human-review Issues still need acceptance in Inbox.

For applications, publish a Team job [Endpoint](/v2/en/service/endpoints) and track its invocation status and result.

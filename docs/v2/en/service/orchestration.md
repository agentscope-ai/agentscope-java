---
title: "Multi-agent orchestration overview"
description: "Organize work with Subagents, Teams, and Workflows, combining Managed, External, and Hosted Agents in the same orchestration."
zh_link: /v2/zh/service/orchestration
---

When work needs several specialist capabilities, divide it into parts with clear responsibilities and deliverables, then let multiple Agents contribute. AgentScope Service supports delegation inside a managed task, persistent collaboration through a Team, and explicit steps and dependencies through a Workflow. Choose based on whether the work needs temporary delegation, continuing collaboration, or a defined process.

Members can be Managed Agents run by Service, existing External Agent applications, or Hosted Agents connected through a Runtime Host. Once registered in the same Agent catalog, they can participate in Teams and Workflows according to their capabilities. This lets existing business Agents and Coding Agents work with managed assistants.

Start with this overview to choose a collaboration approach, then complete an execution with [Create and run a Team](/v2/en/service/create-team) or [Orchestrate a Workflow](/v2/en/service/workflows). If an existing application or Coding Agent needs to participate, expand “Connect existing Agents” and start with the External registration or Hosted connection guide. Consult the SDK, runtime protocol, and configuration guides as needed. Once collaboration works, use “Work assignment and automation” to connect it to business acceptance, schedules, or messaging platforms.

## Choose a collaboration level

Use a HarnessAgent Subagent when part of the current managed task needs separate analysis or review. The main Agent delegates within its execution, receives a result through a child session, and continues the original task. See [Skills and Subagents](/v2/en/service/workspaces#skills) and [Sessions, tasks, and budgets](/v2/en/service/session-event-log) for configuration and observation. This delegation belongs to the Agent's runtime and does not automatically create a platform Team.

Create a Service Team when independent members need continuing assignment, communication, and delivery around a shared goal. The Lead assigns work, members collaborate through persistent tasks and discussion, and results can enter business acceptance. Members can use different runtimes, allowing managed assistants, specialist business Agents, and Coding Agents to collaborate. See [Create and run a Team](/v2/en/service/create-team) for roles and membership.

Use a Workflow when the steps and progression conditions are known. Its execution graph expresses dependencies, branches, and human approvals. Nodes can call Agents or Teams: a Team organizes members to fulfill one step's goal, while the Workflow determines when that step starts and what must happen before proceeding. See [Orchestrate a Workflow](/v2/en/service/workflows) for definition and publication.

## Start with a Team of Managed Agents

For the first collaboration test, create a Lead, Researcher, and Reviewer as Managed Agents and verify each one's model, tools, and resource access. Define their deliverables: the Researcher supplies sources and facts, the Reviewer checks whether conclusions are supported, and the Lead synthesizes member results while retaining open questions.

Follow [Create and run a Team](/v2/en/service/create-team) to add members and roles, then submit a small task. Inspect Issue discussion and the Run graph to confirm actual assignment, member results, and their use in synthesis. An authorized person reviews and accepts or rejects work requiring human acceptance.

Once collaboration works, add members or concurrency as needed. Before several members edit the same material, agree on file ownership and synchronization so each knows what it can change and how to hand results to the next member.

## Add existing capabilities when needed

Applications developed with the AgentScope SDK can [register an External Agent](/v2/en/service/register-agentscope-agent), keeping their processes and business logic while receiving assignments as External Agents. Other frameworks or existing business services can implement the [External SDKs and runtime protocol](/v2/en/service/external-agent) for task claims, events, results, and cancellation. A Team references the platform Agent ID, and its runtime binding routes work to the original application.

To reuse an installed Coding Agent provider, [connect a Hosted Agent](/v2/en/service/connect-hosted-agent). Its [Runtime Host](/v2/en/service/runtime-host) starts the provider, manages working directories, and reports execution records. See [Hosted Agent configuration](/v2/en/service/hosted-agent-configuration) for model and startup options. A member responsible for code changes can keep its execution environment while delivering results to the Team.

After registration, execute a small task independently and check support for the dispatch, collaboration, and controls the Team needs before adding the Agent or selecting it as a Workflow target. Catalog visibility or chat responses alone do not establish these capabilities. See [integration checks](/v2/en/service/api-reference#integrations).

External and Hosted Agents can also execute as direct Session Agent targets. Within a Team, a Managed Lead can coordinate the goal, an External member can provide internal business capabilities, and a Hosted member can handle code work. Applications continue submitting and observing work through the Session API.

<span id="publish-and-observe-collaborative-services"></span>

## Call and observe orchestration through the Session API

After validating collaboration, create a Session targeting the Team or Workflow. Publish a Workflow revision first; the Session pins that revision. Team Sessions retain the collaboration configuration selected at creation. Submit work, follow progress, and answer pending actions at the Turn level. Each Turn starts independent collaboration or workflow work. See [Integrate applications with the Session API](/v2/en/service/service-api) for the calling flow.

To observe a task, restore its snapshot and follow subsequent events. Inspect internal Issues and Runs when you need to understand a member's handling of an assignment. Read actual artifacts and business acceptance records when checking delivery. See [SSE and event replay](/v2/en/service/sse-events) and [Files and artifacts](/v2/en/service/files) for event display and file delivery.

Members do not automatically share a filesystem; exchange results through accessible Artifacts. Cancellation and retries do not undo external effects. Before changing runtimes, inspect member state and completed work. See [Team collaboration](/v2/en/service/create-team#team-collaboration) and [Execution reference](/v2/en/service/sessions) for constraints.

## Connect orchestration to business work

When work needs an assignee, discussion, and human acceptance, use an Issue to preserve its goal and delivery criteria and assign it to an Agent or Team. [Work assignment, approval, and acceptance](/v2/en/service/issues) connects execution records with the business decision to accept or return the work.

For recurring work or external events, configure [schedules and event triggers](/v2/en/service/automation) to assign work to an Agent or Team at a scheduled time or when a Webhook arrives. The rule determines when work starts, while the target's configuration determines execution. Automation cannot currently target a Workflow directly; applications should invoke a published Workflow revision through the Session API when they need a fixed process.

For requests from messaging platforms, follow [Connect messaging channels](/v2/en/service/channels) to configure message receipt, target routing, and result delivery. Feishu currently supports linking external messages to Issues and assigning them to Agents or Teams; this routing does not accept Workflow targets. Check each other adapter's capabilities separately, since a successful connection does not establish support for the same flow. Channels are configured independently rather than as Automation triggers. Associate external conversations, execution records, and acceptance results so users can follow the work.

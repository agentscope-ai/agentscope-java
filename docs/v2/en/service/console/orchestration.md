---
title: "Teams and orchestration"
zh_link: /v2/zh/service/console/orchestration
---

<Note>
This documentation is a preview. The official release is not yet available.
</Note>

When work needs several Agents, first decide whether the division of work is fixed. In a Team, a Leader delegates, coordinates, and combines member results according to the goal. A Workflow defines known steps, dependencies, and human gates. Both can be configured in the console or created and run through APIs.

This guide uses “organize materials and review the result” as an example. See the [Team API](/v2/en/service/create-team), [Workflow API](/v2/en/service/workflows), and [execution reference](/v2/en/service/sessions) for definitions and application integration.

## Create a Team

Prepare a notes assistant and a review assistant with task execution capabilities. Test a small task with each first. Open **Design → Teams → New team**, enter a name, select the notes assistant as Leader Agent, and add the review assistant under Additional members.

<Frame caption="Team creation with fixed demonstration data.">
  <img src="/imgs/service/teams.png" alt="Team Leader and member configuration" />
</Frame>

Expand **Advanced coordination instructions** and describe the collaboration:

```text
The Leader organizes the materials, then delegates fact and completeness checks to the review assistant.
The review assistant returns issues and suggested corrections. The Leader revises and delivers one consolidated result.
Identify unresolved questions and finish the Team work after member tasks have been handled.
```

Click **Create team** and check Team and member readiness. Members can use different runtime types, but each needs the task capabilities required for this work. Open **Team orchestration** to adjust the Leader, members, or instructions. See [Team configuration](/v2/en/service/team-configuration) for field details.

Next, open **Work → Issues → New issue**, supply materials and acceptance criteria, and choose the Team as owner. Inspect the Issue's executions and the Team's Activity to follow delegation, member results, and the Leader's final synthesis. A member finishing its own task does not necessarily finish the Team's work.

## Define fixed steps in a Workflow

If every run must follow “organize → human approval → final review,” use a Workflow to preserve that gate. Open **Design → Workflows**, create a workflow, enter its name, and select an Initial Workflow Agent. Start by verifying this single Agent step.

1. Open **Workflow design** and check the initial node key and target Agent.
2. Use **Add node** for approval and review nodes, then configure their dependencies in the connection settings.
3. Add input mappings as needed. For example, the CEL expression `run.input.request` reads material from the run input. Downstream fields should match actual upstream outputs.
4. Click **Save draft**, then **Validate**, and fix missing targets, unknown node references, or cycles.
5. Click **Publish saved draft**. Use **Run Workflow** to select a version, associate a new or existing Issue, provide JSON input, and start execution.

<Frame caption="Workflow designer with fixed demonstration data.">
  <img src="/imgs/service/workflows.png" alt="Workflow nodes, connections, and publishing controls" />
</Frame>

The designer supports Agent, Team, condition, join, approval, timer, signal, and subrun nodes. Add them as the goal requires. For example, use a signal node to wait for an external result, then submit data through the signal API or the available signal action in execution details.

Drafts are editable; published versions fix the process topology. Editing a draft does not change existing runs or automatically switch versions published to applications. Use the page's JSON editor for advanced fields; the definition still requires server validation.

## Follow and control execution

Open a run from Workflow history or the Issue's Executions to inspect node states, outputs, files, and errors. Handle human approval in the associated request or Inbox. Approval releases that gate; subsequent work still uses the target Agent's capabilities and permissions.

**Pause** prevents new nodes from being scheduled while active nodes may still return. **Resume** continues scheduling, and **Cancel** requests cancellation of tasks and child runs. After a terminal state, **Rerun** creates a new execution record linked to its source. Before rerunning, check whether it would repeat file writes, messages, or other business operations.

When execution ends, return to the Issue to inspect delivery and review status. Cancelling orchestration does not delete the Issue, and successful nodes do not constitute human acceptance.

## Publish for applications

Create a Job Endpoint under the Team's **Connections → Publish as API**. For a Workflow, publish a revision first, then publish a Job Endpoint for the selected version under **Connections**. Enter a Name and Slug and click **Create & publish**.

Open **Manage API → Security** and select an Application you manage under **Calling application**, or create one. Select the required scopes, enter a credential name, and click **Create**. Save the API key and use **Test API**; creating or publishing an Endpoint does not issue credentials. The [Application and credential API workflow](/v2/en/service/endpoints) operates on the same resources as these pages.

After submitting a Job, applications read results from the returned status URL and subscribe to progress at the events URL. Team delegation and Workflow scheduling follow the orchestration configuration; the application follows the published invocation contract.

Publishing another Workflow version requires an explicit Endpoint release update to switch the application entry point. See [Endpoints](/v2/en/service/endpoints) and [SSE events](/v2/en/service/sse-events). Continue with [Automations and channels](/v2/en/service/console/automation) to trigger work on a schedule or from external events.

---
title: "Console overview"
zh_link: /v2/zh/service/console/index
---

<Note>
This documentation is a preview. The official release is not yet available.
</Note>

The console is the graphical interface to AgentScope Service. Use it to configure Agents, try conversations, assign work, organize collaboration, and make human decisions. Its operations use the platform APIs and act on the same Agents, Issues, executions, and resources as your applications. Work created through an API can be followed in the console, and Agents created in the console can be called through APIs.

For application integration, start with the [quickstart](/v2/en/service/first-session) and [Service API](/v2/en/service/service-api). This module explains console workflows; the [API reference](/v2/en/service/api-reference) covers fields, authentication, and contracts.

## Find the right entry point

After signing in, check the current Namespace. The sidebar has three groups. Your permissions determine which menus and actions are available.

| Area | Entries | Purpose |
| --- | --- | --- |
| Work | Chat, Issues, Inbox, Automations | Converse, assign and follow work, handle feedback, and schedule triggers |
| Design | Agents, Teams, Workflows, Channels | Manage executors, collaboration, processes, and messaging connections |
| Resources | Workspaces, Environments, Memory, Vault | Configure files and capabilities, execution environments, memory, and credentials |

Overview summarizes current work. Access settings and Profile at the bottom provide account and access settings. If a resource is missing, check the Namespace and your access first.

## Follow a piece of work

Open **Design → Agents** to create or inspect an available Agent. Send a small request through **Work → Chat** to verify its model, tools, and execution environment. When the request needs an owner and a deliverable, open **Work → Issues**, describe the goal, materials, and acceptance criteria, and select an Agent or Team.

Follow discussion, files, and Executions from the Issue. Open **Work → Inbox** when a result needs review or an operation needs approval. Configure a Team when several Agents should divide the work; use a Workflow when the steps, dependencies, and approval gates are already known. The same resources, states, and results are available through APIs.

| Goal | Console guide | API guide |
| --- | --- | --- |
| Create, connect, and test Agents | [Manage Agents](/v2/en/service/console/agents) | [Create and register Agents](/v2/en/service/agents) |
| Converse, assign work, and give feedback | [Conversations, tasks, and feedback](/v2/en/service/console/tasks) | [Issues](/v2/en/service/issues) · [Inbox](/v2/en/service/inbox) |
| Configure Teams and Workflows, then publish services | [Teams and orchestration](/v2/en/service/console/orchestration) | [Teams](/v2/en/service/create-team) · [Workflows](/v2/en/service/workflows) |
| Schedule work or receive webhooks and messages | [Automations and channels](/v2/en/service/console/automation) | [Automations](/v2/en/service/automation) · [Channels](/v2/en/service/channels) |

## Prepare resources

Create resources under **Resources** as needed, then link them from the Agent configuration. A Workspace supplies rules, skills, and tools. An Environment determines where tools execute. Memory supports knowledge across conversations, and Vault stores credentials for external services. See the [Workspace](/v2/en/service/workspaces), [Environment](/v2/en/service/environments), [Memory](/v2/en/service/memory), and [Vault](/v2/en/service/vault) references for their configuration.

A path on the computer running your browser does not automatically become a file the Agent can read. Use resources accessible to the target Agent or upload work attachments. Resource bindings must also match the selected runtime's capabilities.

The console provides forms for common operations. Application registration, Runtime Host connection, and some advanced settings use the SDK, CLI, or APIs; the following guides identify those steps.

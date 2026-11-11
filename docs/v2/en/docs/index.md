---
title: What's AgentScope 2.0?
description: Build agents that keep working with Harness, then connect them to business applications, execution environments, and service APIs.
zh_link: /v2/zh/docs/index
---

**AgentScope 2.0 is an open-source framework for building agents that use models, tools, and business data to carry out tasks.** You can build a conversational assistant or an agent that researches information, works with files, runs code, delegates subtasks, and waits for human confirmation before continuing.

Configure a shared Builder at application startup, then create a new Agent with `builder.build()` for each request and close it after execution. Stable identities and persistent storage keep the conversation across instances. Start with the [Quickstart](/v2/en/docs/quickstart).

This documentation covers the Java version. Embed an Agent in an existing Java or Spring Boot application, start with a single call, and add context management, workspaces, persistent sessions, and collaboration as needed. AgentScope Service provides centralized deployment and APIs for other applications.

## What you can build

For customer support, knowledge lookup, and business assistance, combine conversation history, retrieved material, and current business data to answer questions. Tools can query orders, update tickets, or call existing systems. Your frontend can show responses, tool activity, and requests that need user input.

For research, data analysis, and code changes, an Agent can read materials in a workspace, make a plan, execute tools, and produce files. Delegate work to subagents when different expertise is useful. The framework also manages growing context, requirements added during execution, and the state needed to continue after an interruption.

For example, an Agent that analyzes customer feedback could read team guidelines, check facts through business tools, delegate product-line analysis to subagents, and produce a report. Users can add priorities along the way, confirm before the report is sent, or leave the page and return to its progress. **Harness combines the runtime capabilities behind these steps so developers can focus on business goals, tools, and execution boundaries.**

## How Harness keeps an Agent working

The ReAct loop—reason, execute tools, observe results—is the foundation of AgentScope Harness. `ReActAgent` provides reasoning, tools, permissions, and middleware. `HarnessAgent` combines workspace, context, and session management around the same loop, supporting continued execution in the three areas below. Start with `HarnessAgent` and configure the capabilities your application needs; see [Harness architecture](/v2/en/docs/harness/architecture).

### Keep track of long-running tasks

For code repairs, research, and other multi-step work, enable Todo to track progress. Add Plan Mode to investigate and write a plan before obtaining user confirmation to execute it. Independent work can run in subagents, synchronously or in the background. Plans, todos, and subtasks retain their state so later execution can follow up on unfinished work. When acceptance criteria matter, the application can also record task requirements and actual verification results to inform the next step.

Session Log records messages, tool calls, and interactions; checkpoints save the state needed to continue. Use `AgentSession` for background execution, queuing, additional requirements, and resuming interrupted work. The frontend first restores existing progress from the log, then receives new events. See [Plan Mode](/v2/en/docs/harness/plan-mode), [subagents](/v2/en/docs/harness/subagent), and [session operations and recovery](/v2/en/docs/harness/session-log).

### Manage context and control what the model sees

Before each model request, Harness assembles stable instructions, conversation history, current task state, and reference material. The workspace provides project rules, memory, knowledge, and Skills; dynamic sources supply current business facts at each reasoning step. Configure sources, priorities, and budgets to keep the input relevant to the current work. Memory and external material are treated as references; the application still checks permission to read business data.

As tasks grow, Harness saves oversized tool results to files, selects material within the context budget, and uses structured compaction to retain goals, key findings, and next steps. The model works with this smaller context while Session Log retains the complete execution history. Long-term memory carries useful information across sessions. See [context management](/v2/en/docs/harness/context), [workspaces](/v2/en/docs/harness/workspace), and [memory](/v2/en/docs/harness/memory).

### Execute tools with explicit controls

Java tools and MCP connect model requests to files, code, and business systems. The application configures available tools; the permission system uses rules, the current mode, and actual arguments to allow, deny, or request user confirmation. Operations requiring approval or execution by an external system can remain pending until a response arrives, then reasoning continues.

For code and commands, configure Docker or a remote sandbox and choose isolation by user or session. Tool implementations and execution backends enforce the actual file, network, and resource boundaries. Typed events and session logs expose tool calls, results, and approvals for display and tracing. See [tools](/v2/en/docs/building-blocks/tool), [permissions and human input](/v2/en/docs/building-blocks/permission-system), and [sandboxes](/v2/en/docs/harness/sandbox).

## Extend and deploy in your application

Model extensions connect different providers, with model selection, retries, and fallback strategies to suit each task. A shared message structure carries text, images, files, and tool results. Java tools and MCP connect business capabilities, while Middleware lets you add dynamic context, model-call policies, auditing, or tool checks. See [models](/v2/en/docs/building-blocks/model), [tools](/v2/en/docs/building-blocks/tool), and [Middleware](/v2/en/docs/building-blocks/middleware).

For multiple users and replicas, assign stable user and session identities, choose workspace isolation, and use persistent backends accessible to each replica. Workspace's Filesystem can connect to shared storage, and Session Log supports alternative backends. Native session writes use leases and writer fencing to control concurrency. Sandbox lifecycle and recovery depend on the selected execution backend. See [Going to production](/v2/en/docs/others/going-to-production) for deployment and storage choices.

## Offer APIs through AgentScope Service

[AgentScope Service](/v2/en/service/index) provides Agent registration, hosting, orchestration, and service publishing. Configure a platform-run Managed Agent, connect an Agent you deploy yourself, or integrate an existing Coding Agent. Agents with the required task capabilities can also join a Team or participate in a Workflow.

Publish an Agent, Team, or Workflow as an Endpoint. Applications use the unified Invocation API to submit work, retrieve results, receive SSE events, and handle pending actions. Frontends restore their view from snapshots and incremental events without depending on how a Team divides its work. For managed capabilities such as multi-turn sessions, files, child sessions, and checkpoints, use the Managed Agent API. See the [unified service API](/v2/en/service/service-api) and [Managed Agent API](/v2/en/service/session-event-log).

<Note>
AgentScope Service is currently a preview and has not reached a stable release. Building and running Agents with the Java SDK does not require deploying Service.
</Note>

## Where to start

Choose the entry point closest to your current needs, then add capabilities as the application grows:

| Your goal | Start here |
| --- | --- |
| Get a reply in a Java application, or use an Agent as a workflow step | Use `agent.call` in the [Quickstart](/v2/en/docs/quickstart) |
| Show responses and tool progress within the current request | Use `agent.streamEvents`; see [messages and events](/v2/en/docs/building-blocks/message-and-event) |
| Build a chat application with background work, queues, interactions, and recovery | Get an `AgentSession` through `agent.session(ctx)` and run the [resumable chat example](/v2/en/blogs/best-practices/session-chat) |
| Offer an Agent or Team to other applications over HTTP | Create an Endpoint with the [service publishing guide](/v2/en/service/service-api) |

For upgrades from 1.x, read the [V1 Migration Guide](/v2/en/docs/change-log). See [Release Notes](/v2/en/docs/others/release-notes) for individual versions.

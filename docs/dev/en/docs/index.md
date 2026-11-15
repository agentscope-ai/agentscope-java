---
title: What's AgentScope 2.0?
description: Build agents that keep working with Harness, then connect them to business applications, execution environments, and service APIs.
zh_link: /dev/zh/docs/index
---

<Note>
This is the dev documentation for the current development version. APIs and examples may differ from released packages. For AgentScope Java `2.0.4`, use the [2.0 documentation](/v2/en/intro).
</Note>


**AgentScope 2.0 is an open-source framework for building agents that use models, tools, and business data to carry out tasks.** You can build a conversational assistant or an agent that researches information, works with files, runs code, delegates subtasks, and waits for human confirmation before continuing.

Configure a shared Builder at application startup, then create a new Agent with `builder.build()` for each request and close it after execution. Stable identities and persistent storage keep the conversation across instances. Start with the [Quickstart](/dev/en/docs/quickstart).

This documentation covers the Java version. Embed an Agent in an existing Java or Spring Boot application, start with a single call, and add context management, workspaces, persistent sessions, and collaboration as needed.

## What you can build

For customer support, knowledge lookup, and business assistance, combine conversation history, retrieved material, and current business data to answer questions. Tools can query orders, update tickets, or call existing systems. Your frontend can show responses, tool activity, and requests that need user input.

For research, data analysis, and code changes, an Agent can read materials in a workspace, make a plan, execute tools, and produce files. Delegate work to subagents when different expertise is useful. The framework also manages growing context, requirements added during execution, and the state needed to continue after an interruption.

For example, an Agent that analyzes customer feedback could read team guidelines, check facts through business tools, delegate product-line analysis to subagents, and produce a report. Users can add priorities along the way, confirm before the report is sent, or leave the page and return to its progress. **Harness combines the runtime capabilities behind these steps so developers can focus on business goals, tools, and execution boundaries.**

## How Harness keeps an Agent working

The ReAct loop—reason, execute tools, observe results—is the foundation of AgentScope Harness. `ReActAgent` provides reasoning, tools, permissions, and middleware. `HarnessAgent` combines workspace, context, and session management around the same loop, supporting continued execution in the three areas below. Start with `HarnessAgent` and configure the capabilities your application needs; see [Harness architecture](/dev/en/docs/harness/architecture).

### Keep track of long-running tasks

For code repairs, research, and other multi-step work, enable Todo to track progress. Add Plan Mode to investigate and write a plan before obtaining user confirmation to execute it. Independent work can run in subagents, synchronously or in the background. Plans, todos, and subtasks retain their state so later execution can follow up on unfinished work. When acceptance criteria matter, the application can also record task requirements and actual verification results to inform the next step.

Native Thread journals retain messages, tool facts and checkpoints. [Durable](/dev/en/docs/harness/durable) admits, queues and recovers persistent tasks; frontends observe its public snapshots and events.

### Manage context and control what the model sees

Before each model request, Harness assembles stable instructions, conversation history, current task state, and reference material. The workspace provides project rules, memory, knowledge, and Skills; dynamic sources supply current business facts at each reasoning step. Configure sources, priorities, and budgets to keep the input relevant to the current work. Memory and external material are treated as references; the application still checks permission to read business data.

As tasks grow, Harness saves oversized tool results to files, selects material within the context budget, and uses structured compaction to retain goals, key findings, and next steps. The model works with this smaller context while Thread journal retains the complete execution history. Long-term memory carries useful information across sessions. See [context management](/dev/en/docs/harness/context), [workspaces](/dev/en/docs/harness/workspace), and [memory](/dev/en/docs/harness/memory).

### Execute tools with explicit controls

Java tools and MCP connect model requests to files, code, and business systems. The application configures available tools; the permission system uses rules, the current mode, and actual arguments to allow, deny, or request user confirmation. Operations requiring approval or execution by an external system can remain pending until a response arrives, then reasoning continues.

For code and commands, configure Docker or a remote sandbox and choose isolation by user or session. Tool implementations and execution backends enforce the actual file, network, and resource boundaries. Typed events and session logs expose tool calls, results, and approvals for display and tracing. See [tools](/dev/en/docs/building-blocks/tool), [permissions and human input](/dev/en/docs/building-blocks/permission-system), and [sandboxes](/dev/en/docs/harness/sandbox).

## Extend and deploy in your application

Model extensions connect different providers, with model selection, retries, and fallback strategies to suit each task. A shared message structure carries text, images, files, and tool results. Java tools and MCP connect business capabilities, while Middleware lets you add dynamic context, model-call policies, auditing, or tool checks. See [models](/dev/en/docs/building-blocks/model), [tools](/dev/en/docs/building-blocks/tool), and [Middleware](/dev/en/docs/building-blocks/middleware).

For multiple users and replicas, assign stable user and session identities, choose workspace isolation, and use persistent backends accessible to each replica. Workspace's Filesystem can connect to shared storage, and Thread journal supports alternative backends. Native session writes use leases and writer fencing to control concurrency. Sandbox lifecycle and recovery depend on the selected execution backend. See [Going to production](/dev/en/docs/others/going-to-production) for deployment and storage choices.

## Where to start

Choose the entry point closest to your current needs, then add capabilities as the application grows:

| Your goal | Start here |
| --- | --- |
| Get a reply in a Java application, or use an Agent as a workflow step | Use `agent.call` in the [Quickstart](/dev/en/docs/quickstart) |
| Show responses and tool progress within the current request | Use `agent.streamEvents`; see [messages and events](/dev/en/docs/building-blocks/message-and-event) |
| Build a chat application with background work, queues, interactions, and recovery | Use Durable Session/Thread/Turn through `runtime` and run the [resumable chat example](/v2/en/blogs/best-practices/session-chat) |

For upgrades from 1.x, read the [V1 Migration Guide](/dev/en/docs/change-log). See [Release Notes](/dev/en/docs/others/release-notes) for individual versions.

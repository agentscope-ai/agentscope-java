---
hide-toc: true
---

# AgentScope 2.0: From Transparent Development to System Engineering

AgentScope is an open-source framework for building agent applications, helping developers go from large language models to deployable agents. The challenge today is no longer just getting an agent to answer a single request — it is getting it to **finish tasks reliably**: handle long, multi-step workflows, call tools safely, and integrate cleanly with external systems.

To meet those needs, AgentScope 1.0 introduced the idea of *transparent development*: every step of how an agent receives messages, calls tools, and coordinates with collaborators should be visible to the developer, lowering the cost of understanding, debugging, and extending the system. AgentScope 2.0 carries that principle forward and adds a more serious focus on production realities — stable execution, controlled autonomy, and clean integration. The result is a more complete and more cohesive system-level upgrade.

![AgentScope 2.0 runtime core modules at a glance](/imgs/v2/as2-release-01.png)

## 1. Model Integration: Fault Tolerance on Top of an Open Ecosystem

![Model integration: open ecosystem + retry & fallback model](/imgs/v2/as2-release-02.png)

AgentScope 2.0 keeps the open model layer of 1.0 — Qwen, Anthropic, DeepSeek, Gemini, OpenAI — and extends it with providers such as Grok and Moonshot. But the real focus of 2.0 is not "more models." It is making model calls **stable under complex workloads**.

In real tasks, an agent typically runs many rounds of reasoning and triggers many tool calls. A single failed, timed-out, or unavailable model response can break the rest of the run. AgentScope 2.0 therefore introduces a unified retry and fallback-model mechanism at the model layer. Developers can configure the maximum number of retries and a fallback model; when the primary model fails, the framework can automatically try the fallback so the task keeps moving forward.

This upgrade means agents are no longer just "able to call a model" — they have a more robust runtime strategy for talking to models. For tasks that depend on continuous reasoning and multi-step execution, stability at the model layer becomes a foundation for whether the agent can finish the job at all.

## 2. Messages and Events: From Chat Strings to an Interactive Execution Stream

![Unified message structure → event stream → UI follows in real time](/imgs/v2/as2-release-03.jpg)

The complexity of agent applications shows up in messages too. In a chat app, a message can be a plain string. Inside an agent run, a single message may carry text, images, files, tool calls, tool results, model thoughts, user-confirmation states, or results from external execution. AgentScope 2.0 reworks the message module so that all of these flow through a unified `Content Block`. Within that, `DataBlock` supports both base64 and URL data sources, making it easier to align with the multimodal and file capabilities of different model APIs.

On top of that foundation, AgentScope 2.0 introduces an event system. A single agent reply is no longer just a final string — it can stream events such as model-call start, text deltas, tool calls, tool results, user confirmations, and external execution updates. This lets the front-end UI render the agent's work as it happens, and it turns human confirmation, human intervention, and external tool execution into first-class capabilities of the framework. For example, when the agent is about to invoke a sensitive tool, it can fire a user confirmation; when a tool runs in an external environment, the agent can wait for the external result and then continue the task.

So the upgrades to the message module and event system do more than reinforce AgentScope's "transparent" principle. They make the agent's execution **observable, interactive, and interruptible**. What developers see is no longer just a final answer — it is an execution stream that can be watched and steered as it unfolds.

## 3. Permission System: Bounded Autonomy

![Permission system: static rules + tool type + input analysis → allow / ask / deny](/imgs/v2/as2-release-04.png)

To get the most out of large models, agents need real autonomy: not just producing replies, but actively choosing tools, reading information, and taking actions based on how the task is progressing. The more autonomous the agent, the more important it becomes to draw a clear boundary around what it is allowed to do.

AgentScope 2.0 introduces a more systematic permission system that governs how agents call tools, read and write files, and execute commands. Tool calls are no longer a binary allow/deny decision. Based on static rules, the type of tool, and the actual inputs, AgentScope can decide whether an operation should be allowed, denied, or escalated to user confirmation. The filesystem tool checks for dangerous directories and sensitive files; the command tool analyses high-risk commands, dynamic shell constructs, and destructive deletions; for unknown or high-risk behavior, AgentScope can automatically route the call into a user-approval flow.

With this system, agents are no longer simply "able to call tools" — they can act autonomously **within an explicit and controllable boundary**. For complex tasks that depend on continuous tool use, file access, or command execution, that safety boundary becomes another foundation for whether the agent can complete the job stably.

## 4. Context Management Rebuilt: Supporting Long Tasks and Performance

![Context management: structured compaction + tool-result offloading + file-IO cache](/imgs/v2/as2-release-05.png)

Handling long tasks is one of the most important steps in bringing agent applications into the real world. A long task can involve many rounds of model calls, many tool results, large file contents, and ongoing user feedback. If context management stops at "compress history into the window," new problems show up quickly: which information should be kept, which tool results should be truncated, how to avoid re-reading the same file, how to keep the task state coherent across a long execution chain.

AgentScope 1.0 already provided context-management capabilities; in 2.0 those capabilities become more systematic. AgentScope 2.0 manages context together with task state, tool results, and file I/O: compaction is no longer a flat summary — it preserves the task goal, current state, key findings, the next plan, and the information that must be retained long-term in a structured way; tool results are truncated so that oversized logs, search results, or file contents do not blow up the context window; the built-in file tools add a read cache to cut down repeated I/O, and they require any edit-existing-file operation to first read the file, improving both performance and operational reliability.

In AgentScope 2.0, context management is no longer just "compress history." It is upgraded into **a system-level strategy for sustaining long-task execution**. It lets agents maintain task state in a more organized way, control how large the context becomes, and stay stable across continuous reasoning and repeated tool calls.

## 5. Middleware: A More Flexible Extension Surface

![Middleware: hook into key execution stages of the agent](/imgs/v2/as2-release-06.png)

Real-world agents often need their own logging, permission policies, business context, and model-call strategies. If every one of those capabilities has to be added by modifying the framework internals, the cost of extension goes up — and so does the risk to the framework's stability.

AgentScope 2.0 introduces a **middleware** mechanism that lets developers inject custom logic at the agent's key execution stages: log tracing before and after model calls, security checks before tool execution, business policy inside the reasoning and acting loop, and dynamic context injection at the system-prompt construction stage. Middleware lets AgentScope 2.0 keep the core framework stable while leaving enough room for the variations real applications demand. It is also how 2.0 continues 1.0's transparency principle: the framework is not a black box — developers can clearly understand it and step into it.

## 6. Workspace: Decoupling Execution Environment from Agent Logic

![Workspace: separate "what the agent does" from "where it runs"](/imgs/v2/as2-release-07.png)

As agents grow more capable, they are no longer only calling models — they also need to use tools, access files, load skills, talk to MCP services, and execute work in different environments. If all of that is baked into the agent's runtime logic, switching between a local environment, a container, or a cloud sandbox forces repeated adaptation. AgentScope 2.0 introduces the **Workspace** specifically to separate "what the agent does" from "where it runs," so that the execution environment becomes a replaceable, manageable system component.

The Workspace is AgentScope 2.0's abstraction for the agent's execution environment. It provides tools, MCP services, the Skills library, and context persistence, and it unifies different execution backends — local filesystem, Docker container, E2B cloud sandbox — behind a single interface. The same agent can run in any of these environments without changing its runtime logic. The design comes down to three points:

**First, a unified interface.** `WorkspaceBase` abstracts identity, lifecycle, resource discovery, context offloading, and dynamic resource management. Each backend only needs to implement the same set of interfaces to plug into AgentScope's execution system.

**Second, composition over coupling.** The agent itself does not depend on a specific Workspace. It uses tools and resources indirectly, through the Toolkit and the instructions it receives. This keeps the execution environment replaceable while keeping the agent's runtime logic stable and portable.

**Third, pooling support.** The Workspace ships with a warm-up pool: execution environments can be pre-initialized in batches, and the pool provides acquire / release / invalidate-and-replace operations. In scenarios such as parallel rollouts during RL training, this cuts the cost of creating environments repeatedly and improves resource reuse and task throughput.

With the Workspace, agents are no longer just "able to call tools." They can keep working inside **a replaceable, manageable execution environment**. For complex tasks that need to move between local machines, containers, and cloud sandboxes, this abstraction becomes another foundation for stable operation and flexible scaling.

## 7. Agent Service: From Local Scripts to a Deployable System

![Agent Service: standardized service interface + session log recovery + background tools](/imgs/v2/as2-release-08.png)

In this release, AgentScope 2.0 merges the Agent Service capability that previously lived in AgentScope Runtime into the main library. The reason is direct: real-world use cases do not need an agent that can only run in a terminal or a single Python process — they need an agent that can be called reliably from front-ends, external apps, and workflow systems.

Previously, when the agent's core logic and runtime service capabilities were spread across separate stacks, developers had to constantly stitch together local development, API packaging, state management, log recovery, sandbox execution, and deployment. By pulling those capabilities together, AgentScope 2.0 closes that gap, so that agents are built **from day one** in a way that is service-ready, recoverable, observable, and safe to run.

A standardized service interface lets the agent stream its execution progress to the outside, so that front-ends can display the run live. Session log recovery lets a task pick up after an interruption. Background tool execution lets long-running tools complete more reliably. The merger of Agent Service means agents are no longer just "able to run locally" — they can be deployed as stable services that plug into different applications and execution contexts, becoming the runtime support layer for an agent system.

## Closing: From Transparent Development to System Engineering

Continuing the *transparent development* principle of 1.0, AgentScope 2.0 organizes its system-level upgrades around a single goal: **letting agents finish tasks reliably**. From retry mechanisms at the model layer, to the decoupling and abstraction of the execution environment; from fine-grained permission boundaries, to standardized service integration — each upgrade is meant to address, system-wide, what real-world agents actually need: long-running execution, safe tool use, sustained task progress, and clean integration with external apps. With this release, AgentScope's focus shifts from "how to build an agent" toward "how to keep an agent running reliably."

Alongside this 2.0 release, AgentScope is also broadening its multi-language support. The [Python](https://github.com/agentscope-ai/agentscope) version has already been updated to 2.0 ([https://github.com/agentscope-ai/agentscope](https://github.com/agentscope-ai/agentscope)), and there is now a [TypeScript edition of AgentScope](https://github.com/agentscope-ai/agentscope-typescript) ([https://github.com/agentscope-ai/agentscope-typescript](https://github.com/agentscope-ai/agentscope-typescript)); the Java edition of AgentScope will also be updated to 2.0 shortly.

QwenPaw, an agent application built on AgentScope, will likewise be upgraded to the AgentScope 2.0 base in the near future, bringing users a more stable, safer, and more extensible agent experience.

AgentScope 2.0 is laying a more solid system foundation for super-agent applications.

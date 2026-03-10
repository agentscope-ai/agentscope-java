# Multi-Agent Overview

Multi-agent systems coordinate specialized agents or components to handle complex workflows. Not every complex task needs multiple agents—a single agent with the right tools and prompt can often suffice. This page summarizes when multi-agent patterns help and which patterns the project supports.

## Why multi-agent?

Multi-agent patterns are useful when you need one or more of the following:

- **Context management**: Expose specialized knowledge without overloading the model’s context. When context and latency are limited, you need ways to surface only what’s relevant per step or per agent.
- **Distributed development**: Let different teams own different capabilities (e.g. skills, sub-agents, specialists) and compose them behind clear boundaries.
- **Parallelization**: Run specialized workers for subtasks concurrently to reduce latency.
- **Structured workflows**: Enforce order (e.g. classify then route, or loop until a condition) or role-based handoffs (e.g. sales vs. support) that a single agent would not naturally enforce.

Multi-agent patterns are especially valuable when a single agent has too many tools and chooses poorly, when tasks need deep domain context (long prompts and domain-specific tools), or when you need sequential or state-driven routing (e.g. collect info before escalating, or switch who “owns” the conversation).

## Patterns supported

The project supports the following multi-agent patterns. Each has a dedicated page with implementation details and examples.

| Pattern | What it does | When to use it |
|--------|----------------|-----------------|
| **[Pipeline](pipeline.md)** | Runs agents in a fixed flow: **sequential** (A → B → C), **parallel** (same input to several agents, then merge), or **loop** (repeat a sub-pipeline until a condition). Built with Spring AI Alibaba flow agents and AgentScopeAgent. | You have a clear pipeline (e.g. NL → SQL → score, or one topic → multiple research angles → merged report). |
| **[Custom Workflow](workflow.md)** | You define the graph with **StateGraph**: sequential, conditional, or mixed **deterministic + agentic** steps (e.g. rewrite → retrieve → agent, or list_tables → get_schema → generate_query). Nodes can be functions or AgentScopeAgent. | Standard patterns don’t fit; you need multi-stage flow with explicit control or mix of non-LLM and LLM/agent steps. |
| **[Routing](routing.md)** | A **router** classifies the input and sends it to one or more specialist agents (e.g. GitHub, Notion, Slack); results are merged into one answer. | You have distinct verticals (knowledge domains) and want classify → specialists → synthesize in one shot or with a graph. |
| **[Skills](skills.md)** | **Progressive disclosure**: one agent sees skill names/descriptions and loads full skill content (e.g. `SKILL.md`) on demand via a tool (`read_skill`). No separate sub-agent process. | You want one agent with many specializations and need to avoid loading all domain text into context at once. |
| **[Subagents](subagent.md)** | A central **orchestrator** delegates work to **sub-agents** via tools (e.g. Task / TaskOutput). Sub-agents can be defined in Markdown or in code. The orchestrator holds the conversation; sub-agents are stateless per call. | You have multiple domains (e.g. codebase, web, dependencies), want one coordinator, and don’t need sub-agents to talk directly to the user. |
| **[Supervisor](supervisor.md)** | A central **supervisor** agent calls specialized agents as **tools** (one tool per specialist, e.g. `schedule_event`, `manage_email`). Specialists are stateless; only the supervisor’s reply is shown to the user. | You have clear domains (e.g. calendar, email) and want a single entry point that routes and combines specialist results. |
| **[Handoffs](handoffs.md)** | **State-driven routing**: tools update a state variable (e.g. `active_agent`); a graph uses it to route to different agent nodes. Each agent can “hand off” to another via a tool call. | You need role-based or sequential handoffs (e.g. sales ↔ support) where the active agent changes during the conversation. |
| **[Multi-Agent Debate](multiagent-debate.md)** | **Debaters** exchange arguments via MsgHub; a **moderator** evaluates with structured output and decides when the debate is finished. | You want multiple perspectives (e.g. reasoning tasks) and a single decision when to stop and what the answer is. |

## Choosing a pattern

At a high level, multi-agent patterns fall into **workflow** and **conversational** modes:

- **Workflow mode**: [Pipeline](pipeline.md), [Routing](routing.md), [Handoffs](handoffs.md), [Custom Workflow](workflow.md). The flow moves between agents or nodes; each node may interact with the user.
- **Conversational mode**: [Supervisor](supervisor.md), [Subagents](subagent.md), [Skills](skills.md). Agent decisions happen in a continuous dialogue context; usually only the main agent interacts with the user and returns the final result.

Other patterns (e.g. [MsgHub](../task/msghub.md), [Agent as Tool](../task/agent-as-tool.md), [Handoffs](handoffs.md), [Multi-Agent Debate](multiagent-debate.md)) can be combined with either mode as needed.

### Routing vs Supervisor

Both patterns dispatch work to multiple agents, but they differ in **how routing decisions are made**:

- **Routing**: A dedicated **routing step** (often a single LLM call or rule-based logic) classifies the current input and dispatches to one or more specialists. The router itself **does not maintain conversation history** or perform multi-turn orchestration—it is a **preprocessing** step. Use when you have clear input categories and want lightweight or deterministic classification, and one request should complete “classify → specialists → merge”.
- **Supervisor**: The **main supervisor agent** **dynamically decides** which specialist to call next (as tools) within an ongoing conversation. The main agent keeps context and can call different specialists across turns to orchestrate complex multi-step flows. Use when you need flexible, conversation-aware orchestration where the LLM decides the next step based on evolving context.

**When to choose**: Use **Routing** when input categories are clear and you want a single pass of classification and merge; use **Supervisor** when you need multi-turn dialogue and the main agent should schedule specialists based on context.

### Skills vs Subagents / Supervisor

The main difference is **whether context is isolated**:

- **Skills**: Skill content (e.g. `SKILL.md`) is **loaded on demand into the main agent’s context** via a tool (e.g. `read_skill`), **sharing the same dialogue context** as the main agent—no isolation. There is a single process and one context; the agent just pulls in domain text when needed. Use when you want “one agent, many specializations, load on demand” and do not need separate execution or isolated context.
- **Subagents / Supervisor**: Sub-agents or specialists run in **separate invocations or sessions**, **isolated** from the main agent’s (orchestrator or supervisor) dialogue context; each call can have its own system prompt and tool set, and results are aggregated back. Use when you need isolated execution, to avoid context pollution, or to restrict tools/permissions per specialist.

**When to choose**: Use **Skills** when you want to load multiple domains on demand in one conversation and are fine with shared context; use **Subagents** or **Supervisor** when you need dedicated sub-agents or specialists to run in isolation and then aggregate.

Use this as a quick guide; details and tradeoffs are on each pattern’s page.

| If you need… | Consider |
|--------------|----------|
| A fixed pipeline (sequence, parallel, or loop) | [Pipeline](pipeline.md) |
| Your own graph (deterministic + agentic steps, multi-stage) | [Custom Workflow](workflow.md) |
| One classification step then specialist(s) and merge | [Routing](routing.md) |
| Several agents seeing each other’s messages | [MsgHub](../task/msghub.md) |
| One agent to call another as a tool (with optional session) | [Agent as Tool](../task/agent-as-tool.md) |
| One agent, many specializations, load context on demand | [Skills](skills.md) |
| One orchestrator dispatching to many sub-agents via Task tools | [Subagents](subagent.md) |
| One supervisor with one tool per specialist (e.g. calendar, email) | [Supervisor](supervisor.md) |
| Control to move between agents (e.g. sales ↔ support) by tool | [Handoffs](handoffs.md) |
| Multiple debaters + a moderator with a clear stopping rule | [Multi-Agent Debate](multiagent-debate.md) |

**Combining patterns**: You can mix them. For example, a supervisor can use Agent as Tool for specialists; a subagent orchestrator can use Skills for on-demand context; a graph can use Handoffs for one part and Routing for another. Choose the pattern that best fits each part of your workflow.

## Summary

- Use **Pipeline** for predefined flows (sequential, parallel, loop).
- Use **Custom Workflow** when you need your own graph with mixed deterministic and agentic steps.
- Use **Routing** for classify → specialists → synthesize.
- Use **MsgHub** for group conversation or debate-style message sharing.
- Use **Agent as Tool** when a parent agent should invoke a sub-agent as a tool.
- Use **Skills** when one agent should load specialized prompts/content on demand.
- Use **Subagents** when one orchestrator delegates to many task-style sub-agents.
- Use **Supervisor** when one agent routes to specialists exposed as one tool each.
- Use **Handoffs** when the “active” agent changes via tool-driven state in a graph.
- Use **Multi-Agent Debate** when you want debaters plus a moderator and a clear end condition.

For implementation details, code samples, and example projects, see the linked pages for each pattern.

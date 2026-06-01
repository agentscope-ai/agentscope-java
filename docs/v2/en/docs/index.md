---
title: "What's AgentScope 2.0?"
description: "More secure, more efficient, more flexible, and more complete agent development."
---

AgentScope 2.0 is a major update to our agent framework, with a focus on improving the developer experience and making it easier to build and run agents in production.

:::{note}
AgentScope Java 2.0 is a breaking change from 1.0, with significant improvements in the core abstractions, APIs and architecture. We recommend users to migrate to 2.0 to take advantage of the new features and improvements.
:::

2.0 brings the following major changes and improvements:

- **Event System**: Every step the agent takes — text, thinking, tool call, tool result — is observable as a typed stream, so you can render rich, responsive UIs without writing adapters.
- **Execution Security**: Dangerous tool calls can be denied or held for review — the agent never silently touches the host or leaks credentials.
- **Human-in-the-loop**: Users can confirm or edit tool arguments mid-run, and sensitive actions can be handed off to your own backend instead of executed in-process, with the agent resuming exactly where it paused.
- **More Efficient**: Multi-tool steps finish faster through concurrent execution, oversized tool outputs no longer blow up the prompt, and transient provider failures fall back gracefully.
- **Middleware System**: The 1.x hook mechanism has been refactored into a 5-hook middleware stack, so you can compose logging, tracing, rate limiting, and dynamic system prompts onto any agent.

If you are still evaluating whether to migrate, check out the [Changelog](/v2/change-log) for a full breakdown of every change — it should give you everything you need to plan your migration to AgentScope 2.0.


::::{grid} 2

:::{grid-item-card} Event System
:link: /v2/building-blocks/message-and-event

Observe every step of the agent and stream it straight into your UI.
:::
	:::{grid-item-card} Execution Security
:link: /v2/building-blocks/permission-system

Gate dangerous tool calls before they touch the host.
:::
	:::{grid-item-card} Human-in-the-loop
:link: /v2/building-blocks/agent

Let users review and edit tool arguments before execution, or delegate sensitive actions to your own backend entirely.
:::
	:::{grid-item-card} Efficient Agent
:link: /v2/building-blocks/agent

Tool calls are auto-batched and run concurrently or sequentially based on each tool's properties.
:::
	:::{grid-item-card} Middleware System
:link: /v2/building-blocks/middleware

Compose logging, tracing, rate limiting and dynamic prompts onto any agent.
:::
	:::{grid-item-card} Observable Tool System
:link: /v2/building-blocks/tool

Unified ToolBase abstraction, annotation-driven registration, MCP adapter and Tool Group.
:::

::::

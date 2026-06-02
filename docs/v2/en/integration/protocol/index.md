# Agent Protocols

AgentScope offers a few protocol adapters to let an Agent talk to the outside world. Each solves a different problem:

| Extension | Protocol | Problem it solves |
| --- | --- | --- |
| [A2A](a2a.md) | [Agent-to-Agent](https://a2aproject.github.io/A2A/) | Agents calling each other / composing into multi-agent workflows |
| [AG-UI](agui.md) | [AG-UI Protocol](https://github.com/ag-ui-protocol/ag-ui) | Standardized event stream for front-end UIs |
| [Agent Protocol](agent-protocol.md) | [Agent Protocol](https://agentprotocol.ai/) | HTTP-based way for other systems to submit "tasks" |

> See also [Chat Completions Web](../ecosystem/chat-completions-web): expose your Agent behind an OpenAI-compatible API.

## Choosing one

- **Stream Agent events to a front-end UI (incl. ThinkingBlock)** → AG-UI
- **Let other backend systems schedule the Agent over REST** → Agent Protocol
- **Let multiple Agents (yours or third-party) call each other** → A2A

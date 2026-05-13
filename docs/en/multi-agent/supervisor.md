# Supervisor

In the **supervisor** pattern, a central **supervisor** (main agent) coordinates specialized agents by calling them as **tools**. The supervisor receives user requests, decides which specialist(s) to call (e.g. calendar, email), and synthesizes their results into one reply. Specialized agents are **stateless** from the user’s perspective; the supervisor keeps the conversation and delegates one-off tasks. This pattern is implemented in AgentScope via **Agent as Tool** ([agent-as-tool.md](../task/agent-as-tool.md)): `Toolkit.registration().subAgent()`.

## Overview

Key characteristics:

- **Centralized control**: All requests go to the supervisor; it routes to specialists via tool calls.
- **One tool per specialist**: Each specialized agent is registered as a single tool (e.g. `schedule_event`, `manage_email`) with a clear name and description so the supervisor knows when to call it.
- **No direct user interaction with specialists**: Specialists run in a focused context (instruction + request string) and return a result to the supervisor; only the supervisor’s reply is shown to the user.
- **Stateless specialists**: Each call creates a new sub-agent instance (or uses a fresh context); the supervisor holds conversation state.

Use the supervisor pattern when you have distinct domains (e.g. calendar, email, CRM), specialists don’t need to converse directly with the user, and you want a single entry point that delegates to the right agent(s).

## Architecture

```
User → Supervisor (ReActAgent with subAgent tools)
                │
                ├→ schedule_event(instruction) → Calendar agent (ReActAgent) → stub tools
                └→ manage_email(instruction)   → Email agent (ReActAgent)    → stub tools
```

- **Supervisor**: One ReActAgent with a Toolkit that registers each specialist via **Toolkit.registration().subAgent(provider)**. Tools appear as `schedule_event` and `manage_email` with a single string input (the user’s request for that domain).
- **Calendar agent**: ReActAgent with calendar-specific prompt and tools (e.g. `get_available_time_slots`, `create_calendar_event`). Exposed as the tool **schedule_event**.
- **Email agent**: ReActAgent with email-specific prompt and tools (e.g. `send_email`). Exposed as the tool **manage_email**.

In the example, calendar and email “APIs” are **stubbed** (`CalendarStubTools`, `EmailStubTools`); replace with real integrations in production.

## Implementation

1. **Model**: All agents use the same **DashScopeChatModel** (or other AgentScope `Model`); API key from `spring.ai.dashscope.api-key` or `AI_DASHSCOPE_API_KEY`.
2. **Specialist agents**: Build one ReActAgent per domain (calendar, email) with its own sysPrompt and Toolkit (stub or real tools). Register each with the supervisor’s Toolkit: `toolkit.registration().subAgent(() -> calendarAgent).apply()` and similarly for email. Optionally use **SubAgentConfig** to set `toolName` and `description`.
3. **Supervisor**: Build a ReActAgent with the same model and the Toolkit that contains the subAgent registrations. The supervisor’s system prompt should describe when to use `schedule_event` and `manage_email`.
4. **Invocation**: Inject the supervisor ReActAgent and call `supervisorAgent.call(userMsg).block()`. For multi-domain requests (e.g. “schedule a meeting and send an email”), the supervisor will call both tools and combine the results.

## Example Project

- **Location**: `agentscope-examples/multiagent-patterns/supervisor/`
- **Key classes**: `SupervisorConfig` (model, calendarAgent, emailAgent, supervisorAgent), `CalendarStubTools`, `EmailStubTools`, `SupervisorRunner` (optional demo).
- **Demo**: Set `supervisor.run-examples=true` to run two scenarios on startup: (1) single-domain: “Schedule a team standup for tomorrow at 9am”; (2) multi-domain: “Schedule a meeting with the design team next Tuesday at 2pm for 1 hour, and send them an email reminder about reviewing the new mockups.”

**Build and run** (from repo root):

```bash
./mvnw -pl agentscope-examples/multiagent-patterns/supervisor -am -B package -DskipTests
./mvnw -pl agentscope-examples/multiagent-patterns/supervisor spring-boot:run
```

**Use in your code**: Inject the supervisor ReActAgent (e.g. `@Qualifier("supervisorAgent")`) and call it with a user `Msg`; get text from the response with `getTextContent()`.

## Related Documentation

- [Agent as Tool](../task/agent-as-tool.md) - Sub-agent registration and SubAgentConfig
- [Subagents](./subagent.md) - Orchestrator with Task/TaskOutput and Markdown/API sub-agents
- [Pipeline](./pipeline.md) - Sequential and parallel agent composition

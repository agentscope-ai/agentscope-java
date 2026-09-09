# Core concepts

Separate access scope, reusable resources and execution location before interpreting tasks and execution records.

| Object | Purpose |
| --- | --- |
| Namespace | Resource ownership and membership permissions |
| Agent | Reusable identity, instructions, model, tools and execution configuration |
| Workspace | Files, skills and working material; distinct from a Namespace |
| Environment | Managed tool execution location: Local, Sandbox or Self-hosted |
| Runtime Host | A daemon on a machine that runs installed providers for Hosted Agents |
| Session | A continuing conversation and its event history |
| Issue | A work objective, discussion, deliverables and acceptance state |
| Team | A leader, members and collaboration policy |
| AgentTask | A directed work obligation |
| ExecutionAttempt | One physical attempt; retries can create new attempts |
| Run | An orchestration execution and its node relationships |

## How work flows

```{mermaid}
flowchart LR
    A[Person or external event] --> B[Issue / Session]
    B --> C[AgentTask / execution request]
    C --> D[Managed or external Runtime]
    D --> E[Events and deliverables]
    E --> B
```

An idle Session, a successful Attempt and an accepted Issue describe different outcomes. After an Agent responds, inspect its deliverables and the Issue review state.

Choose your path: [Managed Agents](agents.md), [Runtime Host](runtime-host.md) or [Application integration](integrations.md).

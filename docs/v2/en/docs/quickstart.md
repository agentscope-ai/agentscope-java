---
title: "Quickstart"
description: "Get started with AgentScope Java 2.0 — bring up your first long-running agent with HarnessAgent"
---

## Installation

AgentScope Java requires JDK 17 or newer. Maven 3.9+ is recommended.

### Maven dependency

`HarnessAgent` is the recommended entry point — it packages workspace, long-term memory, session persistence, subagents, sandboxes, and other engineering capabilities into one builder. Depending on `agentscope-harness` pulls `agentscope-core` in transitively:

```xml
<dependency>
    <groupId>io.agentscope</groupId>
    <artifactId>agentscope-harness</artifactId>
    <version>${agentscope.version}</version>
</dependency>
```

:::{note}
Substitute `${agentscope.version}` with the latest version. See [Release Notes](others/release-notes.md) for the latest version and full release details.
:::

If you only need a bare `ReActAgent` (no workspace / persistence / subagents / sandbox), depend on `agentscope-core` alone. The difference between the two is covered in [Harness Architecture](./harness/architecture.md).

The DashScope / OpenAI / Anthropic / Gemini / Ollama formatters and chat models all live inside `agentscope-core`. MCP integration requires the official MCP SDK — see `agentscope-examples/documentation/pom.xml` for a working example.

## Your first agent

The example below uses `HarnessAgent` to demonstrate three things at once: **workspace-driven persona** (`AGENTS.md`), **automatic session persistence** (the second turn with the same `sessionId` remembers the first), and **conversation compaction** (over-threshold compaction + long-term facts distilled into `MEMORY.md`). The model id is passed as a string to `.model(...)` — `ModelRegistry` resolves it and reads the matching API-key env var automatically.

```java
import io.agentscope.core.agent.RuntimeContext;
import io.agentscope.core.message.UserMessage;
import io.agentscope.harness.agent.HarnessAgent;
import io.agentscope.harness.agent.memory.compaction.CompactionConfig;
import java.nio.file.Paths;

public class FirstAgent {
    public static void main(String[] args) {
        HarnessAgent agent = HarnessAgent.builder()
                .name("note-taker")
                .sysPrompt("You are a note-taking assistant.")
                // String form resolved via ModelRegistry — picks up DASHSCOPE_API_KEY
                // from the environment. Use "openai:gpt-5.5", "anthropic:claude-sonnet-4-5",
                // "gemini:gemini-2.0-flash", or "ollama:llama3" to switch providers.
                .model("dashscope:qwen-plus")
                .workspace(Paths.get(".agentscope/workspace"))
                .compaction(CompactionConfig.builder()
                        .triggerMessages(30)
                        .keepMessages(10)
                        .build())
                .build();

        RuntimeContext ctx = RuntimeContext.builder()
                .sessionId("demo-session")
                .userId("alice")
                .build();

        // Turn 1: introduce yourself + state today's task
        agent.call(new UserMessage("My name is Alice, and I'm preparing a tech talk on ReAct today."), ctx).block();

        // Turn 2: same sessionId — state from turn 1 is restored automatically
        agent.call(new UserMessage("What is my name? What am I doing today?"), ctx).block();
    }
}
```

After this run you get two directory trees — the **workspace** and the **state store**:

```
.agentscope/workspace/                          ← workspace (agent content)
├── AGENTS.md                                   ← write one to give the agent its persona (optional)
└── agents/note-taker/
    └── sessions/                               ← never-compacted raw conversation log

~/.agentscope/state/note-taker/                 ← state store (outside workspace)
└── alice/demo-session/                         ← AgentState auto-saved / auto-loaded
    └── agent_state.json
```

`AgentState` lives **outside the workspace** at `~/.agentscope/state/<agentId>/` by default — because state is a prerequisite for restoring the workspace itself (e.g. after a sandbox wipe), so it must not be entangled with workspace data. Restart the process with the same `sessionId` and the second turn still remembers the first.

:::{warning}
The default `JsonFileAgentStateStore` is a local-file backend suitable for development and single-node deployment. For production clusters, use a distributed implementation such as `RedisAgentStateStore` (provided by `agentscope-extensions-redis`) or implement your own `AgentStateStore`. See [Going to Production](./others/going-to-production.md).
:::

After enough turns trip compaction, distilled facts first land in `workspace/memory/YYYY-MM-DD.md`, then a throttled background job merges them into `MEMORY.md`, which is injected into the system prompt on the next reasoning step.

### Streaming reasoning and tool calls

Swap `call(...)` for `streamEvents(...)` to receive incremental events — text deltas, tool calls, etc. — suitable for Web / TUI rendering:

```java
import io.agentscope.core.event.AgentEventType;
import io.agentscope.core.event.TextBlockDeltaEvent;
import io.agentscope.core.event.ToolCallStartEvent;

agent.streamEvents(new UserMessage("Summarize today in three bullets."))
        .doOnNext(event -> {
            if (event.getType() == AgentEventType.TEXT_BLOCK_DELTA) {
                // Streaming text fragment — append to UI or stdout
                System.out.print(((TextBlockDeltaEvent) event).getDelta());
            } else if (event.getType() == AgentEventType.TOOL_CALL_START) {
                // The agent is about to call a tool — surface the call info
                System.out.println("\n[tool] " + ((ToolCallStartEvent) event).getToolCallName());
            }
            // Other events: thinking blocks, tool results, reply end, etc.
        })
        .blockLast();
```

:::{tip}
Set `DASHSCOPE_API_KEY` in the environment before running. To switch providers, change the string passed to `.model(...)` and export the matching API key (`OPENAI_API_KEY`, `ANTHROPIC_API_KEY`, `GEMINI_API_KEY`). When you need explicit control over timeouts or custom endpoints, build the model with `DashScopeChatModel.builder()...build()` and pass it to `.model(Model)` instead.
:::

### Multi-user concurrency

The agent is **stateless between calls** — a single instance can handle requests from different users and sessions. Pass `userId` / `sessionId` via `RuntimeContext` and the agent automatically loads and isolates the corresponding conversation state:

```java
import io.agentscope.core.agent.RuntimeContext;
import io.agentscope.core.message.UserMessage;
import io.agentscope.harness.agent.HarnessAgent;
import io.agentscope.harness.agent.memory.compaction.CompactionConfig;
import java.nio.file.Path;
import java.nio.file.Paths;

// Create one agent instance at startup (singleton is fine)
HarnessAgent agent = HarnessAgent.builder()
        .name("note-taker")
        .sysPrompt("You are a note-taking assistant.")
        .model("dashscope:qwen-plus")
        .workspace(Paths.get(".agentscope/workspace"))
        .compaction(CompactionConfig.builder()
                .triggerMessages(30)
                .keepMessages(10)
                .build())
        .build();

// In your HTTP handler — different requests pass different RuntimeContexts
agent.call(new UserMessage(userInput), RuntimeContext.builder()
        .sessionId(sessionId)
        .userId(userId)
        .build()).block();
```

Calls targeting the same `(userId, sessionId)` are automatically serialized (no concurrent writes to one session); calls to different sessions run in parallel. For full production patterns (Redis session, sandbox, skill repositories), see [Going to Production](./others/going-to-production.md).

## Next steps

- [Agent](./building-blocks/agent.md) — full `ReActAgent` API, builder fields, `call` / `streamEvents` / `observe`, human-in-the-loop, `AgentStateStore` configuration
- [Harness Architecture](./harness/architecture.md) — how `HarnessAgent`'s capabilities cooperate, how state flows
- [Workspace](./harness/workspace.md) — `AGENTS.md` / `MEMORY.md` / `skills/` / `subagents/` / `tools.json` directory layout and loading model
- [Filesystem](./harness/filesystem.md) — local + shell / shared store / sandbox deployment modes

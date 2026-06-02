---
title: "Subagent"
description: "Declare subagents, sync/background calls, auto push-back, remote subagents, streaming forwarding"
---

## Role

Let the parent delegate "independent, context-heavy, parallelizable" tasks so it doesn't bloat its own loop. Each subagent is a transient instance (a local `HarnessAgent` or a remote stub), with its own session, returning a result via tool result.

## A minimal example

Simplest path: drop the spec into the workspace. The filename is the `agent_id`:

`workspace/subagents/reviewer.md`:

```markdown
---
description: Code-review specialist. Use when the user wants to review a PR, hunt for code issues, or check code style.
---

You are a subagent focused on code review. Follow this flow:
1. First read_file / grep_files to gather context
2. Give specific suggestions by file and line
3. End with an overall 1–5 score
```

The parent can now call it during reasoning:

```
agent_spawn agent_id="reviewer" task="review every change in this PR"
```

No registration step.

## Three ways to declare

Three sources are merged at build time:

| Way | Use for | How |
|-----|---------|-----|
| Built-in `general-purpose` | Generic fallback (mirrors parent capability) | Always present, no config |
| Workspace spec files | Project-specific, version-controlled | `workspace/subagents/<id>.md` |
| Programmatic declarations | Decided at runtime (remote, dynamic params) | `builder.subagent(SubagentDeclaration.builder()...)` |

### Workspace spec files

Non-recursive scan of `workspace/subagents/*.md`; the filename (minus `.md`) **is** the `agent_id` — **do not** also set `name` in the front matter.

```markdown
---
description: Code review specialist     # required, the model uses this to decide whether to delegate
workspace:
  mode: isolated              # default isolated; shared = use parent's workspace
  path: ./defs/reviewer       # optional; if absent, framework auto-creates a subdir
model: openai:gpt-4o-mini     # optional; inherits parent's if absent
steps: 8                      # optional; max iterations per spawn
temperature: 0.2              # optional; overrides parent GenerateOptions
top_p: 0.95                   # optional
hidden: false                 # true = not listed to the model (still callable programmatically)
mode: subagent                # primary / subagent / all (default all); primary can't be spawned
tools: [read_file, grep_files]   # optional; allowlist over inherited tools
---

You are a subagent focused on code review.
```

### Programmatic declarations

```java
HarnessAgent.builder()
    .name("orchestrator")
    .model(model)
    .workspace(workspace)
    .subagent(SubagentDeclaration.builder()
        .name("reviewer")
        .description("Code review specialist")
        .workspace(Path.of("./defs/reviewer"))
        .workspaceMode(WorkspaceMode.ISOLATED)
        .model("qwen3-max")
        .steps(8)
        .tools(List.of("read_file", "grep_files"))
        .build())
    .subagent(SubagentDeclaration.builder()
        .name("remote-researcher")
        .description("Remote research subagent")
        .url("http://agent-task-server:8080")     // remote subagent
        .headers(Map.of("Authorization", "Bearer xxx"))
        .build())
    .build();
```

Three sources are mutually exclusive: `workspace(...)`, `inlineAgentsBody(...)`, `url(...)` — pick one.

### Built-in `general-purpose`

No spec file needed; always available. Its role is "generic fallback" — it mirrors the parent's capability (same model, tools, skills) and shares the parent's workspace. Useful when the parent wants to isolate context for a sub-task without writing a dedicated spec.

## ISOLATED vs SHARED

`workspaceMode` decides what counts as the subagent's workspace:

- **ISOLATED** (default): the subagent has its own workspace (if `workspace.path` is omitted, the framework auto-creates a subdirectory). Subagent runtime state is bucketed per "parent sessionId × user" — so spawning the same subagent across different conversations of the same user doesn't cross-contaminate.
- **SHARED**: the subagent uses the parent's workspace directly. Good for cases where the subagent's output is read by the parent immediately (e.g. `general-purpose`).

## Sync or background?

The parent creates a subagent with `agent_spawn`; the key knob is `timeout_seconds`:

- `timeout_seconds > 0` (default 30, max 600) — **synchronous** call; the parent blocks on this step, result returns as the tool result.
- `timeout_seconds = 0` — **background** call; returns a `task_id` immediately, subagent runs in the background.

### Background tasks push back automatically

When a background task finishes, the parent **does not need to poll** — before the parent's next reasoning step, the framework injects completed task results as a system reminder at the end of the conversation:

```
<system-reminder>
Background tasks delivered:
- task_id=xxx, agent=research-analyst, status=COMPLETED
  result summary: ...
</system-reminder>
```

The parent naturally responds or continues. This means **you do not** write "remember to poll task_output" in your prompt — that was the old way.

> `task_output` / `task_cancel` / `task_list` still exist as escape hatches and debugging aids. Production prompts should not contain polling logic.

## Send a follow-up to an existing subagent

`agent_spawn` returns an `agent_key` (runtime instance handle). Use it (or your `label`) to send follow-up messages:

```
agent_send agent_key="agent:reviewer:abc-123" message="also check the schema changes"
```

To list active subagents: `agent_list`.

## Let the agent author new subagent specs

The `agent_generate` tool (**off by default**) lets the LLM draft a new subagent spec and write it to `workspace/subagents/<name>.md`:

```java
// Opt-in (at build time):
// Grab the builder's internal SubagentsMiddleware reference and call enableAgentGenerateTool
```

Useful when "halfway through, the agent realizes it needs a new kind of helper". Use with care in production — usually you'd have the agent draft the spec and have a human review before writing the file.

## Behavior notes

- **Write `description` well**: it's the model's primary signal for delegating. "Code review" is far less useful than "Use when the user wants to review a PR or check code style".
- **Recursion safety**: subagents cannot spawn further subagents (force-marked as leaves); plus a hard cap of 3 levels.
- **userId is propagated**: parent's `RuntimeContext.userId` is forwarded to the child, so the multi-tenant isolation chain stays intact.
- **Streaming forwarding**: during the parent's `stream()`, intermediate events from synchronous subagents are forwarded back into the parent's `Flux` live (with source tags); see [Subagent streaming](#subagent-streaming) below.

## Remote subagent

Just set `url` + optional `headers` and the subagent runs through a remote HTTP service (Agent Protocol):

```java
.subagent(SubagentDeclaration.builder()
    .name("remote-researcher")
    .description("Remote research subagent")
    .url("http://agent-task-server:8080")
    .headers(Map.of("Authorization", "Bearer xxx"))
    .build())
```

Same sync (`timeout_seconds>0`) / background (`timeout_seconds=0`) semantics apply.

## Background task storage

Background task state is written by default to `workspace/agents/<parentAgentId>/tasks/<sessionId>.json`. So:

- In shared-store mode (multi-replica) any node can read task state;
- Task execution **pins to the creating node**, but any node can read the result and push it back to the parent;
- Cancel from any node via `task_cancel` — the executing node polls the cancel flag and aborts.

## Delegating during Plan Mode

⚠ Current **known gap**: subagents spawned by a parent in Plan Mode **do not automatically inherit the read-only restriction**. To restrict the child: narrow `tools` in its declaration to a read-only set, or enable `enablePlanMode()` on the child's own builder.

## Subagent streaming

> Streaming basics: new code should prefer `streamEvents()` (returns `Flux<io.agentscope.core.event.AgentEvent>` — the v2 fine-grained event hierarchy that aligns with Python 2.0's `agent.reply_stream()`). The legacy `stream()` family that returns `Flux<Event>` is `@Deprecated(forRemoval = true)` as of 2.0.0 — see [Message & Event](../building-blocks/message-and-event.md) and [Changelog B.4](../change-log.md). This section covers `HarnessAgent`'s child-agent event forwarding behavior on both APIs.

### Picking your streaming API

| Use case | Recommended |
|----------|-------------|
| Parent-agent events only — text deltas, tool calls, lifecycle | **`streamEvents()`** (`Flux<AgentEvent>`) |
| **Live child-agent events** (subagent forwarding with `EventSource`) | `stream()` (`Flux<Event>`) — currently the only path |

The `AgentEvent` hierarchy does not yet expose an `EventSource`-equivalent channel for spawned subagents — that's on the v2 roadmap. Until it lands, callers that need live child-agent events must stay on the deprecated `stream()` API; parent-only consumers should switch to `streamEvents()` today.

### Parent events via `streamEvents()` (recommended)

```java
import io.agentscope.core.event.AgentEvent;
import io.agentscope.core.event.AgentEventType;
import io.agentscope.core.event.TextBlockDeltaEvent;
import io.agentscope.core.event.ToolCallStartEvent;

parent.streamEvents(new UserMessage(message), ctx)
    .doOnNext(event -> {
        // event is a typed io.agentscope.core.event.AgentEvent subclass
        if (event.getType() == AgentEventType.TEXT_BLOCK_DELTA) {
            System.out.print(((TextBlockDeltaEvent) event).getDelta());
        } else if (event.getType() == AgentEventType.TOOL_CALL_START) {
            ToolCallStartEvent start = (ToolCallStartEvent) event;
            System.out.println("\n[tool] " + start.getToolName());
        }
        // Other lifecycle events: AgentStartEvent / AgentEndEvent,
        // ModelCallStart/End, ToolResultStart/End, RequireUserConfirmEvent, etc.
    })
    .blockLast();
```

Child-agent events are **not** forwarded on this path today — anything spawned via `agent_spawn` / `agent_send` finishes silently and its final result arrives back to the parent as a `TOOL_RESULT` block.

### Child-agent forwarding via `stream()` (deprecated, only path today)

When you call the parent with `parent.stream()` and the parent invokes a child via `agent_spawn` / `agent_send` during reasoning, **every intermediate event the child produces is injected live into the parent's event stream**. Each event carries an `EventSource` field telling you whether it's from the parent or which subagent.

```
caller
  └─ parent.stream()                          ← @Deprecated(forRemoval=true), but the only API
        │                                       that forwards subagent events today
        ├─ parent REASONING chunks…           ← parent's first round (incl. tool call)
        │
        │  [agent_spawn "researcher" starts]
        ├─ child REASONING chunks…            ← child reasoning (live forwarded with EventSource)
        ├─ child TOOL_RESULT…
        ├─ child AGENT_RESULT (last)          ← child's final reply (live forwarded)
        │  [agent_spawn returns; result given to parent as TOOL_RESULT]
        │
        ├─ parent TOOL_RESULT…
        ├─ parent REASONING chunks…           ← parent's second round
        └─ parent AGENT_RESULT (last)         ← parent's final reply
```

Parent self-events: `source == null`. Child events: `source != null`.

#### Distinguishing by source

```java
// NOTE: stream(...) is @Deprecated(forRemoval=true). Kept here because it is currently
// the only API that forwards live subagent events. Migrate to streamEvents(...) once the
// AgentEvent subagent-source channel lands.
Flux<Event> events = parent.stream(msgs, StreamOptions.defaults(), ctx);

events.subscribe(event -> {
    EventSource src = event.getSource();
    if (src == null) {
        // parent self
        System.out.printf("[parent][%s] %s%n",
                event.getType(), event.getMessage().getTextContent());
    } else {
        // child (or grandchild)
        System.out.printf("[%s|depth=%d|path=%s][%s] %s%n",
                src.getAgentId(), src.getDepth(), src.getPath(),
                event.getType(), event.getMessage().getTextContent());
    }
});
```

Useful `EventSource` fields:

| Field | Meaning |
|-------|---------|
| `agentId` | Subagent type id (filename of `subagents/<id>.md`) |
| `agentKey` | Runtime instance handle; pass to `agent_send` |
| `agentName` | Display name (nullable) |
| `sessionId` | Subagent's call session id |
| `parentSessionId` | Parent agent's session id |
| `depth` | Nesting depth (parent's direct child = 1, grandchild = 2, etc.) |
| `path` | `/`-joined call path; stacks automatically for nesting, e.g. `sess-001/planner/executor` |

### Multi-level nesting (grandchildren)

A child can spawn a grandchild (subject to the 3-level hard cap). Grandchild events bubble up to the root parent; filter by `depth` or `path`:

```java
// Only first-level child REASONING events
events.filter(e -> e.getSource() != null
               && e.getSource().getDepth() == 1
               && e.getType() == EventType.REASONING)
      .subscribe(...);

// Events on a path containing "executor" at any depth
events.filter(e -> e.getSource() != null
               && e.getSource().getPath().contains("executor"))
      .subscribe(...);
```

### SSE forwarding

Pick the API that matches what your client needs:

**Parent-only events (recommended for most chat UIs):**

```java
@GetMapping(value = "/chat", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
public Flux<ServerSentEvent<String>> chat(@RequestParam String message,
                                          @RequestParam String sessionId) {
    RuntimeContext ctx = RuntimeContext.builder().sessionId(sessionId).build();
    return agent.streamEvents(new UserMessage(message), ctx)
            .map(event -> {
                Map<String, Object> payload = new LinkedHashMap<>();
                payload.put("type", event.getType().name());
                payload.put("id",   event.getId());
                if (event instanceof TextBlockDeltaEvent delta) {
                    payload.put("delta", delta.getDelta());
                } else if (event instanceof ToolCallStartEvent start) {
                    payload.put("toolName", start.getToolName());
                }
                return ServerSentEvent.<String>builder()
                        .data(objectMapper.writeValueAsString(payload))
                        .build();
            });
}
```

**Include child-agent events** (uses the deprecated `stream()` path — only option until the `AgentEvent` subagent-source channel lands):

```java
@GetMapping(value = "/chat", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
public Flux<ServerSentEvent<String>> chat(@RequestParam String message,
                                          @RequestParam String sessionId) {
    RuntimeContext ctx = RuntimeContext.builder().sessionId(sessionId).build();
    return agent.stream( // @Deprecated(forRemoval=true) — see note above
                    List.of(new UserMessage(message)),
                    StreamOptions.defaults(), ctx)
            .map(event -> {
                Map<String, Object> payload = new LinkedHashMap<>();
                payload.put("type", event.getType());
                payload.put("text", event.getMessage().getTextContent());
                payload.put("last", event.isLast());
                if (event.getSource() != null) {
                    payload.put("agentId", event.getSource().getAgentId());
                    payload.put("depth",   event.getSource().getDepth());
                    payload.put("path",    event.getSource().getPath());
                }
                return ServerSentEvent.<String>builder()
                        .data(objectMapper.writeValueAsString(payload))
                        .build();
            });
}
```

### Behavior boundaries

| Scenario | Live forwarding? |
|----------|------------------|
| `stream()` (deprecated) + synchronous local child (`timeout_seconds > 0`) | ✔ |
| `streamEvents()` (recommended) — any subagent | ✗ (parent events only; subagent channel on `AgentEvent` is a roadmap item) |
| `call()` mode (non-streaming) | ✗ (child result returns as a `tool_result` string) |
| `timeout_seconds = 0` background task | ✗ (terminal state is pushed back to the parent's next round) |
| Remote subagent (Agent Protocol) | ✗ |
| Multi-level nesting (grandchildren), `stream()` path | ✔ (`path` / `depth` stack automatically) |

### Error handling

When a child throws internally, the framework captures it and writes a `TOOL_RESULT` back to the parent. It **does not** propagate `onError` into the parent stream — child failures don't break the parent. If the parent stream itself errors, use standard Reactor semantics (`onErrorResume`, etc.).

## Related pages

- [Workspace](./workspace) — `subagents/` and `agents/<id>/tasks/` layout
- [Plan Mode](./plan-mode) — restrictions on subagents during the plan phase
- [Architecture](./architecture) — how parent and child cooperate
- [Message & Event](../building-blocks/message-and-event.md) — `AgentEvent` hierarchy (recommended) and the deprecated `Event` / `EventType` / `StreamOptions` types
- [Changelog B.4](../change-log.md) — `stream()` → `streamEvents()` deprecation timeline

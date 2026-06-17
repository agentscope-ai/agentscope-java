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
expose_to_user: true          # optional tri-state; force/forbid user exposure (omit = no opinion)
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

### Background task tools

Behind the scenes, subagent lifecycle is split across two groups of tools:

| Tool | Role |
|------|------|
| `agent_spawn` | Create a subagent and optionally run a task (sync or background) |
| `agent_send` | Send a follow-up message to an existing subagent |
| `agent_list` | List active subagent instances |
| `task_output` | Retrieve the result of a background task by `task_id` (blocking or non-blocking) |
| `task_cancel` | Cancel a running background task |
| `task_list` | List all background tasks with their current statuses |

`agent_spawn` / `agent_send` manage subagent **instances** (create, reuse, communicate); `task_output` / `task_cancel` / `task_list` manage background **task results** (check status, fetch output, cancel). The bridge between them is the `task_id` — returned by `agent_spawn` or `agent_send` when `timeout_seconds=0`.

> In most cases the auto push-back mechanism delivers results without any explicit tool call. The task tools are useful as escape hatches: checking progress before push-back fires, cancelling tasks that are no longer needed, or recovering task state after conversation compaction.

## Send a follow-up to an existing subagent

`agent_spawn` returns an `agent_key` (runtime instance handle). Use it or a `label` to send follow-up messages:

```
agent_send agent_key="agent:reviewer:abc-123" message="also check the schema changes"
```

If you set a `label` at spawn time, you can use that instead of the `agent_key`:

```
agent_spawn agent_id="reviewer" task="review the PR" label="pr-reviewer"
agent_send label="pr-reviewer" message="also check the schema changes"
```

To list active subagents: `agent_list`.

## Persistent sessions

By default every `agent_spawn` creates a fresh subagent with a new session — no memory of previous calls. Set `persistSession(true)` in the declaration to reuse the same subagent instance across multiple spawns:

```java
.subagent(SubagentDeclaration.builder()
    .name("note-taker")
    .description("Accumulates notes across the conversation")
    .persistSession(true)
    .build())
```

When `persistSession` is on, the framework derives a deterministic key from `(parentSessionId, agentId, label)`. If `agent_spawn` is called again with the same combination, the existing agent instance is reused — its conversation history and state are preserved.

## Exposing subagents to the user

Normally subagents are invisible to the user — they run behind the scenes as the parent's internal tools. With `expose_to_user=true`, the parent can make a subagent **directly addressable by the user** through the Channel:

```
agent_spawn agent_id="researcher" task="investigate AI trends" expose_to_user=true
```

This does two things:

1. **Registers the subagent in the Gateway** as a user-addressable entry point
2. **Emits a `SubagentExposedEvent`** into the streaming event flow, carrying a `subagentId` handle

The user's client receives the `SubagentExposedEvent`, and can then send messages directly to the subagent — bypassing the parent agent entirely:

```java
// Client-side: listen for exposed subagents in the event stream
chat.sendStream(SendOptions.userId("user-1"), "Spawn a researcher to investigate AI trends")
    .doOnNext(event -> {
        if (event instanceof SubagentExposedEvent se) {
            // se.getSubagentId() → use this to talk directly to the subagent
            // se.getAgentId()    → subagent type (e.g. "researcher")
            // se.getLabel()      → optional human-readable name
        }
    })
    .blockLast();

// Send a message directly to the exposed subagent
chat.sendToSubagent(subagentId, "Focus on LLM agents specifically").block();
```

This is useful for "branch-off" scenarios: the parent spawns a specialist, and the user continues the conversation with that specialist independently. See [Channel — Talking to exposed subagents](./channel#talking-to-exposed-subagents) for the full Channel-side API.

### How to enable

Use `agent.channel(...)` — the bridge is wired automatically, zero configuration:

```java
HarnessAgent agent = HarnessAgent.builder()
    .name("orchestrator")
    .model("dashscope:qwen-plus")
    .build();

// channel() creates the internal gateway and wires the bridge — expose_to_user just works.
ChatUiChannel chat = agent.channel(ChatUiChannel.create());
```

Without a Channel binding, `expose_to_user=true` in `agent_spawn` is silently ignored — the subagent still works normally, just not exposed to the user. For multi-agent setups with `GatewayBootstrap`, see [Channel — Thread exposure with GatewayBootstrap](./channel#thread-exposure-with-gatewaybootstrap).

### Controlling exposure from code

Relying on the LLM to pass `expose_to_user=true` is not always flexible enough. You can override the decision from application code in two ways, and the effective value is resolved with this precedence (highest first):

1. **`RuntimeContext` per-call override** — applies to every `agent_spawn` in the current call
2. **`SubagentDeclaration` per-type policy** — a static default for that subagent type
3. **The LLM's `expose_to_user` tool argument**
4. **`false`** when none of the above expresses an opinion

**Per-call override via `RuntimeContext`.** Put a `Boolean` (or its string form) under the `AgentSpawnTool.CTX_EXPOSE_TO_USER` key:

```java
RuntimeContext ctx = RuntimeContext.builder()
    .userId("user-1")
    .put(AgentSpawnTool.CTX_EXPOSE_TO_USER, true)   // force on; false forbids exposure
    .build();
```

**Per-type policy on the declaration.** Use the tri-state `exposeToUser` — `TRUE` always exposes, `FALSE` never exposes (overriding an LLM `expose_to_user=true`), and `null` (default) defers to the context override and then the LLM argument:

```java
SubagentDeclaration decl = SubagentDeclaration.builder()
    .name("researcher")
    .description("Investigates topics and returns a synthesized report.")
    .exposeToUser(true)   // this subagent type is always user-addressable
    .build();
```

Or in a Markdown subagent spec's front matter (also tri-state — omit the key for "no opinion"):

```markdown
---
name: researcher
description: Investigates topics and returns a synthesized report.
expose_to_user: true
---
```

This lets you force or forbid exposure regardless of what the model decides, while still allowing the LLM to choose when neither code source expresses an opinion.

### Across restarts and multiple replicas

By default the exposure is in-process: the `subagentId` is only valid on the node that created it and is lost on restart. To make an exposed subagent resolvable on **any replica** and **across restarts**, build the agent with a `distributedStore(...)` — the same one-liner used for state and filesystem:

```java
HarnessAgent agent = HarnessAgent.builder()
    .name("orchestrator")
    .model("dashscope:qwen-plus")
    .distributedStore(RedisDistributedStore.fromJedis(jedis))
    .build();

ChatUiChannel chat = agent.channel(ChatUiChannel.create());  // recovery wired automatically
```

The `subagentId` is persisted in the store, and the subagent's own conversation is reloaded from the distributed `AgentStateStore` by session — so the user keeps talking to the *same* subagent even if a later message lands on a different node. For multi-agent `GatewayBootstrap`, pass `.distributedStore(...)` (otherwise it inherits the main agent's). Deployment guidance — including routing a `subagentId` back to its live node (sticky routing) — is in [Going to Production](../others/going-to-production.md).

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
- **Permission inheritance**: all DENY permission rules from the parent are automatically propagated to the child. If the parent is denied a tool, the child is also denied — the security boundary cannot be bypassed by delegation. Set `inheritParentPermissions(false)` in the declaration to opt out.
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

When the parent is in Plan Mode, spawned subagents **automatically inherit the read-only restriction**. The child enters Plan Mode at spawn time, so it cannot perform write operations — the safety boundary is maintained across the delegation chain.

## Subagent streaming

> New code should use `streamEvents()` (returns `Flux<AgentEvent>`). The legacy `stream()` family (`Flux<Event>`) is `@Deprecated(forRemoval = true)` since 2.0.0 — see [Message & Event](../building-blocks/message-and-event.md) and [V1 Migration Guide B.4](../change-log.md).

When the parent calls a synchronous subagent via `agent_spawn` / `agent_send`, the child's intermediate events are **forwarded live** into the parent's `streamEvents()` stream. Each child event carries a `source` field (a `/`-separated path like `"main/researcher"`) so you can tell parent events (`source == null`) from child events.

```
caller
  └─ parent.streamEvents(msg, ctx)
        │
        ├─ AGENT_START                            ← parent starts
        ├─ TEXT_BLOCK_DELTA …                     ← parent reasoning
        ├─ TOOL_CALL_START "agent_spawn"
        │
        │  [child spawned]
        ├─ AGENT_START          (source="main/researcher")  ← child starts
        ├─ TEXT_BLOCK_DELTA …   (source="main/researcher")  ← child reasoning
        ├─ TOOL_CALL_START …    (source="main/researcher")
        ├─ TOOL_RESULT_END …   (source="main/researcher")
        ├─ AGENT_END            (source="main/researcher")  ← child done
        │  [agent_spawn returns; child result → parent TOOL_RESULT]
        │
        ├─ TOOL_RESULT_END                        ← parent receives tool result
        ├─ TEXT_BLOCK_DELTA …                     ← parent second round
        └─ AGENT_END                              ← parent done
```

### Using `streamEvents()` (recommended)

```java
parent.streamEvents(new UserMessage(message), ctx)
    .doOnNext(event -> {
        String src = event.getSource();
        String prefix = (src != null) ? "[" + src + "] " : "";

        if (event.getType() == AgentEventType.TEXT_BLOCK_DELTA) {
            System.out.print(prefix + ((TextBlockDeltaEvent) event).getDelta());
        } else if (event.getType() == AgentEventType.TOOL_CALL_START) {
            System.out.println(prefix + "[tool] " + ((ToolCallStartEvent) event).getToolCallName());
        } else if (event.getType() == AgentEventType.AGENT_START) {
            if (src != null) System.out.println("── child started: " + src);
        } else if (event.getType() == AgentEventType.AGENT_END) {
            if (src != null) System.out.println("── child finished: " + src);
        }
    })
    .blockLast();
```

Distinguish parent vs child events:

```java
// parent events only
events.filter(e -> e.getSource() == null).subscribe(…);

// child events only
events.filter(e -> e.getSource() != null).subscribe(…);

// events from a specific child
events.filter(e -> e.getSource() != null && e.getSource().contains("researcher")).subscribe(…);
```

### SSE forwarding

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
                if (event.getSource() != null) {
                    payload.put("source", event.getSource());
                }
                if (event instanceof TextBlockDeltaEvent delta) {
                    payload.put("delta", delta.getDelta());
                } else if (event instanceof ToolCallStartEvent start) {
                    payload.put("toolName", start.getToolCallName());
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
| `streamEvents()` + synchronous local child (`timeout_seconds > 0`) | ✔ |
| `call()` mode (non-streaming) | ✗ (child result returns as `tool_result` string) |
| `timeout_seconds = 0` background task | ✗ (result pushed via reverse notification to parent's next round) |
| Remote subagent (Agent Protocol) | ✗ |

### Error handling

When a child throws internally, the framework captures it and writes a `TOOL_RESULT` back to the parent. It **does not** propagate `onError` into the parent stream — child failures don't break the parent. If the parent stream itself errors, use standard Reactor semantics (`onErrorResume`, etc.).

## Related pages

- [Channel](./channel) — `expose_to_user`, `SendOptions`, direct user-to-subagent messaging
- [Workspace](./workspace) — `subagents/` and `agents/<id>/tasks/` layout
- [Plan Mode](./plan-mode) — restrictions on subagents during the plan phase
- [Architecture](./architecture) — how parent and child cooperate
- [Message & Event](../building-blocks/message-and-event.md) — `AgentEvent` hierarchy (recommended) and the deprecated `Event` / `EventType` / `StreamOptions` types
- [V1 Migration Guide B.4](../change-log.md) — `stream()` → `streamEvents()` deprecation timeline

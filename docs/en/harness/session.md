# Session

## Purpose

Enable the agent to restore state across requests, process restarts, and multi-user scenarios. After each `call()` ends, two outputs are automatically persisted on parallel tracks:

- **StateModule snapshot** (`Memory`, `ToolExecutionContext`, and other serializable state) — defaults to `WorkspaceSession`
- **Conversation JSONL** (LLM context + full history) — goes through `SessionTree`, triggered by `MemoryFlushManager.offloadMessages`

The two are **parallel, independent paths**.

## Trigger Points

| When | Action |
|------|--------|
| `agent.call(msg, ctx)` | `bindRuntimeContext` passes `ctx.session/sessionKey` to `delegate.loadIfExists` to restore StateModule |
| `PostCallEvent` / `ErrorEvent` | `SessionPersistenceHook` (priority 900) calls `agent.saveTo(session, sessionKey)` — saves on both success and failure |
| Compaction / `PostCallEvent` flush | `MemoryFlushManager.offloadMessages` appends to `<sessionId>.jsonl` + `.log.jsonl` |
| End of session | `WorkspaceManager.updateSessionIndex` updates `sessions.json` for `session_list` queries |

## Key Logic

### Dual-Track Storage Layout

```{mermaid}
graph LR
    Call[agent.call] --> Hook[SessionPersistenceHook]
    Hook -->|saveTo / loadIfExists| WS[(WorkspaceSession<br/>StateModule snapshot)]
    Call --> Compact[CompactionHook / MemoryFlushHook]
    Compact -->|offloadMessages| ST[(SessionTree<br/>JSONL dual files)]
    WSWrite[WorkspaceManager<br/>updateSessionIndex] --> Idx[(sessions.json<br/>session index)]
    Compact --> WSWrite
```

```
workspace/agents/<agentId>/
├── context/                          ← managed by WorkspaceSession
│   └── <sessionId>/
│       ├── memory.json               ← ReActAgent.memory snapshot
│       └── *.json                    ← other StateModule serialization artifacts
└── sessions/                         ← managed by SessionTree + WorkspaceManager
    ├── sessions.json                 ← session index (sessionId / summary / updatedAt)
    ├── <sessionId>.jsonl             ← LLM-visible compacted context
    └── <sessionId>.log.jsonl         ← full conversation log (append-only, never compacted)
```

- **`context/`**: `WorkspaceSession` extends `JsonSession`, base at `agents/<agentId>/context/`; each `StateModule` is stored per `SessionKey → {key}.json` in the sessionId subdirectory.
- **`sessions/`**: `SessionTree` organizes a JSONL file as a `id/parentId` tree; the paired `<sessionId>.log.jsonl` is **never compacted**, used for auditing and `session_search`.

### How `RuntimeContext` Aligns the Two Tracks

```java
RuntimeContext ctx = RuntimeContext.builder()
    .sessionId("sess-001")
    .userId("alice")
    .build();

agent.call(msg, ctx).block();
```

`HarnessAgent.bindRuntimeContext` does several things:

1. **Fill defaults**: if `session` is null, use the `defaultSession` from build time (defaults to `WorkspaceSession(workspace, agentId)`); if `sessionKey` is null, try `SimpleSessionKey.of(sessionId)` → `SimpleSessionKey.of(agentName)` in order.
2. **Distribute to hooks**: `workspaceContextHook`, `memoryFlushHook`, `sessionPersistenceHook`, `compactionHook` all sync to this ctx — they can read `sessionId` during offload / saveTo.
3. **Link `userIdRef`**: an `AtomicReference<String>` is updated to `userId`; the default `NamespaceFactory → List.of(userId)` uses this as a path prefix, enabling transparent multi-tenant isolation.
4. **Pre-load state**: if both `session` and `sessionKey` are present, calls `delegate.loadIfExists` to overwrite current Memory. Does nothing if not found.

### Default vs Custom Session

```java
// 1. Default: nothing passed → WorkspaceSession(workspace, agentId)
HarnessAgent.builder()
    .name("MyAgent").model(model).workspace(workspace).build();

// 2. Use a JsonSession at a specific path
HarnessAgent.builder()
    ...
    .session(new JsonSession(Path.of("/custom/sessions")))
    .build();

// 3. Override per call
agent.call(msg, RuntimeContext.builder()
        .sessionId("sess-001")
        .session(customSession)
        .sessionKey(SimpleSessionKey.of("sess-001"))
        .build())
    .block();
```

### Multi-User Isolation at Two Levels

- **Session level**: `sessionId` determines that `context/<sessionId>/` and `sessions/<sessionId>.jsonl` are independent.
- **File level**: `userId` + `NamespaceFactory` determines the file operation path prefix (the default `LocalFilesystemWithShell` reads `userIdRef`).

```java
// Serve alice and bob from the same agent instance
agent.call(msg, RuntimeContext.builder().sessionId("alice-1").userId("alice").build()).block();
agent.call(msg, RuntimeContext.builder().sessionId("bob-1").userId("bob").build()).block();
// Session state and file paths for the two users do not interfere with each other
```

### Session Index

After `MemoryFlushManager.offloadMessages` completes, `WorkspaceManager.updateSessionIndex(agentId, sessionId, summary)` merges a write into `sessions/sessions.json`. In another turn, the agent can use the `session_list` tool to see "what conversations this agent has had historically".

## Related Pages

- [Tool](./tool.md) — `session_search` / `session_list` / `session_history` parameters
- [Memory](./memory.md) — when `offloadMessages` is called, and how it feeds back into `memory_search`
- [Filesystem](./filesystem.md) — `userIdRef` + `NamespaceFactory` multi-tenant path isolation
- [Architecture](./architecture.md) — `SessionPersistenceHook` position in `PostCallEvent` / `ErrorEvent`

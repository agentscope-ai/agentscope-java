---
title: Architecture
---

[Overview](/v1/en/docs/harness/overview) introduces Harness capabilities through the lens of "what problem they solve". This page takes a different angle: **explaining the architecture itself** — why it is designed this way, what each layer is responsible for, what happens during a `call()`, and how state flows through the system.

---

## 1. Design Philosophy

Understanding the Harness architecture starts with three core decisions.

### Decision 1: Thin Wrapper, Not a New Reasoning Loop

`HarnessAgent` is not a new reasoning engine — it is a **thin wrapper** around `ReActAgent` that does exactly two extra things:

- **`bindRuntimeContext(ctx)`**: at the start of each `call()`, distributes the current identity (`sessionId`, `userId`) to interested hooks and restores Memory state from the Session as needed;
- **`forceCompactAndRetry`**: if the model actually returns a `ContextOverflow` error, forces compaction and retries once.

Everything else — workspace injection, memory management, session persistence, subagent orchestration — is injected through ReActAgent's existing **Hook** and **Toolkit** extension points. The benefit: all of ReActAgent's capabilities are preserved unchanged; Harness only adds, never replaces.

### Decision 2: Hook-Driven, Orthogonal Capabilities

Each hook has a single responsibility and its execution order within the same event is determined by `priority`:

- `CompactionHook(10)` checks whether history needs compaction before each reasoning turn;
- `SubagentsHook(80)` injects the subagent list before reasoning;
- `WorkspaceContextHook(900)` is the last to run — because it assembles the final system prompt and must layer on top of all preceding processing.

Hooks **hold no references to each other** and communicate only through three shared objects. Each capability can be independently toggled: `compaction` requires explicit configuration, `session persistence` is on by default, `toolResultEviction` is opt-in.

### Decision 3: Shared Objects Are the Only Coupling Point

All hooks collaborate through the same "common language":

| Object | Responsibility | Lifecycle |
|--------|---------------|-----------|
| `RuntimeContext` | Current `call()` identity: sessionId, userId, session reference, extra data | Re-injected on every `call()`, **not persisted** |
| `WorkspaceManager` | Stateless workspace accessor: two-layer reads (filesystem first → local fallback), writes go through filesystem | Created at build time, reused across calls |
| `AbstractFilesystem` | Storage backend: local disk / sandbox / KV store, pluggable | Created at build time, reused across calls |

---

## 2. Top-Level Architecture Diagram

```mermaid
graph TD
    USER(["Caller\nagent.call(msg, ctx)"])

    subgraph HA["HarnessAgent  ·  Thin Wrapper Layer"]
        BRC["① bindRuntimeContext(ctx)\ndistribute ctx · loadIfExists restores Memory"]

        subgraph RA["ReActAgent  ·  Reasoning Core"]
            HOOKS["Hook Chain\nby priority ascending\nintercepts lifecycle events"]
            LOOP["ReAct Loop\nreason → act → observe"]
            TK["Toolkit\nFilesystemTool · MemorySearch\nAgentSpawnTool · TaskTool · ..."]
            MEM["Memory\n(InMemoryMemory)"]
            HOOKS <-.->|event-driven| LOOP
            LOOP <-->|tool invocation| TK
            LOOP <-->|read/write context| MEM
        end

        OVF["③ forceCompactAndRetry\nContextOverflow safety net"]
        BRC --> RA --> OVF
    end

    subgraph SO["Shared Objects  ·  Common Language for Hook Collaboration"]
        RC["RuntimeContext\nsessionId / userId / extra"]
        WM["WorkspaceManager\nAGENTS · MEMORY · knowledge\nskills · subagents"]
        AFS["AbstractFilesystem\nlocal · sandbox · remote KV"]
    end

    USER -->|"② call(msg, ctx)"| BRC
    HOOKS <-->|"ctx + read/write"| SO
    TK <-->|"file / shell ops"| AFS
    MEM <-.->|"session persistence"| RC
```

**Three layers, one glance**:

- **Thin wrapper layer** (HarnessAgent): per-call identity binding and extreme-case recovery;
- **Reasoning core** (ReActAgent): Hook event pipeline + ReAct loop + tool execution;
- **Shared objects layer**: three objects that serve as the collaboration substrate for all hooks — belonging to no hook, read/written by all.

---

## 3. Build Phase (`Builder.build()`)

Capability injection happens once, during the **build phase**. After `build()` completes, the hook chain and toolkit composition are fixed for the lifetime of the agent:

```mermaid
graph LR
    B["HarnessAgent.Builder.build()"]

    B -->|"create"| SO2["Three Shared Objects\nWorkspaceManager\nAbstractFilesystem\nRuntimeContext (ref)"]

    B -->|"assemble in priority order"| HK["Hook Chain\n[0] AgentTraceHook\n[5] MemoryFlushHook\n[6] MemoryMaintenanceHook\n[10] CompactionHook  ✗ opt-in\n[50] SandboxLifecycleHook  ✗ opt-in\n[50] ToolResultEvictionHook  ✗ opt-in\n[80] SubagentsHook\n[900] WorkspaceContextHook\n[900] SessionPersistenceHook"]

    B -->|"append built-in tools"| TK2["Toolkit\nuser tools + built-in tools\n(SubagentsHook registers its tools via tools())"]

    B -->|"load from workspace/skills/"| SK["SkillBox\nauto or AgentSkillRepository"]

    B -->|"hand off to"| RA2["ReActAgent.builder()\n→ final product: delegate"]

    B -->|"start background"| BG["MemoryMaintenanceScheduler\ndaemon thread, 6h cycle"]
```

> **✗ opt-in** hooks are only assembled when conditions are met: `CompactionHook` requires `.compaction(...)`; `SandboxLifecycleHook` requires `filesystem(SandboxFilesystemSpec)`; `ToolResultEvictionHook` requires `.toolResultEviction(...)`.

---

## 4. Hook Event Pipeline

`ReActAgent` fires events at key points in the ReAct loop; hooks execute in **ascending priority order** at their subscribed events. The complete Hook × Event matrix:

| Event | When | Hooks that fire (priority ascending) |
|-------|------|--------------------------------------|
| `PreCallEvent` | Before reasoning loop starts | `AgentTraceHook`(0) |
| `PreReasoningEvent` | **Before each** model call | `AgentTraceHook`(0) → `CompactionHook`(10) → `SubagentsHook`(80) → `WorkspaceContextHook`(900) |
| `PostReasoningEvent` | After each model response | `AgentTraceHook`(0) |
| `PreActingEvent` | Before each tool call | `AgentTraceHook`(0) |
| `PostActingEvent` | After each tool call | `AgentTraceHook`(0) → `ToolResultEvictionHook`(50) |
| `PostCallEvent` | After final reply is produced | `AgentTraceHook`(0) → `MemoryFlushHook`(5) → `MemoryMaintenanceHook`(6) → `SessionPersistenceHook`(900) |
| `ErrorEvent` | When an exception occurs | `AgentTraceHook`(0) → `SessionPersistenceHook`(900) |

The priority arrangement reflects design intent:

- **0**: pure logging, always first, never modifies events;
- **5/6/10**: memory and compaction, handling context lifecycle outside the reasoning loop;
- **50**: sandbox lifecycle and tool result offloading, handled in-place during acting;
- **80**: subagent injection, before workspace context — because subagent information must appear inside the system prompt;
- **900**: final system prompt assembly (`WorkspaceContextHook`) and persistence (`SessionPersistenceHook`) — ensuring they layer on top of all prior processing, and that memory is flushed before snapshotting.

---

## 5. `call()` Lifecycle Sequence

```mermaid
sequenceDiagram
    autonumber
    actor User
    participant HA as HarnessAgent
    participant RA as ReActAgent
    participant H as Hooks (priority ↑)
    participant M as Model
    participant T as Toolkit

    User->>HA: call(msg, ctx)
    HA->>HA: ① bindRuntimeContext(ctx)<br/>distribute ctx · loadIfExists restores Memory

    HA->>RA: delegate.call(msg)
    RA->>H: PreCallEvent → Trace(0)

    loop ReAct loop (until no tool calls)
        RA->>H: PreReasoningEvent
        Note over H: Compact(10): if threshold → flushMemories + LLM distill + replace memory<br/>Subagents(80): inject subagent list<br/>WorkspaceCtx(900): inject AGENTS/MEMORY/KNOWLEDGE

        RA->>M: stream(messages)
        M-->>RA: ChatResponse

        RA->>H: PostReasoningEvent → Trace(0)

        opt contains tool_calls
            loop each tool_call
                RA->>H: PreActingEvent → Trace(0)
                RA->>T: invoke(toolCall)
                T-->>RA: ToolResult
                RA->>H: PostActingEvent
                Note over H: Eviction(50): > 80K chars → write to disk + replace with placeholder
            end
        end
    end

    RA->>H: PostCallEvent
    Note over H: Trace(0) · MemFlush(5): flush facts + offload JSONL<br/>MemMaint(6): requestConsolidation<br/>Session(900): saveTo(session, key)

    RA-->>HA: final Msg
    HA-->>User: ② final Msg

    Note over HA: Failure path: ErrorEvent → Session(900) saveTo<br/>ContextOverflow: ③ forceCompactAndRetry → delegate.call retry
```

---

## 6. State Flow

State in Harness has three layers, from shortest to longest lived:

```mermaid
graph LR
    subgraph INCALL["In-call\nalive for one call()"]
        IM["Memory\n(InMemoryMemory)\nmessage sequence for this turn"]
        RC2["RuntimeContext\nsessionId · userId · extra"]
    end

    subgraph CROSSCALL["Cross-call\npersistent within same sessionId"]
        SP["WorkspaceSession\nagents/&lt;id&gt;/context/&lt;sess&gt;/*.json\nMemory snapshot + StateModule"]
        JSONL["sessions/&lt;sess&gt;.log.jsonl\nfull conversation log (append-only)"]
    end

    subgraph LONGTERM["Long-term\naccumulates across sessions"]
        DAILY["memory/YYYY-MM-DD.md\ndaily fact log (append-only)"]
        MMEM["MEMORY.md\ncurated long-term memory (full rewrite)"]
        FTS["memory_index.db\nSQLite FTS5 full-text index"]
    end

    IM -- "PostCallEvent\nMemoryFlushHook.flush()" --> DAILY
    IM -- "PostCallEvent\nSessionPersistenceHook.saveTo()" --> SP
    IM -- "compaction / offload\nMemoryFlushHook.offload()" --> JSONL

    DAILY -- "background 6h\nMemoryConsolidator" --> MMEM
    DAILY -- "after incremental write\nMemoryIndex" --> FTS

    SP -- "next call() start\nbindRuntimeContext + loadIfExists" --> IM

    MMEM -- "every PreReasoningEvent\nWorkspaceContextHook" --> IM
    FTS -- "agent invokes\nmemory_search tool" --> IM
```

**Core pattern**:
- `Memory` is the in-call "working memory", persisted via two paths when `call()` ends;
- `WorkspaceSession` ensures "the next call with the same sessionId still remembers this turn";
- `MEMORY.md` + FTS index ensures "long-term facts survive session boundaries".

---

## 7. Four Typical Collaboration Scenarios

### Scenario A — How Workspace Files Become the Model's System Prompt

```mermaid
sequenceDiagram
    participant RA as ReActAgent
    participant Hook as WorkspaceContextHook(900)
    participant WM as WorkspaceManager
    participant FS as AbstractFilesystem
    participant LD as Local disk
    participant M as Model

    RA->>Hook: PreReasoningEvent
    Hook->>WM: readAgentsMd / readMemoryMd / readKnowledgeMd
    WM->>FS: read(path) first
    alt FS hit, non-empty
        FS-->>WM: content (multi-tenant transparent)
    else not found
        WM->>LD: Files.readString(workspace/...)
        LD-->>WM: content (fallback)
    end
    WM-->>Hook: AGENTS / MEMORY / KNOWLEDGE content
    Note over Hook: wraps into loaded_context XML,<br>merges into first SYSTEM message
    Hook-->>RA: modified event
    RA->>M: stream(newMessages)
```

### Scenario B — How Facts Settle into `MEMORY.md` Over a Long Session

```mermaid
graph TD
    A["conversation accumulates → CompactionHook threshold hit"] --> B["ConversationCompactor.compactIfNeeded"]
    B --> C["MemoryFlushManager.flushMemories(prefix)\n→ LLM extracts new facts"]
    B --> D["offloadMessages\n→ sessions/&lt;sess&gt;.log.jsonl"]
    B --> E["LLM distill summary\n→ replace Memory + setInputMessages"]

    C --> C1["append to memory/YYYY-MM-DD.md"]
    C --> C2["MemoryIndex.indexFromString (FTS5 incremental)"]
    C --> C3["scheduler.requestConsolidation()"]

    C3 -- "30min throttle" --> C4["submit consolidateMemory"]
    C4 --> C5["MemoryConsolidator + LLM\nread old daily logs + current MEMORY.md"]
    C5 --> C6["overwrite MEMORY.md"]
    C6 --> NEXT["next call\nWorkspaceContextHook reads new MEMORY.md\n→ injected into system prompt"]
```

### Scenario C — How the Same `sessionId` Remembers Across Calls

```mermaid
graph LR
    subgraph T1["Turn 1: call(msg1, ctx{sess=A})"]
        A1["bindRuntimeContext\nloadIfExists → Memory empty (first time)"] --> B1["ReAct loop"]
        B1 --> C1["PostCallEvent\nMemoryFlushHook: flush + offload\nSessionPersistenceHook: saveTo → write to disk"]
    end

    subgraph T2["Turn 2: call(msg2, ctx{sess=A})"]
        A2["bindRuntimeContext\nloadIfExists → read context/A/memory.json\nrestore turn 1 conversation into Memory"] --> B2["ReAct loop\n(aware of turn 1 content)"]
        B2 --> C2["PostCallEvent → write to disk (overwrite)"]
    end

    C1 -. "context/A/memory.json" .-> A2
```

### Scenario D — Parent Agent Delegates to Subagent: Sync and Background Paths

```mermaid
sequenceDiagram
    participant Parent as Parent Agent
    participant Hook as SubagentsHook
    participant Sub as Child HarnessAgent (leaf)
    participant Repo as TaskRepository
    participant Exec as Executor

    rect rgb(235, 245, 255)
    Note over Parent,Sub: Sync path (agent_send / timeout_seconds > 0)
    Parent->>Hook: agent_send(agent_id, message)
    Hook->>Sub: factory.create() · sub.call(msg).block()
    Sub-->>Hook: reply
    Hook-->>Parent: ToolResultBlock(reply)
    end

    rect rgb(255, 245, 235)
    Note over Parent,Exec: Background path (agent_spawn + timeout_seconds=0)
    Parent->>Hook: agent_spawn(agent_id, task, timeout=0)
    Hook->>Repo: putTask(taskId, supplier)
    Repo->>Exec: submit(supplier) → return immediately
    Hook-->>Parent: ToolResultBlock(taskId)

    Note over Parent: poll in subsequent turns
    Parent->>Hook: task_output(taskId, block=false)
    Hook->>Repo: getTask(taskId)
    Repo-->>Hook: RUNNING / result
    Hook-->>Parent: status / final result
    end
```

---

## Related Pages

- [Workspace](/v1/en/docs/harness/workspace) — workspace directory structure, `WorkspaceManager` two-layer read/write details
- [Memory](/v1/en/docs/harness/memory) — two-layer memory model, compaction configuration, FTS5 retrieval
- [Filesystem](/v1/en/docs/harness/filesystem) — `AbstractFilesystem` three modes and extension patterns
- [Subagent](/v1/en/docs/harness/subagent) — subagent declaration format, `TaskRepository`, five-row decision table
- [Session](/v1/en/docs/harness/session) — `WorkspaceSession` / `JsonSession` serialization protocol
- [Tool](/v1/en/docs/harness/tool) — built-in tool reference and registration

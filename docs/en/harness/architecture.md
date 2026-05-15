# Harness Architecture

[Overview](./overview.md) organizes Harness capabilities by "what problem they solve". This page switches perspective: explaining each component's **definition, behavior, trigger timing, and collaborators**, then using sequence diagrams to show how they cooperate inside a single `call()`.

> This page focuses on a medium-grain user-facing view — clarifying "who, when, what, and with whom" — without expanding call stacks or implementation details; those are covered in sub-documents ([memory](./memory.md), [workspace](./workspace.md), [filesystem](./filesystem.md), [sandbox](./sandbox/index.md), [subagent](./subagent.md), [session](./session.md), [tool](./tool.md)).

## 1. Top-Level Structure

`HarnessAgent` is not a new reasoning loop — it is a thin wrapper around `Agent` + `StateModule`, internally holding a `ReActAgent delegate`. All `call` / `stream` / `observe` / `saveTo` / `loadFrom` are forwarded to it. All Harness capabilities are assembled through the three existing extension points of `ReActAgent`:

```mermaid
flowchart TD
    HA["<b>HarnessAgent</b><br/>Agent + StateModule, external API"]
    HA --> SHARED["<b>Shared Objects</b>"]
    HA --> DEL["<b>delegate: ReActAgent</b>"]

    SHARED --> WM["WorkspaceManager<br/><i>reads/writes workspace</i>"]
    SHARED --> AFS["AbstractFilesystem<br/><i>local / sandbox / remote / composite</i>"]
    SHARED --> RC["RuntimeContext<br/><i>sessionId / userId / session / extra</i>"]

    DEL --> HK["hooks<br/><i>sorted by priority</i>"]
    DEL --> TK["toolkit<br/><i>user + harness built-in + SubagentsHook task tools</i>"]
    DEL --> SB["skillBox<br/><i>AgentSkillRepository or workspace/skills/</i>"]
    DEL --> MEM["memory<br/><i>InMemoryMemory (persisted via Session)</i>"]
```

**Injection happens in `HarnessAgent.Builder.build()`**: after constructing the three shared objects, the hook list is assembled in a fixed order, built-in tools are appended to the user's toolkit, the skillBox is wired from workspace/repo, and everything is handed to `ReActAgent.builder()`.

At the start of each `agent.call(msg, ctx)`, `HarnessAgent.bindRuntimeContext(ctx)` distributes the current `RuntimeContext` to all hooks implementing `RuntimeContextAwareHook` (workspace context, memory flush, compaction, session persistence), and auto-restores state from `Session` as needed.

## 2. Three Shared Objects

These three objects are the common language through which hooks collaborate. Understanding them means understanding how Harness is "coupled".

### 2.1 RuntimeContext

The identity carrier for the current `call()`: not persisted, re-distributed to `RuntimeContextAwareHook` on every `call`.

- **`sessionId`** — determines persistence paths and JSONL filename
- **`userId`** — threaded to `AbstractFilesystem.NamespaceFactory` for multi-tenant isolation
- **`session` + `sessionKey`** — explicitly specified, or defaults to `WorkspaceSession + SimpleSessionKey.of(sessionId)`
- **`extra`** — custom key-value pairs; tools/hooks read via `ctx.get(key)`

### 2.2 WorkspaceManager

A stateless workspace accessor. **Two-layer semantics**: reads hit filesystem first, fall back to local disk; writes always go through filesystem; list operations merge and deduplicate both layers. Expected layout:

```
workspace/
├── AGENTS.md / MEMORY.md
├── memory/YYYY-MM-DD.md / .consolidation_state / archive/
├── memory_index.db                # SQLite FTS5
├── knowledge/KNOWLEDGE.md / **/*
├── skills/<skill>/SKILL.md
├── subagents/*.md                 # YAML front matter + body
└── agents/<agentId>/
    ├── context/<sessionId>/{key}.json     # written by WorkspaceSession
    └── sessions/<sessionId>.log.jsonl     # offloaded by MemoryFlushManager
```

### 2.3 AbstractFilesystem

The physical storage backend for the workspace — pluggable. Base interface: `ls/read/write/edit/grep/glob/upload/download`; the extending interface `AbstractSandboxFilesystem` adds `execute/id`.

| Implementation | Use | Key Characteristics |
|---|---|---|
| `LocalFilesystem` | Local disk | `virtualMode` anchors `rootDir` to prevent traversal; no shell |
| `LocalFilesystemWithShell` | Local + host shell | Declarative `LocalFilesystemSpec` and the **default when no `filesystem` is configured**; registers `shell_execute` when `instanceof AbstractSandboxFilesystem` |
| `BaseSandboxFilesystem` / `SandboxBackedFilesystem` | Sandbox backend | Files and commands run inside sandbox; see [Sandbox](./sandbox/index.md) |
| `RemoteFilesystem` | KV store | Combined with `LocalFilesystem` via `CompositeFilesystem` under `RemoteFilesystemSpec`; no shell |
| `CompositeFilesystem` | Prefix routing | Implements only `AbstractFilesystem` (**not** `AbstractSandboxFilesystem`), does **not** trigger `ShellExecuteTool`; longest-prefix-first |

> **Multi-tenant and isolation**: `NamespaceFactory` is called on every operation; `RemoteFilesystemSpec` / `SandboxFilesystemSpec` can also configure `IsolationScope` (aligned with sandbox/shared-storage naming). **Which mode registers `ShellExecuteTool`** is the key distinction — see [filesystem](./filesystem.md#three-declarative-modes).

## 3. Hook List

Below are the common built-in Harness hooks assembled in `Builder.build()` (**sandbox mode** additionally includes `SandboxLifecycleHook` — see [Sandbox](./sandbox/index.md)). `ReActAgent` executes hooks in **ascending `priority()` order**; same-priority hooks preserve assembly order.

| Hook | Priority | Event | Enabled by Default | Key Dependencies |
|------|----------|-------|-------------------|-----------------|
| `AgentTraceHook` | 0 | all | ✓ (default; disable with `.agentTracing(false)`) | — |
| `MemoryFlushHook` | 5 | `PostCallEvent` | ✓ (requires `model`) | `WorkspaceManager`, `Model`, `MemoryFlushManager` |
| `MemoryMaintenanceHook` | 6 | `PostCallEvent` (throttled) | ✓ (requires `model`) | `MemoryConsolidator`, `WorkspaceManager` |
| `CompactionHook` | 10 | `PreReasoningEvent` | ✗ (requires explicit `.compaction(...)`) | `WorkspaceManager`, `Model`, `CompactionConfig`, `MemoryFlushManager` |
| `SandboxLifecycleHook` | 50 | `PreCall` / `PostCall` / `Error` | Only when `filesystem(SandboxFilesystemSpec)` | `SandboxManager`, `SandboxBackedFilesystem` |
| `ToolResultEvictionHook` | 50 | `PostActingEvent` | ✗ (requires explicit `.toolResultEviction(...)`) | `AbstractFilesystem`, `ToolResultEvictionConfig` |
| `SubagentsHook` | 80 | `PreReasoningEvent` + `tools()` | ✓ (non-leaf with `model`) | subagent list, `TaskRepository` |
| `WorkspaceContextHook` | 900 | `PreReasoningEvent` | ✓ | `WorkspaceManager`, `RuntimeContext`, token budget |
| `SessionPersistenceHook` | 900 | `PostCallEvent` + `ErrorEvent` | ✓ | `RuntimeContext` |

> Hooks implementing `RuntimeContextAwareHook` (workspace context, memory flush, compaction, session persistence) are re-injected with the current `RuntimeContext` via `bindRuntimeContext` on every `call()`.

### 3.1 Context Injection: `WorkspaceContextHook` (priority 900)

**Purpose**: before every reasoning turn, merges workspace files into the first SYSTEM message as a `<loaded_context>` XML block.

**Trigger**: `PreReasoningEvent`. Priority 900 lets it run after compaction and subagents, layering on top of the final system prompt.

**Key logic**: reads AGENTS / MEMORY / KNOWLEDGE (including file listing) + user-specified `additionalContextFiles` → estimates tokens (chars/4) and retains fixed sections within `maxContextTokens` budget, truncates `MEMORY.md` tail when over budget and appends a `memory_search` hint.

### 3.2 Memory Management: `MemoryFlushHook` + Background

**Purpose**: `MemoryFlushHook` (priority 5) on `PostCallEvent` hands the current memory to `MemoryFlushManager`, which does two things:

- **flushMemories**: LLM extracts facts → appends to `memory/YYYY-MM-DD.md` (daily log) → incrementally updates FTS5
- **offloadMessages**: raw message sequence written to `agents/<id>/sessions/<sessionId>.log.jsonl`

Four components share the work:

| Component | Responsibility | Frequency |
|---|---|---|
| `MemoryFlushManager` | Layer 1: daily log + JSONL | Each `call()` end + before each compaction |
| `MemoryConsolidator` | Layer 2: curated `MEMORY.md` | 6-hour cycle / opportunistic (30-min throttle) |
| `MemoryIndex` | SQLite FTS5 index `memory_index.db` | Incremental (on write) + full (maintenance cycle) |
| `MemoryMaintenanceScheduler` | Scheduling + old file archival/cleanup | Daemon thread 6-hour cycle |

> **Two-layer semantics**: the daily log is append-only and never modified; `MEMORY.md` is completely rewritten by the consolidator (outputs a full new version, not a diff). Layer 1 is the facts stream; layer 2 is a curated view. No overlap with `CompactionHook`: compaction manages the compressed prefix, this hook manages the retained tail.

### 3.3 Context Length Control: `CompactionHook` + Overflow Safety Net

**Purpose**: `CompactionHook` (priority 10) on `PreReasoningEvent` delegates to `ConversationCompactor.compactIfNeeded`.

**Trigger condition**: message count ≥ `triggerMessages` or token count ≥ `triggerTokens` (defaults: 50 / 80K).

**On trigger**: first calls `flushMemories(prefix)` to extract facts, `offloadMessages(full segment)` to save JSONL, then uses a structured prompt (SESSION INTENT / SUMMARY / ARTIFACTS / NEXT STEPS) for the LLM to distill a summary, yielding `[summaryMsg + tail]` written back to both `Memory` and `event.setInputMessages`. Tail length controlled by `keepMessages` / `keepTokens` (default 20 messages).

**Overflow safety net**: `HarnessAgent.call()` catches model `ContextOverflow`-class exceptions → `forceCompactAndRetry` forces the most aggressive compaction → retries `delegate.call()` once. This is the last line of defense when thresholds are misconfigured.

### 3.4 Tool Result Offloading: `ToolResultEvictionHook` (priority 50)

**Purpose**: when a single tool result is too large, saves it to disk and keeps only a head+tail preview + placeholder in context.

**Trigger**: `PostActingEvent` (before memory writes, so downstream only sees the placeholder).

**Key logic**: exceeds `maxResultChars` (default 80K chars ≈ 20K tokens) → writes to `{evictionPath}/{agent}/{toolCallId}` → replaces with `Tool output too large, saved to ...` + 2K head + 2K tail. `excludedToolNames` (read/write/edit, grep/glob/ls, memory/session search) are skipped — these tools have their own pagination or back-read loops.

> Independent from compaction: compaction manages depth (accumulated message length), eviction manages width (single message length).

### 3.5 Session Persistence: `SessionPersistenceHook` + `WorkspaceSession`

**Purpose**: `SessionPersistenceHook` (priority 900) on both `PostCallEvent` and `ErrorEvent` tries `agent.saveTo(session, sessionKey)` (`HarnessAgent` implements `StateModule`). Priority 900 lets `MemoryFlushHook` (5) finish writing memory before the snapshot.

**`WorkspaceSession`** is a `JsonSession` subclass whose `baseDir` is locked to `<workspace>/agents/<agentId>/context/`, ultimately writing `<workspace>/agents/<agentId>/context/<sessionId>/{key}.json`.

The next `call()` start, `bindRuntimeContext` calls `loadIfExists` to restore memory — this is the source of "same sessionId remembers across calls".

### 3.6 Subagent Orchestration: `SubagentsHook` + `TaskRepository`

**Purpose**: `SubagentsHook` (priority 80) plays two roles — registers `agent_spawn / agent_send / agent_list / task_output / task_cancel / task_list` via `tools()`, and injects a system prompt segment with subagent name+description list on `PreReasoningEvent`.

- **Sync path** `agent_send`: blocks on subagent execution and fills in the result
- **Background path** `agent_spawn`: submits via `TaskRepository.putTask` to an executor and gets a `taskId`; the parent agent uses `task_output(taskId)` in a later turn to pull the result

**Subagent sources** (`Builder.buildSubagentEntries`): workspace `subagents/*.md` (parsed by `AgentSpecLoader` into `SubagentDeclaration`) / programmatic `.subagent(SubagentDeclaration)` / custom `.subagentFactory`. Each subagent is a leaf `HarnessAgent` (`asLeafSubagent()`, no `SubagentsHook` registered); workspace / filesystem / sysPrompt are determined by the declaration and the five-row decision table — see [Subagent](./subagent.md).

**`TaskRepository`** is the task orchestration interface (`putTask` / `getTask` / `listTasks(filter)` / `cancelTask`); the default `DefaultTaskRepository` uses a thread pool + `CompletableFuture<String>` + `BackgroundTask` state machine (PENDING/RUNNING/COMPLETED/FAILED/CANCELLED).

### 3.7 Trace Logging: `AgentTraceHook` (priority 0)

Listens to all events, outputs `[<agent>] PRE_REASONING | model=..., messages=...`-style INFO logs (DEBUG for detailed content); does not modify events.

## 4. `call()` Lifecycle Sequence

The diagram below shows the collaboration order of components during a complete `agent.call(msg, ctx)`. **Hooks on the same event fire in ascending priority order** — this is why they can stack without conflict.

```mermaid
sequenceDiagram
    autonumber
    actor User
    participant HA as HarnessAgent
    participant RA as delegate<br/>(ReActAgent)
    participant H as Hooks<br/>(by priority)
    participant M as Model
    participant T as Toolkit
    participant FS as Filesystem<br/>+ Memory/JSONL

    User->>HA: call(msg, ctx)
    HA->>HA: bindRuntimeContext(ctx)
    Note over HA: Distribute to RuntimeContextAwareHook<br/>loadIfExists(session, key) restores state
    HA->>RA: delegate.call(...)

    RA->>H: PreCallEvent
    Note over H: AgentTraceHook(0) logs

    loop ReAct reasoning loop
        RA->>H: PreReasoningEvent
        Note over H,FS: CompactionHook(10) if threshold reached:<br/>① flushMemories(prefix) → memory/*.md<br/>② offload(full segment) → sessions/*.jsonl<br/>③ LLM distill prefix → summaryMsg<br/>④ memory.clear() + add(summary + tail)<br/>⑤ event.setInputMessages(...)<br/><br/>SubagentsHook(80) injects subagent prompt<br/>WorkspaceContextHook(900) injects workspace files
        RA->>M: Model.stream(messages)
        M-->>RA: ChatResponse
        RA->>H: PostReasoningEvent
        Note over H: AgentTraceHook(0) logs text or tool_calls

        alt contains tool_calls
            loop each tool_call
                RA->>H: PreActingEvent
                RA->>T: Toolkit.invoke(toolCall)
                T-->>RA: ToolResult
                RA->>H: PostActingEvent
                Note over H,FS: ToolResultEvictionHook(50) if over threshold:<br/>① write to filesystem evictionPath<br/>② replace result with head+tail placeholder
            end
        else no tool_call (text response)
            Note over RA: exit loop
        end
    end

    RA->>H: PostCallEvent
    Note over H,FS: MemoryFlushHook(5):<br/>① flushMemories → memory/YYYY-MM-DD.md<br/>② offloadMessages → sessions/*.jsonl<br/>③ requestConsolidation notifies background<br/><br/>SessionPersistenceHook(900) saveTo(session, key)
    RA-->>HA: final Msg
    HA-->>User: final Msg

    Note over HA: Failure path: throw → ErrorEvent → SessionPersistenceHook still saveTo<br/>ContextOverflow → forceCompactAndRetry → delegate.call retry
```

## 5. Background Maintenance Sequence

`MemoryMaintenanceScheduler.start()` is triggered at the end of `Builder.build()`; it holds a daemon-thread `ScheduledExecutorService`.

```mermaid
flowchart LR
    subgraph CYCLE["Periodic trigger (default every 6 hours)"]
        direction TB
        T0["runMaintenance()"] --> S1["1. expireDailyFiles<br/>memory/*.md files named YYYY-MM-DD<br/>and &lt; cutoff (default 90 days)<br/>→ archive/"]
        S1 --> S2["2. consolidateMemory<br/>read .consolidation_state watermark<br/>list files with mtime > watermark<br/>LLM merge → overwrite MEMORY.md → advance watermark"]
        S2 --> S3["3. pruneOldSessions<br/>workspace/agents/&lt;id&gt;/sessions/*<br/>non-sessions.json files with mtime &lt; cutoff (default 180 days) deleted"]
        S3 --> S4["4. reindex<br/>MemoryIndex.indexAllFromWorkspace<br/>DELETE + INSERT rebuild FTS5"]
    end

    subgraph OPP["Opportunistic path"]
        direction TB
        F["MemoryFlushManager.flushMemories()<br/>each successful flush"] --> R["scheduler.requestConsolidation()"]
        R -- "last < 30min" --> SK["skip"]
        R -- "otherwise" --> SUB["submit consolidateMemory<br/>(via executor, non-blocking to agent)"]
    end
```

## 6. Four Typical Collaboration Scenarios

### Scenario A — Workspace Files Become Model-Visible System Prompt

```mermaid
sequenceDiagram
    participant RA as ReActAgent
    participant Hook as WorkspaceContextHook<br/>(priority 900)
    participant WM as WorkspaceManager
    participant FS as AbstractFilesystem
    participant LD as Local disk
    participant M as Model

    RA->>Hook: PreReasoningEvent(messages)
    Note over WM: readAgentsMd / readMemoryMd / readKnowledgeMd
    Hook->>WM: read workspace files
    WM->>FS: read(...) first
    alt FS hit, non-empty
        FS-->>WM: content (multi-tenant transparent)
    else otherwise
        WM->>LD: Files.readString(workspace/AGENTS.md)
        LD-->>WM: content (fallback)
    end
    WM-->>Hook: AGENTS / MEMORY / KNOWLEDGE / extra
    Note over Hook: wrap in loaded_context XML block<br>merge into first SYSTEM message<br>event.setInputMessages(...)
    Hook-->>RA: return modified event (with new messages)
    RA->>M: Model.stream(messages)
```

### Scenario B — How Facts Settle into `MEMORY.md` Over a Long Session

```mermaid
flowchart TD
    A["conversation accumulates → CompactionHook threshold hit"] --> B["ConversationCompactor.compactIfNeeded"]
    B --> C["MemoryFlushManager<br/>.flushMemories(prefix)"]
    B --> D["offloadMessages<br/>→ sessions/&lt;sessionId&gt;.log.jsonl"]
    B --> E["distill summary<br/>→ replace memory + setInputMessages"]

    C --> C1["memory/YYYY-MM-DD.md (append)"]
    C --> C2["MemoryIndex.indexFromString<br/>(FTS5 incremental)"]
    C --> C3["scheduler.requestConsolidation"]

    C3 -- "throttle 30min" --> C4["submit consolidateMemory"]
    C4 --> C5["MemoryConsolidator + LLM"]
    C5 --> C6["overwrite MEMORY.md"]
    C6 --> C7["next reindex reflects to FTS5"]

    C7 --> NEXT["<b>next call</b>"]
    NEXT --> N1["WorkspaceContextHook reads MEMORY.md<br/>→ injects into system prompt"]
    NEXT --> N2["memory_search tool queries FTS5<br/>for older facts"]
```

### Scenario C — How Turn 2 "Remembers" Turn 1

```mermaid
flowchart LR
    subgraph T1["turn 1"]
        direction TB
        T1A["call(msg1, ctx{sess=A})"] --> T1B["bindRuntimeContext"]
        T1B --> T1C["ReAct loop"]
        T1C --> T1D["PostCallEvent"]
        T1D --> T1E["MemoryFlushHook<br/>flush + offload"]
        T1D --> T1F["SessionPersistenceHook<br/>saveTo(session, key)<br/>→ write to disk"]
    end

    subgraph T2["turn 2"]
        direction TB
        T2A["call(msg2, ctx{sess=A})"] --> T2B["bindRuntimeContext"]
        T2B --> T2B1["loadIfExists<br/>read context/A/{key}.json<br/>restore turn1 conversation into memory"]
        T2B1 --> T2C["ReAct loop<br/>(memory already has turn1)"]
        T2C --> T2D["PostCallEvent"]
        T2D --> T2E["..."]
        T2D --> T2F["SessionPersistenceHook<br/>saveTo (overwrite) → write to disk"]
    end

    T1F -. persisted to disk .-> T2B1
```

### Scenario D — Sync and Background Delegation Paths for Subagents

```mermaid
sequenceDiagram
    participant Parent as Parent agent
    participant Hook as SubagentsHook
    participant Sub as Child HarnessAgent (leaf)
    participant Repo as TaskRepository
    participant Exec as Executor

    Note over Parent: reasoning selects tool injected by SubagentsHook

    rect rgb(238, 248, 255)
    Note over Parent,Sub: Sync path
    Parent->>Hook: agent_send(name, message)
    Hook->>Sub: factory.create()
    Hook->>Sub: sub.call(msg).block()
    Sub-->>Hook: reply
    Hook-->>Parent: ToolResultBlock(reply)
    end

    rect rgb(255, 245, 238)
    Note over Parent,Exec: Background path
    Parent->>Hook: agent_spawn(name, message)
    Hook->>Repo: putTask(id, name, supplier)
    Repo->>Exec: submit(supplier)
    Repo-->>Hook: taskId
    Hook-->>Parent: ToolResultBlock(taskId)

    Note over Parent: parent agent in subsequent turn
    Parent->>Hook: task_output(taskId)
    Hook->>Repo: getTask(taskId).getResult()
    Repo-->>Hook: result / null
    Hook-->>Parent: ToolResultBlock(result/status)
    end
```

## Related Pages

- [Workspace](./workspace.md) — workspace directory structure, `WorkspaceManager` two-layer read details
- [Memory](./memory.md) — two-layer memory model, compaction configuration, FTS5 retrieval, message format details
- [Filesystem](./filesystem.md) — `AbstractFilesystem` implementation tradeoffs and composition
- [Subagent](./subagent.md) — subagent spec format, `TaskRepository` customization, nested harness notes
- [Session](./session.md) — `WorkspaceSession` / `JsonSession` serialization protocol and version compatibility
- [Tool](./tool.md) — built-in tool reference and custom tool registration

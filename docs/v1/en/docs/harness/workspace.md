# Workspace

## Purpose

The workspace is the foundation of a `HarnessAgent`: persona, long-term memory, domain knowledge, subagent declarations, session history, and skill definitions all land here as a **directory structure + Markdown** — no longer scattered in code.

Before every reasoning turn, a few key workspace files are automatically injected into the system prompt. Memory and session output from the running agent is also written back here along well-defined paths.

## Trigger Points

| When | Action |
|------|--------|
| `HarnessAgent.build()` | `WorkspaceManager.validate()` checks that the directory and `AGENTS.md` exist; missing items only warn |
| Before every `call()` reasoning turn | `WorkspaceContextHook` reads `AGENTS.md` / `MEMORY.md` / `knowledge/` / extra files and injects into system prompt |
| Compaction / end of call | `MemoryFlushHook`, `SessionPersistenceHook`, etc. write back to `memory/`, `agents/.../sessions/` via `WorkspaceManager` |

## Directory Layout

```
workspace/                           ← default: .agentscope/workspace
├── AGENTS.md                        ← persona / behavior guidelines (full text injected each turn)
├── MEMORY.md                        ← curated long-term memory (injected each turn, token-budgeted)
├── knowledge/
│   ├── KNOWLEDGE.md                 ← domain knowledge entry point
│   └── *                            ← other reference files, opened on demand via read_file
├── memory/
│   ├── YYYY-MM-DD.md                ← daily fact log (append-only, written by MemoryFlushManager)
│   └── .consolidation_state         ← MemoryConsolidator internal state
├── skills/<skill-name>/SKILL.md     ← custom skills
├── subagents/<id>.md                ← subagent declarations (filename = agent_id, auto-discovered)
└── agents/<agentId>/
    ├── workspace/                   ← runtime root for isolated subagents (auto-created when no workspace.path)
    └── sessions/
        ├── sessions.json            ← session index (id / summary / updatedAt)
        ├── <sessionId>.jsonl        ← LLM-visible compacted context
        └── <sessionId>.log.jsonl   ← full conversation log (append-only)
```

> The three-layer model for subagents (declaration / definition / runtime) is detailed in [Subagent](./subagent.md).

## Key Logic

### Two-Layer Read / Write

`WorkspaceManager` is a stateless accessor; all reads and writes follow the same contract:

```{mermaid}
graph LR
    Caller[Hook / Tool] -->|read| WM[WorkspaceManager]
    WM -->|read first| FS[AbstractFilesystem<br/>multi-tenant namespace transparent]
    FS -- hit non-empty --> WM
    FS -- empty --> LD[Local disk<br/>workspace/...]
    LD --> WM

    Caller -->|write| WM
    WM -->|appendUtf8 / uploadFiles| FS2[AbstractFilesystem]
    WM -. filesystem absent .-> LD2[local disk fallback]
```

Key points:

- **Read path**: `AbstractFilesystem` first → local disk fallback, making multi-tenant scenarios transparent to callers
- **Write path**: defaults to `AbstractFilesystem`; falls back to local disk when not configured
- **List operations** (`listKnowledgeFiles` / `listMemoryFilePaths` / `listSessionLogFiles`) take the union of both layers and deduplicate, avoiding missing files

### System Prompt Injection Content

`WorkspaceContextHook` (priority 900) assembles a fixed-structure text segment on `PreReasoningEvent` and merges it into the first SYSTEM message:

| Section | Source | Token Budget |
|---------|--------|--------------|
| `## Session Context` | Template-generated (date, OS, workspace path, `runtimeContext.sessionId`) | Unlimited |
| `## Workspace` guidance | Built-in template | Unlimited |
| `<loaded_context>` XML block | — | — |
| ↳ `<agents_context>` | `AGENTS.md` | Full text |
| ↳ `<memory_context>` | `MEMORY.md` | Capped by `maxContextTokens` |
| ↳ `<domain_knowledge_context>` | `knowledge/KNOWLEDGE.md` + `listKnowledgeFiles()` listing | Full text + path listing |
| ↳ `<{rel_path}>` | Each `additionalContextFile` | Full text |

`maxContextTokens` defaults to `8000` (estimated as `chars/4`). When `MEMORY.md` estimated size exceeds the "remaining budget", it is character-truncated and appended with `... (memory truncated — use memory_search for older entries) ...`, prompting the agent to fall back to `memory_search`.

### Key APIs

```java
WorkspaceManager wm = new WorkspaceManager(workspace, abstractFilesystem);

wm.readAgentsMd();                 // two-layer read
wm.readMemoryMd();
wm.readKnowledgeMd();              // note: reads knowledge/KNOWLEDGE.md
wm.readManagedWorkspaceFileUtf8(rel); // any workspace-relative path, with path traversal check

wm.listKnowledgeFiles();           // union of both layers
wm.listMemoryFilePaths();
wm.listSessionLogFiles();

wm.appendUtf8WorkspaceRelative(rel, content);  // goes through AbstractFilesystem
wm.updateSessionIndex(agentId, sessionId, summary); // maintains sessions.json
```

## Configuration

```java
HarnessAgent agent = HarnessAgent.builder()
    .name("MyAgent")
    .model(model)
    .workspace(Paths.get(".agentscope/workspace"))   // uses default if not provided
    .additionalContextFile("SOUL.md")                // any workspace-relative path
    .additionalContextFile("PREFERENCES.md")
    .maxContextTokens(8000)                          // controls injection cap for MEMORY
    .build();
```

If `AGENTS.md` is missing, the agent still works but loses the persona section. It is recommended to at least write a minimal skeleton (see the quickstart in [overview.md](./overview.md)).

## Related Pages

- [Architecture](./architecture.md) — `WorkspaceContextHook` position in the `call()` lifecycle
- [Filesystem](./filesystem.md) — implementation of the "upper layer" in the two-layer read path
- [Memory](./memory.md) — how `MEMORY.md` / `memory/*.md` are generated and maintained
- [Session](./session.md) — details of `agents/<agentId>/sessions/`

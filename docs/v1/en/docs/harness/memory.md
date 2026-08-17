# Memory

## Purpose

Enable the agent to "remember facts across sessions" while preventing conversation context from growing unboundedly. Harness splits memory into two layers: high-frequency low-curation "daily logs" + low-frequency high-curation "long-term memory", supplemented by FTS5 full-text search and background maintenance.

## Trigger Points

| When | Action |
|------|--------|
| Before reasoning (`PreReasoningEvent`) | `CompactionHook` checks conversation thresholds; triggers `ConversationCompactor` if exceeded |
| End of `call()` (`PostCallEvent`) | `MemoryFlushHook` calls `MemoryFlushManager` to extract facts + offload |
| Context overflow (`ContextLengthExceeded`) | `HarnessAgent.forceCompactAndRetry` compacts with `triggerMessages=1` and retries |
| Oversized tool result (`PostActingEvent`) | `ToolResultEvictionHook` offloads to filesystem |
| Background schedule | `MemoryMaintenanceScheduler` runs a cycle every 6h by default; sends an opportunistic signal after each flush (30-minute throttle) |

## Key Logic

### Two-Layer Memory Model

```{mermaid}
graph LR
    Conv[conversation messages] -->|over threshold| Compactor[ConversationCompactor]
    Compactor -->|offload| Sess[sessions/&lt;id&gt;.log.jsonl]
    Compactor -->|flushMemories| Flush[MemoryFlushManager]
    Flush -->|append + index| Daily[memory/YYYY-MM-DD.md]
    Daily -. background processing .-> Cons[MemoryConsolidator]
    MEM[MEMORY.md as context] -->|read for deduplication| Cons
    Cons -->|rewrite| MEM
    MEM -->|injected each reasoning turn| Hook[WorkspaceContextHook]
    Daily -.not injected directly.- Hook
    Daily --> Idx[(MemoryIndex<br/>SQLite FTS5)]
    MEM --> Idx
```

- **Layer 1 — Daily log `memory/YYYY-MM-DD.md`**: owned by `MemoryFlushManager`, **append-only**, no deduplication; a raw record of "what was just being discussed".
- **Layer 2 — Curated long-term memory `MEMORY.md`**: owned by `MemoryConsolidator`, **complete rewrite**; `MemoryFlushManager` never touches it. Injected into system prompt by `WorkspaceContextHook` on every reasoning turn.
- **Index `MemoryIndex`**: fully indexed at startup with `indexAllFromWorkspace`; incrementally rebuilt for today's file after each flush; SQLite file at `<workspace_parent>/memory_index.db`.

### Conversation Compaction (`ConversationCompactor`)

```
check thresholds → find cutoff (don't split ASSISTANT/TOOL pairs)
        → (optional) flushMemories(prefix)
        → (optional) offloadMessages(messages → sessions/.../<id>.jsonl)
        → LLM distills summary
        → [summaryUserMsg] + tail returned to hook to reload memory
```

Default values (all configurable):

| Parameter | Default | Description |
|-----------|---------|-------------|
| `triggerMessages` | `50` | Trigger by message count (`0` = disabled) |
| `triggerTokens` | `80_000` | Trigger by estimated token count (`0` = disabled) |
| `keepMessages` | `20` | Number of tail messages to retain |
| `keepTokens` | `0` | Non-zero: scan from back by token budget, overrides `keepMessages` |
| `flushBeforeCompact` | **`true`** | Extract memory to daily log before compacting |
| `offloadBeforeCompact` | **`true`** | Append raw messages to session `.log.jsonl` before compacting |
| `summaryPrompt` | Built-in template | Four-section format: SESSION INTENT / SUMMARY / ARTIFACTS / NEXT STEPS |

```java
CompactionConfig.builder()
    .triggerMessages(30)
    .keepMessages(10)
    .build();   // flush/offload both default to true
```

#### `TruncateArgsConfig` — Lightweight Pre-processing (Optional)

Before LLM summarization, a **no-LLM** pre-pass can truncate `ToolUseBlock` arguments in older messages (default threshold: 25 messages / 40k tokens, arguments exceeding 2000 characters are trimmed). Useful for scenarios like `write_file` where large argument bodies are not needed later.

```java
CompactionConfig.builder()
    .triggerMessages(80)
    .truncateArgs(TruncateArgsConfig.builder().build())
    .build();
```

#### Automatic Context Overflow Recovery

When the model returns a `context_length_exceeded` / `maximum context` style error, `HarnessAgent.recoverFromOverflow` → `forceCompactAndRetry` builds a temporary `CompactionConfig` with `triggerMessages=1`, runs one compaction round, clears `Memory`, and retries. **Prerequisite: `compaction(...)` must be configured**; otherwise the error is rethrown directly.

### Memory Extraction (`MemoryFlushManager`)

- `flushMemories(messages)`: hands the current `MEMORY.md` and today's log to the LLM as "deduplication reference", requesting **only newly added** bullets. "NO_REPLY" means nothing to write.
- Write location is always `memory/YYYY-MM-DD.md`, **never `MEMORY.md`** (to prevent layer 1 overwriting layer 2).
- After writing, immediately calls `indexFromString` to rebuild the file index, then calls `MemoryMaintenanceScheduler.requestConsolidation()` to signal "consolidate when you can".

### Secondary Consolidation (`MemoryConsolidator`)

- Reads daily logs with mtime exceeding the watermark + current `MEMORY.md`, calls LLM to merge, deduplicate, and trim.
- Output limit: default `maxMemoryTokens=4000` (~16k characters); the prompt communicates this as a character budget to the LLM.
- After writing, advances the watermark stored in `memory/.consolidation_state`; next run only looks at files with mtime past the watermark.
- Consolidation only runs on the background executor: triggered by a periodic tick or `requestConsolidation()`, never blocking the reasoning loop.

### Background Maintenance (`MemoryMaintenanceScheduler`)

Auto-created and `start()`ed inside `HarnessAgent.build()`; each tick runs in sequence:

1. `expireDailyFiles` — archive daily files older than `dailyFileRetentionDays` to `memory/archive/` (**default 90 days**)
2. `consolidateMemory` — call `MemoryConsolidator.consolidate()`
3. `pruneOldSessions` — delete session files with mtime older than `sessionRetentionDays` (**default 180 days**)
4. `reindex` — `MemoryIndex.indexAllFromWorkspace`

Default interval: `Duration.ofHours(6)`; opportunistic calls are throttled to 30-minute intervals to avoid hammering the LLM with frequent flushes.

### Tool Result Eviction (`ToolResultEvictionConfig`)

Independent from compaction. When a `tool_call` return text exceeds the threshold, the full content is written to a file under `evictionPath`, and the original position is replaced with a "head+tail preview + path" placeholder. The agent calls `read_file` when it needs the full content.

| Parameter | Default | Description |
|-----------|---------|-------------|
| `maxResultChars` | `80_000` | Evict if exceeded |
| `previewChars` | `2_000` | Number of head and tail preview characters |
| `evictionPath` | `large_tool_results` | Workspace-relative root path for evicted files |
| `excludedToolNames` | Built-in set (includes `read_file` etc.) | Tools excluded from eviction |

```java
HarnessAgent.builder()
    ...
    .toolResultEviction(ToolResultEvictionConfig.defaults())
    .build();
```

## Configuration and Code Examples

```java
HarnessAgent agent = HarnessAgent.builder()
    .name("MyAgent")
    .model(model)
    .workspace(workspace)
    .compaction(CompactionConfig.builder()
        .triggerMessages(30)
        .keepMessages(10)
        .build())
    .toolResultEviction(ToolResultEvictionConfig.defaults())
    .build();

// Agent can call memory_search at any time
MemoryIndex index = new MemoryIndex(workspaceAgentScopeDir);
index.open();
List<MemoryIndex.SearchHit> hits = index.search("database migration", 10);
// hit: { path, lineNumber, content, rank }
```

## Related Pages

- [Tool](./tool.md) — `memory_search` / `memory_get` parameters and call examples
- [Workspace](./workspace.md) — `MEMORY.md` / `memory/*.md` location in the workspace
- [Session](./session.md) — how `.log.jsonl` / `.jsonl` feeds back into memory extraction
- [Architecture](./architecture.md) — `CompactionHook` / `MemoryFlushHook` / `ToolResultEvictionHook` position in the lifecycle

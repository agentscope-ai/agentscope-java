---
title: "Memory"
description: "Two-layer long-term memory, conversation compaction, large tool-result offloading"
---

## Role

Lets the agent "remember facts across sessions" while keeping the conversation context bounded. Harness splits memory into two layers:

- **Layer 1 · daily log** `memory/YYYY-MM-DD.md` — append-only each day, raw and not deduped;
- **Layer 2 · curated long-term** `MEMORY.md` — periodically merged + deduped by the LLM; injected into the system prompt every reasoning step as long-term memory.

Three companion mechanisms:

- **Conversation compaction** — summarizes history and keeps a recent tail when context is too long;
- **Overflow safety net** — when the model actually errors, force a compaction and retry;
- **Large tool-result offloading** — offload to disk + placeholder when a single tool returns too much.

## How the two layers work

```{mermaid}
graph LR
    Conv["conversation messages"] -->|over threshold| Compactor["conversation compaction"]
    Compactor -->|offload| Sess["sessions/&lt;id&gt;.log.jsonl"]
    Compactor -->|extract new facts| Daily["memory/YYYY-MM-DD.md"]
    Daily -. periodic background merge .-> MEM["MEMORY.md"]
    MEM -->|injected each reasoning step| SYS["system prompt"]
```

Key points:

- Layer 1 only appends, never dedupes; Layer 2 is periodically rewritten as a whole; **the two layers never overwrite each other**.
- Layer 2 is the only one injected into the prompt; Layer 1 waits to be merged.
- Raw messages dropped during compaction are also saved into a never-compacted log file (`*.log.jsonl`) for later audit or `session_search`.

## Enable compaction

```java
HarnessAgent agent = HarnessAgent.builder()
    .name("MyAgent")
    .model(model)
    .workspace(workspace)
    .compaction(CompactionConfig.builder()
        .triggerMessages(30)     // fire at 30 messages
        .keepMessages(10)        // keep the last 10 after compaction
        .build())
    .build();
```

Common options:

| Field | Default | Meaning |
|-------|---------|---------|
| `triggerMessages` | `50` | Trigger by message count (`0` = off) |
| `triggerTokens` | `80_000` | Trigger by estimated tokens (`0` = off) |
| `keepMessages` | `20` | Number of tail messages to keep |
| `keepTokens` | `0` | When non-zero, walk back by token budget; overrides `keepMessages` |
| `flushBeforeCompact` | `true` | Extract new facts to the daily log before compacting |
| `offloadBeforeCompact` | `true` | Append raw messages to the never-compacted log before compacting |

**Auto-recovery on overflow**: when the model returns `context_length_exceeded` (or similar), the framework forces one compaction and retries — but only when `compaction(...)` is configured; otherwise the error propagates.

### Want it lighter? Trim arguments first

Tool calls like `write_file` carry huge arguments that nobody reads later. Before LLM summarization you can run a **non-LLM** string truncation:

```java
CompactionConfig.builder()
    .triggerMessages(80)
    .truncateArgs(CompactionConfig.TruncateArgsConfig.builder()
        .maxArgLength(2000)
        .truncationText("... [truncated] ...")
        .build())
    .build();
```

## Large tool-result offloading

Independent of compaction. When a single tool call returns more than the threshold, the full text is written to a directory and only a head/tail preview + a placeholder is left in context. The agent can `read_file` for the full content:

```java
HarnessAgent.builder()
    ...
    .toolResultEviction(ToolResultEvictionConfig.defaults())
    .build();
```

Defaults:

- Triggered at 80K characters
- Keeps ~2K chars at head + tail + a line "full content at `{path}`"
- `read_file` is excluded by default (to avoid re-offloading what was just read back)

Customize threshold or destination via `ToolResultEvictionConfig.builder()...build()`.

## Tools the agent can use itself

When memory is enabled, the agent gets two tools:

- `memory_search query="..."` — keyword scan over `MEMORY.md` + `memory/*.md`, up to 30 hits
- `memory_get path="memory/2026-06-02.md" startLine=10 endLine=40` — read a specific line range

When the model sees a "MEMORY truncated" note in the prompt, it typically calls `memory_search` to look further back.

## Background maintenance

When memory is enabled, a throttled background job also runs (triggered at each `call()` end with a minimum gap, default ~30 minutes max):

- Archives daily logs older than 90 days to `memory/archive/`
- Runs one `MEMORY.md` consolidation pass
- Prunes session logs older than 180 days

All numbers are tunable, but most projects don't need to touch them.

## Turn it off entirely

If you want to handle memory yourself or wire your own tools:

```java
HarnessAgent.builder()
    ...
    .disableMemoryHooks()      // disables flush + background maintenance
    .disableMemoryTools()      // skips memory_search / memory_get / session_search registration
    .build();
```

## Related Pages

- [Workspace](./workspace) — where `MEMORY.md` / `memory/` live in the workspace
- [Context](./context) — the never-compacted `*.log.jsonl` conversation log
- [Architecture](./architecture) — how facts in long conversations settle into `MEMORY.md`

---
title: "Memory"
description: "Two-layer long-term memory, conversation compaction, large tool-result offloading; prompts and trigger policy are customizable"
---

## Role

Lets the agent "remember facts across sessions" while keeping the conversation context bounded. Harness splits memory into two layers:

- **Layer 1 · daily log** `memory/YYYY-MM-DD.md` — append-only each day, raw and not deduped;
- **Layer 2 · curated long-term** `MEMORY.md` — periodically merged + deduped by the LLM; injected into the system prompt every reasoning step as long-term memory.

Three companion mechanisms:

- **Conversation compaction** — summarizes history and keeps a recent tail when context is too long;
- **Overflow safety net** — when the model actually errors, force a compaction and retry;
- **Large tool-result offloading** — offload to disk + placeholder when a single tool returns too much.

## The three LLM calls at a glance

The memory pipeline runs **three independent LLM calls**, each with its own prompt and triggering rules. This is the easiest place to get confused when customizing:

| # | Operation | Writes to | Default prompt | Customize via |
|---|------|----------|---------------|----------|
| 1 | **Flush** — extracts long-term facts from a conversation window | `memory/YYYY-MM-DD.md` (append) | `MemoryFlushManager.DEFAULT_FLUSH_PROMPT` | `MemoryConfig.builder().flushPrompt(...)` |
| 2 | **Consolidation** — merges daily ledgers into `MEMORY.md` | `MEMORY.md` (full rewrite) | `MemoryConsolidator.DEFAULT_CONSOLIDATION_PROMPT` | `MemoryConfig.builder().consolidationPrompt(...)` |
| 3 | **Compaction summary** — distills the conversation prefix into one summary message | Injected into the current context | `CompactionConfig.DEFAULT_SUMMARY_PROMPT` | `CompactionConfig.builder().summaryPrompt(...)` |

The first two are "long-term memory settling" and live on `MemoryConfig`; the third is "in-context compression" and lives on `CompactionConfig`. All three LLM calls share the agent's primary model by default, but `MemoryConfig` and `CompactionConfig` each support a `.model(...)` override so you can use a lighter model for these auxiliary operations.

## How the two layers work

```{mermaid}
graph LR
    Conv["conversation messages"]
    Conv -->|each call end / can be throttled| Flush["Flush LLM call"]
    Flush -->|extract new facts| Daily["memory/YYYY-MM-DD.md"]
    Conv -->|over threshold| Compactor["conversation compaction"]
    Compactor -->|offload raw| Sess["sessions/&lt;id&gt;.log.jsonl"]
    Compactor -->|flush again before summarizing| Flush
    Daily -. throttled background consolidation .-> MEM["MEMORY.md"]
    MEM -->|injected each reasoning step| SYS["system prompt"]
```

Key points:

- Layer 1 only appends, never dedupes; Layer 2 is periodically rewritten as a whole; **the two layers never overwrite each other**.
- Layer 2 is the only one injected into the prompt; Layer 1 waits to be merged.
- Raw messages dropped during compaction are also saved into a never-compacted log file (`*.log.jsonl`) for later audit or `session_search`.

## When flush fires

Flush (path 1) is triggered at three different moments:

1. **End of every `call()`** — the default `MemoryFlushMiddleware` behaviour. Can be retuned to `NEVER` or `THROTTLED(Duration)` via `flushTrigger`.
2. **Pre-compaction extraction** — when `CompactionConfig.flushBeforeCompact = true` (default), the conversation prefix is flushed once before being summarized.
3. **Overflow safety net** — when the model actually returns `context_length_exceeded`, the framework runs an emergency compaction that includes a flush.

All three sites share the **same** `flushPrompt`, so customizing it changes all three.

Both flush and offload are **asynchronous**: they are launched in a fire-and-forget fashion via `doOnComplete` after the response stream has ended, so they never block the current `call()` return. The caller receives the full response first; the flush LLM call and JSONL offload run in the background afterward.

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
| `flushBeforeCompact` | `true` | Extract new facts to the daily log before compacting (path 2) |
| `offloadBeforeCompact` | `true` | Append raw messages to the never-compacted log before compacting |
| `summaryPrompt` | see `DEFAULT_SUMMARY_PROMPT` | Path-3 summary prompt (must contain `{messages}`) |
| `model` | `null` (uses the agent's primary model) | Dedicated model for the compaction summarization call |

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

## Customizing the memory pipeline: `MemoryConfig`

`MemoryConfig` is the single place to configure flush / consolidation prompts, throttling, retention, and the per-call flush trigger. Every field has a default; not calling `.memory(...)` reproduces the historical behaviour bit-for-bit.

### Example 1: throttle per-call flush to save tokens

A flush LLM call after every agent invocation can add up on long sessions. Throttle it to at most once every 10 minutes:

```java
HarnessAgent.builder()
    ...
    .memory(MemoryConfig.builder()
        .flushTrigger(MemoryConfig.FlushTrigger.throttled(Duration.ofMinutes(10)))
        .build())
    .build();
```

Notes:

- `THROTTLED` only affects **path 1** (per-call flush). The flush embedded in compaction (path 2) and the overflow flush (path 3) still fire on their own triggers — compaction is rare, so those two are infrequent by construction.
- **Offload is unaffected**, the session JSONL is still written in full every call. `session_search` and session resumption keep working.

### Example 2: disable per-call flush entirely

```java
.memory(MemoryConfig.builder()
    .flushTrigger(MemoryConfig.FlushTrigger.never())
    .build())
```

Now flush only happens when compaction does (same cost as raw compaction).

> To turn off flush **and** background maintenance use `.disableMemoryHooks()`; `flushTrigger(NEVER)` only stops the per-call flush — background consolidation still runs.

### Example 3: extend the default prompt with project rules

```java
.memory(MemoryConfig.builder()
    .flushPrompt(MemoryFlushManager.DEFAULT_FLUSH_PROMPT + """

        Additional project rules:
        - Never record customer PII (names, emails, phone numbers).
        - Always use English for project-internal vocabulary.
        """)
    .build())
```

### Example 4: fully custom consolidation prompt

```java
.memory(MemoryConfig.builder()
    .consolidationPrompt("""
        You are merging daily memory ledgers into MEMORY.md.
        Keep within %d tokens (~%d chars). Output the complete file in markdown.
        ... your custom rules ...
        """)
    .build())
```

> **Important**: a custom consolidation prompt **must** contain exactly two `%d` placeholders (max-tokens then max-chars). The Builder rejects anything else at construction time so you don't hit a runtime `MissingFormatArgumentException`.

### Example 5: tune background maintenance

```java
.memory(MemoryConfig.builder()
    .consolidationMinGap(Duration.ofHours(2))   // background merge at most every 2h
    .dailyFileRetentionDays(30)                 // archive daily logs after 30 days
    .sessionRetentionDays(60)                   // prune session JSONL after 60 days
    .consolidationMaxTokens(8_000)              // raise MEMORY.md cap to 8K tokens
    .build())
```

### Example 6: use a smaller model for memory operations

Flush and consolidation don't need the full power of the primary reasoning model — use a cheaper one to save cost:

```java
HarnessAgent.builder()
    .model("openai:o3")                   // primary reasoning model
    .memory(MemoryConfig.builder()
        .model("openai:gpt-4.1-mini")     // lighter model for memory ops
        .build())
    .compaction(CompactionConfig.builder()
        .model("openai:gpt-4.1-mini")     // lighter model for compaction
        .build())
    .build();
```

`model(String)` resolves via `ModelRegistry.resolve()`; you can also pass a `Model` instance. When not set, falls back to the agent's primary model.

### `MemoryConfig` field reference

| Field | Default | Purpose |
|------|------|------|
| `model` | `null` (uses the agent's primary model) | Dedicated model for flush / consolidation; accepts a `Model` instance or a `"provider:model"` string |
| `flushPrompt` | `null` (uses `DEFAULT_FLUSH_PROMPT`) | SYSTEM prompt for path 1 |
| `consolidationPrompt` | `null` (uses `DEFAULT_CONSOLIDATION_PROMPT`) | Template for path 2 (must contain two `%d`) |
| `consolidationMaxTokens` | `4_000` | Token cap for `MEMORY.md` |
| `consolidationMinGap` | `30 min` | Throttle gap for background maintenance |
| `dailyFileRetentionDays` | `90` | Days before a daily log moves to `memory/archive/` |
| `sessionRetentionDays` | `180` | Days before a `*.log.jsonl` is pruned |
| `flushTrigger` | `FlushTrigger.always()` | `ALWAYS` / `NEVER` / `THROTTLED(Duration)` |

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

- Archives daily logs older than `dailyFileRetentionDays` (default 90 days) to `memory/archive/`
- Runs one `MEMORY.md` consolidation pass
- Prunes session logs older than `sessionRetentionDays` (default 180 days)

All thresholds are tunable via `.memory(MemoryConfig.builder()...)`, though most projects don't need to touch them.

## Turn it off entirely

If you want to handle memory yourself or wire your own tools:

```java
HarnessAgent.builder()
    ...
    .disableMemoryHooks()      // disables flush + background maintenance
    .disableMemoryTools()      // skips memory_search / memory_get / session_search registration
    .build();
```

`disableMemoryHooks()` is the nuclear option; if you only want to throttle, use `.memory(MemoryConfig.builder().flushTrigger(...).build())` instead.

## Related Pages

- [Workspace](./workspace.md) — where `MEMORY.md` / `memory/` live in the workspace
- [Context](../building-blocks/context.md) — the never-compacted `*.log.jsonl` conversation log
- [Architecture](./architecture.md) — how facts in long conversations settle into `MEMORY.md`

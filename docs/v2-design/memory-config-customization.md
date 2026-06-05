# Memory Config Customization — Flush / Consolidation Prompt & Flush Trigger Strategy

**Status**: Implemented
**Scope**: `agentscope-harness`
**Public API surface**: `MemoryConfig`, `HarnessAgent.Builder.memory(MemoryConfig)`, plus new constructors / public defaults on `MemoryFlushManager` and `MemoryConsolidator`.

## Why

The harness's long-term memory pipeline runs **three independent LLM calls**, each with its own prompt. Before this change, only one of them was customizable:

| # | Operation | Where the prompt lived | Customizable? |
|---|---|---|---|
| 1 | **Flush** — extract long-term memories from a conversation window into the daily ledger (`memory/YYYY-MM-DD.md`) | `MemoryFlushManager.FLUSH_SYSTEM_PROMPT` — `private static final` | ❌ |
| 2 | **Consolidation** — merge daily ledgers into the curated `MEMORY.md` | `MemoryConsolidator.CONSOLIDATION_PROMPT` — `private static final` | ❌ |
| 3 | **Compaction summary** — distill the conversation prefix into one summary message before reasoning | `CompactionConfig.summaryPrompt` (via builder) | ✅ |

There were two practical consequences:

1. **Inability to localize or specialise** the flush / consolidation prompts for a given product (e.g., a Chinese-only agent, a project that wants extra extraction rules, a research agent that wants a different consolidation style).
2. **Hidden token cost from per-call flush**. `MemoryFlushMiddleware.onAgent.doOnComplete` ran a flush LLM call after **every** agent invocation, with no throttling or off switch other than the nuclear `disableMemoryHooks()` option, which also kills consolidation/maintenance.

## What changed

### 1. Unified `MemoryConfig`

New value object at `agentscope-harness/.../memory/MemoryConfig.java`. Parallel in spirit to `CompactionConfig`; covers everything *outside* the in-context compaction pipeline:

| Field | Default | Purpose |
|---|---|---|
| `flushPrompt` | `null` (uses `MemoryFlushManager.DEFAULT_FLUSH_PROMPT`) | SYSTEM prompt for the flush LLM call |
| `consolidationPrompt` | `null` (uses `MemoryConsolidator.DEFAULT_CONSOLIDATION_PROMPT`) | Template for consolidation; must contain **exactly two `%d` placeholders** (max-tokens, max-chars) |
| `consolidationMaxTokens` | `4000` | Token budget for the consolidated `MEMORY.md` |
| `consolidationMinGap` | `Duration.ofMinutes(30)` | Throttle for `MemoryMaintenanceMiddleware` |
| `dailyFileRetentionDays` | `90` | Days before a daily ledger is moved to `memory/archive/` |
| `sessionRetentionDays` | `180` | Days before a `*.log.jsonl` is pruned |
| `flushTrigger` | `FlushTrigger.always()` | Per-call flush trigger policy (see below) |

`MemoryConfig.defaults()` returns a config equivalent to the harness's historical behaviour, so adopting this class is a no-op upgrade.

### 2. Flush trigger strategy

`MemoryConfig.FlushTrigger` controls the per-call flush hook. Three modes:

| Mode | Behaviour |
|---|---|
| `ALWAYS` (default) | Flush after every agent call. Matches historical behaviour. |
| `NEVER` | Skip the flush LLM call entirely. **Offload still runs** so the session JSONL stays complete (required by `SessionSearchTool` and resumption). Compaction-driven flush (`CompactionConfig.flushBeforeCompact`) and overflow-recovery flush are unaffected. |
| `THROTTLED(Duration)` | Flush at most once per `Duration`. Uses `AtomicReference#compareAndSet` for race-free single-winner semantics, mirroring `MemoryMaintenanceMiddleware`. |

Edge case: `FlushTrigger.throttled(Duration.ZERO)` collapses to the `always()` singleton, so callers don't need a special branch.

### 3. `summaryPrompt` stays put

Per discussion, `CompactionConfig.summaryPrompt` was **not** moved into `MemoryConfig`. The class javadoc on `CompactionConfig` now carries a "Memory prompt landscape" section that cross-references all three prompts so users can find them together regardless of which config they're reading.

This keeps zero churn for existing callers like `CodingBootstrap` and `CodingAgentFactory`, which already use `.compaction(CompactionConfig...)`.

## API at a glance

```java
HarnessAgent agent = HarnessAgent.builder()
        .name("my-agent")
        .model(myModel)
        .workspace(Paths.get(".agentscope/workspace"))
        // 1. Memory pipeline — prompts + trigger + maintenance gaps
        .memory(MemoryConfig.builder()
                .flushPrompt(MemoryFlushManager.DEFAULT_FLUSH_PROMPT + "\n\n额外指令: ...")
                .consolidationPrompt(MemoryConsolidator.DEFAULT_CONSOLIDATION_PROMPT)
                .consolidationMinGap(Duration.ofHours(1))
                .flushTrigger(MemoryConfig.FlushTrigger.throttled(Duration.ofMinutes(10)))
                .dailyFileRetentionDays(30)
                .build())
        // 2. Compaction pipeline (in-context summarization) — prompt + thresholds
        .compaction(CompactionConfig.builder()
                .summaryPrompt(myCustomSummaryPrompt)
                .triggerTokens(80_000)
                .build())
        .build();
```

## Design rationale

### Why a single `MemoryConfig` and not multiple Builder setters

`HarnessAgent.Builder` is already large. Adding 7+ `.memoryXxx(...)` setters would inflate its surface area, and most of these fields are correlated (you typically tune them together). A typed value object keeps the Builder focused and makes the memory configuration discoverable in one place.

### Why keep `summaryPrompt` in `CompactionConfig`

`CompactionConfig` owns the *in-context* summarization pipeline: trigger thresholds (token / message), keep window, flush-before-compact toggles, argument truncation. The summary prompt is one piece of that pipeline. Pulling it out would split a cohesive configuration across two classes and would require either deprecation churn or a forwarding shim. Cross-referencing in javadoc captures the relationship without the churn.

### Why `ALWAYS / NEVER / THROTTLED` and not `EVERY_N_CALLS`

`THROTTLED(Duration)` covers the dominant ops concern (cap LLM cost) and is semantically identical to the existing `MemoryMaintenanceMiddleware.minGap` knob — consistency wins. `EVERY_N_CALLS` would address a different concern (cap per conversation length) but adds a counter for marginal value; we can revisit if a real use case emerges.

### Why `NEVER` still runs offload

The session JSONL is the substrate for `SessionSearchTool` and cross-session resumption. Disabling offload alongside the flush prompt would silently break those features. Users who want to disable both should call `.disableMemoryHooks()` — that's the intentional kill-switch.

### Why custom consolidation prompt must contain exactly two `%d`

`MemoryConsolidator.consolidate(...)` runs `String.format(prompt, maxMemoryTokens, maxChars)`. A custom prompt without the placeholders would throw `MissingFormatArgumentException` at runtime — failing at Builder time gives a much better error and turn-around.

## Files touched

| File | Change |
|---|---|
| `agentscope-harness/.../memory/MemoryConfig.java` | **New** value object + `FlushTrigger` nested types |
| `agentscope-harness/.../memory/MemoryFlushManager.java` | `FLUSH_SYSTEM_PROMPT` → `public DEFAULT_FLUSH_PROMPT`; new 3-arg constructor; old 2-arg constructor preserved as forwarder |
| `agentscope-harness/.../memory/MemoryConsolidator.java` | `CONSOLIDATION_PROMPT` → `public DEFAULT_CONSOLIDATION_PROMPT`; new 4-arg constructor; old constructors preserved as forwarders |
| `agentscope-harness/.../memory/compaction/CompactionConfig.java` | Javadoc: added "Memory prompt landscape" section + `@see MemoryConfig` |
| `agentscope-harness/.../middleware/MemoryFlushMiddleware.java` | New `(ws, model, flushPrompt, trigger)` constructor; gate via `shouldFlushNow()`; old 2-arg constructor preserved |
| `agentscope-harness/.../HarnessAgent.java` | Builder field `memoryConfig` + `.memory(MemoryConfig)` method; build-time wiring of all four memory parameters; HarnessAgent now holds `memoryConfig` for `forceCompactAndRetry` |
| `agentscope-harness/src/test/java/.../memory/MemoryConfigTest.java` | **New** — defaults, builder validation, FlushTrigger semantics, builder reusability |
| `agentscope-harness/src/test/java/.../middleware/MemoryFlushMiddlewareTriggerTest.java` | **New** — gate behaviour for ALWAYS / NEVER / THROTTLED |

## Backward compatibility

- All previously public APIs are intact; new constructors are additive.
- Callers that never invoke `.memory(...)` get `MemoryConfig.defaults()` — bit-for-bit identical behaviour to the pre-change harness.
- `CodingBootstrap` and `CodingAgentFactory` were not modified; their existing `.compaction(...)` calls work unchanged.

---
title: "Compact tool history and restore archived messages"
zh_link: /dev/zh/docs/harness/jev/guides/context-compaction-api
---

Long-running tasks accumulate tool calls and results. `JevContextCompactor` plugs into Harness compaction to retain, truncate or remove historical pairs and archive them before adoption. This is useful for file reads and retrieval results that should be reread without re-executing tools.

## Example: keep current test evidence and move an old file read aside

A repair task read an obsolete file, then collected a current test failure. The user also said not to modify generated files. Cutting older messages by length could lose that constraint or leave a tool call without its result.

Specify eligible tools and pin messages you must retain. Within those boundaries JEV judges the usefulness of earlier material, treating calls and results as pairs. Archive current history before adopting a candidate so it can be restored without replay.

## 1. Define eligible and protected history

Use matching `agentscope-extensions-jev` and `agentscope-harness` versions. Harness is optional for the extension; client- or Judge-only applications do not need it.

```java
import io.agentscope.extensions.judge.jev.JevExecution;
import io.agentscope.extensions.judge.jev.context.FileJevContextArchive;
import io.agentscope.extensions.judge.jev.context.JevContextCompactor;
import io.agentscope.harness.agent.HarnessAgent;
import io.agentscope.harness.agent.memory.compaction.CompactionConfig;
import java.time.Duration;
import java.util.Set;

var archive = new FileJevContextArchive(archiveRoot, 20_000_000);
var limits = new JevContextCompactor.Config(
    0.2, 0.8,         // discardThreshold / keepThreshold: illustrative; calibrate before use
    6,                // preserveRecentMessages
    25_000, 30_000,    // maxStateTokens / maxRequestTokens: estimates
    128,              // maxQuestions: two questions per pair
    300,              // truncateHeadChars
    0.10,             // minimumReduction: fraction of serialized characters saved
    5_000_000,        // maxTranscriptChars
    Set.of("read_file", "search"), // host-approved tools eligible for compaction
    Set.of());        // pinned message IDs
var execution = new JevExecution.Options(
    JevExecution.Mode.SHADOW, Duration.ofSeconds(3), "context-v1",
    (ctx, record) -> System.out.println(record));
var strategy = new JevContextCompactor(client::systemOne, limits, execution, archive);

```

Only `read_file` and `search` history is eligible here. Keep the six most recent messages; require 10% reduction to justify a judgment. `truncateHeadChars=300` retains a result prefix when truncating, not a generated summary. Token limits are estimates; Harness still checks the final dispatch budget.

## 2. Register at the Harness compaction boundary

```java
var agent = HarnessAgent.builder()
    .name("research-agent")
    .model(model)
    .compaction(CompactionConfig.builder().strategy(strategy).build())
    .build();
```

`Config.defaults()` has an empty eligibleTools set. The single-argument `new JevContextCompactor(client)` defaults to OFF. ENFORCE requires an archive at construction and userId/sessionId in runtime context; Harness supplies agentId. The host, not JEV, specifies which tools are eligible, authorized or read-only.

Protect additional messages with metadata `jev.context.pinned=true` or `pinnedMessageIds`. By default exclude the first and recent messages, SYSTEM messages, messages with ThinkingBlock, errors, denials, suspended or incomplete results, and non-text tool output. ThinkingBlock is never sent to JEV; other unsupported message types cause semantic compaction to abstain. Rules take precedence over model scores.

## 3. Inspect recommendations before adopting them

Each pair receives keepCall and keepResult judgments:

- Both at or below discardThreshold: DROP_PAIR removes the call and result from current context together.
- keepResult at or below discardThreshold and keepCall at or above keepThreshold: TRUNCATE_RESULT keeps the call and result prefix; short results remain intact.
- keepResult at or above keepThreshold: KEEP.
- Otherwise: UNCERTAIN. Keep the whole pair; uncertainty is not grounds to discard it.

`plan(request)` returns `JevExecution.Decision<Plan>`. The Plan contains candidate messages, per-pair actions, Scores, request count, known tokens, state adaptation stage and archive reference. Pinned Scores are deterministic placeholders (reason=PINNED), not model probabilities. `INSUFFICIENT_REDUCTION` can retain per-pair decisions: adopting compaction was not worthwhile, not necessarily erroneous.

| Mode | Model decision | Archive | Subsequent behavior |
| --- | --- | --- | --- |
| OFF | None | None | Standard compaction |
| SHADOW | Judge eligible candidates | None | Record recommendations; leave standard compaction input unchanged |
| ENFORCE | Judge eligible candidates | Must succeed before adoption | Produce a candidate on success. On error, insufficient reduction due to uncertainty, overflow or archive failure, keep history without calling an extra summary model |

The policy runs only at existing compaction triggers. Lightweight trimming and tool-result offloading have separate switches. Final requests still pass through Harness budget checks; an over-budget dispatch stops even after a successful JEV decision.

Batches share the total budget. Missing questions, wrong types, invalid probabilities, empty responses or any failed batch prevent partial adoption. Cancellation propagates to the backend and prevents later state commits, but storage I/O already in progress may leave an unadopted archive. The host manages cleanup; cancellation does not guarantee remote billing stops.

## Archive and restore

An archive must succeed before compaction is adopted. See [Archive and restore context](/dev/en/docs/harness/jev/guides/context-archive) for file and BaseStore implementations, scope and restore code. Restore reads stored messages without replaying tools.

## Run and verify

Set `JEV_CP` using the [example build instructions](/dev/en/docs/harness/jev/guides/agent-integration-example#build-once-and-run-multiple-examples), then run:

```bash
java -cp "$JEV_CP" io.agentscope.examples.jev.JevContextCompactionExample
```

A real HarnessAgent with a scripted model and synthetic JEV responses archives the old file read as a pair, retains current test evidence and the instruction to leave generated files alone, calls the model once and restores six original messages. Explicit `InMemoryAgentStateStore` avoids reusing default persistent sessions between runs. No historical tools are dispatched.

[Full source](/examples/jev/source/JevContextCompactionExample.java.txt). Synthetic judgments exercise protection and archive recovery. Measure actual reduction, subsequent answer quality and model latency on your own traces.

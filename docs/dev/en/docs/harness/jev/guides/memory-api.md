---
title: "Check long-term memory value and factual conflicts"
zh_link: /dev/zh/docs/harness/jev/guides/memory-api
---

`JevMemoryGate` reviews a candidate fact for lasting value and factual conflict before a memory write. It recommends admission; your application's memory workflow owns writes, updates and deletion.

## Example: remember a preference without overwriting an older fact silently

A user explicitly asks for concise answers from now on. That may be worth retaining. But if a candidate comes from an undated note contradicting an existing preference, first check its source and whether it is an explicit update.

JEV separately assesses lasting value and conflict without evidence of an update. Both checks must pass; high value cannot compensate for conflict. Code checks ownership before any model request.

## 1. Construct a candidate with ownership and provenance

```java
import io.agentscope.extensions.judge.jev.application.JevMemoryGate;
import java.util.List;

String owner = "user-42";
var candidate = new JevMemoryGate.Fact(
    owner, "Prefers concise answers", "Explicit request in the current turn");
var existingFacts = List.<JevMemoryGate.Fact>of();
```

Derive `owner` from authenticated identity. Describe actual source evidence in `source`. Supplying a source string does not verify provenance. Do not mix in another user's memory.

## 2. Review before writing

```java
var gate = new JevMemoryGate(judge);
var pending = gate.assess(owner, candidate, existingFacts);
var observed = pending.doOnNext(result -> {
    System.out.println("Admission status: " + result.status());
    System.out.println("Condition findings: " + result.findings());
});
```

Initialize `judge` as in [Application component setup](/dev/en/docs/harness/jev/guides/application-api#initialize-the-components). Compose this Mono into the write workflow and observe results before enforcement. Only PASS means both checks passed; host authorization, provenance and idempotency checks still apply.

| Status | Handling the preference |
|---|---|
| PASS | Proceed to the application's write/update review |
| FAIL | Keep existing memory; inspect the failed condition before any overwrite |
| INCONCLUSIVE | Clarify or defer writing |
| ERROR | No valid decision; do not treat as a pass |

## Run and verify

Set `JEV_CP` using the [example build instructions](/dev/en/docs/harness/jev/guides/agent-integration-example#build-once-and-run-multiple-examples), then run:

```bash
java -cp "$JEV_CP" io.agentscope.examples.jev.JevApplicationScenarios memory
```

The example yields PASS without conflict and FAIL with high scripted conflict. Both branches perform zero writes. Offline probabilities do not measure model quality. [JevApplicationScenarios source](/examples/jev/source/JevApplicationScenarios.java.txt)

## Connect to Harness extraction

The optional adapter gates extracted facts before ledger append:

```java
import io.agentscope.harness.agent.memory.MemoryConfig;

MemoryConfig memory = MemoryConfig.builder()
    .admission(new JevMemoryGate(judge).admission())
    .build();
// Pass memory to HarnessAgent.builder().memory(memory).
```

Admission is disabled by default. `assess()` keeps its standalone policy; `admission()` accepts source-supported corrections and rejects unsupported contradictions. Both use 0.2/0.8 thresholds. Candidates receive the source batch, memory excerpts and earlier accepted candidates; the excerpts are not a complete conflict index.

With bullets, preambles and unindented closing prose are ignored; otherwise nonempty prose becomes one bullet candidate. Rejection consumes the source; ERROR or INCONCLUSIVE retains the batch, so persistent judge errors block extraction until recovery or disabling admission. Inline extraction records completed decisions for replay within the same Durable finalization frame; later turns judge again. Decisions are indexed once per batch. Admission stays serial so later candidates see earlier accepted ones.

An accepted correction is appended; it does not delete an older fact. This hook covers extraction, including the legacy pre-compaction path. It does not gate `memory_save` or consolidation.

## Limits and errors

The candidate and existing facts must all belong to the explicit owner; otherwise an argument error occurs before any model call. The built-in `durable` condition expects true and `conflict` expects false, with example 0.2/0.8 thresholds. Define custom admission criteria with [JevJudge](/dev/en/docs/harness/jev/guides/judge-api).

Standalone `assess()` requires the application to enforce its result at the write boundary. A denial never deletes old memory, and context compaction does not authorize long-term memory writes. Timeout and cancellation follow the Judge contract.

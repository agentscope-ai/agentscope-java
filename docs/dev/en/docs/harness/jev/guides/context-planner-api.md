---
title: "Plan which tool exchanges to retain or offload"
zh_link: /dev/zh/docs/harness/jev/guides/context-planner-api
---

`JevContextPlanner` recommends which application-managed tool exchanges to retain or offload and can restore complete pairs in order. Use it when you assemble your own context and want to reduce old material before the next model call.

## Example: move obsolete search results out of a repair task

A repair task read earlier-version documentation and later obtained test results for the current patch. Including the full old search in every request consumes context, but dropping only the result leaves a dangling call.

Group each call and result into an `Exchange`. JEV judges only complete unpinned exchanges. Pin the current test evidence so it always stays.

## 1. Prepare complete exchanges

```java
import io.agentscope.extensions.judge.jev.application.JevContextPlanner;
import java.util.List;

var exchanges = List.of(
    new JevContextPlanner.Exchange(
        "old-search", "search(old issue)", "Previous-version documentation", false),
    new JevContextPlanner.Exchange(
        "current-test", "test(current change)", "Current test evidence", true));
```

The example uses strings for call/result; applications may pass separate message snapshots. The last argument `pinned=true` requires retention without model discretion. Include both a call and result in each pair and use unique IDs.

## 2. Plan for the current task

```java
var planner = new JevContextPlanner(selector);
var pending = planner.plan(
    ctx, "Fix the issue using the current test evidence", exchanges);
```

Initialize `selector` as in [Application component setup](/dev/en/docs/harness/jev/guides/application-api#initialize-the-components); `ctx` belongs to the current call. Exchanges explicitly judged unnecessary go to `archive()`. Uncertain exchanges remain in `retained()`.

## 3. Inspect the smaller context and restore on demand

```java
var observed = pending.doOnNext(plan -> {
    System.out.println("Retain for the next input: " + plan.retained());
    System.out.println("Can move aside: " + plan.archive().keySet());
    System.out.println("Complete material in original order: " + plan.restore());
});
```

If the old search is explicitly unnecessary, `retained()` contains only the current test, `archive()` contains the old search and `restore()` returns both in original order. Only change the next model input when your application adopts the plan. In SHADOW, observe only.

## Run and verify

Set `JEV_CP` using the [example build instructions](/dev/en/docs/harness/jev/guides/agent-integration-example#build-once-and-run-multiple-examples), then run:

```bash
java -cp "$JEV_CP" io.agentscope.examples.jev.JevApplicationScenarios context
```

The offline example puts `old-search` in the in-memory archive, retains `current-test` and restores both in order without re-executing tools. [JevApplicationScenarios source](/examples/jev/source/JevApplicationScenarios.java.txt)

## Persistence and failure handling

`archive()` is an in-memory map. It neither writes files, frees all process memory nor changes AgentState automatically. The Plan retains references to the host call/result objects; leave them unchanged while in use. If judgment fails or is off, retain all exchanges.

For Harness-triggered compaction, durable archiving before adoption and cross-request recovery, see [Context compaction](/dev/en/docs/harness/jev/guides/context-compaction-api) and [Archive recovery](/dev/en/docs/harness/jev/guides/context-archive).

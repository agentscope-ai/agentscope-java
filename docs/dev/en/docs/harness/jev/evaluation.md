---
title: "Evaluate your Harness and decision backend"
zh_link: /dev/zh/docs/harness/jev/evaluation
---

Compare the original workflow, a JEV-assisted workflow and a text model on the same inputs. Measure decision quality, actual Harness behavior and end-to-end outcomes separately.

## Is a refund check worth the extra call?

A guard might block every unsafe refund and every legitimate one as well. For a request such as “check the order and refund it if it has not shipped,” measure false approvals, blocked legitimate calls and added latency before enforcing the check.

The [refund guard comparison](/examples/jev/tool-guard/README.md.txt) includes 160 synthetic cases, splits, label rationales, offline behavior checks and measured JEV/Qwen results in `agentscope-examples/jev/benchmarks/tool-guard`. Its CLI differs from the general benchmarks below; follow its README. The [guard guide](/dev/en/docs/harness/jev/guides/tool-guard-api#refund-scenario-comparison) summarizes the results.

## Choose what to measure

- **Decision:** Compare tool relevance, classifications, reviews, routing labels or compaction recommendations against independent labels.
- **Execution:** Did a denial prevent dispatch? Did an allowed call run exactly once, with a matching result? Did work stop after cancellation?
- **Task:** Measure answer correctness, evidence recall, retention of key facts, task success and total latency.

A correct action label does not prove a browser task finished. Matching routing labels do not demonstrate cost savings. A good compaction recommendation does not prove the next answer is correct.

## Fixed inputs and runnable benchmarks

The examples provide synthetic inputs for learning the formats and running comparisons. For business acceptance, add independent labels and difficult cases representative of your traffic.

| Area | Program | Input |
|---|---|---|
| Trace evaluation | [JevTraceBenchmark](/examples/jev/source/JevTraceBenchmark.java.txt) | [trace-cases.jsonl](/examples/jev/data/trace-cases.jsonl.txt) |
| Context compaction | [JevContextBenchmark](/examples/jev/source/JevContextBenchmark.java.txt) | [context-cases.jsonl](/examples/jev/data/context-cases.jsonl.txt) |
| Long-running task supervision | [JevSupervisionBenchmark](/examples/jev/source/JevSupervisionBenchmark.java.txt) | [supervision-cases.jsonl](/examples/jev/data/supervision-cases.jsonl.txt) |
| Code review | [JevCodeReviewBenchmark](/examples/jev/source/JevCodeReviewBenchmark.java.txt) | [review-cases.jsonl](/examples/jev/data/review-cases.jsonl.txt) |
| Retrieved evidence | [JevEvidenceBenchmark](/examples/jev/source/JevEvidenceBenchmark.java.txt) | [evidence-cases.jsonl](/examples/jev/data/evidence-cases.jsonl.txt) |
| Stage routing | [JevPhaseRoutingBenchmark](/examples/jev/source/JevPhaseRoutingBenchmark.java.txt) | [phase-routing-cases.jsonl](/examples/jev/data/phase-routing-cases.jsonl.txt) |
| Visible browser actions | [JevBrowserBenchmark](/examples/jev/source/JevBrowserBenchmark.java.txt) | [browser-cases.jsonl](/examples/jev/data/browser-cases.jsonl.txt) |

These programs replay decision inputs rather than full production tasks. Gold labels are used only for scoring and are never sent to the backend. Source data is in `agentscope-examples/jev/src/test/resources/jev`.

## Run a baseline and compare the same cases

Set `JEV_CP` using the [example build instructions](/dev/en/docs/harness/jev/guides/agent-integration-example#build-once-and-run-multiple-examples). For trace evaluation:

```bash
JEV_DATA="agentscope-examples/jev/src/test/resources/jev/trace-cases.jsonl"
java -cp "$JEV_CP" io.agentscope.examples.jev.JevTraceBenchmark \
  --baseline "$JEV_DATA" /tmp/trace-baseline.json
java -cp "$JEV_CP" io.agentscope.examples.jev.JevTraceBenchmark \
  --offline "$JEV_DATA" /tmp/trace-offline.json
```

All programs in the table support `--baseline`. Only the trace program provides the `--offline` synthetic decision mode shown here. Choose an output path that does not exist so a previous report is not overwritten.

Enable a live backend explicitly and choose the endpoint and model:

```bash
java -cp "$JEV_CP" io.agentscope.examples.jev.JevTraceBenchmark \
  --live-jev "$JEV_DATA" /tmp/trace-jev.json jev-latest
java -cp "$JEV_CP" io.agentscope.examples.jev.JevTraceBenchmark \
  --live-qwen "$JEV_DATA" /tmp/trace-qwen.json "$QWEN_MODEL" "$QWEN_ENDPOINT"
```

JEV reads `TYPESAFE_API_KEY`; Qwen reads `DASHSCOPE_API_KEY`. Set `QWEN_ENDPOINT` to the full Chat Completions compatible endpoint for your account's region. For other benchmarks, substitute the class and input path from the table, keeping the argument order. Live calls incur usage; offline runs do not read credentials.

## Interpret the report and calibrate thresholds

Record correct decisions, mistakes, abstentions, skips and call errors by scenario. Include abstentions and errors in the denominator for overall accuracy on labeled cases. Report enforcement coverage and the error rate on enforced cases separately so dropping hard cases cannot inflate apparent accuracy.

Separate decision P50/P95 latency from end-to-end Agent P50/P95. Token totals cover only responses that returned usage; unknown prices or usage do not mean zero cost. Select thresholds on an independent calibration set and compare on held-out data. Agreement between models is not a gold label.

Record definition and model versions, input hashes, region and mode. Report sample sizes. For business evaluation, consider paired confidence intervals, permutations of option order and names, and concurrent cancellation checks. Observe recommendations in SHADOW before deciding where to enforce them.

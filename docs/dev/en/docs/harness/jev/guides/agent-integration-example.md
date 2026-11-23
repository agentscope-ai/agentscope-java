---
title: "Run the JEV examples"
zh_link: /dev/zh/docs/harness/jev/guides/agent-integration-example
---

All examples are in `agentscope-examples/jev`, in package `io.agentscope.examples.jev`. Start with synthetic decisions to exercise the Harness, then substitute your own backend and business evidence.

## Build once and run multiple examples

From the repository root:

```bash
mvn -pl agentscope-dependencies-bom,agentscope-distribution/agentscope-bom,agentscope-examples/jev -am install -DskipTests
mvn -pl agentscope-examples/jev dependency:build-classpath -Dmdep.outputFile=/tmp/jev-examples-cp.txt
JEV_CP="agentscope-examples/jev/target/classes:$(cat /tmp/jev-examples-cp.txt)"
java -cp "$JEV_CP" io.agentscope.examples.jev.JevIntegrationExample
```

The combined example looks up refund conditions, retrieves authorized evidence, generates a draft that omits a condition, then revises only the text after review fails. The final answer retains the refund conditions. It uses in-memory retrieval, a scripted generation model and synthetic decisions, without credentials or model service calls.

## What to expect

The final reply should retain both conditions: the item must be unused and within the seven-day window. The generation model runs three times: propose retrieval, generate an incomplete draft, then rewrite text without tools. Console review statistics confirm the scripted branches rather than online accuracy.

To explore one capability, run its standalone scenario below and use the corresponding API guide to replace the inputs and backend.

## Run examples by capability

Replace the class name at the end of the previous Java command with an entry below. All run offline by default, except for the local browser example described separately.

| Capability | Entry point and full source |
|---|---|
| Standalone tool selection and model routing | [JevHarnessScenarios](/examples/jev/source/JevHarnessScenarios.java.txt), with `selection` or `routing` |
| Tool selection, guards and per-call routing | [JevHarnessExample](/examples/jev/source/JevHarnessExample.java.txt) |
| Customer support Judge | [JevCustomerSupportExample](/examples/jev/source/JevCustomerSupportExample.java.txt) |
| Retrieval, guardrails and answer revision | [JevIntegrationExample](/examples/jev/source/JevIntegrationExample.java.txt) |
| Trace evaluation | [JevTraceEvaluationExample](/examples/jev/source/JevTraceEvaluationExample.java.txt) |
| Context compaction and recovery | [JevContextCompactionExample](/examples/jev/source/JevContextCompactionExample.java.txt) |
| Continuous supervision and verification evidence | [JevSupervisionExample](/examples/jev/source/JevSupervisionExample.java.txt) |
| Code review | [JevCodeReviewExample](/examples/jev/source/JevCodeReviewExample.java.txt) |
| Retrieved evidence and draft revision | [JevEvidenceExample](/examples/jev/source/JevEvidenceExample.java.txt) |
| Automatic and explicit stage routing | [JevPhaseRoutingExample](/examples/jev/source/JevPhaseRoutingExample.java.txt) |
| Read-only browser tool | [JevBrowserExample](/examples/jev/source/JevBrowserExample.java.txt) |
| Standalone application and review scenarios | [JevApplicationScenarios](/examples/jev/source/JevApplicationScenarios.java.txt); omit arguments to run all, or specify `classification`, `context`, `memory`, `support`, `team`, `browser`, `judge`, `evaluation`, `task-review`, `rag` or `draft` |
| Classification, memory, teams and application components | [JevApplicationExample](/examples/jev/source/JevApplicationExample.java.txt) |

Scripted probabilities demonstrate status and execution branches; they cannot measure real model accuracy. For benchmark inputs and live backend options, see [Evaluate your Harness](/dev/en/docs/harness/jev/evaluation).

## Use a local browser

`JevBrowserLocalExample` serves a loopback page and uses an explicitly supplied Chromium executable. By default it uses synthetic decisions and does not access external websites or model APIs:

```bash
java -cp "$JEV_CP" io.agentscope.examples.jev.JevBrowserLocalExample \
  --browser "/Applications/Google Chrome.app/Contents/MacOS/Google Chrome"
```

Substitute the browser path for your system. The session visits the policy page, an independent verifier checks its content, and the page and local service close. [Browser API](/dev/en/docs/harness/jev/guides/browser-execution-api)

## Make live calls

The support example calls the client only with `--live`. Other ordinary examples have no common live switch: replace the scripted caller and model as shown in each guide. Browser and benchmark programs have their own explicit `--live-jev` / `--live-qwen` options. Environment variables alone never switch an offline example to live calls.

---
title: "Build Agent Harnesses with JEV"
zh_link: /dev/zh/docs/harness/jev/index
---

<Note>
This guide covers the dev version. Its APIs and examples may differ from released packages. For AgentScope Java `2.0.4`, see the [2.0 documentation](/v2/en/jev/index).
</Note>


JEV supplies typed decisions where a Harness needs semantic judgment: which tools fit, whether an answer has evidence, which history to retain or when a task needs verification. Call components directly or integrate them into Agent execution as middleware or read-only tools.

## Capabilities

| Area | Typical task | Guides |
|---|---|---|
| Trace evaluation and reusable definitions | Check tool use, grounding and requirement coverage with shared offline and online metrics | [Review definitions](/dev/en/docs/harness/jev/guides/judge-api), [trace evaluation](/dev/en/docs/harness/jev/guides/trace-evaluation-api), [custom metrics](/dev/en/docs/harness/jev/guides/metric-definitions), [batch evaluation](/dev/en/docs/harness/jev/guides/evaluator-api) |
| Context management and recovery | Preserve constraints, reduce tool history and recover original material | [Compaction and archives](/dev/en/docs/harness/jev/guides/context-compaction-api), [explicit exchange planning](/dev/en/docs/harness/jev/guides/context-planner-api), [memory admission](/dev/en/docs/harness/jev/guides/memory-api) |
| Progress and delivery supervision | Observe drift, stalls, missing verification and readiness for review | [Continuous supervision](/dev/en/docs/harness/jev/guides/supervision-api), [verification evidence](/dev/en/docs/harness/jev/guides/supervision-evidence), [checkpoint review](/dev/en/docs/harness/jev/guides/task-review) |
| Tool selection and execution guards | Narrow a large catalog; check proposed refunds or outbound messages | [Tool selection](/dev/en/docs/harness/jev/guides/tool-selection-api), [pre-execution guards](/dev/en/docs/harness/jev/guides/tool-guard-api) |
| Model, stage and team routing | Choose candidates by complexity, tools, capacity and quota | [Per-call routing](/dev/en/docs/harness/jev/guides/model-routing-api), [explicit stages](/dev/en/docs/harness/jev/guides/phase-routing-api), [team recommendations](/dev/en/docs/harness/jev/guides/team-routing) |
| Retrieval and knowledge answers | Filter injection, retain conflicting evidence, rerank results and check grounding | [Evidence processing](/dev/en/docs/harness/jev/guides/evidence-pipeline-api), [relevance and grounding](/dev/en/docs/harness/jev/guides/rag-api), [Knowledge adapter](/dev/en/docs/harness/jev/guides/knowledge-adapter-api) |
| Content screening and answer revision | Screen input/output before publication; revise only rejected drafts | [Content guardrails](/dev/en/docs/harness/jev/guides/content-guardrail-api), [Agent answer revision](/dev/en/docs/harness/jev/guides/answer-refinement-api), [standalone draft pipeline](/dev/en/docs/harness/jev/guides/draft-pipeline-api) |
| Code review and evidence location | Screen diffs or source snapshots for risk and locate evidence | [Staged code review](/dev/en/docs/harness/jev/guides/code-review-api) |
| Read-only browser navigation and verification | Follow visible links to find information on authorized pages | [Browser execution](/dev/en/docs/harness/jev/guides/browser-execution-api), [action proposals only](/dev/en/docs/harness/jev/guides/browser-proposals) |
| Business classification and support | Route multiple requests and check conditional refunds and reply promises | [Candidate classification](/dev/en/docs/harness/jev/guides/application-api), [support review](/dev/en/docs/harness/jev/guides/support-api) |

## Combine checks in a support workflow

For “refund me if it hasn't shipped,” keep both lookup and refund tools during selection. Before dispatch, check that current evidence supports the refund. Review the final reply for invented payment or omitted conditions. After the invocation, use trace metrics to assess tool use and answer quality.

Enable each capability independently. Start with the [refund tool guard](/dev/en/docs/harness/jev/guides/tool-guard-api) for context, configuration and failure handling, or run the offline example below to see a rejected draft revised without repeating an action.

## Choose an integration

- **Direct API:** If your application already has the input, evidence or draft, call the Judge, evaluator or evidence processor and handle the result.
- **Harness:** Register middleware with `.middleware(...)`, plug into `.compaction(...)`, or register a Toolkit tool at the appropriate execution step.

A JEV decision grants no tool permission and does not prove a business operation succeeded. Your application owns authorization, ACLs, argument validation, idempotency and independent completion checks. Each guide distinguishes recommendations from delivered inputs and actual results.

## Start with an offline example

```bash
mvn -pl agentscope-dependencies-bom,agentscope-distribution/agentscope-bom,agentscope-examples/jev -am install -DskipTests
mvn -pl agentscope-examples/jev dependency:build-classpath -Dmdep.outputFile=/tmp/jev-examples-cp.txt
java -cp "agentscope-examples/jev/target/classes:$(cat /tmp/jev-examples-cp.txt)" io.agentscope.examples.jev.JevIntegrationExample
```

[Run the example](/dev/en/docs/harness/jev/guides/agent-integration-example) to see retrieval, review and bounded revision without credentials. Runnable source is under `agentscope-examples/jev` in package `io.agentscope.examples.jev`.

Before connecting a live model, read [Noul, Choice, Score and decision statuses](/dev/en/docs/harness/jev/concepts), then configure the [client](/dev/en/docs/harness/jev/guides/client) and [execution mode](/dev/en/docs/harness/jev/guides/harness-runtime). Calibrate example thresholds on your own samples.

## Adopt decisions explicitly

Middleware defaults to OFF. SHADOW records recommendations while preserving downstream input; ENFORCE applies the component-specific policy. Trace evaluation and ongoing supervision support only OFF/SHADOW. Direct Judge calls evaluate when invoked.

---
title: Build Agent Harnesses with JEV
zh_link: /v2/zh/jev/index
---

JEV adds typed semantic decisions to your Harness: tool selection, response review, context retention, routing and task supervision. Use components directly, register middleware or tools, or configure supported capabilities through AgentScope Service.

For example, a support agent can select order tools, check a conditional refund before dispatch, review its final reply against policy evidence, and evaluate the completed trace. Enable each step independently; a semantic approval never proves that the refund was executed.

## Capabilities

| Area | User guide |
|---|---|
| Agent trace evaluation and reusable definitions | [Trace evaluation](/v2/zh/jev/guides/trace-evaluation-api), [definitions](/v2/zh/jev/guides/metric-definitions) |
| Context governance and recovery | [Compaction](/v2/zh/jev/guides/context-compaction-api), [archives](/v2/zh/jev/guides/context-archive), [memory admission](/v2/zh/jev/guides/memory-api) |
| Long-running task supervision | [Progress observation](/v2/zh/jev/guides/supervision-api), [verification evidence](/v2/zh/jev/guides/supervision-evidence) |
| Tool selection and execution checks | [Selection](/v2/zh/jev/guides/tool-selection-api), [pre-execution checks](/v2/zh/jev/guides/tool-guard-api) |
| Model, stage and team routing | [Model routing](/v2/zh/jev/guides/model-routing-api), [stages](/v2/zh/jev/guides/phase-routing-api), [team recommendations](/v2/zh/jev/guides/team-routing) |
| Retrieval evidence and answer quality | [Evidence processing](/v2/zh/jev/guides/evidence-pipeline-api), [content checks](/v2/zh/jev/guides/content-guardrail-api), [draft revision](/v2/zh/jev/guides/answer-refinement-api) |
| Code review and browser tasks | [Code evidence](/v2/zh/jev/guides/code-review-api), [read-only navigation](/v2/zh/jev/guides/browser-execution-api) |
| Business classification and support | [Candidate selection](/v2/zh/jev/guides/application-api), [support review](/v2/zh/jev/guides/support-api) |

Detailed guides are currently available in Chinese. Each explains the supported behavior, application inputs and APIs.

## Start with an offline example

```bash
mvn -pl agentscope-dependencies-bom,agentscope-distribution/agentscope-bom,agentscope-examples/jev -am install -DskipTests
mvn -pl agentscope-examples/jev dependency:build-classpath -Dmdep.outputFile=/tmp/jev-examples-cp.txt
java -cp "agentscope-examples/jev/target/classes:$(cat /tmp/jev-examples-cp.txt)" io.agentscope.examples.jev.JevIntegrationExample
```

This example uses scripted generation and judgment responses; no API key is required. The final reply retains the unused-order and seven-day conditions. The example checks that revision uses no tools. See the [scenario catalog](/v2/zh/jev/guides/agent-integration-example) for focused examples and their API guides. Runnable examples live in `agentscope-examples/jev`. Add `agentscope-extensions-jev` to your application and the same-version `agentscope-harness` dependency when using Harness integrations.

## Adopt decisions explicitly

Middleware defaults to OFF. SHADOW records recommendations while preserving downstream input; ENFORCE applies the component-specific policy. Trace evaluation and ongoing supervision support only OFF/SHADOW. Direct Judge calls evaluate when invoked.

Semantic decisions do not grant permissions or prove execution succeeded. Keep deterministic authorization, ACLs and completion checks in your application. Calibrate thresholds using your own labeled data. See [client setup](/v2/zh/jev/guides/client), [runtime modes](/v2/zh/jev/guides/harness-runtime), [evaluation](/v2/zh/jev/evaluation) and [Service configuration](/v2/zh/jev/guides/service-api).

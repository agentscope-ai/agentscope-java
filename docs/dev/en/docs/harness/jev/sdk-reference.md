---
title: "API and package index"
zh_link: /dev/zh/docs/harness/jev/sdk-reference
---

Use this page to find imports. The base dependency is `io.agentscope:agentscope-extensions-jev` at the project BOM version. Harness integration also requires a matching `agentscope-harness`.

| Package (prefix `io.agentscope.extensions.judge.jev`) | Main entry points | Guides |
|---|---|---|
| Root package | JevClient, SystemOneRequest, JevJudge, JevExecution, JevGuardrail | [Client](/dev/en/docs/harness/jev/guides/client), [review definitions](/dev/en/docs/harness/jev/guides/judge-api) |
| example | JevToolSelectionMiddleware, JevAutoModeMiddleware, JevModelRouterMiddleware | [Execution modes](/dev/en/docs/harness/jev/guides/harness-runtime) |
| evaluation | JevTrace, JevTraceMetric, JevTraceEvaluator, JevTraceEvaluationMiddleware, JevTraceRunner, JevEvaluator, JevEvaluationRunner, JevTextBackend | [Trace evaluation](/dev/en/docs/harness/jev/guides/trace-evaluation-api) |
| context | JevContextCompactor, JevContextArchive, FileJevContextArchive, StoreJevContextArchive | [Context compaction](/dev/en/docs/harness/jev/guides/context-compaction-api) |
| supervision | JevTaskSupervisor, JevSupervisionMiddleware, SupervisionEvidence | [Continuous supervision](/dev/en/docs/harness/jev/guides/supervision-api) |
| routing | JevPhaseRouting, JevRouteCatalog | [Stage routing](/dev/en/docs/harness/jev/guides/phase-routing-api) |
| evidence | JevPassage, JevEvidenceProcessor, JevEvidenceTool | [Retrieved evidence](/dev/en/docs/harness/jev/guides/evidence-pipeline-api) |
| review | JevReviewInput, JevCodeReviewer, JevCodeReviewTool, ReviewRegions | [Code review](/dev/en/docs/harness/jev/guides/code-review-api) |
| browser | JevBrowserSession, JevBrowserNavigator, JevBrowserReadTool, JevPlaywrightSession | [Read-only browser](/dev/en/docs/harness/jev/guides/browser-execution-api) |
| integration | JevResponseMiddleware, JevKnowledge, JevKnowledgeTool | [Answer review](/dev/en/docs/harness/jev/guides/answer-refinement-api), [Knowledge adapter](/dev/en/docs/harness/jev/guides/knowledge-adapter-api) |
| application | JevCandidateSelector, JevCustomerSupport, JevRag, JevDraftPipeline, JevSupervisor, JevContextPlanner, JevMemoryGate, JevStageRouter, JevTeamPlanner, JevBrowserPlanner | [Application components](/dev/en/docs/harness/jev/guides/application-api) |

`example` is the existing package for the three reusable middleware classes. Runnable examples are separate, in `agentscope-examples/jev` under `io.agentscope.examples.jev`. Do not add examples as production dependencies.

For a pre-dispatch refund check, import `example.JevAutoModeMiddleware`. For final answer review, import `integration.JevResponseMiddleware`. To judge an existing draft, use `JevJudge` from the root package. Choose the task first and use the table to locate its imports.

Application code supplies the models, ACLs, Sources, recorders and business stores used in snippets. For complete imports and runnable wiring, see the [example catalog](/dev/en/docs/harness/jev/guides/agent-integration-example).

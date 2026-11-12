---
title: "API 与包索引"
en_link: /dev/en/docs/harness/jev/sdk-reference
---

通过本页查找导入位置。基础依赖是 `io.agentscope:agentscope-extensions-jev`，与项目 BOM 使用同一版本；Harness 集成另外依赖同版本 `agentscope-harness`。

| 包（前缀 `io.agentscope.extensions.judge.jev`） | 主要入口 | 使用说明 |
|---|---|---|
| 根包 | JevClient、SystemOneRequest、JevJudge、JevExecution、JevGuardrail | [客户端](/dev/zh/docs/harness/jev/guides/client)、[评审定义](/dev/zh/docs/harness/jev/guides/judge-api) |
| example | JevToolSelectionMiddleware、JevAutoModeMiddleware、JevModelRouterMiddleware | [运行模式](/dev/zh/docs/harness/jev/guides/harness-runtime) |
| evaluation | JevTrace、JevTraceMetric、JevTraceEvaluator、JevTraceEvaluationMiddleware、JevTraceRunner、JevEvaluator、JevEvaluationRunner、JevTextBackend | [轨迹评估](/dev/zh/docs/harness/jev/guides/trace-evaluation-api) |
| context | JevContextCompactor、JevContextArchive、FileJevContextArchive、StoreJevContextArchive | [上下文压缩](/dev/zh/docs/harness/jev/guides/context-compaction-api) |
| supervision | JevTaskSupervisor、JevSupervisionMiddleware、SupervisionEvidence | [持续监督](/dev/zh/docs/harness/jev/guides/supervision-api) |
| routing | JevPhaseRouting、JevRouteCatalog | [阶段路由](/dev/zh/docs/harness/jev/guides/phase-routing-api) |
| evidence | JevPassage、JevEvidenceProcessor、JevEvidenceTool | [检索证据](/dev/zh/docs/harness/jev/guides/evidence-pipeline-api) |
| review | JevReviewInput、JevCodeReviewer、JevCodeReviewTool、ReviewRegions | [代码评审](/dev/zh/docs/harness/jev/guides/code-review-api) |
| browser | JevBrowserSession、JevBrowserNavigator、JevBrowserReadTool、JevPlaywrightSession | [只读浏览器](/dev/zh/docs/harness/jev/guides/browser-execution-api) |
| integration | JevResponseMiddleware、JevKnowledge、JevKnowledgeTool | [回答审核](/dev/zh/docs/harness/jev/guides/answer-refinement-api)、[Knowledge 适配](/dev/zh/docs/harness/jev/guides/knowledge-adapter-api) |
| application | JevCandidateSelector、JevCustomerSupport、JevRag、JevDraftPipeline、JevSupervisor、JevContextPlanner、JevMemoryGate、JevStageRouter、JevTeamPlanner、JevBrowserPlanner | [应用组件](/dev/zh/docs/harness/jev/guides/application-api) |

`example` 是三个可复用中间件的现有包名。可运行案例另在 `agentscope-examples/jev`，使用 `io.agentscope.examples.jev` 包，不需要将 examples 加为生产依赖。

例如，给退款工具增加派发前检查，导入 `example.JevAutoModeMiddleware`；审核最终回答，导入 `integration.JevResponseMiddleware`；只评审一份已有草稿，则用根包的 `JevJudge`。先按任务选择功能页，再从本表查找导入位置。

文档片段中的 model、ACL、Source、记录函数及业务存储由应用提供。完整导入和可运行装配见[案例目录](/dev/zh/docs/harness/jev/guides/agent-integration-example)。

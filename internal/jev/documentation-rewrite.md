# JEV 用户文档逐页改写记录（内部）

目标：全部现有 JEV 功能讲解与使用页采用工具防护页的场景驱动写法；缺少的可运行案例补至 agentscope-examples/jev。保留功能边界，避免把预设结果当作模型效果。此记录不发布。

工作目录：/Users/ken/agentscope-3/agentscope-java；分支：harness-context-redesign。保留现有未提交改动。

验收：每个功能页有清晰简介、具体输入/典型错误/功能作用、同场景 API 与配置解释、结果处理、可运行案例及诚实的验证说明。概览/参考/运维页按用途组织，不机械套用功能模板。文档代码需核对当前实现并尽可能编译；新增示例必须离线可运行、有行为测试。正式站点链接、源码附件和下载包保持同步。

- 工具防护参考页：已完成并有真实 JEV/Qwen benchmark；最后仍检查链接和下载包同步。
- 其他页面的模型效果只引用已核对的同版本实测；无实测时明确提供离线行为验证，不编造准确率和性能。

## 逐页状态

- [x] `docs/v2/zh/jev/concepts.md`：已按具体场景、API 与结果处理改写并复核。
- [x] `docs/v2/zh/jev/evaluation.md`：已按具体场景、API 与结果处理改写并复核。
- [x] `docs/v2/zh/jev/guides/agent-integration-example.md`：已按具体场景、API 与结果处理改写并复核。
- [x] `docs/v2/zh/jev/guides/answer-refinement-api.md`：已按具体场景、API 与结果处理改写并复核。
- [x] `docs/v2/zh/jev/guides/application-api.md`：场景、API、行为和离线入口已改写；实际代码块通过 javac 17 编译，相关示例/行为测试通过。
- [x] `docs/v2/zh/jev/guides/browser-execution-api.md`：已按具体场景、API 与结果处理改写并复核。
- [x] `docs/v2/zh/jev/guides/browser-proposals.md`：场景、API、行为和离线入口已改写；实际代码块通过 javac 17 编译，相关示例/行为测试通过。
- [x] `docs/v2/zh/jev/guides/client.md`：已按具体场景、API 与结果处理改写并复核。
- [x] `docs/v2/zh/jev/guides/code-review-api.md`：已按具体场景、API 与结果处理改写并复核。
- [x] `docs/v2/zh/jev/guides/content-guardrail-api.md`：已按具体场景、API 与结果处理改写并复核。
- [x] `docs/v2/zh/jev/guides/context-archive.md`：已按具体场景、API 与结果处理改写并复核。
- [x] `docs/v2/zh/jev/guides/context-compaction-api.md`：已按具体场景、API 与结果处理改写并复核。
- [x] `docs/v2/zh/jev/guides/context-planner-api.md`：场景、API、行为和离线入口已改写；实际代码块通过 javac 17 编译，相关示例/行为测试通过。
- [x] `docs/v2/zh/jev/guides/draft-pipeline-api.md`：已按具体场景、API 与结果处理改写并复核。
- [x] `docs/v2/zh/jev/guides/evaluator-api.md`：场景、API、行为和离线入口已改写；实际代码块通过 javac 17 编译，相关示例/行为测试通过。
- [x] `docs/v2/zh/jev/guides/evidence-pipeline-api.md`：已按具体场景、API 与结果处理改写并复核。
- [x] `docs/v2/zh/jev/guides/harness-runtime.md`：场景、API、行为和离线入口已改写；实际代码块通过 javac 17 编译，相关示例/行为测试通过。
- [x] `docs/v2/zh/jev/guides/judge-api.md`：场景、API、行为和离线入口已改写；实际代码块通过 javac 17 编译，相关示例/行为测试通过。
- [x] `docs/v2/zh/jev/guides/knowledge-adapter-api.md`：已按具体场景、API 与结果处理改写并复核。
- [x] `docs/v2/zh/jev/guides/memory-api.md`：场景、API、行为和离线入口已改写；实际代码块通过 javac 17 编译，相关示例/行为测试通过。
- [x] `docs/v2/zh/jev/guides/metric-definitions.md`：已按具体场景、API 与结果处理改写并复核。
- [x] `docs/v2/zh/jev/guides/model-routing-api.md`：场景、API、行为和离线入口已改写；实际代码块通过 javac 17 编译，相关示例/行为测试通过。
- [x] `docs/v2/zh/jev/guides/phase-routing-api.md`：已按具体场景、API 与结果处理改写并复核。
- [x] `docs/v2/zh/jev/guides/rag-api.md`：已按具体场景、API 与结果处理改写并复核。
- [x] `docs/v2/zh/jev/guides/service-api.md`：已按具体场景、API 与结果处理改写并复核。
- [x] `docs/v2/zh/jev/guides/supervision-api.md`：已按具体场景、API 与结果处理改写并复核。
- [x] `docs/v2/zh/jev/guides/supervision-evidence.md`：已按具体场景、API 与结果处理改写并复核。
- [x] `docs/v2/zh/jev/guides/support-api.md`：场景、API、行为和离线入口已改写；实际代码块通过 javac 17 编译，相关示例/行为测试通过。
- [x] `docs/v2/zh/jev/guides/task-review.md`：已按具体场景、API 与结果处理改写并复核。
- [x] `docs/v2/zh/jev/guides/team-routing.md`：场景、API、行为和离线入口已改写；实际代码块通过 javac 17 编译，相关示例/行为测试通过。
- [x] `docs/v2/zh/jev/guides/tool-guard-api.md`：参考页已复核，保留实测表、完整用例入口与下载包。
- [x] `docs/v2/zh/jev/guides/tool-selection-api.md`：场景、API、行为和离线入口已改写；实际代码块通过 javac 17 编译，相关示例/行为测试通过。
- [x] `docs/v2/zh/jev/guides/trace-evaluation-api.md`：已按具体场景、API 与结果处理改写并复核。
- [x] `docs/v2/zh/jev/index.md`：已按具体场景、API 与结果处理改写并复核。
- [x] `docs/v2/zh/jev/operations.md`：已按具体场景、API 与结果处理改写并复核。
- [x] `docs/v2/zh/jev/sdk-reference.md`：已按具体场景、API 与结果处理改写并复核。
- [x] `docs/v2/en/jev/index.md`：英文功能入口、退款流程说明与新案例链接已复核。

## 实现与验证记录

逐批记录；最终验收见末尾。

### 第一批（2026-09-26）

- 新增 JevApplicationScenarios：8 个独立应用/评审场景；9 个行为测试通过。
- 新增 JevHarnessScenarios：工具筛选 6 个分支、路由 5 个分支；2 个行为测试通过。
- verify-guide-snippets.py 编译 11 页实际代码块（不执行网络调用）。
- docs 检查通过：448 页、504 redirects；源码附件及案例目录已同步。
- 后续仍需改写轨迹/定义复用、压缩/归档、持续监督、检索/护栏/修订、代码与浏览器、阶段路由、Service、客户端与总览参考页；不得据第一批验证标记整体完成。

### 完整改写与最终验收（2026-09-26）

- 全范围：36 个中文页（含参考工具防护页）、1 个英文入口，逐页完成场景/API/配置/结果/示例入口复核。概览、概念、运维与包索引按用途组织，未机械套用功能模板。
- 功能页包含具体业务问题、JEV 的职责、按步骤使用 API、宿主依赖与失败边界；没有实测的页面明确说明离线预设验证，不编造准确率。工具防护实测与原始数据保留在 examples，页面只展示简表。
- JevApplicationScenarios 扩展为 11 个场景，12 个测试；新增 draft 覆盖修订后发布、相同草稿停止、输入拒绝不生成。JevHarnessScenarios 保留 6 个筛选分支、5 个路由分支，2 个测试。
- 实际代码块：verify-guide-snippets.py 编译 30 个页面的全部 Java fenced blocks；仅为文中明确说明的宿主依赖提供参数，不执行网络调用。Knowledge 兼容页产生预期弃用警告。
- 构建：mvn -q -pl agentscope-examples/jev package 成功；184 tests，0 failures/errors/skips。
- 离线入口：JevApplicationScenarios（全部）、JevHarnessScenarios selection/routing、JevHarnessExample、JevCustomerSupportExample、JevApplicationExample、JevIntegrationExample、JevTraceEvaluationExample、JevContextCompactionExample、JevSupervisionExample、JevCodeReviewExample、JevEvidenceExample、JevPhaseRoutingExample、JevBrowserExample 均运行成功。
- 关键实测行为（均为脚本判断）：代码评审读取快照 1 次/评审请求 6 次；证据案例检索 1 次/模型 3 次/评审请求 5 次；阶段路由判断 1 次/strong 4 次/fast 2 次/工具 3 次；浏览器动作 1 次并关闭会话 1 次；归档案例恢复 6 条历史。
- 文档构建：npm --prefix docs run validate 成功（448 pages、504 redirects）。曾发现阶段标题锚点断链，已保留原 #显式阶段 后通过复核。
- 站点：37 个 JEV 页面 HTTP 200；浏览器实际读取 Service 页，确认新场景、代码、表格、侧栏分类与入口已呈现。
- 所有发布的 Java 源码附件与 examples 原文件逐字节一致；工具防护下载包重新发布（24 files）。
- 边界：本次不开展新的真实模型性能评测，不部署 Service、不更改运行时代码、不提交 Git commit。浏览器真实驱动与 Service 生产环境不在本次文档/离线示例验收范围，文中已明确宿主接入责任。

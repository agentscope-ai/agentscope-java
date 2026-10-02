---
title: "检索证据：分类、重排与只读工具"
---

已实现段落分类、独立重排、只读工具、Service 构建装配和实际 Agent 草稿审核/修订组合。默认关闭；未校准配置用于 SHADOW。效果记录见[固定输入对照](/v2/zh/jev/guides/evidence-benchmark)，生产检索源与部署验证仍由宿主接入。

## 来源与现有接口的关系

参考 spring-ai-typesafe [5c469ce](https://github.com/spring-ai-community/spring-ai-typesafe/tree/5c469ce10ee45175f56a8f5882cc93d29f72f591) 的 JevDocumentFilter、JevDocumentReranker 与 JevRagPostProcessorTests。按段落分别询问相关性、答案证据、前提冲突、提示注入，随后独立询问 answers_query。冲突证据保留并标记；重排将未评分项放在所有已评分项之后，分数相同保持检索原序。

现有 JevRag / JevKnowledge 保留兼容，它们原来的单一相关性筛选不等价于本页的四项分类。新实现位于 `io.agentscope.extensions.judge.jev.evidence`，复用 JevClient 和 JevExecution，不增加核心 Knowledge 或 Model 接口。

原项目过滤失败放行未分类段落。本实现 ENFORCE 下不把未筛选或注入不确定的段落传给 Agent；已经通过分类但重排失败的段落保留、置后并标记不完整。这是明确的 AgentScope 适配。原项目接纳阈值 .7 / .7 / .45 / .55 作为演示参考，另外引入 .2 的否定阈值形成弃权区间；演示值未经校准，生产先使用 SHADOW。

## API

依赖当前 BOM 中的 `io.agentscope:agentscope-extensions-jev`。

```java
var processor = new JevEvidenceProcessor(
    client::systemOne,
    JevEvidenceProcessor.Policy.demonstration(),
    JevEvidenceProcessor.Limits.defaults(),
    0.0, // 重排最低分数；0 表示不另做分数裁剪
    new JevExecution.Options(
        JevExecution.Mode.SHADOW, Duration.ofSeconds(5), "evidence-v1",
        (ctx, record) -> recordMetadata(ctx, record)));

var passages = List.of(new JevPassage("policy-7", passageText, "revision-3"));
var result = processor.process(context, query, passages,
    (ctx, passage) -> acl.canRead(ctx, passage.id()), 5);
```

client、context、query、passageText、acl 和 recordMetadata 由应用提供。也可传 `Supplier<Mono<List<JevPassage>>>`，将检索读取与语义判断放入同一个预算。ACL 在每次订阅、每个候选上执行，拒绝项不会发给模型。id/version 有界且非空，可见 ID 不得重复。JevPassage 的 attributes 为不可变字符串映射，保留给宿主，但不会传给判断模型。

Report 区分 delivered（实际下游输入）、suggested（建议 topK）、assessments（逐段结果）、calls（请求状态与用量）、complete 和 applied。分类包含 INCLUDED、CONFLICTING、EXCLUDED、INCONCLUSIVE、ERROR、UNSCREENED。未知分数为 null，不能当作零或无关。

OFF 仍执行检索和 ACL，只禁用语义请求。SHADOW 返回原有授权候选的顺序及 topK，建议独立记录。ENFORCE 应用分类/重排；明确排除、弃权、错误和未筛选内容不进入 delivered。重排故障不推翻已通过的分类，排在所有已评分内容之后。没有候选不请求模型；空结果和不完整处理有不同状态，不应统一解释为“知识库没有答案”。

默认最多 32 个可见候选，每段 16,000 字符、query 8,000 字符、每请求 state 32,000 字符、64 次请求。总量超限不开始语义处理，单段超限标为 UNSCREENED，正文不静默截断。当前按段串行，过滤与重排分别请求。超时保留已有部分报告；取消停止证据读取/在途请求及后续派发，不保证远端停止计费。观察器异常不改变结果。OFF 的读取/ACL 故障继续作为应用检索错误传播。

## 只读工具

```java
var toolkit = new Toolkit();
toolkit.registerTool(new JevEvidenceTool(processor, 32, 5));
var agent = HarnessAgent.builder().model(model).toolkit(toolkit).build();
var context = RuntimeContext.builder().userId(userId).sessionId(sessionId)
    .put(JevEvidenceTool.Source.class, new JevEvidenceTool.Source(
        request -> retriever.search(request.context(), request.query(), request.candidateLimit()),
        (ctx, passage) -> acl.canRead(ctx, passage.id())))
    .build();
```

model、retriever 和 acl 为宿主实现；Source 在每次运行上下文中提供，必须执行实际权限校验。工具名 search_evidence，只读但仍受现有权限链约束。参数只有 query，candidateLimit / topK 是构造时配置。用户、会话或 Source 缺失时明确报错。

Agent 得到的 Result 只包含实际 delivered 段落的 id、version、text、可空分类/分数，不返回被过滤的正文、内部 ACL attributes 或完整评估报告。SHADOW 的工具输出与 OFF 一致，不把建议偷偷写回实际输入；ENFORCE 才附加分类与分数。不完整处理使用 EVIDENCE_REVIEW_INCOMPLETE，完整空结果使用 NO_EVIDENCE。模型仍需根据证据作答、引用 id/version；本工具本身不执行最终答案审核或验证引用声明。

## 与最终草稿审核组合

`JevResponseMiddleware.qualityState(input, answer)` 从实际消息构造 `user_question`、`assistant_answer`、按调用 ID 配对的 `tool_calls`、成功工具结果的 `supporting_context` 及 `trace_issues`，同时保留兼容字段 `prompt` / `answer`。定义可以分别检查 grounded 和 answers_question，不能把“切题”自动当作“有依据”。元数据和模型思考不作为证据；工具输出是待核验材料，不因放进 supporting_context 就自动成为可信事实。

有重复/缺失调用 ID、孤立工具结果等轨迹问题时，不猜测对应关系，质量审核返回 INCONCLUSIVE / INCOMPLETE_JUDGING_EVIDENCE；整个序列化判断状态超过 maxChars 时返回 JUDGE_STATE_LIMIT，不静默截断。ENFORCE 不发布此类草稿，SHADOW 保留原事件。这里比参考项目按名称或轮次补配对更严格，是 AgentScope 当前适配边界。

同步发布前审核继续使用已有缓冲和预算。仅 FAIL 可进入有限草稿修订，修订调用没有工具；ERROR、INCONCLUSIVE 和修订耗尽不发布草稿。不提供上游 BEFORE_TOOLS_ORDER 的重跑工具路径，也不在耗尽时发布“相对最好但仍未通过”的答案。参见[最终草稿审核](/v2/zh/jev/guides/answer-refinement-api)。引用格式/ID/version 的确定性验证仍由应用负责；本例保留引用并审核语义依据，没有实现通用引用解析器。

## AgentScope Service

在现有 Agent overrides 中配置（以下阈值只用于演示，不代表已校准）：

```json
{
  "jev": {
    "retrieval": {
      "mode": "SHADOW",
      "version": "evidence-v1",
      "budgetMillis": 5000,
      "rejectionThreshold": 0.2,
      "injectionThreshold": 0.7,
      "contradictionThreshold": 0.7,
      "relevanceThreshold": 0.45,
      "evidenceThreshold": 0.55,
      "maxCandidates": 32,
      "topK": 5,
      "minimumScore": 0.0
    }
  }
}
```

`JevServiceSupport.evidenceTool(overrides)` 校验配置并创建工具；HarnessAgentBuildService 在 Harness 构建前注册进 Toolkit，避免工具快照看不到新增工具。overrides 参与现有构建缓存键，改为 OFF 会重建并移除此工具。宿主仍需在每次运行的 RuntimeContext 注入 Source 和真实 ACL；这次没有新增生产知识库连接器或绕过权限确认链。

开启时必须显式提供五个阈值，默认 OFF 不创建客户端。总预算默认 5 秒、上限 30 秒；候选默认 32、上限 128；topK 不大于候选上限；单段字符默认 16,000、上限 64,000；query 默认 8,000、上限 16,000；state 默认 32,000、上限 100,000；请求数默认 64、上限 256。阈值必须有限且否定阈值低于各接纳阈值。拒绝未知字段，不允许通过 Agent JSON 放入 API key 或 endpoint。决策观察关联现有运行上下文，默认只记计数、模式和状态，不保存完整报告及正文；需要调用用量可从宿主处理器 Report.calls 接入。

## 离线运行与验收

按[轨迹案例构建步骤](/v2/zh/jev/guides/trace-evaluation-api#离线运行)安装模块并生成 classpath，在项目根目录运行：

```bash
java -cp "agentscope-examples/jev/target/classes:$(cat /tmp/jev-trace-cp.txt)" io.agentscope.examples.jev.JevEvidenceExample
```

不使用密钥。案例通过真实 ReActAgent / Toolkit / ResponseMiddleware：ACL 移除私有段落、语义筛选移除注入、保留冲突政策证据；初稿错误承诺退款，审核后只修订草稿。实际输出为 `retrievals=1, modelCalls=3, judgmentRequests=5`，包含一个正确配对的工具结果，初稿没有泄漏给用户。判断与生成均为脚本，案例证明执行边界，不证明模型准确率。

2026-09-26 合并后回归：JEV 210 项、Harness 26 项、Service 38 项通过，21 个相关模块 install 成功。新增段落测试覆盖 ACL、冲突/注入、独立重排、稳定排序、错误后置、topK、OFF/SHADOW、超限、超时、取消、并发、观察器异常和非法响应用量；质量测试补充孤立工具结果、整体状态超限和问题/配对证据字段分离。Service 验证构建前工具装配、配置缓存、关闭恢复和会话 ACL。benchmark 保留异常类/HTTP 状态分类，不记录原始错误正文。

源码：[处理器](/examples/jev/source/JevEvidenceProcessor.java.txt)、[段落](/examples/jev/source/JevPassage.java.txt)、[只读工具](/examples/jev/source/JevEvidenceTool.java.txt)、[实际 Agent 案例](/examples/jev/source/JevEvidenceExample.java.txt)。尚未完成生产知识库接入、真实生成模型闭环质量、独立人工金标、阈值校准或 Service 部署。

## 源码映射与差异

| 上游固定源码入口 | 本项目对应实现 | 边界 |
| --- | --- | --- |
| rag/JevDocumentFilter、JevRagPostProcessorTests | JevEvidenceProcessor.questions / classify | 四项独立判断与冲突保留；额外弃权区间，ENFORCE 未筛选不放行 |
| rag/JevDocumentReranker、JevRagPostProcessorTests | processor 独立 rank 阶段 | answers_query Noul、稳定排序、未知置后；当前串行，设总预算 |
| judge/JevJudgeInput、advisor/JevSelfRefineAdvisor 与测试 | JevResponseMiddleware.qualityState / 默认修订 | 命名证据字段、仅草稿重试；严格 ID 配对，失败不发布 |
| advisor/JevGuardrailAdvisor 与测试 | 既有输入/输出护栏及 ResponseMiddleware | 发布前缓冲、显式模式/预算；不直接搬用上游非流式 advisor 顺序 |
| toolsearch/JevToolIndex 与测试 | 既有 ToolSelectionMiddleware、JevCandidateSelector | applicability 与数量分开，none/单候选/多候选；没有独立索引服务 |

上述代码与测试来自本页固定提交。上游接受缺概率分布的部分选择结果；本项目继续按 JevClient 的完整响应契约校验，非法响应走显式回退。许可证为 Apache-2.0，随模块保留许可证和 NOTICE。

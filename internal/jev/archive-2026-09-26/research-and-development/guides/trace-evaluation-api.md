---
title: "Agent 轨迹评估：API、案例与源码依据"
---

本项为 R1，参考 [jevals 固定提交 e9fb26a](https://github.com/openlayer-ai/jevals/tree/e9fb26aff4a4410580776fb35deec85b4ad9c308)，在现有 JEV 扩展上增加完整轨迹评估。它补充原有问题/证据/答案评估接口，不改变 `JevJudge` 的状态契约。已完成轨迹 API、实际 Agent 影子接入、Service 构建链验证和同样本真实模型对照。部署环境的 HTTP、持久事件与生产业务效果仍需单独验收，不能由本页测试代替。

## 参考实现与适配决策

| 来源源码 | 参考机制 | 本项目实现与明确差异 |
| --- | --- | --- |
| [_sample.py](https://github.com/openlayer-ai/jevals/blob/e9fb26aff4a4410580776fb35deec85b4ad9c308/src/jevals/_sample.py) | 消息归一化、工具调用 ID 配对、派生输入和最终答案 | `JevTrace.fromMessages` 直接读取 AgentScope Msg、ToolUseBlock、ToolResultBlock；不兼容导入其他框架对象。不发送 metadata 或 ThinkingBlock；非文本材料、重复 ID、未配对结果显式标为不完整 |
| [_eval.py](https://github.com/openlayer-ai/jevals/blob/e9fb26aff4a4410580776fb35deec85b4ad9c308/src/jevals/_eval.py) | state/questions/reduce，加确定性 pre 检查 | `JevTraceMetric` 可复用于离线和在线；定义有版本，结果区分 DECIDED、INCONCLUSIVE、SKIPPED、ERROR；标签、分数与 passed 分开 |
| [_runner.py](https://github.com/openlayer-ai/jevals/blob/e9fb26aff4a4410580776fb35deec85b4ad9c308/src/jevals/_runner.py) | 相同 state 合并、冲突拆分、问题命名空间、按输入顺序输出 | `JevTraceEvaluator` 保留此机制；采用串行分组与单次总预算，避免无界并发；严格校验响应题目集合；请求用量不重复分摊给每个指标 |
| [agent/_evals.py](https://github.com/openlayer-ai/jevals/blob/e9fb26aff4a4410580776fb35deec85b4ad9c308/src/jevals/agent/_evals.py) | ToolChoice、UsedToolResult、Grounded、StayedInScope、TrajectoryMatch | 保留四类工具选择标签、逐句依据与确定性轨迹对照。Grounded 要求每句有依据；重复调用按次数匹配，不使用集合消掉重复执行 |
| [quality/_evals.py](https://github.com/openlayer-ai/jevals/blob/e9fb26aff4a4410580776fb35deec85b4ad9c308/src/jevals/quality/_evals.py) | AnswerRelevancy、Completeness | 保留离散分级及相关性 × 非回避概率的计算；这类分数不是校准概率，不能解释为业务正确率 |
| [backends/llm.py](https://github.com/openlayer-ai/jevals/blob/e9fb26aff4a4410580776fb35deec85b4ad9c308/src/jevals/backends/llm.py) | 文本模型按同一题目生成类型化概率 | `JevTextBackend` 接受调用者的 HTTP 传输；严格校验 JSON、题目集合与概率，不复制原实现的容错补值和自动归一化。自报概率不能视为校准概率 |
| [security/_evals.py](https://github.com/openlayer-ai/jevals/blob/e9fb26aff4a4410580776fb35deec85b4ad9c308/src/jevals/security/_evals.py) | IndirectInjection 的负向指标 | 返回安全分数 1−p，并保存 yes_probability；工具内容始终作为待评材料 |

以上是有选择的机制适配，不是完整移植 jevals 的 CLI、MCP、所有内置指标和后端。阈值、缺失处理、预算、在线采集和权限边界属于 AgentScope 适配决策。指标定义附带原项目 MIT 声明，随 JAR 发布在 `META-INF/jev-references/`。

## 依赖与输入

```xml
<dependency>
  <groupId>io.agentscope</groupId>
  <artifactId>agentscope-extensions-jev</artifactId>
  <version>${agentscope.version}</version>
</dependency>
```

```java
import io.agentscope.extensions.judge.jev.evaluation.*;

var trace = JevTrace.fromMessages("order-42", invocationMessages, availableTools);
var metrics = JevTraceMetrics.agentMetrics(
    new JevTraceMetrics.Thresholds(0.2, 0.8));
var evaluator = new JevTraceEvaluator(client, metrics,
    new JevTraceEvaluator.Limits(Duration.ofSeconds(3), 128, 100_000));
var report = evaluator.evaluate(trace).block();
```

`invocationMessages` 必须属于要评估的同一次调用，包含用户请求、工具调用、配对结果、最终回答。`availableTools` 为调用者已授权的候选；`null` 表示未提供目录，空列表表示确实无工具。工具 ID 必须唯一，结果必须出现在调用之后。样本构造时对参数等 JSON 数据做独立不可变快照。

输入字段 `request`、`final_answer`、`messages`、`tool_calls`、`tool_results`、`available_tools` 可从消息派生。也可通过 `new JevTrace(id, fields)` 构造自有轨迹格式。`with("contexts", list)` 提供授权检索证据；`with("claims", list)` 指定需要逐条检查的断言，避免依赖句子分割。默认分句是确定性文本处理，并不是语义断言提取。

上例阈值仅演示 API，未校准。没有默认线上接管：先用固定金标分别校准各用途。Grounded 逐句概率处于区间内部时整个指标弃权；缺少任一响应不能按剩余句子算通过。

## 指标与结果

| 指标 ID | 输入要求 | 结果 |
| --- | --- | --- |
| tool_choice | 请求、候选目录、调用、最终答案 | correct / unnecessary / missing / wrong_tool；低置信度保留标签并弃权 |
| used_tool_result | 请求、最终答案、非空工具结果 | 是否使用结果；无结果 SKIPPED |
| grounded | 最终答案与工具结果或 contexts | 每句依据概率、支持比例；要求所有句子明确有依据才 passed |
| stayed_in_scope | 请求、调用、最终答案 | 行为与回复是否超出用户请求 |
| answer_relevancy | 请求、最终答案 | 分级相关性与非回避概率组合分数 |
| completeness | 请求、最终答案 | 四档覆盖程度归一化到 0..1 |
| indirect_injection | 非空工具结果 | 是否含引导 Agent 改变行为的指令，负向概率转安全分数 |
| trajectory_match | tool_calls、expected_tool_calls | STRICT、UNORDERED、SUBSET、SUPERSET；可比较参数；无需模型 |

`agentMetrics` 返回前七项；确定性轨迹对照单独添加：

```java
var metrics = new ArrayList<>(JevTraceMetrics.agentMetrics(thresholds));
metrics.add(JevTraceMetrics.trajectoryMatch(
    JevTraceMetrics.MatchMode.UNORDERED, true));
var labelled = trace.with("expected_tool_calls",
    List.of(Map.of("name", "lookup_order", "arguments", Map.of())));
```

自定义指标实现 `id/version/precheck/state/questions/reduce`。metric ID 和本地题目 ID 只能使用字母开头的字母、数字、下划线，最长 64 字符。线上问题名为 `metric.question`；重复 metric ID 拒绝构造。无问题的确定性指标不发请求。不同指标对同一 state 字段给出不同值时拆分请求，绝不覆盖。

`report.passed()` 只有在非空且全部指标明确 DECIDED/passed=true 时才为 true。SKIPPED、错误、弃权、没有通过策略的纯分类都不能使整体通过。调用方仍需查看每项状态；false 不等于业务判定为拒绝。

## 错误、预算和取消

- 缺字段或空证据：SKIPPED；不完整轨迹：INCOMPLETE_TRACE；超出单项问题数或状态字符数：EVALUATION_LIMIT，不截断证据后继续评分。
- 预检查、定义或 reducer 异常只影响对应指标；不在报告中保存异常文本，避免带出上下文。
- 后端错误、空响应、缺题、错类型、非法概率均为 ERROR；已报告的用量仍保留，未知用量不算零。
- 总预算覆盖本次所有分组；超时取消在途请求，未完成指标记 TIMEOUT；已完成指标保留。
- 下游取消向调用函数传播，不发出伪造的“完成报告”，也不继续后续分组。底层传输是否成功中断远端计算依赖 transport，不能据此声称不再计费。
- 分组串行执行；数据集并发由 runner 参数控制，范围 1..32；自定义回调必须及时返回非阻塞 Publisher。

## Agent 在线影子接入

```java
var options = new JevExecution.Options(
    JevExecution.Mode.SHADOW, Duration.ofSeconds(5), "orders-v1",
    (ctx, record) -> saveDecisionMetadata(ctx, record));
var observer = new JevTraceEvaluationMiddleware(
    evaluator, options, 100_000,
    (ctx, report) -> saveEvaluationSummary(ctx, report));
// ReActAgent.builder().middleware(observer) ...
```

默认构造器为 OFF，不采集、不调用模型。SHADOW 从当前调用实际传给模型的消息和工具目录构造快照，再评估最终 AgentResult；事件原样透传，评估只在流结束时增加有界等待。逐轮工具目录带有适用消息位置，避免把最后一轮的目录冒充此前可用工具。

采集边界由本次输入消息 ID 定位。找不到边界、上下文删除此前调用、材料超限或不支持的内容时跳过，不能把被压缩后的残缺轨迹当完整执行记录。上下文治理方向将继续处理可恢复的完整轨迹证据。前序用户会话不混入本次动作评价。

每次订阅的采集状态放在 Reactor Context，复用同一中间件不共享轨迹。观察器异常不改变 Agent 输出。Agent 本身错误或被取消时不启动事后评估；评估开始后取消则取消评估订阅。

该观察器拒绝 ENFORCE：动作已经执行，事后评估无权撤销它。执行前权限及护栏继续由现有工具防护承担；同步输出审核继续用响应中间件。不会根据评估结果重跑 Agent 或写工具。

## Service 配置和记录

```json
{
  "jev": {
    "evaluation": {
      "mode": "SHADOW",
      "version": "orders-v1",
      "budgetMillis": 3000,
      "threshold": 0.8,
      "rejectionThreshold": 0.2,
      "maxQuestions": 128,
      "maxStateChars": 100000,
      "metrics": ["tool_choice", "used_tool_result", "grounded", "completeness"]
    }
  }
}
```

`evaluation` 默认 OFF；启用时显式提供两个阈值。`metrics` 省略时使用七项；未知名称、重复名称、ENFORCE、任意 endpoint/key 配置均拒绝。预算最多 30 秒、问题最多 512、状态最多 1,000,000 字符。阈值仅演示，不构成线上建议。

配置进入已有 Agent 构建缓存身份；变更指标或阈值重建 Agent。结果复用 `JevServiceSupport.TraceSink`，由现有运行链关联 run/session：`evaluation.<metric>` 保存版本、状态、标签/分数/通过值和耗时；`evaluation.usage` 记录请求数、已知用量响应数与 token。不会保存原始轨迹、模型密钥、完整证据或自由生成的解释。未获得响应的请求成本未知，不能用已知 token 总量当完整账单。

## 批量报告与对照

```java
var cases = List.of(new JevTraceRunner.Case("orders", trace,
    Map.of("tool_choice", new JevTraceRunner.Expected(null, "correct"),
           "grounded", new JevTraceRunner.Expected(true, null))));
var report = new JevTraceRunner().run(evaluator, cases, 4).block();
```

相同样本 ID 不可重复。报告保留输入顺序，按指标和场景汇总。准确率分母为该指标全部有金标样本，包含错误、弃权、跳过及无法判分项；没有金标时不可解读 accuracy。延迟为每个完整样本评估的实测墙钟耗时，P50/P95 使用 nearest-rank；数据集另有总耗时。用量按实际请求累计一次，`responsesWithUsage < requests` 时总成本不完整。

原流程使用相同样本的确定性执行结果作基线；JEV 与 Qwen 必须消费同一状态、题目和金标。Qwen 如模拟概率，应标记为自报值并独立校准。模型价格和账单未核实时不输出推算费用。

## 离线运行

`JevTraceEvaluationExample` 使用真实 ReActAgent 与只读模拟工具，脚本模型生成工具调用和最终答案，评估端返回合成响应，不需要密钥。

```bash
mvn -pl agentscope-dependencies-bom,agentscope-distribution/agentscope-bom,agentscope-examples/jev -am install -DskipTests
mvn -f agentscope-examples/jev/pom.xml dependency:build-classpath -Dmdep.outputFile=/tmp/jev-trace-cp.txt
java -cp "agentscope-examples/jev/target/classes:$(cat /tmp/jev-trace-cp.txt)" io.agentscope.examples.jev.JevTraceEvaluationExample
```

预期一次工具执行、一次合并评估请求和七项指标；合成判定只验证调用链，不证明真实模型准确率或速度。

## 文本模型后端与固定样本

`JevTextBackend(model, transport)` 将相同 `SystemOneRequest` 转成兼容 Chat Completions 的 JSON 请求；transport 接收请求 JSON 并返回完整响应 JSON 的 `Mono<String>`。端点、密钥、HTTP 超时和取消由 transport 管理，适配器不自行读取环境变量。示例 `JevTraceBenchmark` 用 HTTP API 调用 Qwen。

仅接受完整 JSON、全部题目和全部分布项；重复键、非数值概率、非法总和、截断响应均为 ERROR/INVALID_RESPONSE，仍保留可解析的 token 用量。不会用 0.5 或归一化结果掩盖格式失败。Choice 的置信度采用 jevals 的相对均匀分布公式；概率没有经过独立校准。

固定输入、逐项金标、基线与 JEV/Qwen 结果、运行命令见[轨迹评估对照记录](/v2/zh/jev/guides/trace-benchmark)。基准程序必须显式选择 `--offline`、`--baseline`、`--live-jev` 或 `--live-qwen`，不会自动调用真实接口。

## 当前验证记录

2026-09-25，`/Users/ken/agentscope-3/agentscope-java`，`harness-context-redesign`：

- 原有 JEV 105 项，加轨迹评估 17 项、在线集成 6 项、文本后端 2 项，合计 **130 项通过**。
- Service 相关 **23 项通过**，包括新增 3 项配置/缓存/记录测试和 1 项实际 `HarnessAgentBuildService` 集成测试；后者实际构建并运行 Harness Agent，验证相同配置复用、关闭后重建、评估停止和摘要到达 TraceSink。控制面及外部存储用替身，不能称为完整部署验收。
- 验证命令：`mvn -o -pl agentscope-service/service-dataplane -am verify -Dtest='Jev*Test,HarnessAgentBuildServiceCacheKeyTest,ToolConfirmationMiddlewareTest' -Dsurefire.failIfNoSpecifiedTests=false`，相关 19 个 reactor 模块构建通过。
- 上述离线主程序实际运行成功：工具恰好执行 1 次，7 个指标合并成 1 个评估请求。
- 固定 12 条合成轨迹的确定性基线、真实 JEV、真实 Qwen 均已运行；保留弃权和格式错误，结果不作生产准确率结论。
- 未部署 Service；HTTP 网关、数据库持久事件、多实例恢复、真实业务金标、阈值校准、账单成本和远端取消仍未验证。

R1 的源码适配与研发验收已完成；生产验证独立跟踪，七方向整体目标尚未完成。下一项为 R2 上下文治理，进度以[逐项路线](/v2/zh/jev/reference-roadmap)为准。

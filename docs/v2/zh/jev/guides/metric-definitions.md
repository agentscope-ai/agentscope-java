---
title: "复用与扩展评估定义"
---

当多个 Agent 需要采用相同的质量标准时，把标准定义成带版本的对象，在线观察和离线评估共用它。AgentScope 提供两种定义层次。

## 示例场景：在线审核与离线回归采用同一套退款标准

客服 Agent 在线审核回答，评测程序离线回放历史答案。如果两处各写一套提示词，线上认为“无依据退款承诺”不合格，离线却只检查语气，就无法用离线结果指导上线。

把条件、输入字段和阈值一起放入带版本的定义，在线 Judge 与离线 Evaluator 使用同一对象。涉及工具行为时，再复用轨迹指标；分类结果不必强行变成通过或失败。

## 1. 让草稿审核与样本回放共用定义

`JevJudge.Definition` 适用于所有条件都必须满足的草稿审核。本例在 `JevJudge`、`JevEvaluator` 与 `JevResponseMiddleware` 之间复用相同字段约定。`JevDraftPipeline` 也使用 Definition，但输出状态字段是 `request/evidence/draft`，不能直接套用本例的字段名；应建立对应定义或在自有工作流中统一输入。

```java
import io.agentscope.extensions.judge.jev.JevJudge;
import io.agentscope.extensions.judge.jev.NoulQuestion;
import io.agentscope.extensions.judge.jev.evaluation.JevEvaluator;
import io.agentscope.extensions.judge.jev.evaluation.JevTraceEvaluator;
import io.agentscope.extensions.judge.jev.evaluation.JevTraceMetrics;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;

var definition = new JevJudge.Definition("refund-answer-v1", List.of(
    new JevJudge.Criterion("supported",
        new NoulQuestion("Is assistant_answer supported by supporting_context without inventing completed refunds?", null),
        true, 0.2, 0.8)));
var judge = new JevJudge(client, Duration.ofSeconds(2));
var evaluator = new JevEvaluator(judge, definition);
```

同一份定义要使用一致的字段语义：`JevEvaluator` 提供 `user_question`、`supporting_context`、`assistant_answer`；直接调用 Judge 时由你提供对应 state。修改问题、阈值或通过策略时更新版本，以便区分不同报告。

## 2. 为 Agent 行为复用指标组合

`JevTraceMetric` 可组合确定性预检查、问题构造和结果归约。它支持 Choice 分类或纯确定性检查，不要求每个指标都有通过/失败结果。

| 方法 | 你的实现负责什么 |
|---|---|
| `id()` / `version()` | 唯一指标 ID 与定义版本 |
| `precheck(trace)` | 缺输入时返回 SKIPPED，或直接返回确定性结果；继续评估时返回 null |
| `state(trace)` | 提取本指标需要的授权材料 |
| `questions(trace)` | 返回局部问题 ID 到 Question 的映射 |
| `reduce(answers, trace)` | 返回状态、分数、标签、passed 和必要证据摘要 |

```java
var thresholds = new JevTraceMetrics.Thresholds(0.2, 0.8);
var metrics = new ArrayList<>(JevTraceMetrics.agentMetrics(thresholds));
metrics.add(JevTraceMetrics.trajectoryMatch(
    JevTraceMetrics.MatchMode.STRICT, true));
var traceEvaluator = new JevTraceEvaluator(client, metrics,
    new JevTraceEvaluator.Limits(Duration.ofSeconds(3), 128, 100_000));
```

同一个 traceEvaluator 可以交给 `JevTraceRunner` 回放数据，也可以交给 `JevTraceEvaluationMiddleware` 观察真实 Agent 调用。[接入示例](/v2/zh/jev/guides/trace-evaluation-api)

指标 ID 和局部题目 ID 使用字母开头的字母、数字、下划线，最长 64 字符；指标 ID 不可重复。评估器用命名空间隔离问题，兼容的 state 合并请求，冲突字段拆分请求。无问题的确定性指标不调用模型。定义和 reducer 异常只影响对应指标。

## 保存状态而非只保存总分

`JevMetricResult.passed` 可为空，非 DECIDED 状态不能声明通过或失败。保留 SKIPPED、INCONCLUSIVE 和 ERROR，分别判断是输入缺失、模型不确定还是调用失败；不要将它们替换成零分后丢掉原因。

阈值示例用于说明接口。业务金标、版本、运行模式和完整样本分母一起决定评测结果是否可比较。[评测方法](/v2/zh/jev/evaluation)

## 运行与验证

按[案例构建步骤](/v2/zh/jev/guides/agent-integration-example#构建一次运行多个案例)设置 `JEV_CP` 后运行：

```bash
java -cp "$JEV_CP" io.agentscope.examples.jev.JevApplicationScenarios judge
java -cp "$JEV_CP" io.agentscope.examples.jev.JevApplicationScenarios evaluation
```

两个入口复用源码中的 `answerDefinition()`：单条调用展示状态边界，批量调用回放一正一反样本。它们用预设概率验证定义复用，不以离线匹配数宣称模型准确率。[完整源码](/examples/jev/source/JevApplicationScenarios.java.txt)

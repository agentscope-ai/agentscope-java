---
title: "采集并评估 Agent 轨迹"
---

当你需要判断 Agent 是否选对工具、使用了工具结果、回答有无依据时，使用 `JevTraceEvaluator`。同一组 `JevTraceMetric` 可以用于历史轨迹回放，也可以通过中间件评估真实调用。在线评估只观察已经发生的行为。

## 示例场景：查到了订单状态，最终回答是否真的使用了它

客户问“订单 42 到哪里了？”。Agent 调用 `lookup_order`，工具返回“昨天已发货”，然后给出回答。工具执行成功只说明查到了数据，还需要检查是否选对工具、是否使用查询结果、回答有没有增加未经证实的承诺。

把这一次调用的完整轨迹交给 JEV，可以同时得到工具选择、结果利用和回答依据等指标。它是事后评估，不会撤销已经发生的工具调用，也不会为了提高分数重跑业务流程。

## 1. 把调用与结果配成完整轨迹

依赖及凭据见[客户端接入](/v2/zh/jev/guides/client)。下面先使用一个固定快照说明输入结构：

```java
import io.agentscope.core.message.Msg;
import io.agentscope.core.message.UserMessage;
import io.agentscope.core.message.AssistantMessage;
import io.agentscope.core.message.ToolUseBlock;
import io.agentscope.core.message.ToolResultMessage;
import io.agentscope.core.model.ToolSchema;
import io.agentscope.extensions.judge.jev.evaluation.JevTrace;
import io.agentscope.extensions.judge.jev.evaluation.JevTraceEvaluator;
import io.agentscope.extensions.judge.jev.evaluation.JevTraceMetrics;
import java.time.Duration;
import java.util.List;
import java.util.Map;

var messages = List.<Msg>of(
    new UserMessage("订单 42 到哪里了？"),
    new AssistantMessage(new ToolUseBlock("lookup-42", "lookup_order", Map.of())),
    new ToolResultMessage("lookup-42", "lookup_order", "订单 42 昨天已发货。"),
    new AssistantMessage("订单 42 昨天已发货。"));
var tools = List.of(ToolSchema.builder()
    .name("lookup_order").description("查询订单状态").build());
var trace = JevTrace.fromMessages("order-42", messages, tools);
```

工具调用与结果使用同一个 `lookup-42` ID，实际工具目录也随轨迹提供。目录为 `null` 表示没有提供，空列表则表示当时确实没有工具，两者不能混用。线上应使用实际发生的消息，不把后来可用的工具冒充此前候选。

## 2. 对同一次任务评估多个方面

```java
var metrics = JevTraceMetrics.agentMetrics(
    new JevTraceMetrics.Thresholds(0.2, 0.8));
var evaluator = new JevTraceEvaluator(client, metrics,
    new JevTraceEvaluator.Limits(Duration.ofSeconds(3), 128, 100_000));
var pending = evaluator.evaluate(trace);
```

三个上限分别约束一次评估的总耗时、单项问题数量和状态字符数，避免长轨迹无限放大评审开销。0.2/0.8 是演示阈值，正式使用应分用途校准。应用在响应式链中组合 `pending`，独立命令行才阻塞等待。

本例重点看 `tool_choice`、`used_tool_result` 和 `grounded`：工具选择正确不代表最终回答有依据。如果把回答换成“退款已经完成”，应由依据检查独立暴露问题，而不是被工具调用成功掩盖。

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
var extendedMetrics = new java.util.ArrayList<>(metrics);
extendedMetrics.add(JevTraceMetrics.trajectoryMatch(
    JevTraceMetrics.MatchMode.UNORDERED, true));
var labelled = trace.with("expected_tool_calls",
    List.of(Map.of("name", "lookup_order", "arguments", Map.of())));
var extendedEvaluator = new JevTraceEvaluator(client, extendedMetrics,
    new JevTraceEvaluator.Limits(Duration.ofSeconds(3), 128, 100_000));
var comparison = extendedEvaluator.evaluate(labelled);
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

## 3. 在线采集时只观察本次调用

这里 `model` 和 `toolkit` 沿用已有生成模型及业务工具；注册观察器后正常调用 Agent 即可。

```java
import io.agentscope.harness.agent.HarnessAgent;

var options = new io.agentscope.extensions.judge.jev.JevExecution.Options(
    io.agentscope.extensions.judge.jev.JevExecution.Mode.SHADOW, Duration.ofSeconds(5), "orders-v1",
    (ctx, record) -> System.out.println(record));
var observer = new io.agentscope.extensions.judge.jev.evaluation.JevTraceEvaluationMiddleware(
    evaluator, options, 100_000,
    (ctx, report) -> System.out.println(report.results()));
var agent = HarnessAgent.builder().name("observed-order-agent")
    .model(model).toolkit(toolkit).middleware(observer).build();
```

默认构造器为 OFF，不采集、不调用模型。SHADOW 从当前调用实际传给模型的消息和工具目录构造快照，再评估最终 AgentResult；事件原样透传，评估只在流结束时增加有界等待。逐轮工具目录带有适用消息位置，避免把最后一轮的目录冒充此前可用工具。

采集边界由本次输入消息 ID 定位。找不到边界、上下文删除此前调用、材料超限或不支持的内容时跳过，不能把被压缩后的残缺轨迹当完整执行记录。前序用户会话不混入本次动作评价。

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
var cases = List.of(new io.agentscope.extensions.judge.jev.evaluation.JevTraceRunner.Case("orders", trace,
    Map.of("tool_choice", new io.agentscope.extensions.judge.jev.evaluation.JevTraceRunner.Expected(null, "correct"),
           "grounded", new io.agentscope.extensions.judge.jev.evaluation.JevTraceRunner.Expected(true, null))));
var report = new io.agentscope.extensions.judge.jev.evaluation.JevTraceRunner().run(evaluator, cases, 4).block();
```

相同样本 ID 不可重复。报告保留输入顺序，按指标和场景汇总。本例可把工具选择标成 correct、回答依据标成通过；金标不进入模型请求。准确率分母为该指标全部有金标样本，包含错误、弃权、跳过及无法判分项；没有金标时不可解读 accuracy。延迟为每个完整样本评估的实测墙钟耗时，P50/P95 使用 nearest-rank；数据集另有总耗时。用量按实际请求累计一次，`responsesWithUsage < requests` 时总成本不完整。

原流程使用相同样本的确定性执行结果作基线；JEV 与 Qwen 必须消费同一状态、题目和金标。Qwen 如模拟概率，应标记为自报值并独立校准。模型价格和账单未核实时不输出推算费用。

## 离线运行

`JevTraceEvaluationExample` 使用真实 ReActAgent 与只读模拟工具，脚本模型生成工具调用和最终答案，评估端返回合成响应，不需要密钥。

按[统一构建步骤](/v2/zh/jev/guides/agent-integration-example#构建一次运行多个案例)设置 `JEV_CP`：

```bash
java -cp "$JEV_CP" io.agentscope.examples.jev.JevTraceEvaluationExample
```

兼容使用旧命令的页面时，也可将 classpath 写到 `/tmp/jev-trace-cp.txt`；新例子统一使用 `JEV_CP`。

预期一次工具执行、一次合并评估请求和七项指标；合成判定只验证调用链，不证明真实模型准确率或速度。

## 文本模型后端与固定样本

`JevTextBackend(model, transport)` 将相同 `SystemOneRequest` 转成兼容 Chat Completions 的 JSON 请求；transport 接收请求 JSON 并返回完整响应 JSON 的 `Mono<String>`。端点、密钥、HTTP 超时和取消由 transport 管理，适配器不自行读取环境变量。示例 `JevTraceBenchmark` 用 HTTP API 调用 Qwen。

仅接受完整 JSON、全部题目和全部分布项；重复键、非数值概率、非法总和、截断响应均为 ERROR/INVALID_RESPONSE，仍保留可解析的 token 用量。不会用 0.5 或归一化结果掩盖格式失败。Choice 的置信度根据相对均匀分布计算；概率没有经过独立校准。

---
title: "批量评估问题、证据与回答"
---

`JevEvaluator` 将问题、证据和回答交给统一的 Judge 定义；`JevEvaluationRunner` 回放固定样本并按场景汇总。适合修改提示词、检索或生成模型后，对客服回复和知识答案做质量回归。

## 示例场景：改了客服提示词，检查退款承诺有没有变差

同样面对“退款了吗？”和“尚未执行退款”的业务记录，一条回答如实说明需核查，另一条却声称退款完成。我们希望使用同一套标准比较它们，而不是每次人工临时决定什么算好答案。

先定义“回答有业务证据支持”的标准，再把每条问题、证据、回答放进 `Evaluator.Request`。预期通过或失败的金标只交给 runner，不发送给评审模型。

## 1. 复用草稿评审定义

```java
import io.agentscope.extensions.judge.jev.JevJudge;
import io.agentscope.extensions.judge.jev.NoulQuestion;
import io.agentscope.extensions.judge.jev.evaluation.Evaluator;
import io.agentscope.extensions.judge.jev.evaluation.JevEvaluator;
import io.agentscope.extensions.judge.jev.evaluation.JevEvaluationRunner;
import java.time.Duration;
import java.util.List;

var definition = new JevJudge.Definition("refund-answer-v1", List.of(
    new JevJudge.Criterion("supported",
        new NoulQuestion(
            "Is assistant_answer supported by supporting_context without inventing completed refunds?",
            null),
        true, 0.2, 0.8)));
var judge = new JevJudge(client, Duration.ofSeconds(2));
Evaluator evaluator = new JevEvaluator(judge, definition);
```

`client` 配置见[客户端接入](/v2/zh/jev/guides/client)。定义与[单条 Judge 用法](/v2/zh/jev/guides/judge-api)相同，问题、证据和回答分别映射到 `user_question`、`supporting_context`、`assistant_answer`。

## 2. 构造一正一反的固定样本

```java
var evidence = List.of("业务记录：尚未执行退款");
var honest = new Evaluator.Request("honest", "退款了吗？",
    evidence, "尚未退款，需先核查状态");
var invented = new Evaluator.Request("invented", "退款了吗？",
    evidence, "退款已经完成");
var cases = List.of(
    new JevEvaluationRunner.Case("退款承诺", honest, true),
    new JevEvaluationRunner.Case("退款承诺", invented, false));
var pending = new JevEvaluationRunner().run(evaluator, cases, 2);
```

`honest`、`invented` 是唯一 ID；“退款承诺”是报告中的场景分组；最后的布尔值是预期标签。并发 2 表示最多同时评审两条，实际返回顺序仍与输入一致。不要把错误回答的标签写进证据中。

## 3. 一起看正确、弃权和错误

```java
var observed = pending.doOnNext(report -> {
    System.out.println("总体：" + report.overall());
    System.out.println("退款场景：" + report.scenarios().get("退款承诺"));
    report.rows().forEach(row ->
        System.out.println(row.id() + " " + row.response().verdict().status()));
});
```

报告分别统计正确、错误、弃权和调用错误。准确率是正确数 / 全部样本数，弃权和接口错误不会从分母消失。单条 `pass()` 只在 Judge 总状态 PASS 时为 true；`score()` 是通过条件占比，不能替代完整 verdict。

例如不实回答得到 FAIL 算一次正确判断；如果接口超时，即使 `pass=false`，也不算“正确识别了不实回答”。

## 运行与验证

按[案例构建步骤](/v2/zh/jev/guides/agent-integration-example#构建一次运行多个案例)设置 `JEV_CP` 后执行：

```bash
java -cp "$JEV_CP" io.agentscope.examples.jev.JevApplicationScenarios evaluation
```

离线夹具对两条样本分别返回 PASS、FAIL，报告显示 2 条匹配。它验证标签隔离与统计口径，不是“模型准确率 100%”的证据。[完整用例](/examples/jev/source/JevApplicationScenarios.java.txt)

需要对比真实后端时，让两个 Evaluator 使用同一数据集、定义和预算，保留各自错误、弃权、用量及版本。[评测自己的 Harness](/v2/zh/jev/evaluation)说明如何组织对照。

## 预算、报告与取消

最多 10,000 条样本，ID 唯一，并发范围 1–32。Judge 控制每次评审预算；自定义 Evaluator 也需要自己的超时。runner 捕获单项异常或空响应并记 ERROR；取消会终止整个订阅，不作为正常完成报告返回。

P50/P95 使用最近秩，度量的是评审耗时，不是 Agent 完整任务时间。适配器之外的错误条目耗时可能为零，应同时看错误数。报告不保存原始问题、回答和证据；用量未知时保持未知，没有核实价格时不推算费用。

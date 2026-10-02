---
title: "评估接口与固定样本批量报告"
---


当前仓库没有统一的 Agent 评估 SPI。本次在 JEV 扩展的 `evaluation` 包新增 `Evaluator` 接口，并提供 `JevEvaluator` 及 `JevEvaluationRunner`；不宣称已适配一个不存在的 Core 接口。

```java
Evaluator evaluator = new JevEvaluator(judge, definition);
var request = new Evaluator.Request("case-1", "用户问题",
    List.of("已授权参考证据"), "待评审回答");
var response = evaluator.evaluate(request);
var cases = List.of(new JevEvaluationRunner.Case("客服", request, true));
var report = new JevEvaluationRunner().run(evaluator, cases, 2);
```

定义使用 `user_question`、`supporting_context`、`assistant_answer` 字段。response 保留完整 Judge verdict、逐项概率、定义版本、模型、usage 和耗时。pass 仅在整体 PASS 时为 true；score 为通过条件数/定义条件总数，不确定、错误不算通过，不能用 score 替代 verdict 状态。

固定样本的期望标签仅交给 runner，不发送给评审模型。最多 10000 条，ID 唯一，并发 1..32，报告保持输入顺序，按 scenario 分组。分别统计正确、错误、弃权、调用错误；准确率为正确数/总样本数，错误与弃权留在分母。P50/P95 使用最近秩，当前度量是评审耗时，不是 Agent 端到端耗时。

报告不包含原问题、回答、证据。每项保留模型返回的 usage，未返回时保持未知；未配置价格时不推算成本。替代后端可以实现 Evaluator，在同一固定样本上回放，但本次未运行真实 JEV/Qwen 对照。

JevJudge 负责每次评审超时；自定义 Evaluator 也必须配置自己的预算。runner 捕获单项异常或空响应并记录 ERROR，取消则终止整个订阅，不作为正常报告返回。失败于适配器之外的条目耗时可能为零，不能据此宣称低延迟。

离线案例见[组合案例](/v2/zh/jev/guides/agent-integration-example)。源码：[接口](/examples/jev/source/agentscope-extensions/agentscope-extensions-judge/agentscope-extensions-jev/src/main/java/io/agentscope/extensions/judge/jev/evaluation/Evaluator.java.txt)、[JevEvaluator](/examples/jev/source/agentscope-extensions/agentscope-extensions-judge/agentscope-extensions-jev/src/main/java/io/agentscope/extensions/judge/jev/evaluation/JevEvaluator.java.txt)、[批量报告](/examples/jev/source/agentscope-extensions/agentscope-extensions-judge/agentscope-extensions-jev/src/main/java/io/agentscope/extensions/judge/jev/evaluation/JevEvaluationRunner.java.txt)。

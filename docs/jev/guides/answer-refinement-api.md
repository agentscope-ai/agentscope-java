---
title: "Agent 最终草稿审核与修订"
---

# Agent 最终草稿审核与修订

`JevResponseMiddleware` 已接到 `onModelCall`。只对没有工具调用的文本回答做质量评审；带工具调用的轮次保留工具事件，不把它当最终答案修订。内容安全检查仍覆盖该轮文本。

```java
var definition = new JevJudge.Definition("answer-v1", List.of(
    new JevJudge.Criterion("grounded",
        new NoulQuestion("Is answer supported by the evidence in prompt?", null),
        true, 0.2, 0.8)));
var middleware = JevResponseMiddleware.builder()
    .quality(new JevJudge(client, Duration.ofSeconds(2)), definition,
        new JevExecution.Options(JevExecution.Mode.SHADOW,
            Duration.ofSeconds(10), "answer-v1", (ctx, r) -> observe(r)))
    .maxRevisions(1)
    .build();
```

评审状态包含 `prompt` 和 `answer`；prompt 显式保留消息文本与工具结果文本。定义中只评估实际存在的证据，不假设评审器看过原模型的隐藏状态。

ENFORCE 的 PASS 才发布。FAIL 可触发最多 0..10 次修订；ERROR、INCONCLUSIVE 不触发修订。耗尽、空稿、相同草稿、非法工具调用均终止且不发布原草稿。quality 预算覆盖评审及所有修订，roundBudget 还覆盖原模型生成。

默认修订直接调用该次 ModelCallInput 的模型，工具列表为空；即使模型仍返回工具调用也会拒绝。它不重跑 Agent、工具循环或业务写操作。模型的原调用参数沿用；不支持在此层修订依赖强制工具调用的结构化输出流程。

也可通过 `.reviser(revision -> Flux<String>)` 提供纯草稿修订函数。该函数必须无写工具副作用。每次收到当前草稿、原 ModelCallInput 和完整 Judge 结果。

通过审核后替换文本 delta，核心 ReActAgent 据此重建最终消息，因此流式文本与 AgentResult 一致。修订过程不向外逐字发布，仍保留原调用的事件配对。原模型 ModelCallEnd 的 usage 不包含额外修订调用；修订用量核算需接模型侧观测，不把它当作整轮总成本。

OFF 完全委托原流程；SHADOW 评审但不修订、不替换。每订阅独立保存缓冲和次数，可共享一个中间件实例处理不同会话。

与内容护栏组合时调用同一 builder 的 `.guardrails(...)`，详见[内容护栏](content-guardrail-api.md)。可运行案例见[组合案例](agent-integration-example.md)。

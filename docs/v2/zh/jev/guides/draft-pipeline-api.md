---
title: "独立应用中的审核与有限修订"
---

当应用自己负责生成草稿时，用 `JevDraftPipeline` 串起输入检查、生成、完整草稿审核和有限修订。只有通过审核的文本才会出现在 `publishedText` 中，应用据此决定是否发布。

## 场景：客服工作台中的回复草稿

客服正在处理“订单未发货时可以退款吗？”。后台已有政策证据“未发货订单可申请退款，到账以实际处理结果为准”，但生成器可能写出“退款已经到账”。工作台需要在显示可发送回复之前检查草稿，必要时重写一次，不能为了纠正措辞重新执行退款。

这个场景由应用掌握生成与发布时机，适合独立流水线；如果希望直接拦截 Agent 的最终回答，使用[响应中间件](/v2/zh/jev/guides/answer-refinement-api)。

## 1. 分别定义输入和草稿检查

`judge` 的初始化见[应用组件配置](/v2/zh/jev/guides/application-api#初始化)。输入检查接收原始请求；输出检查接收包含 `request`、`evidence`、`draft` 的状态。定义问题时必须对应这些字段：

```java
import io.agentscope.extensions.judge.jev.JevJudge;
import io.agentscope.extensions.judge.jev.NoulQuestion;
import io.agentscope.extensions.judge.jev.application.JevDraftPipeline;
import java.time.Duration;
import java.util.List;
import reactor.core.publisher.Flux;

var inputChecks = new JevJudge.Definition("refund-input-v1", List.of(
    new JevJudge.Criterion("in_scope",
        new NoulQuestion("Is this a customer request about orders or refunds?", null),
        true, 0.2, 0.8)));
var outputChecks = new JevJudge.Definition("refund-draft-v1", List.of(
    new JevJudge.Criterion("supported",
        new NoulQuestion(
            "Is draft supported by evidence without inventing completed refunds or arrival of funds?",
            null),
        true, 0.2, 0.8)));
var pipeline = new JevDraftPipeline(judge, 16000, 1, Duration.ofSeconds(15));
```

这里最多缓冲 16,000 个字符，最多修订一次，15 秒包含输入审核、生成、输出审核及修订。输入必须为 PASS 才生成草稿；概率阈值是示例配置，须在你的业务样本上校准。

## 2. 接入生成函数，只发布审核通过的文本

下面使用固定字符串演示生成器接口，让输入、错误草稿和修订稿一目了然。实际接入时，替换两个 `Flux.just` 为你的纯文本生成函数；JEV 仍通过传入的 `judge` 完成检查。

```java
var outcome = pipeline.run(
    "订单未发货时可以退款吗？",
    "未发货订单可申请退款，到账以实际处理结果为准。",
    inputChecks,
    outputChecks,
    () -> Flux.just("退款已经到账。"),
    revision -> Flux.just("未发货订单可申请退款，到账以实际处理结果为准。"))
    .block();

if (outcome.publishedText() != null) {
    System.out.println("可发布：" + outcome.publishedText());
} else {
    System.out.println("暂不发布：" + outcome.reason());
}
```

预期流程是错误草稿被评为 FAIL，修订稿通过后才返回可发布文本。真实模型可能给出不同判断，因此应用必须检查结果，不能默认调用成功就可以发送。修订函数收到当前 `draft`、完整 `review` 和 `attempt`，可据此针对未通过的条件改写。

生成与修订函数均不能重跑会执行写工具的 Agent。应用应在流水线成功返回后才调用消息发送接口，不能把生成器的中间片段直接推送给客户。

## 3. 处理无法发布的结果

| `reason` | 场景与处理 |
|---|---|
| `ACCEPTED` | `publishedText` 可发布 |
| `INPUT_NOT_ACCEPTED` | 输入未通过，生成函数不会执行 |
| `DRAFT_NOT_ACCEPTED` | 审核不确定、出错，或修订耗尽；转人工或提示用户 |
| `UNCHANGED_DRAFT` | 修订仍是原文，停止重试 |
| `DRAFT_TOO_LARGE`、`TIMEOUT`、`PIPELINE_ERROR` | 不发布，按应用的错误策略处理 |

只有 FAIL 可以触发修订，ERROR/INCONCLUSIVE 不修订。正常审核拒绝时，`draftText` 可供授权复核，但不能当作回退回答直接发布。错误结果中的 `revisions` 为 0，不能据此计算错误发生前的实际调用次数；计费或调用统计应接在生成函数和客户端侧。

取消会向当前生成链传播。该组件不提供审核前的对外流式输出；如果界面需要实时反馈，可以展示处理状态，待审核完成后一次性呈现文本。

## 运行离线案例

[JevApplicationScenarios](/examples/jev/source/JevApplicationScenarios.java.txt)的 `draft` 场景提供上述请求、证据和两份草稿。按[构建步骤](/v2/zh/jev/guides/agent-integration-example#构建一次运行多个案例)设置 `JEV_CP` 后执行：

```bash
java -cp "$JEV_CP" io.agentscope.examples.jev.JevApplicationScenarios draft
```

案例验证三条路径：错误草稿经过一次修订后发布；相同修订稿停止且不发布；输入被拒时不调用生成器。对应断言在示例模块的 `JevApplicationScenariosTest` 中。生成与判断均为预设，没有测量真实模型的审核准确率和响应时间。

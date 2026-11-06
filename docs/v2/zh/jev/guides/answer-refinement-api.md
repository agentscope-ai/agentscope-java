---
title: "Agent 最终草稿审核与修订"
---

`JevResponseMiddleware` 已接到 `onModelCall`。只对没有工具调用的文本回答做质量评审；带工具调用的轮次保留工具事件，不把它当最终答案修订。内容安全检查仍覆盖该轮文本。

## 场景：避免把有条件退款说成无条件退款

知识库给出的政策是“订单未使用且在七天内才可退款”，但 Agent 草稿写成“可以无条件退款”。此时工具已经完成检索，重跑整个 Agent 可能重复执行其他业务操作；需要修正的是最终回答。

为这个场景定义一项检查：回答必须保留证据中的退款条件。中间件在发布前评审完整草稿，失败时仅请求一次文本修订，再评审修订稿。

## 1. 定义回答必须满足的条件

`client` 初始化见[客户端接入](/v2/zh/jev/guides/client)。检查使用 `assistant_answer` 与 `supporting_context`，后者由已配对的成功工具结果构成：

```java
import io.agentscope.extensions.judge.jev.JevJudge;
import io.agentscope.extensions.judge.jev.NoulQuestion;
import io.agentscope.extensions.judge.jev.JevExecution;
import io.agentscope.extensions.judge.jev.integration.JevResponseMiddleware;
import java.time.Duration;
import java.util.List;

var definition = new JevJudge.Definition("refund-answer-v1", List.of(
    new JevJudge.Criterion("grounded",
        new NoulQuestion(
            "Does assistant_answer retain all refund conditions in supporting_context, without inventing eligibility?",
            null),
        true, 0.2, 0.8)));
var middleware = JevResponseMiddleware.builder()
    .quality(new JevJudge(client, Duration.ofSeconds(2)), definition,
        new JevExecution.Options(JevExecution.Mode.SHADOW,
            Duration.ofSeconds(10), "refund-answer-v1",
            (context, record) -> System.out.println(record)))
    .maxRevisions(1)
    .build();
```

`true` 表示希望条件成立，0.2/0.8 划分失败、不确定与通过区间，须用业务数据校准。单次 Judge 调用最多 2 秒；10 秒覆盖质量评审及全部修订。`maxRevisions(1)` 限制最多一次重写，避免无限纠错。

SHADOW 只评审原稿，不改写、不阻止发布；完成校准后将模式改为 ENFORCE，才能使用下面的修订流程。

## 2. 接入已有 Agent

`model` 和 `toolkit` 沿用应用的模型及工具配置：

```java
import io.agentscope.harness.agent.HarnessAgent;

var agent = HarnessAgent.builder()
    .name("refund-answer-review")
    .model(model)
    .toolkit(toolkit)
    .middleware(middleware)
    .build();
```

在 ENFORCE 下，假设评审器拒绝“无条件退款”，中间件使用当前模型生成一份不携带工具的新草稿；只有修订稿再次评审为 PASS 才发布。示例期望的修订是“订单未使用且在七天内才可退款”，但实际措辞及判定由模型决定。

| 评审或修订结果 | 应用能收到的行为 |
|---|---|
| 原稿 PASS | 发布原稿 |
| 原稿 FAIL，修订稿 PASS | 发布修订稿 |
| ERROR、INCONCLUSIVE 或修订耗尽 | 抛出 `JevResponseMiddleware.Rejected`，不发布原稿 |
| 修订为空、与原稿相同或带工具调用 | 停止修订，不发布未经认可的内容 |

应用应捕获拒绝，显示业务提示或转人工。不要在捕获后重新发布被拒原稿。需要自定义重写时，可配置 `.reviser(revision -> Flux<String>)`；该函数只能处理草稿，不得执行退款等写操作。

## 运行离线案例

[JevIntegrationExample](/examples/jev/source/JevIntegrationExample.java.txt)提供上述完整退款场景。按[构建步骤](/v2/zh/jev/guides/agent-integration-example#构建一次运行多个案例)设置 `JEV_CP` 后执行：

```bash
java -cp "$JEV_CP" io.agentscope.examples.jev.JevIntegrationExample
```

案例使用预设生成与评审结果，断言最终文本保留 `seven days`，生成模型共调用三次：检索工具建议、原始回答、纯文本修订。修订调用的工具列表必须为空。这验证流程接线，不代表真实模型准确率或修订成功率。

## 证据、预算与输出边界

评审状态包含兼容字段 `prompt` / `answer`，以及 `user_question`、`assistant_answer`、按 ID 配对的 `tool_calls`、成功工具结果的 `supporting_context` 和 `trace_issues`。`qualityState(input, answer)` 可供应用复用。元数据与思考不作为证据；工具结果内容仍需核验。

轨迹配对不完整时返回 INCONCLUSIVE / INCOMPLETE_JUDGING_EVIDENCE，整个序列化判断状态超过 maxChars 时返回 JUDGE_STATE_LIMIT；ENFORCE 不发布，SHADOW 不改变原输出。详见[检索证据与审核组合](/v2/zh/jev/guides/evidence-pipeline-api)。定义只评估实际存在的证据，不假设评审器看过隐藏状态。

`maxRevisions` 范围为 0..10；quality 预算覆盖评审及所有修订，roundBudget 还覆盖原模型生成。

默认修订直接调用该次 ModelCallInput 的模型，工具列表为空；即使模型仍返回工具调用也会拒绝。它不重跑 Agent、工具循环或业务写操作。模型的原调用参数沿用；不支持在此层修订依赖强制工具调用的结构化输出流程。

通过审核后替换文本 delta，核心 ReActAgent 据此重建最终消息，因此流式文本与 AgentResult 一致。修订过程不向外逐字发布，仍保留原调用的事件配对。原模型 ModelCallEnd 的 usage 不包含额外修订调用；修订用量核算需接模型侧观测，不把它当作整轮总成本。

OFF 完全委托原流程；SHADOW 评审但不修订、不替换。每订阅独立保存缓冲和次数，可共享一个中间件实例处理不同会话。

与内容护栏组合时调用同一 builder 的 `.guardrails(...)`，详见[内容护栏](/v2/zh/jev/guides/content-guardrail-api)。可运行案例见[组合案例](/v2/zh/jev/guides/agent-integration-example)。

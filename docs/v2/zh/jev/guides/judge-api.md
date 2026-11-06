---
title: "用条件定义评审标准"
---

`JevJudge` 把一组必需的评审条件聚合为 PASS、FAIL、INCONCLUSIVE 或 ERROR。适合草稿是否有依据、是否覆盖需求等检查；同一份带版本定义可以复用于在线审核和离线回放。

## 示例场景：回复不能把“尚未退款”说成“退款完成”

客户问“退款了吗？”，业务记录显示尚未执行退款。Agent 的草稿可能如实说明需先核查，也可能直接声称“退款已经完成”。仅检查句子是否流畅，不能发现这种无依据承诺。

应用把客户问题、业务证据与草稿一起交给 Judge，要求评审“回答是否得到证据支持”。审核发生在发布前，但 Judge 只返回结果，何时修订、复核或发布由应用决定。

## 1. 定义要满足的条件

```java
import io.agentscope.extensions.judge.jev.JevJudge;
import io.agentscope.extensions.judge.jev.NoulQuestion;
import java.time.Duration;
import java.util.List;
import java.util.Map;

var definition = new JevJudge.Definition("refund-answer-v1", List.of(
    new JevJudge.Criterion("supported",
        new NoulQuestion(
            "Is assistant_answer supported by supporting_context without inventing completed refunds?",
            null),
        true, 0.2, 0.8)));
```

`true` 表示期望条件成立；0.2 和 0.8 是失败、通过阈值，中间区间保留不确定。它们是演示配置，正式使用前应在自己的业务样本上校准。

`refund-answer-v1` 标识这套标准。修改问题、字段含义或阈值时更新版本，才能区分“模型表现变了”与“评审规则变了”。

## 2. 把真实证据和草稿交给 Judge

```java
var judge = new JevJudge(client, Duration.ofSeconds(2));
var state = Map.of(
    "user_question", "退款了吗？",
    "supporting_context", List.of("业务记录：尚未执行退款"),
    "assistant_answer", "退款已经完成");
var pending = judge.judge(state, definition);
```

`client` 按[客户端接入](/v2/zh/jev/guides/client)创建。2 秒是这次评审的总预算，包含客户端重试和退避。证据必须来自实际业务记录，不能用 Agent 自己的承诺作为退款成功证明。

这三个字段与 [JevEvaluator](/v2/zh/jev/guides/evaluator-api) 的输入一致，因此这份 definition 可以直接用于后续批量回归。

## 3. 按状态决定草稿下一步

```java
var observed = pending.doOnNext(result -> {
    System.out.println("定义版本：" + result.definitionVersion());
    System.out.println("评审状态：" + result.status());
    System.out.println("条件明细：" + result.findings());
});
```

服务中返回或组合 `observed`；独立命令行可用 `observed.block()`。不要为了日志再订阅一次，同一个 Mono 每次订阅都会重新评审。

| 条件得到的支持概率（示意） | 状态 | 草稿处理 |
|---|---|---|
| 0.95 | PASS | 可以进入应用后续发布检查 |
| 0.05 | FAIL | 修订无依据表述或人工复核 |
| 0.50 | INCONCLUSIVE | 补充证据，不能当作通过 |
| 无有效响应 | ERROR | 按审核失败策略处理，不补造概率 |

概率不是文字理由。`findings()` 保留逐项概率和状态，不自动生成原因解释、证据引用或 run/session；应用应关联原业务记录。

## 运行与验证

按[案例构建步骤](/v2/zh/jev/guides/agent-integration-example#构建一次运行多个案例)设置 `JEV_CP`：

```bash
java -cp "$JEV_CP" io.agentscope.examples.jev.JevApplicationScenarios judge
```

用例把同一条件分别送入三个预设概率分支，验证 PASS、FAIL 和 INCONCLUSIVE 的处理，没有真实模型调用。完整代码见 [JevApplicationScenarios](/examples/jev/source/JevApplicationScenarios.java.txt)。

需要“覆盖客户条件 + 无依据承诺”两条条件组合，可运行 [JevCustomerSupportExample](/examples/jev/source/JevCustomerSupportExample.java.txt)：

```bash
java -cp "$JEV_CP" io.agentscope.examples.jev.JevCustomerSupportExample
```

它默认离线，只有显式追加 `--live` 才调用真实接口。离线结果不用于计算真实模型准确率或速度。

## 多条件、错误和取消

同一 Definition 中的条件都必须满足：任一 FAIL 使总结果 FAIL；没有 FAIL 但有不确定项时为 INCONCLUSIVE；全部通过才 PASS。没有加权平均，不把 Choice/Score 强制聚合成通过/失败。定义必须非空、条件 ID 不重复。

如果条件问的是“是否存在无依据承诺”，期望应设为 false，此时使用 `1 - p` 与阈值比较。阈值要求 `0 <= fail < pass <= 1`，边界分别包含在失败和通过区间内。

总预算耗尽记 ERROR/TIMEOUT；Judge 检测到缺项、错类型或非法概率时记 ERROR/INVALID_RESPONSE；客户端和其他后端异常记 ERROR/BACKEND。错误结果不保存原始异常文本，不把失败当概率 0。取消终止订阅，不返回伪造的 ERROR 结果；远端是否停止计算取决于传输实现。

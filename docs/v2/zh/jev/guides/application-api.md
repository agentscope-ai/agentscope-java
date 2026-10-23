---
title: "对业务候选进行多标签分类"
---

`JevCandidateSelector` 分别判断每个候选是否适用，适合工单队列、文档和团队成员等可多选场景。它返回选中、排除和不确定三组建议，业务动作由应用决定。

## 示例场景：一张工单同时涉及订单与退款

客户说：“订单没发货，如果今天还发不了请退款。”客服系统有订单、账单和技术三个队列。如果只取一个分类，可能遗漏退款诉求；如果看到“退款”就直接执行，又会丢掉用户的前置条件。

这里先用 JEV 判断哪些队列需要参与处理。订单和账单可以同时被推荐，技术队列可以被排除。分类结果只决定处理方向，不能把条件性申请变成退款执行许可。

## 初始化

```java
import io.agentscope.extensions.judge.jev.JevClient;
import io.agentscope.extensions.judge.jev.JevExecution;
import io.agentscope.extensions.judge.jev.JevJudge;
import io.agentscope.extensions.judge.jev.application.JevCandidateSelector;
import java.time.Duration;
import java.util.Map;

var client = JevClient.builder().build();
var options = new JevExecution.Options(
    JevExecution.Mode.SHADOW, Duration.ofSeconds(2), "ticket-routing-v1",
    (context, record) -> System.out.println(record));
var selector = new JevCandidateSelector(client, options, 0.2, 0.8);
var judge = new JevJudge(client, Duration.ofSeconds(2));
```

依赖与凭据见[客户端接入](/v2/zh/jev/guides/client)。后续应用组件页中的 `selector`、`judge` 可以复用这里的初始化；本页分流只用到 `selector`。

两个阈值分别表示“明确不适用”和“明确适用”。例如概率为 0.5 的账单队列应进入不确定组，应用可以继续澄清，不能把它当作明确不需要。示例阈值未校准。

## 2. 给出当前请求和队列候选

```java
var pending = selector.select(ctx,
    Map.of("request", "订单没发货，如果今天还发不了请退款"),
    "Does this request need the candidate support queue?",
    Map.of("orders", "订单查询",
           "billing", "退款和账单",
           "technical", "技术故障"));
```

`ctx` 是本次调用的 `RuntimeContext`；候选 key 是应用自己的队列 ID，value 描述队列职责。每个候选独立判断，所以不要求所有概率之和为 1。候选必须已经通过宿主的授权和可用性检查。

## 3. 先看判断状态，再使用建议

```java
var observed = pending.doOnNext(decision -> {
    if (decision.status() != JevExecution.Status.DECIDED) {
        System.out.println("沿用原分流：" + decision.reason());
        return;
    }
    System.out.println("建议参与：" + decision.value().selected());
    System.out.println("待澄清：" + decision.value().uncertain());
});
```

在响应式服务里返回或组合 `observed`；独立命令行可以 `observed.block()`。SHADOW 下只记录建议。这个 API 本身不分派工单，即使改成 ENFORCE，宿主仍要明确决定何时采用建议。

## 运行与验证

按[案例构建步骤](/v2/zh/jev/guides/agent-integration-example#构建一次运行多个案例)设置 `JEV_CP` 后执行：

```bash
java -cp "$JEV_CP" io.agentscope.examples.jev.JevApplicationScenarios classification
```

预设订单 0.95、账单 0.90、技术 0.05，输出选中 `[orders, billing]`、排除 `[technical]`，实际业务动作数为 0。它验证多标签结果与业务动作的分离，不代表真实模型准确率。[JevApplicationScenarios 完整源码](/examples/jev/source/JevApplicationScenarios.java.txt)

## 错误与规模边界

最多 1,024 个候选，按 64 项串行分批，共享总预算；空候选不请求。选中项按概率降序，同分按 ID 排序。OFF 或失败没有有效选择，应保留原流程或交人工处理；取消终止订阅。具体队列映射和重试策略由宿主控制。

需要同时评审回复时，继续看[客服多诉求与草稿评审](/v2/zh/jev/guides/support-api)。

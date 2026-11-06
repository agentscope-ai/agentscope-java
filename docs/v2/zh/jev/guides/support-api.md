---
title: "客服多诉求分流与回复评审"
---

`JevCustomerSupport` 同时给出队列分流建议和回复草稿评审，适合条件性退款、多诉求工单和无依据承诺检查。组件只产生报告，不提交工单、发送回复或执行退款。

## 示例场景：客户提出条件性退款，草稿却说已经完成

客户说：“若今天无法发货，请申请退款。”业务系统尚未发起退款，但 Agent 生成了“退款已经完成”。这时需要解决两个问题：由哪些客服队列参与，以及这份草稿是否适合继续使用。

JEV 可以同时建议订单和账单队列，并检查草稿是否覆盖用户条件、是否承诺了没有成功业务记录支持的动作。分流建议不会取消客户的条件，草稿通过也不是支付执行授权。

## 1. 将原请求、草稿和业务记录一起交给评审

```java
import io.agentscope.extensions.judge.jev.JevExecution;
import io.agentscope.extensions.judge.jev.application.JevCustomerSupport;
import java.util.Map;

var support = new JevCustomerSupport(selector, judge);
var pending = support.review(ctx,
    "若今天无法发货，请申请退款",
    "退款已经完成",
    Map.of("refund", "尚未执行"));
```

`selector` 和 `judge` 使用[应用组件初始化](/v2/zh/jev/guides/application-api#初始化)，`ctx` 为当前调用。草稿取自尚未发送的文本，业务记录应来自实际执行结果；不能用 Agent 自述代替退款成功凭据。

## 2. 分别处理分流与草稿质量

```java
var observed = pending.doOnNext(report -> {
    System.out.println("分流状态：" + report.triage().status());
    if (report.triage().status() == JevExecution.Status.DECIDED) {
        System.out.println("建议队列：" + report.triage().value().selected());
        System.out.println("待澄清队列：" + report.triage().value().uncertain());
    }
    System.out.println("草稿状态：" + report.review().status());
    System.out.println("评审条件：" + report.review().findings());
});
```

分流固定提供 `orders`、`billing`、`technical` 三类，可以多选；草稿评审独立返回 Judge 状态。应用可以只记录建议，也可以把未通过的草稿送人工复核或有限修订。两个分支有各自预算，不能假定整个组合共享一个总预算。

需要别的队列体系时直接使用[候选分类](/v2/zh/jev/guides/application-api)。需要修改文本时使用[有限修订](/v2/zh/jev/guides/answer-refinement-api)，避免为了修正一句承诺而重新执行退款流程。

## 运行与验证

按[案例构建步骤](/v2/zh/jev/guides/agent-integration-example#构建一次运行多个案例)设置 `JEV_CP` 后执行：

```bash
java -cp "$JEV_CP" io.agentscope.examples.jev.JevApplicationScenarios support
```

预设结果推荐订单和账单，草稿总状态 FAIL；退款次数和发送消息次数均为 0。用例验证报告与业务动作的边界，未测真实模型准确率。[JevApplicationScenarios 完整源码](/examples/jev/source/JevApplicationScenarios.java.txt)

## 接入位置与失败处理

在草稿生成后、正式发送前显式调用；Service 不会仅因配置客户端就自动创建客服流程。分流错误或不确定时保留原归属或人工分流；草稿 ERROR/INCONCLUSIVE 不按通过处理。取消组合订阅会向两条评审链传播。

内置标准来自 `JevCustomerSupport.definition()`，检查需求覆盖和无依据完成承诺。定制其他审核要求时，复用 [Judge 定义](/v2/zh/jev/guides/judge-api)。

---
title: "客服分流与草稿评审API"
---

# 客服分流与草稿评审API

状态：已实现`JevCustomerSupport`，普通应用可在得到实际草稿之后调用，无需Agent Loop。初始化与可运行main见[应用API](application-api.md)。

```java
var support = new JevCustomerSupport(selector, judge);
support.review(ctx, "若今天无法发货，请申请退款", draft, businessRecord)
    .subscribe(report -> saveReview(report));
```

draft与businessRecord是应用真实的草稿和业务记录，saveReview是宿主自己的保存函数。报告包含多意图分流建议和JevJudge评审；分流队列为orders、billing、technical，可能多选或不确定。条件性退款仍是条件，不代表授权执行。

草稿检查需求覆盖和无依据承诺，复用已有客服definition。判断和分流仅产生报告，不发消息、不发起退款、不自动提交工单。应用按自己的业务规则映射队列；每个分支独立预算。测试覆盖多诉求、承诺检查；示例fixture不是准确率结论。Service中的自动客服工作流尚未装配，需业务提供草稿和真实记录后显式接入。

源码：[JevCustomerSupport](../../../agentscope-extensions/agentscope-extensions-judge/agentscope-extensions-jev/src/main/java/io/agentscope/extensions/judge/jev/application/JevCustomerSupport.java)。

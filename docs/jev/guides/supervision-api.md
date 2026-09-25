---
title: "任务监督建议API与验证案例"
---

# 任务监督建议API与验证案例

状态：已实现独立`JevSupervisor`；不自动注册到运行循环。初始化与离线main见[应用API](application-api.md)。

```java
var supervisor = new JevSupervisor(judge);
supervisor.assess(task, boundedEvidence, verificationSucceeded)
    .subscribe(report -> recordAdvice(report));
```

task为任务说明；boundedEvidence由宿主裁剪后的差异、运行轨迹和测试结果组成；verificationSucceeded由实际验证流程提供，不能从worker口头声明推导。recordAdvice为应用自身记录函数。

评估是否完成、是否停滞或偏航，返回CONTINUE、REQUEST_VERIFICATION、REVIEW_COMPLETION或MANUAL_REVIEW。即使语义判断完成，如果没有真实验证成功标志，也只能建议请求验证。ERROR/INCONCLUSIVE进入人工复核建议。

本组件不终止任务、不启动worker、不宣布完成；应用可以先旁路记录再统计误干预。测试覆盖“worker声称完成但测试未通过”不能得到完成复核建议。Foreman式持续观察、去抖和自动干预还没有接入；jev-review式逐文件风险筛查也未实现，不将此最小监督器描述成完整代码审查产品。

源码：[JevSupervisor](../../../agentscope-extensions/agentscope-extensions-judge/agentscope-extensions-jev/src/main/java/io/agentscope/extensions/judge/jev/application/JevSupervisor.java)。

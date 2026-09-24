---
title: "阶段团队建议与只读浏览器试点API"
---

# 阶段团队建议与只读浏览器试点API

状态：已实现显式建议组件。初始化与可运行main见[应用API](application-api.md)。

## 阶段与团队

```java
new JevTeamPlanner(selector)
    .recommend(ctx, "review", task, members, member -> directory.isEligible(member.id()));
```

Member包含id和capabilities，stage必须显式给出。directory是宿主授权/健康目录，在候选发送前过滤；重复ID拒绝。不推断隐式阶段，不创建任务，不派发团队成员。每次调用只对当前阶段给建议；阶段内模型粘性由JevStageRouter复用已有模型路由中间件负责，不把团队建议当作模型自动切换。

测试验证不可用成员不进入候选。显式阶段模型路由也提供`JevStageRouter`：

```java
var stages = new JevStageRouter(configuredModelRouter);
var phase = stages.begin(parentContext, "review", taskDescription).block();
stages.onModelCall(phase, modelCallInput, nextModelCall);
```

configuredModelRouter使用[模型路由API](model-routing-api.md)配置。每次begin创建独立调用上下文，选择一次模型并在该Phase保持粘性；parentContext原路由不被覆盖，阶段名称写入`jev.stage`供观察器关联。begin返回冷Mono，应保留其结果，不能每轮重新订阅来伪造阶段。

测试覆盖两个阶段的选择隔离。阶段识别、Team任务状态机和路由成本账本尚未自动接入。

## 浏览器只读建议

```java
new JevBrowserPlanner(selector).propose(
    ctx, goal, pageVersion, observedActions,
    () -> browser.currentPageVersion(), independentlyComplete);
```

Action包含id、operation、visibleTarget，候选只能来自当前观察。操作限READ、SCROLL、WAIT、DONE、BLOCKED，不提供提交表单、付款或任意点击。选择后复核页面版本；不确定或无候选则ABSTAIN，页面变化返回STALE_PAGE。

DONE必须有独立完成验证；JEV自己选择DONE不算完成。宿主执行前还要调用proposal.validFor(currentVersion)并原子绑定页面快照，避免检查后页面再次变化。API只返回建议，不附带浏览器驱动。

main使用内存页面版本与READ候选展示选择；测试覆盖过期页面、未经验证完成。真实站点的DOM适配、动作执行、文本填写与成功率测量尚未实现，因此当前只是只读应用的决策组件。

源码：[JevStageRouter](../../../agentscope-extensions/agentscope-extensions-judge/agentscope-extensions-jev/src/main/java/io/agentscope/extensions/judge/jev/application/JevStageRouter.java) · [JevTeamPlanner](../../../agentscope-extensions/agentscope-extensions-judge/agentscope-extensions-jev/src/main/java/io/agentscope/extensions/judge/jev/application/JevTeamPlanner.java) · [JevBrowserPlanner](../../../agentscope-extensions/agentscope-extensions-judge/agentscope-extensions-jev/src/main/java/io/agentscope/extensions/judge/jev/application/JevBrowserPlanner.java)。

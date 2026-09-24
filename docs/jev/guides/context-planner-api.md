---
title: "上下文成对卸载与恢复API"
---

# 上下文成对卸载与恢复API

状态：已实现`JevContextPlanner`的显式建议API，不直接修改AgentState。初始化与离线main见[应用API](application-api.md)。

```java
var planner = new JevContextPlanner(selector);
planner.plan(ctx, task, exchanges).subscribe(plan -> {
    var retained = plan.retained();
    var archive = plan.archive();
    var restored = plan.restore();
});
```

Exchange包含id、call、result、pinned。调用和结果必须同时存在，ID不得重复；最近消息和关键约束由宿主标为pinned。只对未固定的完整交换判断是否需要保留；明确不需要的整对移到archive，不确定时保留。失败或关闭保留全部。

Plan保存原始顺序并可restore回原列表，不重写工具内容。此首版archive是内存映射，尚未接入磁盘或Service资源存储；返回对象仍引用宿主call/result，宿主不得修改它们。它证明卸载与恢复的组合边界，并不意味着已经降低进程内存或自动缩短Harness提示词。

测试覆盖固定项保护、成对归档、原始顺序恢复。真正接入Compaction需先测关键事实保留率和端到端任务成功率；长期记忆使用独立准入API。

源码：[JevContextPlanner](../../../agentscope-extensions/agentscope-extensions-judge/agentscope-extensions-jev/src/main/java/io/agentscope/extensions/judge/jev/application/JevContextPlanner.java)。

---
title: "模型路由API与能力约束案例"
---


状态：原有调用级路由已实现。新增显式阶段、目录预筛、思考等级及执行用量见[阶段路由 API](/v2/zh/jev/guides/phase-routing-api)；团队调度仍为后续工作。运行示例见[公共API与离线案例](/v2/zh/jev/guides/harness-runtime)。

```java
var router = JevModelRouterMiddleware.builder(client)
    .execution(options)
    .choice("fast", fastModel, "适合简单查询")
    .choice("strong", strongModel, "适合多步复杂分析")
    .confidenceThreshold(0.8)
    .eligible((ctx, candidate) -> availability.isAvailable(candidate.model()))
    .compatible((input, candidate) -> capabilities.supports(candidate.model(), input))
    .build();
```

availability、capabilities是应用自己的资源/能力目录，上例为接入位置，不是新增框架类。默认eligible接受注册候选；默认compatible只允许没有工具的模型请求。有工具时必须提供明确的能力检查，否则使用原模型。接口是BiPredicate，执行时应快速返回。

onAgent只选择一次，结果保存在当前RuntimeContext；不同会话不共享判断。候选1至255个，应用预先注册授权模型，并通过eligible筛去当前不可用项。JEV返回非候选、低置信度或故障时用原模型。

onModelCall复核eligible和compatible；一旦失效，本次Agent调用后续保持原模型，不在工具循环中重新选择或重放任务。SHADOW只记录推荐模型，保持原ModelCallInput。实际模型后端自身失败仍交由原执行链处理，不由路由器重跑Agent。

基本离线案例选择fast；测试覆盖调用内粘性、原模型回退、SHADOW观察、候选中途失效、独立上下文与工具能力未声明。模型成本和端到端质量仍需独立对照测量。

源码：[JevModelRouterMiddleware](/examples/jev/source/agentscope-extensions/agentscope-extensions-judge/agentscope-extensions-jev/src/main/java/io/agentscope/extensions/judge/jev/example/JevModelRouterMiddleware.java.txt)。

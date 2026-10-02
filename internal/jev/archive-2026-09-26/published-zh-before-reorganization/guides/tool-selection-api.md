---
title: "工具选择API与订单案例"
---


状态：已实现。使用`JevToolSelectionMiddleware.builder(client).execution(options)`，共用[运行模式与离线案例](/v2/zh/jev/guides/harness-runtime)。

```java
var selector = JevToolSelectionMiddleware.builder(client)
    .execution(options)
    .maxTools(3)
    .confidenceThreshold(0.8)
    .rejectionThreshold(0.2)
    .alwaysIncludeTools(Set.of("generate_response"))
    .build();
```

`confidenceThreshold`现在表示独立工具适用概率的接纳阈值，非旧Choice分布置信度；必须满足`0 <= rejection < confidence <= 1`。原有默认必要工具为`load_skill_through_path`、`reset_tools`、`generate_response`，且只保留输入里确实存在的工具。maxTools只限制可选工具。

每轮onReasoning以当前消息和工具schema判断，按64个独立Noul问题分批串行发送，同一总预算覆盖所有批次。无可选工具或无用户文本不请求；一个候选仍判断。多个相关工具能同时保留，再按适用概率排序截取；同分保持原候选顺序，最终传给模型的工具顺序沿用原输入。

任一工具概率位于两个阈值之间时整次回退原输入；非法、缺项、多项或后端失败也回退。全部明确不适用时只保留必要工具，不恢复全量。筛选不赋予执行权限。

订单案例：查询订单保留order；条件性退款可同时保留order和refund；闲聊可返回none。通用离线main展示order=0.95、refund=0.01；工具测试覆盖none、单候选、多工具、130候选跨批及失败回退。业务阈值必须另外校准。

源码：[JevToolSelectionMiddleware](/examples/jev/source/agentscope-extensions/agentscope-extensions-judge/agentscope-extensions-jev/src/main/java/io/agentscope/extensions/judge/jev/example/JevToolSelectionMiddleware.java.txt)。

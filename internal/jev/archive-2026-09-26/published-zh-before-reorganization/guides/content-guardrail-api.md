---
title: "Agent 内容护栏 API"
---


已实现 `JevGuardrail` 与 `JevResponseMiddleware`，区分内容安全、草稿质量和工具权限。默认关闭；未校准的真实场景先开启 SHADOW。依赖沿用 `agentscope-extensions-jev`，不需要修改核心 Model 接口。

```java
var policy = JevGuardrail.defaults(client);
var options = new JevExecution.Options(JevExecution.Mode.SHADOW,
    Duration.ofSeconds(2), "content-v1", (ctx, record) -> observe(record));
var middleware = JevResponseMiddleware.builder()
    .guardrails(policy, policy, options) // 输入、输出可分别传 null 关闭
    .blockOnReview(false)
    .maxChars(64000).maxEvents(8192)
    .roundBudget(Duration.ofSeconds(120))
    .build();
// ReActAgent.builder().middleware(middleware)
```

每次模型调用前检查消息文本及工具结果中的文本；输出完整缓冲后检查。四种结论为 PASS、REVIEW、BLOCK、SUPPORT。默认 REVIEW 继续，`blockOnReview(true)` 可阻止；严重度可将 REVIEW 升为 BLOCK。默认策略包含越权提示、严重伤害/违法协助、自伤危机，阈值是配置起点，不代表业务已校准。

`JevGuardrail` 可使用自定义 Hazard 列表及概率、严重度阈值，`evaluate(SystemOneResult)` 可纯离线测试。`screen` 本身不设置超时；中间件通过 `JevExecution` 统一预算、非法响应、取消和观察器异常处理。

ENFORCE 下，BLOCK/SUPPORT、调用错误或超时终止当前模型流程，抛出 `JevResponseMiddleware.Rejected`；不向用户释放被拒文本，不派发该轮已提出的工具。SUPPORT 通过观察记录保留，宿主可映射为人工支持入口。本实现不自动发送支持消息。确定性 PermissionEngine 和执行前工具防护仍需保留。

SHADOW 保持原事件对象及顺序，不进行修订；检查建议记录在 `content` 用途中。超过捕获上限时停止捕获，不截断原输出。ENFORCE 超限直接结束流程；整轮预算包含原模型生成，单次护栏预算包含 JEV 重试。取消传播至当前订阅，不再次调用 Agent。

审核范围是文本，不是图片、音频、工具参数或思考块。标准 AgentEvent/AgentResult 消费路径受到保护；旧版原始模型 chunk Hook 在此中间件之前触发，不应将该 Hook 作为对外发布通道。启用时不得另行绕过审核输出原始草稿。

与修订一起使用同一个 `JevResponseMiddleware`：先安全检查，再质量评审；每份修订稿再次安全检查，安全拒绝不能通过质量重试绕过。

离线组合案例见[集成案例](/v2/zh/jev/guides/agent-integration-example)，Service 配置见[Service API](/v2/zh/jev/guides/service-api)。

源码：[JevGuardrail](/examples/jev/source/agentscope-extensions/agentscope-extensions-judge/agentscope-extensions-jev/src/main/java/io/agentscope/extensions/judge/jev/JevGuardrail.java.txt)、[响应中间件](/examples/jev/source/agentscope-extensions/agentscope-extensions-judge/agentscope-extensions-jev/src/main/java/io/agentscope/extensions/judge/jev/integration/JevResponseMiddleware.java.txt)。

---
title: "执行前防护API与退款案例"
---

# 执行前防护API与退款案例

状态：已实现。使用`JevAutoModeMiddleware`的onActing边界。初始化与运行命令见[公共API与离线案例](harness-runtime.md)。

```java
var guard = JevAutoModeMiddleware.builder(client)
    .execution(options)
    .guardedTools(Set.of("refund", "send_message"))
    .safetyThreshold(0.8)
    .build();
```

默认OFF，演示阈值0.8不是生产校准结论。SHADOW保留原调用及状态；ENFORCE只放行概率达到阈值的受保护调用。缺状态、缺答案、非法概率、超时或后端失败均拒绝受保护项。非保护工具保留原顺序，允许项一次交给next；没有自动重试next。

拒绝产生与原toolCallId匹配的start/text/end事件，并在可用AgentState中写入DENIED结果。缺AgentState仍返回配对事件，但无法写入不存在的会话状态；原因码为MISSING_STATE。允许通过后仍由PermissionEngine决定是否真正执行。

人工已确认的ALLOWED调用沿用原确认链跳过语义检查；Service确认链通过JevConfirmedCalls绑定工具名与参数指纹，只有匹配才跳过检查；同一调用ID的已确认参数变化时，ENFORCE以CONFIRMATION_CHANGED拒绝并要求重新复核，不允许JEV覆盖旧确认失效。本中间件不构造授权凭据。上游修改已确认调用参数必须重置确认状态并重新校验；不能把旧ALLOWED标记复制到新参数上。

退款案例使用模拟调用，低安全概率时仅生成拒绝事件。离线测试验证混合调用、缺状态、意外答案、SHADOW不写状态和取消不派发。同步确认、其他重写中间件必须安排在本检查之前，避免检查后修改参数。

源码：[JevAutoModeMiddleware](../../../agentscope-extensions/agentscope-extensions-judge/agentscope-extensions-jev/src/main/java/io/agentscope/extensions/judge/jev/example/JevAutoModeMiddleware.java) · [JevConfirmedCalls](../../../agentscope-extensions/agentscope-extensions-judge/agentscope-extensions-jev/src/main/java/io/agentscope/extensions/judge/jev/JevConfirmedCalls.java)。

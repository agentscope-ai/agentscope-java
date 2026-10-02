---
title: "长期记忆准入API与冲突案例"
---


状态：已实现`JevMemoryGate`，只提供写入前的评审，不自动写长期记忆。初始化与离线main见[应用API](/v2/zh/jev/guides/application-api)。

```java
var gate = new JevMemoryGate(judge);
gate.assess(owner, candidate, existingFacts).subscribe(result -> reviewAdmission(result));
```

Fact包含owner、text、source且均非空。候选与现有事实必须全部属于显式owner，否则在调用模型前拒绝。source由宿主提供可信证据，不能把任意文本字段当作已验证来源。

两个独立条件为持久价值与冲突；期望conflict=false，因此高冲突概率使准入失败，不会被其他条件的高分抵消。ERROR/INCONCLUSIVE不构成写入依据。reviewAdmission为宿主策略，写入仍需自己的权限、幂等和来源校验。

离线测试验证跨owner拒绝与冲突不通过；默认不删除旧记忆。自动拦截MemoryFlush及显式写入工具仍需后续按实际存储逐条接入。

源码：[JevMemoryGate](/examples/jev/source/agentscope-extensions/agentscope-extensions-judge/agentscope-extensions-jev/src/main/java/io/agentscope/extensions/judge/jev/application/JevMemoryGate.java.txt)。

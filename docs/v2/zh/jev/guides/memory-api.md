---
title: "检查长期记忆的价值与事实冲突"
---

`JevMemoryGate` 在记忆写入前评审候选事实的长期价值及事实冲突。它返回准入建议，写入、更新或删除仍由应用自己的记忆流程负责。

## 示例场景：记住偏好，但不要悄悄覆盖旧事实

用户明确要求“以后回答简洁一点”，应用准备把它存为长期偏好。这类信息可能值得保留；但如果候选来自时间不明的旧笔记，并与已有偏好相反，就需要先核查来源及是否属于明确更新。

JEV 分别判断“是否有持久价值”和“是否存在缺乏更新依据的冲突”。两项都必须通过，不能用高价值分数抵消冲突。owner 检查则由代码在请求模型前完成。

## 1. 构造带归属和来源的候选

```java
import io.agentscope.extensions.judge.jev.application.JevMemoryGate;
import java.util.List;

String owner = "user-42";
var candidate = new JevMemoryGate.Fact(
    owner, "偏好简洁的回答", "本轮用户明确要求");
var existingFacts = List.<JevMemoryGate.Fact>of();
```

`owner` 应来自认证后的用户身份；`source` 应描述应用实际掌握的来源证据。填写一个来源字符串不等于完成来源验证，不能把其他用户的记忆混进这次评审。

## 2. 在真正写入前评审

```java
var gate = new JevMemoryGate(judge);
var pending = gate.assess(owner, candidate, existingFacts);
var observed = pending.doOnNext(result -> {
    System.out.println("准入状态：" + result.status());
    System.out.println("逐项结果：" + result.findings());
});
```

`judge` 初始化见[应用组件配置](/v2/zh/jev/guides/application-api#初始化)。应用应把这个 Mono 组合到自己的写入流程，先观察结果再决定是否接管。只有 PASS 表示两项评审都通过，仍须经过宿主权限、来源和幂等检查。

| 状态 | 在偏好记忆中的处理 |
|---|---|
| PASS | 可以进入应用的写入或更新审核 |
| FAIL | 保留当前记忆，不自动覆盖；核查不符合的条件 |
| INCONCLUSIVE | 证据不足，继续澄清或暂不写入 |
| ERROR | 本次没有有效判断，不能按通过处理 |

## 运行与验证

按[案例构建步骤](/v2/zh/jev/guides/agent-integration-example#构建一次运行多个案例)设置 `JEV_CP` 后执行：

```bash
java -cp "$JEV_CP" io.agentscope.examples.jev.JevApplicationScenarios memory
```

用例演示无冲突时 PASS、预设高冲突时 FAIL，两个分支实际写入数都为 0。概率由离线夹具提供，不是模型效果测量。[JevApplicationScenarios 完整源码](/examples/jev/source/JevApplicationScenarios.java.txt)

## 边界与错误

候选与现有事实必须全部属于显式 owner，否则调用模型前抛出参数错误。内置条件 `durable` 期望 true，`conflict` 期望 false，使用 0.2/0.8 演示阈值；需要自定义准入政策时，用 [JevJudge](/v2/zh/jev/guides/judge-api) 定义规则。

组件不会自动拦截 MemoryFlush 或其他写工具，宿主必须在真实写入入口调用。准入失败不删除旧记忆，上下文压缩的结论也不构成长期记忆写入许可。超时和取消沿用 Judge 契约。

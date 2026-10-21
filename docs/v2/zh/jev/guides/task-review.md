---
title: "对任务产物做单次复核"
---

`JevSupervisor` 在应用指定的检查点评估任务是否完成、有无偏航，并结合宿主验证结果给出下一步建议。适合已经掌握任务和产物证据、不需要持续事件观察的流程。

## 示例场景：退款补丁已写完，但测试还没执行

研发 Agent 提交了一份修改说明，声称已补上退款前的订单状态检查。应用需要决定是继续实现、先跑验证，还是进入交付复核。

JEV 判断提供的产物证据能否支持任务完成；是否通过实际验证则由宿主提供。即使完成概率很高，`verificationSucceeded=false` 仍只能得到补验证建议，不能直接进入完成复核。

## 1. 提供任务与有界产物证据

```java
import io.agentscope.extensions.judge.jev.application.JevSupervisor;
import java.util.Map;

var task = "修复退款条件检查";
var evidence = Map.of(
    "changes", "退款前已添加订单状态检查",
    "testPlan", "覆盖已发货和未发货两条分支");
var supervisor = new JevSupervisor(judge);
var pending = supervisor.assess(task, evidence, false);
```

`judge` 使用[应用组件初始化](/v2/zh/jev/guides/application-api#初始化)。示例证据为简短说明；真实应用应提供经过授权的变更和检查结果，而不只转发 worker 的“已经完成”。最后的 false 明确表示尚无成功验证。

## 2. 按建议推进下一步

```java
var observed = pending.doOnNext(report -> {
    System.out.println("建议：" + report.advice());
    System.out.println("评审状态：" + report.assessment().status());
});
```

| 建议 | 在补丁任务中的处理 |
|---|---|
| CONTINUE | 当前证据尚不支持完成，继续实现或补材料 |
| REQUEST_VERIFICATION | 实现证据支持完成，但应先实际执行测试 |
| REVIEW_COMPLETION | 实现证据与宿主成功验证支持进入复核 |
| MANUAL_REVIEW | 偏航、判断不确定或评审出错，交由宿主复核 |

实际测试完成后，重新取得当前产物证据，再调用 `assess(task, currentEvidence, true)`。true 必须来自真实验证结果，不能根据模型的完成概率填入。

## 运行与验证

按[案例构建步骤](/v2/zh/jev/guides/agent-integration-example#构建一次运行多个案例)设置 `JEV_CP`：

```bash
java -cp "$JEV_CP" io.agentscope.examples.jev.JevApplicationScenarios task-review
```

用例给两个分支相同的预设完成与未偏航概率，仅改变模拟宿主验证标志。输出依次为 REQUEST_VERIFICATION、REVIEW_COMPLETION，证明语义完成不替代验证。它没有实际运行业务测试或修改任务状态。[完整源码](/examples/jev/source/JevApplicationScenarios.java.txt)

## 边界与持续监督

该 API 的两个内置条件为 `complete` 和 `off_track`，阈值 0.2/0.8；调用即评审，没有 OFF/SHADOW 参数，关闭时由应用绕过调用。超时或失败进入人工复核建议，不自动重试整个任务。

布尔验证标志没有版本和作用域校验。需要长期运行、并发会话和过期证据检测时，使用[持续监督](/v2/zh/jev/guides/supervision-api)与[可信证据源](/v2/zh/jev/guides/supervision-evidence)。

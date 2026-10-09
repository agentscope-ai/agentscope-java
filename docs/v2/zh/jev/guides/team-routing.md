---
title: "为显式阶段选择团队候选"
---

`JevTeamPlanner` 从已授权且可用的团队成员中推荐适合当前阶段的人选。适合规划、实现、评审职责分离的 Harness；任务创建、实际分派及团队状态由你的调度流程负责。

## 示例场景：补丁进入评审阶段，选择适合核查的人选

退款补丁已经实现，接下来需要核查测试证据。团队中有代码评审成员、文档成员，以及暂时不可用的另一名评审成员。仅按“擅长编程”选择人选，可能忽略当前任务已经进入验收阶段。

宿主先过滤不可用或未授权成员，再告诉 JEV 当前明确阶段是 `review`。JEV 评估剩余候选的职责是否适合本次验收，不自动创建子任务。

## 1. 提供成员职责与宿主过滤条件

```java
import io.agentscope.extensions.judge.jev.application.JevTeamPlanner;
import java.util.List;
import java.util.Set;

var members = List.of(
    new JevTeamPlanner.Member("reviewer", "代码评审与测试证据核查"),
    new JevTeamPlanner.Member("writer", "撰写用户文档"),
    new JevTeamPlanner.Member("offline-reviewer", "代码评审与测试证据核查"));
var availableAndAuthorized = Set.of("reviewer", "writer");
```

这里用集合模拟应用的成员目录；生产应由授权和健康检查决定集合内容。候选职责用于语义判断，不能替代目录的访问控制。

## 2. 为当前阶段生成建议

```java
var planner = new JevTeamPlanner(selector);
var pending = planner.recommend(ctx, "review",
    "检查退款补丁是否满足验收条件", members,
    member -> availableAndAuthorized.contains(member.id()));
```

`selector` 使用[候选组件配置](/v2/zh/jev/guides/application-api#初始化)。`stage` 必须显式提供；筛选后不可重复的成员 ID 作为建议结果的稳定标识。检查 `status()` 为 DECIDED 后，再读取 `value().selected()` 与 `value().uncertain()`。

在 SHADOW 下，宿主只记录“建议给 reviewer”，继续沿用原分派。即使采用建议，实际启动成员前仍应复核其授权和可用状态。

## 运行与验证

按[案例构建步骤](/v2/zh/jev/guides/agent-integration-example#构建一次运行多个案例)设置 `JEV_CP` 后执行：

```bash
java -cp "$JEV_CP" io.agentscope.examples.jev.JevApplicationScenarios team
```

预设结果推荐 `reviewer`，不考虑 `offline-reviewer`，创建任务数为 0。这个用例验证宿主过滤在先、建议不自动执行；没有评测团队最终交付质量。[JevApplicationScenarios 完整源码](/examples/jev/source/JevApplicationScenarios.java.txt)

## 与模型路由的关系

团队建议决定当前阶段适合交给哪个成员；[模型路由](/v2/zh/jev/guides/model-routing-api)决定成员的一次调用使用哪个模型。需要阶段内模型粘性时，使用[显式阶段 API](/v2/zh/jev/guides/phase-routing-api#显式阶段)。

本组件不从工具输出自动推断阶段。判断失败或结果不确定时由调度方保留原分派或请求澄清，不能把无建议记成“分派成功”。

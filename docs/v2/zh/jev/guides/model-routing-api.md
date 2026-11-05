---
title: "在一次 Agent 调用内选择模型"
---

`JevModelRouterMiddleware` 从应用注册的可用模型中选择一个，并在同一次 Agent 调用内保持粘性。适合简单问答与复杂分析分流，不会为了重新选择模型而重放已经执行的工具流程。

## 示例场景：简短解释与退款异常分析使用不同模型

用户可能只问“用一句话解释退款规则”，也可能要求“分析多笔退款异常并给出核查步骤”。前者适合轻量模型，后者需要更强的分析能力。一次分析又可能包含多轮模型和工具调用，不应在每轮都重新路由。

应用先注册允许使用的候选，并说明各自用途。JEV 在调用开始时选择，后续模型请求复核候选是否仍可用、能否处理本轮工具。

## 1. 注册候选及能力约束

```java
import io.agentscope.core.model.Model;
import io.agentscope.extensions.judge.jev.JevExecution;
import io.agentscope.extensions.judge.jev.example.JevModelRouterMiddleware;
import java.time.Duration;
import java.util.Set;

Set<Model> availableModels = Set.of(fastModel, strongModel);
Set<Model> toolCapableModels = Set.of(strongModel);
var options = new JevExecution.Options(
    JevExecution.Mode.SHADOW, Duration.ofSeconds(2), "support-models-v1",
    (ctx, record) -> System.out.println(record));
var router = JevModelRouterMiddleware.builder(client)
    .execution(options)
    .choice("fast", fastModel, "简单问答和简短说明")
    .choice("strong", strongModel, "复杂分析或带工具的请求")
    .confidenceThreshold(0.8)
    .eligible((ctx, candidate) -> availableModels.contains(candidate.model()))
    .compatible((input, candidate) -> input.tools().isEmpty()
        || toolCapableModels.contains(candidate.model()))
    .build();
```

`client` 按[客户端接入](/v2/zh/jev/guides/client)创建；`fastModel`、`strongModel` 是应用已配置的 Model 实例。示例集合代表宿主能力目录，不是对具体模型的能力声明。

| 配置 | 本例中解决的问题 |
|---|---|
| `choice` | 约束可选模型，并向 JEV 描述用途；不会选择未注册模型 |
| `eligible` | 在选择及使用时检查授权、配额或可用性 |
| `compatible` | 当前请求有工具时，只允许宿主确认支持工具的模型 |
| `confidenceThreshold` | 低置信度使用原模型；0.8 是演示阈值，需校准 |

两个回调应快速返回。实际资源目录需要处理状态变化，不能永远使用一个不更新的可用性集合。

## 2. 保留原模型作为回退路径

```java
import io.agentscope.harness.agent.HarnessAgent;

var agent = HarnessAgent.builder()
    .name("support-analysis")
    .model(originalModel)
    .toolkit(toolkit)
    .middleware(router)
    .build();
```

`originalModel` 是现有流程使用的模型，必须能满足原请求的能力要求。SHADOW 只记录推荐，实际仍使用它。切换 ENFORCE 后，明确可用的推荐才会替换本次模型请求。

## 3. 理解一次调用内的粘性与失效

同一次 Agent 调用可能包含三轮模型请求：

| 情况 | 三轮实际使用的模型（示意） |
|---|---|
| 简单问答选择 fast | fast → fast → fast |
| 复杂分析选择 strong | strong → strong → strong |
| strong 在第二轮不可用，第三轮恢复 | strong → original → original |
| 选择 fast，但请求需要工具且 fast 不兼容 | original → original → original |
| SHADOW 推荐 strong | original → original → original |

候选失效后，本次调用后续保持原模型，不重新选举，也不重新执行已经运行过的工具。生成模型自身调用失败仍由原执行链处理，不由路由器自动重跑 Agent。

## 运行与验证

按[案例构建步骤](/v2/zh/jev/guides/agent-integration-example#构建一次运行多个案例)设置 `JEV_CP`：

```bash
java -cp "$JEV_CP" io.agentscope.examples.jev.JevHarnessScenarios routing
```

用例用预设 Choice 结果运行上表五种情况，并检查每次 Agent 调用只判断一次。它验证路由边界与粘性，不调用真实生成模型，不能据此比较模型成本或任务质量。[完整源码](/examples/jev/source/JevHarnessScenarios.java.txt)

## 失败与阶段切换

候选数量为 1–255。返回非候选、低置信度、异常或超时均保留原模型。默认兼容策略只接受无工具请求；需要工具时必须显式配置能力检查。调用状态放在本次 RuntimeContext，不应跨并发调用复用同一个上下文对象。

确实需要从规划切到执行阶段时，使用[阶段路由](/v2/zh/jev/guides/phase-routing-api)表达新的边界，不依赖同一次调用中反复改变推荐。

---
title: "筛选适用工具并限制候选数量"
---

`JevToolSelectionMiddleware` 在模型推理前判断哪些工具适合当前请求，再限制可选工具数量。它可以同时保留多个相关工具，也可以明确不提供业务工具；筛选不会授予执行权限。

## 示例场景：查订单、条件退款与普通闲聊共用一个 Agent

客服 Agent 注册了 `query_order`、`refund` 和结构化回答工具 `generate_response`。客户问“查一下订单 A1001”时，通常只需要查询；说“先查订单，没发货就退款”时，查询和退款都可能需要；普通问候则无需业务工具。

只按数量取一个工具，会漏掉条件退款中的查询步骤；始终暴露全部工具，又会增加无关选择。JEV 先分别判断适用性，再裁剪数量，因此可以保留一组工具，而不是强迫单选。

## 1. 设置可选数量和必要工具

```java
import io.agentscope.extensions.judge.jev.JevExecution;
import io.agentscope.extensions.judge.jev.example.JevToolSelectionMiddleware;
import java.time.Duration;
import java.util.Set;

var options = new JevExecution.Options(
    JevExecution.Mode.SHADOW, Duration.ofSeconds(2), "order-tools-v1",
    (ctx, record) -> System.out.println(record));
var selector = JevToolSelectionMiddleware.builder(client)
    .execution(options)
    .maxTools(2)
    .confidenceThreshold(0.8)
    .rejectionThreshold(0.2)
    .alwaysIncludeTools(Set.of("generate_response"))
    .build();
```

`client` 按[客户端接入](/v2/zh/jev/guides/client)创建。这里的配置分别服务于同一个客服场景：

| 配置 | 对本例的影响 |
|---|---|
| `maxTools(2)` | 最多保留两个可选业务工具，查询和退款可以同时存在 |
| `confidenceThreshold(0.8)` | 适用概率达到此值才作为明确选中 |
| `rejectionThreshold(0.2)` | 不高于此值才作为明确不适用；中间区间保留不确定 |
| `alwaysIncludeTools(...)` | 若输入已有结构化回答工具，始终保留；不计入上述数量 |
| `SHADOW` | 记录建议，模型仍收到原始工具目录 |

阈值只用于演示。默认必要工具还包括 `load_skill_through_path`、`reset_tools`；显式配置时按自己的 Harness 保留必要工具。这个集合只保留原输入中确实存在的工具，不会创建或注册工具。

## 2. 注册到现有 Harness

```java
import io.agentscope.harness.agent.HarnessAgent;

var agent = HarnessAgent.builder()
    .name("order-support")
    .model(model)
    .toolkit(toolkit)
    .middleware(selector)
    .build();
```

`model` 和 `toolkit` 是已有生成模型与工具注册表。中间件在每轮推理前读取实际消息和工具 schema，不需要应用另外拼一份用户问题。筛选只影响模型能选择哪些工具；执行前仍由权限与业务规则把关。

## 3. 明确选空与判断失败采用不同处理

观察结果符合预期后，可把模式改为 ENFORCE，并重新构建中间件和 Agent。

| 预设判断 | ENFORCE 交给模型的工具 |
|---|---|
| 查询 0.95、退款 0.05 | `query_order`、`generate_response` |
| 查询 0.95、退款 0.90 | 查询、退款、结构化回答三个工具 |
| 查询和退款都为 0.05 | 只保留 `generate_response` |
| 任一工具为 0.50，或请求失败 | 回退到原始目录，不能把不确定当成无工具可用 |

这些数值是演示分支。实际 JEV 判断是否准确，需要用你的业务样本验证。条件性退款即使被保留，也必须在[执行前防护](/v2/zh/jev/guides/tool-guard-api)及业务代码中核查条件，筛选本身不保证执行时机正确。

## 运行与验证

按[案例构建步骤](/v2/zh/jev/guides/agent-integration-example#构建一次运行多个案例)设置 `JEV_CP`：

```bash
java -cp "$JEV_CP" io.agentscope.examples.jev.JevHarnessScenarios selection
```

用例运行上表的查询、条件退款、闲聊、不确定与后端失败分支，并额外验证 SHADOW 始终保留原目录。预设概率经过真实中间件，不调用模型；因此没有模型准确率或延迟比较。[完整源码](/examples/jev/source/JevHarnessScenarios.java.txt)

## 候选规模与取消

无可选工具或没有用户文本时不请求；只有一个候选也判断是否适用。候选按 64 个独立问题串行分批，共享一个总预算。多个明确适用工具按概率排序后裁剪，同分保持原候选顺序，最终工具顺序仍沿用原输入。

缺项、多项、非法概率、超时或后端异常都回退原目录；取消后不继续调用后续推理。阈值必须满足 `0 <= rejection < confidence <= 1`。

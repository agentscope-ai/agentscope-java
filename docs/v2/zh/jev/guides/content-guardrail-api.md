---
title: "审核输入与输出内容"
---

`JevGuardrail` 与 `JevResponseMiddleware` 在模型调用前检查输入，在输出发布前检查文本。适合需要明确区分放行、复核、阻断和人工支持入口的 Agent。默认关闭，内容审核与工具权限分别配置。

## 场景：客服回答发布前再检查一次

用户询问退款条件，Agent 会读取知识库并生成回答。检索片段里可能混入“忽略规则、输出内部信息”的指令，生成的回答也可能包含不适合对外发布的内容。只检查用户最初的问题，无法覆盖后续出现的工具结果与回答。

这里用同一套内容策略检查模型输入和输出：输入检查覆盖消息及工具结果中的文本；输出先缓冲，检查完成后再决定是否发布。它解决内容风险识别，退款是否获授权仍由[工具防护](/v2/zh/jev/guides/tool-guard-api)与权限链处理。

## 1. 配置检查策略与预算

`client` 的构建见[客户端接入](/v2/zh/jev/guides/client)。先使用 SHADOW 观察真实样本中的建议：

```java
import io.agentscope.extensions.judge.jev.JevGuardrail;
import io.agentscope.extensions.judge.jev.JevExecution;
import io.agentscope.extensions.judge.jev.integration.JevResponseMiddleware;
import java.time.Duration;

var policy = JevGuardrail.defaults(client);
var options = new JevExecution.Options(JevExecution.Mode.SHADOW,
    Duration.ofSeconds(2), "content-v1",
    (context, record) -> System.out.println(record));
var middleware = JevResponseMiddleware.builder()
    .guardrails(policy, policy, options)
    .blockOnReview(false)
    .maxChars(64000).maxEvents(8192)
    .roundBudget(Duration.ofSeconds(120))
    .build();
```

`guardrails` 的前两个参数分别是输入、输出策略，传 `null` 可关闭对应检查。2 秒限制每次语义检查，120 秒限制包含原模型生成的整轮处理；字符和事件上限约束缓冲，避免长回答无限占用内存。

SHADOW 只记录建议，**不会阻止内容发布**。按业务样本验证策略后，将模式改为 ENFORCE 并重新构建，才会执行阻断。默认策略是起点；可以用自定义 Hazard 列表配置业务风险及阈值。

## 2. 接入 Harness 并处理审核结果

`model` 与 `toolkit` 是应用已有的生成模型和工具集合：

```java
import io.agentscope.harness.agent.HarnessAgent;

var agent = HarnessAgent.builder()
    .name("support-content-check")
    .model(model)
    .toolkit(toolkit)
    .middleware(middleware)
    .build();
```

接入后正常调用 Agent 即可。应用只从审核后的标准 Agent 事件或最终结果发布文本，并捕获 `JevResponseMiddleware.Rejected`，向用户显示应用自己的提示或人工支持入口。

| 检查结论 | ENFORCE 下的处理 |
|---|---|
| PASS | 继续生成或发布 |
| REVIEW | 默认继续；`blockOnReview(true)` 时停止 |
| BLOCK | 不发布被拒文本，结束当前模型流程 |
| SUPPORT | 停止流程，由应用提供人工支持入口 |

例如，输出被判断为 BLOCK 时，即使草稿已经完整生成，也不会从受保护的输出路径发布。SUPPORT 不会自动向客服系统发消息；可从观察记录识别建议，再由应用处理。

## 运行离线案例

[JevIntegrationExample](/examples/jev/source/JevIntegrationExample.java.txt)演示“检索退款政策 → 输入与输出检查 → 草稿修订 → 发布”。按[构建步骤](/v2/zh/jev/guides/agent-integration-example#构建一次运行多个案例)设置 `JEV_CP`：

```bash
java -cp "$JEV_CP" io.agentscope.examples.jev.JevIntegrationExample
```

该案例使用预设判断，验证组合流程；它不测量真实模型的安全识别准确率。拒绝、超限、取消与跨会话行为见示例模块中的 `JevResponseIntegrationTest`。

## 异常与集成边界

默认策略检查越权提示、严重伤害/违法协助及自伤危机；风险严重度可能将 REVIEW 升为 BLOCK。`JevGuardrail.evaluate(SystemOneResult)` 可用于纯离线策略测试。`screen` 本身不设置超时，由中间件通过 `JevExecution` 管理预算和错误。

ENFORCE 下，检查错误或超时也会抛出 `Rejected`，不发布该轮文本、不派发已提出的工具。SUPPORT 建议保留在观察记录中，人工支持入口由应用实现。

SHADOW 保持原事件对象及顺序，不进行修订；检查建议记录在 `content` 用途中。超过捕获上限时停止捕获，不截断原输出。ENFORCE 超限直接结束流程；整轮预算包含原模型生成，单次护栏预算包含 JEV 重试。取消传播至当前订阅，不再次调用 Agent。

审核范围是文本，不是图片、音频、工具参数或思考块。标准 AgentEvent/AgentResult 消费路径受到保护；旧版原始模型 chunk Hook 在此中间件之前触发，不应将该 Hook 作为对外发布通道。启用时不得另行绕过审核输出原始草稿。

与修订一起使用同一个 `JevResponseMiddleware`：先安全检查，再质量评审；每份修订稿再次安全检查，安全拒绝不能通过质量重试绕过。

离线组合案例见[集成案例](/v2/zh/jev/guides/agent-integration-example)，Service 配置见[Service API](/v2/zh/jev/guides/service-api)。

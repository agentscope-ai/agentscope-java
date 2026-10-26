---
title: OpenAI Decisions
description: 通过独立的 judge 扩展，对文本和图片进行类型化决策。
en_link: /v2/en/integration/judge/openai-decisions
---

OpenAI Decisions 对共享输入进行判断，返回概率、固定选项或基于量表的评分。扩展使用 OpenAI 官方 Java SDK，位于 `agentscope-extensions-judge` 下，与 JEV 同级，通过独立于聊天模型 API 的 `OpenAIDecisionsClient` 使用。应用可以根据结果分类请求或决定下一步动作。

[官方指南](https://developers.openai.com/api/docs/guides/decisions)将该 API 标记为公开测试阶段。[API reference](https://developers.openai.com/api/reference/resources/decisions/methods/create)列出了 `gpt-6-luna`，客户端以它作为默认模型。能否访问服务取决于你的 OpenAI 账号。

## 依赖和首次决策

此功能面向 `2.1.0-SNAPSHOT`，需要使用包含该扩展的构建。使用 AgentScope BOM 时可以省略依赖版本。

```xml
<dependency>
    <groupId>io.agentscope</groupId>
    <artifactId>agentscope-extensions-openai-decisions</artifactId>
    <version>2.1.0-SNAPSHOT</version>
</dependency>
```

运行前设置 `OPENAI_API_KEY`。builder 显式设置的密钥优先于环境变量。执行以下示例会将文本和问题发送给 OpenAI，可能产生费用。

```java
import io.agentscope.extensions.judge.openaidecisions.DecisionAnswer;
import io.agentscope.extensions.judge.openaidecisions.DecisionQuestion;
import io.agentscope.extensions.judge.openaidecisions.DecisionRequest;
import io.agentscope.extensions.judge.openaidecisions.DecisionResult;
import io.agentscope.extensions.judge.openaidecisions.OpenAIDecisionsClient;
import java.util.List;

OpenAIDecisionsClient client = OpenAIDecisionsClient.builder()
        .apiKey(System.getenv("OPENAI_API_KEY"))
        .build();
DecisionRequest request = DecisionRequest.builder()
        .input("I was charged twice for my order.")
        .question("department", new DecisionQuestion.Choice(
                "Which team should handle this?",
                List.of(new DecisionQuestion.Option("billing", "Payments and refunds"),
                        new DecisionQuestion.Option("technical", "Product issues"))))
        .build();
DecisionResult result = client.decideBlocking(request);
DecisionAnswer answer = result.answers().get("department");
if (answer instanceof DecisionAnswer.Choice choice) {
    System.out.println(choice.choice());
    System.out.println(choice.confidence());
    System.out.println(choice.probabilities());
} else if (answer instanceof DecisionAnswer.Refusal) {
    System.out.println("Send to manual review");
}
```

`DecisionRequest` 包含 1–400 个名称唯一的问题。请求中的可选模型会覆盖客户端模型。构造时会复制输入和问题集合，因此请求可以安全复用。可选的 `promptCacheKey()` 用于划分缓存复用；本扩展不暴露 `prompt_cache_options` 或显式缓存断点，OpenAI 会使用默认隐式缓存。`decide()` 返回冷 `Mono`，每次订阅都会发送请求，适合响应式应用；`decideBlocking()` 则供非响应式线程使用。

## 问题和答案

所有公开类型均位于 `io.agentscope.extensions.judge.openaidecisions` 包。

| 问题 | 定义 | 成功答案 |
| --- | --- | --- |
| `DecisionQuestion.Predicate` | 描述一个条件的指令 | `DecisionAnswer.Predicate.probability()`，范围 [0, 1] |
| `DecisionQuestion.Choice` | 指令和 2–255 个不同的 `Option` 值 | `DecisionAnswer.Choice`，包含选中值、置信度和各选项概率 |
| `DecisionQuestion.Score` | 指令和非空、有序的 `Level` 列表 | `DecisionAnswer.Score`，包含从零开始的等级索引加权平均值、置信度和各等级概率 |

选项值可以是字符串或布尔值，`"true"` 和 `true` 是不同选项。选项和等级的描述均可省略。置信度与选中选项的概率分别保留，不能直接视为业务正确率保证；应使用带标签样本确定阈值。

任何问题都可能返回 `DecisionAnswer.Refusal`，执行后续动作前应显式检查该类型。同一个 `DecisionResult` 中仍会保留其他有效答案和 usage。拒绝不会被转换成零概率或猜测的选项。

客户端按名称匹配答案，按值或索引匹配分布项，不依赖响应顺序。缺失、重复、未知或类型不匹配的答案都会被拒绝。每个选项或等级必须且只能出现一次；数值字段必须存在且有限；概率和置信度必须位于 [0, 1]；概率总和与 1 的误差不得超过 `1e-6 × 选项数`。选中值必须属于提供的选项，评分必须在相同容差内符合等级索引的加权平均值。客户端保留原值，不做归一化。非法响应会使整个 `Mono` 以 `OpenAIDecisionsException` 失败。

`DecisionResult.usage()` 保留提供商返回的输入、输出和总 token 数，并将提供商的 token 详情字段平铺为 `cachedTokens`、`cacheWriteTokens` 和 `reasoningTokens`。缺失 usage 为 `null`，缺失的单个计数和详情字段也保持 `null`，真实的零仍为零。失败调用不暴露 usage。

## 图片和混合输入

纯图片输入使用 `DecisionInput.image(url)`；文本和图片混合时使用 `DecisionInput(text, imageUrls)`，最多 128 张图片，编码为一条用户消息。下面的 `pngBytes` 表示由应用加载的 PNG 数据。

```java
import io.agentscope.extensions.judge.openaidecisions.DecisionInput;
import java.util.Base64;

String dataUrl = "data:image/png;base64," + Base64.getEncoder().encodeToString(pngBytes);
DecisionRequest imageRequest = DecisionRequest.builder()
        .input(new DecisionInput("Inspect the product.", List.of(dataUrl)))
        .question("damaged", new DecisionQuestion.Predicate("Is there visible damage?"))
        .build();
client.decide(imageRequest).subscribe(imageResult -> System.out.println(imageResult.answers()));
```

客户端按 API reference 和官方指南接受图片 data URL 以及可公开访问的 HTTP(S) 图片 URL。Java 客户端只将这些值发送给提供商，不在本地获取图片；仓库测试不会验证真实服务兼容性。请求 API 提供文本和单条用户消息中的文本/图片输入。下文中间件示例将对话文本和工具调用证据整理成文本输入，不增加原生工具调用或提示缓存选项。

## 配置和失败处理

默认 API base 为 `https://api.openai.com/v1`。`baseUrl()` 接受带版本号的 API base（可包含网关路径前缀），或完整的 `/decisions` 地址。base URL 不允许包含用户凭据、查询参数和片段。自定义地址时应保留 API 版本路径。

默认超时为 30 秒，必须为正值。阻塞 SDK 调用运行在 Reactor 的 `boundedElastic` 调度器上，超时也会传递给 SDK。超时和取消会向执行任务发送中断，正在进行的网络请求何时停止取决于 SDK transport。通过封装层创建的客户端需要调用 `close()` 或使用 try-with-resources 释放 SDK 连接。Spring 会在上下文关闭时关闭自动配置的客户端。

HTTP 错误可通过 `OpenAIDecisionsException.getStatusCode()` 获取状态码。HTTP 429、5xx、客户端超时、SDK I/O 错误及 SDK 明确标记为可重试的错误携带重试提示。非法响应和永久性 HTTP 错误不重试。客户端生成的异常不会附带提供商原始响应体或底层 SDK 的原始异常链。

封装层重试默认关闭，保持每次订阅只尝试一次。可以显式启用与 JEV 相同的重试次数和初始退避：

```java
import io.agentscope.extensions.judge.openaidecisions.OpenAIDecisionsRetryPolicy;

OpenAIDecisionsClient retryingClient = OpenAIDecisionsClient.builder()
        .retryPolicy(OpenAIDecisionsRetryPolicy.defaults())
        .build();
```

`defaults()` 允许额外重试两次，初始退避为 500ms，使用带抖动的指数退避。通过 `new OpenAIDecisionsRetryPolicy(maxRetries, initialBackoff)` 自定义限制；`disabled()` 不允许重试。超时分别作用于每次尝试，总耗时还包括重试及退避。重试耗尽后保留最后一次脱敏异常。拒答仍是正常返回的结果，不触发重试。

封装层会关闭自行创建的 SDK 客户端的重试。`builder().client(openAIClient)` 借用已有 SDK 客户端，其凭据、base URL、重试配置和生命周期由拥有者管理。启用封装层重试前应关闭借用客户端自身的重试，避免调用次数相乘。取消会停止响应式重试序列，正在进行的网络请求何时停止仍取决于 transport。

## Agent 中间件示例

`io.agentscope.extensions.judge.openaidecisions.example` 提供三个需要显式启用的中间件，覆盖 JEV 的模型路由、工具筛选和自动执行判断场景，不依赖 JEV 扩展。默认均关闭，需配置 `.enabled(true)`。它们是应用接入示例，不会自动注册为 Spring bean。

| 中间件 | 启用后的行为 | 默认回退策略 |
| --- | --- | --- |
| `OpenAIDecisionsModelRouterMiddleware` | 每次 Agent 调用做一次单选决策，通过 `RuntimeContext` 保存路由并在 `onModelCall` 应用 | 拒答、置信度低于 0.8 或请求失败时保留原模型 |
| `OpenAIDecisionsToolSelectionMiddleware` | 每轮推理用独立 predicate 判断可选工具相关性，最多选择三个概率不低于 0.8 的工具 | 拒答、请求失败，或任一概率严格位于 0.2 与 0.8 之间时保留原工具集 |
| `OpenAIDecisionsAutoModeMiddleware` | 派发前检查配置的受保护工具调用，要求 P(safe) 至少为 0.8 | 拒答、缺少上下文或请求失败时拒绝受保护调用 |

路由器接受 2–255 个命名模型候选。默认在请求包含工具时保留原模型，并在包含 response format 时检查候选的原生结构化输出能力。只有确认候选支持当前工具、输入模态和输出要求后，才应通过 `.compatible((input, choice) -> ...)` 放开对应请求。路由和筛选默认 `failOpen(true)`，设为 `false` 时传播请求失败。工具执行判断显式配置 `failOpen(true)` 后会放行请求失败和缺少上下文的情况，但拒答始终不会转为执行许可。

筛选器保留 `load_skill_through_path`、`reset_tools` 和 `generate_response`，这些工具不占可选工具名额。可通过 `alwaysIncludeTools(Set.of(...))` 自定义。当前请求的 `ToolChoice.Specific` 指定的工具若存在于输入目录中，也会保留且不占名额；筛选器不对其评分，也不会创建该工具。确定无关时可以移除全部可选工具。筛选默认仅提交最近八条非空白文本消息，总文本最多 8,000 字符，可用 `maxContextMessages` 和 `maxContextChars` 调整。工具结果块中的文本也纳入相同的消息数和字符预算。工具执行判断采用相同的固定窗口，另将实际工具名和参数作为 JSON 证据提交。这些示例不会转发对话中的图片。筛选和执行判断超过 400 题时会顺序分批请求。

```java
import io.agentscope.extensions.judge.openaidecisions.example.OpenAIDecisionsModelRouterMiddleware;
import io.agentscope.extensions.judge.openaidecisions.example.OpenAIDecisionsToolSelectionMiddleware;
import io.agentscope.extensions.judge.openaidecisions.example.OpenAIDecisionsAutoModeMiddleware;

var router = OpenAIDecisionsModelRouterMiddleware.builder(client)
        .enabled(true)
        .choice("fast", fastModel, "简单查询")
        .choice("capable", capableModel, "复杂分析")
        .build();
var selector = OpenAIDecisionsToolSelectionMiddleware.builder(client)
        .enabled(true).maxTools(3).build();
var guard = OpenAIDecisionsAutoModeMiddleware.builder(client)
        .enabled(true).guardedTool("execute").guardedTool("write_file").build();
// 通过 ReActAgent.builder().middleware(router).middleware(selector).middleware(guard) 添加。
```

执行判断跳过权限流程已标为 `ALLOWED` 的调用。通过模型判断并不授予核心权限：放行及非保护调用仍进入正常执行管线。被拒绝的调用不会进入后续派发器，而会生成 `DENIED` 工具结果消息及 start/text/end 事件，让模型依据结果继续处理。评估过程中取消不会派发工具，也不会生成拒绝消息。

这些示例没有实现 JEV 更广的执行模式、确认记录、分阶段路由、RAG、上下文压缩和评测扩展。测试使用本地 fixture 和桩决策，不代表真实提供商效果，也不验证生产安全阈值。

### 可运行场景

`agentscope-examples/openai-decisions` 模块提供 `io.agentscope.examples.decisions.OpenAIDecisionsMiddlewareExample`，可独立运行 `route`（模型路由）、`select`（工具筛选）和 `guard`（执行判断）三个场景。程序打印 Decisions 答案、实际调用的模型和工具列表，以及工具结果状态。业务工具全部使用模拟实现，删除文件也不会改变文件系统；运行 main 会真实调用 Decisions 和 Chat Completions API。

在仓库根目录运行：

```shell
mvn -B -T1C -pl agentscope-dependencies-bom,agentscope-distribution/agentscope-bom,agentscope-examples/openai-decisions -am install -DskipTests
mvn -B -T1C -pl agentscope-examples/openai-decisions org.codehaus.mojo:exec-maven-plugin:3.5.0:java "-Dexec.mainClass=io.agentscope.examples.decisions.OpenAIDecisionsMiddlewareExample" "-Dexec.args=route"
```

设置 `OPENAI_API_KEY`、`OPENAI_MODEL`（支持工具调用的 Chat Completions 模型），以及 `OPENAI_FAST_MODEL`（仅 `route` 必需）。模型名不带 `openai:` 前缀。将 `route` 替换为 `select` 或 `guard` 即可切换场景，也可以通过 `"-Dexec.args=route Design a distributed task scheduler"` 追加自定义提示词。模块 README 包含环境变量设置和离线验证命令。真实决策结果及聊天模型是否调用工具取决于模型，示例提示词不保证特定路由或拒绝结果。

当任一聊天模型为 `gpt-6-luna` 时，示例会为该模型设置 `reasoning_effort=none`，以满足 Chat Completions 工具调用的要求。此配置仅用于聊天请求，Decisions 和其他聊天模型保留默认配置。

如果连接超时，请按示例模块 README 配置 JVM 的 HTTP/HTTPS 代理属性，让两个客户端都使用代理。main 方法会在回退前输出脱敏的 Decisions 失败原因，并在退出时关闭 Reactor 共享调度器；此关闭操作仅适用于独立运行的示例。

## Spring Boot

使用 starter 依赖替代直接引入扩展，然后注入 `OpenAIDecisionsClient`。

```xml
<dependency>
    <groupId>io.agentscope</groupId>
    <artifactId>agentscope-openai-decisions-spring-boot-starter</artifactId>
    <version>2.1.0-SNAPSHOT</version>
</dependency>
```

```yaml
agentscope:
  openai-decisions:
    api-key: ${OPENAI_API_KEY}
    model: gpt-6-luna
    base-url: https://api.openai.com/v1
    timeout: 30s
    retry:
      max-retries: 0
      initial-backoff: 500ms
```

starter 使用上述默认值。设置 `agentscope.openai-decisions.enabled=false` 可禁用客户端创建，用户自定义的客户端 bean 优先。可以实现 `OpenAIDecisionsClientBuilderCustomizer` 传入官方 SDK 客户端或覆盖属性，Spring 在绑定属性之后按顺序应用 customizer。

设置 `retry.max-retries=2` 可启用封装层重试。次数必须非负，退避必须为正值。这些属性配置客户端，不启用中间件；需要在每个 Agent 上显式注册所需中间件。

完整的三问题示例和图片输入辅助方法位于 `agentscope-examples/documentation/src/main/java/io/agentscope/examples/documentation2/judge/OpenAIDecisionsExample.java`。

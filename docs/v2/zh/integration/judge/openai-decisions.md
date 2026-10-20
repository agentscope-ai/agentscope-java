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

客户端按 API reference 和官方指南接受图片 data URL 以及可公开访问的 HTTP(S) 图片 URL。Java 客户端只将这些值发送给提供商，不在本地获取图片；仓库测试不会验证真实服务兼容性。当前扩展提供文本和单条用户消息中的文本/图片输入，尚未暴露会话历史、工具调用输入或提示缓存选项。

## 配置和失败处理

默认 API base 为 `https://api.openai.com/v1`。`baseUrl()` 接受带版本号的 API base（可包含网关路径前缀），或完整的 `/decisions` 地址。base URL 不允许包含用户凭据、查询参数和片段。自定义地址时应保留 API 版本路径。

默认超时为 30 秒，必须为正值。阻塞 SDK 调用运行在 Reactor 的 `boundedElastic` 调度器上，超时也会传递给 SDK。超时和取消会向执行任务发送中断，正在进行的网络请求何时停止取决于 SDK transport。通过封装层创建的客户端需要调用 `close()` 或使用 try-with-resources 释放 SDK 连接。Spring 会在上下文关闭时关闭自动配置的客户端。

HTTP 错误可通过 `OpenAIDecisionsException.getStatusCode()` 获取状态码。HTTP 429、5xx 和客户端超时会携带重试提示，`isRetryable()` 本身不会触发重试。SDK I/O 错误及 SDK 明确标记为可重试的错误也会保留重试提示。封装层会关闭自行创建的 SDK 客户端的自动重试。`builder().client(openAIClient)` 可以借用已有的官方 SDK 客户端，其凭据、base URL、重试配置和生命周期由拥有者管理；封装层仍应用自身的模型和请求超时。客户端生成的异常不会附带提供商原始响应体或底层 SDK 的原始异常链。

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
```

starter 使用上述默认值。设置 `agentscope.openai-decisions.enabled=false` 可禁用客户端创建，用户自定义的客户端 bean 优先。可以实现 `OpenAIDecisionsClientBuilderCustomizer` 传入官方 SDK 客户端或覆盖属性，Spring 在绑定属性之后按顺序应用 customizer。

完整的三问题示例和图片输入辅助方法位于 `agentscope-examples/documentation/src/main/java/io/agentscope/examples/documentation2/judge/OpenAIDecisionsExample.java`。

---
title: Jev
---

`agentscope-extensions-jev` 模块位于 `agentscope-extensions-judge` 父模块下，为 [TypeSafe System One](https://docs.typesafe.ai/) 和 Jev 提供 Java HTTP client。Jev 不是聊天模型，也不会注册成 AgentScope 的 `Model` provider；它适合在应用代码里做路由、评分、分类这类需要快速、类型安全、带置信度判断的决策。

## 何时使用

- 想把非结构化输入变成 `Noul`、`Choice` 或 `Score` 类型结果。
- 需要校准概率和置信度，而不是生成一段文本。
- 想在调用更大的推理模型前做一次低成本预判。

## 添加依赖

```xml
<dependency>
    <groupId>io.agentscope</groupId>
    <artifactId>agentscope-extensions-jev</artifactId>
    <version>${agentscope.version}</version>
</dependency>
```

## 快速上手

```java
import io.agentscope.extensions.judge.jev.ChoiceQuestion;
import io.agentscope.extensions.judge.jev.JevClient;
import io.agentscope.extensions.judge.jev.JevRetryPolicy;
import io.agentscope.extensions.judge.jev.SystemOneRequest;
import io.agentscope.extensions.judge.jev.SystemOneResult;
import io.agentscope.extensions.judge.jev.ChoiceAnswer;
import java.time.Duration;
import java.util.Map;

JevClient client =
        JevClient.builder()
                .apiKey(System.getenv("TYPESAFE_API_KEY"))
                .baseUrl("https://api.typesafe.ai")
                .model("jev-latest")
                .retryPolicy(new JevRetryPolicy(2, Duration.ofMillis(500)))
                .build();

SystemOneRequest request =
        SystemOneRequest.builder()
                .state("My payouts have been failing for 3 days.")
                .question(
                        "team",
                        new ChoiceQuestion(
                                "Which team should handle this?",
                                Map.of(
                                        "billing", "Payments and refunds",
                                        "technical", "Bugs and integrations")))
                .build();

SystemOneResult result = client.systemOneBlocking(request);

ChoiceAnswer answer = (ChoiceAnswer) result.answers().get("team");
if (answer.confidence() < 0.75) {
    // route to human review
} else {
    // route to answer.choice()
}
```

## 支持的问题类型

| 类型 | 结果 |
| --- | --- |
| `NoulQuestion` | 一个 yes/no 陈述为真的概率 |
| `ChoiceQuestion` | 选中的选项、每个选项的概率和置信度 |
| `ScoreQuestion` | 概率加权分数、等级说明、每个等级的概率和置信度 |

## Spring Boot starter

```xml
<dependency>
    <groupId>io.agentscope</groupId>
    <artifactId>agentscope-jev-spring-boot-starter</artifactId>
    <version>${agentscope.version}</version>
</dependency>
```

```yaml
agentscope:
  jev:
    api-key: ${TYPESAFE_API_KEY:}
    base-url: https://api.typesafe.ai
    model: jev-latest
    timeout: 5s
    retry:
      max-retries: 2
      initial-backoff: 500ms
```

`agentscope.jev.api-key` 可以不配置。未设置时，client 会依次读取
`TYPESAFE_API_KEY` 和 `JEV_API_KEY` 环境变量。

如果需要高级配置，可以定义 `JevClientBuilderCustomizer` bean。

## Client 行为

- 调用 `POST /v1/systemone`。
- 默认使用 `https://api.typesafe.ai` 和 `jev-latest`。
- 未显式设置 API key 时读取 `TYPESAFE_API_KEY`；`JEV_API_KEY` 仍作为兜底。
- 使用 AgentScope 共享的 `HttpTransport`。
- 默认每次请求 5 秒超时，可通过 `timeout(Duration)` 配置。
- 对 HTTP `429`、`529`、`5xx` 响应按指数退避重试。
- 校验 answer key、answer 类型、概率和 score legend 是否与请求匹配。
- 对不可重试的客户端错误、重试耗尽和非法响应抛出 `JevException`。

## 技能建议

`JevSkillSuggestionMiddleware` 会让 Jev 对可见技能排序，并在 system prompt 末尾追加一个
`<skill_relevance>` 提示块。它不会替代 `DynamicSkillMiddleware`，也不会从技能列表里删除技能。

```java
JevSkillSuggestionMiddleware skillSuggestion =
        JevSkillSuggestionMiddleware.builder(client)
                .repositories(skillRepositories)
                .skillFilter(skillFilter)
                .maxSuggestions(3)
                .confidenceThreshold(0.5)
                .failOpen(true)
                .build();

ReActAgent agent =
        ReActAgent.builder()
                .name("assistant")
                .model(model)
                .skillRepositories(skillRepositories)
                .middleware(skillSuggestion)
                .build();
```

行为：

- 在 `onAgent` 阶段每个 agent 调用执行一次。
- 在 `onSystemPrompt` 阶段追加建议。
- 接受与 agent 相同的 `SkillFilter`，包括 runtime overlay。
- 使用合成选项 `__none__`；只有概率高于它的技能才会被建议。
- 超过 254 个技能时先分块，再对每块胜者重排。
- `failOpen(true)` 时，Jev 失败则不追加建议。

## 工具选择

`JevToolSelectionMiddleware` 会减少发送给主模型的 tool schema 数量，同时保留核心工具，
并用 Jev 对可选工具排序。

```java
JevToolSelectionMiddleware toolSelection =
        JevToolSelectionMiddleware.builder(client)
                .alwaysIncludeTools(Set.of("load_skill_through_path", "reset_tools"))
                .maxTools(3)
                .confidenceThreshold(0.5)
                .failOpen(true)
                .build();

ReActAgent agent =
        ReActAgent.builder()
                .name("assistant")
                .model(model)
                .toolkit(toolkit)
                .middleware(toolSelection)
                .build();
```

行为：

- 在 `onReasoning` 阶段、模型调用前执行。
- 默认保留 `load_skill_through_path`、`reset_tools` 和 `generate_response`。
- 保留概率高于合成选项 `__none__` 的可选工具，最多保留 `maxTools` 个。
- 每个 reasoning step 都会重新选择，并把完整 `input.messages()` 状态发给 Jev。
- 新技能激活导致可见工具集合变化时，会再次调用 Jev。
- 超过 254 个工具时先分块，再对每块胜者重排。
- `failOpen(true)` 时，Jev 失败则保留原始工具列表。

## 不是 ChatModel provider

这个扩展有意不实现 `Model`、`ChatModelBase` 或 `ModelProvider`。Jev 不生成聊天文本，也不做常规 tool call；如果接入 `ModelRegistry`，会误导使用者把它当成普通对话模型。它更适合由应用代码、中间件、路由逻辑或 workflow 决策节点直接调用。

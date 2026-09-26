---
title: Anthropic
en_link: /v2/en/integration/model/anthropic
---

`agentscope-extensions-model-anthropic` 接入 Anthropic Claude Model，并提供 Anthropic 专属 formatter 和请求 DTO 支持。

## 添加依赖

```xml
<dependency>
    <groupId>io.agentscope</groupId>
    <artifactId>agentscope-extensions-model-anthropic</artifactId>
    <version>${agentscope.version}</version>
</dependency>
```

## ModelRegistry

设置 `ANTHROPIC_API_KEY` 后，使用 `anthropic:<model>` 字符串 id：

```java
ReActAgent agent = ReActAgent.builder()
    .name("assistant")
    .model("anthropic:claude-sonnet-4.5") // 底层由 ModelRegistry.resolve(modelId) 解析
    .build();
```

## 显式 builder

需要自定义 endpoint、formatter、transport、prompt caching、thinking 或生成参数时，使用 builder：

```java
import io.agentscope.core.model.GenerateOptions;
import io.agentscope.extensions.model.anthropic.AnthropicChatModel;
import java.util.Map;

AnthropicChatModel model = AnthropicChatModel.builder()
    .apiKey(System.getenv("ANTHROPIC_API_KEY"))
    .modelName("claude-sonnet-4-6")
    .stream(true)
    .defaultOptions(GenerateOptions.builder()
        .maxTokens(4096)
        .additionalBodyParam("thinking", Map.of("type", "adaptive"))
        .build())
    .build();
```

对于支持 adaptive thinking 的当前 Claude 模型，推荐使用 adaptive 模式。对于要求 manual
extended thinking 的旧模型，可以改为设置 `thinkingBudget(2048)`；AgentScope 会将其映射为
`thinking: {"type": "enabled", "budget_tokens": 2048}`。Manual thinking budget 在 Claude
4.6 上已弃用，在 Claude 4.7 及更高版本会被拒绝。不要同时设置 `thinkingBudget` 和显式的
`thinking` additional body parameter。不同模型版本的能力不同，选择模式前请查阅 Anthropic
模型文档。

### Bearer Token 鉴权

对于需要 `Authorization: Bearer <token>` 的 Anthropic 兼容网关，通过模型 builder 设置
`authToken`：

```java
AnthropicChatModel model = AnthropicChatModel.builder()
    .baseUrl("https://gateway.example.com/anthropic")
    .authToken(System.getenv("ANTHROPIC_AUTH_TOKEN"))
    .modelName("claude-sonnet-4.5")
    .build();
```

传入的 Token 不需要包含 `Bearer ` 前缀，SDK 会自动添加。`apiKey` 设置 `X-Api-Key`，
`authToken` 设置 `Authorization`；两者只能配置一个，同时配置会在创建模型时抛出
`IllegalArgumentException`。
这些鉴权请求头由 SDK 管理，请通过 builder 配置，不要通过 `GenerateOptions.additionalHeaders` 添加。

## Spring Boot

Spring Boot 应用可以使用 Anthropic starter：

```xml
<dependency>
    <groupId>io.agentscope</groupId>
    <artifactId>agentscope-anthropic-spring-boot-starter</artifactId>
    <version>${agentscope.version}</version>
</dependency>
```

通过 Bearer Token 接入网关的配置示例：

```yaml
agentscope:
  model:
    provider: anthropic
  anthropic:
    base-url: https://gateway.example.com/anthropic
    auth-token: ${ANTHROPIC_AUTH_TOKEN}
    model-name: claude-sonnet-4.5
```

`agentscope.anthropic.auth-token` 为可选配置，未设置或为空白时不启用 Bearer 鉴权。
原有的 `agentscope.anthropic.api-key` 配置仍然可用，但同时配置两个非空白凭据会导致启动失败。
Builder customizer 在校验前执行，可以通过 `apiKey(null)` 或 `authToken(null)` 清除其中一种凭据。

完整 builder 选项、formatter、credential 和 registry context 细节见 [模型](/v2/zh/docs/building-blocks/model)。

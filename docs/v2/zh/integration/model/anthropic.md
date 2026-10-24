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

### 扩展思考

对于支持 adaptive thinking 的模型，使用 adaptive 模式。对于要求 manual extended thinking 的模型，
设置 `thinkingBudget(2048)` 会发送 `thinking: {"type": "enabled", "budget_tokens": 2048}`。
`thinkingBudget` 至少为 1024 tokens，更小的值会在本地报错。普通 manual thinking 还需要将
`maxTokens` 设置得高于 thinking budget。Interleaved thinking 允许 budget 大于 `maxTokens`，
因此 AgentScope 将这项与模型相关的校验交给 API。请查阅
[Anthropic thinking 文档](https://platform.claude.com/docs/en/build-with-claude/extended-thinking)
确认所用模型支持的模式和预算规则。

单次调用中的 `thinkingBudget` 或显式 `thinking` additional body parameter 会替换整个默认
thinking 配置。不要在同一个 `GenerateOptions` 中同时设置两者。例如，
`additionalBodyParam("thinking", Map.of("type", "disabled"))` 会覆盖默认的 manual budget，
不会继承 `budget_tokens`。

保存对话历史或回传工具结果时，请保留返回的 `ThinkingBlock` metadata，其中包含多轮往返所需的
原生思考签名和 redacted blocks。如果 Anthropic 专属 metadata 损坏，会在本地给出明确错误；
请恢复原始签名块，或从历史中移除受影响的轮次及其工具结果。将损坏的签名块静默转换为文本无法修复签名。

## 服务端工具

Anthropic 内置工具由 Anthropic 在模型服务端执行，不会进入 AgentScope 本地 Toolkit。使用 SDK
的强类型工具定义构造 `ToolUnion`，再交给 `AnthropicServerTool.of(...)` 包装：

```java
import com.anthropic.models.messages.ToolUnion;
import com.anthropic.models.messages.WebSearchTool20250305;
import io.agentscope.extensions.model.anthropic.tool.AnthropicServerTool;

AnthropicChatModel model = AnthropicChatModel.builder()
    .apiKey(System.getenv("ANTHROPIC_API_KEY"))
    .modelName("claude-sonnet-4.5")
    .addServerTool(AnthropicServerTool.of(
        ToolUnion.ofWebSearchTool20250305(WebSearchTool20250305.builder().build())))
    .build();
```

服务端工具可以与本地 function tools 同时配置；Anthropic 按工具名称选择工具，因此两者名称冲突时会快速
失败。`AnthropicServerTool.of(...)` 只接受 SDK 内置工具定义，不接受自定义 client tool。

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

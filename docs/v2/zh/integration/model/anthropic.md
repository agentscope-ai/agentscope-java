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
import io.agentscope.extensions.model.anthropic.AnthropicChatModel;

AnthropicChatModel model = AnthropicChatModel.builder()
    .apiKey(System.getenv("ANTHROPIC_API_KEY"))
    .modelName("claude-sonnet-4.5")
    .stream(true)
    .build();
```

## PDF 文档与引用

PDF `DataBlock` 会转换为 Anthropic document block。支持 Base64、本地文件路径或 `file://` URL，
以及远程 HTTP(S) URL；本地 PDF 会在发送请求前编码为 Base64。

```java
import io.agentscope.core.message.DataBlock;
import io.agentscope.core.message.Msg;
import io.agentscope.core.message.MsgRole;
import io.agentscope.core.message.TextBlock;
import io.agentscope.core.message.URLSource;
import io.agentscope.core.model.GenerateOptions;
import java.util.List;

DataBlock document = DataBlock.builder()
    .name("guide.pdf")
    .source(URLSource.builder()
        .url("file:///absolute/path/to/guide.pdf")
        .mimeType("application/pdf")
        .build())
    .build();

Msg request = Msg.builder()
    .role(MsgRole.USER)
    .content(document, TextBlock.builder().text("请总结这个文档。").build())
    .build();

GenerateOptions options = GenerateOptions.builder()
    .citationsEnabled(true)
    .build();

model.stream(List.of(request), List.of(), options);
```

Anthropic 要求一次请求中的 document 必须全部开启 citations 或全部关闭，因此
`citationsEnabled` 是请求级选项，并会应用到所有 PDF document。返回的引用可以通过
`TextBlock.getCitations()` 获取。带引用的响应可能包含多个文本块，以保证每条引用仍然与其
支持的具体文本片段关联。请求级设置会覆盖 `defaultOptions`，默认不启用引用。
如果 `onModelCall` 中间件改写了响应文本，引用会被清除，因为此时无法再可靠地将引用与
其支持的文本对应；未改写的文本会保留引用。

当前仅支持 PDF document block，暂不支持 plain-text document、custom-content document 和
Anthropic Files API 的 `file_id` source。

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

## 客户端工具搜索

在 agent 上注册 `ToolSearchMiddleware`，即可使用自定义客户端搜索策略和原生加载。
适配层将候选工具标记为 `defer_loading: true`，搜索工具和始终可见的工具不延迟加载。
搜索结果包含 `tool_reference`，Anthropic 根据请求 `tools` 数组中的完整定义展开引用。
延迟定义仍需每次发送，但只在加载后进入模型上下文。提示缓存标记放在最后一个不延迟
加载的客户端工具上，不会放在延迟工具上。请使用支持该协议的模型，或选择普通函数
调用回退方式。详见[按需搜索工具](/v2/zh/docs/building-blocks/tool#按需搜索工具)。

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

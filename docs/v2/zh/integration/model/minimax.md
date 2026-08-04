# MiniMax 模型

`agentscope-extensions-model-openai` 通过 OpenAI 兼容模型栈提供 MiniMax 的一等支持。引入 OpenAI 模型扩展模块后，可以通过 `ModelRegistry` 使用 `minimax:<model>`。

## 添加依赖

```xml
<dependency>
    <groupId>io.agentscope</groupId>
    <artifactId>agentscope-extensions-model-openai</artifactId>
    <version>${agentscope.version}</version>
</dependency>
```

## ModelRegistry

设置 `MINIMAX_API_KEY` 后，使用 `minimax:<model>` 字符串 id：

```java
ReActAgent agent = ReActAgent.builder()
    .name("assistant")
    .model("minimax:MiniMax-M3") // 底层由 ModelRegistry.resolve(modelId) 解析
    .build();
```

Provider 的 base URL 默认使用 `https://api.minimaxi.com/v1`。`OpenAIClient` 会追加默认 chat completions endpoint，因此最终请求 URL 是 `https://api.minimaxi.com/v1/chat/completions`，与 MiniMax OpenAI 兼容 API 保持一致。发送请求前会去掉 `minimax:` 前缀，并使用 `io.agentscope.extensions.model.openai.compat.minimax` 下的 MiniMax formatter。

## 思考模式

通过 `ModelCreationContext` 解析模型时可以传入 MiniMax 思考参数：

```java
import io.agentscope.core.model.Model;
import io.agentscope.core.model.ModelCreationContext;
import io.agentscope.core.model.ModelRegistry;

Model model = ModelRegistry.resolve(
    "minimax:MiniMax-M3",
    ModelCreationContext.builder()
        .enableThinking(false)
        .build());
```

`enableThinking(false)` 会发送 `thinking: {"type": "disabled"}`，`enableThinking(true)` 会发送 `thinking: {"type": "adaptive"}`。MiniMax-M3 在省略 `thinking` 时默认使用 adaptive thinking；M2.x 模型即使请求 disabled 也会保持思考开启。Formatter 默认开启 `reasoning_split`，这样 MiniMax 的思考内容可以被解析为 `ThinkingBlock`。

## 兼容性说明

MiniMax formatter 会把 OpenAI 风格请求适配为 MiniMax OpenAI 兼容 Chat Completions API 支持的格式。由于 MiniMax 标记 `max_tokens` 为 deprecated，formatter 会把 `max_tokens` 映射为 `max_completion_tokens`。

MiniMax 的工具定义支持 function tools，但官方 schema 没有工具 schema 的 `strict` 字段，因此默认 formatter 会省略 `strict`，即使工具注册时开启了严格 schema 校验。MiniMax 也没有文档化 `tool_choice`，因此显式工具选择会从 MiniMax 请求中移除。

Formatter 会移除 MiniMax 不支持的 OpenAI-only 请求字段，例如 `reasoning_effort`、`frequency_penalty`、`presence_penalty`、`thinking_budget`、`parallel_tool_calls`、`response_format` 和 `seed`。结构化输出默认使用 AgentScope 的 fallback 行为，因为 MiniMax 没有文档化 OpenAI `response_format` 的 schema 约束输出能力。

使用兼容端点或自托管端点时，可以通过 `ModelCreationContext` 传入 `baseUrl`、`endpointPath`、生成参数或 formatter 覆盖。

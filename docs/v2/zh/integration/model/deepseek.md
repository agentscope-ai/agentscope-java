# DeepSeek 模型

`agentscope-extensions-model-openai` 通过 OpenAI 兼容模型栈提供 DeepSeek 的一等支持。引入 OpenAI 模型扩展模块后，可以通过 `ModelRegistry` 使用 `deepseek:<model>`。

## 添加依赖

```xml
<dependency>
    <groupId>io.agentscope</groupId>
    <artifactId>agentscope-extensions-model-openai</artifactId>
    <version>${agentscope.version}</version>
</dependency>
```

## ModelRegistry

设置 `DEEPSEEK_API_KEY` 后，使用 `deepseek:<model>` 字符串 id：

```java
ReActAgent agent = ReActAgent.builder()
    .name("assistant")
    .model("deepseek:deepseek-v4-flash") // 底层由 ModelRegistry.resolve(modelId) 解析
    .build();
```

Provider 默认使用 `https://api.deepseek.com`，发送请求前会去掉 `deepseek:` 前缀，并使用 `io.agentscope.extensions.model.openai.compat.deepseek` 下的 DeepSeek formatter。

## 思考模式

通过 `ModelCreationContext` 解析模型时可以开启 DeepSeek 思考模式：

```java
import io.agentscope.core.model.Model;
import io.agentscope.core.model.ModelCreationContext;
import io.agentscope.core.model.ModelRegistry;

Model model = ModelRegistry.resolve(
    "deepseek:deepseek-v4-flash",
    ModelCreationContext.builder()
        .enableThinking(true)
        .build());
```

流式调用时可以把 `ThinkingBlockDeltaEvent` 和 `TextBlockDeltaEvent` 分开渲染。

## 兼容性说明

DeepSeek formatter 会保留 DeepSeek 兼容的消息字段，包括 `system` 角色和支持的 `name` 字段；同时会移除历史轮次中过期的 reasoning 内容，并保留当前工具调用上下文需要的 reasoning 内容。

DeepSeek 稳定端点默认不使用工具 schema 的 `strict` 字段，因此默认 formatter 会省略 `strict`，即使工具注册时开启了严格 schema 校验。结构化输出默认使用 AgentScope 的 fallback 行为；只有在你明确确认兼容端点支持 native structured output 时才需要手动开启。

使用 beta 或其他兼容端点时，可以通过 `ModelCreationContext` 传入 `baseUrl`、`endpointPath`、生成参数或 formatter 覆盖。

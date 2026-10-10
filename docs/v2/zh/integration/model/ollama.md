---
title: Ollama
en_link: /v2/en/integration/model/ollama
---

`agentscope-extensions-model-ollama` 接入本地托管的 Ollama 模型，适合本地开发、私有化部署和离线模型服务。

## 添加依赖

```xml
<dependency>
    <groupId>io.agentscope</groupId>
    <artifactId>agentscope-extensions-model-ollama</artifactId>
    <version>${agentscope.version}</version>
</dependency>
```

## ModelRegistry

使用 `ollama:<model>` 字符串 id。`OLLAMA_BASE_URL` 是可选环境变量，不设置时默认使用本地 Ollama endpoint。

```java
ReActAgent agent = ReActAgent.builder()
    .name("assistant")
    .model("ollama:llama3") // 底层由 ModelRegistry.resolve(modelId) 解析
    .build();
```

## 显式 builder

需要非默认 Ollama endpoint、formatter、transport、proxy 或 Ollama options 时，使用 builder：

```java
import io.agentscope.extensions.model.ollama.OllamaChatModel;

OllamaChatModel model = OllamaChatModel.builder()
    .modelName("llama3")
    .baseUrl("http://localhost:11434")
    .build();
```

## 结构化输出

调用 `agent.call(messages, Output.class)` 时，Ollama provider 会把输出类的 JSON Schema 写入原生 `format` 字段。返回消息包含 `_structured_output`，可通过 `getStructuredData(Output.class)` 读取结果，同时保留响应正文、思考内容和用量统计。

```java
public record ImageDescription(java.util.List<String> description, String name) {}

try (ReActAgent agent = ReActAgent.builder()
        .name("image-analysis")
        .model(model) // 图片输入需要使用本地视觉模型。
        .build()) {
    Msg result = agent.call(messages, ImageDescription.class).block();
    ImageDescription description = result.getStructuredData(ImageDescription.class);
}
```

文本和图片输入、流式和非流式模式均使用这一格式。注册业务工具后，请求同时包含 `tools` 和 `format`，Agent 可以先执行工具调用，再读取最终的 JSON 响应。需要使用支持工具调用的模型，以及支持这一组合的本地 Ollama 版本。如果原生调用抛出错误，Agent 会回退到合成的 `generate_response` 工具；依赖该回退路径时，应避免设置仅输出 JSON 的默认 `format`。两个请求字段见[官方聊天 API](https://docs.ollama.com/api/chat)。[Ollama Cloud 当前不支持结构化输出](https://docs.ollama.com/capabilities/structured-outputs)。

## Spring Boot

Spring Boot 应用可以使用 Ollama starter：

```xml
<dependency>
    <groupId>io.agentscope</groupId>
    <artifactId>agentscope-ollama-spring-boot-starter</artifactId>
    <version>${agentscope.version}</version>
</dependency>
```

通过 `agentscope.model.provider=ollama` 配置本地 Ollama 模型。base URL 为可选项，默认是
`http://localhost:11434`：

```yaml
agentscope:
  model:
    provider: ollama
  ollama:
    model-name: llama3
    # base-url: http://localhost:11434
```

完整 builder 选项、formatter、credential 和 registry context 细节见 [模型](/v2/zh/docs/building-blocks/model)。

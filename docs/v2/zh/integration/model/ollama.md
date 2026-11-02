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

调用 `agent.call(messages, Output.class)` 时，Ollama provider 会通过 Ollama 原生 `format` 字段发送输出类的 JSON Schema。返回消息保留 JSON 文本，并可通过 `getStructuredData(Output.class)` 读取解析后的值。

```java
public record Weather(String city, double temperature) {}

try (ReActAgent agent = ReActAgent.builder()
        .name("weather")
        .model(model)
        .toolkit(toolkit)
        .build()) {
    Msg result = agent.call("当前温度是多少？", Weather.class).block();
    Weather weather = result.getStructuredData(Weather.class);
}
```

当 Agent 注册了业务工具时，原生结构化输出默认分两个阶段完成。工具推理请求只携带 `tools`，不携带 `format`，因此模型可以正常调用并串联多个工具。某一轮推理不再返回工具调用时，provider 会把该轮自然语言回答作为内部草稿，再发起一次只携带 `format` 且不携带 `tools` 和 `tool_choice` 的最终请求。这样最终请求可以把真实工具结果整理成目标结构，无需让 Ollama 在同一次请求中同时决定工具调用和 JSON 生成。

终止推理轮会发起两次物理 Ollama 请求：第一次判断是否还需要工具，第二次生成结构化响应。在流式模式下，provider 会先缓冲工具阶段的响应，直到能够确定其中是否包含工具调用。内部草稿不会作为用户可见文本发出，只会流式输出最终结构化响应。更早返回工具调用的推理轮仍通过正常 ReAct 循环执行。

如果需要使用合成的 `generate_response` 工具路径，可以这样配置：

```java
OllamaChatModel model = OllamaChatModel.builder()
    .modelName("llama3")
    .nativeStructuredOutputWithTools(false) // 注册业务工具时使用合成路径
    .build();
```

设置 `nativeStructuredOutput(false)` 后，所有结构化输出调用都会使用合成路径，包括未注册业务工具的调用。`ModelCreationContext` 也支持 `nativeStructuredOutput` 和 `nativeStructuredOutputWithTools` 这两个选项。

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

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

## 单次调用执行配置

单次调用的超时与重试通过 `GenerateOptions.executionConfig` 设置，经选项转换后实际约束本次 Ollama HTTP 调用。显式设置的字段覆盖模型默认值；未设置的字段回退默认（5 分钟超时、3 次尝试）。该配置仅在客户端生效，不会发送给 Ollama 服务端：

```java
import io.agentscope.core.model.ExecutionConfig;
import io.agentscope.core.model.GenerateOptions;
import java.time.Duration;

model.stream(messages, /* tools = */ List.of(),
        GenerateOptions.builder()
                .executionConfig(ExecutionConfig.builder()
                        .timeout(Duration.ofSeconds(30))
                        .maxAttempts(2)
                        .build())
                .build());
```

取消订阅仍会传播到底层 HTTP 调用；已发出用户可见内容后不会重试请求。

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

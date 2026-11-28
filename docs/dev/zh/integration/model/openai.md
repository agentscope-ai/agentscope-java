---
title: OpenAI
en_link: /dev/en/integration/model/openai
---

`agentscope-extensions-model-openai` 接入 OpenAI Chat Completions 风格的模型。OpenAI 兼容端点也使用这个适配模块，例如 DeepSeek、GLM、Kimi、MiniMax 等遵循 OpenAI API 载荷格式的服务。

## 添加依赖

```xml
<dependency>
    <groupId>io.agentscope</groupId>
    <artifactId>agentscope-extensions-model-openai</artifactId>
    <version>${agentscope.version}</version>
</dependency>
```

## ModelRegistry

设置 `OPENAI_API_KEY` 后，使用 `openai:<model>` 字符串 id：

```java
ReActAgent agent = ReActAgent.builder()
    .name("assistant")
    .model("openai:gpt-4.1-mini") // 底层由 ModelRegistry.resolve(modelId) 解析
    .build();
```

## 显式 builder

需要自定义 endpoint、formatter、transport 或生成参数时，使用 builder：

```java
import io.agentscope.extensions.model.openai.OpenAIChatModel;

OpenAIChatModel model = OpenAIChatModel.builder()
    .apiKey(System.getenv("OPENAI_API_KEY"))
    .modelName("gpt-4.1-mini")
    .stream(true)
    .build();
```

收到 `[DONE]` 帧，或所有请求的候选项都有结束原因且 HTTP 响应体结束时，流式调用才成功完成。提前结束会报告模型调用失败，Durable 可按已有策略恢复。

OpenAI 默认传输每 10 秒发送一次 HTTP/2 PING，探测被网关静默丢弃的连接。连接层默认每次只发一个 POST，模型层默认也只尝试一次。提高模型尝试上限后，只对 HTTP 429 响应进行模型层重试。断连、超时和 5xx 会返回给调用方，Durable 可按自身恢复策略和预算处理。

Java 调用方可从 `OpenAIChatModel.httpTransportDefaults()` 开始定制，并将配置传给 `OpenAIChatModel.Builder.httpTransportConfig(...)`。显式传入的传输和工厂覆盖项使用自身的连接配置。PING 间隔可按读取超时调整，设为零可关闭 PING。

## Spring Boot

Spring Boot 应用可以使用 OpenAI starter：

```xml
<dependency>
    <groupId>io.agentscope</groupId>
    <artifactId>agentscope-openai-spring-boot-starter</artifactId>
    <version>${agentscope.version}</version>
</dependency>
```

| Spring Boot 配置 | 默认值 |
| --- | --- |
| `agentscope.openai.http2-ping-interval` | `10s` |
| `agentscope.openai.read-timeout` | `5m` |
| `agentscope.openai.retry-on-connection-failure` | `false` |
| `agentscope.openai.max-attempts` | `1` |

开启 `retry-on-connection-failure` 会允许连接层重发 POST。模型尝试上限只控制模型层重试。

完整 builder 选项、formatter、credential 和 registry context 细节见 [模型](/dev/zh/docs/building-blocks/model)。

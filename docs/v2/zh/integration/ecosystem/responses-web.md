# Responses Web

`agentscope-extensions-responses-web` 将 AgentScope `ReActAgent` 暴露为兼容
[OpenAI Responses API](https://developers.openai.com/api/reference/resources/responses) 的接口，
支持非流式响应、Responses 风格 SSE、工具、结构化输出和状态存储。

## 何时使用

- 希望兼容 Responses API 的客户端直接调用 AgentScope Agent。
- 需要流式响应、后台执行、结构化输出或有状态会话。

## Spring Boot 快速开始

```xml
<dependency>
    <groupId>io.agentscope</groupId>
    <artifactId>agentscope-responses-web-starter</artifactId>
    <version>${agentscope.version}</version>
</dependency>
```

Starter 已包含 Responses 核心扩展，并会自动注册 Responses 和 Conversations 控制器。可以按需
修改接口路径：

```yaml
agentscope:
  responses:
    enabled: true
    base-path: /v1/responses
  conversations:
    base-path: /v1/conversations
```

创建流式响应：

```bash
curl -N -X POST http://localhost:8080/v1/responses \
  -H 'Content-Type: application/json' \
  -d '{
    "model": "qwen3-max",
    "input": "用三句话介绍 AgentScope Java。",
    "stream": true
  }'
```

省略 `stream` 或将其设置为 `false` 时返回 JSON。Starter 还支持 `store`、
`previous_response_id`、`background`、Conversations 和 JSON Schema 输出。

## 核心适配器

如果要自行提供 HTTP 传输层，可以直接引入核心扩展：

```xml
<dependency>
    <groupId>io.agentscope</groupId>
    <artifactId>agentscope-extensions-responses-web</artifactId>
    <version>${agentscope.version}</version>
</dependency>
```

```java
import io.agentscope.core.responses.converter.ResponsesConversionResult;
import io.agentscope.core.responses.converter.ResponsesInputConverter;
import io.agentscope.core.responses.model.ResponsesStreamEvent;
import io.agentscope.core.responses.streaming.ResponsesStreamingAdapter;
import reactor.core.publisher.Flux;

ResponsesInputConverter inputConverter = new ResponsesInputConverter();
ResponsesStreamingAdapter adapter = new ResponsesStreamingAdapter();
ResponsesConversionResult converted = inputConverter.convert(request);

Flux<ResponsesStreamEvent> stream =
        adapter.stream(
                agent,
                converted.messages(),
                converted.structuredOutputSchema(),
                request,
                responseId);
```

适配器将 AgentScope 事件转换为 Responses 流式事件：

- 文本增量转换为 `response.output_text.delta` 事件。
- 工具调用块转换为函数调用条目事件。
- 完成和失败转换为 `response.completed` 和 `response.failed`。

如需调用级配置，请使用额外接收 `RuntimeContext` 的重载方法。

## 自定义 Spring SSE 控制器

如果 Starter 提供的完整控制器不适合当前应用，也可以自行编写一个最小的流式端点：

```java
@RestController
public class ResponsesApiController {
    private final ResponsesInputConverter inputConverter = new ResponsesInputConverter();
    private final ResponsesStreamingAdapter adapter = new ResponsesStreamingAdapter();
    @Autowired private ReActAgent agent;

    @PostMapping(value = "/v1/responses",
                 produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public Flux<String> respond(@RequestBody ResponsesRequest req) {
        ResponsesConversionResult converted = inputConverter.convert(req);
        String responseId = "resp_" + UUID.randomUUID();
        return adapter.stream(
                        agent,
                        converted.messages(),
                        converted.structuredOutputSchema(),
                        req,
                        responseId)
                .map(this::toSseLine); // 序列化为事件名称和 JSON 数据
    }
}
```

如需非流式响应、状态存储、后台执行、Conversations、请求级工具和完整校验，请使用 Starter
提供的控制器。

## 模型对照表

OpenAI 客户端发起调用时通常会带 `model` 字段，可在控制器层映射到不同 Agent：

```java
String model = req.getModel(); // 例如 "qwen3-max"
ReActAgent target = agentRegistry.lookup(model);
return adapter.stream(
        target,
        converted.messages(),
        converted.structuredOutputSchema(),
        req,
        responseId);
```

## 状态与工具行为

- 默认 `ResponsesStateService` 将响应和 Conversations 保存在内存中。如需持久化或跨实例共享，
  请在应用中提供自定义 Bean。
- 请求级 Function Tool 仅包含 Schema。客户端执行返回的 `function_call` 后，需要在后续请求中
  发送 `function_call_output`。
- 在 Agent 的 `Toolkit` 中注册的 Java 工具仍可在服务端执行。
- Responses 流以 `response.completed` 或 `response.failed` 结束，不会发送 Chat Completions
  风格的 `[DONE]` 标记。

## 相关集成

- **Chat Completions Web**：适合使用通用 Chat Completions 协议的客户端。
- **AG-UI**：适合需要更丰富、面向 UI 的 Agent 事件的应用。

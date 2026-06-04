# Chat Completions Web

`agentscope-extensions-chat-completions-web` 把 AgentScope Agent 包装成 [OpenAI Chat Completions](https://platform.openai.com/docs/api-reference/chat) 兼容接口，让 OpenAI SDK、LangChain、LlamaIndex、ChatBox 等客户端"以为自己在调 OpenAI"。

## 何时使用

- 想把 Agent 变成"标准 LLM"暴露给已有客户端，无需改对端代码。
- 希望保留流式输出、工具调用过程，符合 OpenAI 的 SSE 协议格式。

## 添加依赖

```xml
<dependency>
    <groupId>io.agentscope</groupId>
    <artifactId>agentscope-extensions-chat-completions-web</artifactId>
    <version>${agentscope.version}</version>
</dependency>
```

注意：本扩展**只**提供框架无关的核心适配器，真正的 HTTP/SSE 路由请使用对应的 Spring Boot Starter（或自行写 Controller）。

## 核心适配器

```java
import io.agentscope.core.chat.completions.streaming.ChatCompletionsStreamingAdapter;
import io.agentscope.core.chat.completions.model.ChatCompletionsRequest;
import io.agentscope.core.chat.completions.model.ChatCompletionsChunk;
import reactor.core.publisher.Flux;

ChatCompletionsStreamingAdapter adapter = new ChatCompletionsStreamingAdapter();

// 把 OpenAI 风格 Request 转成 Agent 调用 + 反向把事件流转回 OpenAI chunks
Flux<ChatCompletionsChunk> stream = adapter.stream(agent, request);
```

适配器把 AgentScope 的 `Event` 流（含 `REASONING`、`TOOL_RESULT` 等）转成 OpenAI 兼容的 `ChatCompletionsChunk`，包括：

- 文本增量 → `delta.content`
- 工具调用 → `delta.tool_calls[]`
- 流结束 → 带 `finish_reason` 的 chunk

## 在 Spring Boot 里暴露 SSE

最简单的方式是引入 starter（推荐），它会自动注册控制器。也可以自己写：

```java
@RestController
public class ChatController {
    private final ChatCompletionsStreamingAdapter adapter = new ChatCompletionsStreamingAdapter();
    @Autowired private Agent agent;

    @PostMapping(value = "/v1/chat/completions",
                 produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public Flux<String> chat(@RequestBody ChatCompletionsRequest req) {
        return adapter.stream(agent, req)
            .map(this::toSseLine);  // 序列化 + "data: ..." 包装
    }
}
```

## 模型对照表

OpenAI 客户端发起调用时通常会带 `model` 字段，可在控制器层做映射：

```java
String model = req.getModel();   // 例如 "gpt-4o"，路由到不同 Agent
Agent target = agentRegistry.lookup(model);
return adapter.stream(target, req);
```

## 适合搭配

- **AG-UI**：偏 Web 前端可视化，关注事件粒度的 UI 渲染。
- **Chat Completions Web**：偏标准 LLM 接入，只关心 OpenAI 兼容。

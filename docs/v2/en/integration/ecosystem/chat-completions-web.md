# Chat Completions Web

`agentscope-extensions-chat-completions-web` exposes an AgentScope Agent behind an [OpenAI Chat Completions](https://platform.openai.com/docs/api-reference/chat)-compatible API, so OpenAI SDKs, LangChain, LlamaIndex, ChatBox, etc. can connect as if they were talking to OpenAI.

## When to use

- You want to expose your Agent as a "standard LLM" without modifying clients.
- You need streaming with tool-call events that match the OpenAI SSE format.

## Add the dependency

```xml
<dependency>
    <groupId>io.agentscope</groupId>
    <artifactId>agentscope-extensions-chat-completions-web</artifactId>
    <version>${agentscope.version}</version>
</dependency>
```

Note: this module ships only the framework-agnostic core adapter — wire HTTP/SSE through the matching Spring Boot Starter (recommended) or your own controller.

## Core adapter

```java
import io.agentscope.core.chat.completions.streaming.ChatCompletionsStreamingAdapter;
import io.agentscope.core.chat.completions.model.ChatCompletionsRequest;
import io.agentscope.core.chat.completions.model.ChatCompletionsChunk;
import reactor.core.publisher.Flux;

ChatCompletionsStreamingAdapter adapter = new ChatCompletionsStreamingAdapter();

// Convert OpenAI-style request → Agent invocation, and Agent events → OpenAI chunks
Flux<ChatCompletionsChunk> stream = adapter.stream(agent, request);
```

The adapter converts AgentScope's `Event` stream (including `REASONING` and `TOOL_RESULT`) into OpenAI-compatible `ChatCompletionsChunk` objects:

- Text deltas → `delta.content`
- Tool calls → `delta.tool_calls[]`
- Stream end → a chunk with `finish_reason`

## Expose as SSE in Spring Boot

Easiest is the starter — it registers the controller. To do it manually:

```java
@RestController
public class ChatController {
    private final ChatCompletionsStreamingAdapter adapter = new ChatCompletionsStreamingAdapter();
    @Autowired private Agent agent;

    @PostMapping(value = "/v1/chat/completions",
                 produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public Flux<String> chat(@RequestBody ChatCompletionsRequest req) {
        return adapter.stream(agent, req)
            .map(this::toSseLine);  // serialize and wrap "data: ..."
    }
}
```

## Model routing

OpenAI clients send a `model` field; route at the controller layer:

```java
String model = req.getModel();   // e.g. "gpt-4o"; route to different Agents
Agent target = agentRegistry.lookup(model);
return adapter.stream(target, req);
```

## Pairs well with

- **AG-UI** for fine-grained UI rendering with event semantics.
- **Chat Completions Web** for plain LLM-style integrations focused on OpenAI compatibility.

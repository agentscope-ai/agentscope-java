---
title: Chat Completions Web
zh_link: /v2/zh/integration/ecosystem/chat-completions-web
---

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
List<Msg> messages = new ChatMessageConverter().convertMessages(request.getMessages());
Flux<ChatCompletionsChunk> stream =
        adapter.stream(agent, messages, requestId, request.getModel(), request.isIncludeUsage());
```

The adapter consumes `ReActAgent#streamEvents` and converts the events into OpenAI-compatible `ChatCompletionsChunk` objects:

- Text deltas → `delta.content`
- Tool calls → `delta.tool_calls[]` (a chunk with `id` and `name`, then the arguments in pieces)
- Stream end → one chunk with `finish_reason`
- With `"stream_options": {"include_usage": true}` → one more chunk with empty `choices` and the `usage` of the whole request (all model calls, including tool steps). It is not sent when no model call reported usage.

## Runtime context and driving the agent yourself

To pass a `RuntimeContext` (session, user, ...), use the overload that takes one:

```java
adapter.stream(agent, messages, ctx, requestId, request.getModel(), request.isIncludeUsage());
```

To also look at the events as they pass, for example to tell a finished reply from one that stopped for tool confirmation, run `streamEvents` yourself and convert each event with a converter. A converter keeps state for one stream, so create a new one for every stream:

```java
Function<AgentEvent, List<ChatCompletionsChunk>> convert =
        adapter.newEventConverter(requestId, request.getModel(), request.isIncludeUsage());
Flux<ChatCompletionsChunk> stream =
        agent.streamEvents(messages, ctx)
                .doOnNext(event -> { /* inspect AgentResultEvent etc. */ })
                .concatMapIterable(convert);
```

## Expose as SSE in Spring Boot

Easiest is the starter — it registers the controller. To do it manually:

```java
@RestController
public class ChatController {
    private final ChatCompletionsStreamingAdapter adapter = new ChatCompletionsStreamingAdapter();
    private final ChatMessageConverter converter = new ChatMessageConverter();
    @Autowired private ReActAgent agent;

    @PostMapping(value = "/v1/chat/completions",
                 produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public Flux<String> chat(@RequestBody ChatCompletionsRequest req) {
        List<Msg> messages = converter.convertMessages(req.getMessages());
        String requestId = "chatcmpl-" + UUID.randomUUID();
        return adapter.stream(agent, messages, requestId, req.getModel(), req.isIncludeUsage())
            .map(this::toSseLine);  // serialize and wrap "data: ...", then end with "data: [DONE]"
    }
}
```

## Model routing

OpenAI clients send a `model` field; route at the controller layer:

```java
String model = req.getModel();   // e.g. "gpt-4o"; route to different Agents
ReActAgent target = agentRegistry.lookup(model);
return adapter.stream(target, messages, requestId, model, req.isIncludeUsage());
```

## Pairs well with

- **AG-UI** for fine-grained UI rendering with event semantics.
- **Chat Completions Web** for plain LLM-style integrations focused on OpenAI compatibility.

# Responses Web

`agentscope-extensions-responses-web` exposes an AgentScope `ReActAgent` through an
[OpenAI Responses API](https://developers.openai.com/api/reference/resources/responses)-compatible
interface, including non-streaming responses, Responses-style SSE, tools, structured output, and
stored state.

## When to use

- You want Responses API-compatible clients to call an AgentScope Agent.
- You need streaming, background execution, structured output, or stateful conversations.

## Spring Boot quick start

```xml
<dependency>
    <groupId>io.agentscope</groupId>
    <artifactId>agentscope-responses-web-starter</artifactId>
    <version>${agentscope.version}</version>
</dependency>
```

The starter includes the core Responses extension and registers the Responses and Conversations
controllers. You can customize the endpoint paths as needed:

```yaml
agentscope:
  responses:
    enabled: true
    base-path: /v1/responses
  conversations:
    base-path: /v1/conversations
```

Create a streaming response:

```bash
curl -N -X POST http://localhost:8080/v1/responses \
  -H 'Content-Type: application/json' \
  -d '{
    "model": "qwen3-max",
    "input": "Explain AgentScope Java in three short sentences.",
    "stream": true
  }'
```

Omit `stream` or set it to `false` for a JSON response. The starter also supports `store`,
`previous_response_id`, `background`, Conversations, and JSON Schema output.

## Core adapter

Use the core extension directly when providing your own HTTP transport:

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

The adapter converts AgentScope events into Responses streaming events:

- Text deltas become `response.output_text.delta` events.
- Tool-use blocks become function-call item events.
- Completion and failure become `response.completed` and `response.failed`.

Use the overload that also accepts a `RuntimeContext` for invocation-local configuration.

## Custom Spring SSE controller

When the starter's complete controller is not suitable, you can wire a minimal streaming endpoint
yourself:

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
                .map(this::toSseLine); // Serialize as an event name plus JSON data
    }
}
```

Use the starter controller for the complete API, including non-streaming responses, stored state,
background execution, Conversations, request-level tools, and validation.

## Model routing

OpenAI clients normally send a `model` field. Map it to a different Agent in the controller:

```java
String model = req.getModel(); // For example, "qwen3-max"
ReActAgent target = agentRegistry.lookup(model);
return adapter.stream(
        target,
        converted.messages(),
        converted.structuredOutputSchema(),
        req,
        responseId);
```

## State and tool behavior

- The default `ResponsesStateService` keeps stored responses and Conversations in memory. Replace
  it with an application bean when durable or shared state is required.
- Request-level function tools are schema-only. The client executes the returned `function_call`
  and sends a `function_call_output` in a later request.
- Java tools registered with the Agent's `Toolkit` remain executable on the server.
- Responses streams end with `response.completed` or `response.failed`; they do not send a
  Chat Completions-style `[DONE]` sentinel.

## Related integrations

- **Chat Completions Web** for clients that expect the widely supported Chat Completions protocol.
- **AG-UI** for applications that need richer UI-oriented agent events.

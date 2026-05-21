# Responses API

AgentScope Java can expose OpenAI Responses-compatible HTTP APIs through
`agentscope-responses-web-starter`. The starter is additive and does not change the existing Chat
Completions starter.

Official references:

- https://developers.openai.com/api/reference/resources/responses
- https://developers.openai.com/api/reference/responses/overview
- https://developers.openai.com/api/reference/resources/responses/streaming-events
- https://developers.openai.com/api/reference/resources/conversations
- https://developers.openai.com/api/reference/resources/conversations/subresources/items

## Scope

The starter provides:

- Responses create/retrieve/delete/cancel/input-items/token-count/compact endpoints
- Conversations create/retrieve/update/delete endpoints
- Conversation items create/list/retrieve/delete endpoints
- Non-streaming JSON responses
- Responses-style Server-Sent Events when `stream=true`
- JSON Schema structured output in non-streaming and streaming modes
- `previous_response_id`, `conversation`, `store`, and `background` stateful behavior
- Text, image, file-reference, audio, video, function-call, and opaque official item handling
- Function tool schema registration for external client-side tool loops
- API-shape acceptance for hosted tools such as web search, file search, code interpreter, MCP,
  computer use, image generation, and custom tools

The default state backend is in-memory. Replace `ResponsesStateService` with an application bean if
you need durable state across process restarts or multiple application instances.

Hosted OpenAI tools are accepted at the request/DTO layer, but the default starter does not execute
OpenAI-hosted services by itself. Wire those tools into AgentScope or your application toolkit when
you need real execution.

## Dependency

```xml
<dependencies>
    <dependency>
        <groupId>io.agentscope</groupId>
        <artifactId>agentscope-spring-boot-starter</artifactId>
    </dependency>
    <dependency>
        <groupId>io.agentscope</groupId>
        <artifactId>agentscope-responses-web-starter</artifactId>
        <version>${agentscope.version}</version>
    </dependency>
</dependencies>
```

## Configuration

```yaml
agentscope:
  model:
    provider: dashscope
  dashscope:
    enabled: true
    api-key: ${DASHSCOPE_API_KEY}
    model-name: qwen3-max
    stream: true
  agent:
    enabled: true
    name: "ResponsesAgent"
    sys-prompt: "You are a helpful assistant."
    max-iters: 8
  responses:
    enabled: true
    base-path: /v1/responses
  conversations:
    base-path: /v1/conversations
```

The starter expects a `ReActAgent` bean from `agentscope-spring-boot-starter`. Each request obtains
a fresh agent instance through `ObjectProvider<ReActAgent>`, while response/conversation state is
managed by `ResponsesStateService`.

## Endpoints

Responses:

- `POST /v1/responses`
- `GET /v1/responses/{response_id}`
- `DELETE /v1/responses/{response_id}`
- `POST /v1/responses/{response_id}/cancel`
- `GET /v1/responses/{response_id}/input_items`
- `POST /v1/responses/input_tokens`
- `POST /v1/responses/input_tokens/count`
- `POST /v1/responses/compact`

Conversations:

- `POST /v1/conversations`
- `GET /v1/conversations/{conversation_id}`
- `POST /v1/conversations/{conversation_id}`
- `DELETE /v1/conversations/{conversation_id}`
- `GET /v1/conversations/{conversation_id}/items`
- `POST /v1/conversations/{conversation_id}/items`
- `GET /v1/conversations/{conversation_id}/items/{item_id}`
- `DELETE /v1/conversations/{conversation_id}/items/{item_id}`

List endpoints support `after`, `limit`, and `order=asc|desc`.

## Running The Example

Start the sample app:

```bash
export DASHSCOPE_API_KEY=your_key
mvn -pl agentscope-examples/responses-web -am -DskipTests package
java -jar agentscope-examples/responses-web/target/responses-web-1.1.0-SNAPSHOT.jar \
  --server.port=8080
```

If you run it from IntelliJ IDEA, set `DASHSCOPE_API_KEY` in the run configuration and use
`--server.port=8080` as program arguments.

The examples below do not require `jq`. Use `python3 -m json.tool` to pretty-print JSON:

```bash
curl -s http://localhost:8080/v1/responses/resp_xxx | python3 -m json.tool
```

Extract an ID from a saved JSON file:

```bash
python3 -c 'import json; print(json.load(open("/tmp/response.json"))["id"])'
```

## Core Examples

Plain text:

```bash
curl -s -X POST http://localhost:8080/v1/responses \
  -H 'Content-Type: application/json' \
  -d '{
    "model": "qwen3-max",
    "input": "Briefly introduce AgentScope Java.",
    "store": true
  }' | tee /tmp/response.json | python3 -m json.tool
```

Expected result:

- `object` is `response`
- `status` is `completed`
- `id` starts with `resp_`
- `output` and `output_text` are present
- no top-level `error` is present

Retrieve a stored response:

```bash
RESP_ID=$(python3 -c 'import json; print(json.load(open("/tmp/response.json"))["id"])')
curl -s "http://localhost:8080/v1/responses/${RESP_ID}" | python3 -m json.tool
```

Continue from a previous response:

```bash
curl -s -X POST http://localhost:8080/v1/responses \
  -H 'Content-Type: application/json' \
  -d "{
    \"model\": \"qwen3-max\",
    \"previous_response_id\": \"${RESP_ID}\",
    \"input\": \"Continue from the previous response in one sentence.\"
  }" | python3 -m json.tool
```

Streaming:

```bash
curl -N -X POST http://localhost:8080/v1/responses \
  -H 'Content-Type: application/json' \
  -H 'Accept: text/event-stream' \
  -d '{
    "model": "qwen3-max",
    "stream": true,
    "input": "Describe AgentScope Java in three short sentences."
  }'
```

The stream uses Responses-style SSE events. A healthy stream includes events such as
`response.created`, `response.in_progress`, `response.output_item.added`,
`response.output_text.delta`, `response.output_text.done`, `response.output_item.done`, and
`response.completed`. Responses streams do not send a Chat Completions-style `[DONE]` sentinel.

## Structured Output

Non-streaming JSON Schema output:

```bash
curl -s -X POST http://localhost:8080/v1/responses \
  -H 'Content-Type: application/json' \
  -d '{
    "model": "qwen3-max",
    "input": "Extract the city and weather from: Hangzhou is hot today.",
    "text": {
      "format": {
        "type": "json_schema",
        "name": "weather_extract",
        "strict": true,
        "schema": {
          "type": "object",
          "properties": {
            "city": { "type": "string" },
            "weather": { "type": "string" }
          },
          "required": ["city", "weather"],
          "additionalProperties": false
        }
      }
    }
  }' | tee /tmp/schema-response.json | python3 -m json.tool
```

Check that `output_text` is valid JSON:

```bash
python3 - <<'PY'
import json
r = json.load(open("/tmp/schema-response.json"))
print("status =", r.get("status"))
print("output_text parsed =", json.loads(r["output_text"]))
PY
```

Streaming JSON Schema output:

```bash
curl -N -X POST http://localhost:8080/v1/responses \
  -H 'Content-Type: application/json' \
  -H 'Accept: text/event-stream' \
  -d '{
    "model": "qwen3-max",
    "stream": true,
    "input": "Extract the city and weather from: Hangzhou is hot today.",
    "text": {
      "format": {
        "type": "json_schema",
        "name": "weather_extract",
        "strict": true,
        "schema": {
          "type": "object",
          "properties": {
            "city": { "type": "string" },
            "weather": { "type": "string" }
          },
          "required": ["city", "weather"],
          "additionalProperties": false
        }
      }
    }
  }'
```

Expected result: the stream reaches `response.completed`, and the text delta/done payload contains a
JSON object.

## Background And State

Create a background response:

```bash
curl -s -X POST http://localhost:8080/v1/responses \
  -H 'Content-Type: application/json' \
  -d '{
    "model": "qwen3-max",
    "background": true,
    "store": true,
    "input": "Write a longer AgentScope Java overview."
  }' | tee /tmp/background-response.json | python3 -m json.tool
```

Retrieve and cancel it:

```bash
BG_ID=$(python3 -c 'import json; print(json.load(open("/tmp/background-response.json"))["id"])')
curl -s "http://localhost:8080/v1/responses/${BG_ID}" | python3 -m json.tool
curl -s -X POST "http://localhost:8080/v1/responses/${BG_ID}/cancel" | python3 -m json.tool
curl -s -X DELETE "http://localhost:8080/v1/responses/${BG_ID}" | python3 -m json.tool
```

Expected result: the initial response is `queued`; cancel returns the same response with
`status=cancelled`; delete returns `deleted=true`.

List the input items for a stored response:

```bash
curl -s "http://localhost:8080/v1/responses/${RESP_ID}/input_items?limit=10&order=asc" \
  | python3 -m json.tool
```

Count input tokens:

```bash
curl -s -X POST http://localhost:8080/v1/responses/input_tokens \
  -H 'Content-Type: application/json' \
  -d '{"input":"hello AgentScope"}' | python3 -m json.tool
```

Compact context:

```bash
curl -s -X POST http://localhost:8080/v1/responses/compact \
  -H 'Content-Type: application/json' \
  -d '{
    "model": "qwen3-max",
    "input": "Summarize this context in one sentence: AgentScope Java supports agents, tools, streaming, and structured output."
  }' | python3 -m json.tool
```

## Conversations

Create a conversation:

```bash
curl -s -X POST http://localhost:8080/v1/conversations \
  -H 'Content-Type: application/json' \
  -d '{"metadata":{"case":"manual-test"}}' \
  | tee /tmp/conversation.json | python3 -m json.tool
```

Use the conversation in a response:

```bash
CONV_ID=$(python3 -c 'import json; print(json.load(open("/tmp/conversation.json"))["id"])')
curl -s -X POST http://localhost:8080/v1/responses \
  -H 'Content-Type: application/json' \
  -d "{
    \"model\": \"qwen3-max\",
    \"conversation\": \"${CONV_ID}\",
    \"input\": \"Add this message to the conversation.\",
    \"store\": true
  }" | python3 -m json.tool
```

List conversation items:

```bash
curl -s "http://localhost:8080/v1/conversations/${CONV_ID}/items?limit=10&order=asc" \
  | python3 -m json.tool
```

Create, retrieve, and delete a conversation item:

```bash
curl -s -X POST "http://localhost:8080/v1/conversations/${CONV_ID}/items" \
  -H 'Content-Type: application/json' \
  -d '{
    "items": [{
      "type": "message",
      "role": "user",
      "content": [{ "type": "input_text", "text": "Hello from a conversation item." }]
    }]
  }' | tee /tmp/conversation-items.json | python3 -m json.tool

ITEM_ID=$(python3 -c 'import json; print(json.load(open("/tmp/conversation-items.json"))["data"][0]["id"])')
curl -s "http://localhost:8080/v1/conversations/${CONV_ID}/items/${ITEM_ID}" \
  | python3 -m json.tool
curl -s -X DELETE "http://localhost:8080/v1/conversations/${CONV_ID}/items/${ITEM_ID}" \
  | python3 -m json.tool
```

## Tools And Multimodal Inputs

Request-level function tools are registered as schema-only tools. This follows the client-side tool
loop pattern: the model may return a `function_call`, the client executes it, and the next request
sends a `function_call_output`.

```bash
curl -s -X POST http://localhost:8080/v1/responses \
  -H 'Content-Type: application/json' \
  -d '{
    "model": "qwen3-max",
    "input": "Call get_weather for Hangzhou, then wait for the tool result.",
    "tools": [{
      "type": "function",
      "name": "get_weather",
      "description": "Get the current weather for a city",
      "parameters": {
        "type": "object",
        "properties": {
          "city": { "type": "string" }
        },
        "required": ["city"],
        "additionalProperties": false
      },
      "strict": true
    }],
    "tool_choice": { "type": "function", "name": "get_weather" },
    "store": true
  }' | tee /tmp/tool-call-response.json | python3 -m json.tool
```

Backend Java tools are different: register real Java methods in the application `Toolkit` with
`@Tool`, then let `ReActAgent` execute them. When using backend Java tools, avoid sending a request
`tools` entry with the same name because request-level schemas are external schema-only tools.

```java
public class WeatherTools {
    @Tool(name = "get_weather", description = "Get weather for a city")
    public String getWeather(@ToolParam(name = "city", description = "City name") String city) {
        return city + " is sunny, 28C";
    }
}
```

Image and audio inputs are converted into AgentScope multimodal content blocks. End-to-end
understanding still depends on the selected model and model adapter. If a text-only model says it
cannot view images or process audio, the API layer still accepted the request, but the model backend
does not provide multimodal understanding.

File inputs with `file_id` are accepted as file references. To understand file content in
production, the application should provide upload, storage, authorization, parsing, and content
injection before calling the model.

## Error Checks

Unsupported text format:

```bash
curl -s -X POST http://localhost:8080/v1/responses \
  -H 'Content-Type: application/json' \
  -d '{
    "model": "qwen3-max",
    "input": "hello",
    "text": { "format": { "type": "json_object" } }
  }' | python3 -m json.tool
```

Expected result: a structured error response.

Missing stored response:

```bash
curl -s -X POST http://localhost:8080/v1/responses \
  -H 'Content-Type: application/json' \
  -d '{
    "model": "qwen3-max",
    "previous_response_id": "resp_not_found",
    "input": "hello"
  }' | python3 -m json.tool
```

Expected result: a structured not-found error, not an unsupported-parameter error.

## Verification

```bash
mvn -pl agentscope-extensions/agentscope-extensions-responses-web -am \
  -Dtest='io.agentscope.core.responses.**' \
  -DfailIfNoTests=false -DfailIfNoSpecifiedTests=false test

mvn -pl agentscope-extensions/agentscope-spring-boot-starters/agentscope-responses-web-starter -am \
  -Dtest='io.agentscope.spring.boot.responses.**' \
  -DfailIfNoTests=false -DfailIfNoSpecifiedTests=false test

mvn -pl agentscope-examples/responses-web -am -DskipTests package
```

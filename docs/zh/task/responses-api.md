# Responses API

AgentScope Java 可以通过 `agentscope-responses-web-starter` 暴露兼容 OpenAI Responses API 的
HTTP 接口。该 starter 是增量能力，不改变现有 Chat Completions starter。

官方参考：

- https://developers.openai.com/api/reference/resources/responses
- https://developers.openai.com/api/reference/responses/overview
- https://developers.openai.com/api/reference/resources/responses/streaming-events
- https://developers.openai.com/api/reference/resources/conversations
- https://developers.openai.com/api/reference/resources/conversations/subresources/items

## 范围

starter 提供：

- Responses create/retrieve/delete/cancel/input-items/token-count/compact endpoints
- Conversations create/retrieve/update/delete endpoints
- Conversation items create/list/retrieve/delete endpoints
- 非流式 JSON 响应
- `stream=true` 时的 Responses 风格 Server-Sent Events
- 非流式和流式 JSON Schema structured output
- `previous_response_id`、`conversation`、`store`、`background` 状态化行为
- text、image、file reference、audio、video、function call、opaque official item 处理
- 用于外部 client-side tool loop 的 function tool schema 注册
- 对 web search、file search、code interpreter、MCP、computer use、image generation、custom
  tools 等 hosted tool 请求形状做 API 兼容接收

默认状态后端是内存实现。如果需要跨进程重启或多实例共享状态，请在业务应用里替换
`ResponsesStateService` bean。

Hosted OpenAI tools 在 request/DTO 层会被接收，但默认 starter 不会自动执行 OpenAI hosted
services。如果需要真实执行，请把这些工具接入 AgentScope 或业务应用自己的 toolkit。

## 依赖

```xml
<dependency>
    <groupId>io.agentscope</groupId>
    <artifactId>agentscope-spring-boot-starter</artifactId>
</dependency>
<dependency>
    <groupId>io.agentscope</groupId>
    <artifactId>agentscope-responses-web-starter</artifactId>
    <version>${agentscope.version}</version>
</dependency>
```

## 配置

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

该 starter 依赖 `agentscope-spring-boot-starter` 提供 `ReActAgent` bean。每次请求通过
`ObjectProvider<ReActAgent>` 获取 fresh agent，response/conversation 状态由
`ResponsesStateService` 管理。

## Endpoints

Responses：

- `POST /v1/responses`
- `GET /v1/responses/{response_id}`
- `DELETE /v1/responses/{response_id}`
- `POST /v1/responses/{response_id}/cancel`
- `GET /v1/responses/{response_id}/input_items`
- `POST /v1/responses/input_tokens`
- `POST /v1/responses/input_tokens/count`
- `POST /v1/responses/compact`

Conversations：

- `POST /v1/conversations`
- `GET /v1/conversations/{conversation_id}`
- `POST /v1/conversations/{conversation_id}`
- `DELETE /v1/conversations/{conversation_id}`
- `GET /v1/conversations/{conversation_id}/items`
- `POST /v1/conversations/{conversation_id}/items`
- `GET /v1/conversations/{conversation_id}/items/{item_id}`
- `DELETE /v1/conversations/{conversation_id}/items/{item_id}`

列表 endpoint 支持 `after`、`limit`、`order=asc|desc`。

## 运行示例

启动示例应用：

```bash
export DASHSCOPE_API_KEY=your_key
mvn -pl agentscope-examples/responses-web -am -DskipTests package
java -jar agentscope-examples/responses-web/target/responses-web-1.1.0-SNAPSHOT.jar \
  --server.port=8080
```

如果用 IntelliJ IDEA 启动，在 Run Configuration 里配置 `DASHSCOPE_API_KEY`，并把
`--server.port=8080` 放到 program arguments。

下面示例不依赖 `jq`。可以用 `python3 -m json.tool` 格式化 JSON：

```bash
curl -s http://localhost:8080/v1/responses/resp_xxx | python3 -m json.tool
```

从保存的 JSON 文件里提取 ID：

```bash
python3 -c 'import json; print(json.load(open("/tmp/response.json"))["id"])'
```

## 核心示例

纯文本：

```bash
curl -s -X POST http://localhost:8080/v1/responses \
  -H 'Content-Type: application/json' \
  -d '{
    "model": "qwen3-max",
    "input": "Briefly introduce AgentScope Java.",
    "store": true
  }' | tee /tmp/response.json | python3 -m json.tool
```

正常结果：

- `object` 是 `response`
- `status` 是 `completed`
- `id` 以 `resp_` 开头
- 返回里有 `output` 和 `output_text`
- 顶层没有 `error`

查询 stored response：

```bash
RESP_ID=$(python3 -c 'import json; print(json.load(open("/tmp/response.json"))["id"])')
curl -s "http://localhost:8080/v1/responses/${RESP_ID}" | python3 -m json.tool
```

用 `previous_response_id` 接续上一轮：

```bash
curl -s -X POST http://localhost:8080/v1/responses \
  -H 'Content-Type: application/json' \
  -d "{
    \"model\": \"qwen3-max\",
    \"previous_response_id\": \"${RESP_ID}\",
    \"input\": \"Continue from the previous response in one sentence.\"
  }" | python3 -m json.tool
```

流式：

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

流式响应使用 Responses 风格 SSE events。健康的流通常包含 `response.created`、
`response.in_progress`、`response.output_item.added`、`response.output_text.delta`、
`response.output_text.done`、`response.output_item.done`、`response.completed` 等事件。
Responses stream 不发送 Chat Completions 风格的 `[DONE]`。

## Structured Output

非流式 JSON Schema output：

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

检查 `output_text` 是否为合法 JSON：

```bash
python3 - <<'PY'
import json
r = json.load(open("/tmp/schema-response.json"))
print("status =", r.get("status"))
print("output_text parsed =", json.loads(r["output_text"]))
PY
```

流式 JSON Schema output：

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

正常结果：stream 能到达 `response.completed`，text delta/done 中包含 JSON object。

## Background And State

创建 background response：

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

查询、取消和删除：

```bash
BG_ID=$(python3 -c 'import json; print(json.load(open("/tmp/background-response.json"))["id"])')
curl -s "http://localhost:8080/v1/responses/${BG_ID}" | python3 -m json.tool
curl -s -X POST "http://localhost:8080/v1/responses/${BG_ID}/cancel" | python3 -m json.tool
curl -s -X DELETE "http://localhost:8080/v1/responses/${BG_ID}" | python3 -m json.tool
```

正常结果：初始 response 是 `queued`；cancel 返回同一个 response 且 `status=cancelled`；
delete 返回 `deleted=true`。

列出 stored response 的 input items：

```bash
curl -s "http://localhost:8080/v1/responses/${RESP_ID}/input_items?limit=10&order=asc" \
  | python3 -m json.tool
```

统计 input tokens：

```bash
curl -s -X POST http://localhost:8080/v1/responses/input_tokens \
  -H 'Content-Type: application/json' \
  -d '{"input":"hello AgentScope"}' | python3 -m json.tool
```

压缩上下文：

```bash
curl -s -X POST http://localhost:8080/v1/responses/compact \
  -H 'Content-Type: application/json' \
  -d '{
    "model": "qwen3-max",
    "input": "Summarize this context in one sentence: AgentScope Java supports agents, tools, streaming, and structured output."
  }' | python3 -m json.tool
```

## Conversations

创建 conversation：

```bash
curl -s -X POST http://localhost:8080/v1/conversations \
  -H 'Content-Type: application/json' \
  -d '{"metadata":{"case":"manual-test"}}' \
  | tee /tmp/conversation.json | python3 -m json.tool
```

在 response 中使用 conversation：

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

列出 conversation items：

```bash
curl -s "http://localhost:8080/v1/conversations/${CONV_ID}/items?limit=10&order=asc" \
  | python3 -m json.tool
```

创建、查询和删除 conversation item：

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

request 里的 function tools 会注册为 schema-only tools。这是 client-side tool loop 模式：模型可能
返回 `function_call`，客户端执行工具，下一次请求再发送 `function_call_output`。

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

后端 Java tools 是另一种模式：业务应用用 `@Tool` 把真实 Java 方法注册到 `Toolkit`，然后让
`ReActAgent` 自动执行。使用后端 Java tools 时，不要在请求里发送同名 `tools`，因为 request-level
schema 是外部 schema-only tool。

```java
public class WeatherTools {
    @Tool(name = "get_weather", description = "Get weather for a city")
    public String getWeather(@ToolParam(name = "city", description = "City name") String city) {
        return city + " is sunny, 28C";
    }
}
```

image 和 audio 输入会转换成 AgentScope multimodal content blocks。端到端理解仍然取决于所选模型
和模型适配器。如果文本模型回复“无法查看图片”或“无法处理音频”，说明 API 层接收成功，但模型后端
没有提供多模态理解能力。

`file_id` 文件输入会作为文件引用被接收。生产环境如果要理解文件内容，业务应用需要提供上传、存储、
鉴权、解析，并在调用模型前把解析后的内容注入上下文。

## 错误检查

不支持的 text format：

```bash
curl -s -X POST http://localhost:8080/v1/responses \
  -H 'Content-Type: application/json' \
  -d '{
    "model": "qwen3-max",
    "input": "hello",
    "text": { "format": { "type": "json_object" } }
  }' | python3 -m json.tool
```

正常结果：返回结构化 error。

不存在的 stored response：

```bash
curl -s -X POST http://localhost:8080/v1/responses \
  -H 'Content-Type: application/json' \
  -d '{
    "model": "qwen3-max",
    "previous_response_id": "resp_not_found",
    "input": "hello"
  }' | python3 -m json.tool
```

正常结果：返回结构化 not-found error，而不是 unsupported-parameter error。

## 验证

```bash
mvn -pl agentscope-extensions/agentscope-extensions-responses-web -am \
  -Dtest='io.agentscope.core.responses.**' \
  -DfailIfNoTests=false -DfailIfNoSpecifiedTests=false test

mvn -pl agentscope-extensions/agentscope-spring-boot-starters/agentscope-responses-web-starter -am \
  -Dtest='io.agentscope.spring.boot.responses.**' \
  -DfailIfNoTests=false -DfailIfNoSpecifiedTests=false test

mvn -pl agentscope-examples/responses-web -am -DskipTests package
```

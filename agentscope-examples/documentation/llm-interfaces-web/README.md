# LLM Interfaces Web Example

This Spring Boot example exposes one AgentScope `ReActAgent` through three stateless APIs:

- `POST /v1/chat/completions`
- `POST /v1/responses`
- `POST /v1/messages`
- `GET /v1/models`

It uses an in-process fake model so the app can be started without external API keys.

```bash
mvn -pl agentscope-examples/documentation/llm-interfaces-web -am spring-boot:run
```

Try the OpenAI Chat Completions endpoint:

```bash
curl -X POST http://localhost:8080/v1/chat/completions \
  -H 'Content-Type: application/json' \
  -d '{"model":"demo-agent","messages":[{"role":"user","content":"hello from chat"}]}'
```

Try the OpenAI Responses endpoint:

```bash
curl -X POST http://localhost:8080/v1/responses \
  -H 'Content-Type: application/json' \
  -d '{"model":"demo-agent","input":"hello from responses"}'
```

Try the Anthropic Messages endpoint:

```bash
curl -X POST http://localhost:8080/v1/messages \
  -H 'Content-Type: application/json' \
  -d '{"model":"demo-agent","max_tokens":128,"messages":[{"role":"user","content":"hello from anthropic"}]}'
```

For streaming, add `"stream": true` and use `curl -N`.

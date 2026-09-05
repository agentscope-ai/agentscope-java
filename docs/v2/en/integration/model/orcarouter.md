# OrcaRouter Model

`agentscope-extensions-model-openai` provides first-class [OrcaRouter](https://www.orcarouter.ai) support through the OpenAI-compatible model stack. Add the OpenAI model extension module, then use `orcarouter:<model>` with `ModelRegistry`.

OrcaRouter is an OpenAI-compatible AI gateway that exposes a provider/model namespace across many models — like OpenRouter — but also combines adaptive routing, automatic failover, zero-markup inference, observability, guardrails, and agent-tool governance behind the same endpoint. It runs gateway-level, zero-trust security for AI agents on the same endpoint, screening every prompt/response and governing every tool call on a default-deny basis, with no application code changes.

## Add the dependency

```xml
<dependency>
    <groupId>io.agentscope</groupId>
    <artifactId>agentscope-extensions-model-openai</artifactId>
    <version>${agentscope.version}</version>
</dependency>
```

## ModelRegistry

Set `ORCAROUTER_API_KEY`, then use the `orcarouter:<model>` id:

```java
ReActAgent agent = ReActAgent.builder()
    .name("assistant")
    .model("orcarouter:openai/gpt-5.5") // Resolved internally by ModelRegistry.resolve(modelId)
    .build();
```

The provider defaults to `https://api.orcarouter.ai/v1`, strips the `orcarouter:` prefix before sending the model name, and uses the standard OpenAI chat formatter from `io.agentscope.extensions.model.openai.formatter`. Model names use the `provider/model` namespace, e.g. `openai/gpt-5.5`, `anthropic/claude-opus-4.8`, `deepseek/deepseek-v4-pro`, or the `orcarouter/auto` adaptive router. See the full catalog at <https://www.orcarouter.ai/models>.

## Compatibility notes

Because OrcaRouter routes to a mix of upstream providers whose `response_format` support varies, native structured output defaults to disabled and the agent falls back to the normal AgentScope `generate_response` tool. If you configure a route that reliably supports `json_schema`, pass `nativeStructuredOutput` or a formatter override through `ModelCreationContext`.

For self-hosted or custom gateways, pass `baseUrl`, `endpointPath`, generation options, or formatter overrides through `ModelCreationContext`.

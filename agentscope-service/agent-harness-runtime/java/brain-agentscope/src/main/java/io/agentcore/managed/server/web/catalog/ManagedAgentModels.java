/*
 * Copyright 2024-2026 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package io.agentcore.managed.server.web.catalog;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.openai.core.JsonValue;
import io.agentcore.managed.harness.runtime.CanonicalJson;
import io.agentcore.managed.harness.runtime.ModelConfigurationContract;
import io.agentcore.managed.harness.runtime.RunConfigurationContract;
import io.agentcore.managed.harness.runtime.SessionServiceClient;
import io.agentcore.managed.server.control.SessionResolveResult;
import io.agentcore.managed.server.runtime.managed.ManagedTurnExecution;
import io.agentcore.managed.server.web.catalog.spec.EffectiveAgentDefinition;
import io.agentcore.managed.server.web.managed.SessionAgentBuildSpec;
import io.agentscope.core.message.Msg;
import io.agentscope.core.message.ThinkingBlock;
import io.agentscope.core.model.ChatResponse;
import io.agentscope.core.model.ExecutionConfig;
import io.agentscope.core.model.GenerateOptions;
import io.agentscope.core.model.Model;
import io.agentscope.core.model.ToolChoice;
import io.agentscope.core.model.ToolSchema;
import io.agentscope.extensions.model.anthropic.AnthropicChatModel;
import io.agentscope.extensions.model.openai.OpenAIChatModel;
import io.agentscope.extensions.model.openaiofficial.OpenAIResponsesChatModel;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.function.Function;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Flux;

/** Resolves immutable service bindings; provider clients and credentials remain instance-local. */
@Component
public final class ManagedAgentModels {
    private static final ObjectMapper SCHEMA_JSON = new ObjectMapper();
    private final SessionServiceClient client;
    private final ObjectMapper mapper;
    private final Function<String, String> credentials;

    @Autowired
    public ManagedAgentModels(SessionServiceClient client, ObjectMapper mapper) {
        this(client, mapper, System::getenv);
    }

    public ManagedAgentModels(
            SessionServiceClient client,
            ObjectMapper mapper,
            Function<String, String> credentials) {
        this.client = client;
        this.mapper = mapper;
        this.credentials = credentials;
    }

    public JsonNode resolve(
            ManagedTurnExecution execution,
            SessionResolveResult resolved,
            SessionAgentBuildSpec spec) {
        execution.check();
        var envelope =
                client.post(
                        "prepared/coordination",
                        Map.of(
                                "protocolVersion",
                                "0.1.0",
                                "operation",
                                "model-configuration",
                                "data",
                                Map.of(
                                        "scope",
                                        execution.scope(),
                                        "turnId",
                                        execution.turnId(),
                                        "epoch",
                                        execution.epoch(),
                                        "token",
                                        execution.owner().token())));
        if (!"0.1.0".equals(envelope.path("protocolVersion").asText()))
            throw new IllegalArgumentException("Unsupported model binding protocol version");
        var response = envelope.required("result");
        validate(response, execution, resolved, spec);
        execution.check();
        return response.required("configuration");
    }

    private void validate(
            JsonNode response,
            ManagedTurnExecution execution,
            SessionResolveResult resolved,
            SessionAgentBuildSpec spec) {
        RunConfigurationContract.validateModelBinding(response);
        var identity = response.required("identity");
        if (!mapper.valueToTree(execution.scope()).equals(response.required("scope"))
                || !execution.turnId().equals(response.required("turnId").asText())
                || !"agentscope-java".equals(identity.required("harnessType").asText())
                || !resolved.session().agentId().equals(identity.required("agentId").asText())
                || resolved.session().agentVersion() != identity.required("agentVersion").asInt()
                || !CanonicalJson.hash(mapper.valueToTree(resolved.agentSnapshot()))
                        .equals(identity.required("sourceDigest").asText())
                || !CanonicalJson.hash(
                                mapper.valueToTree(
                                        EffectiveAgentDefinition.resolve(
                                                resolved.agentSnapshot(), spec.overridesJson())))
                        .equals(identity.required("effectiveDigest").asText()))
            throw new IllegalArgumentException(
                    "Model binding does not match the effective Agent definition");
    }

    public Model create(JsonNode binding) {
        ModelConfigurationContract.validate(binding, "agentscope-java");
        String key = credentials.apply(binding.required("credentialEnv").asText());
        if (key == null || key.isBlank())
            throw new IllegalArgumentException("Managed model credential is unavailable");
        String id = binding.required("id").asText();
        var defaults = options(binding, null);
        Model delegate;
        if ("openai-completions".equals(binding.required("api").asText())) {
            var builder =
                    OpenAIChatModel.builder().modelName(id).apiKey(key).stream(true)
                            .generateOptions(defaults);
            if (binding.has("baseUrl")) builder.baseUrl(binding.get("baseUrl").asText());
            if (binding.has("contextWindow"))
                builder.contextWindowSize(binding.get("contextWindow").asInt());
            delegate = builder.build();
        } else if ("openai-responses".equals(binding.required("api").asText())) {
            var builder =
                    OpenAIResponsesChatModel.builder().modelName(id).apiKey(key).stream(true)
                            .generateOptions(defaults);
            if (binding.has("baseUrl")) builder.baseUrl(binding.get("baseUrl").asText());
            if (binding.has("contextWindow"))
                builder.contextWindowSize(binding.get("contextWindow").asInt());
            delegate = builder.build();
        } else {
            var builder =
                    AnthropicChatModel.builder().modelName(id).apiKey(key).stream(true)
                            .defaultOptions(defaults);
            if (binding.has("baseUrl")) builder.baseUrl(binding.get("baseUrl").asText());
            if (binding.has("contextWindow"))
                builder.contextWindowSize(binding.get("contextWindow").asInt());
            delegate = builder.build();
        }
        JsonNode sealed = binding.deepCopy();
        return new Model() {
            @Override
            public Flux<ChatResponse> stream(
                    List<Msg> messages, List<ToolSchema> tools, GenerateOptions requested) {
                if (requested != null
                        && (requested.getApiKey() != null
                                || requested.getBaseUrl() != null
                                || requested.getEndpointPath() != null
                                || requested.getModelName() != null))
                    return Flux.error(
                            new IllegalArgumentException(
                                    "Bound model forbids request routing overrides"));
                var responses =
                        delegate.stream(
                                responsesHistory(sealed, messages),
                                responsesTools(sealed, tools),
                                options(sealed, requested));
                return "openai-responses".equals(sealed.required("api").asText())
                        ? stableResponsesIdentity(responses)
                        : responses;
            }

            @Override
            public String getModelName() {
                return delegate.getModelName();
            }

            @Override
            public int getContextWindowSize() {
                return delegate.getContextWindowSize();
            }

            @Override
            public boolean supportsNativeStructuredOutput() {
                return delegate.supportsNativeStructuredOutput();
            }

            @Override
            public boolean supportsNativeStructuredOutputWithTools() {
                return delegate.supportsNativeStructuredOutputWithTools();
            }
        };
    }

    /**
     * The Responses SDK gives deltas fresh IDs and uses the provider ID only on the terminal chunk.
     * Assign one identity per subscription before both preview and native accumulation consume it.
     * Provider response/reasoning metadata and tool-call IDs remain intact for subsequent requests.
     */
    static Flux<ChatResponse> stableResponsesIdentity(Flux<ChatResponse> responses) {
        return Flux.defer(
                () -> {
                    String messageId = UUID.randomUUID().toString();
                    return responses.map(
                            chunk ->
                                    ChatResponse.builder()
                                            .id(messageId)
                                            .content(chunk.getContent())
                                            .usage(chunk.getUsage())
                                            .metadata(chunk.getMetadata())
                                            .finishReason(chunk.getFinishReason())
                                            .build());
                });
    }

    /** SDK Map conversion omits null values; JSON Schema defaults and const must retain them. */
    static List<ToolSchema> responsesTools(JsonNode binding, List<ToolSchema> tools) {
        if (!"openai-responses".equals(binding.path("api").asText()) || tools == null) return tools;
        return tools.stream()
                .map(
                        tool ->
                                ToolSchema.builder()
                                        .name(tool.getName())
                                        .description(tool.getDescription())
                                        .parameters(responseSchema(tool.getParameters()))
                                        .outputSchema(responseSchema(tool.getOutputSchema()))
                                        .strict(tool.getStrict())
                                        .deferLoading(tool.getDeferLoading())
                                        .build())
                .toList();
    }

    private static Map<String, Object> responseSchema(Map<String, Object> schema) {
        if (schema == null) return null;
        var result = new LinkedHashMap<String, Object>();
        schema.forEach(
                (key, value) ->
                        result.put(key, JsonValue.fromJsonNode(SCHEMA_JSON.valueToTree(value))));
        return result;
    }

    /** Keep durable history intact; Qwen plaintext reasoning is not an OpenAI replay token. */
    static List<Msg> responsesHistory(JsonNode binding, List<Msg> messages) {
        if (!"dashscope".equals(binding.path("provider").asText())
                || !"openai-responses".equals(binding.path("api").asText())) return messages;
        var result = new ArrayList<Msg>();
        for (var message : messages) {
            var content =
                    message.getContent().stream()
                            .filter(
                                    block ->
                                            !(block instanceof ThinkingBlock thinking)
                                                    || thinking.getMetadata() != null
                                                            && thinking.getMetadata()
                                                                            .get(
                                                                                    "openai.reasoning.encrypted_content")
                                                                    instanceof String encrypted
                                                            && !encrypted.isBlank())
                            .toList();
            if (content.size() == message.getContent().size()) result.add(message);
            else if (!content.isEmpty()) result.add(message.withContent(content));
        }
        return result;
    }

    public static GenerateOptions options(JsonNode binding, GenerateOptions requested) {
        if (requested != null) {
            if (requested.getReasoningEffort() != null
                    && ("anthropic-messages".equals(binding.path("api").asText())
                            || binding.has("reasoning") && !binding.get("reasoning").asBoolean()))
                throw new IllegalArgumentException(
                        "Bound model does not support requested reasoning effort");
            var extras = requested.getAdditionalBodyParams();
            var reserved =
                    Set.of(
                            "model",
                            "temperature",
                            "top_p",
                            "tool_choice",
                            "parallel_tool_calls",
                            "max_tokens",
                            "max_completion_tokens",
                            "max_output_tokens",
                            "reasoning",
                            "reasoning_effort",
                            "thinking",
                            "previous_response_id",
                            "store",
                            "stream");
            if (extras != null
                    && extras.keySet().stream()
                            .anyMatch(
                                    name ->
                                            reserved.contains(name)
                                                    || name.startsWith("reasoning.")))
                throw new IllegalArgumentException(
                        "Bound model forbids additional parameter overrides");
            var headers = requested.getAdditionalHeaders();
            if (headers != null
                    && headers.keySet().stream()
                            .anyMatch(
                                    name ->
                                            "authorization".equalsIgnoreCase(name)
                                                    || "x-api-key".equalsIgnoreCase(name)))
                throw new IllegalArgumentException(
                        "Bound model forbids credential header overrides");
        }
        var parameters = binding.path("parameters");
        var builder = GenerateOptions.builder();
        // Session Service owns recovery; one admitted model call must not hide extra attempts.
        var execution = ExecutionConfig.builder().maxAttempts(1);
        if (binding.has("requestTimeoutSeconds"))
            execution.timeout(Duration.ofSeconds(binding.get("requestTimeoutSeconds").asLong()));
        builder.executionConfig(
                ExecutionConfig.mergeConfigs(
                        execution.build(),
                        requested == null ? null : requested.getExecutionConfig()));
        if (parameters.has("temperature"))
            builder.temperature(parameters.get("temperature").asDouble());
        if (parameters.has("topP")) builder.topP(parameters.get("topP").asDouble());
        if (parameters.has("parallelToolCalls"))
            builder.parallelToolCalls(parameters.get("parallelToolCalls").asBoolean());
        if (parameters.has("toolChoice"))
            builder.toolChoice(
                    switch (parameters.get("toolChoice").asText()) {
                        case "none" -> new ToolChoice.None();
                        case "required" -> new ToolChoice.Required();
                        default -> new ToolChoice.Auto();
                    });
        if (parameters.has("reasoning"))
            builder.reasoningEffort(parameters.get("reasoning").asText());
        JsonNode cap =
                parameters.has("maxTokens")
                        ? parameters.get("maxTokens")
                        : binding.get("outputLimit");
        if (cap != null) {
            int limit = cap.asInt();
            if (requested != null && requested.getMaxTokens() != null)
                limit = Math.min(limit, requested.getMaxTokens());
            if (requested != null && requested.getMaxCompletionTokens() != null)
                limit = Math.min(limit, requested.getMaxCompletionTokens());
            builder.maxTokens(limit);
            if (requested != null && requested.getMaxCompletionTokens() != null)
                builder.maxCompletionTokens(limit);
        }
        return GenerateOptions.mergeOptions(builder.build(), requested);
    }
}

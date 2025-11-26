package io.agentscope.core.tracing;

import static io.agentscope.core.tracing.AgentScopeIncubatingAttributes.GenAiProviderNameAgentScopeIncubatingValues.DASHSCOPE;
import static io.agentscope.core.tracing.AgentScopeIncubatingAttributes.GenAiProviderNameAgentScopeIncubatingValues.MOONSHOT;
import static io.agentscope.core.tracing.GenAiIncubatingAttributes.GEN_AI_CONVERSATION_ID;
import static io.agentscope.core.tracing.GenAiIncubatingAttributes.GEN_AI_INPUT_MESSAGES;
import static io.agentscope.core.tracing.GenAiIncubatingAttributes.GEN_AI_OPERATION_NAME;
import static io.agentscope.core.tracing.GenAiIncubatingAttributes.GEN_AI_OUTPUT_MESSAGES;
import static io.agentscope.core.tracing.GenAiIncubatingAttributes.GEN_AI_PROVIDER_NAME;
import static io.agentscope.core.tracing.GenAiIncubatingAttributes.GEN_AI_REQUEST_FREQUENCY_PENALTY;
import static io.agentscope.core.tracing.GenAiIncubatingAttributes.GEN_AI_REQUEST_MAX_TOKENS;
import static io.agentscope.core.tracing.GenAiIncubatingAttributes.GEN_AI_REQUEST_MODEL;
import static io.agentscope.core.tracing.GenAiIncubatingAttributes.GEN_AI_REQUEST_PRESENCE_PENALTY;
import static io.agentscope.core.tracing.GenAiIncubatingAttributes.GEN_AI_REQUEST_SEED;
import static io.agentscope.core.tracing.GenAiIncubatingAttributes.GEN_AI_REQUEST_TEMPERATURE;
import static io.agentscope.core.tracing.GenAiIncubatingAttributes.GEN_AI_REQUEST_TOP_K;
import static io.agentscope.core.tracing.GenAiIncubatingAttributes.GEN_AI_REQUEST_TOP_P;
import static io.agentscope.core.tracing.GenAiIncubatingAttributes.GEN_AI_RESPONSE_FINISH_REASONS;
import static io.agentscope.core.tracing.GenAiIncubatingAttributes.GEN_AI_RESPONSE_ID;
import static io.agentscope.core.tracing.GenAiIncubatingAttributes.GEN_AI_TOOL_DEFINITIONS;
import static io.agentscope.core.tracing.GenAiIncubatingAttributes.GEN_AI_USAGE_INPUT_TOKENS;
import static io.agentscope.core.tracing.GenAiIncubatingAttributes.GEN_AI_USAGE_OUTPUT_TOKENS;
import static io.agentscope.core.tracing.GenAiIncubatingAttributes.GenAiOperationNameIncubatingValues.CHAT;
import static io.agentscope.core.tracing.GenAiIncubatingAttributes.GenAiProviderNameIncubatingValues.AWS_BEDROCK;
import static io.agentscope.core.tracing.GenAiIncubatingAttributes.GenAiProviderNameIncubatingValues.AZURE_AI_OPENAI;
import static io.agentscope.core.tracing.GenAiIncubatingAttributes.GenAiProviderNameIncubatingValues.DEEPSEEK;
import static io.agentscope.core.tracing.GenAiIncubatingAttributes.GenAiProviderNameIncubatingValues.GCP_GEMINI;
import static io.agentscope.core.tracing.GenAiIncubatingAttributes.GenAiProviderNameIncubatingValues.OPENAI;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.agentscope.core.message.ContentBlock;
import io.agentscope.core.message.Msg;
import io.agentscope.core.message.MsgRole;
import io.agentscope.core.message.TextBlock;
import io.agentscope.core.message.ThinkingBlock;
import io.agentscope.core.message.ToolResultBlock;
import io.agentscope.core.message.ToolUseBlock;
import io.agentscope.core.model.ChatModelBase;
import io.agentscope.core.model.ChatResponse;
import io.agentscope.core.model.DashScopeChatModel;
import io.agentscope.core.model.GeminiChatModel;
import io.agentscope.core.model.GenerateOptions;
import io.agentscope.core.model.OpenAIChatModel;
import io.agentscope.core.model.ToolSchema;
import io.agentscope.core.studio.StudioManager;
import io.agentscope.core.tracing.model.InputMessage;
import io.agentscope.core.tracing.model.MessagePart;
import io.agentscope.core.tracing.model.OutputMessage;
import io.agentscope.core.tracing.model.ReasoningPart;
import io.agentscope.core.tracing.model.Role;
import io.agentscope.core.tracing.model.TextPart;
import io.agentscope.core.tracing.model.ToolCallRequestPart;
import io.agentscope.core.tracing.model.ToolCallResponsePart;
import io.agentscope.core.tracing.model.ToolDefinition;
import io.opentelemetry.api.common.AttributeKey;
import io.opentelemetry.api.common.Attributes;
import io.opentelemetry.api.common.AttributesBuilder;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Objects;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

final class AttributesExtractors {

    private static final Logger LOGGER = LoggerFactory.getLogger(AttributesExtractors.class);

    private static final ObjectMapper MARSHALER = new ObjectMapper();

    /**
     * Get LLM request attributes for OpenTelemetry tracing.
     *
     * <p>Extracts request parameters from LLM model calls into GenAI attributes.
     *
     * @param instance ChatModelBase instance making the request
     * @param inputMessages Input messages
     * @param toolSchemas Tool definitions of model invocation
     * @param options Generation parameters
     * @return Attributes for LLM request
     * */
    static Attributes getLLMRequestAttributes(
            ChatModelBase instance,
            List<Msg> inputMessages,
            List<ToolSchema> toolSchemas,
            GenerateOptions options) {
        AttributesBuilder builder = Attributes.builder();
        internalSet(builder, GEN_AI_OPERATION_NAME, CHAT);
        internalSet(builder, GEN_AI_PROVIDER_NAME, ProviderNameConverter.getProviderName(instance));
        internalSet(builder, GEN_AI_REQUEST_MODEL, instance.getModelName());
        internalSet(builder, GEN_AI_REQUEST_TEMPERATURE, options.getTemperature());
        internalSet(builder, GEN_AI_REQUEST_TOP_P, options.getTopP());
        Object topK = options.getAdditionalOption("top_k");
        internalSet(
                builder,
                GEN_AI_REQUEST_TOP_K,
                topK instanceof Integer ? ((Integer) topK).doubleValue() : null);
        internalSet(
                builder,
                GEN_AI_REQUEST_MAX_TOKENS,
                options.getMaxTokens() == null ? null : options.getMaxTokens().longValue());
        internalSet(builder, GEN_AI_REQUEST_PRESENCE_PENALTY, options.getPresencePenalty());
        internalSet(builder, GEN_AI_REQUEST_FREQUENCY_PENALTY, options.getFrequencyPenalty());
        // stop_sequences is not supported now
        Object seed = options.getAdditionalOption("seed");
        internalSet(
                builder,
                GEN_AI_REQUEST_SEED,
                seed instanceof Integer ? ((Integer) seed).longValue() : null);
        internalSet(
                builder,
                GEN_AI_REQUEST_MAX_TOKENS,
                options.getMaxTokens() == null ? null : options.getMaxTokens().longValue());

        // TODO: capture agentscope function information

        internalSet(builder, GEN_AI_INPUT_MESSAGES, getInputMessages(inputMessages));
        internalSet(builder, GEN_AI_TOOL_DEFINITIONS, getToolDefinitions(toolSchemas));
        return builder.build();
    }

    static Attributes getLLMResponseAttributes(ChatResponse response) {
        AttributesBuilder builder = Attributes.builder();
        if (response.getFinishReason() != null) {
            internalSet(
                    builder,
                    GEN_AI_RESPONSE_FINISH_REASONS,
                    Collections.singletonList(response.getFinishReason()));
        }
        internalSet(builder, GEN_AI_RESPONSE_ID, response.getId());
        internalSet(
                builder, GEN_AI_USAGE_INPUT_TOKENS, (long) response.getUsage().getInputTokens());
        internalSet(
                builder, GEN_AI_USAGE_OUTPUT_TOKENS, (long) response.getUsage().getOutputTokens());
        internalSet(builder, GEN_AI_OUTPUT_MESSAGES, getOutputMessages(response));
        return builder.build();
    }

    static String getFunctionName(Object instance, String methodName) {
        return instance.getClass().getSimpleName() + "." + methodName;
    }

    static Attributes getCommonAttributes() {
        AttributesBuilder builder = Attributes.builder();
        if (StudioManager.isInitialized()) {
            internalSet(builder, GEN_AI_CONVERSATION_ID, StudioManager.getConfig().getRunId());
        }
        return builder.build();
    }

    private static <T> void internalSet(
            AttributesBuilder builder, AttributeKey<T> attributeKey, T value) {
        if (value != null) {
            builder.put(attributeKey, value);
        }
    }

    private static String getInputMessages(List<Msg> messages) {
        if (messages == null || messages.isEmpty()) {
            return null;
        }

        List<InputMessage> inputMessages =
                messages.stream()
                        .map(
                                msg -> {
                                    List<ContentBlock> contents = msg.getContent();
                                    List<MessagePart> parts = new ArrayList<>(contents.size());
                                    for (ContentBlock content : contents) {
                                        if (content instanceof TextBlock textBlock) {
                                            parts.add(TextPart.create(textBlock.getText()));
                                        } else if (content instanceof ThinkingBlock thinkingBlock) {
                                            parts.add(
                                                    ReasoningPart.create(
                                                            thinkingBlock.getThinking()));
                                        } else if (content instanceof ToolUseBlock toolUseBlock) {
                                            parts.add(
                                                    ToolCallRequestPart.create(
                                                            toolUseBlock.getId(),
                                                            toolUseBlock.getName(),
                                                            toolUseBlock.getContent()));
                                        } else if (content
                                                instanceof ToolResultBlock toolResultBlock) {
                                            parts.add(
                                                    ToolCallResponsePart.create(
                                                            toolResultBlock.getId(),
                                                            toolResultBlock.getOutput()));
                                        }
                                        // TODO: support multi modal content
                                    }
                                    return InputMessage.create(
                                            getRole(msg.getRole()), parts, msg.getName());
                                })
                        .toList();

        try {
            return MARSHALER.writeValueAsString(inputMessages);
        } catch (JsonProcessingException e) {
            LOGGER.warn("Failed to serialize input messages, due to: {}", e.getMessage());
            return null;
        }
    }

    private static String getToolDefinitions(List<ToolSchema> toolSchemas) {
        if (toolSchemas == null || toolSchemas.isEmpty()) {
            return null;
        }

        List<ToolDefinition> toolDefinitions =
                toolSchemas.stream()
                        .map(
                                toolSchema ->
                                        ToolDefinition.create(
                                                "function",
                                                toolSchema.getName(),
                                                toolSchema.getDescription(),
                                                toolSchema.getParameters()))
                        .toList();
        try {
            return MARSHALER.writeValueAsString(toolDefinitions);
        } catch (JsonProcessingException e) {
            LOGGER.warn("Failed to serialize tool definitions, due to: {}", e.getMessage());
            return null;
        }
    }

    private static String getOutputMessages(ChatResponse response) {
        if (response == null || response.getContent() == null) {
            return null;
        }

        List<MessagePart> parts =
                response.getContent().stream()
                        .map(
                                content -> {
                                    if (content instanceof TextBlock textBlock) {
                                        return TextPart.create(textBlock.getText());
                                    } else if (content instanceof ThinkingBlock thinkingBlock) {
                                        return ReasoningPart.create(thinkingBlock.getThinking());
                                    } else if (content instanceof ToolUseBlock toolUseBlock) {
                                        return ToolCallRequestPart.create(
                                                toolUseBlock.getId(),
                                                toolUseBlock.getName(),
                                                toolUseBlock.getContent());
                                    } else if (content instanceof ToolResultBlock toolResultBlock) {
                                        return ToolCallResponsePart.create(
                                                toolResultBlock.getId(),
                                                toolResultBlock.getOutput());
                                    }
                                    // TODO: support multi modal content
                                    return null;
                                })
                        .filter(Objects::nonNull)
                        .toList();

        List<OutputMessage> outputMessages =
                Collections.singletonList(
                        OutputMessage.create(
                                Role.ASSISTANT, parts, null, response.getFinishReason()));

        try {
            return MARSHALER.writeValueAsString(outputMessages);
        } catch (JsonProcessingException e) {
            LOGGER.warn("Failed to serialize output messages, due to: {}", e.getMessage());
            return null;
        }
    }

    private static String getRole(MsgRole msgRole) {
        return switch (msgRole) {
            case ASSISTANT -> Role.ASSISTANT.getValue();
            case TOOL -> Role.TOOL.getValue();
            case SYSTEM -> Role.SYSTEM.getValue();
            default -> Role.USER.getValue();
        };
    }

    private static final class ProviderNameConverter {

        private static final List<BaseUrlMapper> BASE_URL_MAPPERS;

        static {
            BASE_URL_MAPPERS = new ArrayList<>();
            BASE_URL_MAPPERS.add(BaseUrlMapper.of("api.openai.com", OPENAI));
            BASE_URL_MAPPERS.add(BaseUrlMapper.of("dashscope", DASHSCOPE));
            BASE_URL_MAPPERS.add(BaseUrlMapper.of("deepseek", DEEPSEEK));
            BASE_URL_MAPPERS.add(BaseUrlMapper.of("moonshot", MOONSHOT));
            BASE_URL_MAPPERS.add(BaseUrlMapper.of("generativelanguage.googleapis.com", GCP_GEMINI));
            BASE_URL_MAPPERS.add(BaseUrlMapper.of("openai.azure.com", AZURE_AI_OPENAI));
            BASE_URL_MAPPERS.add(BaseUrlMapper.of("amazonaws.com", AWS_BEDROCK));
        }

        static String getProviderName(ChatModelBase instance) {
            if (instance instanceof DashScopeChatModel) {
                return DASHSCOPE;
            } else if (instance instanceof GeminiChatModel) {
                return GCP_GEMINI;
            } else if (instance instanceof OpenAIChatModel) {
                String baseUrl = ((OpenAIChatModel) instance).getBaseUrl();
                if (baseUrl == null || baseUrl.isEmpty()) {
                    return OPENAI;
                }
                for (BaseUrlMapper mapper : BASE_URL_MAPPERS) {
                    if (baseUrl.contains(mapper.baseUrlFragment)) {
                        return mapper.providerName;
                    }
                }
            }
            return "unknown";
        }

        private static class BaseUrlMapper {
            final String baseUrlFragment;
            final String providerName;

            static BaseUrlMapper of(String baseUrlFragment, String providerName) {
                return new BaseUrlMapper(baseUrlFragment, providerName);
            }

            private BaseUrlMapper(String baseUrlFragment, String providerName) {
                this.baseUrlFragment = baseUrlFragment;
                this.providerName = providerName;
            }
        }
    }

    private AttributesExtractors() {}
}

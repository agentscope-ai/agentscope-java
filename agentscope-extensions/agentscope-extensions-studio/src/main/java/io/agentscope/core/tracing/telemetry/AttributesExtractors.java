/*
 * Copyright 2024-2026 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package io.agentscope.core.tracing.telemetry;

import static io.agentscope.core.tracing.telemetry.AgentScopeIncubatingAttributes.AGENTSCOPE_FORMAT_TARGET;
import static io.agentscope.core.tracing.telemetry.AgentScopeIncubatingAttributes.AGENTSCOPE_FUNCTION_INPUT;
import static io.agentscope.core.tracing.telemetry.AgentScopeIncubatingAttributes.AGENTSCOPE_FUNCTION_OUTPUT;
import static io.agentscope.core.tracing.telemetry.AgentScopeIncubatingAttributes.GenAiOperationNameAgentScopeIncubatingValues.FORMAT;
import static io.agentscope.core.tracing.telemetry.AgentScopeIncubatingAttributes.GenAiProviderNameAgentScopeIncubatingValues.DASHSCOPE;
import static io.agentscope.core.tracing.telemetry.GenAiIncubatingAttributes.GEN_AI_AGENT_DESCRIPTION;
import static io.agentscope.core.tracing.telemetry.GenAiIncubatingAttributes.GEN_AI_AGENT_ID;
import static io.agentscope.core.tracing.telemetry.GenAiIncubatingAttributes.GEN_AI_AGENT_NAME;
import static io.agentscope.core.tracing.telemetry.GenAiIncubatingAttributes.GEN_AI_CONVERSATION_ID;
import static io.agentscope.core.tracing.telemetry.GenAiIncubatingAttributes.GEN_AI_INPUT_MESSAGES;
import static io.agentscope.core.tracing.telemetry.GenAiIncubatingAttributes.GEN_AI_OPERATION_NAME;
import static io.agentscope.core.tracing.telemetry.GenAiIncubatingAttributes.GEN_AI_OUTPUT_MESSAGES;
import static io.agentscope.core.tracing.telemetry.GenAiIncubatingAttributes.GEN_AI_PROVIDER_NAME;
import static io.agentscope.core.tracing.telemetry.GenAiIncubatingAttributes.GEN_AI_REQUEST_FREQUENCY_PENALTY;
import static io.agentscope.core.tracing.telemetry.GenAiIncubatingAttributes.GEN_AI_REQUEST_MAX_TOKENS;
import static io.agentscope.core.tracing.telemetry.GenAiIncubatingAttributes.GEN_AI_REQUEST_MODEL;
import static io.agentscope.core.tracing.telemetry.GenAiIncubatingAttributes.GEN_AI_REQUEST_PRESENCE_PENALTY;
import static io.agentscope.core.tracing.telemetry.GenAiIncubatingAttributes.GEN_AI_REQUEST_SEED;
import static io.agentscope.core.tracing.telemetry.GenAiIncubatingAttributes.GEN_AI_REQUEST_TEMPERATURE;
import static io.agentscope.core.tracing.telemetry.GenAiIncubatingAttributes.GEN_AI_REQUEST_TOP_K;
import static io.agentscope.core.tracing.telemetry.GenAiIncubatingAttributes.GEN_AI_REQUEST_TOP_P;
import static io.agentscope.core.tracing.telemetry.GenAiIncubatingAttributes.GEN_AI_RESPONSE_FINISH_REASONS;
import static io.agentscope.core.tracing.telemetry.GenAiIncubatingAttributes.GEN_AI_RESPONSE_ID;
import static io.agentscope.core.tracing.telemetry.GenAiIncubatingAttributes.GEN_AI_TOOL_CALL_ARGUMENTS;
import static io.agentscope.core.tracing.telemetry.GenAiIncubatingAttributes.GEN_AI_TOOL_CALL_ID;
import static io.agentscope.core.tracing.telemetry.GenAiIncubatingAttributes.GEN_AI_TOOL_CALL_RESULT;
import static io.agentscope.core.tracing.telemetry.GenAiIncubatingAttributes.GEN_AI_TOOL_DEFINITIONS;
import static io.agentscope.core.tracing.telemetry.GenAiIncubatingAttributes.GEN_AI_TOOL_DESCRIPTION;
import static io.agentscope.core.tracing.telemetry.GenAiIncubatingAttributes.GEN_AI_TOOL_NAME;
import static io.agentscope.core.tracing.telemetry.GenAiIncubatingAttributes.GEN_AI_USAGE_INPUT_TOKENS;
import static io.agentscope.core.tracing.telemetry.GenAiIncubatingAttributes.GEN_AI_USAGE_OUTPUT_TOKENS;
import static io.agentscope.core.tracing.telemetry.GenAiIncubatingAttributes.GenAiOperationNameIncubatingValues.CHAT;
import static io.agentscope.core.tracing.telemetry.GenAiIncubatingAttributes.GenAiOperationNameIncubatingValues.EXECUTE_TOOL;
import static io.agentscope.core.tracing.telemetry.GenAiIncubatingAttributes.GenAiOperationNameIncubatingValues.INVOKE_AGENT;
import static io.agentscope.core.tracing.telemetry.GenAiIncubatingAttributes.GenAiProviderNameIncubatingValues.ANTHROPIC;
import static io.agentscope.core.tracing.telemetry.GenAiIncubatingAttributes.GenAiProviderNameIncubatingValues.GCP_GEMINI;
import static io.agentscope.core.tracing.telemetry.GenAiIncubatingAttributes.GenAiProviderNameIncubatingValues.OPENAI;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.agentscope.core.agent.AgentBase;
import io.agentscope.core.formatter.AbstractBaseFormatter;
import io.agentscope.core.formatter.anthropic.AnthropicChatFormatter;
import io.agentscope.core.formatter.anthropic.AnthropicMultiAgentFormatter;
import io.agentscope.core.formatter.dashscope.DashScopeChatFormatter;
import io.agentscope.core.formatter.dashscope.DashScopeMultiAgentFormatter;
import io.agentscope.core.formatter.gemini.GeminiChatFormatter;
import io.agentscope.core.formatter.gemini.GeminiMultiAgentFormatter;
import io.agentscope.core.formatter.openai.OpenAIChatFormatter;
import io.agentscope.core.formatter.openai.OpenAIMultiAgentFormatter;
import io.agentscope.core.message.AudioBlock;
import io.agentscope.core.message.ContentBlock;
import io.agentscope.core.message.ImageBlock;
import io.agentscope.core.message.Msg;
import io.agentscope.core.message.MsgRole;
import io.agentscope.core.message.TextBlock;
import io.agentscope.core.message.ThinkingBlock;
import io.agentscope.core.message.ToolResultBlock;
import io.agentscope.core.message.ToolUseBlock;
import io.agentscope.core.message.VideoBlock;
import io.agentscope.core.model.AnthropicChatModel;
import io.agentscope.core.model.ChatModelBase;
import io.agentscope.core.model.ChatResponse;
import io.agentscope.core.model.DashScopeChatModel;
import io.agentscope.core.model.GeminiChatModel;
import io.agentscope.core.model.GenerateOptions;
import io.agentscope.core.model.OpenAIChatModel;
import io.agentscope.core.model.ToolSchema;
import io.agentscope.core.studio.StudioManager;
import io.agentscope.core.tool.AgentTool;
import io.agentscope.core.tool.Toolkit;
import io.agentscope.core.tracing.telemetry.model.InputMessage;
import io.agentscope.core.tracing.telemetry.model.MessagePart;
import io.agentscope.core.tracing.telemetry.model.OutputMessage;
import io.agentscope.core.tracing.telemetry.model.ReasoningPart;
import io.agentscope.core.tracing.telemetry.model.Role;
import io.agentscope.core.tracing.telemetry.model.TextPart;
import io.agentscope.core.tracing.telemetry.model.ToolCallRequestPart;
import io.agentscope.core.tracing.telemetry.model.ToolCallResponsePart;
import io.agentscope.core.tracing.telemetry.model.ToolDefinition;
import io.opentelemetry.api.common.AttributeKey;
import io.opentelemetry.api.common.Attributes;
import io.opentelemetry.api.common.AttributesBuilder;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

final class AttributesExtractors {

    private static final Logger LOGGER = LoggerFactory.getLogger(AttributesExtractors.class);

    private static final ObjectMapper MARSHALER = new ObjectMapper();

    /**
     * Get agent request attributes for OpenTelemetry tracing.
     *
     * <p>Extracts request parameters from agent calls into GenAI attributes.
     *
     * @param instance AgentBase instance making the request
     * @param inputMessages Input messages
     * @return Attributes for agent request
     * */
    static Attributes getAgentRequestAttributes(AgentBase instance, List<Msg> inputMessages) {
        AttributesBuilder builder = Attributes.builder();
        internalSet(builder, GEN_AI_OPERATION_NAME, INVOKE_AGENT);
        internalSet(builder, GEN_AI_AGENT_ID, instance.getAgentId());
        internalSet(builder, GEN_AI_AGENT_NAME, instance.getName());
        internalSet(builder, GEN_AI_AGENT_DESCRIPTION, instance.getDescription());

        internalSet(builder, GEN_AI_INPUT_MESSAGES, getInputMessages(inputMessages));

        Map<String, Object> parameters = new HashMap<>();
        parameters.put("msgs", inputMessages);
        internalSet(builder, AGENTSCOPE_FUNCTION_INPUT, serializeToStr(parameters));
        return builder.build();
    }

    /**
     * Get agent response attributes for OpenTelemetry tracing.
     *
     * <p>Extracts response parameters from agent calls responses into GenAI attributes.
     *
     * @param outputMessage Response of agent invocation
     * @return Attributes for agent response
     * */
    static Attributes getAgentResponseAttributes(Msg outputMessage) {
        AttributesBuilder builder = Attributes.builder();
        internalSet(builder, GEN_AI_OUTPUT_MESSAGES, getOutputMessages(outputMessage));

        internalSet(builder, AGENTSCOPE_FUNCTION_OUTPUT, serializeToStr(outputMessage));
        return builder.build();
    }

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
        if (options != null) {
            internalSet(builder, GEN_AI_REQUEST_TEMPERATURE, options.getTemperature());
            internalSet(builder, GEN_AI_REQUEST_TOP_P, options.getTopP());
            Integer topK = options.getTopK();
            internalSet(builder, GEN_AI_REQUEST_TOP_K, topK != null ? topK.doubleValue() : null);
            internalSet(
                    builder,
                    GEN_AI_REQUEST_MAX_TOKENS,
                    options.getMaxTokens() == null ? null : options.getMaxTokens().longValue());
            internalSet(builder, GEN_AI_REQUEST_PRESENCE_PENALTY, options.getPresencePenalty());
            internalSet(builder, GEN_AI_REQUEST_FREQUENCY_PENALTY, options.getFrequencyPenalty());
            // stop_sequences is not supported now
            Long seed = options.getSeed();
            internalSet(builder, GEN_AI_REQUEST_SEED, seed);
            internalSet(
                    builder,
                    GEN_AI_REQUEST_MAX_TOKENS,
                    options.getMaxTokens() == null ? null : options.getMaxTokens().longValue());
        }

        internalSet(builder, GEN_AI_INPUT_MESSAGES, getInputMessages(inputMessages));
        internalSet(builder, GEN_AI_TOOL_DEFINITIONS, getToolDefinitions(toolSchemas));

        Map<String, Object> parameters = new HashMap<>();
        parameters.put("messages", inputMessages);
        parameters.put("tools", toolSchemas);
        parameters.put("options", options);
        internalSet(builder, AGENTSCOPE_FUNCTION_INPUT, serializeToStr(parameters));
        return builder.build();
    }

    /**
     * Get LLM response attributes for OpenTelemetry tracing.
     *
     * <p>Extracts response parameters from LLM model responses into GenAI attributes.
     *
     * @param response Response of model invocation
     * @return Attributes for LLM response
     * */
    static Attributes getLLMResponseAttributes(ChatResponse response) {
        AttributesBuilder builder = Attributes.builder();
        if (response != null) {
            if (response.getFinishReason() != null) {
                internalSet(
                        builder,
                        GEN_AI_RESPONSE_FINISH_REASONS,
                        Collections.singletonList(response.getFinishReason()));
            }
            internalSet(builder, GEN_AI_RESPONSE_ID, response.getId());
            internalSet(
                    builder,
                    GEN_AI_USAGE_INPUT_TOKENS,
                    (long) response.getUsage().getInputTokens());
            internalSet(
                    builder,
                    GEN_AI_USAGE_OUTPUT_TOKENS,
                    (long) response.getUsage().getOutputTokens());
            internalSet(builder, GEN_AI_OUTPUT_MESSAGES, getOutputMessages(response));
        }

        internalSet(builder, AGENTSCOPE_FUNCTION_OUTPUT, serializeToStr(response));
        return builder.build();
    }

    /**
     * Get tool request attributes for OpenTelemetry tracing.
     *
     * <p>Extracts request parameters from tool calls into GenAI attributes.
     *
     * @param instance Toolkit instance making the request
     * @param toolUseBlock Tool call parameters
     * @return Attributes for tool request
     * */
    static Attributes getToolRequestAttributes(Toolkit instance, ToolUseBlock toolUseBlock) {
        AttributesBuilder builder = Attributes.builder();
        internalSet(builder, GEN_AI_OPERATION_NAME, EXECUTE_TOOL);
        if (toolUseBlock != null) {
            internalSet(builder, GEN_AI_TOOL_CALL_ID, toolUseBlock.getId());
            internalSet(builder, GEN_AI_TOOL_NAME, toolUseBlock.getName());
            internalSet(
                    builder,
                    GEN_AI_TOOL_CALL_ARGUMENTS,
                    getToolCallArguments(toolUseBlock.getInput()));
            AgentTool tool = instance.getTool(toolUseBlock.getName());
            if (tool != null) {
                internalSet(builder, GEN_AI_TOOL_DESCRIPTION, tool.getDescription());
            }
        }

        Map<String, Object> parameters = new HashMap<>();
        parameters.put("param", toolUseBlock);
        internalSet(builder, AGENTSCOPE_FUNCTION_INPUT, serializeToStr(parameters));
        return builder.build();
    }

    /**
     * Get tool response attributes for OpenTelemetry tracing.
     *
     * <p>Extracts response parameters from tool responses into GenAI attributes.
     *
     * @param result Result of tool call
     * @return Attributes for tool response
     * */
    static Attributes getToolResponseAttributes(ToolResultBlock result) {
        AttributesBuilder builder = Attributes.builder();

        if (result != null && result.getOutput() != null) {
            internalSet(builder, GEN_AI_TOOL_CALL_RESULT, getToolCallResult(result.getOutput()));
        }

        internalSet(builder, AGENTSCOPE_FUNCTION_INPUT, serializeToStr(result));
        return builder.build();
    }

    /**
     * Get format request attributes for OpenTelemetry tracing.
     *
     * <p>Extracts request parameters from format invocations into GenAI attributes.
     *
     * @param instance Formatter instance making the request
     * @param msgList Format parameters
     * @return Attributes for format request
     * */
    @SuppressWarnings("rawtypes")
    static Attributes getFormatRequestAttributes(
            AbstractBaseFormatter instance, List<Msg> msgList) {
        AttributesBuilder builder = Attributes.builder();
        internalSet(builder, GEN_AI_OPERATION_NAME, FORMAT);
        internalSet(
                builder,
                AGENTSCOPE_FORMAT_TARGET,
                FormatterConverter.getFormatterTarget(instance.getClass().getSimpleName()));
        if (msgList != null) {
            internalSet(builder, AGENTSCOPE_FUNCTION_INPUT, serializeToStr(msgList));
        }
        return builder.build();
    }

    /**
     * Get format response attributes for OpenTelemetry tracing.
     *
     * <p>Extracts response parameters from format invocations into GenAI attributes.
     *
     * @param result Result of format
     * @return Attributes for format response
     * */
    @SuppressWarnings("rawtypes")
    static Attributes getFormatResponseAttributes(List result) {
        AttributesBuilder builder = Attributes.builder();
        if (result != null) {
            internalSet(builder, AGENTSCOPE_FUNCTION_OUTPUT, serializeToStr(result));
        }
        return builder.build();
    }

    /**
     * Get AgentScope function name for OpenTelemetry tracing.
     *
     * @param instance The invoked instance
     * @param methodName The traced method
     * @return Attribute value of AgentScope function name
     * */
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

    private static String getOutputMessages(Msg outputMessage) {
        if (outputMessage == null || outputMessage.getContent() == null) {
            return null;
        }

        List<ContentBlock> contents = outputMessage.getContent();
        List<MessagePart> parts = new ArrayList<>(contents.size());
        for (ContentBlock content : contents) {
            if (content instanceof TextBlock textBlock) {
                parts.add(TextPart.create(textBlock.getText()));
            } else if (content instanceof ThinkingBlock thinkingBlock) {
                parts.add(ReasoningPart.create(thinkingBlock.getThinking()));
            } else if (content instanceof ToolUseBlock toolUseBlock) {
                parts.add(
                        ToolCallRequestPart.create(
                                toolUseBlock.getId(),
                                toolUseBlock.getName(),
                                toolUseBlock.getContent()));
            } else if (content instanceof ToolResultBlock toolResultBlock) {
                parts.add(
                        ToolCallResponsePart.create(
                                toolResultBlock.getId(), toolResultBlock.getOutput()));
            }
            // TODO: support multi modal content
        }

        List<OutputMessage> outputMessages =
                Collections.singletonList(
                        OutputMessage.create(
                                getRole(outputMessage.getRole()),
                                parts,
                                outputMessage.getName(),
                                "stop"));

        try {
            return MARSHALER.writeValueAsString(outputMessages);
        } catch (JsonProcessingException e) {
            LOGGER.warn("Failed to serialize output messages, due to: {}", e.getMessage());
            return null;
        }
    }

    private static String getToolCallArguments(Map<String, Object> input) {
        try {
            return MARSHALER.writeValueAsString(input);
        } catch (JsonProcessingException e) {
            LOGGER.warn("Failed to serialize tool call arguments, due to: {}", e.getMessage());
            return null;
        }
    }

    private static String serializeToStr(Object object) {
        try {
            return MARSHALER.writeValueAsString(object);
        } catch (JsonProcessingException e) {
            LOGGER.warn(
                    "Failed to serialize {} instance to json string, due to: {}",
                    object.getClass().getSimpleName(),
                    e.getMessage());
            return null;
        }
    }

    private static String getToolCallResult(List<ContentBlock> result) {
        List<ContentBlock> blocks =
                result.stream()
                        .map(
                                block -> {
                                    // TODO: support multi modal content
                                    if (block instanceof VideoBlock) {
                                        return TextBlock.builder()
                                                .text("<VideoBlock is not supported>")
                                                .build();
                                    } else if (block instanceof AudioBlock) {
                                        return TextBlock.builder()
                                                .text("<AudioBlock is not supported>")
                                                .build();
                                    } else if (block instanceof ImageBlock) {
                                        return TextBlock.builder()
                                                .text("<ImageBlock is not supported>")
                                                .build();
                                    }
                                    return block;
                                })
                        .toList();
        try {
            return MARSHALER.writeValueAsString(blocks);
        } catch (JsonProcessingException e) {
            LOGGER.warn("Failed to serialize tool call result, due to: {}", e.getMessage());
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

    static final class FormatterConverter {

        private static final Map<String, String> FORMATTER_MAPPERS;

        static {
            FORMATTER_MAPPERS = new HashMap<>();
            FORMATTER_MAPPERS.put(DashScopeChatFormatter.class.getSimpleName(), DASHSCOPE);
            FORMATTER_MAPPERS.put(DashScopeMultiAgentFormatter.class.getSimpleName(), DASHSCOPE);
            FORMATTER_MAPPERS.put(GeminiChatFormatter.class.getSimpleName(), DASHSCOPE);
            FORMATTER_MAPPERS.put(GeminiMultiAgentFormatter.class.getSimpleName(), DASHSCOPE);
            FORMATTER_MAPPERS.put(OpenAIChatFormatter.class.getSimpleName(), DASHSCOPE);
            FORMATTER_MAPPERS.put(OpenAIMultiAgentFormatter.class.getSimpleName(), DASHSCOPE);
            FORMATTER_MAPPERS.put(AnthropicChatFormatter.class.getSimpleName(), ANTHROPIC);
            FORMATTER_MAPPERS.put(AnthropicMultiAgentFormatter.class.getSimpleName(), ANTHROPIC);
        }

        static String getFormatterTarget(String simpleClassName) {
            return FORMATTER_MAPPERS.getOrDefault(simpleClassName, "unknown");
        }
    }

    private static final class ProviderNameConverter {

        static String getProviderName(ChatModelBase instance) {
            if (instance instanceof DashScopeChatModel) {
                return DASHSCOPE;
            } else if (instance instanceof GeminiChatModel) {
                return GCP_GEMINI;
            } else if (instance instanceof AnthropicChatModel) {
                return ANTHROPIC;
            } else if (instance instanceof OpenAIChatModel) {
                return OPENAI;
            }
            return "unknown";
        }
    }

    private AttributesExtractors() {}
}

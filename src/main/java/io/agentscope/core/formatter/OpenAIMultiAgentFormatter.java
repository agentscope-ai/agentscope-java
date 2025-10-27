/*
 * Copyright 2024-2025 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      https://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package io.agentscope.core.formatter;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.openai.models.chat.completions.ChatCompletion;
import com.openai.models.chat.completions.ChatCompletionAssistantMessageParam;
import com.openai.models.chat.completions.ChatCompletionChunk;
import com.openai.models.chat.completions.ChatCompletionCreateParams;
import com.openai.models.chat.completions.ChatCompletionFunctionTool;
import com.openai.models.chat.completions.ChatCompletionMessage;
import com.openai.models.chat.completions.ChatCompletionMessageFunctionToolCall;
import com.openai.models.chat.completions.ChatCompletionMessageParam;
import com.openai.models.chat.completions.ChatCompletionSystemMessageParam;
import com.openai.models.chat.completions.ChatCompletionTool;
import com.openai.models.chat.completions.ChatCompletionToolChoiceOption;
import com.openai.models.chat.completions.ChatCompletionToolMessageParam;
import com.openai.models.chat.completions.ChatCompletionUserMessageParam;
import io.agentscope.core.message.ContentBlock;
import io.agentscope.core.message.Msg;
import io.agentscope.core.message.MsgRole;
import io.agentscope.core.message.TextBlock;
import io.agentscope.core.message.ThinkingBlock;
import io.agentscope.core.message.ToolResultBlock;
import io.agentscope.core.message.ToolUseBlock;
import io.agentscope.core.model.ChatResponse;
import io.agentscope.core.model.ChatUsage;
import io.agentscope.core.model.GenerateOptions;
import io.agentscope.core.model.ToolSchema;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Multi-agent formatter for OpenAI Chat Completion API.
 * Converts AgentScope Msg objects to OpenAI SDK ChatCompletionMessageParam objects with multi-agent support.
 *
 * This formatter handles conversations between multiple agents by:
 * - Grouping multi-agent messages into conversation history
 * - Using special markup (e.g., history tags) to structure conversations
 * - Consolidating multi-agent conversations into single user messages
 */
public class OpenAIMultiAgentFormatter
        implements Formatter<
                ChatCompletionMessageParam, Object, ChatCompletionCreateParams.Builder> {

    private static final Logger log = LoggerFactory.getLogger(OpenAIMultiAgentFormatter.class);
    private static final String HISTORY_START_TAG = "<history>";
    private static final String HISTORY_END_TAG = "</history>";
    private static final String DEFAULT_CONVERSATION_HISTORY_PROMPT =
            "# Conversation History\n"
                    + "The content between <history></history> tags contains your conversation"
                    + " history\n";
    private final ObjectMapper objectMapper = new ObjectMapper();
    private final String conversationHistoryPrompt;

    /**
     * Create an OpenAIMultiAgentFormatter with default conversation history prompt.
     */
    public OpenAIMultiAgentFormatter() {
        this(DEFAULT_CONVERSATION_HISTORY_PROMPT);
    }

    /**
     * Create an OpenAIMultiAgentFormatter with custom conversation history prompt.
     *
     * @param conversationHistoryPrompt The prompt to prepend before conversation history
     */
    public OpenAIMultiAgentFormatter(String conversationHistoryPrompt) {
        this.conversationHistoryPrompt =
                conversationHistoryPrompt != null
                        ? conversationHistoryPrompt
                        : DEFAULT_CONVERSATION_HISTORY_PROMPT;
    }

    @Override
    public List<ChatCompletionMessageParam> format(List<Msg> msgs) {
        List<ChatCompletionMessageParam> result = new ArrayList<>();

        // Group messages into sequences
        List<MessageGroup> groups = groupMessages(msgs);

        for (MessageGroup group : groups) {
            switch (group.getType()) {
                case SYSTEM ->
                        result.add(
                                ChatCompletionMessageParam.ofSystem(
                                        formatSystemMsg(group.getMessages().get(0))));
                case TOOL_SEQUENCE -> result.addAll(formatToolSequence(group.getMessages()));
                case AGENT_CONVERSATION ->
                        result.add(
                                ChatCompletionMessageParam.ofUser(
                                        formatAgentConversation(group.getMessages())));
            }
        }

        return result;
    }

    /**
     * Group messages into different types (system, tool sequences, agent conversations).
     */
    private List<MessageGroup> groupMessages(List<Msg> msgs) {
        List<MessageGroup> groups = new ArrayList<>();
        List<Msg> currentGroup = new ArrayList<>();
        MessageGroupType currentType = null;

        for (Msg msg : msgs) {
            MessageGroupType msgType = determineGroupType(msg);

            if (currentType == null
                    || currentType != msgType
                    || (msgType == MessageGroupType.SYSTEM)) {
                // Start new group
                if (!currentGroup.isEmpty()) {
                    groups.add(new MessageGroup(currentType, currentGroup));
                }
                currentGroup = new ArrayList<>();
                currentType = msgType;
            }

            currentGroup.add(msg);
        }

        // Add final group
        if (!currentGroup.isEmpty()) {
            groups.add(new MessageGroup(currentType, currentGroup));
        }

        return groups;
    }

    /**
     * Determine the group type for a message.
     */
    private MessageGroupType determineGroupType(Msg msg) {
        return switch (msg.getRole()) {
            case SYSTEM -> MessageGroupType.SYSTEM;
            case TOOL -> MessageGroupType.TOOL_SEQUENCE;
            case USER, ASSISTANT -> {
                // Check if this is part of a tool sequence
                if (msg.hasContentBlocks(ToolUseBlock.class)) {
                    yield MessageGroupType.TOOL_SEQUENCE;
                }
                yield MessageGroupType.AGENT_CONVERSATION;
            }
        };
    }

    /**
     * Format a multi-agent conversation into OpenAI format.
     * Consolidates multiple agent messages into a single user message with history tags.
     */
    private ChatCompletionUserMessageParam formatAgentConversation(List<Msg> msgs) {
        // Build conversation with agent names
        StringBuilder conversationHistory = new StringBuilder();
        conversationHistory.append(conversationHistoryPrompt);
        conversationHistory.append(HISTORY_START_TAG).append("\n");

        for (Msg msg : msgs) {
            String agentName = msg.getName() != null ? msg.getName() : "Unknown";
            String roleLabel = formatRoleLabel(msg.getRole());

            // Extract text content from all blocks
            // Note: ThinkingBlock is intentionally NOT included in conversation history
            // (matching Python implementation behavior)
            List<ContentBlock> blocks = msg.getContent();
            for (ContentBlock block : blocks) {
                if (block instanceof TextBlock tb) {
                    conversationHistory
                            .append(roleLabel)
                            .append(" ")
                            .append(agentName)
                            .append(": ")
                            .append(tb.getText())
                            .append("\n");
                } else if (block instanceof ThinkingBlock) {
                    // IMPORTANT: ThinkingBlock is NOT included in conversation history
                    // for multi-agent formatters (matching Python implementation)
                    log.debug("Skipping ThinkingBlock in multi-agent conversation for OpenAI API");
                } else if (block instanceof ToolResultBlock toolResult) {
                    StringBuilder resultText = new StringBuilder();
                    for (ContentBlock output : toolResult.getOutput()) {
                        if (output instanceof TextBlock textBlock) {
                            if (resultText.length() > 0) resultText.append("\n");
                            resultText.append(textBlock.getText());
                        }
                    }
                    String finalResultText =
                            resultText.length() > 0
                                    ? resultText.toString()
                                    : "[Non-text tool result]";
                    conversationHistory
                            .append(roleLabel)
                            .append(" ")
                            .append(agentName)
                            .append(" (")
                            .append(toolResult.getName())
                            .append("): ")
                            .append(finalResultText)
                            .append("\n");
                }
            }
        }

        conversationHistory.append(HISTORY_END_TAG);

        // Return as single user message
        return ChatCompletionUserMessageParam.builder()
                .content(conversationHistory.toString())
                .build();
    }

    /**
     * Format role label for conversation history.
     */
    private String formatRoleLabel(MsgRole role) {
        return switch (role) {
            case USER -> "User";
            case ASSISTANT -> "Assistant";
            case SYSTEM -> "System";
            case TOOL -> "Tool";
        };
    }

    private ChatCompletionSystemMessageParam formatSystemMsg(Msg msg) {
        return ChatCompletionSystemMessageParam.builder().content(extractTextContent(msg)).build();
    }

    private List<ChatCompletionMessageParam> formatToolSequence(List<Msg> msgs) {
        List<ChatCompletionMessageParam> result = new ArrayList<>();

        for (Msg msg : msgs) {
            if (msg.getRole() == MsgRole.ASSISTANT) {
                result.add(ChatCompletionMessageParam.ofAssistant(formatAssistantToolCall(msg)));
            } else if (msg.getRole() == MsgRole.TOOL) {
                result.add(ChatCompletionMessageParam.ofTool(formatToolResult(msg)));
            }
        }

        return result;
    }

    private ChatCompletionAssistantMessageParam formatAssistantToolCall(Msg msg) {
        ChatCompletionAssistantMessageParam.Builder builder =
                ChatCompletionAssistantMessageParam.builder();

        String textContent = extractTextContent(msg);
        if (!textContent.isEmpty()) {
            builder.content(textContent);
        }

        // Handle tool calls
        List<ToolUseBlock> toolBlocks = msg.getContentBlocks(ToolUseBlock.class);
        if (!toolBlocks.isEmpty()) {
            for (ToolUseBlock toolUse : toolBlocks) {
                String argsJson;
                try {
                    argsJson = objectMapper.writeValueAsString(toolUse.getInput());
                } catch (Exception e) {
                    log.warn("Failed to serialize tool call arguments: {}", e.getMessage());
                    argsJson = "{}";
                }

                var toolCallParam =
                        ChatCompletionMessageFunctionToolCall.builder()
                                .id(toolUse.getId())
                                .function(
                                        ChatCompletionMessageFunctionToolCall.Function.builder()
                                                .name(toolUse.getName())
                                                .arguments(argsJson)
                                                .build())
                                .build();

                builder.addToolCall(toolCallParam);
                log.debug(
                        "Formatted multi-agent tool call: id={}, name={}",
                        toolUse.getId(),
                        toolUse.getName());
            }
        }

        return builder.build();
    }

    private ChatCompletionToolMessageParam formatToolResult(Msg msg) {
        ToolResultBlock result = msg.getFirstContentBlock(ToolResultBlock.class);
        String toolCallId =
                result != null ? result.getId() : "tool_call_" + System.currentTimeMillis();
        String content = extractTextContent(msg);

        return ChatCompletionToolMessageParam.builder()
                .content(content)
                .toolCallId(toolCallId)
                .build();
    }

    private String extractTextContent(Msg msg) {
        StringBuilder sb = new StringBuilder();
        for (ContentBlock block : msg.getContent()) {
            if (block instanceof TextBlock tb) {
                if (sb.length() > 0) sb.append("\n");
                sb.append(tb.getText());
            } else if (block instanceof ThinkingBlock) {
                // IMPORTANT: ThinkingBlock is NOT sent back to OpenAI API
                // (matching Python implementation and other formatters' behavior)
                // ThinkingBlock is stored in memory but skipped when formatting messages
                log.debug("Skipping ThinkingBlock when formatting message for OpenAI API");
            } else if (block instanceof ToolResultBlock toolResult) {
                for (ContentBlock output : toolResult.getOutput()) {
                    if (output instanceof TextBlock textBlock) {
                        if (sb.length() > 0) sb.append("\n");
                        sb.append(textBlock.getText());
                    }
                }
            }
        }
        return sb.toString();
    }

    private String extractTextContent(ContentBlock block) {
        if (block instanceof TextBlock tb) {
            return tb.getText();
        }
        return "";
    }

    @Override
    public ChatResponse parseResponse(Object response, Instant startTime) {
        // Dispatch to the appropriate parsing method based on actual type
        if (response instanceof ChatCompletion completion) {
            return parseCompletionResponse(completion, startTime);
        } else if (response instanceof ChatCompletionChunk chunk) {
            return parseChunkResponse(chunk, startTime);
        } else {
            throw new IllegalArgumentException(
                    "Unsupported response type: " + response.getClass().getName());
        }
    }

    /**
     * Parse OpenAI non-streaming response.
     *
     * @param completion ChatCompletion from OpenAI
     * @param startTime Request start time
     * @return AgentScope ChatResponse
     */
    public ChatResponse parseCompletionResponse(ChatCompletion completion, Instant startTime) {
        List<ContentBlock> contentBlocks = new ArrayList<>();
        ChatUsage usage = null;

        try {
            // Parse usage information
            if (completion.usage().isPresent()) {
                var openAIUsage = completion.usage().get();
                usage =
                        ChatUsage.builder()
                                .inputTokens((int) openAIUsage.promptTokens())
                                .outputTokens((int) openAIUsage.completionTokens())
                                .time(
                                        Duration.between(startTime, Instant.now()).toMillis()
                                                / 1000.0)
                                .build();
            }

            // Parse response content
            if (!completion.choices().isEmpty()) {
                ChatCompletion.Choice choice = completion.choices().get(0);
                ChatCompletionMessage message = choice.message();

                // Parse text content
                if (message.content() != null && message.content().isPresent()) {
                    String textContent = message.content().get();
                    if (!textContent.isEmpty()) {
                        contentBlocks.add(TextBlock.builder().text(textContent).build());
                    }
                }

                // Parse tool calls
                if (message.toolCalls().isPresent()) {
                    var toolCalls = message.toolCalls().get();
                    log.debug("Tool calls detected in non-stream response: {}", toolCalls.size());

                    for (var toolCall : toolCalls) {
                        if (toolCall.function().isPresent()) {
                            // Convert OpenAI tool call to AgentScope ToolUseBlock
                            try {
                                var functionToolCall = toolCall.function().get();
                                var function = functionToolCall.function();
                                Map<String, Object> argsMap = new HashMap<>();
                                String arguments = function.arguments();
                                if (arguments != null && !arguments.isEmpty()) {
                                    @SuppressWarnings("unchecked")
                                    Map<String, Object> parsed =
                                            objectMapper.readValue(arguments, Map.class);
                                    if (parsed != null) argsMap.putAll(parsed);
                                }

                                contentBlocks.add(
                                        ToolUseBlock.builder()
                                                .id(functionToolCall.id())
                                                .name(function.name())
                                                .input(argsMap)
                                                .content(arguments)
                                                .build());

                                log.debug(
                                        "Parsed tool call: id={}, name={}",
                                        functionToolCall.id(),
                                        function.name());
                            } catch (Exception ex) {
                                log.warn(
                                        "Failed to parse tool call arguments: {}", ex.getMessage());
                            }
                        }
                    }
                }
            }

        } catch (Exception e) {
            log.warn("Failed to parse completion: {}", e.getMessage());
            // Add a fallback text block
            contentBlocks.add(
                    TextBlock.builder().text("Error parsing response: " + e.getMessage()).build());
        }

        return ChatResponse.builder()
                .id(completion.id())
                .content(contentBlocks)
                .usage(usage)
                .build();
    }

    /**
     * Parse OpenAI streaming response chunk.
     *
     * @param chunk ChatCompletionChunk from OpenAI
     * @param startTime Request start time
     * @return AgentScope ChatResponse
     */
    public ChatResponse parseChunkResponse(ChatCompletionChunk chunk, Instant startTime) {
        List<ContentBlock> contentBlocks = new ArrayList<>();
        ChatUsage usage = null;

        try {
            // Parse usage information (usually only in the last chunk)
            if (chunk.usage().isPresent()) {
                var openAIUsage = chunk.usage().get();
                usage =
                        ChatUsage.builder()
                                .inputTokens((int) openAIUsage.promptTokens())
                                .outputTokens((int) openAIUsage.completionTokens())
                                .time(
                                        Duration.between(startTime, Instant.now()).toMillis()
                                                / 1000.0)
                                .build();
            }

            // Parse chunk content
            if (!chunk.choices().isEmpty()) {
                ChatCompletionChunk.Choice choice = chunk.choices().get(0);
                ChatCompletionChunk.Choice.Delta delta = choice.delta();

                // Parse text content
                if (delta.content() != null && delta.content().isPresent()) {
                    String textContent = delta.content().get();
                    if (!textContent.isEmpty()) {
                        contentBlocks.add(TextBlock.builder().text(textContent).build());
                    }
                }

                // Parse tool calls (in streaming, these come incrementally)
                if (delta.toolCalls().isPresent()) {
                    var toolCalls = delta.toolCalls().get();
                    log.debug("Streaming tool calls detected: {}", toolCalls.size());

                    for (var toolCall : toolCalls) {
                        if (toolCall.function().isPresent()) {
                            try {
                                var function = toolCall.function().get();
                                String toolCallId =
                                        toolCall.id()
                                                .orElse("streaming_" + System.currentTimeMillis());
                                String toolName = function.name().orElse("");
                                String arguments = function.arguments().orElse("");

                                // For streaming, we get partial tool calls that need to be
                                // accumulated
                                // Only process when we have a tool name (arguments may be partial)
                                if (!toolName.isEmpty()) {
                                    Map<String, Object> argsMap = new HashMap<>();

                                    // Try to parse arguments only if they look complete
                                    // (simple heuristic: starts with { and ends with })
                                    if (!arguments.isEmpty()
                                            && arguments.trim().startsWith("{")
                                            && arguments.trim().endsWith("}")) {
                                        try {
                                            @SuppressWarnings("unchecked")
                                            Map<String, Object> parsed =
                                                    objectMapper.readValue(arguments, Map.class);
                                            if (parsed != null) argsMap.putAll(parsed);
                                        } catch (Exception parseEx) {
                                            log.debug(
                                                    "Partial arguments in streaming (expected): {}",
                                                    arguments.length() > 50
                                                            ? arguments.substring(0, 50) + "..."
                                                            : arguments);
                                            // Don't warn for partial JSON - this is normal in
                                            // streaming
                                        }
                                    } else if (!arguments.isEmpty()) {
                                        log.debug(
                                                "Partial tool arguments received: {}",
                                                arguments.length() > 30
                                                        ? arguments.substring(0, 30) + "..."
                                                        : arguments);
                                    }

                                    // Create ToolUseBlock even with partial arguments
                                    // The ReActAgent's ToolCallAccumulator will handle accumulation
                                    ToolUseBlock toolUseBlock =
                                            ToolUseBlock.builder()
                                                    .id(toolCallId)
                                                    .name(toolName)
                                                    .input(argsMap)
                                                    .content(arguments) // Store raw arguments for
                                                    // accumulation
                                                    .build();
                                    contentBlocks.add(toolUseBlock);
                                    log.debug(
                                            "Added streaming tool call chunk: id={}, name={},\""
                                                    + " args_complete={}",
                                            toolCallId,
                                            toolName,
                                            !argsMap.isEmpty());
                                }
                            } catch (Exception ex) {
                                log.warn(
                                        "Failed to parse streaming tool call: {}", ex.getMessage());
                            }
                        }
                    }
                }
            }

        } catch (Exception e) {
            log.warn("Failed to parse chunk: {}", e.getMessage());
            return null; // Skip malformed chunks
        }

        return ChatResponse.builder().id(chunk.id()).content(contentBlocks).usage(usage).build();
    }

    @Override
    public void applyOptions(
            ChatCompletionCreateParams.Builder paramsBuilder,
            GenerateOptions options,
            GenerateOptions defaultOptions) {
        GenerateOptions opt = options != null ? options : defaultOptions;
        if (opt.getTemperature() != null) paramsBuilder.temperature(opt.getTemperature());
        if (opt.getMaxTokens() != null)
            paramsBuilder.maxCompletionTokens(opt.getMaxTokens().longValue());
        if (opt.getTopP() != null) paramsBuilder.topP(opt.getTopP());
        if (opt.getFrequencyPenalty() != null)
            paramsBuilder.frequencyPenalty(opt.getFrequencyPenalty());
        if (opt.getPresencePenalty() != null)
            paramsBuilder.presencePenalty(opt.getPresencePenalty());
    }

    @Override
    public void applyTools(
            ChatCompletionCreateParams.Builder paramsBuilder, List<ToolSchema> tools) {
        if (tools == null || tools.isEmpty()) {
            return;
        }

        try {
            for (ToolSchema toolSchema : tools) {
                // Convert ToolSchema to OpenAI ChatCompletionTool
                // Create function definition first
                com.openai.models.FunctionDefinition.Builder functionBuilder =
                        com.openai.models.FunctionDefinition.builder().name(toolSchema.getName());

                if (toolSchema.getDescription() != null) {
                    functionBuilder.description(toolSchema.getDescription());
                }

                // Convert parameters map to proper format for OpenAI
                if (toolSchema.getParameters() != null) {
                    // Convert Map<String, Object> to FunctionParameters
                    com.openai.models.FunctionParameters.Builder funcParamsBuilder =
                            com.openai.models.FunctionParameters.builder();
                    for (Map.Entry<String, Object> entry : toolSchema.getParameters().entrySet()) {
                        funcParamsBuilder.putAdditionalProperty(
                                entry.getKey(), com.openai.core.JsonValue.from(entry.getValue()));
                    }
                    functionBuilder.parameters(funcParamsBuilder.build());
                }

                // Create ChatCompletionFunctionTool
                ChatCompletionFunctionTool functionTool =
                        ChatCompletionFunctionTool.builder()
                                .function(functionBuilder.build())
                                .build();

                // Create ChatCompletionTool
                ChatCompletionTool tool = ChatCompletionTool.ofFunction(functionTool);
                paramsBuilder.addTool(tool);

                log.debug("Added tool to OpenAI request: {}", toolSchema.getName());
            }

            // Set tool choice to auto to allow the model to decide when to use tools
            paramsBuilder.toolChoice(
                    ChatCompletionToolChoiceOption.ofAuto(
                            ChatCompletionToolChoiceOption.Auto.AUTO));

        } catch (Exception e) {
            log.error("Failed to add tools to OpenAI request: {}", e.getMessage(), e);
        }
    }

    @Override
    public FormatterCapabilities getCapabilities() {
        return FormatterCapabilities.builder()
                .providerName("OpenAI")
                .supportToolsApi(true)
                .supportMultiAgent(true)
                .supportVision(true)
                .supportedBlocks(
                        Set.of(
                                TextBlock.class,
                                ToolUseBlock.class,
                                ToolResultBlock.class,
                                ThinkingBlock.class))
                .build();
    }

    /**
     * Represents a group of related messages.
     */
    private static class MessageGroup {
        private final MessageGroupType type;
        private final List<Msg> messages;

        public MessageGroup(MessageGroupType type, List<Msg> messages) {
            this.type = type;
            this.messages = new ArrayList<>(messages);
        }

        public MessageGroupType getType() {
            return type;
        }

        public List<Msg> getMessages() {
            return messages;
        }
    }

    /**
     * Types of message groups in multi-agent conversations.
     */
    private enum MessageGroupType {
        SYSTEM, // System messages
        TOOL_SEQUENCE, // Tool use and tool result messages
        AGENT_CONVERSATION // Regular agent conversation messages
    }
}

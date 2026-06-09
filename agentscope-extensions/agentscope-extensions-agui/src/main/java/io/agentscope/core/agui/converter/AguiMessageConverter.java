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
package io.agentscope.core.agui.converter;

import com.fasterxml.jackson.core.type.TypeReference;
import io.agentscope.core.agui.model.AguiFunctionCall;
import io.agentscope.core.agui.model.AguiMessage;
import io.agentscope.core.agui.model.AguiToolCall;
import io.agentscope.core.message.ContentBlock;
import io.agentscope.core.message.Msg;
import io.agentscope.core.message.MsgRole;
import io.agentscope.core.message.TextBlock;
import io.agentscope.core.message.ThinkingBlock;
import io.agentscope.core.message.ToolResultBlock;
import io.agentscope.core.message.ToolUseBlock;
import io.agentscope.core.util.JsonException;
import io.agentscope.core.util.JsonUtils;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * Converter between AG-UI messages and AgentScope messages.
 *
 * <p>This class handles the bidirectional conversion between the AG-UI protocol's
 * message format and AgentScope's internal message format.
 */
public class AguiMessageConverter {

    /**
     * Creates a new AguiMessageConverter
     */
    public AguiMessageConverter() {}

    /**
     * Convert an AG-UI message to an AgentScope message.
     *
     * @param aguiMessage The AG-UI message to convert
     * @return The converted AgentScope message
     */
    public Msg toMsg(AguiMessage aguiMessage) {
        MsgRole role = convertRole(aguiMessage.getRole());
        List<ContentBlock> blocks = new ArrayList<>();

        // Add text content if present
        if (aguiMessage.getContent() != null && !aguiMessage.getContent().isEmpty()) {
            if (aguiMessage.isToolMessage() && aguiMessage.getToolCallId() != null) {
                // For tool messages, wrap content in ToolResultBlock
                blocks.add(
                        ToolResultBlock.of(
                                aguiMessage.getToolCallId(),
                                null,
                                TextBlock.builder().text(aguiMessage.getContent()).build()));
            } else if (aguiMessage.isReasoningMessage()) {
                blocks.add(ThinkingBlock.builder().thinking(aguiMessage.getContent()).build());
            } else {
                blocks.add(TextBlock.builder().text(aguiMessage.getContent()).build());
            }
        }

        // Add tool calls if present (for assistant messages)
        if (aguiMessage.hasToolCalls()) {
            for (AguiToolCall tc : aguiMessage.getToolCalls()) {
                blocks.add(toToolUseBlock(tc));
            }
        }

        return Msg.builder().id(aguiMessage.getId()).role(role).content(blocks).build();
    }

    /**
     * Convert an AgentScope message to one or more AG-UI messages.
     *
     * @param msg The AgentScope message to convert
     * @return The converted AG-UI messages
     */
    public List<AguiMessage> toAguiMessages(Msg msg) {
        return toAguiMessages(msg, true);
    }

    /**
     * Convert an AgentScope message to one or more AG-UI messages.
     *
     * <p>When reasoning output is disabled, {@link ThinkingBlock} content is skipped to match the
     * streaming adapter's REASONING_MESSAGE_* behavior.
     *
     * <p>This method splits a single AgentScope message that may contain mixed content blocks
     * (text, thinking, tool calls, tool results) into separate AG-UI messages, each assigned the
     * appropriate role (assistant, reasoning, or tool).
     *
     * @param msg The AgentScope message to convert
     * @param includeReasoning Whether ThinkingBlock content should be emitted as reasoning messages
     * @return The converted AG-UI messages
     */
    public List<AguiMessage> toAguiMessages(Msg msg, boolean includeReasoning) {
        List<AguiMessage> messages = new ArrayList<>();
        StringBuilder textContent = new StringBuilder();
        StringBuilder reasoningContent = new StringBuilder();
        List<AguiToolCall> toolCalls = new ArrayList<>();
        List<ToolResultBlock> toolResults = new ArrayList<>();

        for (ContentBlock block : msg.getContent()) {
            if (block instanceof TextBlock tb) {
                appendContent(textContent, tb.getText());
            } else if (block instanceof ThinkingBlock tb) {
                if (includeReasoning) {
                    appendContent(reasoningContent, tb.getThinking());
                }
            } else if (block instanceof ToolUseBlock tub) {
                toolCalls.add(toAguiToolCall(tub));
            } else if (block instanceof ToolResultBlock trb) {
                toolResults.add(trb);
            }
        }

        if (reasoningContent.length() > 0) {
            messages.add(
                    new AguiMessage(
                            msg.getId(), "reasoning", reasoningContent.toString(), null, null));
        }

        if (textContent.length() > 0) {
            messages.add(
                    new AguiMessage(
                            msg.getId(),
                            convertRole(msg.getRole()),
                            textContent.toString(),
                            null,
                            null));
        }

        if (!toolCalls.isEmpty()) {
            messages.add(new AguiMessage(msg.getId(), "assistant", null, toolCalls, null));
        }

        if (!toolResults.isEmpty()) {
            for (ToolResultBlock toolResult : toolResults) {
                messages.add(
                        new AguiMessage(
                                msg.getId(),
                                "tool",
                                extractToolResultText(toolResult),
                                null,
                                toolResult.getId()));
            }
        }

        return messages;
    }

    /**
     * Convert a list of AG-UI messages to AgentScope messages.
     *
     * @param aguiMessages The AG-UI messages to convert
     * @return The converted AgentScope messages
     */
    public List<Msg> toMsgList(List<AguiMessage> aguiMessages) {
        return aguiMessages.stream().map(this::toMsg).collect(Collectors.toList());
    }

    /**
     * Convert a list of AgentScope messages to AG-UI messages.
     *
     * @param msgs The AgentScope messages to convert
     * @return The converted AG-UI messages
     */
    public List<AguiMessage> toAguiMessageList(List<Msg> msgs) {
        return toAguiMessageList(msgs, true);
    }

    /**
     * Convert a list of AgentScope messages to AG-UI messages.
     *
     * @param msgs The AgentScope messages to convert
     * @param includeReasoning Whether ThinkingBlock content should be emitted as reasoning messages
     * @return The converted AG-UI messages
     */
    public List<AguiMessage> toAguiMessageList(List<Msg> msgs, boolean includeReasoning) {
        return msgs.stream()
                .flatMap(msg -> toAguiMessages(msg, includeReasoning).stream())
                .collect(Collectors.toList());
    }

    /**
     * Convert an AG-UI role string to an AgentScope MsgRole.
     *
     * @param role The AG-UI role string
     * @return The corresponding MsgRole
     */
    private MsgRole convertRole(String role) {
        return switch (role.toLowerCase()) {
            case "user" -> MsgRole.USER;
            case "assistant" -> MsgRole.ASSISTANT;
            case "system" -> MsgRole.SYSTEM;
            case "tool" -> MsgRole.TOOL;
            case "reasoning" -> MsgRole.ASSISTANT;
            default -> MsgRole.USER;
        };
    }

    /**
     * Convert an AgentScope MsgRole to an AG-UI role string.
     *
     * @param role The AgentScope MsgRole
     * @return The corresponding role string
     */
    private String convertRole(MsgRole role) {
        return switch (role) {
            case USER -> "user";
            case ASSISTANT -> "assistant";
            case SYSTEM -> "system";
            case TOOL -> "tool";
        };
    }

    private void appendContent(StringBuilder builder, String content) {
        if (content == null || content.isEmpty()) {
            return;
        }
        if (builder.length() > 0) {
            builder.append("\n");
        }
        builder.append(content);
    }

    private String extractToolResultText(ToolResultBlock toolResult) {
        StringBuilder content = new StringBuilder();
        for (ContentBlock output : toolResult.getOutput()) {
            if (output instanceof TextBlock tb) {
                appendContent(content, tb.getText());
            }
        }
        return content.length() > 0 ? content.toString() : null;
    }

    /**
     * Convert an AG-UI tool call to an AgentScope ToolUseBlock.
     *
     * @param tc The AG-UI tool call
     * @return The converted ToolUseBlock
     */
    private ToolUseBlock toToolUseBlock(AguiToolCall tc) {
        Map<String, Object> input = parseJsonArguments(tc.getFunction().getArguments());
        return ToolUseBlock.builder()
                .id(tc.getId())
                .name(tc.getFunction().getName())
                .input(input)
                .build();
    }

    /**
     * Convert an AgentScope ToolUseBlock to an AG-UI tool call.
     *
     * @param tub The AgentScope ToolUseBlock
     * @return The converted AG-UI tool call
     */
    private AguiToolCall toAguiToolCall(ToolUseBlock tub) {
        String arguments = serializeArguments(tub.getInput());
        AguiFunctionCall function = new AguiFunctionCall(tub.getName(), arguments);
        return new AguiToolCall(tub.getId(), function);
    }

    /**
     * Parse JSON arguments string to a Map.
     *
     * @param arguments The JSON arguments string
     * @return The parsed Map
     */
    private Map<String, Object> parseJsonArguments(String arguments) {
        if (arguments == null || arguments.isEmpty()) {
            return Map.of();
        }
        try {
            return JsonUtils.getJsonCodec()
                    .fromJson(arguments, new TypeReference<Map<String, Object>>() {});
        } catch (JsonException e) {
            return Map.of();
        }
    }

    /**
     * Serialize arguments Map to JSON string.
     *
     * @param arguments The arguments Map
     * @return The JSON string
     */
    private String serializeArguments(Map<String, Object> arguments) {
        if (arguments == null || arguments.isEmpty()) {
            return "{}";
        }
        try {
            return JsonUtils.getJsonCodec().toJson(arguments);
        } catch (JsonException e) {
            return "{}";
        }
    }
}

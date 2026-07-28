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
package io.agentscope.extensions.model.anthropic.formatter;

import com.anthropic.core.JsonValue;
import com.anthropic.models.messages.ContentBlockParam;
import com.anthropic.models.messages.ImageBlockParam;
import com.anthropic.models.messages.MessageParam;
import com.anthropic.models.messages.MessageParam.Role;
import com.anthropic.models.messages.TextBlockParam;
import com.anthropic.models.messages.ToolResultBlockParam;
import com.anthropic.models.messages.ToolUseBlockParam;
import io.agentscope.core.message.ContentBlock;
import io.agentscope.core.message.DataBlock;
import io.agentscope.core.message.HintBlock;
import io.agentscope.core.message.ImageBlock;
import io.agentscope.core.message.Msg;
import io.agentscope.core.message.MsgRole;
import io.agentscope.core.message.TextBlock;
import io.agentscope.core.message.ThinkingBlock;
import io.agentscope.core.message.ToolResultBlock;
import io.agentscope.core.message.ToolUseBlock;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Converts AgentScope Msg objects to Anthropic SDK MessageParam types.
 *
 * <p>This class handles all message role conversions including system, user, assistant, and tool
 * messages. It supports multimodal content (text, images) and tool calling functionality.
 *
 * <p>Important: In Anthropic API, only the first message can be a system message. Non-first system
 * messages are converted to user messages.
 */
public class AnthropicMessageConverter {

    private static final Logger log = LoggerFactory.getLogger(AnthropicMessageConverter.class);

    private final AnthropicMediaConverter mediaConverter;
    private final Function<List<ContentBlock>, String> toolResultConverter;

    /**
     * Create an AnthropicMessageConverter with required dependency functions.
     *
     * @param toolResultConverter Function to convert tool result blocks to strings
     */
    public AnthropicMessageConverter(Function<List<ContentBlock>, String> toolResultConverter) {
        this.mediaConverter = new AnthropicMediaConverter();
        this.toolResultConverter = toolResultConverter;
    }

    /**
     * Convert list of Msg to list of Anthropic MessageParam. Handles the special case where tool
     * results need to be in separate user messages.
     *
     * @param messages The messages to convert
     * @return List of MessageParam for Anthropic API
     */
    public List<MessageParam> convert(List<Msg> messages) {
        List<MessageParam> result = new ArrayList<>();

        for (int i = 0; i < messages.size(); i++) {
            Msg msg = messages.get(i);
            boolean isFirstMessage = (i == 0);

            if (msg.getRole() == MsgRole.ASSISTANT
                    && msg.getContentBlocks(ToolUseBlock.class).size() > 1) {
                SplitToolResultSequence splitResults = collectSplitToolResults(messages, i + 1);
                if (shouldSplitParallelToolCalls(msg, splitResults)) {
                    result.addAll(convertParallelToolCalls(msg, splitResults));
                    i += splitResults.consumedMessages();
                    continue;
                }
            }

            // Special handling for tool results - they create separate user messages
            if (msg.hasContentBlocks(ToolResultBlock.class)) {
                // Add non-tool-result content first (if any)
                List<ContentBlock> nonToolBlocks = new ArrayList<>();
                List<ToolResultBlock> toolResults = new ArrayList<>();

                for (ContentBlock block : msg.getContent()) {
                    if (block instanceof ToolResultBlock tr) {
                        toolResults.add(tr);
                    } else {
                        nonToolBlocks.add(block);
                    }
                }

                // Add regular content if present
                if (!nonToolBlocks.isEmpty()) {
                    MessageParam regularMsg = convertMessageContent(msg, nonToolBlocks, i == 0);
                    if (regularMsg != null) {
                        result.add(regularMsg);
                    }
                }

                // Add tool results as separate user messages
                for (ToolResultBlock toolResult : toolResults) {
                    result.add(convertToolResult(toolResult));
                }
            } else {
                MessageParam param = convertMessageContent(msg, msg.getContent(), isFirstMessage);
                if (param != null) {
                    result.add(param);
                }
            }
        }

        return result;
    }

    /**
     * Decide whether the current assistant message should be expanded into Anthropic's required
     * tool-use/tool-result alternation.
     *
     * <p>Splitting is enabled only when all of the following are true:
     *
     * <ul>
     *   <li>The current message role is {@link MsgRole#ASSISTANT}.
     *   <li>At least one following pure tool-result message was discovered.
     *   <li>The current message contains two or more {@link ToolUseBlock}s.
     *   <li>Every tool-use id is present and non-blank.
     *   <li>The number of collected tool results equals the number of tool uses, and every
     *       tool-use id has a corresponding result.
     * </ul>
     */
    private boolean shouldSplitParallelToolCalls(Msg msg, SplitToolResultSequence splitResults) {
        if (msg.getRole() != MsgRole.ASSISTANT || splitResults.consumedMessages() == 0) {
            return false;
        }

        List<ToolUseBlock> toolUses = msg.getContentBlocks(ToolUseBlock.class);
        if (toolUses.size() <= 1) {
            return false;
        }

        List<String> toolUseIds = toolUses.stream().map(ToolUseBlock::getId).toList();
        if (toolUseIds.stream().anyMatch(id -> id == null || id.isBlank())) {
            return false;
        }

        return splitResults.resultsById().size() == toolUseIds.size()
                && toolUseIds.stream().allMatch(id -> splitResults.resultsById().containsKey(id));
    }

    /**
     * Collect the consecutive pure tool-result messages that immediately follow an assistant
     * message.
     *
     * <p>Scanning starts at {@code startIndex}, which is normally the message right after the
     * assistant message being inspected. The scan continues only across messages whose content is
     * made entirely of {@link ToolResultBlock}s. It stops as soon as a message has no tool results
     * or contains any non-tool-result content, because such a message must be preserved in its
     * original position rather than consumed into the split sequence.
     */
    private SplitToolResultSequence collectSplitToolResults(List<Msg> messages, int startIndex) {
        Map<String, ToolResultBlock> byId = new LinkedHashMap<>();
        int consumed = 0;

        for (int i = startIndex; i < messages.size(); i++) {
            Msg msg = messages.get(i);
            List<ToolResultBlock> toolResults = msg.getContentBlocks(ToolResultBlock.class);
            if (toolResults.isEmpty() || hasNonToolResultContent(msg)) {
                break;
            }

            consumed++;
            for (ToolResultBlock toolResult : toolResults) {
                if (toolResult.getId() != null) {
                    byId.putIfAbsent(toolResult.getId(), toolResult);
                }
            }
        }

        return new SplitToolResultSequence(byId, consumed);
    }

    private boolean hasNonToolResultContent(Msg msg) {
        return msg.getContent().stream().anyMatch(block -> !(block instanceof ToolResultBlock));
    }

    /**
     * Expand one assistant message with parallel tool uses into alternating Anthropic messages.
     *
     * <p>Each {@link ToolUseBlock} from the original assistant message is wrapped in its own
     * assistant message and immediately followed by the matching user tool-result message. Any
     * leading assistant content, such as explanatory text before the tool calls, is attached only
     * to the first split assistant message so it is not duplicated across the generated sequence.
     */
    private List<MessageParam> convertParallelToolCalls(
            Msg assistantMsg, SplitToolResultSequence splitResults) {
        List<MessageParam> converted = new ArrayList<>();
        List<ContentBlock> leadingBlocks =
                assistantMsg.getContent().stream()
                        .filter(block -> !(block instanceof ToolUseBlock))
                        .toList();
        List<ToolUseBlock> toolUses = assistantMsg.getContentBlocks(ToolUseBlock.class);

        for (int i = 0; i < toolUses.size(); i++) {
            ToolUseBlock toolUse = toolUses.get(i);
            List<ContentBlock> assistantBlocks = new ArrayList<>();
            if (i == 0) {
                assistantBlocks.addAll(leadingBlocks);
            }
            assistantBlocks.add(toolUse);

            MessageParam assistantParam =
                    convertMessageContent(assistantMsg, assistantBlocks, false);
            if (assistantParam != null) {
                converted.add(assistantParam);
            }

            ToolResultBlock toolResult = splitResults.resultsById().get(toolUse.getId());
            converted.add(convertToolResult(toolResult));
        }

        return converted;
    }

    private record SplitToolResultSequence(
            Map<String, ToolResultBlock> resultsById, int consumedMessages) {}

    /**
     * Convert message content to MessageParam.
     */
    private MessageParam convertMessageContent(
            Msg msg, List<ContentBlock> blocks, boolean isFirstMessage) {
        Role role = convertRole(msg.getRole(), isFirstMessage);
        List<ContentBlockParam> contentBlocks = new ArrayList<>();

        for (ContentBlock block : blocks) {
            if (block instanceof TextBlock tb) {
                contentBlocks.add(
                        ContentBlockParam.ofText(
                                TextBlockParam.builder().text(tb.getText()).build()));
            } else if (block instanceof HintBlock hb) {
                contentBlocks.add(
                        ContentBlockParam.ofText(
                                TextBlockParam.builder().text(hb.getHint()).build()));
            } else if (block instanceof ThinkingBlock thinkingBlock) {
                // Anthropic supports thinking blocks natively
                contentBlocks.add(
                        ContentBlockParam.ofText(
                                TextBlockParam.builder()
                                        .text(thinkingBlock.getThinking())
                                        .build()));
            } else if (block instanceof ImageBlock ib) {
                try {
                    ImageBlockParam imageParam = mediaConverter.convertImageBlock(ib);
                    contentBlocks.add(ContentBlockParam.ofImage(imageParam));
                } catch (Exception e) {
                    log.warn("Failed to process ImageBlock: {}", e.getMessage());
                    contentBlocks.add(
                            ContentBlockParam.ofText(
                                    TextBlockParam.builder()
                                            .text(
                                                    "[Image - processing failed: "
                                                            + e.getMessage()
                                                            + "]")
                                            .build()));
                }
            } else if (block instanceof DataBlock db) {
                try {
                    ImageBlockParam imageParam = mediaConverter.convertDataBlock(db);
                    contentBlocks.add(ContentBlockParam.ofImage(imageParam));
                } catch (Exception e) {
                    log.warn("Failed to process DataBlock: {}", e.getMessage());
                    contentBlocks.add(
                            ContentBlockParam.ofText(
                                    TextBlockParam.builder()
                                            .text(
                                                    "[Media - processing failed: "
                                                            + e.getMessage()
                                                            + "]")
                                            .build()));
                }
            } else if (block instanceof ToolUseBlock tub) {
                contentBlocks.add(
                        ContentBlockParam.ofToolUse(
                                ToolUseBlockParam.builder()
                                        .id(tub.getId())
                                        .name(tub.getName())
                                        .input(
                                                JsonValue.from(
                                                        tub.getInput() != null
                                                                ? tub.getInput()
                                                                : Map.of()))
                                        .build()));
            }
            // ToolResultBlock is handled separately in convert() method
        }

        if (contentBlocks.isEmpty()) {
            return null;
        }

        return MessageParam.builder()
                .role(role)
                .content(MessageParam.Content.ofBlockParams(contentBlocks))
                .build();
    }

    /**
     * Convert tool result to separate user message.
     */
    private MessageParam convertToolResult(ToolResultBlock toolResult) {
        // Convert output to content blocks
        List<ToolResultBlockParam.Content.Block> blocks = new ArrayList<>();

        Object output = toolResult.getOutput();
        if (output == null) {
            blocks.add(
                    ToolResultBlockParam.Content.Block.ofText(
                            TextBlockParam.builder().text((String) null).build()));
        } else if (output instanceof List) {
            // Multi-block output
            List<?> outputList = (List<?>) output;
            for (Object item : outputList) {
                if (item instanceof ContentBlock cb) {
                    if (cb instanceof TextBlock tb) {
                        blocks.add(
                                ToolResultBlockParam.Content.Block.ofText(
                                        TextBlockParam.builder().text(tb.getText()).build()));
                    } else if (cb instanceof ImageBlock ib) {
                        try {
                            ImageBlockParam imageParam = mediaConverter.convertImageBlock(ib);
                            blocks.add(ToolResultBlockParam.Content.Block.ofImage(imageParam));
                        } catch (Exception e) {
                            log.warn("Failed to process ImageBlock in tool result: {}", e);
                        }
                    } else if (cb instanceof DataBlock db) {
                        try {
                            ImageBlockParam imageParam = mediaConverter.convertDataBlock(db);
                            blocks.add(ToolResultBlockParam.Content.Block.ofImage(imageParam));
                        } catch (Exception e) {
                            log.warn("Failed to process DataBlock in tool result: {}", e);
                        }
                    }
                }
            }
        } else {
            // String output
            String outputStr =
                    output instanceof String
                            ? (String) output
                            : (output instanceof ContentBlock
                                    ? toolResultConverter.apply(List.of((ContentBlock) output))
                                    : output.toString());
            blocks.add(
                    ToolResultBlockParam.Content.Block.ofText(
                            TextBlockParam.builder().text(outputStr).build()));
        }

        // Create tool result block
        ToolResultBlockParam toolResultParam =
                ToolResultBlockParam.builder()
                        .toolUseId(toolResult.getId())
                        .content(ToolResultBlockParam.Content.ofBlocks(blocks))
                        .build();

        // Wrap in user message
        return MessageParam.builder()
                .role(Role.USER)
                .content(
                        MessageParam.Content.ofBlockParams(
                                List.of(ContentBlockParam.ofToolResult(toolResultParam))))
                .build();
    }

    /**
     * Convert AgentScope MsgRole to Anthropic Role. Important: Anthropic only allows the first
     * message to be system. Non-first system messages are converted to user.
     */
    private Role convertRole(MsgRole msgRole, boolean isFirstMessage) {
        return switch (msgRole) {
            case SYSTEM -> isFirstMessage ? Role.USER : Role.USER; // Anthropic uses user for
            // system messages
            case USER -> Role.USER;
            case ASSISTANT -> Role.ASSISTANT;
            case TOOL -> Role.USER; // Tool results are always user messages
        };
    }

    /**
     * Extract system message content if present in the first message.
     */
    public String extractSystemMessage(List<Msg> messages) {
        if (messages.isEmpty()) {
            return null;
        }

        Msg first = messages.get(0);
        if (first.getRole() == MsgRole.SYSTEM) {
            StringBuilder sb = new StringBuilder();
            for (ContentBlock block : first.getContent()) {
                if (block instanceof TextBlock tb) {
                    if (sb.length() > 0) {
                        sb.append("\n");
                    }
                    sb.append(tb.getText());
                }
            }
            return sb.length() > 0 ? sb.toString() : null;
        }

        return null;
    }
}

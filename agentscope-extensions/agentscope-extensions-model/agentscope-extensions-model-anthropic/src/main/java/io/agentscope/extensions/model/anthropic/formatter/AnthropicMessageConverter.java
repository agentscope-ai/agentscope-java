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
import io.agentscope.core.message.DocumentBlock;
import io.agentscope.core.message.HintBlock;
import io.agentscope.core.message.ImageBlock;
import io.agentscope.core.message.Msg;
import io.agentscope.core.message.MsgRole;
import io.agentscope.core.message.TextBlock;
import io.agentscope.core.message.ThinkingBlock;
import io.agentscope.core.message.ToolResultBlock;
import io.agentscope.core.message.ToolResultState;
import io.agentscope.core.message.ToolUseBlock;
import io.agentscope.core.tool.search.ToolSearchTool;
import java.util.ArrayList;
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
    private static final AnthropicServerToolHelper SERVER_TOOL_HELPER =
            AnthropicServerToolHelper.instance();

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
     * Convert messages while preserving assistant tool batches and grouping consecutive client
     * tool results in one user message, before any accompanying text or media.
     *
     * @param messages The messages to convert
     * @return List of MessageParam for Anthropic API
     */
    public List<MessageParam> convert(List<Msg> messages) {
        List<MessageParam> result = new ArrayList<>();

        for (int i = 0; i < messages.size(); ) {
            Msg msg = messages.get(i);
            if (!hasClientToolResults(msg)) {
                MessageParam param = convertMessageContent(msg, msg.getContent());
                if (param != null) {
                    result.add(param);
                }
                i++;
                continue;
            }

            List<ContentBlockParam> toolResults = new ArrayList<>();
            List<ContentBlockParam> trailingContent = new ArrayList<>();
            int end = i;
            do {
                msg = messages.get(end);
                List<ContentBlock> nonToolBlocks = new ArrayList<>();
                for (ContentBlock block : msg.getContent()) {
                    if (block instanceof ToolResultBlock tr && !tr.isServerTool()) {
                        toolResults.add(convertToolResult(tr));
                    } else {
                        nonToolBlocks.add(block);
                    }
                }
                MessageParam regular = convertMessageContent(msg, nonToolBlocks);
                if (regular != null) {
                    if (msg.getRole() == MsgRole.ASSISTANT) {
                        result.add(regular);
                    } else {
                        trailingContent.addAll(regular.content().asBlockParams());
                    }
                }
                end++;
            } while (end < messages.size()
                    && messages.get(end).getRole() != MsgRole.ASSISTANT
                    && hasClientToolResults(messages.get(end)));
            i = end;

            toolResults.addAll(trailingContent);
            result.add(
                    MessageParam.builder()
                            .role(Role.USER)
                            .content(MessageParam.Content.ofBlockParams(toolResults))
                            .build());
        }
        return result;
    }

    private boolean hasClientToolResults(Msg msg) {
        return msg.getContentBlocks(ToolResultBlock.class).stream()
                .anyMatch(toolResult -> !toolResult.isServerTool());
    }

    /**
     * Convert message content to MessageParam.
     */
    private MessageParam convertMessageContent(Msg msg, List<ContentBlock> blocks) {
        Role role = convertRole(msg.getRole());
        List<ContentBlockParam> contentBlocks = new ArrayList<>();

        for (ContentBlock block : blocks) {
            if (block instanceof TextBlock tb) {
                contentBlocks.add(ContentBlockParam.ofText(convertTextBlock(tb)));
            } else if (block instanceof HintBlock hb) {
                contentBlocks.add(
                        ContentBlockParam.ofText(
                                TextBlockParam.builder().text(hb.getHint()).build()));
            } else if (block instanceof ThinkingBlock thinkingBlock) {
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
            } else if (block instanceof DocumentBlock db) {
                try {
                    contentBlocks.add(
                            ContentBlockParam.ofDocument(mediaConverter.convertDocumentBlock(db)));
                } catch (Exception e) {
                    throw new IllegalArgumentException(
                            "Failed to process DocumentBlock: " + db.getId(), e);
                }
            } else if (block instanceof ToolUseBlock tub) {
                if (tub.isServerTool()) {
                    // Server tool calls are echoed back as server_tool_use blocks
                    contentBlocks.add(
                            ContentBlockParam.ofServerToolUse(SERVER_TOOL_HELPER.encodeUse(tub)));
                } else {
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
            } else if (block instanceof ToolResultBlock trb && trb.isServerTool()) {
                // Server tool results stay inline in the assistant message, echoed verbatim
                ContentBlockParam param = SERVER_TOOL_HELPER.encodeResult(trb);
                if (param != null) {
                    contentBlocks.add(param);
                }
            }
            // Client ToolResultBlock is handled separately in convert() method
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
     * Convert a client tool result to an Anthropic content block.
     */
    private ContentBlockParam convertToolResult(ToolResultBlock toolResult) {
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
                        blocks.add(ToolResultBlockParam.Content.Block.ofText(convertTextBlock(tb)));
                    } else if (cb instanceof ImageBlock ib) {
                        try {
                            ImageBlockParam imageParam = mediaConverter.convertImageBlock(ib);
                            blocks.add(ToolResultBlockParam.Content.Block.ofImage(imageParam));
                        } catch (Exception e) {
                            log.warn("Failed to process ImageBlock in tool result: {}", e);
                        }
                    } else if (cb instanceof DataBlock db) {
                        try {
                            blocks.add(
                                    ToolResultBlockParam.Content.Block.ofImage(
                                            mediaConverter.convertDataBlock(db)));
                        } catch (Exception e) {
                            log.warn("Failed to process DataBlock in tool result: {}", e);
                        }
                    } else if (cb instanceof DocumentBlock db) {
                        try {
                            blocks.add(
                                    ToolResultBlockParam.Content.Block.ofDocument(
                                            mediaConverter.convertDocumentBlock(db)));
                        } catch (Exception e) {
                            log.warn("Failed to process DocumentBlock in tool result: {}", e);
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

        if ("DEFERRED".equals(toolResult.getMetadata().get(ToolSearchTool.METADATA_MODE))
                && toolResult.getState() != ToolResultState.ERROR) {
            Object matches = toolResult.getMetadata().get(ToolSearchTool.METADATA_TOOLS);
            if (matches instanceof List<?> list) {
                for (Object match : list) {
                    if (match instanceof Map<?, ?> schema
                            && schema.get("name") instanceof String name) {
                        blocks.add(ToolResultBlockParam.Content.Block.ofToolReference(name));
                    }
                }
            }
        }

        // Create tool result block
        var resultBuilder =
                ToolResultBlockParam.builder()
                        .toolUseId(toolResult.getId())
                        .content(ToolResultBlockParam.Content.ofBlocks(blocks));
        switch (toolResult.getState()) {
            case ERROR, DENIED, INTERRUPTED -> resultBuilder.isError(true);
            default -> {}
        }
        ToolResultBlockParam toolResultParam = resultBuilder.build();

        return ContentBlockParam.ofToolResult(toolResultParam);
    }

    private TextBlockParam convertTextBlock(TextBlock textBlock) {
        return TextBlockParam.builder().text(textBlock.getText()).build();
    }

    /**
     * Convert AgentScope MsgRole to Anthropic Role. Important: Anthropic only allows the first
     * message to be system. Non-first system messages are converted to user.
     */
    private Role convertRole(MsgRole msgRole) {
        return switch (msgRole) {
            case SYSTEM -> Role.USER;
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

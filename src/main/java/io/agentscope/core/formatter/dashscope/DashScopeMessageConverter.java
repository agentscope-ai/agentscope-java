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
package io.agentscope.core.formatter.dashscope;

import com.alibaba.dashscope.common.Message;
import com.alibaba.dashscope.common.MessageContentBase;
import com.alibaba.dashscope.common.MessageContentText;
import com.alibaba.dashscope.common.MultiModalMessage;
import io.agentscope.core.message.ContentBlock;
import io.agentscope.core.message.ImageBlock;
import io.agentscope.core.message.Msg;
import io.agentscope.core.message.MsgRole;
import io.agentscope.core.message.TextBlock;
import io.agentscope.core.message.ThinkingBlock;
import io.agentscope.core.message.ToolResultBlock;
import io.agentscope.core.message.ToolUseBlock;
import io.agentscope.core.message.VideoBlock;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Converts AgentScope Msg objects to DashScope SDK message types.
 * Handles both Generation API (Message) and MultiModalConversation API (MultiModalMessage).
 */
public class DashScopeMessageConverter {

    private static final Logger log = LoggerFactory.getLogger(DashScopeMessageConverter.class);

    private final DashScopeMediaConverter mediaConverter;
    private final DashScopeToolsHelper toolsHelper;
    private final Function<List<ContentBlock>, String> toolResultConverter;

    public DashScopeMessageConverter(Function<List<ContentBlock>, String> toolResultConverter) {
        this.mediaConverter = new DashScopeMediaConverter();
        this.toolsHelper = new DashScopeToolsHelper();
        this.toolResultConverter = toolResultConverter;
    }

    /**
     * Convert single Msg to DashScope Message (for Generation API).
     */
    public Message convertToMessage(Msg msg, boolean hasMediaContent) {
        if (hasMediaContent
                && (msg.getRole() == MsgRole.USER || msg.getRole() == MsgRole.ASSISTANT)) {
            return convertToMultimodalMessage(msg);
        } else {
            return convertToSimpleMessage(msg);
        }
    }

    private Message convertToMultimodalMessage(Msg msg) {
        List<MessageContentBase> contents = new ArrayList<>();

        for (ContentBlock block : msg.getContent()) {
            if (block instanceof TextBlock tb) {
                contents.add(MessageContentText.builder().text(tb.getText()).build());
            } else if (block instanceof ImageBlock imageBlock) {
                try {
                    contents.add(mediaConverter.convertImageBlockToContentPart(imageBlock));
                } catch (Exception e) {
                    log.warn("Failed to process ImageBlock: {}", e.getMessage());
                    contents.add(
                            MessageContentText.builder()
                                    .text("[Image - processing failed: " + e.getMessage() + "]")
                                    .build());
                }
            } else if (block instanceof VideoBlock) {
                log.warn(
                        "VideoBlock is not supported by DashScope Generation API. Please use a"
                                + " multimodal model. Skipping video block.");
            } else if (block instanceof ThinkingBlock) {
                log.debug("Skipping ThinkingBlock when formatting for DashScope");
            } else if (block instanceof ToolResultBlock toolResult) {
                String toolResultText = toolResultConverter.apply(toolResult.getOutput());
                if (!toolResultText.isEmpty()) {
                    contents.add(MessageContentText.builder().text(toolResultText).build());
                }
            }
        }

        Message dsMsg =
                Message.builder()
                        .role(msg.getRole().name().toLowerCase())
                        .contents(contents)
                        .build();

        if (msg.getRole() == MsgRole.ASSISTANT) {
            List<ToolUseBlock> toolBlocks = msg.getContentBlocks(ToolUseBlock.class);
            if (!toolBlocks.isEmpty()) {
                dsMsg.setToolCalls(toolsHelper.convertToolCalls(toolBlocks));
            }
        }

        return dsMsg;
    }

    private Message convertToSimpleMessage(Msg msg) {
        Message dsMsg = new Message();
        dsMsg.setRole(msg.getRole().name().toLowerCase());

        if (msg.getRole() == MsgRole.TOOL) {
            ToolResultBlock result = msg.getFirstContentBlock(ToolResultBlock.class);
            if (result != null) {
                dsMsg.setToolCallId(result.getId());
                dsMsg.setContent(toolResultConverter.apply(result.getOutput()));
            } else {
                dsMsg.setContent(extractTextContent(msg));
            }
        } else {
            dsMsg.setContent(extractTextContent(msg));
        }

        if (msg.getRole() == MsgRole.ASSISTANT) {
            List<ToolUseBlock> toolBlocks = msg.getContentBlocks(ToolUseBlock.class);
            if (!toolBlocks.isEmpty()) {
                dsMsg.setToolCalls(toolsHelper.convertToolCalls(toolBlocks));
            }
        }

        return dsMsg;
    }

    /**
     * Convert single Msg to DashScope MultiModalMessage (for vision models).
     */
    public MultiModalMessage convertToMultiModalMessage(Msg msg) {
        List<Map<String, Object>> content = new ArrayList<>();

        for (ContentBlock block : msg.getContent()) {
            if (block instanceof TextBlock textBlock) {
                Map<String, Object> textMap = new HashMap<>();
                textMap.put("text", textBlock.getText());
                content.add(textMap);
            } else if (block instanceof ImageBlock imageBlock) {
                try {
                    content.add(mediaConverter.convertImageBlockToMap(imageBlock));
                } catch (Exception e) {
                    log.warn("Failed to process ImageBlock: {}", e.getMessage());
                    Map<String, Object> errorMap = new HashMap<>();
                    errorMap.put("text", "[Image - processing failed: " + e.getMessage() + "]");
                    content.add(errorMap);
                }
            } else if (block instanceof ToolResultBlock toolResult) {
                for (ContentBlock output : toolResult.getOutput()) {
                    if (output instanceof TextBlock textBlock) {
                        Map<String, Object> textMap = new HashMap<>();
                        textMap.put("text", textBlock.getText());
                        content.add(textMap);
                    }
                }
            } else if (block instanceof VideoBlock videoBlock) {
                try {
                    content.add(mediaConverter.convertVideoBlockToMap(videoBlock));
                } catch (Exception e) {
                    log.warn("Failed to process VideoBlock: {}", e.getMessage());
                    Map<String, Object> errorMap = new HashMap<>();
                    errorMap.put("text", "[Video - processing failed: " + e.getMessage() + "]");
                    content.add(errorMap);
                }
            }
        }

        if (content.isEmpty()) {
            Map<String, Object> emptyTextMap = new HashMap<>();
            emptyTextMap.put("text", null);
            content.add(emptyTextMap);
        }

        var builder =
                MultiModalMessage.builder()
                        .role(msg.getRole().name().toLowerCase())
                        .content(content);

        if (msg.getRole() == MsgRole.ASSISTANT) {
            List<ToolUseBlock> toolBlocks = msg.getContentBlocks(ToolUseBlock.class);
            if (!toolBlocks.isEmpty()) {
                builder.toolCalls(toolsHelper.convertToolCalls(toolBlocks));
            }
        }

        if (msg.getRole() == MsgRole.TOOL) {
            ToolResultBlock toolResult = msg.getFirstContentBlock(ToolResultBlock.class);
            if (toolResult != null) {
                builder.toolCallId(toolResult.getId());
            }
        }

        return builder.build();
    }

    private String extractTextContent(Msg msg) {
        return msg.getContent().stream()
                .filter(block -> block instanceof TextBlock)
                .map(block -> ((TextBlock) block).getText())
                .reduce("", (a, b) -> a.isEmpty() ? b : a + "\n" + b);
    }
}

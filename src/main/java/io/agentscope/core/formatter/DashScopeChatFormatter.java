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

import com.alibaba.dashscope.common.ImageURL;
import com.alibaba.dashscope.common.Message;
import com.alibaba.dashscope.common.MessageContentBase;
import com.alibaba.dashscope.common.MessageContentImageURL;
import com.alibaba.dashscope.common.MessageContentText;
import com.alibaba.dashscope.common.MultiModalMessage;
import com.alibaba.dashscope.tools.ToolCallBase;
import com.alibaba.dashscope.tools.ToolCallFunction;
import io.agentscope.core.message.AudioBlock;
import io.agentscope.core.message.Base64Source;
import io.agentscope.core.message.ContentBlock;
import io.agentscope.core.message.ImageBlock;
import io.agentscope.core.message.Msg;
import io.agentscope.core.message.MsgRole;
import io.agentscope.core.message.Source;
import io.agentscope.core.message.TextBlock;
import io.agentscope.core.message.ThinkingBlock;
import io.agentscope.core.message.ToolResultBlock;
import io.agentscope.core.message.ToolUseBlock;
import io.agentscope.core.message.URLSource;
import io.agentscope.core.message.VideoBlock;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Formatter for DashScope Conversation/Generation APIs.
 * Converts between AgentScope Msg objects and DashScope SDK types.
 */
public class DashScopeChatFormatter extends AbstractDashScopeFormatter {

    private static final Logger log = LoggerFactory.getLogger(DashScopeChatFormatter.class);

    @Override
    public List<Message> format(List<Msg> msgs) {
        List<Message> result = new ArrayList<>();
        for (Msg msg : msgs) {
            Message dsMsg = convertToMessage(msg);
            if (dsMsg != null) {
                result.add(dsMsg);
            }
        }
        return result;
    }

    private Message convertToMessage(Msg msg) {
        // Check if message has multimodal content (images)
        boolean hasMedia = hasMediaContent(msg);

        if (hasMedia && (msg.getRole() == MsgRole.USER || msg.getRole() == MsgRole.ASSISTANT)) {
            // Use multimodal format with contents()
            List<MessageContentBase> contents = new ArrayList<>();

            for (ContentBlock block : msg.getContent()) {
                if (block instanceof TextBlock tb) {
                    contents.add(MessageContentText.builder().text(tb.getText()).build());
                } else if (block instanceof ImageBlock imageBlock) {
                    try {
                        String imageUrl = convertImageBlockToUrl(imageBlock);
                        contents.add(
                                MessageContentImageURL.builder()
                                        .imageURL(ImageURL.builder().url(imageUrl).build())
                                        .build());
                    } catch (Exception e) {
                        log.warn("Failed to process ImageBlock: {}", e.getMessage());
                        contents.add(
                                MessageContentText.builder()
                                        .text("[Image - processing failed: " + e.getMessage() + "]")
                                        .build());
                    }
                } else if (block instanceof AudioBlock) {
                    log.warn("AudioBlock is not supported by DashScope Generation API");
                    contents.add(
                            MessageContentText.builder()
                                    .text("[Audio - not supported by DashScope]")
                                    .build());
                } else if (block instanceof VideoBlock) {
                    log.warn("VideoBlock is not supported by DashScope Generation API");
                    contents.add(
                            MessageContentText.builder()
                                    .text("[Video - not supported by DashScope]")
                                    .build());
                } else if (block instanceof ThinkingBlock) {
                    log.debug("Skipping ThinkingBlock when formatting for DashScope");
                } else if (block instanceof ToolResultBlock toolResult) {
                    for (ContentBlock output : toolResult.getOutput()) {
                        if (output instanceof TextBlock textBlock) {
                            contents.add(
                                    MessageContentText.builder().text(textBlock.getText()).build());
                        }
                    }
                }
            }

            Message dsMsg =
                    Message.builder()
                            .role(msg.getRole().name().toLowerCase())
                            .contents(contents)
                            .build();

            // Handle tool calls for assistant messages
            if (msg.getRole() == MsgRole.ASSISTANT) {
                List<ToolUseBlock> toolBlocks = msg.getContentBlocks(ToolUseBlock.class);
                if (!toolBlocks.isEmpty()) {
                    List<ToolCallBase> toolCalls = convertToolCalls(toolBlocks);
                    dsMsg.setToolCalls(toolCalls);
                }
            }

            return dsMsg;
        } else {
            // Use simple text format with content()
            Message dsMsg = new Message();
            dsMsg.setRole(msg.getRole().name().toLowerCase());
            dsMsg.setContent(extractTextContent(msg));

            // Handle tool calls for assistant messages
            if (msg.getRole() == MsgRole.ASSISTANT) {
                List<ToolUseBlock> toolBlocks = msg.getContentBlocks(ToolUseBlock.class);
                if (!toolBlocks.isEmpty()) {
                    List<ToolCallBase> toolCalls = convertToolCalls(toolBlocks);
                    dsMsg.setToolCalls(toolCalls);
                }
            }

            // Handle tool results for tool messages
            if (msg.getRole() == MsgRole.TOOL) {
                ToolResultBlock result = msg.getFirstContentBlock(ToolResultBlock.class);
                if (result != null) {
                    dsMsg.setToolCallId(result.getId());
                }
            }

            return dsMsg;
        }
    }

    /**
     * Convert ImageBlock to URL string for DashScope API.
     *
     * <p><b>Alignment with Python:</b> Uses file:// protocol for local files to match
     * Python implementation behavior.
     *
     * <p>Handles:
     * <ul>
     *   <li>Local files → file:// protocol URL (e.g., file:///absolute/path/image.png)
     *   <li>Remote URLs → Direct URL (e.g., https://example.com/image.png)
     *   <li>Base64 sources → Data URL (e.g., data:image/png;base64,...)
     * </ul>
     */
    private String convertImageBlockToUrl(ImageBlock imageBlock) throws Exception {
        Source source = imageBlock.getSource();

        if (source instanceof URLSource urlSource) {
            String url = urlSource.getUrl();
            MediaUtils.validateImageExtension(url);

            if (MediaUtils.isLocalFile(url)) {
                // Local file: use file:// protocol (align with Python implementation)
                return MediaUtils.toFileProtocolUrl(url);
            } else {
                // Remote URL: use directly
                return url;
            }

        } else if (source instanceof Base64Source base64Source) {
            // Base64 source: construct data URL
            String mediaType = base64Source.getMediaType();
            String base64Data = base64Source.getData();
            return String.format("data:%s;base64,%s", mediaType, base64Data);

        } else {
            throw new IllegalArgumentException("Unsupported source type: " + source.getClass());
        }
    }

    /**
     * Format AgentScope Msg objects to DashScope MultiModalMessage format.
     *
     * <p><b>Design Note:</b> This method exists because the DashScope Java SDK requires different
     * message types for Generation API ({@code List<Message>}) vs MultiModalConversation API
     * ({@code List<MultiModalMessage>}). In Python, both APIs accept the same dict format.
     *
     * <p><b>Python Alignment:</b> This formatter aligns with Python implementation by:
     * <ul>
     *   <li>Using file:// protocol for local image files (matching Python's approach)
     *   <li>Using direct URLs for remote images
     *   <li>Using data URLs for Base64-encoded images
     *   <li>Adding {"text": null} for empty content (matching Python behavior)
     * </ul>
     *
     * <p>MultiModalConversation API requires content as {@code List<Map<String, Object>>} where
     * each map contains either:
     * <ul>
     *   <li>{@code {"text": "..."}} for text content
     *   <li>{@code {"image": "url"}} for images
     *   <li>{@code {"audio": "url"}} for audio (not yet supported)
     * </ul>
     *
     * @param messages The AgentScope messages to convert
     * @return List of MultiModalMessage objects ready for DashScope API
     */
    public List<MultiModalMessage> formatMultiModal(List<Msg> messages) {
        List<MultiModalMessage> result = new ArrayList<>();

        for (Msg msg : messages) {
            List<Map<String, Object>> content = new ArrayList<>();

            // Process content blocks
            for (ContentBlock block : msg.getContent()) {
                if (block instanceof TextBlock textBlock) {
                    Map<String, Object> textMap = new HashMap<>();
                    textMap.put("text", textBlock.getText());
                    content.add(textMap);

                } else if (block instanceof ImageBlock imageBlock) {
                    try {
                        String imageUrl = convertImageBlockToUrl(imageBlock);
                        Map<String, Object> imageMap = new HashMap<>();
                        imageMap.put("image", imageUrl);
                        content.add(imageMap);
                    } catch (Exception e) {
                        log.warn("Failed to process ImageBlock: {}", e.getMessage());
                        Map<String, Object> errorMap = new HashMap<>();
                        errorMap.put("text", "[Image - processing failed: " + e.getMessage() + "]");
                        content.add(errorMap);
                    }
                } else if (block instanceof ToolResultBlock toolResult) {
                    // Extract text from tool result output
                    for (ContentBlock output : toolResult.getOutput()) {
                        if (output instanceof TextBlock textBlock) {
                            Map<String, Object> textMap = new HashMap<>();
                            textMap.put("text", textBlock.getText());
                            content.add(textMap);
                        }
                    }
                }
                // Note: AudioBlock and VideoBlock not supported by DashScope
                // MultiModalConversation
                // ToolUseBlock is handled separately below
            }

            // Align with Python: if content is empty, add {"text": null}
            if (content.isEmpty()) {
                Map<String, Object> emptyTextMap = new HashMap<>();
                emptyTextMap.put("text", null);
                content.add(emptyTextMap);
            }

            var builder =
                    MultiModalMessage.builder()
                            .role(msg.getRole().name().toLowerCase())
                            .content(content);

            // Handle tool calls for assistant messages
            if (msg.getRole() == MsgRole.ASSISTANT) {
                List<ToolUseBlock> toolBlocks = msg.getContentBlocks(ToolUseBlock.class);
                if (!toolBlocks.isEmpty()) {
                    List<ToolCallBase> toolCalls = convertToolCallsForMultiModal(toolBlocks);
                    builder.toolCalls(toolCalls);
                }
            }

            // Handle tool results for tool messages
            if (msg.getRole() == MsgRole.TOOL) {
                ToolResultBlock toolResult = msg.getFirstContentBlock(ToolResultBlock.class);
                if (toolResult != null) {
                    builder.toolCallId(toolResult.getId());
                }
            }

            result.add(builder.build());
        }

        return result;
    }

    /**
     * Convert ToolUseBlock list to DashScope ToolCallBase format for MultiModalConversation API.
     *
     * @param toolBlocks The tool use blocks to convert
     * @return List of ToolCallBase objects for DashScope API
     */
    private List<ToolCallBase> convertToolCallsForMultiModal(List<ToolUseBlock> toolBlocks) {
        List<ToolCallBase> result = new ArrayList<>();

        for (ToolUseBlock block : toolBlocks) {
            ToolCallFunction tcf = new ToolCallFunction();
            tcf.setId(block.getId());
            tcf.setType("function");

            // Create CallFunction as inner class instance
            ToolCallFunction.CallFunction cf = tcf.new CallFunction();
            cf.setName(block.getName());

            // Serialize input map to JSON string
            try {
                String argsJson = objectMapper.writeValueAsString(block.getInput());
                cf.setArguments(argsJson);
            } catch (Exception e) {
                log.warn("Failed to serialize tool arguments: {}", e.getMessage());
                cf.setArguments("{}");
            }

            tcf.setFunction(cf);
            result.add(tcf);
        }

        return result;
    }

    @Override
    public FormatterCapabilities getCapabilities() {
        return FormatterCapabilities.builder()
                .providerName("DashScope")
                .supportToolsApi(true)
                .supportMultiAgent(false)
                .supportVision(true)
                .supportedBlocks(
                        Set.of(
                                TextBlock.class,
                                ToolUseBlock.class,
                                ToolResultBlock.class,
                                ThinkingBlock.class,
                                ImageBlock.class))
                .build();
    }
}

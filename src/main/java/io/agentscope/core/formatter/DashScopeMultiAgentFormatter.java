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
import com.alibaba.dashscope.tools.ToolCallBase;
import com.alibaba.dashscope.tools.ToolCallFunction;
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
 * DashScope formatter for multi-agent conversations.
 * Converts AgentScope Msg objects to DashScope SDK Message objects with multi-agent support.
 * Collapses multi-agent conversation into a single user message with history tags.
 *
 * <p><b>ThinkingBlock Handling:</b> ThinkingBlock content is filtered out and NOT sent to
 * DashScope API. It is stored in memory but excluded from all formatted messages.
 */
public class DashScopeMultiAgentFormatter extends AbstractDashScopeFormatter {

    private static final Logger log = LoggerFactory.getLogger(DashScopeMultiAgentFormatter.class);
    private static final String HISTORY_START_TAG = "<history>";
    private static final String HISTORY_END_TAG = "</history>";
    private static final String DEFAULT_CONVERSATION_HISTORY_PROMPT =
            "# Conversation History\n"
                    + "The content between <history></history> tags contains your conversation"
                    + " history\n";
    private final String conversationHistoryPrompt;

    /**
     * Create a DashScopeMultiAgentFormatter with default conversation history prompt.
     */
    public DashScopeMultiAgentFormatter() {
        this(DEFAULT_CONVERSATION_HISTORY_PROMPT);
    }

    /**
     * Create a DashScopeMultiAgentFormatter with custom conversation history prompt.
     *
     * @param conversationHistoryPrompt The prompt to prepend before conversation history
     */
    public DashScopeMultiAgentFormatter(String conversationHistoryPrompt) {
        this.conversationHistoryPrompt =
                conversationHistoryPrompt != null
                        ? conversationHistoryPrompt
                        : DEFAULT_CONVERSATION_HISTORY_PROMPT;
    }

    @Override
    public List<Message> format(List<Msg> msgs) {
        List<Message> result = new ArrayList<>();

        // Separate tool sequences from conversation
        List<Msg> conversation = new ArrayList<>();
        List<Msg> toolSeq = new ArrayList<>();

        for (Msg msg : msgs) {
            if (msg.getRole() == MsgRole.TOOL
                    || (msg.getRole() == MsgRole.ASSISTANT
                            && msg.hasContentBlocks(ToolUseBlock.class))) {
                toolSeq.add(msg);
            } else {
                conversation.add(msg);
            }
        }

        if (!conversation.isEmpty()) {
            result.add(formatAgentConversation(conversation));
        }
        if (!toolSeq.isEmpty()) {
            result.addAll(formatToolSeq(toolSeq));
        }
        return result;
    }

    private Message formatAgentConversation(List<Msg> msgs) {
        // Build conversation text history with agent names
        StringBuilder textAccumulator = new StringBuilder();
        textAccumulator.append(conversationHistoryPrompt);
        textAccumulator.append(HISTORY_START_TAG).append("\n");

        // Collect images separately for multimodal support
        List<MessageContentImageURL> imageContents = new ArrayList<>();

        for (Msg msg : msgs) {
            String name = msg.getName() != null ? msg.getName() : "Unknown";
            String role = formatRoleLabel(msg.getRole());

            List<ContentBlock> blocks = msg.getContent();
            for (ContentBlock block : blocks) {
                if (block instanceof TextBlock tb) {
                    textAccumulator
                            .append(role)
                            .append(" ")
                            .append(name)
                            .append(": ")
                            .append(tb.getText())
                            .append("\n");
                } else if (block instanceof ImageBlock imageBlock) {
                    // Preserve images for multimodal content
                    try {
                        String imageUrl = convertImageBlockToUrl(imageBlock);
                        imageContents.add(
                                MessageContentImageURL.builder()
                                        .imageURL(ImageURL.builder().url(imageUrl).build())
                                        .build());
                        textAccumulator
                                .append(role)
                                .append(" ")
                                .append(name)
                                .append(": [Image]\\n");
                    } catch (Exception e) {
                        log.warn("Failed to process ImageBlock: {}", e.getMessage());
                        textAccumulator
                                .append(role)
                                .append(" ")
                                .append(name)
                                .append(": [Image - processing failed]\\n");
                    }
                } else if (block instanceof VideoBlock videoBlock) {
                    try {
                        String videoUrl = convertVideoBlockToUrl(videoBlock);
                        // Add video URL to text
                        textAccumulator
                                .append(role)
                                .append(" ")
                                .append(name)
                                .append(": [Video: ")
                                .append(videoUrl)
                                .append("]\\n");
                        log.debug("Processed VideoBlock in multi-agent conversation: {}", videoUrl);
                    } catch (Exception e) {
                        log.warn("Failed to process VideoBlock: {}", e.getMessage());
                        textAccumulator
                                .append(role)
                                .append(" ")
                                .append(name)
                                .append(": [Video - processing failed]\\n");
                    }
                } else if (block instanceof ThinkingBlock) {
                    log.debug("Skipping ThinkingBlock in multi-agent conversation");
                } else if (block instanceof ToolResultBlock toolResult) {
                    // Use convertToolResultToString to handle multimodal content
                    String resultText = convertToolResultToString(toolResult.getOutput());
                    String finalResultText =
                            !resultText.isEmpty() ? resultText : "[Empty tool result]";
                    textAccumulator
                            .append(role)
                            .append(" ")
                            .append(name)
                            .append(" (")
                            .append(toolResult.getName())
                            .append("): ")
                            .append(finalResultText)
                            .append("\n");
                }
            }
        }

        textAccumulator.append(HISTORY_END_TAG);

        // Build the message with multimodal content if needed
        if (imageContents.isEmpty()) {
            // No images - use simple text format
            Message message = new Message();
            message.setRole("user");
            message.setContent(textAccumulator.toString());
            return message;
        } else {
            // Has images - use multimodal format with contents()
            List<MessageContentBase> contents = new ArrayList<>();
            // First add the text conversation history
            contents.add(MessageContentText.builder().text(textAccumulator.toString()).build());
            // Then add all images
            contents.addAll(imageContents);

            return Message.builder().role("user").contents(contents).build();
        }
    }

    private List<Message> formatToolSeq(List<Msg> msgs) {
        List<Message> result = new ArrayList<>();
        for (Msg msg : msgs) {
            if (msg.getRole() == MsgRole.ASSISTANT) {
                result.add(formatAssistantToolCall(msg));
            } else if (msg.getRole() == MsgRole.TOOL) {
                result.add(formatToolResult(msg));
            }
        }
        return result;
    }

    private Message formatAssistantToolCall(Msg msg) {
        Message message = new Message();
        message.setRole("assistant");
        message.setContent(extractTextContent(msg));

        // Handle tool calls
        List<ToolUseBlock> toolBlocks = msg.getContentBlocks(ToolUseBlock.class);
        if (!toolBlocks.isEmpty()) {
            List<ToolCallBase> toolCalls = new ArrayList<>();
            for (ToolUseBlock toolUse : toolBlocks) {
                ToolCallFunction tcf = new ToolCallFunction();
                tcf.setId(toolUse.getId());

                ToolCallFunction.CallFunction cf = tcf.new CallFunction();
                cf.setName(toolUse.getName());

                try {
                    String argsJson = objectMapper.writeValueAsString(toolUse.getInput());
                    cf.setArguments(argsJson);
                } catch (Exception e) {
                    log.warn("Failed to serialize tool call arguments: {}", e.getMessage());
                    cf.setArguments("{}");
                }

                tcf.setFunction(cf);
                toolCalls.add(tcf);

                log.debug(
                        "Formatted multi-agent tool call: id={}, name={}",
                        toolUse.getId(),
                        toolUse.getName());
            }
            message.setToolCalls(toolCalls);
        }

        return message;
    }

    private Message formatToolResult(Msg msg) {
        Message message = new Message();
        message.setRole("tool");

        ToolResultBlock result = msg.getFirstContentBlock(ToolResultBlock.class);
        if (result != null) {
            message.setToolCallId(result.getId());
            // Use convertToolResultToString to handle multimodal content
            message.setContent(convertToolResultToString(result.getOutput()));
        } else {
            message.setToolCallId("tool_call_" + System.currentTimeMillis());
            message.setContent(extractTextContent(msg));
        }

        return message;
    }

    /**
     * Convert VideoBlock to URL string for DashScope (matching ImageBlock behavior).
     * For local files, uses file:// protocol URL.
     * For remote URLs, uses directly.
     */
    private String convertVideoBlockToUrl(VideoBlock videoBlock) throws Exception {
        Source source = videoBlock.getSource();

        if (source instanceof URLSource urlSource) {
            String url = urlSource.getUrl();
            MediaUtils.validateVideoExtension(url);

            if (MediaUtils.isLocalFile(url)) {
                return MediaUtils.toFileProtocolUrl(url);
            } else {
                return url;
            }
        } else if (source instanceof Base64Source base64Source) {
            String mediaType = base64Source.getMediaType();
            String base64Data = base64Source.getData();
            return String.format("data:%s;base64,%s", mediaType, base64Data);
        } else {
            throw new IllegalArgumentException("Unsupported source type: " + source.getClass());
        }
    }

    /**
     * Convert ImageBlock to URL string for DashScope.
     * For local files, converts to base64 data URL.
     * For remote URLs, uses directly.
     */
    private String convertImageBlockToUrl(ImageBlock imageBlock) throws Exception {
        Source source = imageBlock.getSource();

        if (source instanceof URLSource urlSource) {
            String url = urlSource.getUrl();
            MediaUtils.validateImageExtension(url);

            if (MediaUtils.isLocalFile(url)) {
                return MediaUtils.urlToBase64DataUrl(url);
            } else {
                return url;
            }
        } else if (source instanceof Base64Source base64Source) {
            String mediaType = base64Source.getMediaType();
            String base64Data = base64Source.getData();
            return String.format("data:%s;base64,%s", mediaType, base64Data);
        } else {
            throw new IllegalArgumentException("Unsupported source type: " + source.getClass());
        }
    }

    @Override
    public List<com.alibaba.dashscope.common.MultiModalMessage> formatMultiModal(List<Msg> msgs) {
        List<com.alibaba.dashscope.common.MultiModalMessage> result = new ArrayList<>();

        // Separate tool sequences from conversation (same logic as format())
        List<Msg> conversation = new ArrayList<>();
        List<Msg> toolSeq = new ArrayList<>();

        for (Msg msg : msgs) {
            if (msg.getRole() == MsgRole.TOOL
                    || (msg.getRole() == MsgRole.ASSISTANT
                            && msg.hasContentBlocks(ToolUseBlock.class))) {
                toolSeq.add(msg);
            } else {
                conversation.add(msg);
            }
        }

        if (!conversation.isEmpty()) {
            result.add(formatMultiModalAgentConversation(conversation));
        }
        if (!toolSeq.isEmpty()) {
            result.addAll(formatMultiModalToolSeq(toolSeq));
        }
        return result;
    }

    /**
     * Format multi-agent conversation messages into a single MultiModalMessage.
     * Similar to formatAgentConversation() but returns MultiModalMessage with content as
     * List<Map<String, Object>>.
     *
     * <p>This formatter merges conversation messages with this behavior:
     * - Merges all conversation messages into one
     * - Wraps text in <history> tags
     * - Embeds agent names in text
     * - Preserves images/videos as separate content blocks
     */
    private com.alibaba.dashscope.common.MultiModalMessage formatMultiModalAgentConversation(
            List<Msg> msgs) {
        List<Map<String, Object>> content = new ArrayList<>();
        List<String> accumulatedText = new ArrayList<>();

        // Add conversation history prompt
        accumulatedText.add(conversationHistoryPrompt + HISTORY_START_TAG);

        for (Msg msg : msgs) {
            String name = msg.getName() != null ? msg.getName() : "Unknown";
            String role = formatRoleLabel(msg.getRole());

            for (ContentBlock block : msg.getContent()) {
                if (block instanceof TextBlock tb) {
                    // Accumulate text with agent name
                    accumulatedText.add(role + " " + name + ": " + tb.getText());

                } else if (block instanceof ImageBlock imageBlock) {
                    // Flush accumulated text before adding image
                    if (!accumulatedText.isEmpty()) {
                        Map<String, Object> textMap = new HashMap<>();
                        textMap.put("text", String.join("\n", accumulatedText));
                        content.add(textMap);
                        accumulatedText.clear();
                    }

                    // Add image as separate content block
                    try {
                        String imageUrl = convertImageBlockToUrl(imageBlock);
                        Map<String, Object> imageMap = new HashMap<>();
                        imageMap.put("image", imageUrl);
                        content.add(imageMap);
                    } catch (Exception e) {
                        log.warn("Failed to process ImageBlock in multimodal: {}", e.getMessage());
                        Map<String, Object> errorMap = new HashMap<>();
                        errorMap.put("text", "[Image - processing failed: " + e.getMessage() + "]");
                        content.add(errorMap);
                    }

                } else if (block instanceof VideoBlock videoBlock) {
                    // Flush accumulated text before adding video
                    if (!accumulatedText.isEmpty()) {
                        Map<String, Object> textMap = new HashMap<>();
                        textMap.put("text", String.join("\n", accumulatedText));
                        content.add(textMap);
                        accumulatedText.clear();
                    }

                    // Add video as separate content block
                    try {
                        String videoUrl = convertVideoBlockToUrl(videoBlock);
                        Map<String, Object> videoMap = new HashMap<>();
                        videoMap.put("video", videoUrl);
                        content.add(videoMap);
                    } catch (Exception e) {
                        log.warn("Failed to process VideoBlock in multimodal: {}", e.getMessage());
                        Map<String, Object> errorMap = new HashMap<>();
                        errorMap.put("text", "[Video - processing failed: " + e.getMessage() + "]");
                        content.add(errorMap);
                    }

                } else if (block instanceof ThinkingBlock) {
                    log.debug("Skipping ThinkingBlock in multi-agent multimodal formatting");

                } else if (block instanceof ToolResultBlock toolResult) {
                    // Add tool result as text
                    String resultText = convertToolResultToString(toolResult.getOutput());
                    String finalResultText =
                            !resultText.isEmpty() ? resultText : "[Empty tool result]";
                    accumulatedText.add(
                            role
                                    + " "
                                    + name
                                    + " ("
                                    + toolResult.getName()
                                    + "): "
                                    + finalResultText);
                }
            }
        }

        // Close history tag and flush remaining text
        accumulatedText.add(HISTORY_END_TAG);
        if (!accumulatedText.isEmpty()) {
            Map<String, Object> textMap = new HashMap<>();
            textMap.put("text", String.join("\n", accumulatedText));
            content.add(textMap);
        }

        // If content is empty, add {"text": null}
        if (content.isEmpty()) {
            Map<String, Object> emptyTextMap = new HashMap<>();
            emptyTextMap.put("text", null);
            content.add(emptyTextMap);
        }

        return com.alibaba.dashscope.common.MultiModalMessage.builder()
                .role("user")
                .content(content)
                .build();
    }

    /**
     * Format tool sequence messages to MultiModalMessage format.
     * Similar to formatToolSeq() but returns List<MultiModalMessage>.
     */
    private List<com.alibaba.dashscope.common.MultiModalMessage> formatMultiModalToolSeq(
            List<Msg> msgs) {
        List<com.alibaba.dashscope.common.MultiModalMessage> result = new ArrayList<>();
        for (Msg msg : msgs) {
            if (msg.getRole() == MsgRole.ASSISTANT) {
                result.add(formatMultiModalAssistantToolCall(msg));
            } else if (msg.getRole() == MsgRole.TOOL) {
                result.add(formatMultiModalToolResult(msg));
            }
        }
        return result;
    }

    /**
     * Format assistant message with tool calls to MultiModalMessage.
     */
    private com.alibaba.dashscope.common.MultiModalMessage formatMultiModalAssistantToolCall(
            Msg msg) {
        List<Map<String, Object>> content = new ArrayList<>();

        // Extract text content
        String textContent = extractTextContent(msg);
        if (textContent != null && !textContent.isEmpty()) {
            Map<String, Object> textMap = new HashMap<>();
            textMap.put("text", textContent);
            content.add(textMap);
        }

        // If content is empty, add {"text": null}
        if (content.isEmpty()) {
            Map<String, Object> emptyTextMap = new HashMap<>();
            emptyTextMap.put("text", null);
            content.add(emptyTextMap);
        }

        var builder =
                com.alibaba.dashscope.common.MultiModalMessage.builder()
                        .role("assistant")
                        .content(content);

        // Handle tool calls
        List<ToolUseBlock> toolBlocks = msg.getContentBlocks(ToolUseBlock.class);
        if (!toolBlocks.isEmpty()) {
            List<ToolCallBase> toolCalls = convertToolCalls(toolBlocks);
            builder.toolCalls(toolCalls);
        }

        return builder.build();
    }

    /**
     * Format tool result message to MultiModalMessage.
     */
    private com.alibaba.dashscope.common.MultiModalMessage formatMultiModalToolResult(Msg msg) {
        List<Map<String, Object>> content = new ArrayList<>();

        ToolResultBlock result = msg.getFirstContentBlock(ToolResultBlock.class);
        String toolCallId;

        if (result != null) {
            toolCallId = result.getId();
            String resultText = convertToolResultToString(result.getOutput());
            Map<String, Object> textMap = new HashMap<>();
            textMap.put("text", resultText);
            content.add(textMap);
        } else {
            toolCallId = "tool_call_" + System.currentTimeMillis();
            String textContent = extractTextContent(msg);
            Map<String, Object> textMap = new HashMap<>();
            textMap.put("text", textContent);
            content.add(textMap);
        }

        return com.alibaba.dashscope.common.MultiModalMessage.builder()
                .role("tool")
                .content(content)
                .toolCallId(toolCallId)
                .build();
    }

    @Override
    public FormatterCapabilities getCapabilities() {
        return FormatterCapabilities.builder()
                .providerName("DashScope")
                .supportToolsApi(true)
                .supportMultiAgent(true)
                .supportVision(true)
                .supportedBlocks(
                        Set.of(
                                TextBlock.class,
                                ToolUseBlock.class,
                                ToolResultBlock.class,
                                ThinkingBlock.class,
                                ImageBlock.class,
                                VideoBlock.class))
                .build();
    }
}

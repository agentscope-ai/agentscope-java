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
import com.alibaba.dashscope.common.MessageContentImageURL;
import com.alibaba.dashscope.common.MessageContentText;
import com.alibaba.dashscope.common.MultiModalMessage;
import io.agentscope.core.message.AudioBlock;
import io.agentscope.core.message.ContentBlock;
import io.agentscope.core.message.ImageBlock;
import io.agentscope.core.message.Msg;
import io.agentscope.core.message.TextBlock;
import io.agentscope.core.message.ThinkingBlock;
import io.agentscope.core.message.ToolResultBlock;
import io.agentscope.core.message.VideoBlock;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Merges multi-agent conversation messages for DashScope API.
 * Handles both Generation API (Message) and MultiModalConversation API (MultiModalMessage).
 *
 * <p>This class consolidates multiple agent messages into a single message with conversation
 * history wrapped in special tags. It preserves agent names, roles, and multimodal content
 * (images/videos).
 */
public class DashScopeConversationMerger {

    private static final Logger log = LoggerFactory.getLogger(DashScopeConversationMerger.class);

    private static final String HISTORY_START_TAG = "<history>";
    private static final String HISTORY_END_TAG = "</history>";

    private final DashScopeMediaConverter mediaConverter;
    private final String conversationHistoryPrompt;

    /**
     * Create a DashScopeConversationMerger with custom conversation history prompt.
     *
     * @param conversationHistoryPrompt The prompt to prepend before conversation history
     */
    public DashScopeConversationMerger(String conversationHistoryPrompt) {
        this.mediaConverter = new DashScopeMediaConverter();
        this.conversationHistoryPrompt = conversationHistoryPrompt;
    }

    /**
     * Merge conversation messages into a single Message (for Generation API).
     *
     * <p>This method combines all agent messages into a single user message with conversation
     * history wrapped in {@code <history>} tags. Agent names and roles are embedded in the text.
     *
     * @param msgs List of conversation messages to merge
     * @param nameExtractor Function to extract agent name from message
     * @param toolResultConverter Function to convert tool result blocks to strings
     * @param historyPrompt The prompt to prepend (empty if not first group)
     * @return Single merged Message for DashScope Generation API
     */
    public Message mergeToMessage(
            List<Msg> msgs,
            Function<Msg, String> nameExtractor,
            Function<List<ContentBlock>, String> toolResultConverter,
            String historyPrompt) {

        // Build conversation text history with agent names
        StringBuilder textAccumulator = new StringBuilder();
        if (!historyPrompt.isEmpty()) {
            textAccumulator.append(historyPrompt);
        }
        textAccumulator.append(HISTORY_START_TAG).append("\n");

        // Collect images separately for multimodal support
        List<MessageContentImageURL> imageContents = new ArrayList<>();

        for (Msg msg : msgs) {
            String name = nameExtractor.apply(msg);

            List<ContentBlock> blocks = msg.getContent();
            for (ContentBlock block : blocks) {
                if (block instanceof TextBlock tb) {
                    textAccumulator.append(name).append(": ").append(tb.getText()).append("\n");

                } else if (block instanceof ImageBlock imageBlock) {
                    // Preserve images for multimodal content
                    try {
                        MessageContentImageURL imageContent =
                                mediaConverter.convertImageBlockToContentPart(imageBlock);
                        imageContents.add(imageContent);
                        textAccumulator.append(name).append(": [Image]\n");
                    } catch (Exception e) {
                        log.warn("Failed to process ImageBlock: {}", e.getMessage());
                        textAccumulator.append(name).append(": [Image - processing failed]\n");
                    }

                } else if (block instanceof VideoBlock videoBlock) {
                    try {
                        String videoUrl = mediaConverter.convertVideoBlockToUrl(videoBlock);
                        // Add video URL to text
                        textAccumulator
                                .append(name)
                                .append(": [Video: ")
                                .append(videoUrl)
                                .append("]\n");
                        log.debug("Processed VideoBlock in multi-agent conversation: {}", videoUrl);
                    } catch (Exception e) {
                        log.warn("Failed to process VideoBlock: {}", e.getMessage());
                        textAccumulator.append(name).append(": [Video - processing failed]\n");
                    }

                } else if (block instanceof ThinkingBlock) {
                    log.debug("Skipping ThinkingBlock in multi-agent conversation");

                } else if (block instanceof ToolResultBlock toolResult) {
                    // Use provided converter to handle multimodal content in tool results
                    String resultText = toolResultConverter.apply(toolResult.getOutput());
                    String finalResultText =
                            !resultText.isEmpty() ? resultText : "[Empty tool result]";
                    textAccumulator
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

    /**
     * Merge conversation messages into a single MultiModalMessage (for vision models).
     * Follows Python's _format_agent_message logic exactly.
     *
     * <p>This method combines all agent messages into a single user message with conversation
     * history wrapped in {@code <history>} tags. Images and videos are preserved as separate
     * content blocks in the MultiModalMessage format.
     *
     * @param msgs List of conversation messages to merge
     * @param nameExtractor Function to extract agent name from message
     * @param toolResultConverter Function to convert tool result blocks to strings
     * @param isFirst Whether this is the first agent message group (includes history prompt if true)
     * @return Single merged MultiModalMessage for DashScope MultiModalConversation API
     */
    public MultiModalMessage mergeToMultiModalMessage(
            List<Msg> msgs,
            Function<Msg, String> nameExtractor,
            Function<List<ContentBlock>, String> toolResultConverter,
            boolean isFirst) {

        List<Map<String, Object>> content = new ArrayList<>();
        List<String> accumulatedText = new ArrayList<>();

        // Add conversation history prompt (only for first agent message group)
        if (isFirst) {
            accumulatedText.add(conversationHistoryPrompt + HISTORY_START_TAG);
        } else {
            accumulatedText.add(HISTORY_START_TAG);
        }

        for (Msg msg : msgs) {
            String name = nameExtractor.apply(msg);

            for (ContentBlock block : msg.getContent()) {
                if (block instanceof TextBlock tb) {
                    // Accumulate text with agent name (Python format: "name: text")
                    accumulatedText.add(name + ": " + tb.getText());

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
                        Map<String, Object> imageMap =
                                mediaConverter.convertImageBlockToMap(imageBlock);
                        content.add(imageMap);
                    } catch (Exception e) {
                        log.warn("Failed to process ImageBlock in multimodal: {}", e.getMessage());
                        Map<String, Object> errorMap = new HashMap<>();
                        errorMap.put("text", "[Image - processing failed: " + e.getMessage() + "]");
                        content.add(errorMap);
                    }

                } else if (block instanceof AudioBlock audioBlock) {
                    // Flush accumulated text before adding audio
                    if (!accumulatedText.isEmpty()) {
                        Map<String, Object> textMap = new HashMap<>();
                        textMap.put("text", String.join("\n", accumulatedText));
                        content.add(textMap);
                        accumulatedText.clear();
                    }

                    // Add audio as separate content block
                    try {
                        Map<String, Object> audioMap =
                                mediaConverter.convertAudioBlockToMap(audioBlock);
                        content.add(audioMap);
                    } catch (Exception e) {
                        log.warn("Failed to process AudioBlock in multimodal: {}", e.getMessage());
                        Map<String, Object> errorMap = new HashMap<>();
                        errorMap.put("text", "[Audio - processing failed: " + e.getMessage() + "]");
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
                        Map<String, Object> videoMap =
                                mediaConverter.convertVideoBlockToMap(videoBlock);
                        content.add(videoMap);
                    } catch (Exception e) {
                        log.warn("Failed to process VideoBlock in multimodal: {}", e.getMessage());
                        Map<String, Object> errorMap = new HashMap<>();
                        errorMap.put("text", "[Video - processing failed: " + e.getMessage() + "]");
                        content.add(errorMap);
                    }

                } else if (block instanceof ThinkingBlock) {
                    log.debug("Skipping ThinkingBlock in multi-agent multimodal formatting");

                } else if (block instanceof ToolResultBlock) {
                    // Tool results should not appear in agent messages
                    // They are handled separately in tool_sequence groups
                    log.warn("Unexpected ToolResultBlock in agent message group, skipping");
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

        return MultiModalMessage.builder().role("user").content(content).build();
    }
}

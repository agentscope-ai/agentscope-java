/*
 * Copyright 2024-2025 the original author or authors.
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
package io.agentscope.core.formatter.openai;

import com.openai.models.chat.completions.ChatCompletionContentPart;
import com.openai.models.chat.completions.ChatCompletionContentPartText;
import com.openai.models.chat.completions.ChatCompletionUserMessageParam;
import io.agentscope.core.message.AudioBlock;
import io.agentscope.core.message.ContentBlock;
import io.agentscope.core.message.ImageBlock;
import io.agentscope.core.message.Msg;
import io.agentscope.core.message.TextBlock;
import io.agentscope.core.message.ThinkingBlock;
import io.agentscope.core.message.ToolResultBlock;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Function;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Merges multi-agent conversation messages for OpenAI API.
 * Consolidates multiple agent messages into single user messages with history tags.
 *
 * <p>This class combines all agent messages into a single user message with conversation
 * history wrapped in special tags. Images and audio are preserved as separate ContentParts.
 */
public class OpenAIConversationMerger {

    private static final Logger log = LoggerFactory.getLogger(OpenAIConversationMerger.class);

    private static final String HISTORY_START_TAG = "<history>";
    private static final String HISTORY_END_TAG = "</history>";

    private final OpenAIMediaConverter mediaConverter;
    private final String conversationHistoryPrompt;

    /**
     * Create an OpenAIConversationMerger with custom conversation history prompt.
     *
     * @param conversationHistoryPrompt The prompt to prepend before conversation history
     */
    public OpenAIConversationMerger(String conversationHistoryPrompt) {
        this.mediaConverter = new OpenAIMediaConverter();
        this.conversationHistoryPrompt = conversationHistoryPrompt;
    }

    /**
     * Merge conversation messages into a single ChatCompletionUserMessageParam.
     *
     * <p>This method combines all agent messages into a single user message with conversation
     * history wrapped in {@code <history>} tags. Images and audio are preserved as separate
     * ContentParts in the multimodal format.
     *
     * @param msgs List of conversation messages to merge
     * @param roleFormatter Function to format role labels (e.g., USER → "User")
     * @param toolResultConverter Function to convert tool result blocks to strings
     * @return Single merged ChatCompletionUserMessageParam for OpenAI API
     */
    public ChatCompletionUserMessageParam mergeToUserMessage(
            List<Msg> msgs,
            Function<Msg, String> roleFormatter,
            Function<List<ContentBlock>, String> toolResultConverter) {

        // Build conversation text history with agent names
        StringBuilder conversationHistory = new StringBuilder();
        conversationHistory.append(conversationHistoryPrompt);
        conversationHistory.append(HISTORY_START_TAG).append("\n");

        // Collect multimodal content (images/audio) separately
        List<ChatCompletionContentPart> multimodalParts = new ArrayList<>();

        for (Msg msg : msgs) {
            String agentName = msg.getName() != null ? msg.getName() : "Unknown";
            String roleLabel = roleFormatter.apply(msg);

            // Process all blocks: text goes to history, images/audio go to ContentParts
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

                } else if (block instanceof ImageBlock imageBlock) {
                    // Preserve images as ContentParts
                    // Note: Do NOT add "[Image]" marker to conversation history text
                    try {
                        multimodalParts.add(
                                mediaConverter.convertImageBlockToContentPart(imageBlock));
                    } catch (Exception e) {
                        log.warn("Failed to process ImageBlock: {}", e.getMessage());
                        conversationHistory
                                .append(roleLabel)
                                .append(" ")
                                .append(agentName)
                                .append(": [Image - processing failed]\n");
                    }

                } else if (block instanceof AudioBlock audioBlock) {
                    // Preserve audio as ContentParts
                    // Note: Do NOT add "[Audio]" marker to conversation history text
                    try {
                        multimodalParts.add(
                                mediaConverter.convertAudioBlockToContentPart(audioBlock));
                    } catch (Exception e) {
                        log.warn("Failed to process AudioBlock: {}", e.getMessage());
                        conversationHistory
                                .append(roleLabel)
                                .append(" ")
                                .append(agentName)
                                .append(": [Audio - processing failed]\n");
                    }

                } else if (block instanceof ThinkingBlock) {
                    // IMPORTANT: ThinkingBlock is NOT included in conversation history
                    log.debug("Skipping ThinkingBlock in multi-agent conversation for OpenAI API");

                } else if (block instanceof ToolResultBlock toolResult) {
                    // Use provided converter to handle multimodal content in tool results
                    String resultText = toolResultConverter.apply(toolResult.getOutput());
                    String finalResultText =
                            !resultText.isEmpty() ? resultText : "[Empty tool result]";
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

        // Build the user message with multimodal content if needed
        ChatCompletionUserMessageParam.Builder builder = ChatCompletionUserMessageParam.builder();

        if (multimodalParts.isEmpty()) {
            // No multimodal content - use simple text content
            builder.content(conversationHistory.toString());
        } else {
            // Has multimodal content - build ContentPart list
            // First add the text conversation history
            List<ChatCompletionContentPart> allParts = new ArrayList<>();
            allParts.add(
                    ChatCompletionContentPart.ofText(
                            ChatCompletionContentPartText.builder()
                                    .text(conversationHistory.toString())
                                    .build()));

            // Then add all image/audio ContentParts
            allParts.addAll(multimodalParts);

            builder.contentOfArrayOfContentParts(allParts);
        }

        return builder.build();
    }
}

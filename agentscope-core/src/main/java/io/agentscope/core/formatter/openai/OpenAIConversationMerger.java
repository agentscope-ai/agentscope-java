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
package io.agentscope.core.formatter.openai;

import io.agentscope.core.formatter.openai.dto.OpenAIContentPart;
import io.agentscope.core.formatter.openai.dto.OpenAIMessage;
import io.agentscope.core.message.AudioBlock;
import io.agentscope.core.message.Base64Source;
import io.agentscope.core.message.ContentBlock;
import io.agentscope.core.message.ImageBlock;
import io.agentscope.core.message.Msg;
import io.agentscope.core.message.Source;
import io.agentscope.core.message.TextBlock;
import io.agentscope.core.message.ThinkingBlock;
import io.agentscope.core.message.ToolResultBlock;
import io.agentscope.core.message.URLSource;
import io.agentscope.core.message.VideoBlock;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Function;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Merges multi-agent conversation messages for OpenAI HTTP API. Consolidates multiple agent
 * messages into single user messages with history tags.
 *
 * <p>This class combines all agent messages into a single user message with conversation history
 * wrapped in special tags. Images and audio are preserved as separate ContentParts.
 */
public class OpenAIConversationMerger {

    private static final Logger log = LoggerFactory.getLogger(OpenAIConversationMerger.class);

    private static final String HISTORY_START_TAG = "<history>";
    private static final String HISTORY_END_TAG = "</history>";

    private final String conversationHistoryPrompt;

    /**
     * Create an OpenAIConversationMerger with custom conversation history prompt.
     *
     * @param conversationHistoryPrompt The prompt to prepend before conversation history
     */
    public OpenAIConversationMerger(String conversationHistoryPrompt) {
        this.conversationHistoryPrompt = conversationHistoryPrompt;
    }

    /**
     * Merge conversation messages into a single OpenAIMessage.
     *
     * <p>This method combines all agent messages into a single user message with conversation
     * history wrapped in {@code <history>} tags. Images and audio are preserved as separate
     * ContentParts in the multimodal format.
     *
     * @param msgs List of conversation messages to merge
     * @param roleFormatter Function to format role labels (e.g., USER → "User")
     * @param toolResultConverter Function to convert tool result blocks to strings
     * @return Single merged OpenAIMessage for OpenAI API
     */
    public OpenAIMessage mergeToUserMessage(
            List<Msg> msgs,
            Function<Msg, String> roleFormatter,
            Function<List<ContentBlock>, String> toolResultConverter) {

        // List to hold interleaved content parts (text and images/audio)
        List<OpenAIContentPart> allParts = new ArrayList<>();

        // Buffer for accumulating text segments
        StringBuilder textBuffer = new StringBuilder();
        textBuffer.append(conversationHistoryPrompt);
        textBuffer.append(HISTORY_START_TAG).append("\n");

        // Process all messages EXCEPT the last one as history
        int lastIndex = msgs.size() - 1;

        // Append history messages
        if (lastIndex > 0) {
            for (int i = 0; i < lastIndex; i++) {
                processMessage(
                        msgs.get(i),
                        roleFormatter,
                        toolResultConverter,
                        textBuffer,
                        allParts,
                        true);
            }
        }
        textBuffer.append(HISTORY_END_TAG).append("\n");

        // Process the last message (current turn)
        if (lastIndex >= 0) {
            // Include prefix only if there is history (multi-turn context)
            // or if it's explicitly needed. For single-turn user queries,
            // omitting the prefix makes it look like a standard chat request.
            boolean includePrefix = lastIndex > 0;
            processMessage(
                    msgs.get(lastIndex),
                    roleFormatter,
                    toolResultConverter,
                    textBuffer,
                    allParts,
                    includePrefix);
        }

        // Flush remaining text
        if (textBuffer.length() > 0) {
            allParts.add(OpenAIContentPart.text(textBuffer.toString()));
        }

        return OpenAIMessage.builder().role("user").content(allParts).build();
    }

    private void processMessage(
            Msg msg,
            Function<Msg, String> roleFormatter,
            Function<List<ContentBlock>, String> toolResultConverter,
            StringBuilder textBuffer,
            List<OpenAIContentPart> allParts,
            boolean includePrefix) {
        String agentName = msg.getName();

        // Process all blocks
        List<ContentBlock> blocks = msg.getContent();
        if (blocks == null) {
            blocks = new ArrayList<>();
        }
        for (ContentBlock block : blocks) {
            if (block instanceof TextBlock tb) {
                if (includePrefix) {
                    appendNamePrefix(textBuffer, agentName);
                }
                textBuffer.append(tb.getText()).append("\n");

            } else if (block instanceof ImageBlock imageBlock) {
                // Flush existing text to a content part
                if (textBuffer.length() > 0) {
                    allParts.add(OpenAIContentPart.text(textBuffer.toString()));
                    textBuffer.setLength(0); // Clear buffer
                }

                // Process image
                try {
                    Source source = imageBlock.getSource();
                    if (source == null) {
                        log.warn("ImageBlock has null source, skipping");
                        if (includePrefix) {
                            appendNamePrefix(textBuffer, agentName);
                        }
                        textBuffer.append("[Image - null source]\n");
                    } else {
                        String imageUrl = convertImageSourceToUrl(source);
                        allParts.add(OpenAIContentPart.imageUrl(imageUrl));
                    }
                } catch (Exception e) {
                    String errorMsg =
                            e.getMessage() != null ? e.getMessage() : e.getClass().getSimpleName();
                    log.warn("Failed to process ImageBlock: {}", errorMsg);
                    if (includePrefix) {
                        appendNamePrefix(textBuffer, agentName);
                    }
                    textBuffer
                            .append("[Image - processing failed: ")
                            .append(errorMsg)
                            .append("]\n");
                }

            } else if (block instanceof VideoBlock videoBlock) {
                // Flush existing text to a content part
                if (textBuffer.length() > 0) {
                    allParts.add(OpenAIContentPart.text(textBuffer.toString()));
                    textBuffer.setLength(0); // Clear buffer
                }

                // Process video
                try {
                    Source source = videoBlock.getSource();
                    if (source == null) {
                        log.warn("VideoBlock has null source, skipping");
                        if (includePrefix) {
                            appendNamePrefix(textBuffer, agentName);
                        }
                        textBuffer.append("[Video - null source]\n");
                    } else {
                        String videoUrl = convertVideoSourceToUrl(source);
                        allParts.add(OpenAIContentPart.videoUrl(videoUrl));
                    }
                } catch (Exception e) {
                    String errorMsg =
                            e.getMessage() != null ? e.getMessage() : e.getClass().getSimpleName();
                    log.warn("Failed to process VideoBlock: {}", errorMsg);
                    if (includePrefix) {
                        appendNamePrefix(textBuffer, agentName);
                    }
                    textBuffer
                            .append("[Video - processing failed: ")
                            .append(errorMsg)
                            .append("]\n");
                }

            } else if (block instanceof AudioBlock audioBlock) {
                // Flush existing text
                if (textBuffer.length() > 0) {
                    allParts.add(OpenAIContentPart.text(textBuffer.toString()));
                    textBuffer.setLength(0);
                }

                // Process audio
                try {
                    Source source = audioBlock.getSource();
                    if (source == null) {
                        log.warn("AudioBlock has null source, skipping");
                        if (includePrefix) {
                            appendNamePrefix(textBuffer, agentName);
                        }
                        textBuffer.append("[Audio - null source]\n");
                    } else if (source instanceof Base64Source b64) {
                        String audioData = b64.getData();
                        if (audioData == null || audioData.isEmpty()) {
                            log.warn("Base64Source has null or empty data, skipping");
                            if (includePrefix) {
                                appendNamePrefix(textBuffer, agentName);
                            }
                            textBuffer.append("[Audio - null or empty data]\n");
                        } else {
                            String format = detectAudioFormat(b64.getMediaType());
                            allParts.add(OpenAIContentPart.inputAudio(audioData, format));
                        }
                    } else if (source instanceof URLSource urlSource) {
                        String url = urlSource.getUrl();
                        if (url == null || url.isEmpty()) {
                            log.warn("URLSource has null or empty URL, skipping");
                            if (includePrefix) {
                                appendNamePrefix(textBuffer, agentName);
                            }
                            textBuffer.append("[Audio - null or empty URL]\n");
                        } else {
                            log.warn(
                                    "URL-based audio not directly supported, using text"
                                            + " reference");
                            if (includePrefix) {
                                appendNamePrefix(textBuffer, agentName);
                            }
                            textBuffer.append("[Audio URL: ").append(url).append("]\n");
                        }
                    } else {
                        log.warn("Unknown audio source type: {}", source.getClass());
                        if (includePrefix) {
                            appendNamePrefix(textBuffer, agentName);
                        }
                        textBuffer.append("[Audio - unsupported source type]\n");
                    }
                } catch (Exception e) {
                    String errorMsg =
                            e.getMessage() != null ? e.getMessage() : e.getClass().getSimpleName();
                    log.warn("Failed to process AudioBlock: {}", errorMsg);
                    appendNamePrefix(textBuffer, agentName);
                    textBuffer
                            .append("[Audio - processing failed: ")
                            .append(errorMsg)
                            .append("]\n");
                }

            } else if (block instanceof ThinkingBlock thinkingBlock) {
                // Include ThinkingBlock in conversation history for models that support reasoning
                if (includePrefix) {
                    appendNamePrefix(textBuffer, agentName);
                    String thinking = thinkingBlock.getThinking();
                    if (thinking != null && !thinking.isEmpty()) {
                        textBuffer.append("[Thinking]: ").append(thinking).append("\n");
                    }
                } else {
                    String thinking = thinkingBlock.getThinking();
                    if (thinking != null && !thinking.isEmpty()) {
                        textBuffer.append("[Thinking]: ").append(thinking).append("\n");
                    }
                }
            } else if (block instanceof ToolResultBlock toolResult) {
                // Use provided converter to handle multimodal content in tool results
                String resultText = toolResultConverter.apply(toolResult.getOutput());
                String finalResultText =
                        (resultText != null && !resultText.isEmpty())
                                ? resultText
                                : "[Empty tool result]";

                // For tool results, format as: name (tool_name): result
                textBuffer.append(agentName);
                textBuffer
                        .append(" (")
                        .append(toolResult.getName())
                        .append("): ")
                        .append(finalResultText)
                        .append("\n");
            }
        }
    }

    private void appendNamePrefix(StringBuilder buffer, String agentName) {
        if (agentName != null && !agentName.isEmpty()) {
            buffer.append(agentName).append(": ");
        }
    }

    /** Convert image Source to URL string for OpenAI API. */
    private String convertImageSourceToUrl(Source source) {
        return OpenAIConverterUtils.convertImageSourceToUrl(source);
    }

    /** Convert video Source to URL string for OpenAI API. */
    private String convertVideoSourceToUrl(Source source) {
        return OpenAIConverterUtils.convertVideoSourceToUrl(source);
    }

    /** Detect audio format from media type. */
    private String detectAudioFormat(String mediaType) {
        return OpenAIConverterUtils.detectAudioFormat(mediaType);
    }
}

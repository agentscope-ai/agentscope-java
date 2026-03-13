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
package io.agentscope.core.formatter.gemini;

import io.agentscope.core.formatter.gemini.dto.GeminiContent;
import io.agentscope.core.formatter.gemini.dto.GeminiPart;
import io.agentscope.core.message.AudioBlock;
import io.agentscope.core.message.ContentBlock;
import io.agentscope.core.message.ImageBlock;
import io.agentscope.core.message.Msg;
import io.agentscope.core.message.TextBlock;
import io.agentscope.core.message.ThinkingBlock;
import io.agentscope.core.message.ToolResultBlock;
import io.agentscope.core.message.VideoBlock;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Function;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Merges multi-agent conversation messages for Gemini API.
 *
 * <p>This class consolidates multiple agent messages into a single Content with conversation
 * history wrapped in special tags. It preserves agent names and roles in the merged text.
 *
 * <p><b>Format:</b>
 * <pre>
 * # Conversation History
 * &lt;history&gt;
 * ## AgentName (role)
 * Agent message content...
 * &lt;/history&gt;
 * </pre>
 */
public class GeminiConversationMerger {

    private static final Logger log = LoggerFactory.getLogger(GeminiConversationMerger.class);

    private static final String HISTORY_START_TAG = "<history>";
    private static final String HISTORY_END_TAG = "</history>";

    private final GeminiMediaConverter mediaConverter;
    private final String conversationHistoryPrompt;

    /**
     * Create a GeminiConversationMerger with custom conversation history prompt.
     *
     * @param conversationHistoryPrompt The prompt to prepend before conversation history
     */
    public GeminiConversationMerger(String conversationHistoryPrompt) {
        this.mediaConverter = new GeminiMediaConverter();
        this.conversationHistoryPrompt = conversationHistoryPrompt;
    }

    /**
     * Merge conversation messages into a single Content (for Gemini API).
     *
     * <p>This method combines all agent messages into a single "user" role Content with
     * conversation history wrapped in {@code <history>} tags. Agent names and roles are
     * embedded in the text.
     *
     * @param msgs List of conversation messages to merge
     * @param nameExtractor Function to extract agent name from message
     * @param toolResultConverter Function to convert tool result blocks to strings
     * @param historyPrompt The prompt to prepend (empty if not first group)
     * @return Single merged Content for Gemini API
     */
    public GeminiContent mergeToContent(
            List<Msg> msgs,
            Function<Msg, String> nameExtractor,
            Function<List<ContentBlock>, String> toolResultConverter,
            String historyPrompt) {

        List<GeminiPart> parts = new ArrayList<>();
        List<String> accumulatedText = new ArrayList<>();

        // Process each message and its content blocks
        for (Msg msg : msgs) {
            String name = nameExtractor.apply(msg);

            List<ContentBlock> blocks = msg.getContent();
            for (ContentBlock block : blocks) {
                if (block instanceof TextBlock tb) {
                    // Accumulate text in format "name: text"
                    accumulatedText.add(name + ": " + tb.getText());

                } else if (block instanceof ThinkingBlock) {
                    // Skip ThinkingBlock - not sent to API
                    log.debug("Skipping ThinkingBlock in multi-agent merge");

                } else if (block instanceof ToolResultBlock trb) {
                    // Convert tool result to text and accumulate
                    String toolResultText = toolResultConverter.apply(trb.getOutput());
                    accumulatedText.add("Tool: " + trb.getName() + "\n" + toolResultText);

                } else if (block instanceof ImageBlock ib) {
                    // Flush accumulated text as a Part
                    if (!accumulatedText.isEmpty()) {
                        GeminiPart part = new GeminiPart();
                        part.setText(String.join("\n", accumulatedText));
                        parts.add(part);
                        accumulatedText.clear();
                    }
                    // Add image as separate Part
                    parts.add(mediaConverter.convertToInlineDataPart(ib));

                } else if (block instanceof AudioBlock ab) {
                    // Flush accumulated text as a Part
                    if (!accumulatedText.isEmpty()) {
                        GeminiPart part = new GeminiPart();
                        part.setText(String.join("\n", accumulatedText));
                        parts.add(part);
                        accumulatedText.clear();
                    }
                    // Add audio as separate Part
                    parts.add(mediaConverter.convertToInlineDataPart(ab));

                } else if (block instanceof VideoBlock vb) {
                    // Flush accumulated text as a Part
                    if (!accumulatedText.isEmpty()) {
                        GeminiPart part = new GeminiPart();
                        part.setText(String.join("\n", accumulatedText));
                        parts.add(part);
                        accumulatedText.clear();
                    }
                    // Add video as separate Part
                    parts.add(mediaConverter.convertToInlineDataPart(vb));
                }
            }
        }

        // Flush any remaining accumulated text
        if (!accumulatedText.isEmpty()) {
            GeminiPart part = new GeminiPart();
            part.setText(String.join("\n", accumulatedText));
            parts.add(part);
        }

        // Add conversation history prompt and <history> tags
        if (!parts.isEmpty()) {
            GeminiPart firstPart = parts.get(0);
            if (firstPart.getText() != null) {
                String modifiedText = historyPrompt + HISTORY_START_TAG + firstPart.getText();
                firstPart.setText(modifiedText);
            } else {
                // First part is media, insert text part at beginning
                GeminiPart part = new GeminiPart();
                part.setText(historyPrompt + HISTORY_START_TAG);
                parts.add(0, part);
            }

            // Add closing tag to last text part
            GeminiPart lastPart = parts.get(parts.size() - 1);
            if (lastPart.getText() != null) {
                String modifiedText = lastPart.getText() + "\n" + HISTORY_END_TAG;
                lastPart.setText(modifiedText);
            } else {
                // Last part is media, append text part at end
                GeminiPart part = new GeminiPart();
                part.setText(HISTORY_END_TAG);
                parts.add(part);
            }
        }

        // Return Content with "user" role
        return new GeminiContent("user", parts);
    }
}

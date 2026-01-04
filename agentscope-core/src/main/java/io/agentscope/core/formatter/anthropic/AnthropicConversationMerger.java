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
package io.agentscope.core.formatter.anthropic;

import io.agentscope.core.message.ContentBlock;
import io.agentscope.core.message.ImageBlock;
import io.agentscope.core.message.Msg;
import io.agentscope.core.message.TextBlock;
import java.util.ArrayList;
import java.util.List;

/**
 * Merges multi-agent conversation messages into a formatted conversation history for Anthropic
 * API.
 */
public class AnthropicConversationMerger {

    /**
     * Merge conversation messages into formatted blocks with history tags.
     *
     * @param messages List of conversation messages
     * @param conversationHistoryPrompt Prompt text to prepend
     * @return List of content blocks (text and images)
     */
    public static List<Object> mergeConversation(
            List<Msg> messages, String conversationHistoryPrompt) {
        List<Object> conversationBlocks = new ArrayList<>();
        List<String> accumulatedText = new ArrayList<>();

        for (Msg msg : messages) {
            for (ContentBlock block : msg.getContent()) {
                if (block instanceof TextBlock tb) {
                    String msgName = msg.getName() != null ? msg.getName() : msg.getRole().name();
                    accumulatedText.add(msgName + ": " + tb.getText());
                } else if (block instanceof ImageBlock ib) {
                    // Add accumulated text before image
                    if (!accumulatedText.isEmpty()) {
                        conversationBlocks.add(String.join("\n", accumulatedText));
                        accumulatedText.clear();
                    }
                    conversationBlocks.add(ib);
                }
            }
        }

        // Add remaining text
        if (!accumulatedText.isEmpty()) {
            conversationBlocks.add(String.join("\n", accumulatedText));
        }

        // Wrap with history tags
        return wrapWithHistoryTags(conversationBlocks, conversationHistoryPrompt);
    }

    /**
     * Wrap conversation blocks with history tags.
     */
    private static List<Object> wrapWithHistoryTags(
            List<Object> blocks, String conversationHistoryPrompt) {
        if (blocks.isEmpty()) {
            return blocks;
        }

        List<Object> result = new ArrayList<>();

        // Add opening tag to first text block or insert new one
        if (blocks.get(0) instanceof String firstText) {
            result.add(conversationHistoryPrompt + "<history>\n" + firstText);
            for (int i = 1; i < blocks.size(); i++) {
                result.add(blocks.get(i));
            }
        } else {
            result.add(conversationHistoryPrompt + "<history>\n");
            result.addAll(blocks);
        }

        // Add closing tag to last text block or append new one
        int lastIndex = result.size() - 1;
        if (result.get(lastIndex) instanceof String lastText) {
            result.set(lastIndex, lastText + "\n</history>");
        } else {
            result.add("</history>");
        }

        return result;
    }
}

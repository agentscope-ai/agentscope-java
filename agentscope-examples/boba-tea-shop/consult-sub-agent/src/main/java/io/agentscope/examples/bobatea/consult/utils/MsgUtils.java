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
package io.agentscope.examples.bobatea.consult.utils;

import io.agentscope.core.message.AudioBlock;
import io.agentscope.core.message.ImageBlock;
import io.agentscope.core.message.Msg;
import io.agentscope.core.message.MsgRole;
import io.agentscope.core.message.Source;
import io.agentscope.core.message.TextBlock;
import io.agentscope.core.message.ThinkingBlock;
import io.agentscope.core.message.VideoBlock;
import java.util.stream.Collectors;

/**
 * Utility methods for working with Msg in examples.
 * These are convenience methods for common operations.
 */
public class MsgUtils {

    /**
     * Extract text content from a message.
     * Concatenates text from all text-containing blocks (TextBlock and ThinkingBlock).
     *
     * @param msg The message to extract text from
     * @return Concatenated text content or empty string if not available
     */
    public static String getTextContent(Msg msg) {
        return msg.getContent().stream()
                .map(
                        block -> {
                            if (block instanceof TextBlock) {
                                return "Text: " + ((TextBlock) block).getText();
                            } else if (block instanceof ThinkingBlock) {
                                return "Thinking: " + ((ThinkingBlock) block).getThinking();
                            }
                            return "";
                        })
                .filter(s -> !s.isEmpty())
                .collect(Collectors.joining("\n"));
    }

    /**
     * Check if a message has text content.
     *
     * @param msg The message to check
     * @return true if the message contains text content
     */
    public static boolean hasTextContent(Msg msg) {
        return msg.getContent().stream()
                .anyMatch(block -> block instanceof TextBlock || block instanceof ThinkingBlock);
    }

    /**
     * Check if a message has media content.
     *
     * @param msg The message to check
     * @return true if the message contains media content
     */
    public static boolean hasMediaContent(Msg msg) {
        return msg.getContent().stream()
                .anyMatch(
                        block ->
                                block instanceof ImageBlock
                                        || block instanceof AudioBlock
                                        || block instanceof VideoBlock);
    }

    /**
     * Create a message with text content (convenience method).
     *
     * @param name Sender name
     * @param role Message role
     * @param text Text content
     * @return Message with text content
     */
    public static Msg textMsg(String name, MsgRole role, String text) {
        return Msg.builder()
                .name(name)
                .role(role)
                .content(TextBlock.builder().text(text).build())
                .build();
    }

    /**
     * Create a message with image content (convenience method).
     *
     * @param name Sender name
     * @param role Message role
     * @param source Image source
     * @return Message with image content
     */
    public static Msg imageMsg(String name, MsgRole role, Source source) {
        return Msg.builder()
                .name(name)
                .role(role)
                .content(ImageBlock.builder().source(source).build())
                .build();
    }

    /**
     * Create a message with audio content (convenience method).
     *
     * @param name Sender name
     * @param role Message role
     * @param source Audio source
     * @return Message with audio content
     */
    public static Msg audioMsg(String name, MsgRole role, Source source) {
        return Msg.builder()
                .name(name)
                .role(role)
                .content(AudioBlock.builder().source(source).build())
                .build();
    }

    /**
     * Create a message with video content (convenience method).
     *
     * @param name Sender name
     * @param role Message role
     * @param source Video source
     * @return Message with video content
     */
    public static Msg videoMsg(String name, MsgRole role, Source source) {
        return Msg.builder()
                .name(name)
                .role(role)
                .content(VideoBlock.builder().source(source).build())
                .build();
    }

    /**
     * Create a message with thinking content (convenience method).
     *
     * @param name Sender name
     * @param role Message role
     * @param thinking Thinking content
     * @return Message with thinking content
     */
    public static Msg thinkingMsg(String name, MsgRole role, String thinking) {
        return Msg.builder()
                .name(name)
                .role(role)
                .content(ThinkingBlock.builder().thinking(thinking).build())
                .build();
    }

    private MsgUtils() {
        // Utility class
    }
}

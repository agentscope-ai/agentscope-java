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
package io.agentscope.examples.documentation2.common;

import io.agentscope.core.message.AssistantMessage;
import io.agentscope.core.message.AudioBlock;
import io.agentscope.core.message.ImageBlock;
import io.agentscope.core.message.Msg;
import io.agentscope.core.message.MsgRole;
import io.agentscope.core.message.Source;
import io.agentscope.core.message.SystemMessage;
import io.agentscope.core.message.TextBlock;
import io.agentscope.core.message.ThinkingBlock;
import io.agentscope.core.message.UserMessage;
import io.agentscope.core.message.VideoBlock;
import java.util.stream.Collectors;

/**
 * Utility methods for working with {@link Msg} in examples.
 */
public final class MsgUtils {

    private MsgUtils() {}

    /**
     * Extracts text content from a message by concatenating text from all
     * {@link TextBlock} and {@link ThinkingBlock} blocks.
     *
     * @param msg the message to extract text from
     * @return the concatenated text, or {@code "[No response]"} if empty
     */
    public static String getTextContent(Msg msg) {
        String thinking =
                msg.getContent().stream()
                        .filter(block -> block instanceof ThinkingBlock)
                        .map(block -> ((ThinkingBlock) block).getThinking())
                        .collect(Collectors.joining("\n"));

        String text =
                msg.getContent().stream()
                        .filter(block -> block instanceof TextBlock)
                        .map(block -> ((TextBlock) block).getText())
                        .collect(Collectors.joining("\n"));

        if (!thinking.isEmpty() && !text.isEmpty()) {
            return thinking + "\n\n" + text;
        } else if (!thinking.isEmpty()) {
            return thinking;
        } else if (!text.isEmpty()) {
            return text;
        }
        return "[No response]";
    }

    /**
     * Returns {@code true} when the message contains at least one text or thinking block.
     *
     * @param msg the message to check
     * @return {@code true} if there is text content
     */
    public static boolean hasTextContent(Msg msg) {
        return msg.getContent().stream()
                .anyMatch(block -> block instanceof TextBlock || block instanceof ThinkingBlock);
    }

    /**
     * Returns {@code true} when the message contains at least one image, audio, or video block.
     *
     * @param msg the message to check
     * @return {@code true} if there is media content
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
     * Creates a text-only message, dispatching to the appropriate role-pinned
     * subclass ({@link UserMessage}, {@link AssistantMessage}, {@link SystemMessage}).
     * The {@link MsgRole#TOOL} role is not supported here — use
     * {@link io.agentscope.core.message.ToolResultMessage} directly.
     *
     * @param name sender name
     * @param role message role
     * @param text text content
     * @return new {@link Msg}
     */
    public static Msg textMsg(String name, MsgRole role, String text) {
        return switch (role) {
            case USER -> new UserMessage(name, text);
            case ASSISTANT -> new AssistantMessage(name, text);
            case SYSTEM -> new SystemMessage(name, text);
            case TOOL ->
                    throw new IllegalArgumentException(
                            "TOOL role requires ToolResultMessage(toolCallId, toolName, text).");
        };
    }

    /**
     * Creates an image message. Only {@link MsgRole#USER} is supported because
     * the framework restricts media blocks to user turns.
     *
     * @param name   sender name
     * @param role   message role (must be USER)
     * @param source image source
     * @return new {@link UserMessage}
     */
    public static Msg imageMsg(String name, MsgRole role, Source source) {
        requireUserForMedia(role, "image");
        return new UserMessage(name, ImageBlock.builder().source(source).build());
    }

    /**
     * Creates an audio message. Only {@link MsgRole#USER} is supported.
     *
     * @param name   sender name
     * @param role   message role (must be USER)
     * @param source audio source
     * @return new {@link UserMessage}
     */
    public static Msg audioMsg(String name, MsgRole role, Source source) {
        requireUserForMedia(role, "audio");
        return new UserMessage(name, AudioBlock.builder().source(source).build());
    }

    /**
     * Creates a video message. Only {@link MsgRole#USER} is supported.
     *
     * @param name   sender name
     * @param role   message role (must be USER)
     * @param source video source
     * @return new {@link UserMessage}
     */
    public static Msg videoMsg(String name, MsgRole role, Source source) {
        requireUserForMedia(role, "video");
        return new UserMessage(name, VideoBlock.builder().source(source).build());
    }

    private static void requireUserForMedia(MsgRole role, String mediaKind) {
        if (role != MsgRole.USER) {
            throw new IllegalArgumentException(
                    mediaKind + " blocks are only valid on USER messages, got " + role);
        }
    }
}

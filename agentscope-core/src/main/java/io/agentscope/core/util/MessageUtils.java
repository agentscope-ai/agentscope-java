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
package io.agentscope.core.util;

import io.agentscope.core.message.ContentBlock;
import io.agentscope.core.message.Msg;
import io.agentscope.core.message.MsgRole;
import io.agentscope.core.message.ToolResultBlock;
import io.agentscope.core.message.ToolResultState;
import io.agentscope.core.message.ToolUseBlock;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * Utility methods for message processing and extraction.
 *
 * <p>This class provides common operations for working with message lists and extracting
 * information from conversation history.
 * @hidden
 */
public final class MessageUtils {

    private MessageUtils() {
        throw new UnsupportedOperationException("Utility class cannot be instantiated");
    }

    /**
     * Extract tool calls from the most recent assistant message in the message list.
     *
     * <p>This method scans the message list from the end to find the most recent assistant message
     * matching the specified agent name, then extracts any tool use blocks from that message.
     *
     * @param messages The list of messages to search
     * @param agentName The name of the agent to match (must match the message sender's name)
     * @return List of tool use blocks from the last matching assistant message, or empty list if
     *     none found
     */
    public static List<ToolUseBlock> extractRecentToolCalls(List<Msg> messages, String agentName) {
        if (messages == null || messages.isEmpty()) {
            return List.of();
        }

        for (int i = messages.size() - 1; i >= 0; i--) {
            Msg msg = messages.get(i);
            if (msg.getRole() == MsgRole.ASSISTANT && Objects.equals(msg.getName(), agentName)) {
                List<ToolUseBlock> toolCalls = msg.getContentBlocks(ToolUseBlock.class);
                if (!toolCalls.isEmpty()) {
                    return toolCalls;
                }
                break;
            }
        }

        return List.of();
    }

    /**
     * Extracts only pending tool calls from the most recent assistant message.
     *
     * @param messages the conversation messages
     * @param agentName the agent name to match
     * @return tool calls that do not yet have corresponding results
     */
    public static List<ToolUseBlock> extractPendingToolCalls(List<Msg> messages, String agentName) {
        List<ToolUseBlock> allToolCalls = extractRecentToolCalls(messages, agentName);
        if (allToolCalls.isEmpty()) {
            return List.of();
        }

        Set<String> pendingIds = pendingToolUseIds(messages);
        return allToolCalls.stream()
                .filter(toolUse -> pendingIds.contains(toolUse.getId()))
                .toList();
    }

    /**
     * Finds the most recent message with the requested role.
     *
     * @param messages the message list to search
     * @param role the role to match
     * @return the last matching message, or {@code null} if none exists
     */
    public static Msg lastMessageByRole(List<Msg> messages, MsgRole role) {
        if (messages == null || messages.isEmpty()) {
            return null;
        }
        for (int i = messages.size() - 1; i >= 0; i--) {
            Msg msg = messages.get(i);
            if (msg != null && msg.getRole() == role) {
                return msg;
            }
        }
        return null;
    }

    /**
     * Finds the most recent assistant message.
     *
     * @param messages the message list to search
     * @return the last assistant message, or {@code null} if none exists
     */
    public static Msg lastAssistantMessage(List<Msg> messages) {
        return lastMessageByRole(messages, MsgRole.ASSISTANT);
    }

    /**
     * Replaces the most recent message with the requested role.
     *
     * @param messages the mutable message list to update
     * @param role the role to match
     * @param replacement the replacement message
     * @return {@code true} if a message was replaced
     */
    public static boolean replaceLastMessageByRole(
            List<Msg> messages, MsgRole role, Msg replacement) {
        if (messages == null || messages.isEmpty() || replacement == null) {
            return false;
        }
        for (int i = messages.size() - 1; i >= 0; i--) {
            Msg msg = messages.get(i);
            if (msg != null && msg.getRole() == role) {
                messages.set(i, replacement);
                return true;
            }
        }
        return false;
    }

    /**
     * Returns the IDs of tool calls in the last assistant message that do not yet have a matching
     * tool result anywhere in the conversation.
     *
     * @param messages the conversation messages
     * @return the pending tool-call IDs
     */
    public static Set<String> pendingToolUseIds(List<Msg> messages) {
        Msg lastAssistant = lastAssistantMessage(messages);
        if (lastAssistant == null || !lastAssistant.hasContentBlocks(ToolUseBlock.class)) {
            return Set.of();
        }

        Set<String> existingResultIds =
                messages.stream()
                        .filter(Objects::nonNull)
                        .flatMap(m -> m.getContentBlocks(ToolResultBlock.class).stream())
                        .map(ToolResultBlock::getId)
                        .collect(Collectors.toSet());

        return lastAssistant.getContentBlocks(ToolUseBlock.class).stream()
                .map(ToolUseBlock::getId)
                .filter(id -> !existingResultIds.contains(id))
                .collect(Collectors.toSet());
    }

    /**
     * Returns whether the message contains any tool-call blocks.
     *
     * @param message the message to inspect
     * @return {@code true} if at least one tool call exists
     */
    public static boolean hasToolCalls(Msg message) {
        return message != null && !message.getContentBlocks(ToolUseBlock.class).isEmpty();
    }

    /**
     * Prepends a system message to the supplied message list without mutating the original list.
     *
     * @param messages the base messages
     * @param systemMessage the system message to prepend
     * @return the combined message list
     */
    public static List<Msg> prependSystemMessage(List<Msg> messages, Msg systemMessage) {
        if (systemMessage == null) {
            return messages != null ? messages : List.of();
        }
        List<Msg> result = new ArrayList<>();
        result.add(systemMessage);
        if (messages != null) {
            result.addAll(messages);
        }
        return result;
    }

    /**
     * Returns whether every supplied tool call has a DENIED result in the conversation.
     *
     * @param messages the conversation messages
     * @param toolCalls the tool calls to inspect
     * @return {@code true} if all tool calls are denied
     */
    public static boolean allToolCallsDenied(List<Msg> messages, List<ToolUseBlock> toolCalls) {
        if (toolCalls == null) {
            return false;
        }
        Set<String> toolIds =
                toolCalls.stream().map(ToolUseBlock::getId).collect(Collectors.toSet());
        Map<String, ToolResultState> resultStates = new HashMap<>();
        for (Msg message : messages) {
            if (message == null) {
                continue;
            }
            for (ToolResultBlock result : message.getContentBlocks(ToolResultBlock.class)) {
                if (toolIds.contains(result.getId())) {
                    resultStates.put(result.getId(), result.getState());
                }
            }
        }
        return toolIds.size() == resultStates.size()
                && resultStates.values().stream()
                        .allMatch(state -> state == ToolResultState.DENIED);
    }

    /**
     * Replaces tool-call blocks in the last assistant message when their IDs match the supplied
     * replacements.
     *
     * @param messages the mutable conversation messages
     * @param replacements tool-call IDs mapped to replacement blocks
     * @return {@code true} if any block was replaced
     */
    public static boolean replaceToolUseBlocks(
            List<Msg> messages, Map<String, ToolUseBlock> replacements) {
        if (messages == null
                || messages.isEmpty()
                || replacements == null
                || replacements.isEmpty()) {
            return false;
        }
        for (int i = messages.size() - 1; i >= 0; i--) {
            Msg msg = messages.get(i);
            if (msg == null || msg.getRole() != MsgRole.ASSISTANT) {
                continue;
            }
            boolean hasMatch =
                    msg.getContent().stream()
                            .anyMatch(
                                    block ->
                                            block instanceof ToolUseBlock toolUse
                                                    && replacements.containsKey(toolUse.getId()));
            if (!hasMatch) {
                continue;
            }
            List<ContentBlock> rebuilt = new ArrayList<>(msg.getContent().size());
            for (ContentBlock block : msg.getContent()) {
                if (block instanceof ToolUseBlock toolUse
                        && replacements.containsKey(toolUse.getId())) {
                    rebuilt.add(replacements.get(toolUse.getId()));
                } else {
                    rebuilt.add(block);
                }
            }
            messages.set(i, msg.withContent(rebuilt));
            return true;
        }
        return false;
    }
}

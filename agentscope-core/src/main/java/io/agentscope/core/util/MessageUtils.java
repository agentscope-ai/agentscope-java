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
import io.agentscope.core.message.TextBlock;
import io.agentscope.core.message.ToolResultBlock;
import io.agentscope.core.message.ToolResultState;
import io.agentscope.core.message.ToolUseBlock;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

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
     * Builds a provider-safe copy of a transcript by placing each tool result immediately after
     * the assistant message that owns its tool call.
     *
     * <p>If an earlier interruption left a tool call without any result in the transcript, this
     * method adds a terminal error result. It does not mutate the supplied messages.
     *
     * @param messages messages to normalize
     * @return a normalized transcript, or an empty list when the input is null or empty
     */
    public static List<Msg> normalizeToolCallResults(List<Msg> messages) {
        if (messages == null || messages.isEmpty()) {
            return List.of();
        }

        Map<String, ToolResultBlock> resultsById = new HashMap<>();
        for (Msg message : messages) {
            if (message == null) {
                continue;
            }
            for (ToolResultBlock result : message.getContentBlocks(ToolResultBlock.class)) {
                if (result.getId() != null) {
                    resultsById.putIfAbsent(result.getId(), result);
                }
            }
        }

        List<Msg> normalized = new ArrayList<>(messages.size());
        Set<String> relocatedResultIds = new HashSet<>();
        for (Msg message : messages) {
            if (message == null) {
                continue;
            }
            if (message.getRole() == MsgRole.ASSISTANT) {
                List<ToolUseBlock> toolUses = message.getContentBlocks(ToolUseBlock.class);
                if (!toolUses.isEmpty()) {
                    Set<String> toolUseIds = new HashSet<>();
                    for (ToolUseBlock toolUse : toolUses) {
                        if (toolUse.getId() != null) {
                            toolUseIds.add(toolUse.getId());
                        }
                    }
                    List<ContentBlock> assistantContent = new ArrayList<>(message.getContent());
                    assistantContent.removeIf(
                            block ->
                                    block instanceof ToolResultBlock result
                                            && toolUseIds.contains(result.getId()));
                    normalized.add(
                            assistantContent.equals(message.getContent())
                                    ? message
                                    : message.withContent(assistantContent));
                    List<ContentBlock> toolResults = new ArrayList<>(toolUses.size());
                    for (ToolUseBlock toolUse : toolUses) {
                        if (toolUse.getId() == null) {
                            continue;
                        }
                        ToolResultBlock result = resultsById.get(toolUse.getId());
                        if (result == null) {
                            result = interruptedToolResult(toolUse);
                        }
                        toolResults.add(result);
                        relocatedResultIds.add(toolUse.getId());
                    }
                    if (!toolResults.isEmpty()) {
                        normalized.add(
                                Msg.builder().role(MsgRole.TOOL).content(toolResults).build());
                    }
                    continue;
                }
            }

            if (message.getRole() == MsgRole.TOOL && !relocatedResultIds.isEmpty()) {
                List<ContentBlock> retained = new ArrayList<>();
                for (ContentBlock block : message.getContent()) {
                    if (!(block instanceof ToolResultBlock result)
                            || !relocatedResultIds.contains(result.getId())) {
                        retained.add(block);
                    }
                }
                if (!retained.isEmpty()) {
                    normalized.add(message.withContent(retained));
                }
                continue;
            }

            normalized.add(message);
        }
        return normalized;
    }

    private static ToolResultBlock interruptedToolResult(ToolUseBlock toolUse) {
        return new ToolResultBlock(
                        toolUse.getId(),
                        toolUse.getName(),
                        TextBlock.builder()
                                .text(
                                        "Previous tool call was interrupted before a result was"
                                                + " recorded. Treat this tool call as failed or"
                                                + " canceled.")
                                .build())
                .withState(ToolResultState.ERROR);
    }
}

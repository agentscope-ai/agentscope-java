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

import io.agentscope.core.message.Msg;
import io.agentscope.core.message.MsgRole;
import io.agentscope.core.message.ToolUseBlock;
import java.util.List;

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
            if (msg.getRole() == MsgRole.ASSISTANT && msg.getName().equals(agentName)) {
                List<ToolUseBlock> toolCalls = msg.getContentBlocks(ToolUseBlock.class);
                if (!toolCalls.isEmpty()) {
                    return toolCalls;
                }
                break;
            }
        }

        return List.of();
    }
}

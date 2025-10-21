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
package io.agentscope.core.tool;

import io.agentscope.core.message.ContentBlock;
import io.agentscope.core.message.Msg;
import io.agentscope.core.message.MsgRole;
import io.agentscope.core.message.TextBlock;
import io.agentscope.core.message.ToolResultBlock;
import io.agentscope.core.message.ToolUseBlock;
import java.util.List;

/**
 * Utility class for building tool result messages from ToolResponse objects.
 *
 * This class handles the conversion of ToolResponse to Msg with ToolResultBlock,
 * properly handling different types of content blocks.
 */
public class ToolResultMessageBuilder {

    /**
     * Build a tool result message from a ToolResponse and the original tool call.
     *
     * @param response The tool execution response
     * @param originalCall The original tool use block that triggered the execution
     * @param agentName The name of the agent creating this message
     * @return Msg containing the tool result
     */
    public static Msg buildToolResultMsg(
            ToolResponse response, ToolUseBlock originalCall, String agentName) {
        ContentBlock output = aggregateContent(response.getContent());

        return Msg.builder()
                .name(agentName)
                .role(MsgRole.TOOL)
                .content(
                        ToolResultBlock.builder()
                                .id(originalCall.getId())
                                .name(originalCall.getName())
                                .output(output)
                                .build())
                .build();
    }

    /**
     * Aggregate multiple content blocks into a single content block.
     * Strategy:
     * 1. If only one block, return it directly
     * 2. If multiple text blocks, merge them with newlines
     * 3. If mixed content types, create a composite text representation
     *
     * @param contentBlocks List of content blocks to aggregate
     * @return A single ContentBlock representing all content
     */
    private static ContentBlock aggregateContent(List<ContentBlock> contentBlocks) {
        if (contentBlocks == null || contentBlocks.isEmpty()) {
            return TextBlock.builder().text("").build();
        }

        // If only one block, return it directly
        if (contentBlocks.size() == 1) {
            return contentBlocks.get(0);
        }

        // Multiple blocks - need to aggregate
        StringBuilder combined = new StringBuilder();
        for (ContentBlock block : contentBlocks) {
            if (block instanceof TextBlock tb) {
                if (!combined.isEmpty()) {
                    combined.append("\n");
                }
                combined.append(tb.getText());
            } else {
                // For non-text blocks, add a placeholder or toString representation
                if (!combined.isEmpty()) {
                    combined.append("\n");
                }
                combined.append("[").append(block.getClass().getSimpleName()).append("]");
            }
        }

        return TextBlock.builder().text(combined.toString()).build();
    }
}

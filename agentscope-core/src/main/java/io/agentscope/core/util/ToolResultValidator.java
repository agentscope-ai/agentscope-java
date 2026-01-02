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
import io.agentscope.core.message.ToolResultBlock;
import io.agentscope.core.message.ToolUseBlock;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * Utility class for validating ToolResult messages against pending ToolUse blocks.
 *
 * <p>This validator is used in two scenarios:
 * <ul>
 *   <li>HITL (Human-in-the-Loop) resumption via {@code agent.call()}</li>
 *   <li>Hook-based flow control via {@code PostReasoningEvent.gotoReasoning()}</li>
 * </ul>
 */
public final class ToolResultValidator {

    private ToolResultValidator() {
        // Utility class
    }

    /**
     * Validates input messages against pending ToolUse blocks in the assistant message.
     *
     * <p>Validation rules:
     * <ul>
     *   <li>If assistantMsg is null or has no ToolUse blocks: validation passes</li>
     *   <li>If assistantMsg has ToolUse blocks but inputMsgs is null/empty: throws exception</li>
     *   <li>If inputMsgs is provided: checks that all pending ToolUse IDs have matching
     *       ToolResult IDs</li>
     * </ul>
     *
     * @param assistantMsg The assistant message that may contain pending ToolUse blocks
     * @param inputMsgs The input messages that should contain ToolResult blocks
     * @throws IllegalStateException if validation fails
     */
    public static void validate(Msg assistantMsg, List<Msg> inputMsgs) {
        if (assistantMsg == null) {
            return;
        }

        List<ToolUseBlock> pendingToolUses = assistantMsg.getContentBlocks(ToolUseBlock.class);
        if (pendingToolUses.isEmpty()) {
            return; // No pending ToolUse, validation passes
        }

        // Has pending ToolUse
        if (inputMsgs == null || inputMsgs.isEmpty()) {
            // No input messages provided, throw error
            List<String> toolNames = pendingToolUses.stream().map(ToolUseBlock::getName).toList();
            throw new IllegalStateException(
                    "Cannot proceed without ToolResult when there are pending ToolUse. "
                            + "Pending tools: "
                            + toolNames);
        }

        // Has input messages, validate ToolResult matches
        Set<String> providedIds =
                inputMsgs.stream()
                        .flatMap(m -> m.getContentBlocks(ToolResultBlock.class).stream())
                        .map(ToolResultBlock::getId)
                        .collect(Collectors.toSet());

        Set<String> requiredIds =
                pendingToolUses.stream().map(ToolUseBlock::getId).collect(Collectors.toSet());

        if (!providedIds.containsAll(requiredIds)) {
            Set<String> missing = new HashSet<>(requiredIds);
            missing.removeAll(providedIds);
            throw new IllegalStateException(
                    "Missing ToolResult for pending ToolUse. Missing IDs: " + missing);
        }
    }
}

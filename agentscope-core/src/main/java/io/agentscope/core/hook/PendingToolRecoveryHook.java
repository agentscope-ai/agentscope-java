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
package io.agentscope.core.hook;

import io.agentscope.core.ReActAgent;
import io.agentscope.core.agent.Agent;
import io.agentscope.core.memory.Memory;
import io.agentscope.core.message.Msg;
import io.agentscope.core.message.MsgRole;
import io.agentscope.core.message.TextBlock;
import io.agentscope.core.message.ToolResultBlock;
import io.agentscope.core.message.ToolUseBlock;
import io.agentscope.core.tool.ToolResultMessageBuilder;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import reactor.core.publisher.Mono;

/**
 * Hook that automatically recovers from orphaned pending tool calls by generating error
 * {@link ToolResultBlock}s before the agent processes new input.
 *
 * <p>When tool execution fails, times out, or is interrupted, tool call states may remain in
 * memory without corresponding results. This hook detects such orphaned pending tool calls at
 * {@link PreCallEvent} time and patches them with synthetic error results, allowing the agent
 * to continue processing instead of crashing with {@link IllegalStateException}.
 *
 * <p>This hook is registered by default in {@link ReActAgent.Builder}. Users can disable it
 * via {@link ReActAgent.Builder#enablePendingToolRecovery(boolean)} if they prefer to handle
 * pending tool calls manually (e.g., through HITL mechanisms).
 *
 * <p><b>Behavior:</b>
 * <ul>
 *   <li>Only activates when the agent is a {@link ReActAgent}</li>
 *   <li>Only patches when pending tool calls exist AND user input does not contain
 *       {@link ToolResultBlock}s (i.e., user is not providing results themselves)</li>
 *   <li>Generated error results are added to memory as TOOL-role messages</li>
 * </ul>
 *
 * @see ReActAgent
 * @see PreCallEvent
 */
public class PendingToolRecoveryHook implements Hook {

    private static final Logger log = LoggerFactory.getLogger(PendingToolRecoveryHook.class);

    @Override
    public <T extends HookEvent> Mono<T> onEvent(T event) {
        if (event instanceof PreCallEvent preCallEvent) {
            @SuppressWarnings("unchecked")
            Mono<T> result = (Mono<T>) handlePreCall(preCallEvent);
            return result;
        }
        return Mono.just(event);
    }

    @Override
    public int priority() {
        // High priority — must run before other hooks that depend on memory state
        return 10;
    }

    /**
     * Detect and patch orphaned pending tool calls before agent processing begins.
     *
     * @param event the PreCallEvent containing agent and input messages
     * @return Mono containing the unmodified event after patching is complete
     */
    private Mono<PreCallEvent> handlePreCall(PreCallEvent event) {
        Agent agent = event.getAgent();
        if (!(agent instanceof ReActAgent reactAgent)) {
            return Mono.just(event);
        }

        Memory memory = reactAgent.getMemory();
        if (memory == null) {
            return Mono.just(event);
        }

        // Find pending tool call IDs (tool calls without corresponding results)
        Set<String> pendingIds = findPendingToolUseIds(memory);
        if (pendingIds.isEmpty()) {
            return Mono.just(event);
        }

        // Check if user already provided tool results in the input
        List<Msg> inputMessages = event.getInputMessages();

        // If input is empty/null, the user is resuming (wants to continue acting).
        // Do NOT patch — let ReActAgent's doCall handle the resume flow.
        if (inputMessages == null || inputMessages.isEmpty()) {
            return Mono.just(event);
        }

        boolean userProvidedResults =
                inputMessages.stream().anyMatch(m -> m.hasContentBlocks(ToolResultBlock.class));
        if (userProvidedResults) {
            return Mono.just(event);
        }

        // Auto-patch: generate error tool results for orphaned pending tool calls
        log.warn(
                "Pending tool calls detected without results, auto-generating error results."
                        + " Pending IDs: {}",
                pendingIds);

        patchPendingToolCalls(reactAgent, memory, pendingIds);
        return Mono.just(event);
    }

    /**
     * Find tool call IDs from the last assistant message that have no corresponding
     * {@link ToolResultBlock} in memory.
     *
     * @param memory the agent's memory
     * @return set of pending tool use IDs, empty if none
     */
    private Set<String> findPendingToolUseIds(Memory memory) {
        List<Msg> messages = memory.getMessages();

        // Find last assistant message
        Msg lastAssistant = null;
        for (int i = messages.size() - 1; i >= 0; i--) {
            if (messages.get(i).getRole() == MsgRole.ASSISTANT) {
                lastAssistant = messages.get(i);
                break;
            }
        }

        if (lastAssistant == null || !lastAssistant.hasContentBlocks(ToolUseBlock.class)) {
            return Set.of();
        }

        // Collect all existing tool result IDs in memory
        Set<String> existingResultIds =
                messages.stream()
                        .flatMap(m -> m.getContentBlocks(ToolResultBlock.class).stream())
                        .map(ToolResultBlock::getId)
                        .collect(Collectors.toSet());

        // Return tool call IDs that have no result yet
        return lastAssistant.getContentBlocks(ToolUseBlock.class).stream()
                .map(ToolUseBlock::getId)
                .filter(id -> !existingResultIds.contains(id))
                .collect(Collectors.toSet());
    }

    /**
     * Generate error {@link ToolResultBlock}s for each pending tool call and add them
     * to memory as TOOL-role messages.
     *
     * @param agent the ReActAgent instance
     * @param memory the agent's memory
     * @param pendingIds the set of pending tool use IDs to patch
     */
    private void patchPendingToolCalls(ReActAgent agent, Memory memory, Set<String> pendingIds) {
        List<Msg> messages = memory.getMessages();

        // Find last assistant message to get ToolUseBlock details
        Msg lastAssistant = null;
        for (int i = messages.size() - 1; i >= 0; i--) {
            if (messages.get(i).getRole() == MsgRole.ASSISTANT) {
                lastAssistant = messages.get(i);
                break;
            }
        }
        if (lastAssistant == null) {
            return;
        }

        List<ToolUseBlock> pendingToolCalls =
                lastAssistant.getContentBlocks(ToolUseBlock.class).stream()
                        .filter(toolUse -> pendingIds.contains(toolUse.getId()))
                        .toList();

        for (ToolUseBlock toolCall : pendingToolCalls) {
            ToolResultBlock errorResult = buildErrorToolResult(toolCall);
            Msg toolResultMsg =
                    ToolResultMessageBuilder.buildToolResultMsg(
                            errorResult, toolCall, agent.getName());
            memory.addMessage(toolResultMsg);

            log.info(
                    "Auto-generated error result for pending tool call: {} ({})",
                    toolCall.getName(),
                    toolCall.getId());
        }
    }

    /**
     * Build an error {@link ToolResultBlock} for a failed or orphaned tool call.
     *
     * @param toolCall the tool call that has no result
     * @return a ToolResultBlock containing a formatted error message
     */
    private static ToolResultBlock buildErrorToolResult(ToolUseBlock toolCall) {
        return ToolResultBlock.builder()
                .id(toolCall.getId())
                .output(
                        List.of(
                                TextBlock.builder()
                                        .text(
                                                "[ERROR] Previous tool execution failed or was"
                                                        + " interrupted. Tool: "
                                                        + toolCall.getName())
                                        .build()))
                .build();
    }
}

/*
 * Copyright 2024-2026 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package io.agentscope.examples.hitlchat.hook;

import io.agentscope.core.hook.Hook;
import io.agentscope.core.hook.HookEvent;
import io.agentscope.core.hook.PostReasoningEvent;
import io.agentscope.core.message.Msg;
import io.agentscope.core.message.ToolUseBlock;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import reactor.core.publisher.Mono;

/**
 * Hook that stops agent execution when dangerous tools are about to be called.
 *
 * <p>This hook monitors the agent's reasoning output and stops execution if any pending tool call
 * matches the configured dangerous tools list, allowing for human confirmation.
 */
public class ToolConfirmationHook implements Hook {

    private final Set<String> dangerousTools;

    public ToolConfirmationHook() {
        this.dangerousTools = new HashSet<>();
    }

    public ToolConfirmationHook(Set<String> dangerousTools) {
        this.dangerousTools = new HashSet<>(dangerousTools);
    }

    /**
     * Add a tool to the dangerous tools list.
     *
     * @param toolName Name of the tool to mark as dangerous
     */
    public void addDangerousTool(String toolName) {
        dangerousTools.add(toolName);
    }

    /**
     * Remove a tool from the dangerous tools list.
     *
     * @param toolName Name of the tool to remove
     */
    public void removeDangerousTool(String toolName) {
        dangerousTools.remove(toolName);
    }

    /**
     * Set the complete list of dangerous tools.
     *
     * @param toolNames Set of tool names to mark as dangerous
     */
    public void setDangerousTools(Set<String> toolNames) {
        dangerousTools.clear();
        if (toolNames != null) {
            dangerousTools.addAll(toolNames);
        }
    }

    /**
     * Get the current list of dangerous tools.
     *
     * @return Unmodifiable set of dangerous tool names
     */
    public Set<String> getDangerousTools() {
        return Set.copyOf(dangerousTools);
    }

    /**
     * Check if a tool is marked as dangerous.
     *
     * @param toolName Name of the tool to check
     * @return true if the tool is dangerous
     */
    public boolean isDangerous(String toolName) {
        return dangerousTools.contains(toolName);
    }

    @Override
    public <T extends HookEvent> Mono<T> onEvent(T event) {
        if (event instanceof PostReasoningEvent postReasoning) {
            Msg reasoningMsg = postReasoning.getReasoningMessage();
            if (reasoningMsg == null) {
                return Mono.just(event);
            }

            // Check if any dangerous tools are being called
            List<ToolUseBlock> toolCalls = reasoningMsg.getContentBlocks(ToolUseBlock.class);
            boolean hasDangerousTool =
                    toolCalls.stream().anyMatch(tool -> dangerousTools.contains(tool.getName()));

            if (hasDangerousTool) {
                // Stop agent to wait for user confirmation
                postReasoning.stopAgent();
            }
        }
        return Mono.just(event);
    }
}

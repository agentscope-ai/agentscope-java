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
package io.agentscope.core.tool.subagent;

import io.agentscope.core.agent.Agent;
import io.agentscope.core.memory.Memory;

/**
 * Context information provided to SubAgentProvider when creating agent instances.
 *
 * <p>This context allows the provider to make informed decisions about agent
 * configuration based on the parent agent's state, particularly for memory
 * sharing modes.
 *
 * <p>Usage example:
 * <pre>{@code
 * SubAgentProvider<ReActAgent> provider = context -> {
 *     ReActAgent.Builder builder = ReActAgent.builder()
 *         .name("MyAgent")
 *         .model(model);
 *
 *     if (context.getMemoryToUse() != null) {
 *         builder.memory(context.getMemoryToUse());
 *     }
 *
 *     return builder.build();
 * };
 * }</pre>
 */
public class SubAgentContext {

    private final Agent parentAgent;
    private final ContextSharingMode contextSharingMode;
    private final Memory memoryToUse;

    /**
     * Creates a new SubAgentContext.
     *
     * @param parentAgent The parent agent that is invoking the sub-agent
     * @param contextSharingMode The context sharing mode to use
     * @param memoryToUse The memory to use for the sub-agent (may be null for default)
     */
    public SubAgentContext(
            Agent parentAgent, ContextSharingMode contextSharingMode, Memory memoryToUse) {
        this.parentAgent = parentAgent;
        this.contextSharingMode = contextSharingMode;
        this.memoryToUse = memoryToUse;
    }

    /**
     * Creates an empty context with no parent agent information.
     *
     * <p>This is used for backward compatibility when no context is available.
     *
     * @return An empty SubAgentContext
     */
    public static SubAgentContext empty() {
        return new SubAgentContext(null, ContextSharingMode.NEW, null);
    }

    /**
     * Gets the parent agent that is invoking the sub-agent.
     *
     * @return The parent agent, or null if not available
     */
    public Agent getParentAgent() {
        return parentAgent;
    }

    /**
     * Gets the context sharing mode.
     *
     * @return The context sharing mode
     */
    public ContextSharingMode getContextSharingMode() {
        return contextSharingMode;
    }

    /**
     * Gets the memory to use for the sub-agent.
     *
     * <p>This is pre-computed based on the context sharing mode:
     * <ul>
     *   <li>SHARED: The parent agent's memory instance</li>
     *   <li>FORK: A forked copy of the parent agent's memory</li>
     *   <li>NEW: null (sub-agent should use its own memory)</li>
     * </ul>
     *
     * @return The memory to use, or null if the sub-agent should use its own memory
     */
    public Memory getMemoryToUse() {
        return memoryToUse;
    }

    /**
     * Checks if this context has valid parent agent information.
     *
     * @return true if parent agent information is available
     */
    public boolean hasParentAgent() {
        return parentAgent != null;
    }
}

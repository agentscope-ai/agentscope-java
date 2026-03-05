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

/**
 * Factory interface for creating agent instances.
 *
 * <p>Since ReActAgent is not thread-safe, this provider pattern ensures that each tool call gets a
 * fresh agent instance. This is similar to Spring's ObjectProvider pattern.
 *
 * <p>The provider supports context-aware agent creation for memory sharing features:
 * <ul>
 *   <li>The {@link #provideWithContext(SubAgentContext)} method receives context about the parent
 *       agent and the memory to use</li>
 *   <li>For SHARED mode, the context contains the parent's memory instance</li>
 *   <li>For FORK mode, the context contains a forked copy of parent's memory</li>
 *   <li>For NEW mode, the context contains null (use independent memory)</li>
 * </ul>
 *
 * <p>Example usage (context-aware for memory sharing):
 *
 * <pre>{@code
 * SubAgentProvider<ReActAgent> provider = context -> {
 *     ReActAgent.Builder builder = ReActAgent.builder()
 *         .name("ResearchAgent")
 *         .model(model)
 *         .sysPrompt("You are a research expert...");
 *
 *     // Use shared/forked memory if provided
 *     if (context.getMemoryToUse() != null) {
 *         builder.memory(context.getMemoryToUse());
 *     }
 *
 *     return builder.build();
 * };
 *
 * toolkit.registration()
 *     .subAgent(provider)
 *     .apply();
 * }</pre>
 *
 * @param <T> The type of agent this provider creates
 */
@FunctionalInterface
public interface SubAgentProvider<T extends Agent> {

    /**
     * Provides a new agent instance with context information.
     *
     * <p>This method is called for each tool invocation to ensure thread safety.
     * The context provides information about the parent agent and the memory to use.
     *
     * <p>Implementations should:
     * <ul>
     *   <li>Check {@link SubAgentContext#getMemoryToUse()} - if not null, use it as the agent's
     *       memory to enable SHARED or FORK context sharing modes</li>
     *   <li>If null, create the agent with its own independent memory</li>
     * </ul>
     *
     * @param context The context containing parent agent information and memory to use
     * @return A new agent instance
     */
    T provideWithContext(SubAgentContext context);

    /**
     * Provides a new agent instance without context information.
     *
     * <p>This is a convenience method for backward compatibility. It calls
     * {@link #provideWithContext(SubAgentContext)} with an empty context.
     *
     * @return A new agent instance
     */
    default T provide() {
        return provideWithContext(SubAgentContext.empty());
    }
}

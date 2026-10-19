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
 * <p>Each instance returned to {@link SubAgentTool} is owned by the tool. The tool closes
 * instances that implement {@link AutoCloseable}: the temporary initialization instance after
 * reading its metadata, and each invocation instance after completion, error, or cancellation.
 * Implementations must return a fresh instance on every call, not a shared or cached agent that
 * other callers may still use.
 *
 * <p>Example usage:
 *
 * <pre>{@code
 * SubAgentProvider<ReActAgent> provider = () -> ReActAgent.builder()
 *     .name("ResearchAgent")
 *     .model(model)
 *     .sysPrompt("You are a research expert...")
 *     .build();
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
     * Provides a new agent instance.
     *
     * <p>This method is called during tool initialization to obtain metadata and for each tool
     * invocation. Implementations must create a new instance each time and transfer its lifecycle
     * ownership to the tool, which closes it when it implements {@link AutoCloseable}.
     *
     * @return A new agent instance
     */
    T provide();
}

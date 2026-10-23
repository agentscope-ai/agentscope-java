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
 * Supplies an agent instance for delegation, either shared or newly created.
 *
 * <p>ReActAgent supports a long-lived singleton with per-session state: calls in one session
 * are serialized and different sessions can run concurrently. Providers may return that same
 * instance, new instances, or caller-managed cached instances.
 *
 * <p>{@link SubAgentConfig.LifecyclePolicy#AUTO} compares the first two provided instances by
 * identity. A stable singleton is left open; a factory's distinct instances are tool-owned.
 * Use {@link SubAgentConfig.LifecyclePolicy#SHARED} for pools or other externally managed
 * providers, or {@link SubAgentConfig.LifecyclePolicy#PER_CALL} to explicitly transfer ownership.
 * Automatic detection cannot infer ownership of arbitrary provider implementations.
 *
 * <p>Invocation cleanup is dispatched asynchronously so a blocking {@code close()} does not
 * block the thread delivering completion, error, or cancellation. Closing may therefore finish
 * after the tool result or cancellation has been delivered.
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
     * Provides an agent instance, which may be shared or newly created.
     *
     * <p>Registration only calls this method when name or description must be derived. AUTO
     * retains an unresolved metadata sample until first use. When metadata is explicit, the first
     * invocation makes two provider calls to distinguish a singleton from a factory; later
     * invocations make one. PER_CALL releases even unused registration samples immediately.
     *
     * @return an agent instance
     */
    T provide();
}

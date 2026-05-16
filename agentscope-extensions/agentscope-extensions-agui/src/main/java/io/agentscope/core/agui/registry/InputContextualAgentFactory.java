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
package io.agentscope.core.agui.registry;

import io.agentscope.core.agent.Agent;
import io.agentscope.core.agui.model.RunAgentInput;

/**
 * Factory interface for creating agents with access to the current request context.
 *
 * <p>This interface allows agent factories to access the {@link RunAgentInput}
 * when creating agent instances, enabling dynamic agent configuration based on
 * request parameters.
 *
 * <p>Example usage:
 * <pre>{@code
 *     registry.registerFactoryWithContext("my-agent", (input) -> {
 *         String threadId = input.getThreadId();
 *         Map<String, Object> props = input.getForwardedProps();
 *         return createCustomAgent(threadId, props);
 *     });
 * }</pre>
 *
 * @see AguiAgentRegistry #registerFactoryWithContext(String, ContextualAgentFactory)
 */
@FunctionalInterface
public interface InputContextualAgentFactory {

    /**
     * Create a new agent instance with access to the request context.
     *
     * @param input The current run agent input containing thread ID, messages, etc.
     * @return A new agent instance
     */
    Agent create(RunAgentInput input);
}

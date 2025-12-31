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
package io.agentscope.core.e2e.providers;

import io.agentscope.core.ReActAgent;
import io.agentscope.core.tool.Toolkit;

/**
 * Provider interface for creating ReActAgent instances with different model configurations.
 *
 * <p>Implementations provide agents for different APIs (OpenAI Native, DashScope Native,
 * DashScope Compatible, Bailian) with proper model configurations.
 */
public interface ModelProvider {

    /**
     * Creates a ReActAgent with the specified configuration.
     *
     * @param name The name of the agent
     * @param toolkit The toolkit to use
     * @return Configured ReActAgent
     */
    ReActAgent createAgent(String name, Toolkit toolkit);

    /**
     * Gets the display name of this provider.
     *
     * @return Provider name
     */
    String getProviderName();

    /**
     * Checks if this provider supports thinking mode.
     *
     * @return true if thinking is supported
     */
    boolean supportsThinking();

    /**
     * Checks if this provider is enabled (has required API keys).
     *
     * @return true if provider can be used
     */
    boolean isEnabled();

    /**
     * Gets the model name used by this provider.
     *
     * @return Model name
     */
    String getModelName();

    /**
     * Checks if this provider supports tool calling (function calling).
     *
     * <p>Vision models like GLM-4V do not support tool calling.
     *
     * @return true if tool calling is supported
     */
    default boolean supportsToolCalling() {
        return true;
    }
}

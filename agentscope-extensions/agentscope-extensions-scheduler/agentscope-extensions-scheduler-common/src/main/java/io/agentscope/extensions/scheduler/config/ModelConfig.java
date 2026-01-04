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
package io.agentscope.extensions.scheduler.config;

import io.agentscope.core.model.Model;

/**
 * Configuration interface for model settings in scheduled tasks.
 *
 * <p>This interface defines a contract for model configuration objects that encapsulate
 * model-related settings. It provides a unified way to handle model configurations
 * across different model providers.
 *
 * <p><b>Key Benefits:</b>
 * <ul>
 *   <li><b>Serializable Configuration</b> - Easier to persist and transmit model settings</li>
 *   <li><b>Decoupling</b> - Configuration is separate from model implementation</li>
 *   <li><b>Type Safety</b> - Strongly typed configuration objects</li>
 *   <li><b>Flexibility</b> - Support for different model providers</li>
 * </ul>
 *
 * <p><b>Usage Example:</b>
 * <pre>{@code
 * // Create model configuration
 * ModelConfig modelConfig = DashScopeModelConfig.builder()
 *     .apiKey(apiKey)
 *     .modelName("qwen-max")
 *     .stream(true)
 *     .build();
 *
 * // Use in AgentConfig
 * AgentConfig agentConfig = AgentConfig.builder()
 *     .name("MyAgent")
 *     .modelConfig(modelConfig)
 *     .build();
 * }</pre>
 *
 * @see AgentConfig
 * @see DashScopeModelConfig
 */
public interface ModelConfig {

    /**
     * Get the model name or identifier.
     *
     * <p>This is used for logging, identification, and routing purposes.
     *
     * @return The model name/identifier
     */
    String getModelName();

    /**
     * Create a Model instance from this configuration.
     *
     * <p>This method converts the configuration into an actual Model object
     * that can be used for agent execution. Each call may return a new instance
     * or a cached instance depending on the implementation.
     *
     * <p><b>Usage Example:</b>
     * <pre>{@code
     * ModelConfig modelConfig = DashScopeModelConfig.builder()
     *     .apiKey(apiKey)
     *     .modelName("qwen-max")
     *     .build();
     *
     * // Create the actual model instance
     * Model model = modelConfig.createModel();
     * }</pre>
     *
     * @return A Model instance configured according to this configuration
     * @throws IllegalStateException if the model cannot be created
     */
    Model createModel();
}

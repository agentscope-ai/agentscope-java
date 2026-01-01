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

import io.agentscope.extensions.scheduler.AgentScheduler;
import io.agentscope.extensions.scheduler.BaseScheduleAgentTask;
import java.util.Objects;

/**
 * Serializable metadata configuration for Agent instances in scheduled tasks.
 *
 * <p>This class contains only serializable configuration metadata, making it suitable for
 * persistence, transmission, and distributed scheduling scenarios. It excludes runtime
 * objects like Toolkit and Hooks.
 *
 * <p><b>Key Benefits:</b>
 * <ul>
 *   <li><b>Serializable</b> - All fields are serializable configuration objects</li>
 *   <li><b>Persistent</b> - Can be stored in databases or configuration files</li>
 *   <li><b>Transmittable</b> - Can be sent across network in distributed systems</li>
 *   <li><b>Type-Safe</b> - Compile-time validation of required components</li>
 * </ul>
 *
 * <p><b>Usage Example (Basic):</b>
 * <pre>{@code
 * // Create model configuration
 * ModelConfig modelConfig = DashScopeModelConfig.builder()
 *     .apiKey(apiKey)
 *     .modelName("qwen-max")
 *     .stream(true)
 *     .build();
 *
 * // Create serializable agent configuration
 * AgentConfig config = AgentConfig.builder()
 *     .name("ScheduledAssistant")
 *     .modelConfig(modelConfig)
 *     .sysPrompt("You are a scheduled assistant.")
 *     .build();
 *
 * // Schedule with the configuration
 * ScheduleAgentTask task = scheduler.schedule(config, scheduleConfig);
 * }</pre>
 *
 * <p><b>Usage Example (With Runtime Objects):</b>
 * <pre>{@code
 * // For runtime objects like Toolkit and Hooks, use RuntimeAgentConfig
 * RuntimeAgentConfig runtimeConfig = RuntimeAgentConfig.builder()
 *     .name("ScheduledAssistant")
 *     .modelConfig(modelConfig)
 *     .sysPrompt("You are a scheduled assistant.")
 *     .toolkit(myToolkit)           // Runtime object
 *     .hooks(Arrays.asList(hook1))  // Runtime object
 *     .build();
 *
 * // Schedule with runtime configuration
 * ScheduleAgentTask task = scheduler.schedule(runtimeConfig, scheduleConfig);
 * }</pre>
 *
 * <p><b>Design Notes:</b>
 * <ul>
 *   <li>This class contains only serializable fields (name, modelConfig, sysPrompt)</li>
 *   <li>For runtime objects (toolkit, hooks), use {@link RuntimeAgentConfig} subclass</li>
 *   <li>Name and modelConfig are required fields</li>
 *   <li>System prompt is optional</li>
 *   <li>Immutable after construction using the builder pattern</li>
 * </ul>
 *
 * @see AgentScheduler
 * @see BaseScheduleAgentTask
 * @see ModelConfig
 * @see RuntimeAgentConfig
 */
public class AgentConfig {

    private final String name;
    private final String sysPrompt;
    private final ModelConfig modelConfig;

    protected AgentConfig(Builder builder) {
        this.name = builder.name;
        this.modelConfig = builder.modelConfig;
        this.sysPrompt = builder.sysPrompt;
        validate();
    }

    /**
     * Validate the configuration.
     */
    private void validate() {
        if (name == null || name.trim().isEmpty()) {
            throw new IllegalArgumentException("Agent name must not be null or empty");
        }
        if (modelConfig == null) {
            throw new IllegalArgumentException("ModelConfig must not be null");
        }
    }

    /**
     * Create a new builder instance.
     *
     * @return A new AgentConfig.Builder
     */
    public static Builder builder() {
        return new Builder();
    }

    /**
     * Get the agent name.
     *
     * <p>This name is used as the identifier for the scheduled agent and should be unique
     * within the scheduler. It's also used as the JobHandler name in some scheduler
     * implementations like XXL-Job.
     *
     * @return The agent name
     */
    public String getName() {
        return name;
    }

    /**
     * Get the model configuration.
     *
     * @return The ModelConfig instance
     */
    public ModelConfig getModelConfig() {
        return modelConfig;
    }

    /**
     * Get the system prompt.
     *
     * @return The system prompt, may be null
     */
    public String getSysPrompt() {
        return sysPrompt;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (o == null || getClass() != o.getClass()) return false;
        AgentConfig that = (AgentConfig) o;
        return Objects.equals(name, that.name)
                && Objects.equals(modelConfig, that.modelConfig)
                && Objects.equals(sysPrompt, that.sysPrompt);
    }

    @Override
    public int hashCode() {
        return Objects.hash(name, modelConfig, sysPrompt);
    }

    @Override
    public String toString() {
        return "AgentConfig{"
                + "name='"
                + name
                + '\''
                + ", modelConfig="
                + modelConfig
                + ", sysPrompt='"
                + sysPrompt
                + '\''
                + '}';
    }

    /**
     * Builder for creating AgentConfig instances.
     */
    public static class Builder {
        private String name;
        private ModelConfig modelConfig;
        private String sysPrompt;

        protected Builder() {}

        /**
         * Set the agent name (required).
         *
         * <p>The name is used as the identifier for the scheduled agent and should be unique.
         *
         * @param name The agent name
         * @return This builder
         */
        public Builder name(String name) {
            this.name = name;
            return this;
        }

        /**
         * Set the model configuration (required).
         *
         * <p>ModelConfig provides a flexible way to configure model settings.
         * Use {@link DashScopeModelConfig} for DashScope models.
         *
         * <p>Example:
         * <pre>{@code
         * ModelConfig config = DashScopeModelConfig.builder()
         *     .apiKey(apiKey)
         *     .modelName("qwen-max")
         *     .stream(true)
         *     .build();
         *
         * AgentConfig agentConfig = AgentConfig.builder()
         *     .name("MyAgent")
         *     .modelConfig(config)
         *     .build();
         * }</pre>
         *
         * @param modelConfig The model configuration
         * @return This builder
         */
        public Builder modelConfig(ModelConfig modelConfig) {
            this.modelConfig = modelConfig;
            return this;
        }

        /**
         * Set the system prompt (optional).
         *
         * <p>The system prompt defines the agent's role and behavior.
         *
         * @param sysPrompt The system prompt
         * @return This builder
         */
        public Builder sysPrompt(String sysPrompt) {
            this.sysPrompt = sysPrompt;
            return this;
        }

        /**
         * Build the AgentConfig instance.
         *
         * @return A new AgentConfig instance
         * @throws IllegalArgumentException if required fields are missing or invalid
         */
        public AgentConfig build() {
            return new AgentConfig(this);
        }
    }
}

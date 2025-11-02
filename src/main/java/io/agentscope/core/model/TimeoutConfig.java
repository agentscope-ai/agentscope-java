/*
 * Copyright 2024-2025 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      https://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package io.agentscope.core.model;

import java.time.Duration;

/**
 * Immutable configuration for timeout behavior at different execution levels.
 *
 * <p>Defines timeout parameters for three execution levels in the agent framework:
 * <ul>
 *   <li><b>Agent Call Timeout:</b> Maximum duration for the entire agent.call() execution,
 *       including all reasoning iterations and tool executions</li>
 *   <li><b>Model Request Timeout:</b> Maximum duration for a single model API request</li>
 *   <li><b>Tool Execution Timeout:</b> Maximum duration for a single tool execution</li>
 * </ul>
 *
 * <p><b>Timeout Hierarchy:</b>
 * <pre>
 * Agent Call Timeout (e.g., 5 minutes)
 *   └─ Reasoning Iteration 1
 *       ├─ Model Request (timeout: 2 minutes)
 *       └─ Tool Execution 1 (timeout: 30 seconds)
 *       └─ Tool Execution 2 (timeout: 30 seconds)
 *   └─ Reasoning Iteration 2
 *       ├─ Model Request (timeout: 2 minutes)
 *       └─ Tool Execution 3 (timeout: 30 seconds)
 * </pre>
 *
 * <p><b>Default Behavior:</b>
 * All timeout values default to {@code null}, which means no timeout is applied.
 * Users must explicitly configure timeouts to enable them.
 *
 * <p><b>Usage Example:</b>
 * <pre>{@code
 * // Create timeout config for production use
 * TimeoutConfig config = TimeoutConfig.builder()
 *     .agentCallTimeout(Duration.ofMinutes(5))
 *     .modelRequestTimeout(Duration.ofMinutes(2))
 *     .toolExecutionTimeout(Duration.ofSeconds(30))
 *     .build();
 *
 * // Use in agent
 * ReActAgent agent = ReActAgent.builder()
 *     .name("Assistant")
 *     .model(model)
 *     .timeoutConfig(config)
 *     .build();
 * }</pre>
 *
 * <p>Use the builder pattern to construct instances.
 */
public class TimeoutConfig {

    private final Duration agentCallTimeout;
    private final Duration modelRequestTimeout;
    private final Duration toolExecutionTimeout;

    /**
     * Creates a TimeoutConfig from the builder.
     *
     * @param builder the builder containing timeout configuration
     */
    private TimeoutConfig(Builder builder) {
        this.agentCallTimeout = builder.agentCallTimeout;
        this.modelRequestTimeout = builder.modelRequestTimeout;
        this.toolExecutionTimeout = builder.toolExecutionTimeout;
    }

    /**
     * Gets the maximum duration for the entire agent call.
     *
     * <p>This timeout applies to the whole {@link io.agentscope.core.agent.Agent#call(io.agentscope.core.message.Msg)}
     * execution, including all reasoning iterations and tool executions.
     *
     * @return agent call timeout duration, or null if no timeout is configured
     */
    public Duration getAgentCallTimeout() {
        return agentCallTimeout;
    }

    /**
     * Gets the maximum duration for a single model API request.
     *
     * <p>This timeout applies to individual calls to
     * {@link Model#stream(java.util.List, java.util.List, GenerateOptions)}.
     *
     * @return model request timeout duration, or null if no timeout is configured
     */
    public Duration getModelRequestTimeout() {
        return modelRequestTimeout;
    }

    /**
     * Gets the maximum duration for a single tool execution.
     *
     * <p>This timeout applies to individual tool function executions during the acting phase.
     *
     * @return tool execution timeout duration, or null if no timeout is configured
     */
    public Duration getToolExecutionTimeout() {
        return toolExecutionTimeout;
    }

    /**
     * Creates a new builder for TimeoutConfig.
     *
     * @return a new Builder instance with all timeouts set to null (no timeout)
     */
    public static Builder builder() {
        return new Builder();
    }

    /**
     * Builder for creating TimeoutConfig instances.
     */
    public static class Builder {
        private Duration agentCallTimeout = null;
        private Duration modelRequestTimeout = null;
        private Duration toolExecutionTimeout = null;

        /**
         * Sets the maximum duration for the entire agent call.
         *
         * @param agentCallTimeout agent call timeout duration, or null for no timeout
         * @return this builder instance
         * @throws IllegalArgumentException if agentCallTimeout is negative
         */
        public Builder agentCallTimeout(Duration agentCallTimeout) {
            if (agentCallTimeout != null && agentCallTimeout.isNegative()) {
                throw new IllegalArgumentException("agentCallTimeout must not be negative");
            }
            this.agentCallTimeout = agentCallTimeout;
            return this;
        }

        /**
         * Sets the maximum duration for a single model API request.
         *
         * @param modelRequestTimeout model request timeout duration, or null for no timeout
         * @return this builder instance
         * @throws IllegalArgumentException if modelRequestTimeout is negative
         */
        public Builder modelRequestTimeout(Duration modelRequestTimeout) {
            if (modelRequestTimeout != null && modelRequestTimeout.isNegative()) {
                throw new IllegalArgumentException("modelRequestTimeout must not be negative");
            }
            this.modelRequestTimeout = modelRequestTimeout;
            return this;
        }

        /**
         * Sets the maximum duration for a single tool execution.
         *
         * @param toolExecutionTimeout tool execution timeout duration, or null for no timeout
         * @return this builder instance
         * @throws IllegalArgumentException if toolExecutionTimeout is negative
         */
        public Builder toolExecutionTimeout(Duration toolExecutionTimeout) {
            if (toolExecutionTimeout != null && toolExecutionTimeout.isNegative()) {
                throw new IllegalArgumentException("toolExecutionTimeout must not be negative");
            }
            this.toolExecutionTimeout = toolExecutionTimeout;
            return this;
        }

        /**
         * Builds a new TimeoutConfig instance with the configured values.
         *
         * @return a new TimeoutConfig instance
         */
        public TimeoutConfig build() {
            return new TimeoutConfig(this);
        }
    }
}

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
package io.agentscope.core.tool;

import java.util.concurrent.ExecutorService;

/**
 * Configuration for Toolkit execution behavior.
 *
 * <p>This class defines how tools are executed:
 * - Parallel vs Sequential execution
 * - Custom ExecutorService (optional)
 *
 * <p>By default, all tool execution is asynchronous using Reactor's Schedulers.
 * Users only need to decide whether to execute tools in parallel or sequentially.
 */
public class ToolkitConfig {

    private final boolean parallel;
    private final ExecutorService executorService;

    private ToolkitConfig(Builder builder) {
        this.parallel = builder.parallel;
        this.executorService = builder.executorService;
    }

    /**
     * Whether tools should be executed in parallel.
     *
     * @return true if parallel execution is enabled
     */
    public boolean isParallel() {
        return parallel;
    }

    /**
     * Get the custom executor service if provided.
     *
     * @return ExecutorService or null if using default Reactor schedulers
     */
    public ExecutorService getExecutorService() {
        return executorService;
    }

    /**
     * Check if a custom executor service was provided.
     *
     * @return true if custom executor is configured
     */
    public boolean hasCustomExecutor() {
        return executorService != null;
    }

    /**
     * Create a new builder for ToolkitConfig.
     *
     * @return Builder instance
     */
    public static Builder builder() {
        return new Builder();
    }

    /**
     * Get the default configuration (sequential execution using Reactor).
     *
     * @return Default ToolkitConfig
     */
    public static ToolkitConfig defaultConfig() {
        return builder().build();
    }

    /**
     * Builder for ToolkitConfig.
     */
    public static class Builder {
        private boolean parallel = false;
        private ExecutorService executorService;

        private Builder() {}

        /**
         * Set whether to execute tools in parallel.
         *
         * @param parallel true for parallel execution, false for sequential
         * @return this builder
         */
        public Builder parallel(boolean parallel) {
            this.parallel = parallel;
            return this;
        }

        /**
         * Set a custom executor service for tool execution.
         * If not set, Reactor's Schedulers.boundedElastic() will be used.
         *
         * @param executorService Custom executor service
         * @return this builder
         */
        public Builder executorService(ExecutorService executorService) {
            this.executorService = executorService;
            return this;
        }

        /**
         * Build the ToolkitConfig.
         *
         * @return Configured ToolkitConfig instance
         */
        public ToolkitConfig build() {
            return new ToolkitConfig(this);
        }
    }
}

/*
 * Copyright 2024-2026 the original author or authors.
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
package io.agentscope.core.state;

/**
 * Configuration for which components ReActAgent should manage state persistence.
 *
 * <p>By default, ReActAgent manages state persistence for all its components (memory, toolkit,
 * planNotebook, stateful tools). Users can selectively disable management for specific components
 * to handle their state independently.
 *
 * <p>Example usage:
 *
 * <pre>{@code
 * // Default: manage all components
 * ReActAgent agent = ReActAgent.builder()
 *     .name("assistant")
 *     .model(model)
 *     .memory(memory)
 *     .planNotebook(planNotebook)
 *     .build();
 *
 * // Exclude PlanNotebook: user manages it independently
 * ReActAgent agent = ReActAgent.builder()
 *     .name("assistant")
 *     .model(model)
 *     .memory(memory)
 *     .planNotebook(planNotebook)
 *     .statePersistence(StatePersistence.builder()
 *         .planNotebookManaged(false)
 *         .build())
 *     .build();
 *
 * // Only manage Memory
 * ReActAgent agent = ReActAgent.builder()
 *     .name("assistant")
 *     .model(model)
 *     .memory(memory)
 *     .statePersistence(StatePersistence.memoryOnly())
 *     .build();
 *
 * // Don't manage any components (user fully controls)
 * ReActAgent agent = ReActAgent.builder()
 *     .name("assistant")
 *     .model(model)
 *     .statePersistence(StatePersistence.none())
 *     .build();
 * }</pre>
 *
 * @param memoryManaged whether to manage Memory component state
 * @param toolkitManaged whether to manage Toolkit activeGroups state
 * @param planNotebookManaged whether to manage PlanNotebook state
 * @param statefulToolsManaged whether to manage stateful Tool states
 * @see StateModule
 * @see io.agentscope.core.ReActAgent
 */
public record StatePersistence(
        boolean memoryManaged,
        boolean toolkitManaged,
        boolean planNotebookManaged,
        boolean statefulToolsManaged) {

    /** Default configuration: manage all components. */
    public static StatePersistence all() {
        return new StatePersistence(true, true, true, true);
    }

    /** Don't manage any components (user fully controls). */
    public static StatePersistence none() {
        return new StatePersistence(false, false, false, false);
    }

    /** Only manage Memory component. */
    public static StatePersistence memoryOnly() {
        return new StatePersistence(true, false, false, false);
    }

    /**
     * Creates a new builder for constructing StatePersistence instances.
     *
     * @return A new builder instance with all components enabled by default
     */
    public static Builder builder() {
        return new Builder();
    }

    /** Builder for constructing StatePersistence instances with customizable settings. */
    public static class Builder {

        private boolean memoryManaged = true;
        private boolean toolkitManaged = true;
        private boolean planNotebookManaged = true;
        private boolean statefulToolsManaged = true;

        /**
         * Sets whether to manage Memory component state.
         *
         * @param managed true to manage Memory state, false to let user manage
         * @return This builder for method chaining
         */
        public Builder memoryManaged(boolean managed) {
            this.memoryManaged = managed;
            return this;
        }

        /**
         * Sets whether to manage Toolkit activeGroups state.
         *
         * @param managed true to manage Toolkit state, false to let user manage
         * @return This builder for method chaining
         */
        public Builder toolkitManaged(boolean managed) {
            this.toolkitManaged = managed;
            return this;
        }

        /**
         * Sets whether to manage PlanNotebook state.
         *
         * @param managed true to manage PlanNotebook state, false to let user manage
         * @return This builder for method chaining
         */
        public Builder planNotebookManaged(boolean managed) {
            this.planNotebookManaged = managed;
            return this;
        }

        /**
         * Sets whether to manage stateful Tool states.
         *
         * @param managed true to manage stateful Tool states, false to let user manage
         * @return This builder for method chaining
         */
        public Builder statefulToolsManaged(boolean managed) {
            this.statefulToolsManaged = managed;
            return this;
        }

        /**
         * Builds a new StatePersistence with the configured settings.
         *
         * @return A new StatePersistence instance
         */
        public StatePersistence build() {
            return new StatePersistence(
                    memoryManaged, toolkitManaged, planNotebookManaged, statefulToolsManaged);
        }
    }
}

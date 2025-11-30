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

import io.agentscope.core.model.ExecutionConfig;
import java.util.concurrent.ExecutorService;

/**
 * Configuration for Toolkit execution behavior.
 *
 * <p>This class defines how tools are executed:
 * - Parallel vs Sequential execution
 * - Custom ExecutorService (optional)
 * - Execution configuration for timeout and retry
 * - Agent skill instruction and template (not set up by default)
 *
 * <p>By default, all tool execution is asynchronous using Reactor's Schedulers.
 * The default execution config provides 5-minute timeout with no retry (1 attempt).
 */
public class ToolkitConfig {

    private final boolean parallel;
    private final ExecutorService executorService;
    private final ExecutionConfig executionConfig;
    private final boolean allowToolDeletion;
    private final ToolExecutionContext defaultContext;
    private final String agentSkillInstruction;
    private final String agentSkillTemplate;

    private ToolkitConfig(Builder builder) {
        this.parallel = builder.parallel;
        this.executorService = builder.executorService;
        this.executionConfig = builder.executionConfig;
        this.allowToolDeletion = builder.allowToolDeletion;
        this.defaultContext = builder.defaultContext;
        this.agentSkillInstruction = builder.agentSkillInstruction;
        this.agentSkillTemplate = builder.agentSkillTemplate;
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
     * Get the execution configuration for timeout and retry.
     *
     * @return ExecutionConfig or null if not configured (defaults will be applied)
     */
    public ExecutionConfig getExecutionConfig() {
        return executionConfig;
    }

    /**
     * Check if tool deletion is allowed.
     *
     * @return true if tool deletion is allowed (default), false otherwise
     */
    public boolean isAllowToolDeletion() {
        return allowToolDeletion;
    }

    /**
     * Get the default tool execution context.
     *
     * <p>This context is used as the base for all tool calls and can be overridden by
     * agent-level or call-level contexts.
     *
     * @return The default context, or null if not configured
     */
    public ToolExecutionContext getDefaultContext() {
        return defaultContext;
    }

    /**
     * Get the agent skill instruction.
     *
     * @return Agent skill instruction string
     */
    public String getAgentSkillInstruction() {
        return agentSkillInstruction;
    }

    /**
     * Get the agent skill template.
     *
     * @return Agent skill template string
     */
    public String getAgentSkillTemplate() {
        return agentSkillTemplate;
    }

    /**
     * Create a new builder for ToolkitConfig.
     *
     * @return Builder instance
     */
    public static Builder builder() {
        return new Builder();
    }

    private static final String DEFAULT_AGENT_SKILL_INSTRUCTION =
            "# Agent Skills\n"
                    + "The agent skills are a collection of folds of instructions, scripts, "
                    + "and resources that you can load dynamically to improve performance "
                    + "on specialized tasks. Each agent skill has a `SKILL.md` file in its "
                    + "folder that describes how to use the skill. If you want to use a "
                    + "skill, you MUST read its `SKILL.md` file carefully.\n";

    // The placeholders are name, description, skill directory in order.
    private static final String DEFAULT_AGENT_SKILL_TEMPLATE =
            """
            ## %s
            %s
            Check "%s/SKILL.md" for how to use this skill
            """;

    /**
     * Get the default configuration (sequential execution using Reactor).
     *
     * @return Default ToolkitConfig
     */
    public static ToolkitConfig defaultConfig() {
        return builder()
                .agentSkillInstruction(DEFAULT_AGENT_SKILL_INSTRUCTION)
                .agentSkillTemplate(DEFAULT_AGENT_SKILL_TEMPLATE)
                .build();
    }

    /**
     * Builder for ToolkitConfig.
     */
    public static class Builder {
        private boolean parallel = false;
        private ExecutorService executorService;
        private ExecutionConfig executionConfig;
        private boolean allowToolDeletion = true;
        private ToolExecutionContext defaultContext;
        private String agentSkillInstruction;
        private String agentSkillTemplate;

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
         * Set the execution configuration for timeout and retry behavior.
         * If not set, tool execution defaults will be applied (5 minutes timeout, no retry).
         *
         * @param executionConfig Execution configuration for tools
         * @return this builder
         */
        public Builder executionConfig(ExecutionConfig executionConfig) {
            this.executionConfig = executionConfig;
            return this;
        }

        /**
         * Set whether tool deletion is allowed.
         * When set to false, calls to removeTool(), removeToolGroups(), and
         * updateToolGroups(groups, false) will be ignored.
         *
         * @param allowToolDeletion true to allow deletion (default), false to prevent
         * @return this builder
         */
        public Builder allowToolDeletion(boolean allowToolDeletion) {
            this.allowToolDeletion = allowToolDeletion;
            return this;
        }

        /**
         * Set the default tool execution context for all tools.
         *
         * <p>This context will be used as the base for all tool executions and can be
         * overridden by agent-level or call-level contexts through the merge mechanism.
         *
         * @param defaultContext The default execution context
         * @return this builder
         */
        public Builder defaultContext(ToolExecutionContext defaultContext) {
            this.defaultContext = defaultContext;
            return this;
        }

        /**
         * Set the agent skill instruction.
         *
         * @param agentSkillInstruction The agent skill instruction
         * @return this builder
         */
        public Builder agentSkillInstruction(String agentSkillInstruction) {
            this.agentSkillInstruction = agentSkillInstruction;
            return this;
        }

        /**
         * Set the agent skill template.
         *
         * @param agentSkillTemplate The agent skill template
         * @return this builder
         */
        public Builder agentSkillTemplate(String agentSkillTemplate) {
            this.agentSkillTemplate = agentSkillTemplate;
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

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

import io.agentscope.core.hook.Hook;
import io.agentscope.core.model.Model;
import io.agentscope.core.tool.Toolkit;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * Runtime Agent configuration that extends AgentConfig with non-serializable runtime objects.
 *
 * <p>This subclass adds runtime objects like Toolkit and Hooks that cannot be serialized.
 * Use this class when you need to configure agents with tools and lifecycle hooks in the
 * same JVM process.
 *
 * <p><b>When to Use:</b>
 * <ul>
 *   <li><b>RuntimeAgentConfig</b> - When scheduling in the same JVM with toolkit/hooks</li>
 *   <li><b>AgentConfig</b> - When persisting config or using distributed scheduling</li>
 * </ul>
 *
 * <p><b>Usage Example:</b>
 * <pre>{@code
 * // Create toolkit
 * Toolkit toolkit = new Toolkit();
 * toolkit.registerTool(new WeatherTool());
 *
 * // Create hooks
 * List<Hook> hooks = Arrays.asList(
 *     new LoggingHook(),
 *     new MetricsHook()
 * );
 *
 * // Create runtime configuration
 * RuntimeAgentConfig config = RuntimeAgentConfig.builder()
 *     .name("MyAgent")
 *     .modelConfig(modelConfig)
 *     .sysPrompt("You are a helpful assistant.")
 *     .toolkit(toolkit)      // Runtime object
 *     .hooks(hooks)          // Runtime object
 *     .build();
 *
 * // Schedule with runtime configuration
 * ScheduleAgentTask task = scheduler.schedule(config, scheduleConfig);
 * }</pre>
 *
 * <p><b>Design Notes:</b>
 * <ul>
 *   <li>Extends AgentConfig to inherit serializable configuration</li>
 *   <li>Adds toolkit and hooks as runtime-only properties</li>
 *   <li>Not suitable for serialization or distributed scenarios</li>
 *   <li>Immutable after construction</li>
 * </ul>
 *
 * @see AgentConfig
 * @see Toolkit
 * @see Hook
 */
public class RuntimeAgentConfig extends AgentConfig {

    private final Model model;
    private final Toolkit toolkit;
    private final List<Hook> hooks;

    private RuntimeAgentConfig(Builder builder) {
        super(builder);
        this.toolkit = builder.toolkit;
        this.hooks = new CopyOnWriteArrayList<>(builder.hooks != null ? builder.hooks : List.of());
        this.model = builder.model;
    }

    /**
     * Create a new builder instance.
     *
     * @return A new RuntimeAgentConfig.Builder
     */
    public static Builder builder() {
        return new Builder();
    }

    /**
     * Get the toolkit.
     *
     * @return The toolkit instance, may be null
     */
    public Toolkit getToolkit() {
        return toolkit;
    }

    /**
     * Get the hooks.
     *
     * @return The list of hooks, never null but may be empty
     */
    public List<Hook> getHooks() {
        return hooks;
    }

    /**
     * Get the pre-created Model instance.
     *
     * <p>The model is automatically created from ModelConfig during the build process.
     * This avoids repeated model creation for each agent execution.
     *
     * @return The Model instance, may be null if not set
     */
    public Model getModel() {
        return model;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (o == null || getClass() != o.getClass()) return false;
        if (!super.equals(o)) return false;
        RuntimeAgentConfig that = (RuntimeAgentConfig) o;
        return Objects.equals(model, that.model)
                && Objects.equals(toolkit, that.toolkit)
                && Objects.equals(hooks, that.hooks);
    }

    @Override
    public int hashCode() {
        return Objects.hash(super.hashCode(), model, toolkit, hooks);
    }

    @Override
    public String toString() {
        return "RuntimeAgentConfig{"
                + "name='"
                + getName()
                + '\''
                + ", modelConfig="
                + getModelConfig()
                + ", sysPrompt='"
                + getSysPrompt()
                + '\''
                + ", model="
                + model
                + ", toolkit="
                + toolkit
                + ", hooks="
                + hooks
                + '}';
    }

    /**
     * Builder for creating RuntimeAgentConfig instances.
     */
    public static class Builder extends AgentConfig.Builder {

        private Toolkit toolkit;

        private List<Hook> hooks;

        private Model model;

        private Builder() {}

        /**
         * Set the agent name (required).
         *
         * @param name The agent name
         * @return This builder
         */
        @Override
        public Builder name(String name) {
            super.name(name);
            return this;
        }

        /**
         * Set the model configuration (required).
         *
         * <p>This method automatically creates the Model instance from the ModelConfig
         * during the build process, so it's ready for use without repeated creation.
         *
         * @param modelConfig The model configuration
         * @return This builder
         */
        @Override
        public Builder modelConfig(ModelConfig modelConfig) {
            super.modelConfig(modelConfig);
            this.model = modelConfig.createModel();
            return this;
        }

        /**
         * Set the system prompt (optional).
         *
         * @param sysPrompt The system prompt
         * @return This builder
         */
        @Override
        public Builder sysPrompt(String sysPrompt) {
            super.sysPrompt(sysPrompt);
            return this;
        }

        /**
         * Set the toolkit (optional).
         *
         * <p>The toolkit provides tools that the agent can use during execution.
         *
         * @param toolkit The toolkit instance
         * @return This builder
         */
        public Builder toolkit(Toolkit toolkit) {
            this.toolkit = toolkit;
            return this;
        }

        /**
         * Set the hooks (optional).
         *
         * <p>The hooks provide extension points for agent lifecycle events.
         *
         * @param hooks The list of hooks
         * @return This builder
         */
        public Builder hooks(List<Hook> hooks) {
            this.hooks = hooks;
            return this;
        }

        /**
         * Build the RuntimeAgentConfig instance.
         *
         * @return A new RuntimeAgentConfig instance
         * @throws IllegalArgumentException if required fields are missing or invalid
         */
        @Override
        public RuntimeAgentConfig build() {
            return new RuntimeAgentConfig(this);
        }
    }
}

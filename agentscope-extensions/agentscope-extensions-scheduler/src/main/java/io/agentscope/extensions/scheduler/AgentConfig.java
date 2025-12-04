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
package io.agentscope.extensions.scheduler;

import io.agentscope.core.hook.Hook;
import io.agentscope.core.memory.Memory;
import io.agentscope.core.model.Model;
import io.agentscope.core.tool.Toolkit;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * Configuration for creating Agent instances in scheduled tasks.
 *
 * <p>This class encapsulates all the components needed to construct an Agent instance
 * for scheduled task execution. It holds the agent's name, model, toolkit, system prompt,
 * memory, and other configuration details that define the agent's behavior.
 *
 * <p><b>Key Benefits:</b>
 * <ul>
 *   <li><b>Centralized Configuration</b> - All agent components in one place</li>
 *   <li><b>Serializable</b> - Easier to persist and transmit configuration data</li>
 *   <li><b>Reusable</b> - Configuration can be used to create multiple agent instances</li>
 *   <li><b>Type-Safe</b> - Compile-time validation of required components</li>
 * </ul>
 *
 * <p><b>Usage Example:</b>
 * <pre>{@code
 * // Create agent configuration
 * AgentConfig config = AgentConfig.builder()
 *     .name("ScheduledAssistant")
 *     .model(myModel)
 *     .toolkit(myToolkit)
 *     .sysPrompt("You are a scheduled assistant.")
 *     .memory(new InMemoryMemory())
 *     .build();
 *
 * // Schedule with the configuration
 * ScheduleAgent scheduledAgent = scheduler.schedule(config, scheduleConfig);
 * }</pre>
 *
 * <p><b>Design Notes:</b>
 * <ul>
 *   <li>Name and model are required fields</li>
 *   <li>Toolkit, system prompt, and memory are optional</li>
 *   <li>Immutable after construction using the builder pattern</li>
 * </ul>
 *
 * @author yaohui
 * @see AgentScheduler
 * @see BaseScheduleAgentTask
 */
public class AgentConfig {

    private final String name;
    private final String sysPrompt;
	private final Model model;
	private final Toolkit toolkit;
	private final Memory memory;
    private final List<Hook> hooks;

    private AgentConfig(Builder builder) {
        this.name = builder.name;
        this.model = builder.model;
        this.toolkit = builder.toolkit;
        this.sysPrompt = builder.sysPrompt;
        this.memory = builder.memory;
        this.hooks = new CopyOnWriteArrayList<>(builder.hooks != null ? builder.hooks : List.of());
        validate();
    }

    /**
     * Validate the configuration.
     */
    private void validate() {
        if (name == null || name.trim().isEmpty()) {
            throw new IllegalArgumentException("Agent name must not be null or empty");
        }
        if (model == null) {
            throw new IllegalArgumentException("Model must not be null");
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
     * @return The model instance
     */
    public Model getModel() {
        return model;
    }

    /**
     * Get the toolkit configuration.
     *
     * @return The toolkit instance, may be null
     */
    public Toolkit getToolkit() {
        return toolkit;
    }

    /**
     * Get the system prompt.
     *
     * @return The system prompt, may be null
     */
    public String getSysPrompt() {
        return sysPrompt;
    }

    /**
     * Get the memory configuration.
     *
     * @return The memory instance, may be null
     */
    public Memory getMemory() {
        return memory;
    }

    /**
     * Get the hooks configuration.
     *
     * @return The list of hooks, may be null
     */
    public List<Hook> getHooks() {
        return hooks;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (o == null || getClass() != o.getClass()) return false;
        AgentConfig that = (AgentConfig) o;
        return Objects.equals(name, that.name)
                && Objects.equals(model, that.model)
                && Objects.equals(toolkit, that.toolkit)
                && Objects.equals(sysPrompt, that.sysPrompt)
                && Objects.equals(memory, that.memory)
                && Objects.equals(hooks, that.hooks);
    }

    @Override
    public int hashCode() {
        return Objects.hash(name, model, toolkit, sysPrompt, memory, hooks);
    }

    @Override
    public String toString() {
        return "AgentConfig{"
                + "name='"
                + name
                + '\''
                + ", model="
                + model
                + ", toolkit="
                + toolkit
                + ", sysPrompt='"
                + sysPrompt
                + '\''
                + ", memory="
                + memory
                + ", hooks="
                + hooks
                + '}';
    }

    /**
     * Builder for creating AgentConfig instances.
     */
    public static class Builder {
        private String name;
        private Model model;
        private Toolkit toolkit;
        private String sysPrompt;
        private Memory memory;
        private List<Hook> hooks;

        private Builder() {}

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
         * Set the model (required).
         *
         * <p>The model is used for agent's LLM interactions.
         *
         * @param model The model instance
         * @return This builder
         */
        public Builder model(Model model) {
            this.model = model;
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
         * Set the memory (optional).
         *
         * <p>The memory stores conversation history and context.
         *
         * @param memory The memory instance
         * @return This builder
         */
        public Builder memory(Memory memory) {
            this.memory = memory;
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

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
package io.agentscope.core.tool.subagent;

import io.agentscope.core.agent.StreamOptions;
import io.agentscope.core.session.InMemorySession;
import io.agentscope.core.session.JsonSession;
import io.agentscope.core.session.Session;

/**
 * Configuration for sub-agent registration.
 *
 * <p>This class provides configuration options for how a sub-agent is exposed as a tool. It
 * supports smart defaults that derive tool name and description from the agent itself.
 *
 * <p>Sub-agents operate in conversation mode, supporting multi-turn dialogue with session
 * management. The tool exposes two parameters:
 *
 * <ul>
 *   <li>{@code message} - Required. The message to send to the agent.
 *   <li>{@code conversation_id} - Optional. Omit to start a new conversation, provide to continue
 *       an existing one.
 * </ul>
 *
 * <p><b>Default Behavior:</b>
 *
 * <ul>
 *   <li>Tool name: derived from agent name (e.g., "ResearchAgent" → "call_researchagent")
 *   <li>Description: uses agent's description, or generates a default
 *   <li>Session: uses {@link InMemorySession} for conversation state management
 *   <li>Event forwarding: enabled by default
 * </ul>
 *
 * <p>Example usage:
 *
 * <pre>{@code
 * // Minimal configuration - uses all defaults
 * SubAgentConfig config = SubAgentConfig.defaults();
 *
 * // Custom configuration with persistent session
 * SubAgentConfig config = SubAgentConfig.builder()
 *     .toolName("ask_expert")
 *     .description("Ask the expert a question")
 *     .session(new JsonSession(Path.of("sessions")))
 *     .build();
 * }</pre>
 */
public class SubAgentConfig {

    private final String toolName;
    private final String description;
    private final boolean forwardEvents;
    private final StreamOptions streamOptions;
    private final Session session;

    private SubAgentConfig(Builder builder) {
        this.toolName = builder.toolName;
        this.description = builder.description;
        this.forwardEvents = builder.forwardEvents;
        this.streamOptions = builder.streamOptions;
        this.session = builder.session != null ? builder.session : new InMemorySession();
    }

    /**
     * Creates a default configuration.
     *
     * <p>Tool name and description are derived from the agent at registration time.
     *
     * @return Default configuration
     */
    public static SubAgentConfig defaults() {
        return builder().build();
    }

    /**
     * Creates a new builder.
     *
     * @return New builder instance
     */
    public static Builder builder() {
        return new Builder();
    }

    /**
     * Gets the tool name override.
     *
     * @return Tool name, or null to derive from agent
     */
    public String getToolName() {
        return toolName;
    }

    /**
     * Gets the description override.
     *
     * @return Description, or null to derive from agent
     */
    public String getDescription() {
        return description;
    }

    /**
     * Gets whether to forward sub-agent events to the parent agent.
     *
     * <p>When enabled, events from the sub-agent (reasoning chunks, tool execution chunks) are
     * forwarded via ToolEmitter to the parent agent's hooks.
     *
     * @return true if events should be forwarded (default: true)
     */
    public boolean isForwardEvents() {
        return forwardEvents;
    }

    /**
     * Gets the stream options for event forwarding.
     *
     * <p>Controls which event types to forward and streaming mode (incremental vs cumulative). Only
     * applicable when {@link #isForwardEvents()} returns true.
     *
     * @return Stream options, or null to use defaults
     */
    public StreamOptions getStreamOptions() {
        return streamOptions;
    }

    /**
     * Gets the session for conversation state management.
     *
     * <p>The session is used to persist and restore sub-agent state across conversation turns. By
     * default, an {@link InMemorySession} is used.
     *
     * @return The session instance (never null)
     */
    public Session getSession() {
        return session;
    }

    /** Builder for SubAgentConfig. */
    public static class Builder {
        private String toolName;
        private String description;
        private boolean forwardEvents = true;
        private StreamOptions streamOptions;
        private Session session;

        private Builder() {}

        /**
         * Sets the tool name.
         *
         * <p>If not set, the tool name is derived from the agent's name.
         *
         * @param toolName The tool name
         * @return This builder
         */
        public Builder toolName(String toolName) {
            this.toolName = toolName;
            return this;
        }

        /**
         * Sets the tool description.
         *
         * <p>If not set, the description is derived from the agent's description.
         *
         * @param description The description
         * @return This builder
         */
        public Builder description(String description) {
            this.description = description;
            return this;
        }

        /**
         * Sets whether to forward sub-agent events to the parent agent.
         *
         * <p>When enabled, events from the sub-agent (reasoning chunks, tool execution chunks) are
         * forwarded via ToolEmitter to the parent agent's hooks.
         *
         * @param forwardEvents true to forward events (default: true)
         * @return This builder
         */
        public Builder forwardEvents(boolean forwardEvents) {
            this.forwardEvents = forwardEvents;
            return this;
        }

        /**
         * Sets the stream options for event forwarding.
         *
         * <p>Controls which event types to forward and streaming mode. Only applicable when {@link
         * #forwardEvents(boolean)} is true.
         *
         * @param streamOptions The stream options (null for defaults)
         * @return This builder
         */
        public Builder streamOptions(StreamOptions streamOptions) {
            this.streamOptions = streamOptions;
            return this;
        }

        /**
         * Sets the session for conversation state management.
         *
         * <p>The session is used to persist and restore sub-agent state across conversation turns.
         * If not set, an {@link InMemorySession} is used by default.
         *
         * <p>To enable persistent conversations across process restarts, use a persistent session
         * implementation like {@link JsonSession}.
         *
         * @param session The session instance
         * @return This builder
         */
        public Builder session(Session session) {
            this.session = session;
            return this;
        }

        /**
         * Builds the configuration.
         *
         * @return New SubAgentConfig instance
         */
        public SubAgentConfig build() {
            return new SubAgentConfig(this);
        }
    }
}

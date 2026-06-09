/*
 * Copyright 2024-2026 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package io.agentscope.core.agui.model;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonProperty;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * Input model for running an agent via the AG-UI protocol.
 *
 * <p>This class represents the complete input needed to invoke an agent,
 * including messages, tools, context, state, and forwarded properties.
 */
public class RunAgentInput {

    private final String threadId;
    private final String runId;
    private final List<AguiMessage> messages;
    private final List<AguiTool> tools;
    private final List<AguiContext> context;
    private final Map<String, Object> state;
    private final Map<String, Object> forwardedProps;

    /**
     * Creates a new RunAgentInput.
     *
     * @param threadId The thread ID for this conversation
     * @param runId The unique run ID
     * @param messages The conversation messages
     * @param tools Frontend-provided tools
     * @param context Additional context information
     * @param state Initial state to load
     * @param forwardedProps Additional properties to forward
     */
    @JsonCreator
    public RunAgentInput(
            @JsonProperty("threadId") String threadId,
            @JsonProperty("runId") String runId,
            @JsonProperty("messages") List<AguiMessage> messages,
            @JsonProperty("tools") List<AguiTool> tools,
            @JsonProperty("context") List<AguiContext> context,
            @JsonProperty("state") Map<String, Object> state,
            @JsonProperty("forwardedProps") Map<String, Object> forwardedProps) {
        this.threadId = Objects.requireNonNull(threadId, "threadId cannot be null");
        this.runId = Objects.requireNonNull(runId, "runId cannot be null");
        this.messages =
                messages != null ? Collections.unmodifiableList(messages) : Collections.emptyList();
        this.tools = tools != null ? Collections.unmodifiableList(tools) : Collections.emptyList();
        this.context =
                context != null ? Collections.unmodifiableList(context) : Collections.emptyList();
        this.state =
                state != null
                        ? Collections.unmodifiableMap(new HashMap<>(state))
                        : Collections.emptyMap();
        this.forwardedProps =
                forwardedProps != null
                        ? Collections.unmodifiableMap(new HashMap<>(forwardedProps))
                        : Collections.emptyMap();
    }

    /**
     * Get the thread ID.
     *
     * @return The thread ID
     */
    public String getThreadId() {
        return threadId;
    }

    /**
     * Get the run ID.
     *
     * @return The run ID
     */
    public String getRunId() {
        return runId;
    }

    /**
     * Get the conversation messages.
     *
     * @return The messages as an immutable list
     */
    public List<AguiMessage> getMessages() {
        return messages;
    }

    /**
     * Get the frontend-provided tools.
     *
     * @return The tools as an immutable list
     */
    public List<AguiTool> getTools() {
        return tools;
    }

    /**
     * Get the context information.
     *
     * @return The context as an immutable list
     */
    public List<AguiContext> getContext() {
        return context;
    }

    /**
     * Get the initial state.
     *
     * @return The state as an immutable map
     */
    public Map<String, Object> getState() {
        return state;
    }

    /**
     * Get the forwarded properties.
     *
     * @return The forwarded properties as an immutable map
     */
    public Map<String, Object> getForwardedProps() {
        return forwardedProps;
    }

    /**
     * Get a specific forwarded property.
     *
     * @param key The property key
     * @return The property value, or null if not present
     */
    public Object getForwardedProp(String key) {
        return forwardedProps.get(key);
    }

    /**
     * Get a specific forwarded property with a default value.
     *
     * @param key The property key
     * @param defaultValue The default value if not present
     * @return The property value, or the default if not present
     */
    public Object getForwardedProp(String key, Object defaultValue) {
        return forwardedProps.getOrDefault(key, defaultValue);
    }

    /**
     * Check if there are any messages.
     *
     * @return true if messages are present
     */
    public boolean hasMessages() {
        return messages != null && !messages.isEmpty();
    }

    /**
     * Check if there are any frontend tools.
     *
     * @return true if tools are present
     */
    public boolean hasTools() {
        return tools != null && !tools.isEmpty();
    }

    /**
     * Check if there is any context.
     *
     * @return true if context is present
     */
    public boolean hasContext() {
        return context != null && !context.isEmpty();
    }

    /**
     * Check if there is initial state.
     *
     * @return true if state is present
     */
    public boolean hasState() {
        return state != null && !state.isEmpty();
    }

    @Override
    public String toString() {
        return "RunAgentInput{threadId='"
                + threadId
                + "', runId='"
                + runId
                + "', messages="
                + messages.size()
                + ", tools="
                + tools.size()
                + ", context="
                + context.size()
                + ", state="
                + state.size()
                + ", forwardedProps="
                + forwardedProps.size()
                + "}";
    }

    /**
     * Creates a new builder for RunAgentInput.
     *
     * @return A new builder instance
     */
    public static Builder builder() {
        return new Builder();
    }

    /**
     * Builder for RunAgentInput.
     */
    public static class Builder {
        private String threadId;
        private String runId;
        private List<AguiMessage> messages;
        private List<AguiTool> tools;
        private List<AguiContext> context;
        private Map<String, Object> state;
        private Map<String, Object> forwardedProps;

        public Builder threadId(String threadId) {
            this.threadId = threadId;
            return this;
        }

        public Builder runId(String runId) {
            this.runId = runId;
            return this;
        }

        public Builder messages(List<AguiMessage> messages) {
            this.messages = messages;
            return this;
        }

        public Builder tools(List<AguiTool> tools) {
            this.tools = tools;
            return this;
        }

        public Builder context(List<AguiContext> context) {
            this.context = context;
            return this;
        }

        public Builder state(Map<String, Object> state) {
            this.state = state;
            return this;
        }

        public Builder forwardedProps(Map<String, Object> forwardedProps) {
            this.forwardedProps = forwardedProps;
            return this;
        }

        public RunAgentInput build() {
            return new RunAgentInput(
                    threadId, runId, messages, tools, context, state, forwardedProps);
        }
    }
}

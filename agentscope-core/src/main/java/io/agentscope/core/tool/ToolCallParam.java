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
package io.agentscope.core.tool;

import io.agentscope.core.agent.Agent;
import io.agentscope.core.message.ToolUseBlock;
import java.util.Collections;
import java.util.HashMap;
import java.util.Map;

/**
 * Parameters for tool invocation.
 *
 * <p>This class encapsulates all the information needed to call a tool, including the tool use
 * block (containing metadata like call ID and tool name), input parameters, and the calling agent.
 *
 * <p>Using a parameter object instead of multiple method parameters provides better extensibility
 * - new parameters can be added without breaking existing code.
 *
 * <p><b>Example usage:</b>
 * <pre>{@code
 * ToolCallParam param = ToolCallParam.builder()
 *     .toolUseBlock(toolUseBlock)
 *     .input(Map.of("key", "value"))
 *     .agent(agent)
 *     .build();
 *
 * ToolResultBlock result = tool.callAsync(param).block();
 * }</pre>
 */
public class ToolCallParam {

    private final ToolUseBlock toolUseBlock;
    private final Map<String, Object> input;
    private final Agent agent;
    private final ToolExecutionContext context;
    private final ToolEmitter emitter;

    private ToolCallParam(Builder builder) {
        this.toolUseBlock = builder.toolUseBlock;
        this.input = builder.input != null ? new HashMap<>(builder.input) : Collections.emptyMap();
        this.agent = builder.agent;
        this.context = builder.context;
        this.emitter = builder.emitter;
    }

    /**
     * Gets the tool use block containing call metadata.
     *
     * @return The tool use block
     */
    public ToolUseBlock getToolUseBlock() {
        return toolUseBlock;
    }

    /**
     * Gets the input parameters for the tool call.
     *
     * @return Unmodifiable map of input parameters
     */
    public Map<String, Object> getInput() {
        return Collections.unmodifiableMap(input);
    }

    /**
     * Gets the agent making the call.
     *
     * @return The agent, or null if not provided
     */
    public Agent getAgent() {
        return agent;
    }

    /**
     * Gets the tool execution context.
     *
     * @return The execution context, or null if not provided
     */
    public ToolExecutionContext getContext() {
        return context;
    }

    /**
     * Gets the ToolEmitter for streaming tool output.
     *
     * <p>Tools can use this emitter to send intermediate progress updates during execution. If no
     * emitter is configured, returns a no-op emitter that silently discards chunks.
     *
     * @return The ToolEmitter, never null
     */
    public ToolEmitter getEmitter() {
        return emitter != null ? emitter : NoOpToolEmitter.INSTANCE;
    }

    /**
     * Creates a new builder for constructing ToolCallParam instances.
     *
     * @return A new builder
     */
    public static Builder builder() {
        return new Builder();
    }

    /**
     * Builder for ToolCallParam.
     */
    public static class Builder {
        private ToolUseBlock toolUseBlock;
        private Map<String, Object> input;
        private Agent agent;
        private ToolExecutionContext context;
        private ToolEmitter emitter;

        private Builder() {}

        /**
         * Sets the tool use block.
         *
         * @param toolUseBlock The tool use block
         * @return This builder
         */
        public Builder toolUseBlock(ToolUseBlock toolUseBlock) {
            this.toolUseBlock = toolUseBlock;
            return this;
        }

        /**
         * Sets the input parameters.
         *
         * @param input The input parameters
         * @return This builder
         */
        public Builder input(Map<String, Object> input) {
            this.input = input;
            return this;
        }

        /**
         * Sets the calling agent.
         *
         * @param agent The agent
         * @return This builder
         */
        public Builder agent(Agent agent) {
            this.agent = agent;
            return this;
        }

        /**
         * Sets the tool execution context.
         *
         * @param context The execution context
         * @return This builder
         */
        public Builder context(ToolExecutionContext context) {
            this.context = context;
            return this;
        }

        /**
         * Sets the ToolEmitter for streaming tool output.
         *
         * @param emitter The ToolEmitter
         * @return This builder
         */
        public Builder emitter(ToolEmitter emitter) {
            this.emitter = emitter;
            return this;
        }

        /**
         * Builds the ToolCallParam instance.
         *
         * @return A new ToolCallParam
         */
        public ToolCallParam build() {
            return new ToolCallParam(this);
        }
    }
}

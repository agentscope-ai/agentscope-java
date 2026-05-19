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
package io.agentscope.core.agui.adapter;

import io.agentscope.core.agui.adapter.strategy.BlockEventConverter;
import io.agentscope.core.agui.model.ToolMergeMode;
import io.agentscope.core.message.ContentBlock;
import java.time.Duration;
import java.util.Collections;
import java.util.HashMap;
import java.util.Map;

/**
 * Configuration for the AG-UI agent adapter.
 *
 * <p>This class provides configuration options for how the adapter handles
 * tool merging, state events, and other behaviors.
 */
public class AguiAdapterConfig {

    private final ToolMergeMode toolMergeMode;
    private final boolean emitStateEvents;
    private final boolean emitToolCallArgs;
    private final boolean enableReasoning;
    private final boolean enableActingChunk;
    private final Duration runTimeout;
    private final String defaultAgentId;
    private final Map<Class<?>, BlockEventConverter<?>> customConverters;

    private AguiAdapterConfig(Builder builder) {
        this.toolMergeMode = builder.toolMergeMode;
        this.emitStateEvents = builder.emitStateEvents;
        this.emitToolCallArgs = builder.emitToolCallArgs;
        this.enableReasoning = builder.enableReasoning;
        this.enableActingChunk = builder.enableActingChunk;
        this.runTimeout = builder.runTimeout;
        this.defaultAgentId = builder.defaultAgentId;
        this.customConverters =
                builder.customConverters != null
                        ? Map.copyOf(builder.customConverters)
                        : Collections.emptyMap();
    }

    /**
     * Get the tool merge mode.
     *
     * @return The tool merge mode
     */
    public ToolMergeMode getToolMergeMode() {
        return toolMergeMode;
    }

    /**
     * Check if state events should be emitted.
     *
     * @return true if state events should be emitted
     */
    public boolean isEmitStateEvents() {
        return emitStateEvents;
    }

    /**
     * Check if tool call arguments should be streamed.
     *
     * @return true if tool call args events should be emitted
     */
    public boolean isEmitToolCallArgs() {
        return emitToolCallArgs;
    }

    /**
     * Check if reasoning/thinking content should be emitted.
     *
     * <p>When enabled, ThinkingBlock content will be converted to REASONING_* events
     * according to the AG-UI Reasoning draft specification. When disabled (default),
     * ThinkingBlock content is ignored and no reasoning events are emitted.
     *
     * @return true if reasoning events should be emitted
     */
    public boolean isEnableReasoning() {
        return enableReasoning;
    }

    /**
     * Check if intermediate acting chunk emissions should be included.
     *
     * <p>When enabled, intermediate tool execution outputs (such as progress)
     * will be streamed to the frontend.
     *
     * @return true if acting chunks should be included
     */
    public boolean isEnableActingChunk() {
        return enableActingChunk;
    }

    /**
     * Get the run timeout duration.
     *
     * @return The run timeout
     */
    public Duration getRunTimeout() {
        return runTimeout;
    }

    /**
     * Get the default agent ID.
     *
     * @return The default agent ID, or null if not set
     */
    public String getDefaultAgentId() {
        return defaultAgentId;
    }

    /**
     * Get the custom block event converters.
     *
     * @return Immutable map of custom converters
     */
    public Map<Class<?>, BlockEventConverter<?>> getCustomConverters() {
        return customConverters;
    }

    /**
     * Creates a new builder for AguiAdapterConfig.
     *
     * @return A new builder instance
     */
    public static Builder builder() {
        return new Builder();
    }

    /**
     * Creates a default configuration.
     *
     * @return Default configuration
     */
    public static AguiAdapterConfig defaultConfig() {
        return builder().build();
    }

    /**
     * Builder for AguiAdapterConfig.
     */
    public static class Builder {

        private ToolMergeMode toolMergeMode = ToolMergeMode.MERGE_FRONTEND_PRIORITY;
        private boolean emitStateEvents = true;
        private boolean emitToolCallArgs = true;
        private boolean enableReasoning = false;
        private boolean enableActingChunk = true;
        private Duration runTimeout = Duration.ofMinutes(10);
        private String defaultAgentId;
        private final Map<Class<?>, BlockEventConverter<?>> customConverters = new HashMap<>();

        /**
         * Set the tool merge mode.
         *
         * @param toolMergeMode The tool merge mode
         * @return This builder
         */
        public Builder toolMergeMode(ToolMergeMode toolMergeMode) {
            this.toolMergeMode = toolMergeMode;
            return this;
        }

        /**
         * Set whether to emit state events.
         *
         * @param emitStateEvents true to emit state events
         * @return This builder
         */
        public Builder emitStateEvents(boolean emitStateEvents) {
            this.emitStateEvents = emitStateEvents;
            return this;
        }

        /**
         * Set whether to emit tool call argument events.
         *
         * @param emitToolCallArgs true to emit tool call args events
         * @return This builder
         */
        public Builder emitToolCallArgs(boolean emitToolCallArgs) {
            this.emitToolCallArgs = emitToolCallArgs;
            return this;
        }

        /**
         * Set whether to enable reasoning/thinking content output.
         *
         * <p>When enabled, ThinkingBlock content will be converted to REASONING_* events
         * according to the AG-UI Reasoning draft specification. Default is false to ensure
         * backward compatibility and privacy compliance.
         *
         * @param enableReasoning true to enable reasoning events
         * @return This builder
         */
        public Builder enableReasoning(boolean enableReasoning) {
            this.enableReasoning = enableReasoning;
            return this;
        }

        /**
         * Set whether to enable acting chunk emissions.
         *
         * <p>When enabled, tools can emit intermediate chunks (e.g., Custom events for progress
         * or real-time logs) during execution. Default is true.
         *
         * @param enableActingChunk true to enable acting chunks
         * @return This builder
         */
        public Builder enableActingChunk(boolean enableActingChunk) {
            this.enableActingChunk = enableActingChunk;
            return this;
        }

        /**
         * Set the run timeout duration.
         *
         * @param runTimeout The timeout duration
         * @return This builder
         */
        public Builder runTimeout(Duration runTimeout) {
            this.runTimeout = runTimeout;
            return this;
        }

        /**
         * Set the default agent ID.
         *
         * @param defaultAgentId The default agent ID
         * @return This builder
         */
        public Builder defaultAgentId(String defaultAgentId) {
            this.defaultAgentId = defaultAgentId;
            return this;
        }

        /**
         * Register a custom block event converter.
         *
         * <p>This allows users to override the default conversion strategies for specific
         * ContentBlock types, enabling deep customization of AG-UI event generation.
         *
         * @param blockClass The class of the ContentBlock to convert
         * @param converter The custom converter strategy
         * @param <T> The ContentBlock type
         * @return This builder
         */
        public <T extends ContentBlock> Builder registerConverter(
                Class<T> blockClass, BlockEventConverter<T> converter) {
            this.customConverters.put(blockClass, converter);
            return this;
        }

        /**
         * Build the configuration.
         *
         * @return The built configuration
         */
        public AguiAdapterConfig build() {
            return new AguiAdapterConfig(this);
        }
    }
}

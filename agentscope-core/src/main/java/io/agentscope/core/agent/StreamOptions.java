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

package io.agentscope.core.agent;

import java.util.Arrays;
import java.util.EnumSet;
import java.util.Set;

/**
 * Configuration options for the {@link Agent#stream} API.
 *
 * <p>Controls which event types to receive and how streaming content is delivered.
 *
 * <p><b>Reasoning filtering (Issue #265):</b>
 * Some streaming backends emit both:
 * <ul>
 *   <li><b>Reasoning chunks</b>: incremental deltas during the reasoning process</li>
 *   <li><b>Reasoning result</b>: the final consolidated reasoning output</li>
 * </ul>
 *
 * <p>Use {@link #isIncludeReasoningChunk()} and {@link #isIncludeReasoningResult()} to filter
 * these reasoning-related emissions when {@link EventType#REASONING} is enabled.
 *
 * <p><b>Example usage:</b>
 *
 * <pre>{@code
 * // Only reasoning events, incremental mode
 * StreamOptions options = StreamOptions.builder()
 *     .eventTypes(EventType.REASONING)
 *     .incremental(true)
 *     .build();
 *
 * // Reasoning events, but hide intermediate deltas and only keep the final reasoning result
 * StreamOptions options = StreamOptions.builder()
 *     .eventTypes(EventType.REASONING)
 *     .includeReasoningChunk(false)
 *     .includeReasoningResult(true)
 *     .incremental(true)
 *     .build();
 *
 * // Multiple specific types
 * StreamOptions options = StreamOptions.builder()
 *     .eventTypes(EventType.REASONING, EventType.TOOL_RESULT)
 *     .incremental(true)
 *     .build();
 * }</pre>
 */
public class StreamOptions {

    private final Set<EventType> eventTypes;
    private final boolean incremental;

    /**
     * Whether to include the incremental delta of the reasoning process during streaming.
     * <p>
     * If false, intermediate reasoning chunk emissions should be filtered out by the stream
     * implementation.
     */
    private final boolean includeReasoningChunk;

    /**
     * Whether to include the final consolidated reasoning output in the response.
     * <p>
     * If false, final reasoning result emissions should be filtered out by the stream
     * implementation.
     */
    private final boolean includeReasoningResult;

    /**
     * Whether to include the incremental chunks from tool execution during streaming.
     * <p>
     * If false, intermediate acting chunk emissions should be filtered out by the stream
     * implementation.
     */
    private final boolean includeActingChunk;

    /**
     * Whether to include the incremental chunks from summary generation during streaming.
     * <p>
     * If false, intermediate summary chunk emissions should be filtered out by the stream
     * implementation.
     */
    private final boolean includeSummaryChunk;

    /**
     * Whether to include the final consolidated summary output in the response.
     * <p>
     * If false, final summary result emissions should be filtered out by the stream
     * implementation.
     */
    private final boolean includeSummaryResult;

    /**
     * Private constructor called by the builder.
     *
     * @param builder The builder containing configuration values
     */
    private StreamOptions(Builder builder) {
        this.eventTypes = builder.eventTypes;
        this.incremental = builder.incremental;
        this.includeReasoningChunk = builder.includeReasoningChunk;
        this.includeReasoningResult = builder.includeReasoningResult;
        this.includeActingChunk = builder.includeActingChunk;
        this.includeSummaryChunk = builder.includeSummaryChunk;
        this.includeSummaryResult = builder.includeSummaryResult;
    }

    /**
     * Default options: All event types, incremental mode, include both reasoning chunk and reasoning result.
     *
     * @return StreamOptions with default configuration
     */
    public static StreamOptions defaults() {
        return builder().build();
    }

    /**
     * Creates a new builder for StreamOptions.
     *
     * @return A new builder instance
     */
    public static Builder builder() {
        return new Builder();
    }

    /**
     * Get the set of event types that should be streamed.
     *
     * <p>If the set contains {@link EventType#ALL}, all event types will be streamed.
     *
     * @return The set of event types to stream
     */
    public Set<EventType> getEventTypes() {
        return eventTypes;
    }

    /**
     * Check if incremental mode is enabled.
     *
     * <p>In incremental mode, only new content is delivered in each emission. In cumulative mode,
     * all accumulated content is delivered.
     *
     * @return true if incremental mode is enabled
     */
    public boolean isIncremental() {
        return incremental;
    }

    /**
     * Whether reasoning "chunk" emissions should be included.
     *
     * <p>Reasoning chunks are the incremental delta of the reasoning process during streaming.</p>
     *
     * @return true if reasoning chunks should be included
     */
    public boolean isIncludeReasoningChunk() {
        return includeReasoningChunk;
    }

    /**
     * Whether the final reasoning result should be included.
     *
     * <p>The reasoning result is the final consolidated reasoning output in the response.</p>
     *
     * @return true if the final reasoning result should be included
     */
    public boolean isIncludeReasoningResult() {
        return includeReasoningResult;
    }

    /**
     * Whether acting (tool execution) chunk emissions should be included.
     *
     * <p>Acting chunks are the incremental outputs from tool execution via ToolEmitter.</p>
     *
     * @return true if acting chunks should be included
     */
    public boolean isIncludeActingChunk() {
        return includeActingChunk;
    }

    /**
     * Whether summary chunk emissions should be included.
     *
     * <p>Summary chunks are the incremental outputs from summary generation when max iterations
     * is reached.</p>
     *
     * @return true if summary chunks should be included
     */
    public boolean isIncludeSummaryChunk() {
        return includeSummaryChunk;
    }

    /**
     * Whether the final summary result should be included.
     *
     * <p>The summary result is the final consolidated summary output when max iterations
     * is reached.</p>
     *
     * @return true if the final summary result should be included
     */
    public boolean isIncludeSummaryResult() {
        return includeSummaryResult;
    }

    /**
     * Check if a specific event type should be streamed.
     *
     * @param type The event type to check
     * @return true if this type should be streamed
     */
    public boolean shouldStream(EventType type) {
        return eventTypes.contains(EventType.ALL) || eventTypes.contains(type);
    }

    /**
     * Convenience method for stream implementations to decide whether to emit a reasoning subtype.
     *
     * <p><b>TODO (Issue #265):</b> Thread these flags through the stream event mapping layer where
     * reasoning events are converted into Flux emissions (e.g., when distinguishing chunk vs result).
     *
     * @param isChunk true if the reasoning emission is an incremental chunk, false if it is the final result
     * @return true if this reasoning emission should be included
     */
    public boolean shouldIncludeReasoningEmission(boolean isChunk) {
        return isChunk ? includeReasoningChunk : includeReasoningResult;
    }

    /**
     * Convenience method for stream implementations to decide whether to emit a summary subtype.
     *
     * @param isChunk true if the summary emission is an incremental chunk, false if it is the final result
     * @return true if this summary emission should be included
     */
    public boolean shouldIncludeSummaryEmission(boolean isChunk) {
        return isChunk ? includeSummaryChunk : includeSummaryResult;
    }

    /** Builder for {@link StreamOptions}. */
    public static class Builder {
        private Set<EventType> eventTypes = EnumSet.of(EventType.ALL);
        private boolean incremental = true;

        // Defaults are "true" to preserve existing behavior.
        private boolean includeReasoningChunk = true;
        private boolean includeReasoningResult = true;
        private boolean includeActingChunk = true;
        private boolean includeSummaryChunk = true;
        private boolean includeSummaryResult = true;

        /**
         * Set which event types to stream.
         *
         * <p>Only events matching these types will be emitted in the Flux. Use {@link
         * EventType#ALL} to receive all types.
         *
         * @param types One or more event types
         * @return this builder
         */
        public Builder eventTypes(EventType... types) {
            this.eventTypes = EnumSet.copyOf(Arrays.asList(types));
            return this;
        }

        /**
         * Set whether to use incremental mode for streaming content.
         *
         * <p>Controls how streaming content is delivered:
         * <ul>
         *   <li>true (incremental): Only new content in each emission</li>
         *   <li>false (cumulative): All accumulated content in each emission</li>
         * </ul>
         *
         * @param incremental true for incremental mode, false for cumulative mode
         * @return this builder
         */
        public Builder incremental(boolean incremental) {
            this.incremental = incremental;
            return this;
        }

        /**
         * Include or exclude incremental reasoning chunk emissions.
         *
         * <p>When {@link EventType#REASONING} is enabled, some providers emit reasoning deltas (chunks)
         * as the model thinks. Set to false to hide these.</p>
         *
         * @param includeReasoningChunk true to include chunk emissions, false to filter them out
         * @return this builder
         */
        public Builder includeReasoningChunk(boolean includeReasoningChunk) {
            this.includeReasoningChunk = includeReasoningChunk;
            return this;
        }

        /**
         * Include or exclude the final consolidated reasoning result emission.
         *
         * <p>When {@link EventType#REASONING} is enabled, some providers emit a final reasoning result.
         * Set to false to hide it.</p>
         *
         * @param includeReasoningResult true to include the final reasoning result, false to filter it out
         * @return this builder
         */
        public Builder includeReasoningResult(boolean includeReasoningResult) {
            this.includeReasoningResult = includeReasoningResult;
            return this;
        }

        /**
         * Include or exclude tool execution chunk emissions.
         *
         * <p>When {@link EventType#TOOL_RESULT} is enabled, tools may emit intermediate chunks via
         * ToolEmitter. Set to false to hide these and only receive the final tool result.</p>
         *
         * @param includeActingChunk true to include chunk emissions, false to filter them out
         * @return this builder
         */
        public Builder includeActingChunk(boolean includeActingChunk) {
            this.includeActingChunk = includeActingChunk;
            return this;
        }

        /**
         * Include or exclude summary chunk emissions.
         *
         * <p>When {@link EventType#SUMMARY} is enabled, summary generation may emit intermediate
         * chunks. Set to false to hide these and only receive the final summary result.</p>
         *
         * @param includeSummaryChunk true to include chunk emissions, false to filter them out
         * @return this builder
         */
        public Builder includeSummaryChunk(boolean includeSummaryChunk) {
            this.includeSummaryChunk = includeSummaryChunk;
            return this;
        }

        /**
         * Include or exclude the final consolidated summary result emission.
         *
         * <p>When {@link EventType#SUMMARY} is enabled, the final summary result is emitted after
         * generation completes. Set to false to hide it.</p>
         *
         * @param includeSummaryResult true to include the final summary result, false to filter it out
         * @return this builder
         */
        public Builder includeSummaryResult(boolean includeSummaryResult) {
            this.includeSummaryResult = includeSummaryResult;
            return this;
        }

        public StreamOptions build() {
            return new StreamOptions(this);
        }
    }
}

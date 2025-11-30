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
package io.agentscope.core.agent;

import java.util.Arrays;
import java.util.EnumSet;
import java.util.Set;

/**
 * Configuration options for the {@link Agent#stream} API.
 *
 * <p>Controls which event types to receive and how streaming content is delivered.
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
 * // All events including final result, cumulative mode
 * StreamOptions options = StreamOptions.builder()
 *     .eventTypes(EventType.ALL)
 *     .includeAgentResult(true)
 *     .incremental(false)
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
     * Private constructor called by the builder.
     *
     * @param builder The builder containing configuration values
     */
    private StreamOptions(Builder builder) {
        this.eventTypes = builder.eventTypes;
        this.incremental = builder.incremental;
    }

    /**
     * Default options: All event types (except AGENT_RESULT), incremental mode.
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
     * <p>If the set contains {@link EventType#ALL}, all event types (except AGENT_RESULT unless
     * explicitly opted-in) will be streamed.
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
     * Check if a specific event type should be streamed.
     *
     * @param type The event type to check
     * @return true if this type should be streamed
     */
    public boolean shouldStream(EventType type) {
        return eventTypes.contains(EventType.ALL) || eventTypes.contains(type);
    }

    /** Builder for {@link StreamOptions}. */
    public static class Builder {
        private Set<EventType> eventTypes = EnumSet.of(EventType.ALL);
        private boolean incremental = true;

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

        public StreamOptions build() {
            return new StreamOptions(this);
        }
    }
}

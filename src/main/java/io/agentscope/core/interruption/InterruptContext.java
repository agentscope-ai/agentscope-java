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
package io.agentscope.core.interruption;

import io.agentscope.core.exception.InterruptSource;
import io.agentscope.core.message.Msg;
import io.agentscope.core.message.ToolUseBlock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Context captured when an interruption occurs.
 *
 * <p>This class stores information about an interruption, including:
 * <ul>
 *   <li>The source of the interruption (USER, TOOL, or SYSTEM)</li>
 *   <li>Optional user message provided when calling interrupt()</li>
 *   <li>Timestamp of when the interruption occurred</li>
 *   <li>Tool calls that were interrupted (if any)</li>
 * </ul>
 *
 * <p>This context is used by agents to generate appropriate recovery messages
 * and handle interruptions gracefully.
 */
public class InterruptContext {

    private final InterruptSource source;
    private final Msg userMessage;
    private final Instant timestamp;
    private final List<ToolUseBlock> pendingToolCalls;

    private InterruptContext(Builder builder) {
        this.source = builder.source;
        this.userMessage = builder.userMessage;
        this.timestamp = builder.timestamp;
        this.pendingToolCalls =
                builder.pendingToolCalls != null
                        ? Collections.unmodifiableList(new ArrayList<>(builder.pendingToolCalls))
                        : Collections.emptyList();
    }

    /**
     * Get the source of this interruption.
     *
     * @return the interruption source
     */
    public InterruptSource getSource() {
        return source;
    }

    /**
     * Get the user message provided when calling interrupt().
     *
     * @return the user message, or null if not provided
     */
    public Msg getUserMessage() {
        return userMessage;
    }

    /**
     * Get the timestamp when this interruption occurred.
     *
     * @return the interruption timestamp
     */
    public Instant getTimestamp() {
        return timestamp;
    }

    /**
     * Get the list of tool calls that were pending when interrupted.
     *
     * @return immutable list of pending tool calls, or empty list if none
     */
    public List<ToolUseBlock> getPendingToolCalls() {
        return pendingToolCalls;
    }

    /**
     * Create a new builder for constructing InterruptContext instances.
     *
     * @return a new builder
     */
    public static Builder builder() {
        return new Builder();
    }

    /**
     * Builder for creating InterruptContext instances.
     */
    public static class Builder {
        private InterruptSource source;
        private Msg userMessage;
        private Instant timestamp = Instant.now();
        private List<ToolUseBlock> pendingToolCalls;

        private Builder() {}

        /**
         * Set the source of the interruption.
         *
         * @param source the interruption source
         * @return this builder
         */
        public Builder source(InterruptSource source) {
            this.source = source;
            return this;
        }

        /**
         * Set the user message provided when calling interrupt().
         *
         * @param userMessage the user message
         * @return this builder
         */
        public Builder userMessage(Msg userMessage) {
            this.userMessage = userMessage;
            return this;
        }

        /**
         * Set the timestamp of the interruption.
         *
         * @param timestamp the interruption timestamp
         * @return this builder
         */
        public Builder timestamp(Instant timestamp) {
            this.timestamp = timestamp;
            return this;
        }

        /**
         * Set the list of tool calls that were pending when interrupted.
         *
         * @param pendingToolCalls list of pending tool calls
         * @return this builder
         */
        public Builder pendingToolCalls(List<ToolUseBlock> pendingToolCalls) {
            this.pendingToolCalls = pendingToolCalls;
            return this;
        }

        /**
         * Build the InterruptContext instance.
         *
         * @return a new InterruptContext
         */
        public InterruptContext build() {
            return new InterruptContext(this);
        }
    }

    @Override
    public String toString() {
        return String.format(
                "InterruptContext{source=%s, timestamp=%s, pendingTools=%d}",
                source, timestamp, pendingToolCalls.size());
    }
}

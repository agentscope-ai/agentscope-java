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
package io.agentscope.core.interruption;

import io.agentscope.core.message.Msg;
import io.agentscope.core.message.ToolUseBlock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

/**
 * Context information about an interruption.
 *
 * <p>Captures metadata about when and why an interruption occurred,
 * including the source, timestamp, user message (if any), and any pending
 * tool calls that were interrupted.
 *
 * <p>Example usage:
 * <pre>{@code
 * InterruptContext context = InterruptContext.builder()
 *     .source(InterruptSource.USER)
 *     .userMessage(userMsg)
 *     .pendingToolCalls(toolCalls)
 *     .build();
 * }</pre>
 */
public class InterruptContext {

    private final InterruptSource source;
    private final Instant timestamp;
    private final Msg userMessage;
    private final List<ToolUseBlock> pendingToolCalls;

    private InterruptContext(
            InterruptSource source,
            Instant timestamp,
            Msg userMessage,
            List<ToolUseBlock> pendingToolCalls) {
        this.source = source;
        this.timestamp = timestamp;
        this.userMessage = userMessage;
        this.pendingToolCalls =
                pendingToolCalls != null ? List.copyOf(pendingToolCalls) : List.of();
    }

    /**
     * Get the source of the interruption.
     *
     * @return The interrupt source
     */
    public InterruptSource getSource() {
        return source;
    }

    /**
     * Get the timestamp when the interruption occurred.
     *
     * @return The interrupt timestamp
     */
    public Instant getTimestamp() {
        return timestamp;
    }

    /**
     * Get the user message that triggered the interruption (if any).
     *
     * @return The user message, or null if not applicable
     */
    public Msg getUserMessage() {
        return userMessage;
    }

    /**
     * Get the list of tool calls that were pending when the interruption occurred.
     *
     * @return Immutable list of pending tool calls
     */
    public List<ToolUseBlock> getPendingToolCalls() {
        return pendingToolCalls;
    }

    /**
     * Create a new builder for InterruptContext.
     *
     * @return A new builder instance
     */
    public static Builder builder() {
        return new Builder();
    }

    /**
     * Builder for InterruptContext.
     */
    public static class Builder {
        private InterruptSource source = InterruptSource.USER;
        private Instant timestamp = Instant.now();
        private Msg userMessage;
        private List<ToolUseBlock> pendingToolCalls = new ArrayList<>();

        private Builder() {}

        /**
         * Set the source of the interruption.
         *
         * @param source The interrupt source
         * @return This builder
         */
        public Builder source(InterruptSource source) {
            this.source = source;
            return this;
        }

        /**
         * Set the timestamp of the interruption.
         *
         * @param timestamp The interrupt timestamp
         * @return This builder
         */
        public Builder timestamp(Instant timestamp) {
            this.timestamp = timestamp;
            return this;
        }

        /**
         * Set the user message that triggered the interruption.
         *
         * @param userMessage The user message
         * @return This builder
         */
        public Builder userMessage(Msg userMessage) {
            this.userMessage = userMessage;
            return this;
        }

        /**
         * Set the pending tool calls at the time of interruption.
         *
         * @param pendingToolCalls List of pending tool calls
         * @return This builder
         */
        public Builder pendingToolCalls(List<ToolUseBlock> pendingToolCalls) {
            this.pendingToolCalls =
                    pendingToolCalls != null
                            ? new ArrayList<>(pendingToolCalls)
                            : new ArrayList<>();
            return this;
        }

        /**
         * Build the InterruptContext.
         *
         * @return A new InterruptContext instance
         */
        public InterruptContext build() {
            return new InterruptContext(source, timestamp, userMessage, pendingToolCalls);
        }
    }

    /**
     * Returns a string representation of the interrupt context.
     *
     * <p>The string includes the interrupt source, timestamp, user message presence,
     * and count of pending tool calls in a compact format.
     *
     * @return A formatted string representation of the interrupt context
     */
    @Override
    public String toString() {
        return String.format(
                "InterruptContext{source=%s, timestamp=%s, userMessage=%s, pendingToolCalls=%d}",
                source,
                timestamp,
                userMessage != null ? "present" : "null",
                pendingToolCalls.size());
    }
}

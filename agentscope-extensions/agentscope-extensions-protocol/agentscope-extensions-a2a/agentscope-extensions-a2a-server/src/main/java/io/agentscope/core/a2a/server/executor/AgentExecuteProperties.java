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

package io.agentscope.core.a2a.server.executor;

/**
 * Properties about agent execution.
 *
 * <p>Some of these properties are used to configure the agent execution in {@link AgentScopeAgentExecutor}.
 */
public class AgentExecuteProperties {

    /**
     * Whether the agent task execution should complete with a message.
     *
     * <p>Only for Non-blocking agent task request. The Blocking agent task request always complete with a message.
     * <p>If is {@code true}, the task will complete with a message which include whole result of the agent task.
     */
    private final boolean completeWithMessage;

    /**
     * Whether the agent task execution should require inner messages such as tool results and hints.
     *
     * <p>If this is {@code true}, the agent execution will expose inner
     * {@link io.agentscope.core.event.AgentEvent} payloads such as tool-result deltas and hint
     * blocks to the A2A client.
     */
    private final boolean requireInnerMessage;

    /**
     * How long a blocking request waits for the agent to finish the task, in seconds.
     *
     * <p>{@code null} keeps the request handler's default (60 seconds).
     */
    private final Integer agentCompletionTimeoutSeconds;

    /**
     * How long a blocking request waits for the produced events to be consumed after the agent
     * finished, in seconds.
     *
     * <p>{@code null} keeps the request handler's default (10 seconds).
     */
    private final Integer consumptionCompletionTimeoutSeconds;

    private AgentExecuteProperties(
            boolean completeWithMessage,
            boolean requireInnerMessage,
            Integer agentCompletionTimeoutSeconds,
            Integer consumptionCompletionTimeoutSeconds) {
        this.completeWithMessage = completeWithMessage;
        this.requireInnerMessage = requireInnerMessage;
        this.agentCompletionTimeoutSeconds = agentCompletionTimeoutSeconds;
        this.consumptionCompletionTimeoutSeconds = consumptionCompletionTimeoutSeconds;
    }

    public boolean isCompleteWithMessage() {
        return completeWithMessage;
    }

    public boolean isRequireInnerMessage() {
        return requireInnerMessage;
    }

    public Integer getAgentCompletionTimeoutSeconds() {
        return agentCompletionTimeoutSeconds;
    }

    public Integer getConsumptionCompletionTimeoutSeconds() {
        return consumptionCompletionTimeoutSeconds;
    }

    public static Builder builder() {
        return new Builder();
    }

    public static class Builder {
        private boolean completeWithMessage;
        private boolean requireInnerMessage;
        private Integer agentCompletionTimeoutSeconds;
        private Integer consumptionCompletionTimeoutSeconds;

        public Builder completeWithMessage(boolean completeWithMessage) {
            this.completeWithMessage = completeWithMessage;
            return this;
        }

        public Builder requireInnerMessage(boolean requireInnerMessage) {
            this.requireInnerMessage = requireInnerMessage;
            return this;
        }

        public Builder agentCompletionTimeoutSeconds(Integer agentCompletionTimeoutSeconds) {
            this.agentCompletionTimeoutSeconds = agentCompletionTimeoutSeconds;
            return this;
        }

        public Builder consumptionCompletionTimeoutSeconds(
                Integer consumptionCompletionTimeoutSeconds) {
            this.consumptionCompletionTimeoutSeconds = consumptionCompletionTimeoutSeconds;
            return this;
        }

        public AgentExecuteProperties build() {
            return new AgentExecuteProperties(
                    completeWithMessage,
                    requireInnerMessage,
                    agentCompletionTimeoutSeconds,
                    consumptionCompletionTimeoutSeconds);
        }
    }
}

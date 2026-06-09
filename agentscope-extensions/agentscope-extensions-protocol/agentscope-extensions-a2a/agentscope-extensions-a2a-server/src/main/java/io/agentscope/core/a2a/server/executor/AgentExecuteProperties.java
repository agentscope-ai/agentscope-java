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
     * Whether the agent task execution should require an inner message such as tool_call result.
     *
     * <p>If is {@code true}, the agent execution will handle {@link io.agentscope.core.agent.Event} with
     * {@link io.agentscope.core.agent.EventType#TOOL_RESULT} and {@link io.agentscope.core.agent.EventType#HINT}.
     */
    private final boolean requireInnerMessage;

    private AgentExecuteProperties(boolean completeWithMessage, boolean requireInnerMessage) {
        this.completeWithMessage = completeWithMessage;
        this.requireInnerMessage = requireInnerMessage;
    }

    public boolean isCompleteWithMessage() {
        return completeWithMessage;
    }

    public boolean isRequireInnerMessage() {
        return requireInnerMessage;
    }

    public static Builder builder() {
        return new Builder();
    }

    public static class Builder {
        private boolean completeWithMessage;
        private boolean requireInnerMessage;

        public Builder completeWithMessage(boolean completeWithMessage) {
            this.completeWithMessage = completeWithMessage;
            return this;
        }

        public Builder requireInnerMessage(boolean requireInnerMessage) {
            this.requireInnerMessage = requireInnerMessage;
            return this;
        }

        public AgentExecuteProperties build() {
            return new AgentExecuteProperties(completeWithMessage, requireInnerMessage);
        }
    }
}

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

package io.agentscope.core.a2a.server.executor.runner;

import io.agentscope.core.ReActAgent;

/**
 * Default Implementation for {@link AgentRunner} by {@link ReActAgent}.
 *
 * <p>Use {@link ReActAgent} directly to handler request from A2A client. In this implementation, {@link ReActAgent}
 * will be created for each request and be cached to intercept when the request is stopped.
 *
 * <p> {@link ReActAgent} should be created from {@link ReActAgent.Builder}, which input and configured by developers.
 */
public class ReActAgentWithBuilderRunner extends BaseReActAgentRunner implements AgentRunner {

    private final ReActAgent.Builder agentBuilder;

    private ReActAgentWithBuilderRunner(ReActAgent.Builder agentBuilder) {
        super();
        this.agentBuilder = agentBuilder;
    }

    @Override
    protected ReActAgent buildReActAgent() {
        return agentBuilder.build();
    }

    /**
     * Build new {@link ReActAgentWithBuilderRunner} instance from {@link ReActAgent.Builder}.
     *
     * @param agentBuilder builder of {@link ReActAgent}
     * @return new {@link ReActAgentWithBuilderRunner} instance
     */
    public static ReActAgentWithBuilderRunner newInstance(ReActAgent.Builder agentBuilder) {
        return new ReActAgentWithBuilderRunner(agentBuilder);
    }
}

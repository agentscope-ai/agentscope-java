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

package io.agentscope.spring.boot.a2a.runner;

import io.agentscope.core.ReActAgent;
import io.agentscope.core.a2a.server.executor.runner.AgentRunner;
import io.agentscope.core.a2a.server.executor.runner.BaseReActAgentRunner;
import io.agentscope.core.memory.Memory;
import io.agentscope.core.model.Model;
import io.agentscope.core.tool.Toolkit;
import io.agentscope.spring.boot.properties.AgentscopeProperties;
import org.springframework.beans.factory.ObjectProvider;

/**
 * Default Implementation for {@link AgentRunner} by {@link ReActAgent}.
 *
 * <p>Use {@link ReActAgent} directly to handler request from A2A client. In this implementation, {@link ReActAgent}
 * will be created for each request and be cached to intercept when the request is stopped.
 *
 * <p> {@link ReActAgent} should be created from {@link org.springframework.beans.factory.ObjectProvider}, which build
 * from
 * {@link io.agentscope.spring.boot.AgentscopeAutoConfiguration#agentscopeReActAgent(Model, Memory, Toolkit,
 * AgentscopeProperties)}.
 */
public class ReActAgentWithStarterRunner extends BaseReActAgentRunner implements AgentRunner {

    private final ObjectProvider<ReActAgent> agentBuilder;

    private ReActAgentWithStarterRunner(ObjectProvider<ReActAgent> agentBuilder) {
        super();
        this.agentBuilder = agentBuilder;
    }

    @Override
    protected ReActAgent buildReActAgent() {
        return agentBuilder.getObject();
    }

    /**
     * Build new {@link ReActAgentWithStarterRunner} instance from
     * {@link org.springframework.beans.factory.ObjectProvider}.
     *
     * @param agentBuilder ObjectProvider of {@link ReActAgent}
     * @return new {@link ReActAgentWithStarterRunner} instance
     */
    public static ReActAgentWithStarterRunner newInstance(ObjectProvider<ReActAgent> agentBuilder) {
        return new ReActAgentWithStarterRunner(agentBuilder);
    }
}

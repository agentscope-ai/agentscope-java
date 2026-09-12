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
package io.agentscope.core.agui.processor;

import static org.junit.jupiter.api.Assertions.assertSame;

import io.agentscope.core.agent.Agent;
import io.agentscope.core.agent.RuntimeContext;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

/** Unit tests for {@link AgentResolver} default methods. */
class AgentResolverTest {

    @Test
    void resolveAgentWithUserIdDefaultsToTwoArgLookup() {
        Agent agent = Mockito.mock(Agent.class);
        AgentResolver resolver =
                new AgentResolver() {
                    @Override
                    public Agent resolveAgent(String agentId, String threadId) {
                        return agent;
                    }

                    @Override
                    public boolean hasMemory(RuntimeContext runtimeContext) {
                        return false;
                    }
                };

        assertSame(agent, resolver.resolveAgent("agent-a", "thread-1", "user-1"));
        assertSame(agent, resolver.resolveAgent("agent-a", "thread-1", null));
    }
}

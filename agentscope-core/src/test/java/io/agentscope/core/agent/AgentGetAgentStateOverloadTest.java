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

import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.withSettings;

import io.agentscope.core.state.AgentState;
import org.junit.jupiter.api.Test;
import org.mockito.Answers;
import org.mockito.quality.Strictness;

/** Tests for {@link Agent} session-scoped {@code getAgentState} defaults. */
class AgentGetAgentStateOverloadTest {

    @Test
    void defaultsReturnNullWhenNotOverridden() {
        Agent agent = mockAgentCallingRealDefaults();

        assertNull(agent.getAgentState());
        assertNull(agent.getAgentState("user-1", "thread-1"));
        assertNull(agent.getAgentState(RuntimeContext.builder().sessionId("thread-1").build()));
    }

    @Test
    void runtimeContextOverloadDelegatesToUserAndSession() {
        AgentState expected = mock(AgentState.class);
        Agent agent = mockAgentCallingRealDefaults();
        doReturn(expected).when(agent).getAgentState("user-1", "thread-1");
        doReturn(expected).when(agent).getAgentState(null, null);

        RuntimeContext context =
                RuntimeContext.builder().userId("user-1").sessionId("thread-1").build();

        assertSame(expected, agent.getAgentState(context));
        assertSame(expected, agent.getAgentState((RuntimeContext) null));
    }

    private static Agent mockAgentCallingRealDefaults() {
        return mock(
                Agent.class,
                withSettings()
                        .defaultAnswer(Answers.CALLS_REAL_METHODS)
                        .strictness(Strictness.LENIENT));
    }
}

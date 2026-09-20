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

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;

import io.agentscope.core.ReActAgent;
import io.agentscope.core.state.AgentState;
import org.junit.jupiter.api.Test;

/** Tests for {@link SessionStateAgent}. */
class SessionStateAgentTest {

    @Test
    void reActAgentImplementsSessionStateAgent() {
        assertTrue(SessionStateAgent.class.isAssignableFrom(ReActAgent.class));
    }

    @Test
    void runtimeContextOverloadDelegatesToUserAndSession() {
        AgentState expected = mock(AgentState.class);
        RecordingAgent agent = new RecordingAgent(expected);
        RuntimeContext context =
                RuntimeContext.builder().userId("user-1").sessionId("thread-1").build();

        assertSame(expected, agent.getAgentState(context));
        assertEquals("user-1", agent.lastUserId);
        assertEquals("thread-1", agent.lastSessionId);

        assertSame(expected, agent.getAgentState(null));
        assertNull(agent.lastUserId);
        assertNull(agent.lastSessionId);
    }

    private static final class RecordingAgent implements SessionStateAgent {

        private final AgentState state;
        private String lastUserId;
        private String lastSessionId;

        private RecordingAgent(AgentState state) {
            this.state = state;
        }

        @Override
        public AgentState getAgentState(String userId, String sessionId) {
            lastUserId = userId;
            lastSessionId = sessionId;
            return state;
        }
    }
}

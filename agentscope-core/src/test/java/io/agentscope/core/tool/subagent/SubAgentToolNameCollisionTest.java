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
package io.agentscope.core.tool.subagent;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import io.agentscope.core.agent.Agent;
import org.junit.jupiter.api.Test;

/** Tests that derived sub-agent tool names stay collision-free across distinct agent names. */
class SubAgentToolNameCollisionTest {

    @Test
    void agentNamesDifferingOnlyByCaseDoNotCollide() {
        assertNotEquals(
                toolNameFor("Researcher"),
                toolNameFor("researcher"),
                "distinct agent names must not sanitize to the same tool name");
    }

    @Test
    void agentNamesDifferingOnlyByWhitespaceDoNotCollide() {
        assertNotEquals(
                toolNameFor("data analyst"),
                toolNameFor("data_analyst"),
                "a space and an underscore are different agent names and must not collide");
    }

    @Test
    void canonicalNameKeepsItsStableToolName() {
        assertEquals(
                "call_researcher",
                toolNameFor("researcher"),
                "an already-canonical name must not gain a hash suffix");
    }

    @Test
    void derivedNamesRemainOpenAiCompatible() {
        for (String agentName : new String[] {"Researcher", "data analyst", "数据 分析"}) {
            String toolName = toolNameFor(agentName);
            assertTrue(
                    toolName.matches("^[a-zA-Z0-9_-]{1,64}$"),
                    () -> "tool name '" + toolName + "' is not a valid function name");
        }
    }

    private static String toolNameFor(String agentName) {
        Agent agent = mock(Agent.class);
        when(agent.getName()).thenReturn(agentName);
        return new SubAgentTool(() -> agent, SubAgentConfig.defaults()).getName();
    }
}

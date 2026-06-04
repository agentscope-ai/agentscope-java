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
package io.agentscope.core.state;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

class PlanModeContextStatePersistenceTest {

    @Test
    void defaultsToInactive() {
        AgentState state = AgentState.builder().build();
        assertFalse(state.getPlanModeContext().isPlanActive());
    }

    @Test
    void planModeSurvivesJsonRoundTrip() {
        AgentState state = AgentState.builder().build();
        state.getPlanModeContext().setPlanActive(true);
        state.getPlanModeContext().setCurrentPlanFile("plans/PLAN.md");

        AgentState restored = AgentState.fromJsonString(state.toJson());

        assertTrue(restored.getPlanModeContext().isPlanActive());
        assertEquals("plans/PLAN.md", restored.getPlanModeContext().getCurrentPlanFile());
        assertEquals(state.getPlanModeContext(), restored.getPlanModeContext());
    }

    @Test
    void legacyJsonWithoutPlanModeDeserializesToDefault() {
        String legacy =
                "{\"session_id\":\"s1\",\"summary\":\"\",\"context\":[],\"reply_id\":\"r1\","
                        + "\"cur_iter\":0,\"shutdown_interrupted\":false}";
        AgentState restored = AgentState.fromJsonString(legacy);
        assertFalse(restored.getPlanModeContext().isPlanActive());
    }
}

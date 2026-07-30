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
package io.agentscope.harness.agent.tool;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.agentscope.core.agent.RuntimeContext;
import io.agentscope.core.agent.test.MockModel;
import io.agentscope.core.state.AgentState;
import io.agentscope.core.state.InMemoryAgentStateStore;
import io.agentscope.harness.agent.HarnessAgent;
import io.agentscope.harness.agent.middleware.SubagentEntry;
import io.agentscope.harness.agent.subagent.DefaultAgentManager;
import io.agentscope.harness.agent.subagent.SubagentDeclaration;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** Verifies that reused subagents cannot retain build-mode privileges under a plan-mode parent. */
class AgentSpawnToolPlanModeTest {

    @TempDir Path workspace;

    private final List<HarnessAgent> createdAgents = new ArrayList<>();

    @AfterEach
    void closeAgents() {
        createdAgents.forEach(HarnessAgent::close);
        createdAgents.clear();
    }

    @Test
    void persistentSpawnReuse_reappliesParentPlanModeBeforeReturningOrInvoking() {
        Fixture fixture = fixture();

        fixture.tool()
                .agentSpawn(
                        fixture.context(),
                        fixture.parentState(),
                        "worker",
                        null,
                        "persistent-worker",
                        null,
                        null)
                .block();
        HarnessAgent reused = createdAgents.get(0);
        String childSessionId =
                "sub-"
                        + AgentSpawnTool.deterministicHash(
                                fixture.context().getSessionId(), "worker", "persistent-worker");
        assertFalse(reused.isPlanModeActive(fixture.context().getUserId(), childSessionId));

        fixture.parentState().getPlanModeContext().setPlanActive(true);
        fixture.tool()
                .agentSpawn(
                        fixture.context(),
                        fixture.parentState(),
                        "worker",
                        null,
                        "persistent-worker",
                        null,
                        null)
                .block();

        assertTrue(
                reused.isPlanModeActive(fixture.context().getUserId(), childSessionId),
                "the existing persistent child must enter plan mode on reuse");
    }

    @Test
    void agentSend_reappliesParentPlanModeBeforeInvokingExistingChild() {
        Fixture fixture = fixture();

        String spawnResult =
                fixture.tool()
                        .agentSpawn(
                                fixture.context(),
                                fixture.parentState(),
                                "worker",
                                null,
                                "persistent-worker",
                                null,
                                null)
                        .block();
        HarnessAgent reused = createdAgents.get(0);
        String key = firstLineValue(spawnResult, "agent_key: ");
        String childSessionId = firstLineValue(spawnResult, "session_id: ");
        assertFalse(reused.isPlanModeActive(fixture.context().getUserId(), childSessionId));

        fixture.parentState().getPlanModeContext().setPlanActive(true);
        fixture.tool()
                .agentSend(
                        fixture.context(),
                        fixture.parentState(),
                        key,
                        null,
                        "Continue with read-only research.",
                        null)
                .block();

        assertTrue(
                reused.isPlanModeActive(fixture.context().getUserId(), childSessionId),
                "agent_send must synchronize plan mode before invoking an existing child");
    }

    private Fixture fixture() {
        SubagentDeclaration declaration =
                SubagentDeclaration.builder()
                        .name("worker")
                        .description("Persistent worker")
                        .inlineAgentsBody("Work on delegated tasks.")
                        .persistSession(true)
                        .build();
        SubagentEntry entry =
                new SubagentEntry(
                        "worker",
                        "Persistent worker",
                        ignored -> {
                            HarnessAgent child =
                                    HarnessAgent.builder()
                                            .name("worker")
                                            .model(new MockModel("done"))
                                            .workspace(workspace)
                                            .stateStore(new InMemoryAgentStateStore())
                                            .enablePlanMode()
                                            .build();
                            createdAgents.add(child);
                            return child;
                        },
                        declaration);
        DefaultAgentManager manager = new DefaultAgentManager(List.of(entry), null);
        AgentSpawnTool tool = new AgentSpawnTool(manager, null, 0);
        RuntimeContext context =
                RuntimeContext.builder().userId("user").sessionId("parent-session").build();
        AgentState parentState =
                AgentState.builder().userId("user").sessionId("parent-session").build();
        return new Fixture(tool, context, parentState);
    }

    private static String firstLineValue(String result, String prefix) {
        return result.lines()
                .filter(line -> line.startsWith(prefix))
                .map(line -> line.substring(prefix.length()))
                .findFirst()
                .orElseThrow();
    }

    private record Fixture(AgentSpawnTool tool, RuntimeContext context, AgentState parentState) {}
}

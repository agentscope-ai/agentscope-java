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
package io.agentscope.harness.agent.workspace.plan;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.agentscope.core.agent.RuntimeContext;
import io.agentscope.core.state.AgentState;
import io.agentscope.harness.agent.filesystem.AbstractFilesystem;
import io.agentscope.harness.agent.filesystem.spec.LocalFilesystemSpec;
import io.agentscope.harness.agent.workspace.WorkspaceManager;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class PlanModeManagerTest {

    private final List<WorkspaceManager> openManagers = new ArrayList<>();

    @AfterEach
    void closeOpenManagers() {
        // Release SQLite handles so @TempDir can delete the workspace on Windows.
        for (WorkspaceManager wm : openManagers) {
            wm.close();
        }
        openManagers.clear();
    }

    private WorkspaceManager wsm(Path project, Path workspace) {
        AbstractFilesystem fs =
                new LocalFilesystemSpec().project(project).toFilesystem(workspace, null);
        WorkspaceManager wm = new WorkspaceManager(workspace, fs);
        openManagers.add(wm);
        return wm;
    }

    @Test
    void enterAndExitTogglePersistedFlag(@TempDir Path project, @TempDir Path workspace) {
        PlanModeManager manager = new PlanModeManager(wsm(project, workspace), null);
        AgentState state = AgentState.builder().build();

        assertFalse(manager.isPlanActive(state));
        String path = manager.enter(state);
        assertTrue(manager.isPlanActive(state));
        assertEquals("plans/PLAN.md", path);
        assertEquals("plans/PLAN.md", state.getPlanModeContext().getCurrentPlanFile());

        manager.exit(state);
        assertFalse(manager.isPlanActive(state));
        // Plan file reference is retained after exit.
        assertEquals("plans/PLAN.md", state.getPlanModeContext().getCurrentPlanFile());
    }

    @Test
    void customPlanDirNormalised(@TempDir Path project, @TempDir Path workspace) {
        PlanModeManager manager = new PlanModeManager(wsm(project, workspace), "/docs/plans/");
        AgentState state = AgentState.builder().build();
        assertEquals("docs/plans/PLAN.md", manager.enter(state));
    }

    @Test
    void writePlanRecordsPathAndDoesNotThrow(@TempDir Path project, @TempDir Path workspace) {
        PlanModeManager manager = new PlanModeManager(wsm(project, workspace), null);
        AgentState state = AgentState.builder().build();
        manager.enter(state);

        String path = manager.writePlan(RuntimeContext.empty(), state, "# Plan\n- step 1\n");
        assertEquals("plans/PLAN.md", path);
        assertEquals("plans/PLAN.md", state.getPlanModeContext().getCurrentPlanFile());
    }
}

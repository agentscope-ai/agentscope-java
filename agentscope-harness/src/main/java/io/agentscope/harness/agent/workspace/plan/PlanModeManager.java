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

import io.agentscope.core.agent.RuntimeContext;
import io.agentscope.core.state.AgentState;
import io.agentscope.core.state.PlanModeContextState;
import io.agentscope.harness.agent.workspace.WorkspaceManager;
import java.util.Objects;

/**
 * Coordinates plan mode for a single agent.
 *
 * <p>State (whether plan mode is active and which markdown file holds the current plan) lives in
 * {@link PlanModeContextState} inside the agent's {@link AgentState}, so it is persisted with the
 * session and survives restarts / cross-node hand-offs — this is the mechanism that makes dynamic
 * (runtime) plan-mode switching work in a distributed setting, where an in-process boolean would be
 * lost.
 *
 * <p>The plan markdown file is written exclusively through {@link WorkspaceManager}, never via
 * {@code java.nio.file.Files}, so it lands on whatever backend (local, sandbox, remote) the agent's
 * filesystem is configured with.
 */
public final class PlanModeManager {

    /** Default workspace-relative directory for plan files. */
    public static final String DEFAULT_PLAN_DIR = "plans";

    private final WorkspaceManager workspaceManager;
    private final String planDir;

    public PlanModeManager(WorkspaceManager workspaceManager, String planDir) {
        this.workspaceManager = Objects.requireNonNull(workspaceManager, "workspaceManager");
        String dir = planDir == null || planDir.isBlank() ? DEFAULT_PLAN_DIR : planDir.trim();
        // Normalise: strip leading/trailing slashes.
        while (dir.startsWith("/")) {
            dir = dir.substring(1);
        }
        while (dir.endsWith("/")) {
            dir = dir.substring(0, dir.length() - 1);
        }
        this.planDir = dir.isEmpty() ? DEFAULT_PLAN_DIR : dir;
    }

    public boolean isPlanActive(AgentState state) {
        return state != null && state.getPlanModeContext().isPlanActive();
    }

    /**
     * Enters plan mode. Idempotent. Ensures a plan file path is recorded (default if none set).
     *
     * @return the workspace-relative plan file path
     */
    public String enter(AgentState state) {
        Objects.requireNonNull(state, "state");
        PlanModeContextState ctx = state.getPlanModeContext();
        ctx.setPlanActive(true);
        if (ctx.getCurrentPlanFile() == null || ctx.getCurrentPlanFile().isBlank()) {
            ctx.setCurrentPlanFile(defaultPlanFile());
        }
        return ctx.getCurrentPlanFile();
    }

    /** Exits plan mode (back to BUILD). Idempotent. Keeps {@code currentPlanFile} for reference. */
    public void exit(AgentState state) {
        Objects.requireNonNull(state, "state");
        state.getPlanModeContext().setPlanActive(false);
    }

    /** Workspace-relative path of the plan file currently associated with this agent. */
    public String planFilePath(AgentState state) {
        if (state != null) {
            String current = state.getPlanModeContext().getCurrentPlanFile();
            if (current != null && !current.isBlank()) {
                return current;
            }
        }
        return defaultPlanFile();
    }

    /**
     * Writes (creates or overwrites) the plan markdown file through the workspace filesystem and
     * records its path in state.
     *
     * @return the workspace-relative path written
     */
    public String writePlan(RuntimeContext rc, AgentState state, String content) {
        Objects.requireNonNull(state, "state");
        String path = planFilePath(state);
        RuntimeContext effective = rc != null ? rc : RuntimeContext.empty();
        workspaceManager.writeUtf8WorkspaceRelative(
                effective, path, content == null ? "" : content);
        state.getPlanModeContext().setCurrentPlanFile(path);
        return path;
    }

    private String defaultPlanFile() {
        return planDir + "/PLAN.md";
    }
}

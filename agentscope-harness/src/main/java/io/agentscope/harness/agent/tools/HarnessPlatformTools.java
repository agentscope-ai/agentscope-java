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
package io.agentscope.harness.agent.tools;

import java.util.Set;

/**
 * Harness platform / orchestration tool names that {@link ToolFilter} must not strip via an {@code
 * allow} list.
 *
 * <p>Product agent toolsets (Managed Agents UI / {@code tools.json} allow) may narrow the
 * <em>catalogued</em> surface — filesystem, shell, web, memory, session helpers, etc. They must not
 * disable the coordination runtime itself: subagents, teams, background tasks, plan mode, and skills
 * admin. Explicit {@code deny} still removes a name (operator opt-out).
 */
public final class HarnessPlatformTools {

    /**
     * Names registered by {@code HarnessAgent} middleware / builder paths that are not part of the
     * product "agent toolset" catalog.
     */
    public static final Set<String> NAMES =
            Set.of(
                    // AgentTeams (unified + per-action aliases models often invent)
                    "team",
                    "listTasks",
                    "listClaimableTasks",
                    "createTask",
                    "assignTask",
                    "claimTask",
                    "unclaimTask",
                    "completeTask",
                    "failTask",
                    "sendMessage",
                    "broadcastMessage",
                    "listMessages",
                    "listMembers",
                    "spawnMember",
                    "shutdownMember",
                    "submitPlan",
                    "approvePlan",
                    "rejectPlan",
                    "completeTeam",
                    // Subagents (session-mode aliases included)
                    "agent_spawn",
                    "agent_send",
                    "agent_list",
                    "agent_generate",
                    "sessions_spawn",
                    "sessions_send",
                    "sessions_list",
                    "sessions_history",
                    "sessions_pending_completions",
                    // Background task lifecycle
                    "task_output",
                    "task_cancel",
                    "task_list",
                    "wait_async_results",
                    // Plan mode
                    "plan_enter",
                    "plan_write",
                    "plan_exit",
                    // Skills self-learning
                    "skill_manage",
                    "propose_skill");

    private HarnessPlatformTools() {}

    /** {@code true} when {@code toolName} is a harness platform tool. */
    public static boolean isPlatformTool(String toolName) {
        return toolName != null && NAMES.contains(toolName);
    }
}

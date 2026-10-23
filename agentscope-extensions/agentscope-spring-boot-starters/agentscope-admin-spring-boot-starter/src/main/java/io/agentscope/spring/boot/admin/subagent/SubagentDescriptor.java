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
package io.agentscope.spring.boot.admin.subagent;

import io.agentscope.harness.agent.middleware.SubagentEntry;
import io.agentscope.harness.agent.subagent.SubagentDeclaration;
import io.agentscope.harness.agent.subagent.WorkspaceMode;
import java.util.List;

/**
 * Wire view of a registered subagent — flattens whichever projection (a {@link SubagentEntry}
 * from a built agent, or a {@link SubagentDeclaration} loaded from a yaml/markdown spec) the
 * inventory adapter happens to hold.
 *
 * @param name unique subagent name / agent-id
 * @param description used by the orchestrator agent to decide when to delegate
 * @param source where this entry came from: {@code spring-bean} / {@code declaration}
 * @param workspaceMode {@link WorkspaceMode#ISOLATED} or {@link WorkspaceMode#SHARED}; may be null
 * @param workspacePath optional path to the on-disk subagent definition
 * @param model optional override model id
 * @param maxIters per-call iteration cap, 0 if unspecified
 * @param toolAllowlist when non-empty, only inherited parent tools whose names are listed are
 *     visible to the subagent
 */
public record SubagentDescriptor(
        String name,
        String description,
        String source,
        String workspaceMode,
        String workspacePath,
        String model,
        int maxIters,
        List<String> toolAllowlist) {

    public static SubagentDescriptor of(SubagentEntry e) {
        return new SubagentDescriptor(
                e.name(), e.description(), "spring-bean", null, null, null, 0, List.of());
    }

    public static SubagentDescriptor of(SubagentDeclaration d) {
        WorkspaceMode mode = d.getWorkspaceMode();
        return new SubagentDescriptor(
                d.getName(),
                d.getDescription(),
                "declaration",
                mode == null ? null : mode.name(),
                d.getWorkspacePath() == null ? null : d.getWorkspacePath().toString(),
                d.getModel(),
                d.getMaxIters(),
                safeAllowlist(d));
    }

    private static List<String> safeAllowlist(SubagentDeclaration d) {
        try {
            // Different versions of core may name this field differently; reflect defensively.
            var m = d.getClass().getMethod("getToolAllowlist");
            Object v = m.invoke(d);
            if (v instanceof List<?> raw) {
                List<String> out = new java.util.ArrayList<>(raw.size());
                for (Object o : raw) out.add(String.valueOf(o));
                return List.copyOf(out);
            }
        } catch (ReflectiveOperationException ignored) {
            // method missing — older core; fall through
        }
        return List.of();
    }
}

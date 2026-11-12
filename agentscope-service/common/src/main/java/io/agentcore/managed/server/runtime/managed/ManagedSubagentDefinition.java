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
package io.agentcore.managed.server.runtime.managed;

import io.agentscope.core.agent.RuntimeContext;
import io.agentscope.core.model.GenerateOptions;
import io.agentscope.core.permission.PermissionContextState;
import io.agentscope.core.skill.SkillFilter;
import io.agentscope.core.state.PlanModeContextState;
import io.agentscope.core.util.JsonUtils;
import io.agentscope.harness.agent.HarnessAgent;
import java.util.List;

/** Serializable effective child configuration. Live Models, tool implementations and credentials stay out. */
public record ManagedSubagentDefinition(
        int version,
        String parentScopeKey,
        String name,
        String system,
        String modelReference,
        int maxIters,
        String generateOptions,
        String permissions,
        String planMode,
        List<String> tools,
        List<String> skills) {
    public ManagedSubagentDefinition {
        if (version != 1 || maxIters < 1)
            throw new IllegalArgumentException("Unsupported child definition");
        ManagedScope.requireText(parentScopeKey, "child definition parent");
        ManagedScope.requireText(name, "child name");
        tools = List.copyOf(tools);
        skills = List.copyOf(skills);
    }

    public void configure(HarnessAgent.Builder builder) {
        builder.name(name).sysPrompt(system).maxIters(maxIters);
        if (modelReference != null) builder.model(modelReference);
        var codec = JsonUtils.getJsonCodec();
        var options = codec.fromJson(generateOptions, GenerateOptions.class);
        if (options != null) builder.generateOptions(options);
        builder.permissionContext(codec.fromJson(permissions, PermissionContextState.class));
        builder.skillFilter(
                skills.isEmpty()
                        ? SkillFilter.none()
                        : SkillFilter.only(skills.toArray(String[]::new)));
        if (!tools.contains("agent_spawn")) builder.disableSubagents();
        if (codec.fromJson(planMode, PlanModeContextState.class).isPlanActive())
            builder.enablePlanMode();
    }

    public void restrict(HarnessAgent agent) {
        for (String tool : List.copyOf(agent.getToolkit().getToolNames()))
            if (!tools.contains(tool)) agent.getToolkit().removeTool(tool);
        if (!agent.getToolkit().getToolNames().containsAll(tools))
            throw new ManagedStateException(
                    "subagent_tool_definition_missing",
                    "Frozen child tools cannot be materialized");
    }

    /** Existing checkpoints always win; this seed is only for the child's initial native run. */
    public void initialize(HarnessAgent agent, RuntimeContext context) {
        var state = agent.getDelegate().getAgentState(context);
        var plan = JsonUtils.getJsonCodec().fromJson(planMode, PlanModeContextState.class);
        state.getPlanModeContext().setPlanActive(plan.isPlanActive());
        state.getPlanModeContext().setCurrentPlanFile(plan.getCurrentPlanFile());
        context.setAgentState(state);
    }
}

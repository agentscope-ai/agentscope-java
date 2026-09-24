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
package io.agentscope.core.tool.builtin;

import io.agentscope.core.message.ToolResultBlock;
import io.agentscope.core.state.AgentState;
import io.agentscope.core.state.TaskRequirement;
import io.agentscope.core.tool.Tool;
import io.agentscope.core.tool.ToolParam;

/** Opt-in candidate proposals, independent of the todo capability. */
public final class RequirementTools {
    /** Proposals cannot confirm authority or certify completion. */
    @Tool(
            name = "task_requirement_propose",
            stateInjected = true,
            concurrencySafe = false,
            description =
                    "Propose a candidate CONSTRAINT or ACCEPTANCE_CRITERION for this session."
                        + " Include a source reference (such as a user message ID or PLAN.md"
                        + " section). References are unverified claims. This tool cannot confirm"
                        + " requirements, change confirmed text, or mark criteria satisfied. Do not"
                        + " treat candidates as binding.")
    public ToolResultBlock proposeRequirement(
            @ToolParam(name = "kind", description = "CONSTRAINT or ACCEPTANCE_CRITERION")
                    TaskRequirement.Kind kind,
            @ToolParam(
                            name = "text",
                            description = "Exact proposed requirement, at most 2000 characters")
                    String text,
            @ToolParam(name = "source_ref", description = "Where this proposal came from")
                    String sourceRef,
            AgentState state) {
        if (state == null) return ToolResultBlock.error("Agent state unavailable");
        if (kind == null) return ToolResultBlock.error("Requirement kind is required");
        try {
            var requirement = state.getTasksContext().propose(kind, text, sourceRef);
            return ToolResultBlock.text(
                    "Requirement "
                            + requirement.id()
                            + " recorded; status="
                            + requirement.status()
                            + ". Use current TASK_STATE for requirements. "
                            + "This receipt does not confirm authority or verification.");
        } catch (IllegalArgumentException | IllegalStateException error) {
            return ToolResultBlock.error(error.getMessage());
        }
    }
}

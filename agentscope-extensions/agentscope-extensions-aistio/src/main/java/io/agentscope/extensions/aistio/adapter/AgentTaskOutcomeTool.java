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

/*
 * Copyright 2024-2026 the original author or authors.
 * Licensed under the Apache License, Version 2.0.
 */
package io.agentscope.extensions.aistio.adapter;

import io.agentscope.core.agent.RuntimeContext;
import io.agentscope.core.message.ToolResultBlock;
import io.agentscope.core.tool.Tool;
import io.agentscope.core.tool.ToolParam;
import java.util.List;

/** Captures intent. Only the adapter is allowed to commit the physical AgentTask lifecycle. */
public final class AgentTaskOutcomeTool {
    @Tool(
            name = "task.submit_result",
            description =
                    "Submit the actual outcome of this AgentTask, then end your turn. succeeded"
                        + " requires the full deliverable in result, not a plan or promise. waiting"
                        + " requires real background task IDs (or control-plane AgentTask IDs for a"
                        + " Team leader). If required evidence or tools are missing, submit blocked"
                        + " with the missing capability and partial result. Submitting this tool"
                        + " does not by itself mark the task successful.")
    public ToolResultBlock submit(
            RuntimeContext context,
            @ToolParam(name = "outcome", description = "succeeded, waiting, blocked or failed")
                    String outcome,
            @ToolParam(
                            name = "result",
                            description = "Full deliverable or partial work",
                            required = false)
                    String result,
            @ToolParam(
                            name = "reason",
                            description = "Reason for completion, waiting or inability",
                            required = false)
                    String reason,
            @ToolParam(
                            name = "pending_task_ids",
                            description =
                                    "Background task IDs to collect, or delegated AgentTask IDs for"
                                            + " a Team leader",
                            required = false)
                    List<String> pendingTaskIds) {
        AgentTaskOutcome.State state =
                context == null ? null : context.get(AgentTaskOutcome.State.class);
        if (state == null)
            return ToolResultBlock.error("This tool is only available inside an AgentTask");
        try {
            state.submit(new AgentTaskOutcome(outcome, result, reason, pendingTaskIds));
            return ToolResultBlock.text(
                    "Outcome submitted for adapter validation. End this turn now.");
        } catch (IllegalArgumentException e) {
            return ToolResultBlock.error(e.getMessage());
        }
    }
}

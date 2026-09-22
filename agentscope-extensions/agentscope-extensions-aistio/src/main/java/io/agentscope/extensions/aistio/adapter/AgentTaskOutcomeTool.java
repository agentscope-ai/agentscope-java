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
import io.agentscope.core.tool.ToolBase;
import io.agentscope.core.tool.ToolCallParam;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import reactor.core.publisher.Mono;

/**
 * Captures intent. Only the adapter is allowed to commit the physical AgentTask lifecycle.
 *
 * <p>Model-facing name is {@code task_submit_result} (OpenAI-safe form of the legacy dotted
 * {@code task.submit_result}). {@link io.agentscope.core.permission.PermissionEngine} accepts both
 * spellings during rule lookup so persisted allow/deny/ask keys keep matching after the rename.
 */
public final class AgentTaskOutcomeTool extends ToolBase {

    /** OpenAI-safe model-facing name registered on the toolkit. */
    public static final String MODEL_NAME = "task_submit_result";

    /** Legacy dotted spelling previously used as {@code @Tool(name=...)} / permission keys. */
    public static final String LEGACY_DOTTED_NAME = "task.submit_result";

    private static final String DESCRIPTION =
            "Submit the actual outcome of this AgentTask, then end your turn. succeeded"
                    + " requires the full deliverable in result, not a plan or promise. waiting"
                    + " requires real background task IDs (or control-plane AgentTask IDs for a"
                    + " Team leader). If required evidence or tools are missing, submit blocked"
                    + " with the missing capability and partial result. Submitting this tool"
                    + " does not by itself mark the task successful.";

    public AgentTaskOutcomeTool() {
        super(
                ToolBase.builder()
                        .name(MODEL_NAME)
                        .description(DESCRIPTION)
                        .inputSchema(parameterSchema())
                        .readOnly(false)
                        .concurrencySafe(false));
    }

    private static Map<String, Object> parameterSchema() {
        Map<String, Object> properties = new HashMap<>();
        properties.put(
                "outcome",
                Map.of("type", "string", "description", "succeeded, waiting, blocked or failed"));
        properties.put(
                "result",
                Map.of("type", "string", "description", "Full deliverable or partial work"));
        properties.put(
                "reason",
                Map.of(
                        "type",
                        "string",
                        "description",
                        "Reason for completion, waiting or inability"));
        properties.put(
                "pending_task_ids",
                Map.of(
                        "type",
                        "array",
                        "description",
                        "Background task IDs to collect, or delegated AgentTask IDs for"
                                + " a Team leader",
                        "items",
                        Map.of("type", "string")));
        Map<String, Object> schema = new HashMap<>();
        schema.put("type", "object");
        schema.put("properties", properties);
        schema.put("required", List.of("outcome"));
        return schema;
    }

    @Override
    public Mono<ToolResultBlock> callAsync(ToolCallParam param) {
        RuntimeContext context = param.getRuntimeContext();
        AgentTaskOutcome.State state =
                context == null ? null : context.get(AgentTaskOutcome.State.class);
        if (state == null) {
            return Mono.just(
                    ToolResultBlock.error("This tool is only available inside an AgentTask"));
        }
        Map<String, Object> input = param.getInput() == null ? Map.of() : param.getInput();
        String outcome = stringArg(input.get("outcome"));
        String result = stringArg(input.get("result"));
        String reason = stringArg(input.get("reason"));
        List<String> pendingTaskIds = stringListArg(input.get("pending_task_ids"));
        try {
            state.submit(new AgentTaskOutcome(outcome, result, reason, pendingTaskIds));
            return Mono.just(
                    ToolResultBlock.text(
                            "Outcome submitted for adapter validation. End this turn now."));
        } catch (IllegalArgumentException e) {
            return Mono.just(ToolResultBlock.error(e.getMessage()));
        }
    }

    private static String stringArg(Object value) {
        return value == null ? null : String.valueOf(value);
    }

    private static List<String> stringListArg(Object value) {
        if (value == null) {
            return null;
        }
        if (value instanceof List<?> list) {
            return list.stream().map(v -> v == null ? null : String.valueOf(v)).toList();
        }
        return List.of(String.valueOf(value));
    }
}

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

import io.agentscope.core.message.ToolResultBlock;
import io.agentscope.core.permission.PermissionContextState;
import io.agentscope.core.permission.PermissionDecision;
import io.agentscope.core.tool.ToolBase;
import io.agentscope.core.tool.ToolCallParam;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import reactor.core.publisher.Mono;

/**
 * Built-in tool that lets the model ask the user for input (HITL "ask" direction).
 *
 * <p>{@code checkPermissions()} always returns {@link PermissionDecision#askUser(String)}, so the
 * agent pauses with {@code GenerateReason.ASK_USER_ASKING} and emits {@code RequireUserAskEvent}
 * every time the model calls {@code ask_user} — in every {@code PermissionMode}, including
 * {@code BYPASS}. The tool itself is never executed; the host application renders the questions
 * and resumes with {@code List<AskUserResult>} under {@code Msg.METADATA_ASK_USER_RESULTS}.
 *
 * <p>Register it explicitly on a {@code Toolkit} or enable it on the harness builder:
 *
 * <pre>{@code
 * HarnessAgent agent = HarnessAgent.builder()
 *         .model(model)
 *         .enableAskUser()
 *         .build();
 * }</pre>
 */
public class AskUserTool extends ToolBase {

    public static final String TOOL_NAME = "ask_user";

    public AskUserTool() {
        super(
                ToolBase.builder()
                        .name(TOOL_NAME)
                        .description(
                                "Ask the user one or more questions and wait for their answers. "
                                        + "Each question may offer options and always additionally "
                                        + "accepts free-text input; the user may also skip a "
                                        + "question. Use this when you genuinely need information "
                                        + "only the user can provide (preferences, decisions, "
                                        + "credentials); do not ask about anything you can infer "
                                        + "from context or tools, and do not re-ask what was "
                                        + "already answered.")
                        .inputSchema(buildInputSchema())
                        .readOnly(true)
                        .concurrencySafe(true));
    }

    @Override
    public Mono<PermissionDecision> checkPermissions(
            Map<String, Object> toolInput, PermissionContextState context) {
        // Always interrupt: the model asks the user for input, the tool is never executed.
        return Mono.just(
                PermissionDecision.askUser(
                        "ask_user pauses the agent to collect input from the user"));
    }

    @Override
    public Mono<ToolResultBlock> callAsync(ToolCallParam param) {
        // Never invoked in the normal flow — checkPermissions interrupts the run first. This
        // fallback only protects against a misconfigured host that bypasses the interrupt.
        return Mono.just(
                ToolResultBlock.text(
                        "This tool is handled interactively by the host application "
                                + "and is not executed by the agent. Resume the run with answers "
                                + "under Msg.METADATA_ASK_USER_RESULTS."));
    }

    private static Map<String, Object> buildInputSchema() {
        Map<String, Object> option = new LinkedHashMap<>();
        option.put("type", "object");
        option.put(
                "description", "An answer option; users may also type their own free-text answer.");
        option.put("required", List.of("label"));
        Map<String, Object> optionProps = new LinkedHashMap<>();
        optionProps.put(
                "label", Map.of("type", "string", "description", "Option text shown to the user"));
        optionProps.put(
                "description",
                Map.of(
                        "type",
                        "string",
                        "description",
                        "Optional explanatory text for the option"));
        option.put("properties", optionProps);
        option.put("additionalProperties", false);

        Map<String, Object> question = new LinkedHashMap<>();
        question.put("type", "object");
        question.put("description", "A single question for the user.");
        question.put("required", List.of("question"));
        Map<String, Object> questionProps = new LinkedHashMap<>();
        questionProps.put(
                "id",
                Map.of(
                        "type",
                        "string",
                        "description",
                        "Stable question identifier (e.g. \"q_1\"), echoed in the answer "
                                + "so the model can correlate answers to questions"));
        questionProps.put(
                "question",
                Map.of("type", "string", "description", "The question text, short and precise"));
        questionProps.put(
                "header",
                Map.of(
                        "type", "string",
                        "description", "Optional card title / group name for the question"));
        questionProps.put(
                "type",
                Map.of(
                        "type",
                        "string",
                        "enum",
                        List.of("single", "multiple", "free", "secret"),
                        "default",
                        "single",
                        "description",
                        "single=choose one option; multiple=choose any number of options; "
                                + "free=free-text answer; secret=masked input (API keys, "
                                + "passwords) — the host must not persist or log the raw "
                                + "value"));
        questionProps.put(
                "options",
                Map.of(
                        "type",
                        "array",
                        "items",
                        option,
                        "description",
                        "Recommended answer options for single/multiple (3-5, specific and "
                                + "mutually exclusive). Optional for free/secret; the user "
                                + "always retains a free-text input regardless."));
        questionProps.put(
                "required",
                Map.of(
                        "type",
                        "boolean",
                        "default",
                        true,
                        "description",
                        "Whether the question must be answered; false lets the user skip "
                                + "it without answering"));
        question.put("properties", questionProps);
        question.put("additionalProperties", false);

        Map<String, Object> schema = new LinkedHashMap<>();
        schema.put("type", "object");
        schema.put("required", List.of("questions"));
        schema.put("additionalProperties", false);
        schema.put(
                "properties",
                Map.of(
                        "questions",
                        Map.of(
                                "type",
                                "array",
                                "minItems",
                                1,
                                "maxItems",
                                5,
                                "description",
                                "One or more questions for the user. Ask at most 1-2 per "
                                        + "call unless more are truly required.",
                                "items",
                                question)));
        return schema;
    }
}

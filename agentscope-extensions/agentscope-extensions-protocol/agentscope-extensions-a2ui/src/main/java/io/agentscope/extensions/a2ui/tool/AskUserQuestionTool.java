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
package io.agentscope.extensions.a2ui.tool;

import io.agentscope.core.message.ToolResultBlock;
import io.agentscope.core.tool.AgentTool;
import io.agentscope.core.tool.ToolCallParam;
import io.agentscope.core.tool.ToolSuspendException;
import io.agentscope.core.util.JsonUtils;
import io.agentscope.extensions.a2ui.A2uiRenderer;
import io.agentscope.extensions.a2ui.envelope.A2uiConstants;
import io.agentscope.extensions.a2ui.envelope.A2uiValidationException;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import reactor.core.publisher.Mono;

/**
 * HITL entry point (spec §9, v1.10 dual-mode): compiles structured questions and suspends the
 * tool call so the payload surfaces as a {@code tool_call} interrupt message and the frontend's
 * resume payload (answer JSON) becomes this call's tool result.
 *
 * <p>{@code enableA2ui} selects the suspend payload: {@code true} renders the questions as an
 * A2UI form envelope through {@link A2uiRenderer} (registered by {@code A2uiMiddleware});
 * {@code false} suspends with the canonical {@code {"questions":[…]}} JSON so a plain AG-UI
 * frontend can render a native text question card (registered by {@link
 * io.agentscope.extensions.a2ui.ClarificationMiddleware}, no renderer required).
 */
public class AskUserQuestionTool implements AgentTool {

    private static final Set<String> QUESTION_TYPES =
            Set.of("text", "select", "multi_select", "confirm");

    private final A2uiRenderer renderer;
    private final boolean enableA2ui;

    /**
     * @param renderer A2UI renderer; required when {@code enableA2ui} is {@code true}, ignored
     *     (may be {@code null}) when {@code false}.
     * @param enableA2ui suspend with an A2UI form envelope ({@code true}) or with the plain
     *     canonical questions JSON ({@code false}).
     */
    public AskUserQuestionTool(A2uiRenderer renderer, boolean enableA2ui) {
        if (enableA2ui && renderer == null) {
            throw new IllegalArgumentException("renderer is required when enableA2ui is true");
        }
        this.renderer = renderer;
        this.enableA2ui = enableA2ui;
    }

    @Override
    public String getName() {
        return A2uiConstants.TOOL_ASK_USER_QUESTION;
    }

    @Override
    public String getDescription() {
        return "Ask the user one or more structured questions and wait for the answer. Use this"
                + " instead of plain-text follow-up questions whenever input collection benefits"
                + " from widgets. The tool suspends until the user submits; the returned value is"
                + " a JSON object mapping question id to answer."
                + (enableA2ui
                        ? " Questions are rendered as an A2UI form."
                        : " Questions are rendered as a plain-text question card.");
    }

    @Override
    public Map<String, Object> getParameters() {
        return Map.of(
                "type",
                "object",
                "properties",
                Map.of(
                        "questions",
                        Map.of(
                                "type",
                                "array",
                                "description",
                                "Questions to ask, rendered as form fields in order.",
                                "items",
                                Map.of(
                                        "type",
                                        "object",
                                        "properties",
                                        Map.of(
                                                "id",
                                                Map.of(
                                                        "type",
                                                        "string",
                                                        "description",
                                                        "Stable answer key."),
                                                "question",
                                                Map.of(
                                                        "type",
                                                        "string",
                                                        "description",
                                                        "Question text."),
                                                "type",
                                                Map.of(
                                                        "type",
                                                        "string",
                                                        "enum",
                                                        List.of(
                                                                "text",
                                                                "select",
                                                                "multi_select",
                                                                "confirm"),
                                                        "description",
                                                        "Answer widget type."),
                                                "options",
                                                Map.of(
                                                        "type",
                                                        "array",
                                                        "items",
                                                        Map.of("type", "string"),
                                                        "description",
                                                        "Choices; required for"
                                                                + " select/multi_select."),
                                                "required",
                                                Map.of(
                                                        "type",
                                                        "boolean",
                                                        "description",
                                                        "Whether an answer is mandatory.")),
                                        "required",
                                        List.of("id", "question", "type")))),
                "required",
                List.of("questions"));
    }

    @Override
    public Mono<ToolResultBlock> callAsync(ToolCallParam param) {
        return Mono.fromCallable(
                        () -> {
                            Map<String, Object> input = param.getInput();
                            List<Map<String, Object>> questions =
                                    castMapList(input == null ? null : input.get("questions"));
                            String error = validateQuestions(questions);
                            if (error != null) {
                                return ToolResultBlock.error(error);
                            }
                            // Suspend: the payload becomes the interrupt message the frontend
                            // renders (A2UI form or plain question card); the resume payload is
                            // injected back as this call's tool result (tool is NOT replayed).
                            throw new ToolSuspendException(
                                    enableA2ui
                                            ? renderer.renderEnvelope(
                                                    param.getRuntimeContext(),
                                                    toFormComponents(questions))
                                            : JsonUtils.getJsonCodec()
                                                    .toJson(Map.of("questions", questions)));
                        })
                .onErrorResume(
                        Exception.class,
                        e -> {
                            if (e instanceof ToolSuspendException) {
                                return Mono.error(e);
                            }
                            if (e instanceof A2uiValidationException) {
                                return Mono.just(
                                        ToolResultBlock.error(
                                                "Invalid questions: " + e.getMessage()));
                            }
                            return Mono.just(
                                    ToolResultBlock.error(
                                            "ask_user_question failed: " + e.getMessage()));
                        });
    }

    private static String validateQuestions(List<Map<String, Object>> questions) {
        if (questions == null || questions.isEmpty()) {
            return "Error: `questions` must be a non-empty array.";
        }
        if (questions.size() > 10) {
            return "Error: at most 10 questions per form, got " + questions.size() + ".";
        }
        Set<String> ids = new HashSet<>();
        for (Map<String, Object> question : questions) {
            if (question == null) {
                return "Error: question entries must be objects.";
            }
            String id = stringOf(question.get("id"));
            String text = stringOf(question.get("question"));
            String type = stringOf(question.get("type"));
            if (id == null || id.isBlank() || text == null || text.isBlank()) {
                return "Error: each question needs non-blank `id` and `question`.";
            }
            if (!ids.add(id)) {
                return "Error: duplicate question id \"" + id + "\".";
            }
            if (type == null || !QUESTION_TYPES.contains(type)) {
                return "Error: question \""
                        + id
                        + "\" has invalid type \""
                        + type
                        + "\" (must be one of "
                        + QUESTION_TYPES
                        + ").";
            }
            List<Object> options = listOf(question.get("options"));
            if (("select".equals(type) || "multi_select".equals(type))
                    && (options == null || options.isEmpty())) {
                return "Error: question \""
                        + id
                        + "\" of type \""
                        + type
                        + "\" requires non-empty `options`.";
            }
        }
        return null;
    }

    private static List<Map<String, Object>> toFormComponents(List<Map<String, Object>> questions) {
        List<Map<String, Object>> components = new ArrayList<>();
        for (Map<String, Object> question : questions) {
            String id = stringOf(question.get("id"));
            String type = stringOf(question.get("type"));
            Map<String, Object> props = new java.util.LinkedHashMap<>();
            props.put("name", id);
            props.put("label", stringOf(question.get("question")));
            if (Boolean.TRUE.equals(question.get("required"))) {
                props.put("required", true);
            }
            String component;
            switch (type) {
                case "select" -> {
                    component = "Select";
                    props.put("options", listOf(question.get("options")));
                }
                case "multi_select" -> {
                    component = "Select";
                    props.put("options", listOf(question.get("options")));
                    props.put("multiple", true);
                }
                case "confirm" -> {
                    component = "RadioGroup";
                    props.put("options", List.of("yes", "no"));
                }
                default -> component = "TextInput";
            }
            components.add(
                    Map.of(
                            "id", "q_" + id,
                            "component", component,
                            "props", props));
        }
        components.add(
                Map.of(
                        "id", "a2ui_submit",
                        "component", "Button",
                        "props",
                                Map.of(
                                        "text", "Submit",
                                        "action", "submit")));
        return components;
    }

    @SuppressWarnings("unchecked")
    private static List<Map<String, Object>> castMapList(Object value) {
        return value instanceof List<?> list ? (List<Map<String, Object>>) list : null;
    }

    private static List<Object> listOf(Object value) {
        return value instanceof List<?> list ? (List<Object>) list : null;
    }

    private static String stringOf(Object value) {
        return value instanceof String s ? s : null;
    }
}

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
 *
 * <p>The declaration is a form spec, not a widget list: each question states the answer key, label,
 * widget type and choices, and the optional {@code title}, {@code description} and {@code submitText}
 * frame the form around them. Only {@link #toFormComponents} knows which A2UI component a widget
 * type compiles to, so a host can swap the catalog without touching this tool's schema.
 */
public class AskUserQuestionTool implements AgentTool {

    private static final Set<String> QUESTION_TYPES =
            Set.of("text", "textarea", "select", "multi_select", "radio", "checkbox", "confirm");

    /** Types whose answer comes from a list the model must therefore supply. */
    private static final Set<String> OPTION_TYPES = Set.of("select", "multi_select", "radio");

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
                + " from widgets. Declare each question (answer key, label, widget type, choices,"
                + " required) and frame the form with an optional title, description and submit"
                + " label; the UI is compiled from that spec, you never name components. The tool"
                + " suspends until the user submits; the returned value is a JSON object mapping"
                + " question id to answer (multi_select gives an array, checkbox a boolean)."
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
                                                        "Field label / question text."),
                                                "type",
                                                Map.of(
                                                        "type",
                                                        "string",
                                                        "enum",
                                                        List.of(
                                                                "text",
                                                                "textarea",
                                                                "select",
                                                                "multi_select",
                                                                "radio",
                                                                "checkbox",
                                                                "confirm"),
                                                        "description",
                                                        "Answer widget: text single line,"
                                                                + " textarea multi-line, select"
                                                                + " one of options, multi_select"
                                                                + " several of options, radio"
                                                                + " exclusive choices, checkbox"
                                                                + " one boolean, confirm a"
                                                                + " yes/no question."),
                                                "options",
                                                Map.of(
                                                        "type",
                                                        "array",
                                                        "items",
                                                        Map.of("type", "string"),
                                                        "description",
                                                        "Choices; required for"
                                                                + " select/multi_select/radio."),
                                                "placeholder",
                                                Map.of(
                                                        "type",
                                                        "string",
                                                        "description",
                                                        "Input hint shown while empty."),
                                                "required",
                                                Map.of(
                                                        "type",
                                                        "boolean",
                                                        "description",
                                                        "Whether an answer is mandatory.")),
                                        "required",
                                        List.of("id", "question", "type"))),
                        "title",
                        Map.of("type", "string", "description", "Optional heading above the form."),
                        "description",
                        Map.of(
                                "type",
                                "string",
                                "description",
                                "Optional line under the title explaining why you are asking."),
                        "submitText",
                        Map.of(
                                "type",
                                "string",
                                "description",
                                "Optional submit button label, default \"Submit\".")),
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
                                                    toFormComponents(input, questions))
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
            if (OPTION_TYPES.contains(type) && (options == null || options.isEmpty())) {
                return "Error: question \""
                        + id
                        + "\" of type \""
                        + type
                        + "\" requires non-empty `options`.";
            }
        }
        return null;
    }

    /**
     * Compiles the declarative form spec into the flat component list: the optional frame fields
     * first, then one catalogued input per question, then the submit button. Answer keys are the
     * question ids, so the resume payload the frontend sends back maps id → answer.
     */
    private static List<Map<String, Object>> toFormComponents(
            Map<String, Object> input, List<Map<String, Object>> questions) {
        List<Map<String, Object>> components = new ArrayList<>();
        addTextComponent(components, "a2ui_title", "Heading", stringOf(input.get("title")));
        addTextComponent(components, "a2ui_desc", "Text", stringOf(input.get("description")));
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
                case "radio" -> {
                    component = "RadioGroup";
                    props.put("options", listOf(question.get("options")));
                }
                case "checkbox" -> component = "Checkbox";
                case "textarea" -> component = "TextArea";
                case "confirm" -> {
                    component = "RadioGroup";
                    props.put("options", List.of("yes", "no"));
                }
                default -> {
                    component = "TextInput";
                    String placeholder = stringOf(question.get("placeholder"));
                    if (placeholder != null && !placeholder.isBlank()) {
                        props.put("placeholder", placeholder);
                    }
                }
            }
            components.add(Map.of("id", "q_" + id, "component", component, "props", props));
        }
        String submitText = stringOf(input.get("submitText"));
        components.add(
                Map.of(
                        "id",
                        "a2ui_submit",
                        "component",
                        "Button",
                        "props",
                        Map.of(
                                "text",
                                submitText == null || submitText.isBlank() ? "Submit" : submitText,
                                "action",
                                "submit")));
        return components;
    }

    private static void addTextComponent(
            List<Map<String, Object>> components, String id, String component, String text) {
        if (text != null && !text.isBlank()) {
            components.add(Map.of("id", id, "component", component, "props", Map.of("text", text)));
        }
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

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
package io.agentscope.core.event;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonProperty;

/**
 * The user's answer to one {@code ask_user} tool call.
 *
 * <p>{@code answers} maps each question id (as sent by the model in the tool input) to the user's
 * answer. An answer value may be:
 *
 * <ul>
 *   <li>a {@code String} — free-text answer (or the selected option label);</li>
 *   <li>a {@code List<String>} — selected labels for a multi-select question;</li>
 *   <li>a {@code Map} with keys {@code selected} / {@code text} / {@code skipped} for rich
 *       answers (a skipped question is treated as "user did not answer").</li>
 * </ul>
 *
 * <p>The framework formats these answers into the {@code ask_user} tool result so the model can
 * read them on the next reasoning iteration; the tool itself is never executed.
 */
public class AskUserResult {

    private final String toolCallId;
    private final java.util.Map<String, Object> answers;

    @JsonCreator
    public AskUserResult(
            @JsonProperty("toolCallId") String toolCallId,
            @JsonProperty("answers") java.util.Map<String, Object> answers) {
        if (toolCallId == null || toolCallId.isEmpty()) {
            throw new IllegalArgumentException("AskUserResult.toolCallId must not be empty");
        }
        this.toolCallId = toolCallId;
        this.answers = answers != null ? java.util.Map.copyOf(answers) : java.util.Map.of();
    }

    @JsonProperty("toolCallId")
    public String getToolCallId() {
        return toolCallId;
    }

    @JsonProperty("answers")
    public java.util.Map<String, Object> getAnswers() {
        return answers;
    }

    /**
     * Formats the answers into the model-visible tool result text.
     *
     * @param answers the answer map (questionId → answer)
     * @return a stable, human-readable rendering of the answers
     */
    public static String formatAnswers(java.util.Map<String, Object> answers) {
        if (answers == null || answers.isEmpty()) {
            return "The user did not answer any question.";
        }
        StringBuilder sb = new StringBuilder();
        for (java.util.Map.Entry<String, Object> e : answers.entrySet()) {
            if (sb.length() > 0) {
                sb.append('\n');
            }
            sb.append(e.getKey()).append(": ").append(formatAnswerValue(e.getValue()));
        }
        return sb.toString();
    }

    @SuppressWarnings("unchecked")
    private static String formatAnswerValue(Object value) {
        if (value == null) {
            return "(no answer)";
        }
        if (value instanceof String s) {
            return s;
        }
        if (value instanceof java.util.List<?> list) {
            return list.isEmpty() ? "(no answer)" : String.join("; ", toStrings(list));
        }
        if (value instanceof java.util.Map<?, ?> map) {
            Object skipped = map.get("skipped");
            if (Boolean.TRUE.equals(skipped)) {
                return "(skipped)";
            }
            java.util.List<String> parts = new java.util.ArrayList<>();
            Object selected = map.get("selected");
            if (selected instanceof java.util.List<?> sel && !sel.isEmpty()) {
                parts.add(String.join("; ", toStrings(sel)));
            }
            Object text = map.get("text");
            if (text != null && !text.toString().isBlank()) {
                parts.add(text.toString());
            }
            return parts.isEmpty() ? "(no answer)" : String.join(" | ", parts);
        }
        return value.toString();
    }

    private static java.util.List<String> toStrings(java.util.List<?> values) {
        return values.stream().map(v -> v == null ? "" : v.toString()).toList();
    }

    @Override
    public String toString() {
        return "AskUserResult{toolCallId='" + toolCallId + "', answers=" + answers + '}';
    }
}

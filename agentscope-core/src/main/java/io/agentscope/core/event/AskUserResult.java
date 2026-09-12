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
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Predicate;

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

    private static final String REDACTED_VALUE = "[REDACTED]";

    private final String toolCallId;
    private final Map<String, Object> answers;

    @JsonCreator
    public AskUserResult(
            @JsonProperty("toolCallId") String toolCallId,
            @JsonProperty("answers") Map<String, Object> answers) {
        if (toolCallId == null || toolCallId.isEmpty()) {
            throw new IllegalArgumentException("AskUserResult.toolCallId must not be empty");
        }
        this.toolCallId = toolCallId;
        this.answers =
                answers == null
                        ? Map.of()
                        : Collections.unmodifiableMap(new LinkedHashMap<>(answers));
    }

    @JsonProperty("toolCallId")
    public String getToolCallId() {
        return toolCallId;
    }

    @JsonProperty("answers")
    public Map<String, Object> getAnswers() {
        return answers;
    }

    /**
     * Formats the answers into the model-visible tool result text.
     *
     * @param answers the answer map (questionId → answer)
     * @return a stable, human-readable rendering of the answers
     */
    public static String formatAnswers(Map<String, Object> answers) {
        return formatAnswers(answers, Set.of());
    }

    /**
     * Formats the answers while masking values belonging to secret questions.
     *
     * @param answers the answer map (questionId → answer)
     * @param secretQuestionIds question ids whose values must not be exposed
     * @return a stable, human-readable rendering of the answers with secret values redacted
     */
    public static String formatAnswers(Map<String, Object> answers, Set<String> secretQuestionIds) {
        Set<String> secrets = secretQuestionIds == null ? Set.of() : secretQuestionIds;
        return formatAnswers(answers, key -> secrets.contains(key));
    }

    /**
     * Formats answers using a fail-closed policy for a tool call that declared a secret question.
     * When {@code hasSecretQuestion} is true, only ids in {@code nonSecretQuestionIds} are
     * considered safe to expose; all other answer keys are masked. This protects against hosts
     * that return an invented key or the question text instead of the declared secret id.
     *
     * @param answers the answer map (questionId → answer)
     * @param nonSecretQuestionIds declared question ids that are safe to expose
     * @param hasSecretQuestion whether the tool call declared at least one secret question
     * @return a stable, human-readable rendering of the answers with unknown values redacted
     */
    public static String formatAnswers(
            Map<String, Object> answers,
            Set<String> nonSecretQuestionIds,
            boolean hasSecretQuestion) {
        Set<String> nonSecrets = nonSecretQuestionIds == null ? Set.of() : nonSecretQuestionIds;
        return formatAnswers(answers, key -> hasSecretQuestion && !nonSecrets.contains(key));
    }

    private static String formatAnswers(
            Map<String, Object> answers, Predicate<String> secretAnswerPredicate) {
        if (answers == null || answers.isEmpty()) {
            return "The user did not answer any question.";
        }
        StringBuilder sb = new StringBuilder();
        for (Map.Entry<String, Object> e : answers.entrySet()) {
            if (sb.length() > 0) {
                sb.append('\n');
            }
            sb.append(e.getKey())
                    .append(": ")
                    .append(
                            formatAnswerValue(
                                    e.getValue(), secretAnswerPredicate.test(e.getKey())));
        }
        return sb.toString();
    }

    /**
     * Returns a copy safe to publish in an event stream. Secret answer values are replaced with a
     * fixed marker, while non-secret answers and insertion order are preserved.
     *
     * @param secretQuestionIds question ids whose values must be redacted
     * @return this result when no redaction is needed, otherwise a redacted copy
     */
    public AskUserResult redactedFor(Set<String> secretQuestionIds) {
        if (secretQuestionIds == null || secretQuestionIds.isEmpty() || answers.isEmpty()) {
            return this;
        }
        Map<String, Object> redacted = new LinkedHashMap<>(answers);
        boolean changed = false;
        for (String questionId : secretQuestionIds) {
            if (redacted.containsKey(questionId)) {
                redacted.put(questionId, REDACTED_VALUE);
                changed = true;
            }
        }
        return changed ? new AskUserResult(toolCallId, redacted) : this;
    }

    /**
     * Returns a copy safe to publish when the originating tool call declared a secret question.
     * Only declared non-secret ids remain visible; every other answer key is replaced with the
     * fixed redaction marker.
     *
     * @param nonSecretQuestionIds declared question ids that are safe to expose
     * @param hasSecretQuestion whether the tool call declared at least one secret question
     * @return this result when no redaction is needed, otherwise a redacted copy
     */
    public AskUserResult redactedFor(Set<String> nonSecretQuestionIds, boolean hasSecretQuestion) {
        if (!hasSecretQuestion || answers.isEmpty()) {
            return this;
        }
        Set<String> nonSecrets = nonSecretQuestionIds == null ? Set.of() : nonSecretQuestionIds;
        Map<String, Object> redacted = new LinkedHashMap<>(answers);
        boolean changed = false;
        for (String questionId : answers.keySet()) {
            if (!nonSecrets.contains(questionId)) {
                redacted.put(questionId, REDACTED_VALUE);
                changed = true;
            }
        }
        return changed ? new AskUserResult(toolCallId, redacted) : this;
    }

    private static String formatAnswerValue(Object value, boolean secret) {
        if (secret) {
            return REDACTED_VALUE;
        }
        if (value == null) {
            return "(no answer)";
        }
        if (value instanceof String s) {
            return s;
        }
        if (value instanceof List<?> list) {
            return list.isEmpty() ? "(no answer)" : String.join("; ", toStrings(list));
        }
        if (value instanceof Map<?, ?> map) {
            Object skipped = map.get("skipped");
            if (Boolean.TRUE.equals(skipped)) {
                return "(skipped)";
            }
            List<String> parts = new java.util.ArrayList<>();
            Object selected = map.get("selected");
            if (selected instanceof List<?> sel && !sel.isEmpty()) {
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

    private static List<String> toStrings(List<?> values) {
        return values.stream().map(v -> v == null ? "" : v.toString()).toList();
    }

    @Override
    public String toString() {
        return "AskUserResult{toolCallId='" + toolCallId + "', answers=<redacted>}";
    }
}

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

package io.agentscope.extensions.judge.jev.example;

import io.agentscope.core.message.Msg;
import io.agentscope.core.message.MsgRole;
import io.agentscope.core.model.ToolSchema;
import io.agentscope.extensions.judge.jev.ChoiceAnswer;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;

/** Shared helpers for Jev-backed tool selection. */
final class JevSelectionSupport {

    static final String NONE_OPTION = "__none__";
    static final int MAX_CHOICE_OPTIONS = 255;
    static final int MAX_CANDIDATES_PER_CHOICE = MAX_CHOICE_OPTIONS - 1;

    private JevSelectionSupport() {}

    static String latestUserText(List<Msg> messages) {
        if (messages == null) {
            return "";
        }
        for (int i = messages.size() - 1; i >= 0; i--) {
            Msg message = messages.get(i);
            if (message != null && message.getRole() == MsgRole.USER) {
                String text = message.getTextContent();
                return text == null ? "" : text.trim();
            }
        }
        return "";
    }

    static Map<String, Object> messagesState(List<Msg> messages) {
        if (messages == null || messages.isEmpty()) {
            return Map.of("messages", List.of());
        }
        return Map.of("messages", messages.stream().filter(Objects::nonNull).toList());
    }

    static Map<String, Object> userRequestState(String userText) {
        return Map.of("userRequest", userText == null ? "" : userText);
    }

    /**
     * Builds a bounded conversation state from the most recent messages. Each message is
     * projected to a {@code {role, text}} entry; blank-text messages (null or whitespace-only
     * text) are skipped and do not consume window slots. The window keeps at most {@code
     * maxMessages} messages and at most {@code maxChars} characters of text in total, counted
     * from the newest message backwards. Older messages are dropped first, and the oldest kept
     * message is truncated when the budget runs out.
     */
    static Map<String, Object> recentWindowState(
            List<Msg> messages, int maxMessages, int maxChars) {
        if (messages == null || messages.isEmpty() || maxMessages <= 0 || maxChars <= 0) {
            return Map.of("messages", List.of());
        }
        List<Map<String, String>> entries = new ArrayList<>();
        int remaining = maxChars;
        for (int i = messages.size() - 1;
                i >= 0 && remaining > 0 && entries.size() < maxMessages;
                i--) {
            Msg message = messages.get(i);
            if (message == null) {
                continue;
            }
            String text = message.getTextContent();
            if (text == null || text.isBlank()) {
                continue;
            }
            if (text.length() > remaining) {
                int end = remaining;
                // Never split a surrogate pair at the cut point.
                if (end > 0 && Character.isHighSurrogate(text.charAt(end - 1))) {
                    end--;
                }
                text = text.substring(0, end);
                // The surrogate-aware cut may have emptied the text; skip it like any other
                // blank message so it does not consume a window slot.
                if (text.isBlank()) {
                    continue;
                }
            }
            remaining -= text.length();
            entries.add(
                    0,
                    Map.of(
                            "role",
                            message.getRole().name().toLowerCase(Locale.ROOT),
                            "text",
                            text));
        }
        return Map.of("messages", List.copyOf(entries));
    }

    static Map<String, Object> toolCriteria(List<ToolSchema> tools) {
        Map<String, Object> criteria = new LinkedHashMap<>();
        for (ToolSchema tool : tools) {
            criteria.put(tool.getName(), tool.getDescription());
        }
        criteria.put(NONE_OPTION, "No additional tool is needed for this request.");
        return criteria;
    }

    static <T> List<List<T>> partition(List<T> values, int size) {
        List<List<T>> partitions = new ArrayList<>();
        if (values == null || values.isEmpty()) {
            return partitions;
        }
        for (int i = 0; i < values.size(); i += size) {
            partitions.add(values.subList(i, Math.min(i + size, values.size())));
        }
        return partitions;
    }

    static List<String> selectedNames(ChoiceAnswer answer, int limit, double confidenceThreshold) {
        if (answer == null
                || answer.probabilities() == null
                || answer.probabilities().isEmpty()
                || answer.confidence() == null
                || answer.confidence() < confidenceThreshold) {
            return List.of();
        }
        double noneProbability = answer.probabilities().getOrDefault(NONE_OPTION, 0.0);
        return answer.probabilities().entrySet().stream()
                .filter(entry -> !NONE_OPTION.equals(entry.getKey()))
                .filter(entry -> entry.getValue() != null && entry.getValue() > noneProbability)
                .sorted(Map.Entry.comparingByValue(Comparator.reverseOrder()))
                .limit(limit)
                .map(Map.Entry::getKey)
                .toList();
    }

    static String topName(ChoiceAnswer answer) {
        if (answer == null || answer.probabilities() == null || answer.probabilities().isEmpty()) {
            return null;
        }
        double noneProbability = answer.probabilities().getOrDefault(NONE_OPTION, 0.0);
        return answer.probabilities().entrySet().stream()
                .filter(entry -> !NONE_OPTION.equals(entry.getKey()))
                .filter(entry -> entry.getValue() != null)
                .filter(entry -> entry.getValue() > noneProbability)
                .max(Map.Entry.comparingByValue())
                .map(Map.Entry::getKey)
                .orElse(null);
    }
}

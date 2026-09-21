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

package io.agentscope.extensions.jev.middleware;

import io.agentscope.core.message.Msg;
import io.agentscope.core.message.MsgRole;
import io.agentscope.core.model.ToolSchema;
import io.agentscope.core.skill.AgentSkill;
import io.agentscope.extensions.jev.ChoiceAnswer;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Shared helpers for Jev-backed skill and tool selection. */
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
            return Map.of();
        }
        return Map.of("messages", List.copyOf(messages));
    }

    static Map<String, Object> skillCriteria(List<AgentSkill> skills) {
        Map<String, Object> criteria = new LinkedHashMap<>();
        for (AgentSkill skill : skills) {
            criteria.put(skill.getName(), skill.getDescription());
        }
        criteria.put(NONE_OPTION, "No skill is needed for this request.");
        return criteria;
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
        return answer.probabilities().entrySet().stream()
                .filter(entry -> !NONE_OPTION.equals(entry.getKey()))
                .filter(entry -> entry.getValue() != null)
                .max(Map.Entry.comparingByValue())
                .map(Map.Entry::getKey)
                .orElse(null);
    }
}

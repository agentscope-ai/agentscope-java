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
package io.agentscope.core.agent.config;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonProperty;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * Structured-output schema describing the five sections an agent fills in when compressing prior
 * memory into a continuation summary: {@link #taskOverview()}, {@link #currentState()}, {@link
 * #importantDiscoveries()}, {@link #nextSteps()}, {@link #contextToPreserve()}.
 *
 * <p>{@link #jsonSchema()} returns the JSON-schema dictionary consumed by structured-output APIs;
 * the per-field {@code maxLength} caps mirror the prompt-level guidance an agent receives.
 */
public final class SummarySchema {

    public static final int TASK_OVERVIEW_MAX_LENGTH = 300;
    public static final int CURRENT_STATE_MAX_LENGTH = 300;
    public static final int IMPORTANT_DISCOVERIES_MAX_LENGTH = 300;
    public static final int NEXT_STEPS_MAX_LENGTH = 200;
    public static final int CONTEXT_TO_PRESERVE_MAX_LENGTH = 300;

    private final String taskOverview;
    private final String currentState;
    private final String importantDiscoveries;
    private final String nextSteps;
    private final String contextToPreserve;

    @JsonCreator
    public SummarySchema(
            @JsonProperty("task_overview") String taskOverview,
            @JsonProperty("current_state") String currentState,
            @JsonProperty("important_discoveries") String importantDiscoveries,
            @JsonProperty("next_steps") String nextSteps,
            @JsonProperty("context_to_preserve") String contextToPreserve) {
        this.taskOverview = Objects.requireNonNull(taskOverview, "taskOverview");
        this.currentState = Objects.requireNonNull(currentState, "currentState");
        this.importantDiscoveries =
                Objects.requireNonNull(importantDiscoveries, "importantDiscoveries");
        this.nextSteps = Objects.requireNonNull(nextSteps, "nextSteps");
        this.contextToPreserve = Objects.requireNonNull(contextToPreserve, "contextToPreserve");
    }

    @JsonProperty("task_overview")
    public String taskOverview() {
        return taskOverview;
    }

    @JsonProperty("current_state")
    public String currentState() {
        return currentState;
    }

    @JsonProperty("important_discoveries")
    public String importantDiscoveries() {
        return importantDiscoveries;
    }

    @JsonProperty("next_steps")
    public String nextSteps() {
        return nextSteps;
    }

    @JsonProperty("context_to_preserve")
    public String contextToPreserve() {
        return contextToPreserve;
    }

    /**
     * Returns the JSON schema dictionary that structured-output APIs accept. The result is a fresh,
     * mutable copy on every call so callers can safely mutate it (e.g. attach a {@code $defs} map).
     */
    public static Map<String, Object> jsonSchema() {
        Map<String, Object> properties = new LinkedHashMap<>();
        properties.put(
                "task_overview",
                stringProperty(
                        TASK_OVERVIEW_MAX_LENGTH,
                        "The user's core request and success criteria.\n"
                                + "Any clarifications or constraints they specified"));
        properties.put(
                "current_state",
                stringProperty(
                        CURRENT_STATE_MAX_LENGTH,
                        "What has been completed so far.\n"
                                + "File created, modified, or analyzed (with paths if relevant).\n"
                                + "Key outputs or artifacts produced."));
        properties.put(
                "important_discoveries",
                stringProperty(
                        IMPORTANT_DISCOVERIES_MAX_LENGTH,
                        "Technical constraints or requirements uncovered.\n"
                                + "Decisions made and their rationale.\n"
                                + "Errors encountered and how they were resolved.\n"
                                + "What approaches were tried that didn't work (and why)"));
        properties.put(
                "next_steps",
                stringProperty(
                        NEXT_STEPS_MAX_LENGTH,
                        "Specific actions needed to complete the task.\n"
                                + "Any blockers or open questions to resolve.\n"
                                + "Priority order if multiple steps remain"));
        properties.put(
                "context_to_preserve",
                stringProperty(
                        CONTEXT_TO_PRESERVE_MAX_LENGTH,
                        "User preferences or style requirements.\n"
                                + "Domain-specific details that aren't obvious.\n"
                                + "Any promises made to the user"));

        Map<String, Object> schema = new LinkedHashMap<>();
        schema.put("type", "object");
        schema.put("title", "SummarySchema");
        schema.put("properties", properties);
        schema.put(
                "required",
                List.of(
                        "task_overview",
                        "current_state",
                        "important_discoveries",
                        "next_steps",
                        "context_to_preserve"));
        return schema;
    }

    private static Map<String, Object> stringProperty(int maxLength, String description) {
        Map<String, Object> property = new LinkedHashMap<>();
        property.put("type", "string");
        property.put("maxLength", maxLength);
        property.put("description", description);
        return property;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) {
            return true;
        }
        if (!(o instanceof SummarySchema other)) {
            return false;
        }
        return Objects.equals(taskOverview, other.taskOverview)
                && Objects.equals(currentState, other.currentState)
                && Objects.equals(importantDiscoveries, other.importantDiscoveries)
                && Objects.equals(nextSteps, other.nextSteps)
                && Objects.equals(contextToPreserve, other.contextToPreserve);
    }

    @Override
    public int hashCode() {
        return Objects.hash(
                taskOverview, currentState, importantDiscoveries, nextSteps, contextToPreserve);
    }

    @Override
    public String toString() {
        return "SummarySchema{taskOverview="
                + taskOverview
                + ", currentState="
                + currentState
                + ", importantDiscoveries="
                + importantDiscoveries
                + ", nextSteps="
                + nextSteps
                + ", contextToPreserve="
                + contextToPreserve
                + '}';
    }
}

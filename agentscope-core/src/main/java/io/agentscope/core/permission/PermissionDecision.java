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
package io.agentscope.core.permission;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.annotation.JsonPropertyOrder;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * Decision returned by a permission rule match or a tool self-check.
 *
 * <p>A decision carries the {@link PermissionBehavior} together with a human-readable message and
 * optional fields used by the engine (rewritten inputs, suggested follow-up rules).
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
@JsonPropertyOrder({"behavior", "message", "decision_reason", "updated_input", "suggested_rules"})
public final class PermissionDecision {

    private final PermissionBehavior behavior;
    private final String message;
    private final String decisionReason;
    private final Map<String, Object> updatedInput;
    private final List<PermissionRule> suggestedRules;

    private PermissionDecision(Builder builder) {
        this.behavior = Objects.requireNonNull(builder.behavior, "behavior must not be null");
        this.message = Objects.requireNonNull(builder.message, "message must not be null");
        this.decisionReason = builder.decisionReason;
        this.updatedInput =
                builder.updatedInput == null
                        ? null
                        : Collections.unmodifiableMap(new LinkedHashMap<>(builder.updatedInput));
        this.suggestedRules =
                builder.suggestedRules == null ? null : List.copyOf(builder.suggestedRules);
    }

    @JsonCreator
    static PermissionDecision fromJson(
            @JsonProperty("behavior") PermissionBehavior behavior,
            @JsonProperty("message") String message,
            @JsonProperty("decision_reason") String decisionReason,
            @JsonProperty("updated_input") Map<String, Object> updatedInput,
            @JsonProperty("suggested_rules") List<PermissionRule> suggestedRules) {
        return builder()
                .behavior(behavior)
                .message(message)
                .decisionReason(decisionReason)
                .updatedInput(updatedInput)
                .suggestedRules(suggestedRules)
                .build();
    }

    @JsonProperty("behavior")
    public PermissionBehavior getBehavior() {
        return behavior;
    }

    @JsonProperty("message")
    public String getMessage() {
        return message;
    }

    @JsonProperty("decision_reason")
    public String getDecisionReason() {
        return decisionReason;
    }

    @JsonProperty("updated_input")
    public Map<String, Object> getUpdatedInput() {
        return updatedInput;
    }

    @JsonProperty("suggested_rules")
    public List<PermissionRule> getSuggestedRules() {
        return suggestedRules;
    }

    public static PermissionDecision allow(String message) {
        return builder().behavior(PermissionBehavior.ALLOW).message(message).build();
    }

    public static PermissionDecision deny(String message) {
        return builder().behavior(PermissionBehavior.DENY).message(message).build();
    }

    public static PermissionDecision ask(String message) {
        return builder().behavior(PermissionBehavior.ASK).message(message).build();
    }

    public static PermissionDecision passthrough(String message) {
        return builder().behavior(PermissionBehavior.PASSTHROUGH).message(message).build();
    }

    public static Builder builder() {
        return new Builder();
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) {
            return true;
        }
        if (!(o instanceof PermissionDecision other)) {
            return false;
        }
        return behavior == other.behavior
                && Objects.equals(message, other.message)
                && Objects.equals(decisionReason, other.decisionReason)
                && Objects.equals(updatedInput, other.updatedInput)
                && Objects.equals(suggestedRules, other.suggestedRules);
    }

    @Override
    public int hashCode() {
        return Objects.hash(behavior, message, decisionReason, updatedInput, suggestedRules);
    }

    @Override
    public String toString() {
        return "PermissionDecision{behavior="
                + behavior
                + ", message='"
                + message
                + "', decisionReason='"
                + decisionReason
                + "', updatedInput="
                + updatedInput
                + ", suggestedRules="
                + suggestedRules
                + '}';
    }

    public static final class Builder {
        private PermissionBehavior behavior;
        private String message;
        private String decisionReason;
        private Map<String, Object> updatedInput;
        private List<PermissionRule> suggestedRules;

        private Builder() {}

        public Builder behavior(PermissionBehavior behavior) {
            this.behavior = behavior;
            return this;
        }

        public Builder message(String message) {
            this.message = message;
            return this;
        }

        public Builder decisionReason(String decisionReason) {
            this.decisionReason = decisionReason;
            return this;
        }

        public Builder updatedInput(Map<String, Object> updatedInput) {
            this.updatedInput = updatedInput;
            return this;
        }

        public Builder suggestedRules(List<PermissionRule> suggestedRules) {
            this.suggestedRules = suggestedRules;
            return this;
        }

        public PermissionDecision build() {
            return new PermissionDecision(this);
        }
    }
}

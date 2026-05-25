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
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.annotation.JsonPropertyOrder;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * Permission evaluation context: mode + working directories + three rule tables.
 *
 * <p>Rule tables are keyed by tool name; each value is the ordered list of rules registered for
 * that tool. The engine evaluates {@code denyRules} first, then {@code askRules}, then tool
 * self-check, then {@code allowRules}; see the {@code PermissionEngine} javadoc for full ordering.
 */
@JsonPropertyOrder({"mode", "working_directories", "allow_rules", "deny_rules", "ask_rules"})
public final class PermissionContext {

    private final PermissionMode mode;
    private final Map<String, AdditionalWorkingDirectory> workingDirectories;
    private final Map<String, List<PermissionRule>> allowRules;
    private final Map<String, List<PermissionRule>> denyRules;
    private final Map<String, List<PermissionRule>> askRules;

    private PermissionContext(Builder builder) {
        this.mode = builder.mode == null ? PermissionMode.DEFAULT : builder.mode;
        this.workingDirectories =
                Collections.unmodifiableMap(new LinkedHashMap<>(builder.workingDirectories));
        this.allowRules = freeze(builder.allowRules);
        this.denyRules = freeze(builder.denyRules);
        this.askRules = freeze(builder.askRules);
    }

    @JsonCreator
    static PermissionContext fromJson(
            @JsonProperty("mode") PermissionMode mode,
            @JsonProperty("working_directories")
                    Map<String, AdditionalWorkingDirectory> workingDirectories,
            @JsonProperty("allow_rules") Map<String, List<PermissionRule>> allowRules,
            @JsonProperty("deny_rules") Map<String, List<PermissionRule>> denyRules,
            @JsonProperty("ask_rules") Map<String, List<PermissionRule>> askRules) {
        Builder b = builder();
        if (mode != null) {
            b.mode(mode);
        }
        if (workingDirectories != null) {
            workingDirectories.forEach(b::addWorkingDirectory);
        }
        copyInto(allowRules, b::addAllowRule);
        copyInto(denyRules, b::addDenyRule);
        copyInto(askRules, b::addAskRule);
        return b.build();
    }

    private static void copyInto(Map<String, List<PermissionRule>> source, RuleAdder adder) {
        if (source == null) {
            return;
        }
        source.forEach(
                (tool, rules) -> {
                    if (rules == null) {
                        return;
                    }
                    rules.forEach(rule -> adder.add(tool, rule));
                });
    }

    private static Map<String, List<PermissionRule>> freeze(
            Map<String, List<PermissionRule>> source) {
        Map<String, List<PermissionRule>> copy = new LinkedHashMap<>();
        source.forEach((key, value) -> copy.put(key, List.copyOf(value)));
        return Collections.unmodifiableMap(copy);
    }

    @JsonProperty("mode")
    public PermissionMode getMode() {
        return mode;
    }

    @JsonProperty("working_directories")
    public Map<String, AdditionalWorkingDirectory> getWorkingDirectories() {
        return workingDirectories;
    }

    @JsonProperty("allow_rules")
    public Map<String, List<PermissionRule>> getAllowRules() {
        return allowRules;
    }

    @JsonProperty("deny_rules")
    public Map<String, List<PermissionRule>> getDenyRules() {
        return denyRules;
    }

    @JsonProperty("ask_rules")
    public Map<String, List<PermissionRule>> getAskRules() {
        return askRules;
    }

    public static Builder builder() {
        return new Builder();
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) {
            return true;
        }
        if (!(o instanceof PermissionContext other)) {
            return false;
        }
        return mode == other.mode
                && Objects.equals(workingDirectories, other.workingDirectories)
                && Objects.equals(allowRules, other.allowRules)
                && Objects.equals(denyRules, other.denyRules)
                && Objects.equals(askRules, other.askRules);
    }

    @Override
    public int hashCode() {
        return Objects.hash(mode, workingDirectories, allowRules, denyRules, askRules);
    }

    @Override
    public String toString() {
        return "PermissionContext{mode="
                + mode
                + ", workingDirectories="
                + workingDirectories
                + ", allowRules="
                + allowRules
                + ", denyRules="
                + denyRules
                + ", askRules="
                + askRules
                + '}';
    }

    @FunctionalInterface
    private interface RuleAdder {
        void add(String toolName, PermissionRule rule);
    }

    public static final class Builder {
        private PermissionMode mode = PermissionMode.DEFAULT;
        private final Map<String, AdditionalWorkingDirectory> workingDirectories =
                new LinkedHashMap<>();
        private final Map<String, List<PermissionRule>> allowRules = new LinkedHashMap<>();
        private final Map<String, List<PermissionRule>> denyRules = new LinkedHashMap<>();
        private final Map<String, List<PermissionRule>> askRules = new LinkedHashMap<>();

        private Builder() {}

        public Builder mode(PermissionMode mode) {
            this.mode = Objects.requireNonNull(mode, "mode must not be null");
            return this;
        }

        public Builder addWorkingDirectory(String key, AdditionalWorkingDirectory directory) {
            Objects.requireNonNull(key, "key must not be null");
            Objects.requireNonNull(directory, "directory must not be null");
            this.workingDirectories.put(key, directory);
            return this;
        }

        public Builder addAllowRule(String toolName, PermissionRule rule) {
            appendRule(allowRules, toolName, rule);
            return this;
        }

        public Builder addDenyRule(String toolName, PermissionRule rule) {
            appendRule(denyRules, toolName, rule);
            return this;
        }

        public Builder addAskRule(String toolName, PermissionRule rule) {
            appendRule(askRules, toolName, rule);
            return this;
        }

        private static void appendRule(
                Map<String, List<PermissionRule>> table, String toolName, PermissionRule rule) {
            Objects.requireNonNull(toolName, "toolName must not be null");
            Objects.requireNonNull(rule, "rule must not be null");
            table.computeIfAbsent(toolName, key -> new ArrayList<>()).add(rule);
        }

        public PermissionContext build() {
            return new PermissionContext(this);
        }
    }
}

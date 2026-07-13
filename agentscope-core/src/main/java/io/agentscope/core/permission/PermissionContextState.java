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
import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.annotation.JsonPropertyOrder;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
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
@JsonPropertyOrder({
    "mode",
    "working_directories",
    "allow_rules",
    "deny_rules",
    "ask_rules",
    "inherited_working_directories",
    "inherited_allow_rules",
    "inherited_deny_rules",
    "inherited_ask_rules"
})
public final class PermissionContextState {

    private final PermissionMode mode;
    private final Map<String, AdditionalWorkingDirectory> workingDirectories;
    private final Map<String, List<PermissionRule>> allowRules;
    private final Map<String, List<PermissionRule>> denyRules;
    private final Map<String, List<PermissionRule>> askRules;
    private final Map<String, AdditionalWorkingDirectory> inheritedWorkingDirectories;
    private final Map<String, List<PermissionRule>> inheritedAllowRules;
    private final Map<String, List<PermissionRule>> inheritedDenyRules;
    private final Map<String, List<PermissionRule>> inheritedAskRules;

    private PermissionContextState(Builder builder) {
        this.mode = builder.mode == null ? PermissionMode.DEFAULT : builder.mode;
        this.workingDirectories =
                Collections.unmodifiableMap(new LinkedHashMap<>(builder.workingDirectories));
        this.allowRules = freeze(builder.allowRules);
        this.denyRules = freeze(builder.denyRules);
        this.askRules = freeze(builder.askRules);
        this.inheritedWorkingDirectories =
                Collections.unmodifiableMap(
                        new LinkedHashMap<>(builder.inheritedWorkingDirectories));
        this.inheritedAllowRules = freeze(builder.inheritedAllowRules);
        this.inheritedDenyRules = freeze(builder.inheritedDenyRules);
        this.inheritedAskRules = freeze(builder.inheritedAskRules);
    }

    @JsonCreator
    static PermissionContextState fromJson(
            @JsonProperty("mode") PermissionMode mode,
            @JsonProperty("working_directories")
                    Map<String, AdditionalWorkingDirectory> workingDirectories,
            @JsonProperty("allow_rules") Map<String, List<PermissionRule>> allowRules,
            @JsonProperty("deny_rules") Map<String, List<PermissionRule>> denyRules,
            @JsonProperty("ask_rules") Map<String, List<PermissionRule>> askRules,
            @JsonProperty("inherited_working_directories")
                    Map<String, AdditionalWorkingDirectory> inheritedWorkingDirectories,
            @JsonProperty("inherited_allow_rules")
                    Map<String, List<PermissionRule>> inheritedAllowRules,
            @JsonProperty("inherited_deny_rules")
                    Map<String, List<PermissionRule>> inheritedDenyRules,
            @JsonProperty("inherited_ask_rules")
                    Map<String, List<PermissionRule>> inheritedAskRules) {
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
        if (inheritedWorkingDirectories != null) {
            inheritedWorkingDirectories.forEach(b::markInheritedWorkingDirectory);
        }
        copyInto(inheritedAllowRules, b::markInheritedAllowRule);
        copyInto(inheritedDenyRules, b::markInheritedDenyRule);
        copyInto(inheritedAskRules, b::markInheritedAskRule);
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

    /**
     * Whether this context is "trivial" — built from {@link Builder#build()} with no further
     * customisation. Used by ReAct agents to decide whether to engage the full permission engine
     * (rules + mode + tool self-check) or fall back to the lightweight, pre-2.0 path that only
     * gates on the tool's own {@code checkPermissions} self-check.
     */
    @JsonIgnore
    public boolean isTrivial() {
        return mode == PermissionMode.DEFAULT
                && workingDirectories.isEmpty()
                && allowRules.isEmpty()
                && denyRules.isEmpty()
                && askRules.isEmpty();
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

    @JsonProperty("inherited_working_directories")
    @JsonInclude(JsonInclude.Include.NON_EMPTY)
    public Map<String, AdditionalWorkingDirectory> getInheritedWorkingDirectories() {
        return inheritedWorkingDirectories;
    }

    @JsonProperty("inherited_allow_rules")
    @JsonInclude(JsonInclude.Include.NON_EMPTY)
    public Map<String, List<PermissionRule>> getInheritedAllowRules() {
        return inheritedAllowRules;
    }

    @JsonProperty("inherited_deny_rules")
    @JsonInclude(JsonInclude.Include.NON_EMPTY)
    public Map<String, List<PermissionRule>> getInheritedDenyRules() {
        return inheritedDenyRules;
    }

    @JsonProperty("inherited_ask_rules")
    @JsonInclude(JsonInclude.Include.NON_EMPTY)
    public Map<String, List<PermissionRule>> getInheritedAskRules() {
        return inheritedAskRules;
    }

    public static Builder builder() {
        return new Builder();
    }

    /**
     * Returns a copy of this context with the {@link PermissionMode} replaced and every working
     * directory and rule preserved. Used to switch the evaluation mode at runtime (e.g. flipping a
     * session into {@link PermissionMode#BYPASS}) without losing the configured rules.
     *
     * @param newMode the mode the returned context should use
     * @return a new context identical to this one except for its mode (returns {@code this} when the
     *     mode is unchanged)
     */
    public PermissionContextState withMode(PermissionMode newMode) {
        Objects.requireNonNull(newMode, "newMode must not be null");
        if (newMode == this.mode) {
            return this;
        }
        Builder b = builder().mode(newMode);
        workingDirectories.forEach(b::addWorkingDirectory);
        copyInto(allowRules, b::addAllowRule);
        copyInto(denyRules, b::addDenyRule);
        copyInto(askRules, b::addAskRule);
        inheritedWorkingDirectories.forEach(b::markInheritedWorkingDirectory);
        copyInto(inheritedAllowRules, b::markInheritedAllowRule);
        copyInto(inheritedDenyRules, b::markInheritedDenyRule);
        copyInto(inheritedAskRules, b::markInheritedAskRule);
        return b.build();
    }

    /**
     * Returns an immutable child permission context that also carries the supplied parent scope
     * and rules.
     *
     * <p>The child mode and child entries take precedence. Parent working directories are added
     * only when the child does not already define the same key. Rule tables retain child-first
     * insertion order and de-duplicate equal rules before appending parent rules. Permission
     * evaluation still applies the engine's deny/ask/tool-check/allow ordering, so inherited deny
     * and ask rules cannot be bypassed by an inherited allow rule.
     *
     * @param parent the parent context to inherit
     * @return a new merged context; neither source context is mutated
     */
    public PermissionContextState inheritFrom(PermissionContextState parent) {
        Objects.requireNonNull(parent, "parent must not be null");
        Builder b = builder().mode(mode);
        Map<String, AdditionalWorkingDirectory> localWorkingDirectories =
                withoutInheritedWorkingDirectories();
        localWorkingDirectories.forEach(b::addWorkingDirectory);
        parent.workingDirectories.forEach(
                (key, directory) -> {
                    if (!localWorkingDirectories.containsKey(key)) {
                        b.addWorkingDirectory(key, directory);
                        b.markInheritedWorkingDirectory(key, directory);
                    }
                });
        copyRebasedRules(
                allowRules,
                inheritedAllowRules,
                parent.allowRules,
                b::addAllowRule,
                b::markInheritedAllowRule);
        copyRebasedRules(
                denyRules,
                inheritedDenyRules,
                parent.denyRules,
                b::addDenyRule,
                b::markInheritedDenyRule);
        copyRebasedRules(
                askRules,
                inheritedAskRules,
                parent.askRules,
                b::addAskRule,
                b::markInheritedAskRule);
        return b.build();
    }

    private Map<String, AdditionalWorkingDirectory> withoutInheritedWorkingDirectories() {
        Map<String, AdditionalWorkingDirectory> local = new LinkedHashMap<>(workingDirectories);
        inheritedWorkingDirectories.forEach((key, directory) -> local.remove(key, directory));
        return local;
    }

    private static void copyRebasedRules(
            Map<String, List<PermissionRule>> effectiveChild,
            Map<String, List<PermissionRule>> inheritedChild,
            Map<String, List<PermissionRule>> parent,
            RuleAdder effectiveAdder,
            RuleAdder inheritedAdder) {
        Map<String, LinkedHashSet<PermissionRule>> local = new LinkedHashMap<>();
        collectDistinctRules(local, effectiveChild);
        inheritedChild.forEach(
                (tool, rules) -> {
                    LinkedHashSet<PermissionRule> localRules = local.get(tool);
                    if (localRules != null) {
                        localRules.removeAll(rules);
                        if (localRules.isEmpty()) {
                            local.remove(tool);
                        }
                    }
                });
        local.forEach((tool, rules) -> rules.forEach(rule -> effectiveAdder.add(tool, rule)));
        parent.forEach(
                (tool, rules) -> {
                    LinkedHashSet<PermissionRule> mergedRules =
                            local.computeIfAbsent(tool, ignored -> new LinkedHashSet<>());
                    for (PermissionRule rule : rules) {
                        if (mergedRules.add(rule)) {
                            effectiveAdder.add(tool, rule);
                            inheritedAdder.add(tool, rule);
                        }
                    }
                });
    }

    private static void collectDistinctRules(
            Map<String, LinkedHashSet<PermissionRule>> target,
            Map<String, List<PermissionRule>> source) {
        source.forEach(
                (tool, rules) ->
                        target.computeIfAbsent(tool, ignored -> new LinkedHashSet<>())
                                .addAll(rules));
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) {
            return true;
        }
        if (!(o instanceof PermissionContextState other)) {
            return false;
        }
        return mode == other.mode
                && Objects.equals(workingDirectories, other.workingDirectories)
                && Objects.equals(allowRules, other.allowRules)
                && Objects.equals(denyRules, other.denyRules)
                && Objects.equals(askRules, other.askRules)
                && Objects.equals(inheritedWorkingDirectories, other.inheritedWorkingDirectories)
                && Objects.equals(inheritedAllowRules, other.inheritedAllowRules)
                && Objects.equals(inheritedDenyRules, other.inheritedDenyRules)
                && Objects.equals(inheritedAskRules, other.inheritedAskRules);
    }

    @Override
    public int hashCode() {
        return Objects.hash(
                mode,
                workingDirectories,
                allowRules,
                denyRules,
                askRules,
                inheritedWorkingDirectories,
                inheritedAllowRules,
                inheritedDenyRules,
                inheritedAskRules);
    }

    @Override
    public String toString() {
        return "PermissionContextState{mode="
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
        private final Map<String, AdditionalWorkingDirectory> inheritedWorkingDirectories =
                new LinkedHashMap<>();
        private final Map<String, List<PermissionRule>> inheritedAllowRules = new LinkedHashMap<>();
        private final Map<String, List<PermissionRule>> inheritedDenyRules = new LinkedHashMap<>();
        private final Map<String, List<PermissionRule>> inheritedAskRules = new LinkedHashMap<>();

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

        private void markInheritedWorkingDirectory(
                String key, AdditionalWorkingDirectory directory) {
            inheritedWorkingDirectories.put(key, directory);
        }

        private void markInheritedAllowRule(String toolName, PermissionRule rule) {
            appendRule(inheritedAllowRules, toolName, rule);
        }

        private void markInheritedDenyRule(String toolName, PermissionRule rule) {
            appendRule(inheritedDenyRules, toolName, rule);
        }

        private void markInheritedAskRule(String toolName, PermissionRule rule) {
            appendRule(inheritedAskRules, toolName, rule);
        }

        private static void appendRule(
                Map<String, List<PermissionRule>> table, String toolName, PermissionRule rule) {
            Objects.requireNonNull(toolName, "toolName must not be null");
            Objects.requireNonNull(rule, "rule must not be null");
            table.computeIfAbsent(toolName, key -> new ArrayList<>()).add(rule);
        }

        public PermissionContextState build() {
            return new PermissionContextState(this);
        }
    }
}

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
package io.agentscope.core.agent;

import io.agentscope.core.skill.AgentSkill;
import io.agentscope.core.tool.AgentTool;
import io.agentscope.core.tool.SkillToolGroup;
import java.util.Set;
import java.util.function.Function;

/**
 * Immutable per-request grant set, agent-agnostic; supersets are harmless. Only one surface
 * shape skips trimming entirely: ALL three dimensions {@code null} (nothing set — the {@link
 * #ALLOW_ALL} shape). As soon as any dimension is set — even to an explicit empty set — the
 * surface is a complete grant enumeration: unset ({@code null}) dimensions contribute no
 * grants, exactly like {@link Set#of()} (explicitly empty); the difference stays visible via
 * the accessors. All grant decisions live in the query methods; callers only pass mechanical
 * lookups (the tool's groups).
 *
 * <p>A tool is granted when any explicit path matches: it is system-marked, per-name
 * whitelisted, a member of a granted group, or in a granted skill's tool group. A skill is
 * granted when it is system-marked or its name is whitelisted. Default is deny: anything
 * not reachable from the enumeration does not exist.
 *
 * @param allowedSkills allowed skill names (the business identity, e.g. from an
 *     entitlement list); {@code null} = not set (no skill grants)
 * @param allowedToolGroups allowed ToolGroup names; {@code null} = not set (no group grants)
 * @param allowedTools allowed tool names (per-name, grouping ignored); {@code null} = not set
 *     (no per-name grants)
 * @author shanhongyu
 */
public record CapabilitySurface(
        Set<String> allowedSkills, Set<String> allowedToolGroups, Set<String> allowedTools) {

    /**
     * Defensive copy locks immutability at construction; unset dimensions stay null so
     * "not set" remains distinguishable from an explicit empty set.
     */
    public CapabilitySurface {
        allowedSkills = allowedSkills == null ? null : Set.copyOf(allowedSkills);
        allowedToolGroups = allowedToolGroups == null ? null : Set.copyOf(allowedToolGroups);
        allowedTools = allowedTools == null ? null : Set.copyOf(allowedTools);
    }

    /** Allow-everything sentinel (all three null), substituted when no resolver is configured. */
    public static final CapabilitySurface ALLOW_ALL = new CapabilitySurface(null, null, null);

    /**
     * Explicit deny-everything grant set (system capabilities still pass); also the
     * self-service degradation value a resolver may return when it must keep serving.
     */
    public static final CapabilitySurface DENY_ALL =
            new CapabilitySurface(Set.of(), Set.of(), Set.of());

    /** Whether nothing is set at all — the only shape that skips trimming. */
    private boolean isUnrestricted() {
        return allowedSkills == null && allowedToolGroups == null && allowedTools == null;
    }

    /**
     * Skill decision by name, grant set only. Fallback for callers that do not hold an
     * {@link AgentSkill} instance; this overload does not know the system-capability mark, so
     * such callers must apply that bypass themselves (or resolve the skill and prefer {@link
     * #allowsSkill(AgentSkill)}).
     *
     * @param skillName the skill name to check
     * @return {@code true} for an all-null surface or a whitelisted skill name
     */
    public boolean allowsSkill(String skillName) {
        return isUnrestricted() || (allowedSkills != null && allowedSkills.contains(skillName));
    }

    /**
     * Skill decision, full entry point. Prefer this over {@link #allowsSkill(String)} whenever
     * the caller holds the {@link AgentSkill}: it applies the system-capability bypass ({@code
     * skill.isSystem()}), mirroring how {@link #allowsTool} treats system tools.
     *
     * @param skill the skill to check; {@code null} (a registry miss) is never granted, not
     *     even on {@link #ALLOW_ALL} — fail-closed like a missing surface
     * @return {@code true} for a system skill, an all-null surface, or a whitelisted skill
     *     name
     */
    public boolean allowsSkill(AgentSkill skill) {
        if (skill == null) {
            return false; // registry miss: fail closed
        }
        return skill.isSystem() || allowsSkill(skill.getName());
    }

    /**
     * Tool-group decision. A granted skill implicitly grants its skill tool group.
     *
     * @param name the group name to check
     * @return {@code true} for an all-null surface, a whitelisted group, or a group derived
     *     from a granted skill
     */
    public boolean allowsToolGroup(String name) {
        return isUnrestricted()
                || (allowedToolGroups != null && allowedToolGroups.contains(name))
                || isSkillGroupName(name);
    }

    /**
     * Tool decision; any matching path grants (system, per-name, group, skill).
     *
     * @param tool the tool to check
     * @param groupsOfName the tool's groups on this agent (mechanical lookup, may be empty)
     * @return {@code true} when the tool is system-marked, nothing is set at all, or any grant
     *     path matches
     */
    public boolean allowsTool(AgentTool tool, Set<String> groupsOfName) {
        if (tool.isSystem()) {
            return true; // system capability
        }
        if (isUnrestricted()) {
            return true; // nothing set: no trimming
        }
        if (allowedTools != null && allowedTools.contains(tool.getName())) {
            return true; // per-name grant
        }
        return groupsOfName.stream().anyMatch(this::allowsToolGroup); // group or skill path
    }

    /**
     * Group activation decision for reset_equipped_tools (enum and execution side). A group is
     * also reachable when some per-name granted tool belongs to it (other members still go
     * through {@link #allowsTool}).
     *
     * @param name the group name to check
     * @param groupsOf reverse lookup from tool name to its groups on this agent
     * @return {@code true} when the group is granted or reachable via a granted member tool
     */
    public boolean allowsToolGroupActivation(String name, Function<String, Set<String>> groupsOf) {
        if (allowsToolGroup(name)) {
            return true;
        }
        // Reachability: some per-name granted tool belongs to this group.
        return allowedTools != null
                && allowedTools.stream().anyMatch(t -> groupsOf.apply(t).contains(name));
    }

    /**
     * Whether the group name derives from a granted skill (naming convention: {@code
     * skillName + "_skill_tools"}, see {@link SkillToolGroup#skillToolsGroupName}).
     *
     * @param name the group name to check
     * @return {@code true} when the name is a granted skill's tool group
     */
    private boolean isSkillGroupName(String name) {
        return allowedSkills != null
                && allowedSkills.stream()
                        .map(SkillToolGroup::skillToolsGroupName)
                        .anyMatch(name::equals);
    }
}

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
package io.agentscope.core.skill;

import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Pure storage layer for {@link AgentSkill}s, keyed by skillId.
 *
 * <p>The key is {@link AgentSkill#getSkillId()}: the skill name when no source is set, or the
 * combined {@code name__s__source} form when one is — so same-named skills from different
 * repositories coexist as separate entries, while re-registering under the same key (same name
 * and source) replaces the previous instance (version override). Lookups are exact: a bare name
 * is itself a skillId (the source-less form) and never resolves a sourced copy.
 *
 * <p>The skill activation state is not stored here: it is the per-session {@code activatedGroups}
 * on {@link io.agentscope.core.state.ToolContextState}.
 */
class SkillRegistry {
    private final Map<String, AgentSkill> skills = new ConcurrentHashMap<>();

    // ==================== Registration ====================

    /**
     * Registers a skill under its skillId. Same-named skills from different sources coexist;
     * re-registering the same skillId replaces the previous instance.
     *
     * @param skillId the skill's identifier (its own key, see {@link AgentSkill#getSkillId()})
     * @param skill The skill implementation (must not be null)
     */
    void registerSkill(String skillId, AgentSkill skill) {
        skills.put(skillId, skill);
    }

    // ==================== Query Operations ====================

    /**
     * Gets a skill by its exact skillId.
     *
     * @param skillId the skillId (a bare name is the source-less form)
     * @return The matching skill instance, or null if not found
     */
    AgentSkill getSkill(String skillId) {
        return skillId == null ? null : skills.get(skillId);
    }

    /**
     * Gets all registered skillIds (each unique in this catalog).
     *
     * @return Set of skillIds (never null, may be empty)
     */
    Set<String> getSkillIds() {
        return new TreeSet<>(skills.keySet());
    }

    /**
     * Checks if a skill with this exact skillId exists.
     *
     * @param skillId the skillId (a bare name is the source-less form)
     * @return true if the skill exists, false otherwise
     */
    boolean exists(String skillId) {
        return skillId != null && skills.containsKey(skillId);
    }

    // ==================== Removal Operations ====================

    /**
     * Removes the skill with this exact skillId (no-op when absent).
     *
     * @param skillId the skillId (a bare name is the source-less form)
     */
    void removeSkill(String skillId) {
        if (skillId != null) {
            skills.remove(skillId);
        }
    }
}

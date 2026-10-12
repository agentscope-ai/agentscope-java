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
 * Pure storage layer for {@link AgentSkill}s, keyed by skill name.
 *
 * <p>The skill name is the business identity (industry-standard unique skill naming) and must be
 * unique within one catalog; re-registering the same instance is idempotent, while registering a
 * different instance under an already-used name fails fast.
 *
 * <p>The skill activation state is not stored here: it is the per-session {@code activatedGroups}
 * on {@link io.agentscope.core.state.ToolContextState}.
 */
class SkillRegistry {
    private final Map<String, AgentSkill> skills = new ConcurrentHashMap<>();

    // ==================== Registration ====================

    /**
     * Registers a skill under its name.
     *
     * @param skillName The unique skill name (must not be null)
     * @param skill The skill implementation (must not be null)
     * @throws IllegalArgumentException when the name is already taken by a different instance
     */
    void registerSkill(String skillName, AgentSkill skill) {
        AgentSkill existing = skills.putIfAbsent(skillName, skill);
        if (existing != null && existing != skill) {
            throw new IllegalArgumentException(
                    "Duplicate skill name: '" + skillName + "' is already registered");
        }
    }

    // ==================== Query Operations ====================

    /**
     * Gets a skill by name.
     *
     * @param skillName The skill name (must not be null)
     * @return The skill instance, or null if not found
     */
    AgentSkill getSkill(String skillName) {
        return skills.get(skillName);
    }

    /**
     * Gets all skill names.
     *
     * @return Set of skill names (never null, may be empty)
     */
    Set<String> getSkillNames() {
        return new TreeSet<>(skills.keySet());
    }

    /**
     * Checks if a skill exists.
     *
     * @param skillName The skill name (must not be null)
     * @return true if the skill exists, false otherwise
     */
    boolean exists(String skillName) {
        return skills.containsKey(skillName);
    }

    // ==================== Removal Operations ====================

    /**
     * Removes a skill completely.
     *
     * @param skillName The skill name (must not be null)
     */
    void removeSkill(String skillName) {
        skills.remove(skillName);
    }
}

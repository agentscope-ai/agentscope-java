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
 * Pure storage layer for {@link AgentSkill}s, keyed by skill id.
 *
 * <p>The skill activation state is not stored here: it is the per-session {@code activatedGroups}
 * on {@link io.agentscope.core.state.ToolContextState}.
 *
 * <p>This is a pure storage layer: parameters are assumed non-null and validation is performed at
 * the {@code Toolkit} layer.
 */
class SkillRegistry {
    private final Map<String, AgentSkill> skills = new ConcurrentHashMap<>();

    // ==================== Registration ====================

    /**
     * Registers a skill, replacing any existing skill with the same id.
     *
     * @param skillId The unique skill identifier (must not be null)
     * @param skill The skill implementation (must not be null)
     */
    void registerSkill(String skillId, AgentSkill skill) {
        skills.put(skillId, skill);
    }

    // ==================== Query Operations ====================

    /**
     * Gets a skill by ID.
     *
     * @param skillId The skill ID (must not be null)
     * @return The skill instance, or null if not found
     */
    AgentSkill getSkill(String skillId) {
        return skills.get(skillId);
    }

    /**
     * Gets all skill IDs.
     *
     * @return Set of skill IDs (never null, may be empty)
     */
    Set<String> getSkillIds() {
        return new TreeSet<>(skills.keySet());
    }

    /**
     * Checks if a skill exists.
     *
     * @param skillId The skill ID (must not be null)
     * @return true if the skill exists, false otherwise
     */
    boolean exists(String skillId) {
        return skills.containsKey(skillId);
    }

    // ==================== Removal Operations ====================

    /**
     * Removes a skill completely.
     *
     * @param skillId The skill ID (must not be null)
     */
    void removeSkill(String skillId) {
        skills.remove(skillId);
    }
}

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

import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Registry for managing skill registration and activation state.
 *
 * <p>This class provides basic storage and retrieval operations for skills.
 *
 * <p><b>Responsibilities:</b>
 * <ul>
 *   <li>Store and retrieve skills
 *   <li>Track skill metadata and activation state
 * </ul>
 *
 * <p><b>Design principle:</b>
 * This is a pure storage layer. All parameters are assumed to be non-null
 * unless explicitly documented. Parameter validation should be performed
 * at the Toolkit layer.
 */
class SkillRegistry {
    private final Map<String, AgentSkill> skills = new ConcurrentHashMap<>();
    private final Map<String, RegisteredSkill> registeredSkills = new ConcurrentHashMap<>();

    // ==================== Registration ====================

    /**
     * Registers a skill with its metadata.
     *
     * <p>If the skill is already registered, it will be replaced.
     *
     * @param skillId The unique skill identifier (must not be null)
     * @param skill The skill implementation (must not be null)
     * @param registered The registered skill wrapper containing metadata (must not be null)
     */
    void registerSkill(String skillId, AgentSkill skill, RegisteredSkill registered) {
        skills.put(skillId, skill);
        registeredSkills.put(skillId, registered);
    }

    // ==================== Activation Management ====================

    /**
     * Sets the activation state of a skill.
     *
     * @param skillId The skill ID (must not be null)
     * @param active Whether to activate the skill
     */
    void setSkillActive(String skillId, boolean active) {
        RegisteredSkill registered = registeredSkills.get(skillId);
        if (registered != null) {
            registered.setActive(active);
        }
    }

    /**
     * Sets the activation state of all skills.
     *
     * @param active Whether to activate all skills
     */
    void setAllSkillsActive(boolean active) {
        registeredSkills.values().forEach(r -> r.setActive(active));
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
     * Gets a registered skill by ID.
     *
     * @param skillId The skill ID (must not be null)
     * @return The registered skill, or null if not found
     */
    RegisteredSkill getRegisteredSkill(String skillId) {
        return registeredSkills.get(skillId);
    }

    /**
     * Gets all skill IDs.
     *
     * @return Set of skill IDs (never null, may be empty)
     */
    Set<String> getSkillIds() {
        return new HashSet<>(skills.keySet());
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

    /**
     * Gets all registered skills.
     *
     * @return Map of skill IDs to registered skills (never null, may be empty)
     */
    Map<String, RegisteredSkill> getAllRegisteredSkills() {
        return new ConcurrentHashMap<>(registeredSkills);
    }

    // ==================== Removal Operations ====================

    /**
     * Removes a skill completely.
     *
     * @param skillId The skill ID (must not be null)
     */
    void removeSkill(String skillId) {
        skills.remove(skillId);
        registeredSkills.remove(skillId);
    }
}

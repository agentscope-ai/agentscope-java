/*
 * Copyright 2024-2025 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      https://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package io.agentscope.core.skill;

import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Registry for managing skill registration, versioning, and activation state.
 *
 * <p>This class provides basic storage and retrieval operations for skills
 * with multi-version support.
 *
 * <p><b>Responsibilities:</b>
 * <ul>
 *   <li>Store and retrieve skills
 *   <li>Manage version storage (via VersionedSkill)
 *   <li>Track skill metadata and activation state
 * </ul>
 *
 * <p><b>Design principle:</b>
 * This is a pure storage layer. All parameters are assumed to be non-null
 * unless explicitly documented. Parameter validation should be performed
 * at the Toolkit layer.
 */
class SkillRegistry {
    private final Map<String, VersionedSkill> skills = new ConcurrentHashMap<>();
    private final Map<String, RegisteredSkill> registeredSkills = new ConcurrentHashMap<>();

    // ==================== Registration ====================

    /**
     * Registers a skill with its metadata.
     *
     * <p>If the skill is already registered, this adds a new version.
     *
     * @param skillId The unique skill identifier (must not be null)
     * @param skill The skill implementation (must not be null)
     * @param registered The registered skill wrapper containing metadata (must not be null)
     */
    void registerSkill(String skillId, AgentSkill skill, RegisteredSkill registered) {
        if (!skills.containsKey(skillId)) {
            // First time registration
            skills.put(skillId, new VersionedSkill(skill));
            registeredSkills.put(skillId, registered);
        } else {
            // Add as new version
            skills.get(skillId).addVersion(skill, null, true);
        }
    }

    // ==================== Version Operations ====================

    /**
     * Adds a new version as the latest.
     *
     * @param skillId The skill ID (must not be null)
     * @param skill The skill instance to add as new version (must not be null)
     * @param versionId The version ID (null to auto-generate)
     */
    void addNewVersion(String skillId, AgentSkill skill, String versionId) {
        VersionedSkill versionedSkill = skills.get(skillId);
        if (versionedSkill != null) {
            versionedSkill.addVersion(skill, versionId, true);
        }
    }

    /**
     * Adds an old version (doesn't affect latest).
     *
     * @param skillId The skill ID (must not be null)
     * @param skill The skill instance to add as old version (must not be null)
     * @param versionId The version ID (null to auto-generate)
     */
    void addOldVersion(String skillId, AgentSkill skill, String versionId) {
        VersionedSkill versionedSkill = skills.get(skillId);
        if (versionedSkill != null) {
            versionedSkill.addVersion(skill, versionId, false);
        }
    }

    /**
     * Promotes an old version to be the latest version.
     *
     * @param skillId The skill ID (must not be null)
     * @param versionId The version ID to promote to latest (must not be null)
     */
    void promoteVersionToLatest(String skillId, String versionId) {
        VersionedSkill versionedSkill = skills.get(skillId);
        if (versionedSkill != null) {
            versionedSkill.promoteToLatest(versionId);
        }
    }

    /**
     * Gets a specific version of a skill.
     *
     * @param skillId The skill ID (must not be null)
     * @param versionId The version ID (must not be null, "latest" for current version)
     * @return The skill for the specified version, or null if not found
     */
    AgentSkill getSkillVersion(String skillId, String versionId) {
        VersionedSkill versionedSkill = skills.get(skillId);
        if (versionedSkill == null) {
            return null;
        }
        return versionedSkill.getSkillByVersionId(versionId);
    }

    /**
     * Lists all version IDs for a skill.
     *
     * @param skillId The skill ID (must not be null)
     * @return List of version IDs, or empty list if skill not found
     */
    List<String> listVersionIds(String skillId) {
        VersionedSkill versionedSkill = skills.get(skillId);
        if (versionedSkill == null) {
            return List.of();
        }
        return versionedSkill.getAllVersionIds();
    }

    /**
     * Gets the version ID of the latest version.
     *
     * @param skillId The skill ID (must not be null)
     * @return The latest version ID, or null if skill not found
     */
    String getLatestVersionId(String skillId) {
        VersionedSkill versionedSkill = skills.get(skillId);
        if (versionedSkill == null) {
            return null;
        }
        return versionedSkill.getLatestVersionId();
    }

    /**
     * Removes a specific version of a skill.
     *
     * <p>Note: The latest version cannot be removed.
     *
     * @param skillId The skill ID (must not be null)
     * @param versionId The version ID to remove (must not be null)
     */
    void removeVersion(String skillId, String versionId) {
        VersionedSkill versionedSkill = skills.get(skillId);
        if (versionedSkill != null) {
            versionedSkill.removeVersion(versionId);
        }
    }

    /**
     * Removes all old versions of a skill, keeping only the latest.
     *
     * @param skillId The skill ID (must not be null)
     */
    void clearOldVersions(String skillId) {
        VersionedSkill versionedSkill = skills.get(skillId);
        if (versionedSkill != null) {
            versionedSkill.clearOldVersions();
        }
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
     * Gets a skill by ID (latest version).
     *
     * @param skillId The skill ID (must not be null)
     * @return The skill instance, or null if not found
     */
    AgentSkill getSkill(String skillId) {
        VersionedSkill versionedSkill = skills.get(skillId);
        if (versionedSkill == null) {
            return null;
        }
        return versionedSkill.getLatestSkill();
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
     * <p>If force is false and the skill has old versions, removal will be skipped.
     *
     * @param skillId The skill ID (must not be null)
     * @param force Whether to force removal even if old versions exist
     */
    void removeSkill(String skillId, boolean force) {
        VersionedSkill versionedSkill = skills.get(skillId);
        if (versionedSkill == null) {
            return;
        }

        // Only remove if forced or no old versions exist
        if (force || versionedSkill.getOldVersionSkills().isEmpty()) {
            skills.remove(skillId);
            registeredSkills.remove(skillId);
        }
    }
}

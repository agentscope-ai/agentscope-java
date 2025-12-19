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

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Collectors;

/**
 * Manages multiple versions of an agent skill with version control capabilities.
 *
 * <p>This class maintains a latest version and a collection of old versions,
 * providing basic version storage and retrieval operations.
 *
 * <p><b>Version management:</b>
 * <ul>
 *   <li>Latest version - the current active version (always exists, cannot be removed)
 *   <li>Old versions - historical versions stored in a map by version ID
 *   <li>Automatic version ID generation using timestamp if not specified
 * </ul>
 *
 * <p><b>Design principle:</b>
 * This class is a pure version container with minimal interface.
 * Parameter validation should be performed at the Toolkit layer.
 * All parameters passed to this class are assumed to be non-null.
 * The latest version always exists and cannot be null.
 */
public class VersionedSkill {

    private Version latestVersion;
    private final Map<String, Version> oldVersions;

    /**
     * Creates a versioned skill with an initial skill version.
     *
     * @param skill The initial skill to add as the latest version (must not be null)
     */
    public VersionedSkill(AgentSkill skill) {
        this.latestVersion = new Version(skill, null);
        this.oldVersions = new HashMap<>();
    }

    /**
     * Adds a new version to the skill.
     *
     * <p>If the skill object reference is the same as the current latest version,
     * the operation will be skipped to prevent duplicate versions.
     *
     * <p><b>Note:</b> Uses reference equality ({@code ==}), not {@code .equals()}.
     *
     * @param skill The skill to add (must not be null)
     * @param versionId The version ID (null to auto-generate using timestamp)
     * @param asLatest If true, sets as latest version; if false, adds to old versions
     */
    public void addVersion(AgentSkill skill, String versionId, boolean asLatest) {
        // avoid adding the same skill object reference again
        if (latestVersion.getSkill() == skill) {
            return;
        }

        Version newVersion = new Version(skill, versionId);

        if (asLatest) {
            // Demote current latest to old versions
            oldVersions.put(latestVersion.getVersionId(), latestVersion);
            latestVersion = newVersion;
        } else {
            // Add directly to old versions
            oldVersions.put(newVersion.getVersionId(), newVersion);
        }
    }

    /**
     * Gets the latest version of the skill.
     *
     * @return The latest skill version (never null)
     */
    public AgentSkill getLatestSkill() {
        return latestVersion.getSkill();
    }

    /**
     * Gets the version ID of the latest version.
     *
     * @return The latest version ID (never null)
     */
    public String getLatestVersionId() {
        return latestVersion.getVersionId();
    }

    /**
     * Gets a skill by its version ID.
     *
     * <p>Supports "latest" as an alias for the current version.
     * Returns null if the version ID is not found.
     *
     * @param versionId The version ID to retrieve (must not be null)
     * @return The skill for the specified version, or null if not found
     */
    public AgentSkill getSkillByVersionId(String versionId) {
        // Handle "latest" alias
        if ("latest".equals(versionId)) {
            return latestVersion.getSkill();
        }

        // Check if it's the latest version ID
        if (versionId.equals(latestVersion.getVersionId())) {
            return latestVersion.getSkill();
        }

        // Check old versions, may return null
        Version version = oldVersions.get(versionId);
        return version != null ? version.getSkill() : null;
    }

    /**
     * Promotes an old version to be the latest version.
     *
     * <p>The current latest version is demoted to old versions.
     * If the version ID is not found in old versions, this operation is silently ignored.
     *
     * @param versionId The version ID to promote to latest (must not be null)
     */
    public void promoteToLatest(String versionId) {
        Version versionToPromote = oldVersions.get(versionId);
        if (versionToPromote == null) {
            // Version not found, ignore silently
            return;
        }

        // Swap: demote current latest, promote the old version
        oldVersions.put(latestVersion.getVersionId(), latestVersion);
        latestVersion = versionToPromote;
        oldVersions.remove(versionId);
    }

    /**
     * Removes a version by its version ID.
     *
     * <p><b>Important:</b> The latest version cannot be removed.
     * Only old versions can be removed. If the version ID matches the latest version
     * or is not found, this operation is silently ignored.
     *
     * @param versionId The version ID to remove (must not be null)
     */
    public void removeVersion(String versionId) {
        // Only remove from old versions
        // Latest version cannot be removed
        oldVersions.remove(versionId);
    }

    /**
     * Gets all version IDs including both old versions and the latest.
     *
     * <p>The list includes the actual version IDs plus "latest" as an alias.
     *
     * @return List of all version IDs (never null, never empty)
     */
    public List<String> getAllVersionIds() {
        List<String> versionIds = new ArrayList<>(oldVersions.keySet());
        versionIds.add(latestVersion.getVersionId());
        versionIds.add("latest");
        return versionIds;
    }

    /**
     * Gets version IDs of all old versions (excluding the latest).
     *
     * @return List of old version IDs (never null, may be empty)
     */
    public List<String> getOldVersionIds() {
        return new ArrayList<>(oldVersions.keySet());
    }

    /**
     * Gets all skill versions including old versions (excluding the latest).
     *
     * @return List of all old version skills (never null, may be empty)
     */
    public List<AgentSkill> getOldVersionSkills() {
        return oldVersions.values().stream().map(Version::getSkill).collect(Collectors.toList());
    }

    /**
     * Checks if a specific version exists.
     *
     * @param versionId The version ID to check (must not be null)
     * @return true if the version exists
     */
    public boolean hasVersion(String versionId) {
        if ("latest".equals(versionId)) {
            return true;
        }
        if (versionId.equals(latestVersion.getVersionId())) {
            return true;
        }
        return oldVersions.containsKey(versionId);
    }

    /**
     * Removes all old versions, keeping only the latest version.
     */
    public void clearOldVersions() {
        oldVersions.clear();
    }

    /**
     * Internal class representing a single version of a skill.
     */
    private static class Version {
        private final AgentSkill skill;
        private final String versionId;

        /**
         * Creates a version with the given skill and version ID.
         *
         * @param skill The skill instance (must not be null)
         * @param versionId The version ID (null to auto-generate using current timestamp)
         */
        public Version(AgentSkill skill, String versionId) {
            this.skill = skill;
            this.versionId =
                    versionId != null
                            ? versionId
                            : String.valueOf(System.currentTimeMillis())
                                    + "-"
                                    + UUID.randomUUID().toString().substring(0, 8);
        }

        public String getVersionId() {
            return versionId;
        }

        public AgentSkill getSkill() {
            return skill;
        }
    }
}

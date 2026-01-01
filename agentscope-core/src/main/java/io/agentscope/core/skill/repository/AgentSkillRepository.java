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
package io.agentscope.core.skill.repository;

import io.agentscope.core.skill.AgentSkill;
import java.util.List;

/**
 * Repository interface for AgentSkill persistence operations.
 *
 * <p>This interface follows the Repository Pattern and Dependency Inversion Principle,
 * allowing different storage backends (filesystem, database, remote APIs, etc.) to be
 * used interchangeably.
 *
 * <p>Example usage:
 * <pre>{@code
 * AgentSkillRepository repo = new GitHubSkillRepository("owner/repo");
 * AgentSkill skill = repo.getByName("calculate").orElseThrow();
 * }</pre>
 */
public interface AgentSkillRepository {

    /**
     * Gets a skill by its name.
     *
     * @param name The skill name
     * @return The skill matching the name
     * @throws IllegalArgumentException if name or version is invalid
     */
    AgentSkill getSkill(String name);

    /**
     * Lists all available skill IDs in the repository.
     *
     * <p>Each skill ID follows the format {@code name_version_source}.
     *
     * @return List of all skill IDs (never null, may be empty)
     */
    List<String> getAllSkillNames();

    /**
     * Gets all skills from the repository.
     *
     * @return List of all skills (never null, may be empty)
     */
    List<AgentSkill> getAllSkills();

    /**
     * Saves or updates a skill in the repository.
     *
     * <p>If a skill with the same name exists, it will be updated.
     * Otherwise, a new skill will be created.
     * <p>If the skills list is empty, return false.
     *
     * @param skills The skills to save
     * @param force Whether to force save even if the skill already exists
     * @return {@code true} if save succeeded, {@code false} otherwise
     */
    boolean save(List<AgentSkill> skills, boolean force);

    /**
     * Deletes a skill by its skill name.
     *
     * @param skillName The skill name (never null)
     * @return {@code true} if deletion succeeded, {@code false} if skill not found
     */
    boolean delete(String skillName);

    /**
     * Checks if a skill exists in the repository.
     *
     * @param skillName The skill name (never null)
     * @return {@code true} if the skill exists, {@code false} otherwise
     */
    boolean skillExists(String skillName);

    /**
     * Gets metadata about this repository.
     *
     * <p>The information includes repository type, location, and other metadata.
     *
     * @return Repository information (never null)
     */
    AgentSkillRepositoryInfo getRepositoryInfo();

    /**
     * Gets the source identifier of this repository.
     *
     * <p>The source follows the format {@code repositoryType_location}.
     *
     * @return The source identifier (never null)
     */
    String getSource();

    /**
     * Sets the writeable flag for this repository.
     *
     * @param writeable Whether the repository supports write operations
     */
    void setWriteable(boolean writeable);

    /**
     * Checks if the repository supports write operations.
     *
     * @return {@code true} if writable, {@code false} otherwise
     */
    boolean isWriteable();

    /**
     * Cleans up any resources used by this repository.
     *
     * <p>Implementations should override this method if they need to release resources
     * such as network connections, file handles, or caches.
     */
    default void close() {
        // Default implementation does nothing
    }
}

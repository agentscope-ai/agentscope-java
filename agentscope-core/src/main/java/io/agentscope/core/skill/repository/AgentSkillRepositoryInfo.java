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

/**
 * Metadata about a skill repository.
 *
 * <p>Contains essential information about a skill repository including its type,
 * location, and write capability.
 *
 * <p><b>Repository types:</b>
 * <ul>
 *   <li>filesystem - Local file system storage
 *   <li>github - GitHub repository
 *   <li>mysql - MySQL database
 *   <li>redis - Redis cache
 *   <li>Custom implementations
 * </ul>
 *
 * <p><b>Usage example:</b>
 * <pre>{@code
 * AgentSkillRepositoryInfo info = new AgentSkillRepositoryInfo(
 *     "filesystem",
 *     "/path/to/skills",
 *     true
 * );
 * }</pre>
 */
public class AgentSkillRepositoryInfo {
    private final String type;
    private final String location;
    private final boolean writable;

    /**
     * Creates a new AgentSkillRepositoryInfo instance.
     *
     * @param type The repository type (never null)
     * @param location The repository location (never null)
     * @param writable Whether the repository supports write operations
     */
    public AgentSkillRepositoryInfo(String type, String location, boolean writable) {
        this.type = type;
        this.location = location;
        this.writable = writable;
    }

    /**
     * Gets the repository type.
     *
     * @return The repository type (never null)
     */
    public String getType() {
        return type;
    }

    /**
     * Gets the repository location.
     *
     * @return The repository location (never null)
     */
    public String getLocation() {
        return location;
    }

    /**
     * Checks if the repository supports write operations.
     *
     * @return {@code true} if writable, {@code false} otherwise
     */
    public boolean isWritable() {
        return writable;
    }

    /**
     * Returns a string representation of this repository info.
     *
     * @return String in format "AgentSkillRepositoryInfo{type='...', location='...', writable=...}"
     */
    @Override
    public String toString() {
        return String.format(
                "AgentSkillRepositoryInfo{type='%s', location='%s', writable=%s}",
                type, location, writable);
    }
}

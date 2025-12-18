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
import java.util.Objects;
import java.util.Set;

/**
 * Represents a group of related skills for organization and management.
 *
 * <p>Skill groups provide a way to organize related skills together, supporting
 * batch activation or deactivation. Through skill groups, skill availability can
 * be flexibly controlled based on different scenarios.
 *
 * <p><b>Usage example:</b>
 * <pre>{@code
 * SkillGroup group = SkillGroup.builder()
 *     .name("data_analysis")
 *     .description("Data analysis related skills")
 *     .active(true)
 *     .build();
 * group.addSkill("calculate");
 * }</pre>
 */
public class SkillGroup {
    private final String name;
    private final String description;
    private boolean active;
    private final Set<String> skills; // Skill names in this group

    private SkillGroup(SkillGroup.Builder builder) {
        this.name = Objects.requireNonNull(builder.name, "name cannot be null");
        this.description = builder.description != null ? builder.description : "";
        this.active = builder.active;
        this.skills = new HashSet<>(builder.skills);
    }

    /**
     * Gets the skill group name.
     *
     * @return The group name (never null)
     */
    public String getName() {
        return name;
    }

    /**
     * Gets the skill group description.
     *
     * @return The group description (empty string if not set)
     */
    public String getDescription() {
        return description;
    }

    /**
     * Checks if the skill group is active.
     *
     * @return true if active, false otherwise
     */
    public boolean isActive() {
        return active;
    }

    /**
     * Sets the activation state of the skill group.
     *
     * @param active true to activate, false to deactivate
     */
    public void setActive(boolean active) {
        this.active = active;
    }

    /**
     * Gets a defensive copy of the skill set.
     *
     * @return A new set containing skill names
     */
    public Set<String> getSkills() {
        return new HashSet<>(skills); // Defensive copy
    }

    /**
     * Adds a skill to the group.
     *
     * @param skillName The skill name to add
     */
    public void addSkill(String skillName) {
        skills.add(skillName);
    }

    /**
     * Removes a skill from the group.
     *
     * @param skillName The skill name to remove
     */
    public void removeSkill(String skillName) {
        skills.remove(skillName);
    }

    /**
     * Checks if the group contains a specific skill.
     *
     * @param skillName The skill name to check
     * @return true if contained, false otherwise
     */
    public boolean containsSkill(String skillName) {
        return skills.contains(skillName);
    }

    /**
     * Creates a skill group builder.
     *
     * @return A new builder instance
     */
    public static SkillGroup.Builder builder() {
        return new SkillGroup.Builder();
    }

    /**
     * Builder for creating skill groups.
     */
    public static class Builder {

        private String name;
        private String description = "";
        private boolean active = true;
        private Set<String> skills = new HashSet<>();

        /**
         * Sets the skill group name.
         *
         * @param name The group name (required)
         * @return This builder for method chaining
         */
        public SkillGroup.Builder name(String name) {
            this.name = name;
            return this;
        }

        /**
         * Sets the skill group description.
         *
         * @param description The group description
         * @return This builder for method chaining
         */
        public SkillGroup.Builder description(String description) {
            this.description = description;
            return this;
        }

        /**
         * Sets the initial activation state.
         *
         * @param active true to activate (default), false to deactivate
         * @return This builder for method chaining
         */
        public SkillGroup.Builder active(boolean active) {
            this.active = active;
            return this;
        }

        /**
         * Sets the initial skill set.
         *
         * @param skills The set of skill names (a defensive copy will be created)
         * @return This builder for method chaining
         */
        public SkillGroup.Builder skills(Set<String> skills) {
            this.skills = new HashSet<>(skills);
            return this;
        }

        /**
         * Builds the skill group instance.
         *
         * @return A new skill group instance
         * @throws NullPointerException if name is null
         */
        public SkillGroup build() {
            return new SkillGroup(this);
        }
    }
}

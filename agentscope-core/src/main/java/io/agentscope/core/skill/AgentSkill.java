/*
 * Copyright 2024-2026 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package io.agentscope.core.skill;

import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

/**
 * Represents an agent skill that can be loaded and used by agents.
 *
 * <p>A skill consists of:
 * <ul>
 *   <li>Name and description - identifying the skill
 *   <li>Skill content - the actual skill implementation or instructions
 *   <li>Resources - supporting files or data referenced by the skill
 *   <li>Version and source - tracking skill origin and versioning
 * </ul>
 *
 * <p><b>Creation options:</b>
 * <ul>
 *   <li>From markdown with YAML frontmatter - metadata extracted automatically
 *   <li>From explicit parameters - direct construction with all fields
 *   <li>From builder - for creating modified versions of existing skills
 * </ul>
 *
 * <p><b>Usage examples:</b>
 * <pre>{@code
 * // From markdown with frontmatter (use SkillUtil)
 * String skillMd = "---\nname: my_skill\ndescription: Does something\n---\nContent here";
 * Map<String, String> resources = Map.of("file1.txt", "content1");
 * AgentSkill skill = SkillUtil.createFrom(skillMd, resources);
 *
 * // Direct construction
 * AgentSkill skill2 = new AgentSkill("my_skill", "Does something", "Content here", resources);
 *
 * // Create modified version using builder
 * AgentSkill modified = skill.toBuilder()
 *     .description("Updated description")
 *     .skillContent("Modified instructions")
 *     .addResource("config.json", "{\"key\": \"value\"}")
 *     .build();
 * }</pre>
 *
 * @see io.agentscope.core.skill.util.SkillUtil
 * @see io.agentscope.core.skill.util.MarkdownSkillParser
 */
public class AgentSkill {
    private final String name;
    private final String description;
    private final String skillContent;
    private final Map<String, String> resources;
    private final String source;

    /**
     * Creates an AgentSkill with explicit parameters.
     *
     * <p>Use this constructor when you want to create a skill directly without parsing
     * markdown. Uses "custom" as the default source.
     *
     * @param name Skill name (must not be null or empty)
     * @param description Skill description (must not be null or empty)
     * @param skillContent The skill implementation or instructions (must not be null or empty)
     * @param resources Supporting resources referenced by the skill (can be null)
     * @throws IllegalArgumentException if name, description, or skillContent is null or empty
     */
    public AgentSkill(
            String name, String description, String skillContent, Map<String, String> resources) {
        this(name, description, skillContent, resources, "custom");
    }

    /**
     * Creates an AgentSkill with explicit parameters and custom source.
     *
     * <p>Use this constructor when you want to create a skill directly without parsing
     * markdown. The source parameter indicates where the skill originated from.
     *
     * @param name Skill name (must not be null or empty)
     * @param description Skill description (must not be null or empty)
     * @param skillContent The skill implementation or instructions (must not be null or empty)
     * @param resources Supporting resources referenced by the skill (can be null)
     * @param source Source identifier for the skill (null defaults to "custom")
     * @throws IllegalArgumentException if name, description, or skillContent is null or empty
     */
    public AgentSkill(
            String name,
            String description,
            String skillContent,
            Map<String, String> resources,
            String source) {
        if (name == null || name.isEmpty() || description == null || description.isEmpty()) {
            throw new IllegalArgumentException(
                    "The skill must have `name` and `description` fields.");
        }
        if (skillContent == null || skillContent.isEmpty()) {
            throw new IllegalArgumentException("The skill must have content");
        }

        this.name = name;
        this.description = description;
        this.skillContent = skillContent;
        this.resources = resources != null ? new HashMap<>(resources) : new HashMap<>();
        this.source = source != null ? source : "custom";
    }

    /**
     * Gets the skill name.
     *
     * @return The skill name (never null)
     */
    public String getName() {
        return name;
    }

    /**
     * Gets the skill description.
     *
     * @return The skill description (never null)
     */
    public String getDescription() {
        return description;
    }

    /**
     * Gets the skill content.
     *
     * <p>This contains the actual skill implementation or instructions.
     *
     * @return The skill content (never null)
     */
    public String getSkillContent() {
        return skillContent;
    }

    /**
     * Gets the skill source identifier.
     *
     * @return The source identifier (never null)
     */
    public String getSource() {
        return source;
    }

    /**
     * Gets the skill resources.
     *
     * @return The resources map (never null, may be empty)
     */
    public Map<String, String> getResources() {
        return new HashMap<>(resources);
    }

    /**
     * Gets the resource content by path.
     *
     * @param resourcePath The resource path
     * @return The resource content, or null if not found
     */
    public String getResource(String resourcePath) {
        return resources.get(resourcePath);
    }

    /**
     * Gets all resource paths for this skill.
     *
     * @return Unmodifiable set of resource paths
     */
    public Set<String> getResourcePaths() {
        return Collections.unmodifiableSet(new HashSet<>(resources.keySet()));
    }

    /**
     * Gets a unique identifier for this skill.
     *
     * <p>The ID is composed of name and source: "name_source".
     *
     * @return Unique skill identifier (never null)
     */
    public String getSkillId() {
        return name + "_" + source;
    }

    /**
     * Creates a builder initialized with this skill's values.
     *
     * <p>This is useful for creating modified versions of existing skills.
     *
     * @return A new builder instance
     */
    public Builder toBuilder() {
        return new Builder(this);
    }

    /**
     * Creates a new builder for creating a skill from scratch.
     *
     * @return A new builder instance with empty fields
     */
    public static Builder builder() {
        return new Builder();
    }

    /**
     * Returns a string representation of this skill.
     *
     * @return String representation including name, description, and source
     */
    @Override
    public String toString() {
        return "AgentSkill{name='"
                + name
                + "', description='"
                + description
                + "', source='"
                + source
                + "'}";
    }

    /**
     * Builder for creating AgentSkill instances.
     *
     * <p>This builder allows selective modification of skill fields to create
     * new skill instances based on existing ones, or to create new skills from scratch.
     *
     * <p><b>Usage examples:</b>
     * <pre>{@code
     * // Create from scratch
     * AgentSkill skill = AgentSkill.builder()
     *     .name("my_skill")
     *     .description("Does something")
     *     .skillContent("Instructions here")
     *     .addResource("file.txt", "content")
     *     .build();
     *
     * // Modify existing skill
     * AgentSkill modified = existingSkill.toBuilder()
     *     .description("Updated description")
     *     .addResource("new_file.txt", "new content")
     *     .build();
     * }</pre>
     */
    public static class Builder {
        private String name;
        private String description;
        private String skillContent;
        private Map<String, String> resources;
        private String source;

        /**
         * Creates an empty builder.
         */
        private Builder() {
            this.resources = new HashMap<>();
        }

        /**
         * Creates a builder initialized with values from an existing skill.
         *
         * @param baseSkill The skill to copy values from
         */
        private Builder(AgentSkill baseSkill) {
            this.name = baseSkill.name;
            this.description = baseSkill.description;
            this.skillContent = baseSkill.skillContent;
            this.resources = new HashMap<>(baseSkill.resources);
            this.source = baseSkill.source;
        }

        /**
         * Sets the skill name.
         *
         * @param name The skill name
         * @return This builder
         */
        public Builder name(String name) {
            this.name = name;
            return this;
        }

        /**
         * Sets the skill description.
         *
         * @param description The skill description
         * @return This builder
         */
        public Builder description(String description) {
            this.description = description;
            return this;
        }

        /**
         * Sets the skill content.
         *
         * @param skillContent The skill content/instructions
         * @return This builder
         */
        public Builder skillContent(String skillContent) {
            this.skillContent = skillContent;
            return this;
        }

        /**
         * Replaces all resources with a new map.
         *
         * @param resources The new resources map
         * @return This builder
         */
        public Builder resources(Map<String, String> resources) {
            this.resources = new HashMap<>(resources);
            return this;
        }

        /**
         * Adds or updates a single resource.
         *
         * @param path The resource path
         * @param content The resource content
         * @return This builder
         */
        public Builder addResource(String path, String content) {
            this.resources.put(path, content);
            return this;
        }

        /**
         * Removes a resource.
         *
         * @param path The resource path to remove
         * @return This builder
         */
        public Builder removeResource(String path) {
            this.resources.remove(path);
            return this;
        }

        /**
         * Clears all resources.
         *
         * @return This builder
         */
        public Builder clearResources() {
            this.resources.clear();
            return this;
        }

        /**
         * Sets the source identifier.
         *
         * @param source The source identifier
         * @return This builder
         */
        public Builder source(String source) {
            this.source = source;
            return this;
        }

        /**
         * Builds the AgentSkill instance.
         *
         * @return A new AgentSkill instance
         * @throws IllegalArgumentException if required fields are missing
         */
        public AgentSkill build() {
            return new AgentSkill(name, description, skillContent, resources, source);
        }
    }
}

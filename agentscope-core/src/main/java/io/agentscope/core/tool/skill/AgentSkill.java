/*
 * Copyright 2024-2025 the original author or authors.
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

package io.agentscope.core.tool.skill;

import java.util.Map;

/**
 * Represents an agent skill.
 *
 * <p>An agent skill is a collection of name, description, and skill content that can be used to
 * perform a task.
 *
 * <p>This class is immutable and thread-safe.
 *
 * <p>There are two ways to create an AgentSkill:
 * <ul>
 *   <li><b>From YAML frontmatter content</b> - the skill content includes a YAML frontmatter with
 *       'name' and 'description' fields</li>
 *   <li><b>From explicit parameters</b> - name, description, and content are provided separately,
 *       and the content does not require YAML frontmatter</li>
 * </ul>
 *
 * <p>Example usage:
 *
 * <pre>{@code
 * // Method 1: Create from YAML frontmatter content
 * String skillContentWithYaml = """
 *     ---
 *     name: data_analysis
 *     description: Tools for data analysis
 *     ---
 *     # Data Analysis Skill
 *     ...
 *     """;
 * AgentSkill skill = new AgentSkill(skillContentWithYaml);
 *
 * // Method 2: Create with explicit parameters (no YAML frontmatter needed)
 * String plainContent = "# Data Analysis Skill\n...";
 * AgentSkill skill = new AgentSkill("data_analysis", "Tools for data analysis", plainContent);
 * }</pre>
 */
public class AgentSkill {
    private final String name;
    private final String description;
    private final String skillContent;

    /**
     * Create an agent skill from name, description, and content string.
     *
     * @param name The name of the agent skill
     * @param description The description of the agent skill
     * @param skillContent The content string of the agent skill
     * @throws IllegalArgumentException if name, description, or skillContent is null or empty
     */
    public AgentSkill(String name, String description, String skillContent) {
        if (name == null
                || name.isEmpty()
                || description == null
                || description.isEmpty()
                || skillContent == null
                || skillContent.isEmpty()) {
            throw new IllegalArgumentException(
                    "The skill must include `name`, `description`, and `skillContent` fields.");
        }
        this.name = name;
        this.description = description;
        this.skillContent = skillContent;
    }

    /**
     * Create an agent skill from content string with YAML frontmatter.
     *
     * <p>The skill content must contain a YAML frontmatter with 'name' and 'description' fields.
     *
     * <p><b>Type Conversion:</b> The 'name' and 'description' fields will be converted to strings
     * using {@link String#valueOf(Object)}. This means numeric values (e.g., {@code name: 123})
     * and boolean values (e.g., {@code description: true}) will be automatically converted to
     * their string representations ({@code "123"} and {@code "true"} respectively).
     *
     * @param skillContent The content string of the agent skill with YAML frontmatter
     * @throws IllegalArgumentException if skillContent is null, or if frontmatter is missing or
     *         invalid, or if 'name' or 'description' fields are missing, null, or result in empty
     *         strings after conversion
     */
    public AgentSkill(String skillContent) {
        // Check YAML Frontmatter
        Map<String, Object> metadata = AgentSkillYamlReader.parse(skillContent);
        Object nameObj = metadata.get("name");
        Object descObj = metadata.get("description");
        String name = (nameObj != null) ? String.valueOf(nameObj) : "";
        String description = (descObj != null) ? String.valueOf(descObj) : "";
        if (name.isEmpty() || description.isEmpty()) {
            throw new IllegalArgumentException(
                    "The skill content must have a YAML Front Matter including `name` and"
                            + " `description` fields.");
        }
        this.name = name;
        this.description = description;
        this.skillContent = skillContent;
    }

    /**
     * Get the name of the agent skill.
     *
     * @return The name of the agent skill
     */
    public String getName() {
        return name;
    }

    /**
     * Get the description of the agent skill.
     *
     * @return The description of the agent skill
     */
    public String getDescription() {
        return description;
    }

    /**
     * Get the content string of the agent skill.
     *
     * @return The content string of the agent skill
     */
    public String getSkillContent() {
        return skillContent;
    }
}

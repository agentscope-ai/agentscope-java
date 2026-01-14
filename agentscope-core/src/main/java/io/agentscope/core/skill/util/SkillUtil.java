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

package io.agentscope.core.skill.util;

import io.agentscope.core.skill.AgentSkill;
import io.agentscope.core.skill.util.MarkdownSkillParser.ParsedMarkdown;
import java.util.Map;

/**
 * Utility class for creating AgentSkill instances.
 *
 * <p>This class provides factory methods to create AgentSkill objects from various sources,
 * particularly from markdown content with YAML frontmatter.
 *
 * <p><b>Usage example:</b>
 * <pre>{@code
 * // Create skill from markdown with default source
 * String skillMd = "---\nname: my_skill\ndescription: Does something\n---\nContent here";
 * Map<String, String> resources = Map.of("file1.txt", "content1");
 * AgentSkill skill = SkillUtil.createFrom(skillMd, resources);
 *
 * // Create skill from markdown with custom source
 * AgentSkill skill2 = SkillUtil.createFrom(skillMd, resources, "github");
 * }</pre>
 */
public class SkillUtil {

    /**
     * Private constructor to prevent instantiation.
     */
    private SkillUtil() {}

    /**
     * Creates an AgentSkill from markdown with YAML frontmatter.
     *
     * <p>Parses the markdown to extract metadata (name, description) from YAML frontmatter
     * and content. Uses "custom" as the default source.
     *
     * @param skillMd Markdown content with YAML frontmatter containing name and description
     * @param resources Supporting resources referenced by the skill (can be null)
     * @return Created AgentSkill instance
     * @throws IllegalArgumentException if name or description is missing, or if content is empty
     */
    public static AgentSkill createFrom(String skillMd, Map<String, String> resources) {
        return createFrom(skillMd, resources, "custom");
    }

    /**
     * Creates an AgentSkill from markdown with YAML frontmatter and custom source.
     *
     * <p>Parses the markdown to extract metadata (name, description) from YAML frontmatter
     * and content. The source parameter indicates where the skill originated from.
     *
     * @param skillMd Markdown content with YAML frontmatter containing name and description
     * @param resources Supporting resources referenced by the skill (can be null)
     * @param source Source identifier for the skill (null defaults to "custom")
     * @return Created AgentSkill instance
     * @throws IllegalArgumentException if name or description is missing, or if content is empty
     */
    public static AgentSkill createFrom(
            String skillMd, Map<String, String> resources, String source) {
        ParsedMarkdown parsed = MarkdownSkillParser.parse(skillMd);
        Map<String, String> metadata = parsed.getMetadata();

        String name = metadata.get("name");
        String description = metadata.get("description");
        String skillContent = parsed.getContent();

        if (name == null || name.isEmpty() || description == null || description.isEmpty()) {
            throw new IllegalArgumentException(
                    "The SKILL.md must have a YAML Front Matter including `name` and"
                            + " `description` fields.");
        }
        if (skillContent == null || skillContent.isEmpty()) {
            throw new IllegalArgumentException(
                    "The SKILL.md must have content except for the YAML Front Matter.");
        }

        return new AgentSkill(name, description, skillContent, resources, source);
    }
}

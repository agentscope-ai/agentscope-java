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
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.yaml.snakeyaml.LoaderOptions;
import org.yaml.snakeyaml.Yaml;
import org.yaml.snakeyaml.constructor.SafeConstructor;

/**
 * Package-private utility for reading YAML frontmatter from agent skill content.
 *
 * <p>This utility extracts YAML frontmatter metadata from agent skill files.
 * Frontmatter is metadata enclosed between triple dashes at the beginning of a file:
 *
 * <pre>{@code
 * ---
 * name: example_skill
 * description: Example skill description
 * ---
 * # Skill Content
 * ...
 * }</pre>
 *
 * <p>This class is package-private and intended for internal use by {@link AgentSkill}.
 */
class AgentSkillYamlReader {

    /**
     * Private constructor to prevent instantiation.
     */
    private AgentSkillYamlReader() {}

    // Pattern to match frontmatter: starts with ---, ends with ---
    // Pattern explanation:
    // ^---          : frontmatter starts with --- at the beginning of the string
    // \\s*          : optional whitespace after opening ---
    // [\\r\\n]+     : one or more line breaks (handles \n, \r\n, \r)
    // (.*?)         : captured group - frontmatter content (non-greedy)
    // [\\r\\n]+     : one or more line breaks before closing ---
    // ---           : closing --- delimiter
    private static final Pattern FRONTMATTER_PATTERN =
            Pattern.compile("^---\\s*[\\r\\n]+(.*?)[\\r\\n]+---", Pattern.DOTALL);

    /**
     * Extracts YAML frontmatter metadata from string content.
     *
     * @param content Content with frontmatter
     * @return Metadata Map, returns empty Map if no frontmatter found
     * @throws IllegalArgumentException if YAML syntax is invalid
     */
    static Map<String, Object> parse(String content) {
        if (content == null || content.isEmpty()) {
            return Map.of();
        }

        Matcher matcher = FRONTMATTER_PATTERN.matcher(content);

        if (!matcher.find()) {
            return Map.of(); // No frontmatter found
        }

        String yamlContent = matcher.group(1).trim();

        if (yamlContent.isEmpty()) {
            return Map.of();
        }
        try {
            Yaml yaml = new Yaml(new SafeConstructor(new LoaderOptions()));
            Object loaded = yaml.load(yamlContent);

            if (loaded == null) {
                return Map.of();
            }
            if (!(loaded instanceof Map)) {
                throw new IllegalArgumentException(
                        "YAML frontmatter must be a map, not a "
                                + loaded.getClass().getSimpleName());
            }
            @SuppressWarnings("unchecked")
            Map<String, Object> metadata = (Map<String, Object>) loaded;
            return metadata;
        } catch (IllegalArgumentException e) {
            // Re-throw our own IllegalArgumentException
            throw e;
        } catch (RuntimeException e) {
            // Only catch YAML parsing related runtime exceptions
            throw new IllegalArgumentException("Invalid YAML frontmatter syntax", e);
        }
    }
}

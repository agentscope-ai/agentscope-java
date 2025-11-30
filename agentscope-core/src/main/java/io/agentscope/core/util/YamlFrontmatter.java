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

package io.agentscope.core.util;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.yaml.snakeyaml.Yaml;

/**
 * Simple YAML Frontmatter metadata extraction utility.
 *
 * <p>This utility extracts YAML frontmatter metadata from markdown or text files.
 * Frontmatter is metadata enclosed between triple dashes at the beginning of a file:
 *
 * <pre>{@code
 * ---
 * name: example_skill
 * description: Example skill description
 * ---
 * # Document Content
 * ...
 * }</pre>
 */
public class YamlFrontmatter {

    // Pattern to match frontmatter: starts with ---, ends with ---
    private static final Pattern FRONTMATTER_PATTERN =
            Pattern.compile(
                    "^---\\s*[\\r\\n]+(.*?)[\\r\\n]+---", // Only captures frontmatter section
                    Pattern.DOTALL);

    /**
     * Extracts YAML frontmatter metadata from string content.
     *
     * @param content Content with frontmatter
     * @return Metadata Map, returns empty Map if no frontmatter found
     * @throws IllegalArgumentException if YAML syntax is invalid
     */
    public static Map<String, Object> parse(String content) {
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
            Yaml yaml = new Yaml();
            Map<String, Object> metadata = yaml.load(yamlContent);
            return metadata != null ? metadata : Map.of();
        } catch (Exception e) {
            throw new IllegalArgumentException("Invalid YAML frontmatter syntax", e);
        }
    }

    /**
     * Extracts YAML frontmatter metadata from a file.
     *
     * @param filePath Path to the file
     * @return Metadata Map
     * @throws IOException if file reading fails
     */
    public static Map<String, Object> parseFile(Path filePath) throws IOException {
        String content = Files.readString(filePath, StandardCharsets.UTF_8);
        return parse(content);
    }
}

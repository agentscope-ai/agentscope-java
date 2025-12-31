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

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.yaml.snakeyaml.DumperOptions;
import org.yaml.snakeyaml.LoaderOptions;
import org.yaml.snakeyaml.Yaml;
import org.yaml.snakeyaml.constructor.SafeConstructor;
import org.yaml.snakeyaml.representer.Representer;

/**
 * Utility for parsing and generating Markdown files with YAML frontmatter.
 *
 * <p>This utility can:
 * <ul>
 *   <li>Extract YAML frontmatter metadata and markdown content from text
 *   <li>Generate markdown files with YAML frontmatter from metadata and content
 * </ul>
 *
 * <p>Frontmatter format:
 * <pre>{@code
 * ---
 * name: example_skill
 * description: Example skill description
 * version: 1.0.0
 * ---
 * # Skill Content
 * This is the markdown content.
 * }</pre>
 *
 * <p>Usage example:
 * <pre>{@code
 * // Parse markdown with frontmatter
 * ParsedMarkdown parsed = MarkdownSkillParser.parse(markdownContent);
 * Map<String, Object> metadata = parsed.getMetadata();
 * String content = parsed.getContent();
 *
 * // Generate markdown with frontmatter
 * String markdown = MarkdownSkillParser.generate(metadata, content);
 * }</pre>
 */
public class MarkdownSkillParser {

    /**
     * Private constructor to prevent instantiation.
     */
    private MarkdownSkillParser() {}

    // Pattern to match frontmatter: starts with ---, ends with ---
    // Pattern explanation:
    // ^---          : frontmatter starts with --- at the beginning of the string
    // \\s*          : optional whitespace after opening ---
    // [\\r\\n]+     : one or more line breaks (handles \n, \r\n, \r)
    // (.*?)         : captured group - frontmatter content (non-greedy, can be empty)
    // [\\r\\n]*     : zero or more line breaks before closing ---
    // ---           : closing --- delimiter
    // (?:\\s*[\\r\\n]+)? : optional whitespace and line breaks after closing ---
    // (.*)          : captured group - remaining content (greedy)
    private static final Pattern FRONTMATTER_PATTERN =
            Pattern.compile(
                    "^---\\s*[\\r\\n]+(.*?)[\\r\\n]*---(?:\\s*[\\r\\n]+)?(.*)", Pattern.DOTALL);

    /**
     * Parse markdown content with YAML frontmatter.
     *
     * <p>Extracts both the YAML metadata and the markdown content.
     * If no frontmatter is found, returns empty metadata with the entire content.
     *
     * @param markdown Markdown content (may or may not have frontmatter)
     * @return ParsedMarkdown containing metadata and content
     * @throws IllegalArgumentException if YAML syntax is invalid
     */
    public static ParsedMarkdown parse(String markdown) {
        if (markdown == null || markdown.isEmpty()) {
            return new ParsedMarkdown(Map.of(), "");
        }

        Matcher matcher = FRONTMATTER_PATTERN.matcher(markdown);

        if (!matcher.matches()) {
            // No frontmatter found, treat entire content as markdown
            return new ParsedMarkdown(Map.of(), markdown);
        }

        String yamlContent = matcher.group(1).trim();
        String markdownContent = matcher.group(2);

        if (yamlContent.isEmpty()) {
            return new ParsedMarkdown(Map.of(), markdownContent);
        }

        try {
            Yaml yaml = new Yaml(new SafeConstructor(new LoaderOptions()));
            Object loaded = yaml.load(yamlContent);

            if (loaded == null) {
                return new ParsedMarkdown(Map.of(), markdownContent);
            }

            if (!(loaded instanceof Map)) {
                throw new IllegalArgumentException(
                        "YAML frontmatter must be a map, not a "
                                + loaded.getClass().getSimpleName());
            }

            @SuppressWarnings("unchecked")
            Map<String, Object> metadata = (Map<String, Object>) loaded;

            return new ParsedMarkdown(metadata, markdownContent);

        } catch (IllegalArgumentException e) {
            // Re-throw our own IllegalArgumentException
            throw e;
        } catch (RuntimeException e) {
            // Only catch YAML parsing related runtime exceptions
            throw new IllegalArgumentException("Invalid YAML frontmatter syntax", e);
        }
    }

    /**
     * Generate markdown content with YAML frontmatter.
     *
     * <p>Creates a markdown file with the metadata serialized as YAML frontmatter
     * at the beginning, followed by the content.
     *
     * @param metadata Metadata to serialize as YAML frontmatter (can be null or empty)
     * @param content Markdown content (can be null or empty)
     * @return Complete markdown with frontmatter
     */
    public static String generate(Map<String, Object> metadata, String content) {
        StringBuilder sb = new StringBuilder();

        // Add frontmatter if metadata exists
        if (metadata != null && !metadata.isEmpty()) {
            sb.append("---\n");

            DumperOptions options = new DumperOptions();
            options.setDefaultFlowStyle(DumperOptions.FlowStyle.BLOCK);
            options.setPrettyFlow(true);
            options.setIndent(2);

            Representer representer = new Representer(options);
            representer.setDefaultFlowStyle(DumperOptions.FlowStyle.BLOCK);

            Yaml yaml = new Yaml(representer, options);
            String yamlContent = yaml.dump(metadata);

            sb.append(yamlContent);

            // Ensure frontmatter ends properly
            if (!yamlContent.endsWith("\n")) {
                sb.append("\n");
            }
            sb.append("---\n");
        }

        // Add content
        if (content != null && !content.isEmpty()) {
            // Add a blank line between frontmatter and content if frontmatter exists
            if (metadata != null && !metadata.isEmpty()) {
                sb.append("\n");
            }
            sb.append(content);
        }

        return sb.toString();
    }

    /**
     * Result of parsing markdown with frontmatter.
     *
     * <p>Contains both the extracted metadata and the markdown content.
     */
    public static class ParsedMarkdown {
        private final Map<String, Object> metadata;
        private final String content;

        /**
         * Create a parsed markdown result.
         *
         * @param metadata YAML metadata (never null, can be empty)
         * @param content Markdown content (never null, can be empty)
         */
        public ParsedMarkdown(Map<String, Object> metadata, String content) {
            this.metadata =
                    metadata != null ? new LinkedHashMap<>(metadata) : new LinkedHashMap<>();
            this.content = content != null ? content : "";
        }

        /**
         * Get the metadata extracted from YAML frontmatter.
         *
         * @return Metadata map (never null, can be empty)
         */
        public Map<String, Object> getMetadata() {
            return new LinkedHashMap<>(metadata);
        }

        /**
         * Get the markdown content (without frontmatter).
         *
         * @return Markdown content (never null, can be empty)
         */
        public String getContent() {
            return content;
        }

        /**
         * Check if frontmatter exists.
         *
         * @return true if metadata is not empty
         */
        public boolean hasFrontmatter() {
            return !metadata.isEmpty();
        }

        @Override
        public String toString() {
            return String.format(
                    "ParsedMarkdown{metadata=%s, content='%s'}",
                    metadata, content.substring(0, Math.min(50, content.length())));
        }
    }
}

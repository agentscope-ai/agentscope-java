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
 * Map<String, String> metadata = parsed.getMetadata();
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
            Map<String, String> metadata = SimpleYamlParser.parse(yamlContent);
            return new ParsedMarkdown(metadata, markdownContent);
        } catch (IllegalArgumentException e) {
            throw e;
        } catch (RuntimeException e) {
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
    public static String generate(Map<String, String> metadata, String content) {
        StringBuilder sb = new StringBuilder();

        // Add frontmatter if metadata exists
        if (metadata != null && !metadata.isEmpty()) {
            sb.append("---\n");
            sb.append(SimpleYamlParser.generate(metadata));
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
     * Simple YAML parser for flat key-value structures.
     * Only supports String:String mappings.
     */
    private static class SimpleYamlParser {

        // Pattern to match key: value format
        // Captures: group(1) = key, group(2) = value (may include quotes)
        private static final Pattern KEY_VALUE_PATTERN =
                Pattern.compile("^([a-zA-Z_][a-zA-Z0-9_-]*)\\s*:\\s*(.*)$");

        /**
         * Parse YAML string into a map of key-value pairs.
         *
         * @param yaml YAML content to parse
         * @return Map of key-value pairs
         * @throws IllegalArgumentException if YAML syntax is invalid
         */
        static Map<String, String> parse(String yaml) {
            Map<String, String> result = new LinkedHashMap<>();

            if (yaml == null || yaml.isEmpty()) {
                return result;
            }

            String[] lines = yaml.split("[\\r\\n]+");

            for (String line : lines) {
                // Skip empty lines
                if (line.trim().isEmpty()) {
                    continue;
                }

                // Skip comments
                if (line.trim().startsWith("#")) {
                    continue;
                }

                Matcher matcher = KEY_VALUE_PATTERN.matcher(line.trim());
                if (!matcher.matches()) {
                    throw new IllegalArgumentException(
                            "Invalid YAML line (expected 'key: value' format): " + line);
                }

                String key = matcher.group(1);
                String value = parseValue(matcher.group(2));

                result.put(key, value);
            }

            return result;
        }

        /**
         * Parse a YAML value, handling quoted strings.
         *
         * @param rawValue Raw value string from YAML
         * @return Parsed value with quotes removed if present
         */
        private static String parseValue(String rawValue) {
            if (rawValue == null) {
                return "";
            }

            String value = rawValue.trim();

            if (value.isEmpty()) {
                return "";
            }

            // Handle double-quoted strings
            if (value.startsWith("\"") && value.endsWith("\"") && value.length() >= 2) {
                return unescapeString(value.substring(1, value.length() - 1));
            }

            // Handle single-quoted strings
            if (value.startsWith("'") && value.endsWith("'") && value.length() >= 2) {
                // Single-quoted strings don't process escapes, except '' for '
                return value.substring(1, value.length() - 1).replace("''", "'");
            }

            return value;
        }

        /**
         * Unescape a double-quoted YAML string.
         *
         * @param str String content without surrounding quotes
         * @return Unescaped string
         */
        private static String unescapeString(String str) {
            if (str == null || str.isEmpty()) {
                return str;
            }

            StringBuilder result = new StringBuilder();
            boolean escape = false;

            for (int i = 0; i < str.length(); i++) {
                char c = str.charAt(i);

                if (escape) {
                    switch (c) {
                        case 'n':
                            result.append('\n');
                            break;
                        case 't':
                            result.append('\t');
                            break;
                        case 'r':
                            result.append('\r');
                            break;
                        case '\\':
                            result.append('\\');
                            break;
                        case '"':
                            result.append('"');
                            break;
                        default:
                            result.append('\\').append(c);
                    }
                    escape = false;
                } else if (c == '\\') {
                    escape = true;
                } else {
                    result.append(c);
                }
            }

            // Handle trailing backslash
            if (escape) {
                result.append('\\');
            }

            return result.toString();
        }

        /**
         * Generate YAML string from a map of key-value pairs.
         *
         * @param map Map to serialize
         * @return YAML string
         */
        static String generate(Map<String, String> map) {
            if (map == null || map.isEmpty()) {
                return "";
            }

            StringBuilder sb = new StringBuilder();

            for (Map.Entry<String, String> entry : map.entrySet()) {
                String key = entry.getKey();
                String value = entry.getValue();

                sb.append(key).append(": ");

                if (value == null || value.isEmpty()) {
                    sb.append("");
                } else if (needsQuoting(value)) {
                    sb.append(quoteValue(value));
                } else {
                    sb.append(value);
                }

                sb.append("\n");
            }

            return sb.toString();
        }

        /**
         * Check if a value needs to be quoted in YAML.
         *
         * @param value Value to check
         * @return true if quoting is needed
         */
        private static boolean needsQuoting(String value) {
            if (value.isEmpty()) {
                return false;
            }

            // Quote if contains special characters
            if (value.contains(":")
                    || value.contains("#")
                    || value.contains("\n")
                    || value.contains("\r")
                    || value.contains("\t")) {
                return true;
            }

            // Quote if starts/ends with whitespace
            if (Character.isWhitespace(value.charAt(0))
                    || Character.isWhitespace(value.charAt(value.length() - 1))) {
                return true;
            }

            // Quote if starts with special YAML characters
            char first = value.charAt(0);
            if (first == '"'
                    || first == '\''
                    || first == '['
                    || first == ']'
                    || first == '{'
                    || first == '}'
                    || first == '>'
                    || first == '|'
                    || first == '*'
                    || first == '&'
                    || first == '!'
                    || first == '%'
                    || first == '@'
                    || first == '`') {
                return true;
            }

            return false;
        }

        /**
         * Quote a value for YAML output using double quotes.
         *
         * @param value Value to quote
         * @return Quoted and escaped value
         */
        private static String quoteValue(String value) {
            StringBuilder sb = new StringBuilder();
            sb.append('"');

            for (int i = 0; i < value.length(); i++) {
                char c = value.charAt(i);
                switch (c) {
                    case '"':
                        sb.append("\\\"");
                        break;
                    case '\\':
                        sb.append("\\\\");
                        break;
                    case '\n':
                        sb.append("\\n");
                        break;
                    case '\r':
                        sb.append("\\r");
                        break;
                    case '\t':
                        sb.append("\\t");
                        break;
                    default:
                        sb.append(c);
                }
            }

            sb.append('"');
            return sb.toString();
        }
    }

    /**
     * Result of parsing markdown with frontmatter.
     *
     * <p>Contains both the extracted metadata and the markdown content.
     */
    public static class ParsedMarkdown {
        private final Map<String, String> metadata;
        private final String content;

        /**
         * Create a parsed markdown result.
         *
         * @param metadata YAML metadata (never null, can be empty)
         * @param content Markdown content (never null, can be empty)
         */
        public ParsedMarkdown(Map<String, String> metadata, String content) {
            this.metadata =
                    metadata != null ? new LinkedHashMap<>(metadata) : new LinkedHashMap<>();
            this.content = content != null ? content : "";
        }

        /**
         * Get the metadata extracted from YAML frontmatter.
         *
         * @return Metadata map (never null, can be empty)
         */
        public Map<String, String> getMetadata() {
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

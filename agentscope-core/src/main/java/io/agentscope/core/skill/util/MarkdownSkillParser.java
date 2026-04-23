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

import java.lang.reflect.Array;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
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

    private static final int FRONTMATTER_CODE_POINT_LIMIT = 16_384;

    private static final Logger logger = LoggerFactory.getLogger(MarkdownSkillParser.class);

    private static final Pattern FRONTMATTER_PATTERN =
            Pattern.compile(
                    "^---\\s*[\\r\\n]+(.*?)[\\r\\n]*---(?:\\s*[\\r\\n]+)?(.*)", Pattern.DOTALL);

    private static final LoaderOptions LOADER_OPTIONS = createLoaderOptions();

    private static final DumperOptions DUMPER_OPTIONS = createDumperOptions();

    /**
     * Private constructor to prevent instantiation.
     */
    private MarkdownSkillParser() {}

    /**
     * Parse markdown content with YAML frontmatter.
     *
     * <p>Extracts both the YAML metadata and the markdown content.
     * If no frontmatter is found, returns empty metadata with the entire content.
     *
     * @param markdown Markdown content (may or may not have frontmatter)
     * @return ParsedMarkdown containing metadata and content
     */
    public static ParsedMarkdown parse(String markdown) {
        if (markdown == null || markdown.isEmpty()) {
            return new ParsedMarkdown(Map.of(), "");
        }

        Matcher matcher = FRONTMATTER_PATTERN.matcher(markdown);

        if (!matcher.matches()) {
            return new ParsedMarkdown(Map.of(), markdown);
        }

        String yamlContent = matcher.group(1).trim();
        String markdownContent = matcher.group(2);

        if (yamlContent.isEmpty()) {
            return new ParsedMarkdown(Map.of(), markdownContent);
        }

        return new ParsedMarkdown(parseYamlMetadata(yamlContent), markdownContent);
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

        if (metadata != null && !metadata.isEmpty()) {
            sb.append("---\n");
            sb.append(createDumperYaml().dump(metadata));
            sb.append("---\n");
        }

        if (content != null && !content.isEmpty()) {
            if (metadata != null && !metadata.isEmpty()) {
                sb.append("\n");
            }
            sb.append(content);
        }

        return sb.toString();
    }

    private static Map<String, Object> parseYamlMetadata(String yamlContent) {
        if (yamlContent.codePointCount(0, yamlContent.length()) > FRONTMATTER_CODE_POINT_LIMIT) {
            logger.debug(
                    "Skipping YAML frontmatter because it exceeds the code point limit: {}",
                    FRONTMATTER_CODE_POINT_LIMIT);
            return Map.of();
        }

        Object loaded;
        try {
            loaded = createParserYaml().load(yamlContent);
        } catch (RuntimeException e) {
            logger.debug("Failed to parse YAML frontmatter, returning empty metadata", e);
            return Map.of();
        }

        if (loaded == null) {
            return Map.of();
        }

        if (!(loaded instanceof Map<?, ?> rawMap)) {
            logger.debug(
                    "Skipping YAML frontmatter because top-level object is not a map: {}",
                    loaded.getClass());
            return Map.of();
        }

        LinkedHashMap<String, Object> metadata = new LinkedHashMap<>();
        for (Map.Entry<?, ?> entry : rawMap.entrySet()) {
            Object key = entry.getKey();
            if (!(key instanceof String stringKey)) {
                logger.debug("Skipping YAML metadata entry with non-string key: {}", key);
                continue;
            }

            metadata.put(stringKey, normalizeMetadataValue(entry.getValue()));
        }
        return metadata;
    }

    private static LoaderOptions createLoaderOptions() {
        LoaderOptions options = new LoaderOptions();
        options.setAllowDuplicateKeys(false);
        options.setMaxAliasesForCollections(10);
        options.setNestingDepthLimit(10);
        options.setCodePointLimit(10_000);
        return options;
    }

    private static DumperOptions createDumperOptions() {
        DumperOptions options = new DumperOptions();
        options.setDefaultFlowStyle(DumperOptions.FlowStyle.BLOCK);
        options.setPrettyFlow(true);
        options.setSplitLines(false);
        return options;
    }

    private static Yaml createParserYaml() {
        return new Yaml(new SafeConstructor(LOADER_OPTIONS));
    }

    private static Yaml createDumperYaml() {
        return new Yaml(new Representer(DUMPER_OPTIONS), DUMPER_OPTIONS);
    }

    /**
     * Normalizes YAML parser output into a stable metadata tree.
     *
     * <p>Why: {@link SafeConstructor} keeps parsing safe, but it still returns broad container
     * types like {@code Map<?, ?>}, arbitrary {@link Collection} implementations, and arrays.
     * The rest of the skill pipeline needs a predictable shape so nested metadata can be preserved
     * and later rendered as XML without losing structure.
     *
     * <p>How: this method recursively rewrites nested values into three forms only:
     * <ul>
     *   <li>scalar values are kept as-is</li>
     *   <li>maps become {@code Map<String, Object>}</li>
     *   <li>collections and arrays become {@code List<Object>}</li>
     * </ul>
     */
    private static Object normalizeMetadataValue(Object value) {
        if (value == null) {
            return null;
        }

        if (value instanceof Map<?, ?> rawMap) {
            LinkedHashMap<String, Object> normalized = new LinkedHashMap<>();
            for (Map.Entry<?, ?> entry : rawMap.entrySet()) {
                Object key = entry.getKey();
                String normalizedKey = key instanceof String ? (String) key : String.valueOf(key);
                normalized.put(normalizedKey, normalizeMetadataValue(entry.getValue()));
            }
            return normalized;
        }

        if (value instanceof Collection<?> collection) {
            List<Object> normalized = new ArrayList<>(collection.size());
            for (Object item : collection) {
                normalized.add(normalizeMetadataValue(item));
            }
            return normalized;
        }

        if (value.getClass().isArray()) {
            int length = Array.getLength(value);
            List<Object> normalized = new ArrayList<>(length);
            for (int i = 0; i < length; i++) {
                normalized.add(normalizeMetadataValue(Array.get(value, i)));
            }
            return normalized;
        }

        return value;
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
                    metadata != null
                            ? Collections.unmodifiableMap(new LinkedHashMap<>(metadata))
                            : Collections.emptyMap();
            this.content = content != null ? content : "";
        }

        /**
         * Get the metadata extracted from YAML frontmatter.
         *
         * @return Metadata map (never null, can be empty)
         */
        public Map<String, Object> getMetadata() {
            return metadata;
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

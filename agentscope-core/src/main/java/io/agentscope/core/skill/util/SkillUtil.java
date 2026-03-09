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
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.Map;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

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

    private static final String SKILL_FILE_NAME = "SKILL.md";
    private static final String DEFAULT_SOURCE = "custom";

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
        return createFrom(skillMd, resources, DEFAULT_SOURCE);
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

    /**
     * Creates an AgentSkill from a skill package zip.
     *
     * <p>The package must contain a {@value #SKILL_FILE_NAME} entry and any optional resource
     * files. The .skill and .zip extensions are aliases for the same package format.
     *
     * @param zipBytes Zip content as bytes
     * @return Created AgentSkill instance
     * @throws IllegalArgumentException if zipBytes is null/empty, if no SKILL.md is found, or if
     *                                  zip entries are invalid
     */
    public static AgentSkill createFromZip(byte[] zipBytes) {
        return createFromZip(zipBytes, DEFAULT_SOURCE);
    }

    /**
     * Creates an AgentSkill from a skill package zip file path.
     *
     * <p>The package must contain a {@value #SKILL_FILE_NAME} entry and any optional resource
     * files. The .skill and .zip extensions are aliases for the same package format.
     *
     * @param zipPath Zip file path
     * @return Created AgentSkill instance
     * @throws IllegalArgumentException if zipPath is null
     * @throws RuntimeException if the zip file cannot be read
     */
    public static AgentSkill createFromZip(Path zipPath) {
        return createFromZip(zipPath, DEFAULT_SOURCE);
    }

    /**
     * Creates an AgentSkill from a skill package zip input stream.
     *
     * <p>The package must contain a {@value #SKILL_FILE_NAME} entry and any optional resource
     * files. The .skill and .zip extensions are aliases for the same package format.
     *
     * @param zipStream Zip content stream
     * @return Created AgentSkill instance
     * @throws IllegalArgumentException if zipStream is null
     */
    public static AgentSkill createFromZip(InputStream zipStream) {
        return createFromZip(zipStream, DEFAULT_SOURCE);
    }

    /**
     * Creates an AgentSkill from a skill package zip with custom source.
     *
     * <p>The package must contain a {@value #SKILL_FILE_NAME} entry and any optional resource
     * files. The .skill and .zip extensions are aliases for the same package format.
     *
     * @param zipBytes Zip content as bytes
     * @param source Source identifier for the skill (null defaults to "custom")
     * @return Created AgentSkill instance
     * @throws IllegalArgumentException if zipBytes is null/empty, if no SKILL.md is found, or if
     *                                  zip entries are invalid
     */
    public static AgentSkill createFromZip(byte[] zipBytes, String source) {
        if (zipBytes == null || zipBytes.length == 0) {
            throw new IllegalArgumentException("Zip content cannot be null or empty.");
        }
        return createFromZip(new ByteArrayInputStream(zipBytes), source);
    }

    /**
     * Creates an AgentSkill from a skill package zip file path with custom source.
     *
     * <p>The package must contain a {@value #SKILL_FILE_NAME} entry and any optional resource
     * files. The .skill and .zip extensions are aliases for the same package format.
     *
     * @param zipPath Zip file path
     * @param source Source identifier for the skill (null defaults to "custom")
     * @return Created AgentSkill instance
     * @throws IllegalArgumentException if zipPath is null
     * @throws RuntimeException if the zip file cannot be read
     */
    public static AgentSkill createFromZip(Path zipPath, String source) {
        if (zipPath == null) {
            throw new IllegalArgumentException("Zip path cannot be null.");
        }
        try (InputStream inputStream = Files.newInputStream(zipPath)) {
            return createFromZip(inputStream, source);
        } catch (IOException e) {
            throw new RuntimeException("Failed to read skill zip content.", e);
        }
    }

    /**
     * Creates an AgentSkill from a skill package zip input stream with custom source.
     *
     * <p>The package must contain a {@value #SKILL_FILE_NAME} entry and any optional resource
     * files. The .skill and .zip extensions are aliases for the same package format.
     *
     * @param zipStream Zip content stream
     * @param source Source identifier for the skill (null defaults to "custom")
     * @return Created AgentSkill instance
     * @throws IllegalArgumentException if zipStream is null
     * @throws RuntimeException if the zip content cannot be read
     */
    public static AgentSkill createFromZip(InputStream zipStream, String source) {
        if (zipStream == null) {
            throw new IllegalArgumentException("Zip stream cannot be null.");
        }

        String skillEntryName = null;
        String skillMdContent = null;
        String rootDir = null;
        Map<String, String> resources = new HashMap<>();

        try (ZipInputStream zipInputStream = new ZipInputStream(zipStream)) {
            ZipEntry entry;
            while ((entry = zipInputStream.getNextEntry()) != null) {
                if (entry.isDirectory()) {
                    continue;
                }
                String entryName = normalizeZipEntryName(entry.getName());
                int separatorIndex = entryName.indexOf('/');
                if (separatorIndex <= 0) {
                    throw new IllegalArgumentException(
                            "Zip entries must be under a single root directory.");
                }
                String entryRoot = entryName.substring(0, separatorIndex);
                if (rootDir == null) {
                    rootDir = entryRoot;
                } else if (!rootDir.equals(entryRoot)) {
                    throw new IllegalArgumentException(
                            "Zip entries must share the same root directory.");
                }
                String content = readZipEntryContent(zipInputStream);

                String expectedSkillEntry = entryRoot + "/" + SKILL_FILE_NAME;
                if (entryName.endsWith("/" + SKILL_FILE_NAME)
                        && !entryName.equals(expectedSkillEntry)) {
                    throw new IllegalArgumentException(
                            "SKILL.md must be located directly under the root directory.");
                }
                if (entryName.equals(expectedSkillEntry)) {
                    if (skillEntryName != null && !skillEntryName.equals(entryName)) {
                        throw new IllegalArgumentException(
                                "Multiple SKILL.md entries found in zip content.");
                    }
                    skillEntryName = entryName;
                    skillMdContent = content;
                    continue;
                }

                String rootPrefix = rootDir + "/";
                if (!entryName.startsWith(rootPrefix)) {
                    throw new IllegalArgumentException(
                            "Zip entries must share the same root directory as SKILL.md.");
                }
                String resourceName = entryName.substring(rootPrefix.length());
                resources.put(resourceName, content);
            }
        } catch (IOException e) {
            throw new RuntimeException("Failed to read skill zip content.", e);
        }

        if (skillEntryName == null) {
            throw new IllegalArgumentException("SKILL.md not found in zip content.");
        }

        return createFrom(skillMdContent, resources, source);
    }

    private static String normalizeZipEntryName(String entryName) {
        if (entryName == null || entryName.isEmpty()) {
            throw new IllegalArgumentException("Zip entry name cannot be null or empty.");
        }
        String normalized = entryName.replace('\\', '/');
        if (normalized.startsWith("/")) {
            throw new IllegalArgumentException("Zip entry name must be a relative path.");
        }
        String[] segments = normalized.split("/");
        for (String segment : segments) {
            if ("..".equals(segment)) {
                throw new IllegalArgumentException(
                        "Zip entry name must not contain parent directory segments.");
            }
        }
        return normalized;
    }

    private static String readZipEntryContent(ZipInputStream zipInputStream) throws IOException {
        byte[] buffer = new byte[8192];
        int read;
        try (ByteArrayOutputStream outputStream = new ByteArrayOutputStream()) {
            while ((read = zipInputStream.read(buffer)) != -1) {
                outputStream.write(buffer, 0, read);
            }
            return outputStream.toString(StandardCharsets.UTF_8);
        }
    }
}

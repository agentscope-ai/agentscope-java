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
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import java.util.regex.Pattern;
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
 * String skillMd = "---\nname: my-skill\ndescription: Does something\n---\nContent here";
 * Map<String, String> resources = Map.of("file1.txt", "content1");
 * AgentSkill skill = SkillUtil.createFrom(skillMd, resources);
 *
 * // Create skill from markdown with custom source
 * AgentSkill skill2 = SkillUtil.createFrom(skillMd, resources, "github");
 * }</pre>
 *
 */
public class SkillUtil {

    private static final String SKILL_FILE_NAME = "SKILL.md";
    private static final String DEFAULT_SOURCE = "custom";

    /**
     * Maximum length of a skill name, following the Agent Skills ecosystem standard.
     */
    public static final int MAX_SKILL_NAME_LENGTH = 64;

    /**
     * Pattern a skill name must match: starts with a lowercase letter or digit, then lowercase
     * letters, digits and dashes only. Underscores are excluded because they collide with the
     * {@code name_source} skill-id separator.
     */
    public static final Pattern VALID_SKILL_NAME_PATTERN = Pattern.compile("^[a-z0-9][a-z0-9-]*$");

    /**
     * Private constructor to prevent instantiation.
     */
    private SkillUtil() {}

    /**
     * Checks whether the given skill name is non-null, matches {@link #VALID_SKILL_NAME_PATTERN},
     * and is at most {@link #MAX_SKILL_NAME_LENGTH} characters long.
     *
     * @param name the skill name to validate (may be {@code null})
     * @return {@code true} when the name is valid, {@code false} otherwise
     */
    public static boolean isValidSkillName(String name) {
        return name != null
                && name.length() <= MAX_SKILL_NAME_LENGTH
                && VALID_SKILL_NAME_PATTERN.matcher(name).matches();
    }

    /**
     * Fails fast when the given skill name is invalid. Shared by all write boundaries so the
     * rule set and rejection message stay identical.
     *
     * @param name the skill name to validate
     * @throws IllegalArgumentException when the name is null, does not match {@link
     *     #VALID_SKILL_NAME_PATTERN}, or exceeds {@link #MAX_SKILL_NAME_LENGTH}
     */
    public static void requireValidSkillName(String name) {
        if (!isValidSkillName(name)) {
            throw new IllegalArgumentException(
                    "Invalid skill name '"
                            + name
                            + "': names must match "
                            + VALID_SKILL_NAME_PATTERN.pattern()
                            + " and be at most "
                            + MAX_SKILL_NAME_LENGTH
                            + " characters");
        }
    }

    /**
     * Resolves {@code relativeSegment} under {@code baseDir} and returns the normalized result
     * only when it stays inside {@code baseDir}; an empty result means "refuse to write".
     *
     * <p>Normalization before the containment check is essential: {@code Path.resolve} replaces
     * the base entirely for absolute segments, and {@code ..} segments can cancel their way out.
     * Skill write paths (including the local mirror writer) must reuse this method instead of
     * re-implementing the check.
     *
     * @param baseDir the directory the resolved path must stay inside
     * @param relativeSegment the unresolved segment (skill id or resource relative path)
     * @return the normalized contained path, or {@code Optional.empty()} when it escapes {@code
     *     baseDir}
     */
    public static Optional<Path> resolveContainedPath(Path baseDir, String relativeSegment) {
        Path resolved = baseDir.resolve(relativeSegment).normalize();
        return resolved.startsWith(baseDir) ? Optional.of(resolved) : Optional.empty();
    }

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
        Map<String, Object> metadata = parsed.getMetadata();

        String name = stringifyRequiredMetadata(metadata, "name");
        String description = stringifyRequiredMetadata(metadata, "description");
        metadata = new LinkedHashMap<>(metadata);
        metadata.put("name", name);
        metadata.put("description", description);
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

        return new AgentSkill(metadata, skillContent, resources, source);
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
        return createFromZip(zipBytes, DEFAULT_SOURCE, StandardCharsets.UTF_8);
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
        return createFromZip(zipPath, DEFAULT_SOURCE, StandardCharsets.UTF_8);
    }

    /**
     * Creates an AgentSkill from a skill package zip file path using the given charset.
     *
     * <p>The package must contain a {@value #SKILL_FILE_NAME} entry and any optional resource
     * files. The .skill and .zip extensions are aliases for the same package format.
     *
     * @param zipPath Zip file path
     * @param charset Charset used to decode zip entry names and file contents (null defaults to
     *                UTF-8). Use this when the zip was created with a non-UTF-8 charset such as
     *                GBK/GB18030 and the default overload fails during zip entry decoding with
     *                errors like {@code malformed input}
     * @return Created AgentSkill instance
     */
    public static AgentSkill createFromZip(Path zipPath, Charset charset) {
        return createFromZip(zipPath, DEFAULT_SOURCE, charset);
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
        return createFromZip(zipStream, DEFAULT_SOURCE, StandardCharsets.UTF_8);
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
        return createFromZip(zipBytes, source, StandardCharsets.UTF_8);
    }

    /**
     * Creates an AgentSkill from a skill package zip with custom source and charset.
     *
     * <p>The package must contain a {@value #SKILL_FILE_NAME} entry and any optional resource
     * files. The .skill and .zip extensions are aliases for the same package format.
     *
     * @param zipBytes Zip content as bytes
     * @param source Source identifier for the skill (null defaults to "custom")
     * @param charset Charset used to decode zip entry names and file contents (null defaults to
     *                UTF-8). Use this when the zip was created with a non-UTF-8 charset such as
     *                GBK/GB18030 and the default overload fails during zip entry decoding with
     *                errors like {@code malformed input}
     * @return Created AgentSkill instance
     * @throws IllegalArgumentException if zipBytes is null/empty, if no SKILL.md is found, or if
     *                                  zip entries are invalid
     */
    public static AgentSkill createFromZip(byte[] zipBytes, String source, Charset charset) {
        if (zipBytes == null || zipBytes.length == 0) {
            throw new IllegalArgumentException("Zip content cannot be null or empty.");
        }
        return createFromZip(new ByteArrayInputStream(zipBytes), source, charset);
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
        return createFromZip(zipPath, source, StandardCharsets.UTF_8);
    }

    /**
     * Creates an AgentSkill from a skill package zip file path with custom source and charset.
     *
     * <p>The package must contain a {@value #SKILL_FILE_NAME} entry and any optional resource
     * files. The .skill and .zip extensions are aliases for the same package format.
     *
     * @param zipPath Zip file path
     * @param source Source identifier for the skill (null defaults to "custom")
     * @param charset Charset used to decode zip entry names and file contents (null defaults to
     *                UTF-8). Use this when the zip was created with a non-UTF-8 charset such as
     *                GBK/GB18030 and the default overload fails during zip entry decoding with
     *                errors like {@code malformed input}
     * @return Created AgentSkill instance
     * @throws IllegalArgumentException if zipPath is null
     * @throws RuntimeException if the zip file cannot be read
     */
    public static AgentSkill createFromZip(Path zipPath, String source, Charset charset) {
        if (zipPath == null) {
            throw new IllegalArgumentException("Zip path cannot be null.");
        }
        try (InputStream inputStream = Files.newInputStream(zipPath)) {
            return createFromZip(inputStream, source, charset);
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
        return createFromZip(zipStream, source, StandardCharsets.UTF_8);
    }

    /**
     * Creates an AgentSkill from a skill package zip input stream with custom source and charset.
     *
     * <p>The package must contain a {@value #SKILL_FILE_NAME} entry and any optional resource
     * files. The .skill and .zip extensions are aliases for the same package format.
     *
     * @param zipStream Zip content stream
     * @param source Source identifier for the skill (null defaults to "custom")
     * @param charset Charset used to decode zip entry names and file contents (null defaults to
     *                UTF-8). Use this when the zip was created with a non-UTF-8 charset such as
     *                GBK/GB18030 and the default overload fails during zip entry decoding with
     *                errors like {@code malformed input}
     * @return Created AgentSkill instance
     * @throws IllegalArgumentException if zipStream is null
     * @throws RuntimeException if the zip content cannot be read
     */
    public static AgentSkill createFromZip(InputStream zipStream, String source, Charset charset) {
        if (zipStream == null) {
            throw new IllegalArgumentException("Zip stream cannot be null.");
        }

        Charset zipCharset = charset == null ? StandardCharsets.UTF_8 : charset;
        String skillEntryName = null;
        String skillMdContent = null;
        String rootDir = null;
        Map<String, String> resources = new HashMap<>();

        try (ZipInputStream zipInputStream = new ZipInputStream(zipStream, zipCharset)) {
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
                String content = readZipEntryContent(zipInputStream, zipCharset);

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

    private static String readZipEntryContent(ZipInputStream zipInputStream, Charset charset)
            throws IOException {
        byte[] buffer = new byte[8192];
        int read;
        try (ByteArrayOutputStream outputStream = new ByteArrayOutputStream()) {
            while ((read = zipInputStream.read(buffer)) != -1) {
                outputStream.write(buffer, 0, read);
            }
            return outputStream.toString(charset);
        }
    }

    private static String stringifyRequiredMetadata(Map<String, Object> metadata, String key) {
        if (metadata == null) {
            return null;
        }

        Object value = metadata.get(key);
        if (!(value instanceof String stringValue)) {
            return null;
        }

        return stringValue.isBlank() ? null : stringValue;
    }
}

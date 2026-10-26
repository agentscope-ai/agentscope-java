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
import java.io.IOException;
import java.nio.charset.MalformedInputException;
import java.nio.charset.StandardCharsets;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.stream.Stream;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Utility class for file system operations related to skills.
 *
 * <p>This class provides reusable operations for loading, listing, saving, and
 * deleting skills on the file system. It is designed to be shared by multiple
 * repositories such as file system and Git based repositories.
 *
 */
public final class SkillFileSystemHelper {

    private static final Logger logger = LoggerFactory.getLogger(SkillFileSystemHelper.class);
    private static final String SKILL_FILE_NAME = "SKILL.md";

    private SkillFileSystemHelper() {}

    /**
     * Loads a skill by name from the given base directory.
     *
     * @param baseDir The base directory containing skill folders
     * @param skillName The skill name to load
     * @param source The source identifier for the created skill
     * @return The loaded AgentSkill
     * @throws IllegalArgumentException if the skill cannot be found or is invalid
     */
    public static AgentSkill loadSkill(Path baseDir, String skillName, String source) {
        Path skillDir =
                findSkillDirectoryByName(baseDir, skillName)
                        .orElseThrow(
                                () ->
                                        new IllegalArgumentException(
                                                "Skill directory does not exist for skill name: "
                                                        + skillName));
        return loadSkillFromDirectory(skillDir, source);
    }

    /**
     * Loads a skill from a specific directory, eagerly reading every non-hidden file in the
     * skill tree into memory. Equivalent to {@link #loadSkillFromDirectory(Path, String, boolean)
     * loadSkillFromDirectory(skillDir, source, true)}.
     */
    public static AgentSkill loadSkillFromDirectory(Path skillDir, String source) {
        return loadSkillFromDirectory(skillDir, source, true);
    }

    /**
     * Loads a skill from a specific directory.
     *
     * <p>The returned skill always carries an absolute {@code originDir} matching {@code skillDir},
     * so callers (prompt provider, load tool) can address files on disk without a second copy.
     *
     * @param skillDir The directory containing SKILL.md
     * @param source The source identifier for the created skill
     * @param includeResources when {@code true}, all non-hidden files under {@code skillDir} are
     *     pre-loaded into the skill's in-memory resource map (legacy behavior). When {@code false},
     *     only SKILL.md is read; callers fall back to disk through {@code originDir} on demand.
     * @return The loaded AgentSkill (with {@code originDir} populated)
     * @throws IllegalArgumentException if SKILL.md is missing or invalid
     */
    public static AgentSkill loadSkillFromDirectory(
            Path skillDir, String source, boolean includeResources) {
        if (!Files.exists(skillDir)) {
            throw new IllegalArgumentException("Skill directory does not exist: " + skillDir);
        }

        if (!Files.isDirectory(skillDir)) {
            throw new IllegalArgumentException("Skill path is not a directory: " + skillDir);
        }

        Path skillFile = skillDir.resolve(SKILL_FILE_NAME);
        if (!Files.exists(skillFile)) {
            throw new IllegalArgumentException(
                    "SKILL.md not found in skill directory: " + skillDir);
        }

        try {
            String skillMdContent = Files.readString(skillFile, StandardCharsets.UTF_8);
            Map<String, String> resources =
                    includeResources ? loadResources(skillDir, skillFile) : null;
            AgentSkill base = SkillUtil.createFrom(skillMdContent, resources, source);
            return base.toBuilder().originDir(skillDir.toAbsolutePath().normalize()).build();
        } catch (IOException e) {
            throw new RuntimeException("Failed to load skill from: " + skillDir, e);
        }
    }

    /**
     * Size limit for inlining a resource into a tool result. Resources above this limit are
     * not loaded into the conversation context; the load tool instead returns the file path
     * and points the model at shell/read_file. Guards against a single large asset (e.g. a
     * base64-encoded document template) blowing up both the context window and the heap.
     */
    public static final long MAX_INLINE_RESOURCE_BYTES = 512 * 1024;

    /**
     * Thrown by the read helpers when a resource exceeds {@link
     * #MAX_INLINE_RESOURCE_BYTES}; callers turn it into a path hint for the model.
     */
    public static final class ResourceTooLargeException extends RuntimeException {
        /** Relative path of the resource inside the skill's origin directory. */
        public final String relPath;

        /** Absolute path of the file, for the hint message to reference. */
        public final Path file;

        /** Size of the file in bytes. */
        public final long sizeBytes;

        public ResourceTooLargeException(String relPath, Path file, long sizeBytes) {
            super("resource too large: " + relPath + " (" + sizeBytes + " bytes)");
            this.relPath = relPath;
            this.file = file;
            this.sizeBytes = sizeBytes;
        }
    }

    /**
     * Loads a skill head-only: SKILL.md is read transiently to parse name/description, the
     * body is dropped immediately, and the returned {@link AgentSkill} carries metadata only
     * (body and resources are read from disk on demand via {@code originDir}).
     *
     * <p>Registration paths (getAllSkills) use this instead of the full load, cutting the
     * resident cost of a skill from "body + every resource (potentially MBs)" down to its
     * metadata (~KBs).
     *
     * @param skillDir The directory containing SKILL.md
     * @param source The source identifier for the created skill
     * @return head-only AgentSkill whose {@code originDir} points to {@code skillDir}
     */
    public static AgentSkill loadSkillHead(Path skillDir, String source) {
        Path skillFile = requireSkillFile(skillDir);
        try {
            String skillMdContent = Files.readString(skillFile, StandardCharsets.UTF_8);
            ParsedMarkdown parsed = MarkdownSkillParser.parse(skillMdContent);
            Map<String, Object> metadata = parsed.getMetadata();
            if (parsed.getContent() == null || parsed.getContent().isEmpty()) {
                throw new IllegalArgumentException(
                        "The SKILL.md must have content except for the YAML Front Matter.");
            }
            return AgentSkill.headOf(
                    metadata,
                    source,
                    skillDir.toAbsolutePath().normalize(),
                    sha256Hex(skillMdContent));
        } catch (IOException e) {
            throw new RuntimeException("Failed to load skill head from: " + skillDir, e);
        }
    }

    /**
     * Head-only load resolving the skill directory by name, for the getSkill fallback path;
     * same semantics as {@link #loadSkillHead(Path, String)}.
     */
    public static AgentSkill loadSkillHead(Path baseDir, String skillName, String source) {
        Path skillDir =
                findSkillDirectoryByName(baseDir, skillName)
                        .orElseThrow(
                                () ->
                                        new IllegalArgumentException(
                                                "Skill directory does not exist for skill name: "
                                                        + skillName));
        return loadSkillHead(skillDir, source);
    }

    /**
     * Loads every skill under the directory head-only: only SKILL.md metadata is parsed,
     * bodies and resources stay on disk. The registration-time slim variant of {@link
     * #getAllSkills(Path, String)}.
     */
    public static List<AgentSkill> getAllSkillHeads(Path baseDir, String source) {
        List<AgentSkill> skills = new ArrayList<>();
        try (Stream<Path> subdirs = Files.list(baseDir)) {
            subdirs.filter(Files::isDirectory)
                    .forEach(
                            dir -> {
                                if (hasSkillFile(dir)) {
                                    try {
                                        skills.add(loadSkillHead(dir, source));
                                    } catch (Exception e) {
                                        logger.warn(
                                                "Failed to load skill head from '{}': {}",
                                                dir,
                                                e.getMessage(),
                                                e);
                                    }
                                }
                            });
        } catch (IOException e) {
            throw new RuntimeException("Failed to list skill directories", e);
        }
        return skills;
    }

    /**
     * Reads the skill's full SKILL.md (frontmatter + body) from disk on demand.
     *
     * <p>Shared channel for head-only skills: load_skill("SKILL.md") and any flow that
     * needs the body (promotion, write-back). Returns the original bytes rather than a
     * reassembled document so the content stays byte-identical with skill_manage patches.
     *
     * @param originDir the skill's origin directory
     * @return the SKILL.md content as written on disk
     */
    public static String readFullMarkdownFromDir(Path originDir) {
        try {
            return Files.readString(requireSkillFile(originDir), StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new RuntimeException("Failed to read SKILL.md from: " + originDir, e);
        }
    }

    /**
     * Reads a skill resource file from disk on demand (the lazy channel behind
     * load_skill_through_path).
     *
     * <p>Text is returned as UTF-8; binaries follow the {@code base64:} prefix convention,
     * matching the semantics of the eager preloaded resource map. Files larger than {@link
     * #MAX_INLINE_RESOURCE_BYTES} raise {@link ResourceTooLargeException}, which the caller
     * converts into a path hint.
     *
     * @param originDir the skill's origin directory
     * @param relPath relative path (absolute paths and ".." are rejected)
     * @return the content, or empty when the file is missing/unreadable or the path is
     *     invalid
     */
    public static Optional<String> readResourceFromDir(Path originDir, String relPath) {
        if (relPath == null || relPath.isBlank()) {
            return Optional.empty();
        }
        String normalized = relPath.replace('\\', '/');
        if (normalized.startsWith("/") || normalized.contains("..")) {
            return Optional.empty();
        }
        if (SKILL_FILE_NAME.equals(normalized)) {
            return Optional.of(readFullMarkdownFromDir(originDir));
        }
        Path target = originDir.resolve(normalized).normalize();
        if (!target.startsWith(originDir.normalize())) {
            return Optional.empty();
        }
        if (!Files.isRegularFile(target) || !Files.isReadable(target)) {
            return Optional.empty();
        }
        try {
            long size = Files.size(target);
            if (size > MAX_INLINE_RESOURCE_BYTES) {
                throw new ResourceTooLargeException(normalized, target, size);
            }
            String content = Files.readString(target, StandardCharsets.UTF_8);
            return Optional.of(content);
        } catch (MalformedInputException e) {
            try {
                byte[] bytes = Files.readAllBytes(target);
                if (bytes.length > MAX_INLINE_RESOURCE_BYTES) {
                    throw new ResourceTooLargeException(normalized, target, bytes.length);
                }
                return Optional.of("base64:" + Base64.getEncoder().encodeToString(bytes));
            } catch (IOException ex) {
                logger.warn("Failed to read binary resource: {}", target, ex);
                return Optional.empty();
            }
        } catch (IOException e) {
            logger.warn("Failed to read resource: {}", target, e);
            return Optional.empty();
        }
    }

    /**
     * Lists the relative paths under a skill directory that can serve as resources
     * (SKILL.md and hidden files excluded), for the load tool's not-found listing and for
     * the visible-skill-set fingerprint.
     */
    public static List<String> listResourceRelPaths(Path skillDir) {
        List<String> out = new ArrayList<>();
        try (Stream<Path> walk = Files.walk(skillDir)) {
            walk.filter(Files::isRegularFile)
                    .filter(p -> !p.getFileName().toString().startsWith("."))
                    .filter(p -> !p.getFileName().toString().equals(SKILL_FILE_NAME))
                    .forEach(p -> out.add(skillDir.relativize(p).toString().replace('\\', '/')));
        } catch (IOException e) {
            logger.warn("Failed to list resources of: {}", skillDir, e);
        }
        out.sort(Comparator.naturalOrder());
        return out;
    }

    private static String sha256Hex(String content) {
        try {
            java.security.MessageDigest md = java.security.MessageDigest.getInstance("SHA-256");
            byte[] d = md.digest(content.getBytes(StandardCharsets.UTF_8));
            StringBuilder hex = new StringBuilder(d.length * 2);
            for (byte b : d) {
                hex.append(String.format("%02x", b));
            }
            return hex.toString();
        } catch (java.security.NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 unavailable", e);
        }
    }

    private static Path requireSkillFile(Path skillDir) {
        if (!Files.isDirectory(skillDir)) {
            throw new IllegalArgumentException("Skill directory does not exist: " + skillDir);
        }
        Path skillFile = skillDir.resolve(SKILL_FILE_NAME);
        if (!Files.exists(skillFile)) {
            throw new IllegalArgumentException(
                    "SKILL.md not found in skill directory: " + skillDir);
        }
        return skillFile;
    }

    /**
     * Retrieves all skill names by parsing SKILL.md metadata in each skill folder.
     *
     * @param baseDir The base directory containing skill folders
     * @return A list of skill names sorted alphabetically
     */
    public static List<String> getAllSkillNames(Path baseDir) {
        List<String> skillNames = new ArrayList<>();

        try (Stream<Path> subdirs = Files.list(baseDir)) {
            subdirs.filter(Files::isDirectory)
                    .forEach(
                            dir -> {
                                if (hasSkillFile(dir)) {
                                    readSkillName(dir).ifPresent(skillNames::add);
                                }
                            });
        } catch (IOException e) {
            throw new RuntimeException("Failed to list skill directories", e);
        }

        skillNames.sort(String::compareTo);
        return skillNames;
    }

    /**
     * Retrieves all skills from the base directory with resources pre-loaded into memory.
     * Equivalent to {@link #getAllSkills(Path, String, boolean) getAllSkills(baseDir, source,
     * true)}.
     */
    public static List<AgentSkill> getAllSkills(Path baseDir, String source) {
        return getAllSkills(baseDir, source, true);
    }

    /**
     * Retrieves all skills from the base directory.
     *
     * @param baseDir The base directory containing skill folders
     * @param source The source identifier for created skills
     * @param includeResources when {@code false}, each skill's resource map is left empty and
     *     callers must rely on the skill's {@code originDir} for disk-side reads
     * @return A list of skills
     */
    public static List<AgentSkill> getAllSkills(
            Path baseDir, String source, boolean includeResources) {
        List<AgentSkill> skills = new ArrayList<>();

        try (Stream<Path> subdirs = Files.list(baseDir)) {
            subdirs.filter(Files::isDirectory)
                    .forEach(
                            dir -> {
                                if (hasSkillFile(dir)) {
                                    try {
                                        skills.add(
                                                loadSkillFromDirectory(
                                                        dir, source, includeResources));
                                    } catch (Exception e) {
                                        logger.warn(
                                                "Failed to load skill from '{}': {}",
                                                dir,
                                                e.getMessage(),
                                                e);
                                    }
                                }
                            });
        } catch (IOException e) {
            throw new RuntimeException("Failed to list skill directories", e);
        }

        return skills;
    }

    /**
     * Saves skills to the base directory.
     *
     * @param baseDir The base directory to save skills
     * @param skills The skills to save
     * @param force Whether to overwrite existing skills
     * @return true if all skills were saved, false otherwise
     */
    public static boolean saveSkills(Path baseDir, List<AgentSkill> skills, boolean force) {
        if (skills == null || skills.isEmpty()) {
            return false;
        }

        int size = skills.size();
        int saveCount = 0;
        try {
            for (AgentSkill skill : skills) {
                String skillName = skill.getName();
                Path skillDir = validateAndResolvePath(baseDir, skillName);

                if (Files.exists(skillDir)) {
                    if (!force) {
                        logger.info(
                                "Skill directory already exists and force=false: {}", skillName);
                        continue; // Skip to the next skill if force=false
                    } else {
                        logger.info("Overwriting existing skill directory: {}", skillName);
                        deleteDirectory(skillDir);
                    }
                }

                Files.createDirectories(skillDir);

                String skillMdContent =
                        MarkdownSkillParser.generate(skill.getMetadata(), skill.getSkillContent());

                Path skillFile = skillDir.resolve(SKILL_FILE_NAME);
                Files.writeString(skillFile, skillMdContent, StandardCharsets.UTF_8);

                Map<String, String> resources = skill.getResources();
                for (Map.Entry<String, String> entry : resources.entrySet()) {
                    Path resourceFile = skillDir.resolve(entry.getKey());
                    Files.createDirectories(resourceFile.getParent());
                    String content = entry.getValue();
                    if (content != null && content.startsWith("base64:")) {
                        String base64 = content.substring("base64:".length());
                        byte[] bytes = Base64.getDecoder().decode(base64);
                        Files.write(resourceFile, bytes);
                    } else {
                        Files.writeString(resourceFile, content, StandardCharsets.UTF_8);
                    }
                }

                saveCount++;
                logger.info("Successfully saved skill: {}", skillName);
            }
            boolean allSaved = (size == saveCount);
            if (!allSaved) {
                logger.warn("Not all skills were saved. Saved {} of {}", saveCount, size);
            }

            return allSaved;
        } catch (IOException e) {
            logger.error("Failed to save skills", e);
            throw new RuntimeException("Failed to save skills", e);
        }
    }

    /**
     * Deletes a skill by name based on SKILL.md metadata.
     *
     * @param baseDir The base directory containing skill folders
     * @param skillName The skill name to delete
     * @return true if deleted, false if not found
     */
    public static boolean deleteSkill(Path baseDir, String skillName) {
        Optional<Path> skillDir = findSkillDirectoryByName(baseDir, skillName);
        if (skillDir.isEmpty()) {
            logger.warn("Skill directory does not exist: {}", skillName);
            return false;
        }

        try {
            deleteDirectory(skillDir.get());
            logger.info("Successfully deleted skill: {}", skillName);
            return true;
        } catch (IOException e) {
            logger.error("Failed to delete skill: {}", skillName, e);
            throw new RuntimeException("Failed to delete skill: " + skillName, e);
        }
    }

    /**
     * Checks whether a skill exists by scanning SKILL.md metadata.
     *
     * @param baseDir The base directory containing skill folders
     * @param skillName The skill name to check
     * @return true if a matching skill exists
     */
    public static boolean skillExists(Path baseDir, String skillName) {
        if (skillName == null || skillName.isEmpty()) {
            return false;
        }
        return findSkillDirectoryByName(baseDir, skillName).isPresent();
    }

    /**
     * Validates that a resolved path is within the base directory.
     *
     * @param baseDir The base directory to constrain resolution
     * @param skillName The skill name to resolve
     * @return The validated absolute path
     * @throws IllegalArgumentException if the path escapes the base directory
     */
    public static Path validateAndResolvePath(Path baseDir, String skillName) {
        if (skillName == null || skillName.isEmpty()) {
            throw new IllegalArgumentException("Skill name cannot be null or empty");
        }

        Path absoluteBaseDir = baseDir.toAbsolutePath().normalize();
        Path resolvedPath = absoluteBaseDir.resolve(skillName).normalize();

        if (!resolvedPath.startsWith(absoluteBaseDir)) {
            throw new IllegalArgumentException(
                    "Invalid skill name: path traversal detected. Skill name '"
                            + skillName
                            + "' would escape base directory");
        }

        return resolvedPath;
    }

    /**
     * Checks if a directory contains a SKILL.md file.
     *
     * @param dir The directory to check
     * @return true if SKILL.md exists in the directory
     */
    public static boolean hasSkillFile(Path dir) {
        try (Stream<Path> files = Files.walk(dir, 1)) {
            return files.anyMatch(p -> p.getFileName().toString().equals(SKILL_FILE_NAME));
        } catch (IOException e) {
            logger.warn("Failed to check for SKILL.md in directory: {}", dir, e);
            return false;
        }
    }

    /**
     * Recursively deletes a directory and all its contents.
     *
     * @param directory The directory to delete
     * @throws IOException if deletion fails
     */
    public static void deleteDirectory(Path directory) throws IOException {
        if (!Files.exists(directory)) {
            return;
        }

        try (Stream<Path> paths = Files.walk(directory)) {
            paths.sorted(Comparator.reverseOrder())
                    .forEach(
                            path -> {
                                try {
                                    Files.delete(path);
                                } catch (IOException e) {
                                    logger.error("Failed to delete: {}", path, e);
                                    throw new RuntimeException("Failed to delete: " + path, e);
                                }
                            });
        }
    }

    /**
     * Registers a shutdown hook to delete the given directory on JVM exit.
     *
     * @param directory The directory to delete at shutdown
     */
    public static Thread registerTempDirectoryCleanup(Path directory) {
        Thread hook =
                new Thread(
                        () -> {
                            try {
                                deleteDirectory(directory);
                                logger.debug(
                                        "Cleaned up temporary directory on shutdown: {}",
                                        directory);
                            } catch (Exception e) {
                                logger.warn(
                                        "Failed to cleanup temp directory on shutdown: {}",
                                        directory,
                                        e);
                            }
                        });
        Runtime.getRuntime().addShutdownHook(hook);
        return hook;
    }

    private static Map<String, String> loadResources(Path skillDir, Path skillFile) {
        Map<String, String> resources = new HashMap<>();
        // Recursive traversal starting from the root directory
        collectResources(skillDir, skillDir, skillFile, resources);
        return resources;
    }

    private static void collectResources(
            Path currentDir, Path skillDir, Path skillFile, Map<String, String> resources) {
        try (DirectoryStream<Path> stream = Files.newDirectoryStream(currentDir)) {
            for (Path entry : stream) {
                if (Files.isDirectory(entry)) {
                    if (!entry.getFileName().toString().startsWith(".")) {
                        collectResources(entry, skillDir, skillFile, resources);
                    }
                } else {
                    if (isValidResource(entry, skillFile)) {
                        readAndPutResource(entry, skillDir, resources);
                    }
                }
            }
        } catch (IOException e) {
            logger.warn("Failed to open directory: {}", currentDir, e);
        }
    }

    private static void readAndPutResource(
            Path file, Path skillDir, Map<String, String> resources) {
        String relativePath = skillDir.relativize(file).toString().replace('\\', '/');
        try {
            String content = Files.readString(file, StandardCharsets.UTF_8);
            resources.put(relativePath, content);
        } catch (MalformedInputException e) {
            try {
                byte[] bytes = Files.readAllBytes(file);
                String base64 = Base64.getEncoder().encodeToString(bytes);
                resources.put(relativePath, "base64:" + base64);
            } catch (IOException ex) {
                logger.warn("Failed to read binary resource file: {}", file, ex);
            }
        } catch (IOException e) {
            logger.warn("Failed to read resource file: {}", file, e);
        }
    }

    private static boolean isValidResource(Path file, Path skillFile) {
        if (file.equals(skillFile)) {
            return false;
        }

        if (!Files.isRegularFile(file) || !Files.isReadable(file)) {
            return false;
        }

        if (file.getFileName().toString().startsWith(".")) {
            return false;
        }

        try {
            return !Files.isHidden(file);
        } catch (IOException e) {
            logger.warn("Failed to check attributes for file: {}", file, e);
            return true;
        }
    }

    private static Optional<Path> findSkillDirectoryByName(Path baseDir, String skillName) {
        if (skillName == null || skillName.isEmpty()) {
            return Optional.empty();
        }

        try (Stream<Path> subdirs = Files.list(baseDir)) {
            return subdirs.filter(Files::isDirectory)
                    .filter(SkillFileSystemHelper::hasSkillFile)
                    .filter(dir -> skillName.equals(readSkillName(dir).orElse(null)))
                    .findFirst();
        } catch (IOException e) {
            throw new RuntimeException("Failed to list skill directories", e);
        }
    }

    private static Optional<String> readSkillName(Path skillDir) {
        Path skillFile = skillDir.resolve(SKILL_FILE_NAME);
        if (!Files.exists(skillFile)) {
            return Optional.empty();
        }

        try {
            String skillMdContent = Files.readString(skillFile, StandardCharsets.UTF_8);
            ParsedMarkdown parsed = MarkdownSkillParser.parse(skillMdContent);
            Map<String, Object> metadata = parsed.getMetadata();
            Object nameObject = metadata.get("name");
            String name = nameObject != null ? String.valueOf(nameObject) : null;
            if (name == null || name.isEmpty()) {
                logger.warn("Missing skill name in SKILL.md: {}", skillFile);
                return Optional.empty();
            }
            return Optional.of(name);
        } catch (Exception e) {
            logger.warn("Failed to parse SKILL.md: {}", skillFile, e);
            return Optional.empty();
        }
    }
}

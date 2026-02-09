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
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
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
     * Loads a skill from a specific directory.
     *
     * @param skillDir The directory containing SKILL.md
     * @param source The source identifier for the created skill
     * @return The loaded AgentSkill
     * @throws IllegalArgumentException if SKILL.md is missing or invalid
     */
    public static AgentSkill loadSkillFromDirectory(Path skillDir, String source) {
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
            Map<String, String> resources = loadResources(skillDir, skillFile);
            return SkillUtil.createFrom(skillMdContent, resources, source);
        } catch (IOException e) {
            throw new RuntimeException("Failed to load skill from: " + skillDir, e);
        }
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
     * Retrieves all skills from the base directory.
     *
     * @param baseDir The base directory containing skill folders
     * @param source The source identifier for created skills
     * @return A list of skills
     */
    public static List<AgentSkill> getAllSkills(Path baseDir, String source) {
        List<AgentSkill> skills = new ArrayList<>();

        try (Stream<Path> subdirs = Files.list(baseDir)) {
            subdirs.filter(Files::isDirectory)
                    .forEach(
                            dir -> {
                                if (hasSkillFile(dir)) {
                                    try {
                                        skills.add(loadSkillFromDirectory(dir, source));
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

        try {
            for (AgentSkill skill : skills) {
                String skillName = skill.getName();
                Path skillDir = validateAndResolvePath(baseDir, skillName);

                if (Files.exists(skillDir)) {
                    if (!force) {
                        logger.info(
                                "Skill directory already exists and force=false: {}", skillName);
                        return false;
                    } else {
                        logger.info("Overwriting existing skill directory: {}", skillName);
                        deleteDirectory(skillDir);
                    }
                }

                Files.createDirectories(skillDir);

                Map<String, String> metadata = new LinkedHashMap<>();
                metadata.put("name", skill.getName());
                metadata.put("description", skill.getDescription());

                String skillMdContent =
                        MarkdownSkillParser.generate(metadata, skill.getSkillContent());

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

                logger.info("Successfully saved skill: {}", skillName);
            }

            return true;
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

    private static Map<String, String> loadResources(Path skillDir, Path skillFile)
            throws IOException {
        Map<String, String> resources = new HashMap<>();
        try (Stream<Path> paths = Files.walk(skillDir)) {
            paths.filter(Files::isRegularFile)
                    .filter(p -> !p.equals(skillFile))
                    .forEach(
                            p -> {
                                String relativePath =
                                        skillDir.relativize(p).toString().replace('\\', '/');
                                try {
                                    String content = Files.readString(p, StandardCharsets.UTF_8);
                                    resources.put(relativePath, content);
                                } catch (MalformedInputException e) {
                                    try {
                                        byte[] bytes = Files.readAllBytes(p);
                                        String base64 = Base64.getEncoder().encodeToString(bytes);
                                        resources.put(relativePath, "base64:" + base64);
                                    } catch (IOException ex) {
                                        logger.warn(
                                                "Failed to read binary resource file: {}", p, ex);
                                    }
                                } catch (IOException e) {
                                    logger.warn("Failed to read resource file: {}", p, e);
                                }
                            });
        }
        return resources;
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
            Map<String, String> metadata = parsed.getMetadata();
            String name = metadata.get("name");
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

/*
 * Copyright 2024-2026 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package io.agentscope.core.skill.repository;

import io.agentscope.core.skill.AgentSkill;
import io.agentscope.core.skill.util.MarkdownSkillParser;
import io.agentscope.core.skill.util.SkillUtil;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * File system based implementation of AgentSkillRepository.
 *
 * <p>This repository stores skills in a local file system directory structure where each skill
 * is stored in its own subdirectory containing a SKILL.md file and optional resource files.
 *
 * <p>Directory structure:
 * <pre>{@code
 * baseDir/
 * ├── skill-name-1/
 * │   ├── SKILL.md          # Required: Entry file with YAML frontmatter
 * │   ├── references/       # Optional: Reference documentation
 * │   ├── examples/         # Optional: Example files
 * │   └── scripts/          # Optional: Script files
 * └── skill-name-2/
 *     └── SKILL.md
 * }</pre>
 *
 * <p>Example usage:
 * <pre>{@code
 * Path baseDir = Paths.get("/path/to/skills");
 * FileSystemSkillRepository repo = new FileSystemSkillRepository(baseDir);
 * AgentSkill skill = repo.getSkill("my-skill");
 * }</pre>
 */
public class FileSystemSkillRepository implements AgentSkillRepository {

    private static final Logger logger = LoggerFactory.getLogger(FileSystemSkillRepository.class);
    private static final String SKILL_FILE_NAME = "SKILL.md";

    private final Path baseDir;
    private boolean writeable;

    public FileSystemSkillRepository(Path baseDir) {
        this(baseDir, true);
    }

    /**
     * Creates a FileSystemSkillRepository with the specified base directory.
     *
     * @param baseDir The base directory containing skill subdirectories (must not be null)
     * @param writeable Whether the repository supports write operations
     * @throws IllegalArgumentException if baseDir is null, doesn't exist, is not a directory,
     *                                  or is empty
     */
    public FileSystemSkillRepository(Path baseDir, boolean writeable) {
        this.writeable = writeable;
        if (baseDir == null) {
            throw new IllegalArgumentException("Base directory cannot be null");
        }

        // Convert to absolute path and normalize
        this.baseDir = baseDir.toAbsolutePath().normalize();

        // Validate directory exists
        if (!Files.exists(this.baseDir)) {
            throw new IllegalArgumentException("Base directory does not exist: " + this.baseDir);
        }

        // Validate it's a directory
        if (!Files.isDirectory(this.baseDir)) {
            throw new IllegalArgumentException(
                    "Base directory is not a directory: " + this.baseDir);
        }

        logger.info("FileSystemSkillRepository initialized with base directory: {}", this.baseDir);
    }

    @Override
    public AgentSkill getSkill(String name) {
        // Validate path and resolve within baseDir
        Path skillDir = validateAndResolvePath(name);

        if (!Files.exists(skillDir)) {
            throw new IllegalArgumentException("Skill directory does not exist: " + name);
        }

        if (!Files.isDirectory(skillDir)) {
            throw new IllegalArgumentException("Skill path is not a directory: " + name);
        }

        Path skillFile = skillDir.resolve(SKILL_FILE_NAME);
        if (!Files.exists(skillFile)) {
            throw new IllegalArgumentException("SKILL.md not found in skill directory: " + name);
        }

        try {
            // Read SKILL.md content
            String skillMdContent = Files.readString(skillFile, StandardCharsets.UTF_8);

            // Build resources map by walking the skill directory tree
            Map<String, String> resources = new HashMap<>();
            try (Stream<Path> paths = Files.walk(skillDir)) {
                paths.filter(Files::isRegularFile)
                        .filter(p -> !p.equals(skillFile)) // Exclude SKILL.md itself
                        .forEach(
                                p -> {
                                    try {
                                        String relativePath =
                                                skillDir.relativize(p)
                                                        .toString()
                                                        .replace('\\', '/');
                                        String content =
                                                Files.readString(p, StandardCharsets.UTF_8);
                                        resources.put(relativePath, content);
                                    } catch (IOException e) {
                                        logger.warn("Failed to read resource file: {}", p, e);
                                    }
                                });
            }

            // Create AgentSkill using SkillUtil
            return SkillUtil.createFrom(skillMdContent, resources, getSource());

        } catch (IOException e) {
            throw new RuntimeException("Failed to load skill: " + name, e);
        }
    }

    @Override
    public List<String> getAllSkillNames() {
        List<String> skillNames = new ArrayList<>();

        try (Stream<Path> subdirs = Files.list(baseDir)) {
            subdirs.filter(Files::isDirectory)
                    .forEach(
                            dir -> {
                                // Check if this directory contains a SKILL.md file
                                if (hasSkillFile(dir)) {
                                    skillNames.add(dir.getFileName().toString());
                                }
                            });
        } catch (IOException e) {
            throw new RuntimeException("Failed to list skill directories", e);
        }

        skillNames.sort(String::compareTo);
        return skillNames;
    }

    @Override
    public List<AgentSkill> getAllSkills() {
        List<String> skillNames = getAllSkillNames();
        List<AgentSkill> skills = new ArrayList<>();

        for (String name : skillNames) {
            try {
                skills.add(getSkill(name));
            } catch (Exception e) {
                logger.warn("Failed to load skill '{}': {}", name, e.getMessage(), e);
                // Continue processing other skills
            }
        }

        return skills;
    }

    @Override
    public boolean save(List<AgentSkill> skills, boolean force) {
        if (skills == null || skills.isEmpty()) {
            return false;
        }

        if (!writeable) {
            logger.warn("Cannot save skills: repository is read-only");
            return false;
        }

        try {
            for (AgentSkill skill : skills) {
                String skillName = skill.getName();
                // Validate path and resolve within baseDir
                Path skillDir = validateAndResolvePath(skillName);

                // Check if skill directory already exists
                if (Files.exists(skillDir)) {
                    if (!force) {
                        logger.info(
                                "Skill directory already exists and force=false: {}", skillName);
                        return false;
                    } else {
                        // Delete existing directory
                        logger.info("Overwriting existing skill directory: {}", skillName);
                        deleteDirectory(skillDir);
                    }
                }

                // Create skill directory
                Files.createDirectories(skillDir);

                // Generate SKILL.md with YAML frontmatter
                Map<String, Object> metadata = new LinkedHashMap<>();
                metadata.put("name", skill.getName());
                metadata.put("description", skill.getDescription());

                String skillMdContent =
                        MarkdownSkillParser.generate(metadata, skill.getSkillContent());

                // Write SKILL.md
                Path skillFile = skillDir.resolve(SKILL_FILE_NAME);
                Files.writeString(skillFile, skillMdContent, StandardCharsets.UTF_8);

                // Write resource files
                Map<String, String> resources = skill.getResources();
                for (Map.Entry<String, String> entry : resources.entrySet()) {
                    String relativePath = entry.getKey();
                    String content = entry.getValue();

                    Path resourceFile = skillDir.resolve(relativePath);
                    // Create parent directories if needed
                    Files.createDirectories(resourceFile.getParent());
                    Files.writeString(resourceFile, content, StandardCharsets.UTF_8);
                }

                logger.info("Successfully saved skill: {}", skillName);
            }

            return true;

        } catch (IOException e) {
            logger.error("Failed to save skills", e);
            throw new RuntimeException("Failed to save skills", e);
        }
    }

    @Override
    public boolean delete(String skillName) {
        if (!writeable) {
            logger.warn("Cannot delete skill: repository is read-only");
            return false;
        }

        // Validate path and resolve within baseDir
        Path skillDir = validateAndResolvePath(skillName);

        if (!Files.exists(skillDir)) {
            logger.warn("Skill directory does not exist: {}", skillName);
            return false;
        }

        try {
            deleteDirectory(skillDir);
            logger.info("Successfully deleted skill: {}", skillName);
            return true;
        } catch (IOException e) {
            logger.error("Failed to delete skill: {}", skillName, e);
            throw new RuntimeException("Failed to delete skill: " + skillName, e);
        }
    }

    @Override
    public boolean skillExists(String skillName) {
        if (skillName == null || skillName.isEmpty()) {
            return false;
        }

        try {
            // Validate path and resolve within baseDir
            Path skillDir = validateAndResolvePath(skillName);
            if (!Files.exists(skillDir) || !Files.isDirectory(skillDir)) {
                return false;
            }

            Path skillFile = skillDir.resolve(SKILL_FILE_NAME);
            return Files.exists(skillFile);
        } catch (IllegalArgumentException e) {
            // Path traversal detected, return false
            logger.warn("Path traversal attempt detected in exists: {}", skillName);
            return false;
        }
    }

    @Override
    public AgentSkillRepositoryInfo getRepositoryInfo() {
        return new AgentSkillRepositoryInfo("filesystem", baseDir.toString(), writeable);
    }

    @Override
    public String getSource() {
        return "filesystem_" + baseDir.toString();
    }

    /**
     * Validates that a resolved path is within the base directory to prevent path traversal
     * attacks.
     *
     * @param skillName The skill name to resolve
     * @return The validated absolute path
     * @throws IllegalArgumentException if the path escapes the base directory
     */
    private Path validateAndResolvePath(String skillName) {
        if (skillName == null || skillName.isEmpty()) {
            throw new IllegalArgumentException("Skill name cannot be null or empty");
        }

        // Resolve and normalize the path
        Path resolvedPath = baseDir.resolve(skillName).toAbsolutePath().normalize();

        // Check if the resolved path is within baseDir
        if (!resolvedPath.startsWith(baseDir)) {
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
    private boolean hasSkillFile(Path dir) {
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
    private void deleteDirectory(Path directory) throws IOException {
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

    @Override
    public void setWriteable(boolean writeable) {
        this.writeable = writeable;
    }

    @Override
    public boolean isWriteable() {
        return writeable;
    }
}

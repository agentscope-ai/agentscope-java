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
import io.agentscope.core.skill.util.SkillFileSystemHelper;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
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
    private final Path baseDir;
    private final String source;
    private boolean writeable;

    public FileSystemSkillRepository(Path baseDir) {
        this(baseDir, true, null);
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
        this(baseDir, writeable, null);
    }

    /**
     * Creates a FileSystemSkillRepository with the specified base directory and source.
     *
     * @param baseDir The base directory containing skill subdirectories (must not be null)
     * @param writeable Whether the repository supports write operations
     * @param source The custom source identifier for skills (null to use default)
     * @throws IllegalArgumentException if baseDir is null, doesn't exist, is not a directory,
     *                                  or is empty
     */
    public FileSystemSkillRepository(Path baseDir, boolean writeable, String source) {
        this.writeable = writeable;
        this.source = source;
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
        return SkillFileSystemHelper.loadSkill(baseDir, name, getSource());
    }

    @Override
    public List<String> getAllSkillNames() {
        return SkillFileSystemHelper.getAllSkillNames(baseDir);
    }

    @Override
    public List<AgentSkill> getAllSkills() {
        return SkillFileSystemHelper.getAllSkills(baseDir, getSource());
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

        return SkillFileSystemHelper.saveSkills(baseDir, skills, force);
    }

    @Override
    public boolean delete(String skillName) {
        if (!writeable) {
            logger.warn("Cannot delete skill: repository is read-only");
            return false;
        }

        return SkillFileSystemHelper.deleteSkill(baseDir, skillName);
    }

    @Override
    public boolean skillExists(String skillName) {
        return SkillFileSystemHelper.skillExists(baseDir, skillName);
    }

    @Override
    public AgentSkillRepositoryInfo getRepositoryInfo() {
        return new AgentSkillRepositoryInfo("filesystem", baseDir.toString(), writeable);
    }

    @Override
    public String getSource() {
        return source != null ? source : "filesystem_" + buildDefaultSourceSuffix();
    }

    private String buildDefaultSourceSuffix() {
        Path fileName = baseDir.getFileName();
        Path parent = baseDir.getParent();

        if (fileName == null) {
            return "unknown";
        }

        if (parent == null || parent.getFileName() == null) {
            return fileName.toString();
        }

        return parent.getFileName() + "/" + fileName;
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

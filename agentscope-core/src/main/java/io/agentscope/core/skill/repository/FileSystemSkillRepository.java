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
import java.io.IOException;
import java.nio.file.FileVisitOption;
import java.nio.file.Files;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
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
 *
 */
public class FileSystemSkillRepository implements AgentSkillRepository {

    private static final Logger logger = LoggerFactory.getLogger(FileSystemSkillRepository.class);
    private static final String SKILL_FILE_NAME = "SKILL.md";

    private final Path baseDir;
    private final String source;
    private boolean writeable;

    /**
     * When {@code true}, only SKILL.md is read into memory by {@link #getAllSkills()};
     * referenced support files are left out and consumers resolve them on demand through the
     * skill's {@code originDir}. Defaults to {@code false} to preserve historical behaviour
     * for callers that still inspect {@code skill.getResources()}.
     */
    private final boolean lazy;

    /**
     * Memoises (skillDirAbsolutePath → snapshot) so that repeated {@link #getAllSkills()} calls
     * skip reloading while SKILL.md and, in eager mode, every resource keep their mtime and size.
     */
    private final Map<Path, Snapshot> skillCache = new ConcurrentHashMap<>();

    public FileSystemSkillRepository(Path baseDir) {
        this(baseDir, true, null, false);
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
        this(baseDir, writeable, null, false);
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
        this(baseDir, writeable, source, false);
    }

    /**
     * Creates a FileSystemSkillRepository with explicit lazy mode.
     *
     * @param baseDir The base directory containing skill subdirectories (must not be null)
     * @param writeable Whether the repository supports write operations
     * @param source The custom source identifier for skills (null to use default)
     * @param lazy when {@code true}, support files are NOT pre-loaded; consumers must resolve
     *     them via the skill's {@code originDir}. Use this together with a
     *     {@code load_skill_through_path} disk-fallback for large skill libraries
     * @throws IllegalArgumentException if baseDir is null, doesn't exist, is not a directory,
     *                                  or is empty
     */
    public FileSystemSkillRepository(Path baseDir, boolean writeable, String source, boolean lazy) {
        this.writeable = writeable;
        this.source = source;
        this.lazy = lazy;
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

        logger.info(
                "FileSystemSkillRepository initialized [baseDir={}, lazy={}]", this.baseDir, lazy);
    }

    /** Whether this repository only reads SKILL.md and defers support-file loading. */
    public boolean isLazy() {
        return lazy;
    }

    @Override
    public AgentSkill getSkill(String name) {
        // Single-skill lookup is rare on the hot path; just go through the same cache by
        // running getAllSkills(), and pick the matching one. This keeps mtime invalidation
        // logic in one place.
        if (name == null || name.isEmpty()) {
            return null;
        }
        for (AgentSkill skill : getAllSkills()) {
            if (name.equals(skill.getName())) {
                return skill;
            }
        }
        // Fallback path for cases where a skill was just written and the cache hasn't seen
        // it: read directly. Honour the lazy flag.
        return SkillFileSystemHelper.loadSkill(baseDir, name, getSource());
    }

    @Override
    public List<String> getAllSkillNames() {
        return SkillFileSystemHelper.getAllSkillNames(baseDir);
    }

    @Override
    public List<AgentSkill> getAllSkills() {
        List<AgentSkill> out = new ArrayList<>();
        Set<Path> seenDirs = new HashSet<>();
        try (Stream<Path> subdirs = Files.list(baseDir)) {
            for (Path dir : (Iterable<Path>) subdirs::iterator) {
                if (!Files.isDirectory(dir)) {
                    continue;
                }
                Path skillFile = dir.resolve(SKILL_FILE_NAME);
                if (!Files.exists(skillFile)) {
                    continue;
                }
                Path key = dir.toAbsolutePath().normalize();
                seenDirs.add(key);
                try {
                    Map<Path, FileStamp> files = fileStamps(dir, skillFile);
                    Snapshot cached = skillCache.get(key);
                    if (cached != null && cached.files.equals(files)) {
                        out.add(cached.skill);
                        continue;
                    }
                    AgentSkill skill =
                            SkillFileSystemHelper.loadSkillFromDirectory(dir, getSource(), !lazy);
                    skillCache.put(key, new Snapshot(files, skill));
                    out.add(skill);
                } catch (IOException e) {
                    logger.warn(
                            "Failed to inspect skill files for '{}': {}", dir, e.getMessage(), e);
                } catch (Exception e) {
                    logger.warn("Failed to load skill from '{}': {}", dir, e.getMessage(), e);
                }
            }
        } catch (IOException e) {
            throw new RuntimeException("Failed to list skill directories", e);
        }
        // Evict entries whose backing directory disappeared between calls so the cache does
        // not pin removed skills.
        skillCache.keySet().retainAll(seenDirs);
        return out;
    }

    private Map<Path, FileStamp> fileStamps(Path dir, Path skillFile) throws IOException {
        if (lazy) {
            return Map.of(dir.relativize(skillFile), FileStamp.from(skillFile));
        }
        Map<Path, FileStamp> stamps = new HashMap<>();
        try (Stream<Path> paths = Files.walk(dir, FileVisitOption.FOLLOW_LINKS)) {
            for (Path file : (Iterable<Path>) paths.filter(Files::isRegularFile)::iterator) {
                try {
                    stamps.put(dir.relativize(file), FileStamp.from(file));
                } catch (NoSuchFileException ignored) {
                    // A concurrent update removed the file after traversal.
                }
            }
        }
        return Map.copyOf(stamps);
    }

    private record FileStamp(long mtime, long size) {
        static FileStamp from(Path file) throws IOException {
            BasicFileAttributes attrs = Files.readAttributes(file, BasicFileAttributes.class);
            return new FileStamp(attrs.lastModifiedTime().toMillis(), attrs.size());
        }
    }

    /** Cache entry pairing the skill's file fingerprints with the parsed AgentSkill. */
    private record Snapshot(Map<Path, FileStamp> files, AgentSkill skill) {}

    @Override
    public boolean save(List<AgentSkill> skills, boolean force) {
        if (skills == null || skills.isEmpty()) {
            return false;
        }

        if (!writeable) {
            logger.warn("Cannot save skills: repository is read-only");
            return false;
        }

        boolean ok = SkillFileSystemHelper.saveSkills(baseDir, skills, force);
        skillCache.clear();
        return ok;
    }

    @Override
    public boolean delete(String skillName) {
        if (!writeable) {
            logger.warn("Cannot delete skill: repository is read-only");
            return false;
        }

        boolean ok = SkillFileSystemHelper.deleteSkill(baseDir, skillName);
        skillCache.clear();
        return ok;
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
        return source != null ? source : "filesystem-" + buildDefaultSourceSuffix();
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

        return parent.getFileName() + "_" + fileName;
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

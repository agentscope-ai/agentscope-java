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
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Stream;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * File system based implementation of AgentSkillRepository.
 *
 * <p>This repository stores skills in a local file system directory and serves it directly
 * — no materialization cache is used. Two layouts are supported:
 *
 * <p>Flat (unversioned):
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
 * <p>Versioned container:
 * <pre>{@code
 * baseDir/
 * └── skill-name/
 *     ├── 20260101120000/   # One directory per version, each a complete skill package
 *     │   └── SKILL.md
 *     └── 20260201130000/
 *         └── SKILL.md
 * }</pre>
 *
 * <p>Example usage:
 * <pre>{@code
 * Path baseDir = Paths.get("/path/to/skills");
 * FileSystemSkillRepository repo = new FileSystemSkillRepository(baseDir);
 * AgentSkill skill = repo.getSkill("my-skill", null);
 * }</pre>
 *
 * <p>Version semantics: the flat layout has <em>no</em> version capability — every pull
 * serves the current bytes unstamped, and a non-null {@code version} has nothing to address
 * and answers {@code null}. The versioned-container layout makes the version directory
 * name the version reference: a {@code null} version serves the lexicographically greatest
 * directory name (directory names must sort chronologically as strings; fixed-width
 * timestamp names such as {@code 20260101120000} are the recommended form), and a non-null
 * version serves exactly that directory or answers {@code null}. Mixing the two layouts in
 * one repository is unsupported: once any skill serves a stamped pull, the loading
 * middleware classifies the repository as version-capable and skips its unstamped skills
 * (see {@link io.agentscope.core.skill.DynamicSkillMiddleware}).
 */
public class FileSystemSkillRepository implements AgentSkillRepository {

    private static final Logger logger = LoggerFactory.getLogger(FileSystemSkillRepository.class);

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
     * Creates a writable FileSystemSkillRepository on the given base directory serving
     * resources eagerly (non-lazy).
     *
     * @param baseDir The base directory containing skill subdirectories (must not be null)
     * @throws IllegalArgumentException if baseDir is null, doesn't exist, or is not a directory
     */
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

    /**
     * Serves the skill from this repository's own directory — a direct read, no cache.
     *
     * <p>Flat layout ({@code baseDir/<name>/SKILL.md}): a {@code null}/blank version serves
     * the current bytes <em>unstamped</em>; any non-null version has nothing to address and
     * answers {@code null}.
     *
     * <p>Versioned-container layout ({@code baseDir/<name>/<version>/SKILL.md}): the
     * version directory name is the version reference. {@code null}/blank serves the
     * lexicographically greatest version directory; a non-null version serves exactly that
     * directory or answers {@code null} — the requested string is matched against directory
     * names, never resolved as a path. Served instances carry the version directory name as
     * their stamp.
     *
     * <p>Both layouts return the summary form (empty resource map, {@code originDir} = the
     * served directory; see {@link AgentSkillRepository#getSkill(String, String)}).
     * Unresolvable references (missing directory, neither layout, unknown version,
     * traversal attempt) answer {@code null}, never an exception.
     */
    @Override
    public AgentSkill getSkill(String name, String version) {
        Path skillDir = resolveSkillDir(name);
        if (skillDir == null) {
            return null;
        }
        if (SkillFileSystemHelper.hasSkillFile(skillDir)) {
            // Flat layout: this directory is the skill, always current, never versioned.
            if (version != null && !version.isBlank()) {
                return null;
            }
            return loadSummaryFrom(skillDir);
        }
        Path versionDir = resolveVersionDir(skillDir, version);
        return versionDir == null
                ? null
                : stampVersion(loadSummaryFrom(versionDir), versionDir.getFileName().toString());
    }

    /**
     * Loads a directory's SKILL.md as the summary form (empty resource map, {@code
     * originDir} set); {@code null} when the directory is not a loadable skill (missing,
     * invalid or unreadable SKILL.md) — never an exception.
     */
    private AgentSkill loadSummaryFrom(Path skillDir) {
        try {
            return SkillFileSystemHelper.loadSkillFromDirectory(skillDir, getSource(), false);
        } catch (RuntimeException e) {
            logger.warn("Failed to load skill from '{}': {}", skillDir, e.getMessage());
            return null;
        }
    }

    /**
     * Resolves the version directory to serve from a versioned container: the
     * lexicographically greatest version directory name for a {@code null}/blank version,
     * or the exact-name match for a specific version. The requested version is only
     * compared against listed directory names — never resolved as a path — so traversal
     * attempts simply miss. {@code null} when the container holds no matching version
     * directory.
     */
    private Path resolveVersionDir(Path skillDir, String version) {
        boolean exact = version != null && !version.isBlank();
        Path latest = null;
        try (Stream<Path> children = Files.list(skillDir)) {
            for (Path dir : (Iterable<Path>) children::iterator) {
                if (!SkillFileSystemHelper.isVersionDirectory(dir)) {
                    continue;
                }
                if (exact) {
                    if (version.equals(dir.getFileName().toString())) {
                        return dir;
                    }
                } else if (latest == null
                        || latest.getFileName().toString().compareTo(dir.getFileName().toString())
                                < 0) {
                    latest = dir;
                }
            }
        } catch (IOException e) {
            logger.warn(
                    "Failed to list version directories under '{}': {}", skillDir, e.getMessage());
            return null;
        }
        return latest;
    }

    /**
     * Discovery for both layouts, keyed on the directory name — the same key the serving
     * method resolves ({@code baseDir/<name>}): a flat skill directory holds a direct
     * SKILL.md, a versioned container holds version directories. The SKILL.md metadata
     * name is not a discovery key; a skill whose metadata name differs from its directory
     * name is listed and served under the directory name.
     */
    @Override
    public List<String> getAllSkillNames() {
        List<String> names = new ArrayList<>();
        try (Stream<Path> children = Files.list(baseDir)) {
            for (Path dir : (Iterable<Path>) children::iterator) {
                Path fileName = dir.getFileName();
                if (fileName == null
                        || fileName.toString().startsWith(".")
                        || !Files.isDirectory(dir)) {
                    continue;
                }
                if (SkillFileSystemHelper.hasSkillFile(dir)
                        || SkillFileSystemHelper.hasVersionedLayout(dir)) {
                    names.add(fileName.toString());
                }
            }
        } catch (IOException e) {
            throw new RuntimeException("Failed to list skill directories", e);
        }
        names.sort(String::compareTo);
        return names;
    }

    /**
     * Bulk read retained for compatibility: a flat-directory scan matching SKILL.md metadata
     * names, loading resources per the {@code lazy} flag. Versioned containers are not
     * included — they are served through {@link #getSkill(String, String)} at
     * their latest version once {@link #getAllSkillNames()} lists them.
     */
    @Override
    public List<AgentSkill> getAllSkills() {
        return SkillFileSystemHelper.getAllSkills(baseDir, getSource(), !lazy);
    }

    /**
     * Resolves the named skill's directory as {@code baseDir/<name>} (the write path's
     * convention), rejecting traversal; {@code null} when no such directory exists.
     */
    private Path resolveSkillDir(String name) {
        if (name == null || name.isBlank()) {
            return null;
        }
        try {
            Path dir = SkillFileSystemHelper.validateAndResolvePath(baseDir, name);
            return Files.isDirectory(dir) ? dir : null;
        } catch (IllegalArgumentException e) {
            return null;
        }
    }

    /** Stamps a loaded skill with its version; {@code null} skills pass through. */
    private static AgentSkill stampVersion(AgentSkill skill, String version) {
        return skill == null ? null : skill.toBuilder().version(version).build();
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
        if (SkillFileSystemHelper.skillExists(baseDir, skillName)) {
            return true; // legacy flat lookup matching the SKILL.md metadata name
        }
        Path dir = resolveSkillDir(skillName);
        return dir != null
                && (SkillFileSystemHelper.hasSkillFile(dir)
                        || SkillFileSystemHelper.hasVersionedLayout(dir));
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

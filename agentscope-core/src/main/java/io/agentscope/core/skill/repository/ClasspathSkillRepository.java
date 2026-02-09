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
package io.agentscope.core.skill.repository;

import io.agentscope.core.skill.AgentSkill;
import io.agentscope.core.skill.util.SkillFileSystemHelper;
import java.io.IOException;
import java.net.URI;
import java.net.URISyntaxException;
import java.net.URL;
import java.nio.file.FileSystem;
import java.nio.file.FileSystems;
import java.nio.file.Path;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * ClasspathSkillRepository - Loads skills from classpath resources and JAR files.
 *
 * <p>This repository bridges the gap between JAR resources and file system based skill loading by:
 * <ol>
 *   <li>Creating a virtual file system for resources within JAR files</li>
 *   <li>Obtaining Path objects for skill directories</li>
 *   <li>Delegating skill I/O to {@link SkillFileSystemHelper}</li>
 * </ol>
 *
 * <p><b>Important:</b> Skills must be organized in a parent directory structure. You should pass
 * the parent directory name (not individual skill paths). This is required because the repository
 * scans a directory for multiple skill subdirectories.
 *
 * <p><b>Directory Structure:</b>
 * <pre>
 * resources/
 * └── skills/              ← Pass "skills" to repository
 *     ├── skill-a/
 *     │   └── SKILL.md
 *     ├── skill-b/
 *     │   └── SKILL.md
 *     └── skill-c/
 *         └── SKILL.md
 * </pre>
 *
 * <p><b>Usage example:</b>
 * <pre>{@code
 * // Load from parent directory containing multiple skills
 * try (ClasspathSkillRepository repository = new ClasspathSkillRepository("skills")) {
 *     // Get all available skill names
 *     List<String> skillNames = repository.getAllSkillNames();
 *
 *     // Load a specific skill
 *     AgentSkill skillA = repository.getSkill("skill-a");
 *
 *     // Load all skills at once
 *     List<AgentSkill> allSkills = repository.getAllSkills();
 * }
 * }</pre>
 */
public class ClasspathSkillRepository implements AgentSkillRepository {

    private final Logger logger = LoggerFactory.getLogger(ClasspathSkillRepository.class);

    private final FileSystem fileSystem;
    private final Path skillBasePath;
    private final boolean isJar;
    private final String source;
    private final String resourcePath;
    private final AtomicBoolean closed = new AtomicBoolean(false);

    /**
     * Creates a repository for loading skills from resources.
     *
     * @param resourcePath The path to the skill under resources, e.g., "writing-skills"
     * @throws IOException if initialization fails
     */
    public ClasspathSkillRepository(String resourcePath) throws IOException {
        this(resourcePath, null, ClasspathSkillRepository.class.getClassLoader());
    }

    /**
     * Creates a repository for loading skills from resources with a custom source.
     *
     * @param resourcePath The path to the skill under resources, e.g., "writing-skills"
     * @param source The custom source identifier (null to use default)
     * @throws IOException if initialization fails
     */
    public ClasspathSkillRepository(String resourcePath, String source) throws IOException {
        this(resourcePath, source, ClasspathSkillRepository.class.getClassLoader());
    }

    /**
     * Creates a repository for loading skills from resources using a specific ClassLoader.
     *
     * @param resourcePath The path to the skill under resources, e.g., "writing-skills"
     * @param classLoader The ClassLoader to use for loading resources
     * @throws IOException if initialization fails
     */
    protected ClasspathSkillRepository(String resourcePath, ClassLoader classLoader)
            throws IOException {
        this(resourcePath, null, classLoader);
    }

    /**
     * Creates a repository for loading skills from resources using a specific ClassLoader.
     *
     * @param resourcePath The path to the skill under resources, e.g., "writing-skills"
     * @param source The custom source identifier (null to use default)
     * @param classLoader The ClassLoader to use for loading resources
     * @throws IOException if initialization fails
     */
    protected ClasspathSkillRepository(String resourcePath, String source, ClassLoader classLoader)
            throws IOException {
        try {
            this.resourcePath = resourcePath;
            URL resourceUrl = classLoader.getResource(resourcePath);
            logger.info("Resource URL: {}", resourceUrl);

            if (resourceUrl == null) {
                throw new IOException("Resource not found: " + resourcePath);
            }

            URI uri = resourceUrl.toURI();

            if ("jar".equals(uri.getScheme())) {
                this.isJar = true;
                this.fileSystem = FileSystems.newFileSystem(uri, Collections.emptyMap());
                String actualResourcePath = uri.getSchemeSpecificPart().split("!")[1];
                logger.info("Actual resource path: {}", actualResourcePath);
                this.skillBasePath = fileSystem.getPath(actualResourcePath);
            } else {
                this.isJar = false;
                this.fileSystem = null;
                this.skillBasePath = Path.of(uri);
            }
            logger.info("is in Jar environment: {}", this.isJar);
            this.source = source != null ? source : buildDefaultSource(resourcePath);

        } catch (URISyntaxException e) {
            throw new IOException("Invalid resource URI", e);
        }
    }

    /**
     * Gets a skill by name.
     *
     * @param skillName The skill name (from SKILL.md metadata)
     * @return The loaded AgentSkill object
     * @throws IllegalStateException if the repository has been closed
     */
    @Override
    public AgentSkill getSkill(String skillName) {
        checkNotClosed();
        return SkillFileSystemHelper.loadSkill(skillBasePath, skillName, source);
    }

    /**
     * Gets all skill names available in the repository.
     *
     * @return A sorted list of skill names
     * @throws IllegalStateException if the repository has been closed
     */
    @Override
    public List<String> getAllSkillNames() {
        checkNotClosed();
        return SkillFileSystemHelper.getAllSkillNames(skillBasePath);
    }

    /**
     * Gets all skills available in the repository.
     *
     * @return A list of all loaded AgentSkill objects
     * @throws IllegalStateException if the repository has been closed
     */
    @Override
    public List<AgentSkill> getAllSkills() {
        checkNotClosed();
        return SkillFileSystemHelper.getAllSkills(skillBasePath, source);
    }

    @Override
    public boolean save(List<AgentSkill> skills, boolean force) {
        logger.warn("ClasspathSkillRepository is read-only, save operation ignored");
        return false;
    }

    @Override
    public boolean delete(String skillName) {
        logger.warn("ClasspathSkillRepository is read-only, delete operation ignored");
        return false;
    }

    @Override
    public boolean skillExists(String skillName) {
        checkNotClosed();
        return SkillFileSystemHelper.skillExists(skillBasePath, skillName);
    }

    @Override
    public AgentSkillRepositoryInfo getRepositoryInfo() {
        return new AgentSkillRepositoryInfo("classpath", resourcePath, false);
    }

    @Override
    public String getSource() {
        return source;
    }

    private String buildDefaultSource(String resourcePath) {
        String normalized = resourcePath == null ? "" : resourcePath.replace('\\', '/');
        String trimmed =
                normalized.endsWith("/")
                        ? normalized.substring(0, normalized.length() - 1)
                        : normalized;

        if (trimmed.isEmpty()) {
            return "classpath:unknown";
        }

        int lastSlash = trimmed.lastIndexOf('/');
        if (lastSlash < 0) {
            return "classpath:" + trimmed;
        }

        int secondLastSlash = trimmed.lastIndexOf('/', lastSlash - 1);
        if (secondLastSlash < 0) {
            return "classpath:" + trimmed.substring(lastSlash + 1);
        }

        return "classpath:" + trimmed.substring(secondLastSlash + 1);
    }

    @Override
    public void setWriteable(boolean writeable) {
        logger.warn("ClasspathSkillRepository is read-only, set writeable operation ignored");
    }

    @Override
    public boolean isWriteable() {
        return false;
    }

    /**
     * Checks if running in a JAR environment.
     *
     * @return true if running from JAR, false if in development environment
     */
    public boolean isJarEnvironment() {
        return isJar;
    }

    private void checkNotClosed() {
        if (closed.get()) {
            throw new IllegalStateException("ClasspathSkillRepository has been closed");
        }
    }

    /**
     * Closes the file system (if in JAR environment).
     *
     * <p>This method is idempotent - it can be safely called multiple times.
     * Designed for use with try-with-resources.
     */
    @Override
    public void close() {
        if (!closed.compareAndSet(false, true)) {
            return;
        }
        if (fileSystem != null) {
            try {
                fileSystem.close();
            } catch (IOException e) {
                throw new RuntimeException("Failed to close classpath file system", e);
            }
        }
    }
}

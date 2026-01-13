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
package io.agentscope.core.skill.util;

import io.agentscope.core.skill.AgentSkill;
import io.agentscope.core.skill.repository.FileSystemSkillRepository;
import java.io.IOException;
import java.net.URI;
import java.net.URISyntaxException;
import java.net.URL;
import java.nio.file.FileSystem;
import java.nio.file.FileSystems;
import java.nio.file.Path;
import java.util.Collections;
import java.util.List;

/**
 * JarSkillRepositoryAdapter - Adapter that enables FileSystemSkillRepository to load skills from
 * JAR files.
 *
 * <p>This adapter bridges the gap between JAR resources and file system based skill loading by:
 * <ol>
 *   <li>Creating a virtual file system for resources within JAR files</li>
 *   <li>Obtaining Path objects for skill directories</li>
 *   <li>Delegating to the existing FileSystemSkillRepository for skill loading</li>
 * </ol>
 *
 * <p><b>Important:</b> Skills must be organized in a parent directory structure. You should pass
 * the parent directory name (not individual skill paths) to the adapter. This is required because
 * the adapter uses {@link FileSystemSkillRepository} internally, which scans a directory for
 * multiple skill subdirectories.
 *
 * <p><b>Directory Structure:</b>
 * <pre>
 * resources/
 * └── skills/              ← Pass "skills" to adapter
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
 * try (JarSkillRepositoryAdapter adapter =
 *         new JarSkillRepositoryAdapter("skills")) {
 *     // Get all available skill names
 *     List<String> skillNames = adapter.getAllSkillNames(); // ["skill-a", "skill-b", "skill-c"]
 *
 *     // Load a specific skill
 *     AgentSkill skillA = adapter.getSkill("skill-a");
 *
 *     // Load all skills at once
 *     List<AgentSkill> allSkills = adapter.getAllSkills();
 * }
 * }</pre>
 */
public class JarSkillRepositoryAdapter implements AutoCloseable {

    private final FileSystem fileSystem;
    private final Path skillBasePath;
    private final FileSystemSkillRepository repository;
    private final boolean isJar;
    private boolean closed = false;

    /**
     * Creates an adapter for loading skills from resources.
     *
     * @param resourcePath The path to the skill under resources, e.g., "writing-skills"
     * @throws IOException if initialization fails
     */
    public JarSkillRepositoryAdapter(String resourcePath) throws IOException {
        this(resourcePath, JarSkillRepositoryAdapter.class.getClassLoader());
    }

    /**
     * Creates an adapter for loading skills from resources using a specific ClassLoader.
     *
     * @param resourcePath The path to the skill under resources, e.g., "writing-skills"
     * @param classLoader The ClassLoader to use for loading resources
     * @throws IOException if initialization fails
     */
    protected JarSkillRepositoryAdapter(String resourcePath, ClassLoader classLoader)
            throws IOException {
        try {
            URL resourceUrl = classLoader.getResource(resourcePath);

            if (resourceUrl == null) {
                throw new IOException("Resource not found: " + resourcePath);
            }

            URI uri = resourceUrl.toURI();

            if (uri.getScheme().equals("jar")) {
                // JAR environment: create virtual file system
                this.isJar = true;
                this.fileSystem = FileSystems.newFileSystem(uri, Collections.emptyMap());
                this.skillBasePath = fileSystem.getPath("/" + resourcePath);
            } else {
                // Development environment: use file system path directly
                this.isJar = false;
                this.fileSystem = null;
                this.skillBasePath = Path.of(uri);
            }

            // Use FileSystemSkillRepository for actual skill loading
            this.repository = new FileSystemSkillRepository(skillBasePath, false);

        } catch (URISyntaxException e) {
            throw new IOException("Invalid resource URI", e);
        }
    }

    /**
     * Gets a skill by name.
     *
     * @param skillName The skill name (directory name)
     * @return The loaded AgentSkill object
     * @throws IllegalStateException if the adapter has been closed
     */
    public AgentSkill getSkill(String skillName) {
        checkNotClosed();
        return repository.getSkill(skillName);
    }

    /**
     * Gets all skill names available in the repository.
     *
     * @return A sorted list of skill names
     * @throws IllegalStateException if the adapter has been closed
     */
    public List<String> getAllSkillNames() {
        checkNotClosed();
        return repository.getAllSkillNames();
    }

    /**
     * Gets all skills available in the repository.
     *
     * @return A list of all loaded AgentSkill objects
     * @throws IllegalStateException if the adapter has been closed
     */
    public List<AgentSkill> getAllSkills() {
        checkNotClosed();
        return repository.getAllSkills();
    }

    /**
     * Checks if running in a JAR environment.
     *
     * @return true if running from JAR, false if in development environment
     */
    public boolean isJarEnvironment() {
        return isJar;
    }

    /**
     * Checks if the adapter has been closed.
     *
     * @throws IllegalStateException if the adapter has been closed
     */
    private void checkNotClosed() {
        if (closed) {
            throw new IllegalStateException("JarSkillRepositoryAdapter has been closed");
        }
    }

    /**
     * Closes the file system (if in JAR environment).
     *
     * <p>This method is idempotent - it can be safely called multiple times.
     * Designed for use with try-with-resources.
     *
     * @throws IOException if an I/O error occurs during closing
     */
    @Override
    public void close() throws IOException {
        if (closed) {
            return;
        }
        if (fileSystem != null) {
            fileSystem.close();
        }
        closed = true;
    }
}

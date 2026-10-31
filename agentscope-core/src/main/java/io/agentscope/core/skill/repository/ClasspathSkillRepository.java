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
import io.agentscope.core.skill.mirror.SkillMirrorStore;
import io.agentscope.core.skill.util.SkillFileSystemHelper;
import io.agentscope.core.skill.util.SkillUtil;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.net.URI;
import java.net.URISyntaxException;
import java.net.URL;
import java.nio.file.FileSystem;
import java.nio.file.FileSystemNotFoundException;
import java.nio.file.FileSystems;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicBoolean;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * ClasspathSkillRepository - Loads skills from classpath resources and JAR files.
 *
 * <p>Versions are content-addressed: each skill's version is the {@link
 * SkillUtil#hashSkillDirectory(Path)} token over its complete file set, so the same bytes
 * always mean the same version — across rebuilds, JVM runs and processes sharing a cache
 * root. {@link #getSkill(String, String)} serves the current token; any other
 * version reference yields {@code null}, because the classpath holds each skill's bytes only
 * at their current token.
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
 *     // Latest pull by name — the serving read
 *     AgentSkill skillA = repository.getSkill("skill-a");
 * }
 * }</pre>
 *
 */
public class ClasspathSkillRepository implements AgentSkillRepository {

    private static final Object FILE_SYSTEM_MONITOR = new Object();

    private static final Map<URI, SharedFileSystemRef> SHARED_FILE_SYSTEMS = new HashMap<>();

    private final Logger logger = LoggerFactory.getLogger(ClasspathSkillRepository.class);

    private final FileSystem fileSystem;
    private final Path skillBasePath;
    private final boolean isJar;
    private final URI jarFileSystemUri;
    private final String source;
    private final String resourcePath;
    private final AtomicBoolean closed = new AtomicBoolean(false);

    /**
     * Resolved extraction cache root (null in a directory-backed environment): the first pull
     * of a skill at a version extracts its files from the JAR into that version's cache
     * directory, so every later pull — in this run or a later one sharing the root — is a
     * local read while the content is unchanged. Resolved at construction to fail fast on an
     * unresolvable root; the store is acquired lazily through {@link
     * SkillMirrorStore#of(Path)} on first serve, so construction never touches the disk.
     */
    private final Path cacheRoot;

    /** Cache identity shared by all skills of this repository. */
    private final String repoIdentity;

    /**
     * Content-addressed version per skill, keyed by the SKILL.md metadata name and minted
     * once at construction ({@link SkillUtil#hashSkillDirectory} over each skill's complete
     * file set) — JAR resources are immutable within a JVM run, so the tokens hold for the
     * repository's lifetime. Null in a directory-backed environment, whose live tree is
     * re-hashed per call instead.
     */
    private final Map<String, String> jarSkillTokens;

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
     * Creates a repository for loading skills from resources with an explicit extraction
     * cache directory (used only in JAR environments; a directory-backed repository serves
     * its own tree).
     *
     * @param resourcePath The path to the skill under resources, e.g., "writing-skills"
     * @param cacheDir explicit cache root for the JAR-extraction store (null resolves the
     *     property / default root)
     * @throws IOException if initialization fails
     */
    public ClasspathSkillRepository(String resourcePath, Path cacheDir) throws IOException {
        this(resourcePath, null, ClasspathSkillRepository.class.getClassLoader(), cacheDir);
    }

    /**
     * Creates a repository for loading skills from resources with a custom source and an
     * explicit extraction cache directory (JAR environments only).
     *
     * @param resourcePath The path to the skill under resources, e.g., "writing-skills"
     * @param source The custom source identifier (null to use default)
     * @param cacheDir explicit cache root for the JAR-extraction store (null resolves the
     *     property / default root)
     * @throws IOException if initialization fails
     */
    public ClasspathSkillRepository(String resourcePath, String source, Path cacheDir)
            throws IOException {
        this(resourcePath, source, ClasspathSkillRepository.class.getClassLoader(), cacheDir);
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
        this(resourcePath, source, classLoader, null);
    }

    /**
     * Creates a repository for loading skills from resources using a specific ClassLoader and
     * an explicit extraction cache directory (JAR environments only).
     *
     * @param resourcePath The path to the skill under resources, e.g., "writing-skills"
     * @param source The custom source identifier (null to use default)
     * @param classLoader The ClassLoader to use for loading resources
     * @param cacheDir explicit cache root for the JAR-extraction store (null resolves the
     *     property / default root)
     * @throws IOException if initialization fails
     */
    protected ClasspathSkillRepository(
            String resourcePath, String source, ClassLoader classLoader, Path cacheDir)
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
                this.jarFileSystemUri = toFileSystemUri(uri);
                this.fileSystem = acquireFileSystem(jarFileSystemUri);
                String schemeSpecificUriPath = uri.getSchemeSpecificPart();
                String actualResourcePath =
                        schemeSpecificUriPath.substring(schemeSpecificUriPath.lastIndexOf("!") + 1);
                logger.info("Actual resource path: {}", actualResourcePath);
                this.skillBasePath = fileSystem.getPath(actualResourcePath);
                this.repoIdentity = SkillMirrorStore.repoIdentity("classpath", resourcePath);
                // Fail fast on an unresolvable cache root; the store itself is bound
                // lazily on first serve.
                this.cacheRoot = SkillMirrorStore.resolveBuiltinRoot(cacheDir, repoIdentity);
            } else {
                this.isJar = false;
                this.fileSystem = null;
                this.jarFileSystemUri = null;
                this.skillBasePath = Path.of(uri);
                // A real directory serves itself — no extraction cache, originDir = the dir.
                this.repoIdentity = null;
                this.cacheRoot = null;
            }
            logger.info("is in Jar environment: {}", this.isJar);
            this.source = source != null ? source : buildDefaultSource(resourcePath);
            // JAR resources are immutable within a JVM run, so every skill's content
            // version is minted once here and later serves are map lookups; a directory
            // tree is live, so its version is re-hashed per call.
            this.jarSkillTokens = isJar ? mintJarSkillTokens() : null;

        } catch (URISyntaxException e) {
            throw new IOException("Invalid resource URI", e);
        }
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
        List<AgentSkill> skills =
                new ArrayList<>(SkillFileSystemHelper.getAllSkills(skillBasePath, source));
        skills.replaceAll(this::withCurrentVersion);
        return skills;
    }

    /**
     * Serves the skill at its content-addressed version — the {@link
     * SkillUtil#hashSkillDirectory} token over the skill's complete file set. {@code
     * null}/blank resolves and stamps the current version; any other reference yields {@code
     * null}, because the classpath holds each skill's bytes only at their current token.
     *
     * <p><b>JAR environments:</b> the first pull at a token extracts the skill's files from
     * the JAR into that token's cache directory; the returned summary carries {@code
     * originDir} = that directory and an empty resource map, and every later pull —
     * including in a later JVM run sharing the cache root — is a local read while the
     * content is unchanged. <b>Directory environments:</b> the resource directory is served
     * directly with {@code originDir} set, and the version is re-hashed per call because
     * the tree is live.
     *
     * @param name the skill name to read
     * @param version the version reference to resolve ({@code null}/blank = current)
     * @return the skill at its content-addressed version, or {@code null} for a null/blank
     *     name, a missing skill or a reference other than the current version
     */
    @Override
    public AgentSkill getSkill(String name, String version) {
        if (name == null || name.isBlank()) {
            return null;
        }
        checkNotClosed();
        if (jarSkillTokens != null) {
            String token = jarSkillTokens.get(name);
            if (token == null) {
                return null;
            }
            if (version != null && !version.isBlank() && !token.equals(version)) {
                return null;
            }
            return SkillMirrorStore.of(cacheRoot)
                    .serve(
                            repoIdentity,
                            name,
                            token,
                            source,
                            () -> {
                                try {
                                    return Optional.of(
                                            SkillFileSystemHelper.loadSkill(
                                                    skillBasePath, name, source));
                                } catch (IllegalArgumentException e) {
                                    return Optional.empty();
                                }
                            })
                    .orElse(null);
        }
        // Directory branch — summary form (SKILL.md only, resources behind originDir);
        // routing through the default getSkill would recurse back here.
        if (!skillExists(name)) {
            return null;
        }
        AgentSkill skill = SkillFileSystemHelper.loadSkill(skillBasePath, name, source, false);
        String token = hashSkillVersion(skill.getOriginDir().orElseThrow());
        if (version != null && !version.isBlank() && !token.equals(version)) {
            return null;
        }
        return skill.toBuilder().version(token).build();
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

    /**
     * Records the skill's current content-addressed version on a loaded skill, so every
     * instance this repository returns carries its own version: JAR skills read their
     * construction-time token; a live directory tree is re-hashed per call. {@code null}
     * input passes through untouched.
     */
    private AgentSkill withCurrentVersion(AgentSkill skill) {
        if (skill == null) {
            return null;
        }
        String token =
                jarSkillTokens != null
                        ? jarSkillTokens.get(skill.getName())
                        : hashSkillVersion(skill.getOriginDir().orElseThrow());
        return skill.toBuilder().version(token).build();
    }

    /**
     * One pass at construction: every parseable skill directory keyed by its SKILL.md
     * metadata name, valued by its content-addressed version. Unreadable entries the
     * listing helper skips are exactly the skills serving must refuse anyway.
     */
    private Map<String, String> mintJarSkillTokens() {
        Map<String, String> tokens = new HashMap<>();
        for (AgentSkill summary :
                SkillFileSystemHelper.getAllSkills(skillBasePath, source, false)) {
            summary.getOriginDir()
                    .ifPresent(dir -> tokens.put(summary.getName(), hashSkillVersion(dir)));
        }
        return Collections.unmodifiableMap(tokens);
    }

    /**
     * The content-addressed version of a skill directory: the hash of its complete file
     * set, so the same bytes carry the same version wherever and whenever they are hashed.
     */
    private static String hashSkillVersion(Path skillDir) {
        try {
            return SkillUtil.hashSkillDirectory(skillDir);
        } catch (IOException e) {
            throw new UncheckedIOException(
                    "Cannot mint the content version of skill directory: " + skillDir, e);
        }
    }

    private String buildDefaultSource(String resourcePath) {
        String normalized = resourcePath == null ? "" : resourcePath.replace('\\', '/');
        String trimmed =
                normalized.endsWith("/")
                        ? normalized.substring(0, normalized.length() - 1)
                        : normalized;

        if (trimmed.isEmpty()) {
            return "classpath-unknown";
        }

        int lastSlash = trimmed.lastIndexOf('/');
        if (lastSlash < 0) {
            return "classpath-" + trimmed;
        }

        int secondLastSlash = trimmed.lastIndexOf('/', lastSlash - 1);
        if (secondLastSlash < 0) {
            return "classpath-" + trimmed.substring(lastSlash + 1);
        }

        return "classpath-" + trimmed.substring(secondLastSlash + 1);
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

    private static URI toFileSystemUri(URI resourceUri) {
        String schemeSpecificPart = resourceUri.getSchemeSpecificPart();
        int lastBang = schemeSpecificPart.lastIndexOf('!');
        if (lastBang < 0) {
            return resourceUri;
        }
        return URI.create(
                resourceUri.getScheme() + ":" + schemeSpecificPart.substring(0, lastBang));
    }

    private static FileSystem acquireFileSystem(URI uri) throws IOException {
        synchronized (FILE_SYSTEM_MONITOR) {
            SharedFileSystemRef existingRef = SHARED_FILE_SYSTEMS.get(uri);
            if (existingRef != null && existingRef.fileSystem.isOpen()) {
                existingRef.refCount++;
                return existingRef.fileSystem;
            }
            SHARED_FILE_SYSTEMS.remove(uri);

            FileSystem fileSystem;
            boolean closeWhenUnused = false;
            try {
                fileSystem = FileSystems.getFileSystem(uri);
            } catch (FileSystemNotFoundException ignored) {
                fileSystem = FileSystems.newFileSystem(uri, Collections.emptyMap());
                closeWhenUnused = true;
            }

            SHARED_FILE_SYSTEMS.put(uri, new SharedFileSystemRef(fileSystem, closeWhenUnused));
            return fileSystem;
        }
    }

    private static void releaseFileSystem(URI uri) throws IOException {
        synchronized (FILE_SYSTEM_MONITOR) {
            SharedFileSystemRef ref = SHARED_FILE_SYSTEMS.get(uri);
            if (ref == null) {
                return;
            }

            ref.refCount--;
            if (ref.refCount > 0) {
                return;
            }

            SHARED_FILE_SYSTEMS.remove(uri);
            if (ref.closeWhenUnused && ref.fileSystem.isOpen()) {
                ref.fileSystem.close();
            }
        }
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
        if (jarFileSystemUri != null) {
            try {
                releaseFileSystem(jarFileSystemUri);
            } catch (IOException e) {
                throw new RuntimeException("Failed to close classpath file system", e);
            }
        }
    }

    private static final class SharedFileSystemRef {

        private final FileSystem fileSystem;
        private final boolean closeWhenUnused;
        private int refCount = 1;

        private SharedFileSystemRef(FileSystem fileSystem, boolean closeWhenUnused) {
            this.fileSystem = fileSystem;
            this.closeWhenUnused = closeWhenUnused;
        }
    }
}

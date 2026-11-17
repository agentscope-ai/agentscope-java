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
package io.agentscope.core.skill.mirror;

import io.agentscope.core.skill.AgentSkill;
import io.agentscope.core.skill.util.MarkdownSkillParser;
import io.agentscope.core.skill.util.SkillUtil;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.FileAlreadyExistsException;
import java.nio.file.FileSystemException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.sql.Connection;
import java.util.Base64;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.locks.ReentrantLock;
import java.util.function.Supplier;
import java.util.regex.Pattern;
import javax.sql.DataSource;

/**
 * A version-keyed materialization cache for repository-backed skills under a shared cache
 * root (default {@code ~/.agentscope/skill-cache/<repoIdentity>}; see {@link
 * #resolveBuiltinRoot(Path, String)}). The tree is pure cache state, shared across processes
 * and agents: it may be deleted out of band at any time — a miss is re-pulled, never an
 * error — and a repository's {@code close()} never deletes it; reclamation belongs to the
 * operator.
 *
 * <p>Layout: {@code <root>/<repoIdentity>/<skillName>/<version>/}. The version directory is
 * named after the version string itself — verbatim when already a safe directory name
 * ("1.0.0"), sanitized with a short hash suffix otherwise — so every process derives the
 * same path for the same key. A version directory holds exactly a standard skill package
 * ({@code SKILL.md} plus resources); the store adds no housekeeping files of its own.
 *
 * <p>Version directories are immutable once installed: content is written to a temporary
 * sibling and atomically moved into place, and a complete directory (one holding {@code
 * SKILL.md}) is reused, never rewritten or re-fetched. Concurrent materialization within one
 * process is deduplicated by a key lock; across processes the atomic move elects a single
 * winner, so the worst case is one redundant fetch, never a torn directory. There is no
 * in-process eviction — the tree only grows.
 *
 * <p>Instances are stateless (a store binds only its root): {@link #of(Path)} returns a
 * fresh instance per call, and correctness never depends on instance identity.
 *
 * @author shanhongyu
 */
public class SkillMirrorStore {

    /** The skill manifest, materialized at the root of every version directory. */
    static final String SKILL_FILE = "SKILL.md";

    /**
     * System property overriding the default cache root (global, for tests and operations).
     * When set, every root resolved without an explicit {@code cacheDir} lives under this
     * path.
     */
    public static final String CACHE_DIR_PROPERTY = "agentscope.skill.cache.dir";

    /** Default cache home under the OS user's home directory. */
    private static final String DEFAULT_CACHE_HOME = ".agentscope/skill-cache";

    /** Marker prefix for binary resources carried in the in-memory transport form. */
    private static final String BASE64_PREFIX = "base64:";

    /** Prefix of in-flight temporary sibling directories pending their atomic move. */
    private static final String TMP_PREFIX = ".tmp-";

    /** Caps the sanitized part of a version directory name to keep full paths Windows-safe. */
    private static final int MAX_VERSION_DIR_NAME = 64;

    /**
     * Windows-reserved device names ("nul", "com1", also with an extension such as
     * "nul.txt"), case-insensitive — never usable as a verbatim directory name.
     */
    private static final Pattern RESERVED_DIR_NAME =
            Pattern.compile("(?i)^(con|prn|aux|nul|com[1-9]|lpt[1-9])(\\..*)?$");

    /**
     * Process-wide key locks deduplicating concurrent materialization of the same key.
     * Entries are removed best-effort once uncontended (see {@link #materialize}) so the map
     * stays bounded by in-flight keys rather than by every key ever served.
     */
    private static final ConcurrentHashMap<String, ReentrantLock> KEY_LOCKS =
            new ConcurrentHashMap<>();

    private final Path root;

    /**
     * Creates a store bound to the given cache root. Pure — the root directory is created
     * lazily by the first install ({@code Files.createDirectories} on the version
     * directory's parent), so acquiring a store per serve ({@link #of(Path)}) costs one
     * allocation and zero filesystem calls.
     *
     * @param root the cache root directory (never null)
     */
    public SkillMirrorStore(Path root) {
        this.root = Objects.requireNonNull(root, "root must not be null");
    }

    /**
     * Returns a fresh store bound to the given root, normalized (absolute, redundant
     * segments collapsed) so every spelling of one root shares the same key-lock identity.
     * A repository typically resolves its root once at construction (see {@link
     * #resolveBuiltinRoot(Path, String)}) and acquires the store here, where it serves; the
     * root directory is created by the constructor, so repository construction stays
     * disk-free.
     *
     * @param root the cache root directory (never null; a relative path resolves against the
     *     current working directory)
     * @return a new store bound to the normalized root
     */
    public static SkillMirrorStore of(Path root) {
        Objects.requireNonNull(root, "root must not be null");
        return new SkillMirrorStore(root.toAbsolutePath().normalize());
    }

    /**
     * Resolves the cache root for a repository: explicit {@code cacheDir} &gt; the {@value
     * #CACHE_DIR_PROPERTY} system property &gt; {@code ~/.agentscope/skill-cache/}. The root
     * is identity-free on every path — the store layout itself appends {@code
     * <repoIdentity>/<skill>/<version>}, so an explicit root and the default produce the
     * same on-disk shape. Pure — no disk access — so repositories can call it at
     * construction, fail fast on an unresolvable root, and bind a store to the result
     * lazily.
     */
    public static Path resolveBuiltinRoot(Path explicitCacheDir, String repoIdentity) {
        if (explicitCacheDir != null) {
            return explicitCacheDir;
        }
        String override = System.getProperty(CACHE_DIR_PROPERTY);
        if (override != null && !override.isBlank()) {
            // Pinned absolute so every process derives the same tree regardless of its working
            // directory; a relative override would fork one cache per CWD.
            return Path.of(override.trim()).toAbsolutePath();
        }
        String home = System.getProperty("user.home");
        if (home == null || home.isBlank()) {
            throw new IllegalArgumentException(
                    "Cannot resolve the default skill cache root for repository '"
                            + repoIdentity
                            + "': user.home is not set and neither an explicit cacheDir nor"
                            + " the "
                            + CACHE_DIR_PROPERTY
                            + " system property provides one");
        }
        return Path.of(home, DEFAULT_CACHE_HOME.split("/"));
    }

    /** Returns the cache root this store serves. */
    public Path root() {
        return root;
    }

    /**
     * Builds the repository identity directory name from a repository type and location —
     * {@code repositoryType_location} reduced to a safe directory name by the same rules as
     * {@link #versionDirName(String)}: verbatim only when already fully safe, sanitized with
     * a short hash suffix otherwise.
     *
     * @param repositoryType the repository type marker (never null)
     * @param location the backend-native location string (never null)
     * @return a safe directory name unique to the (type, location) pair
     */
    public static String repoIdentity(String repositoryType, String location) {
        return versionDirName(repositoryType + "_" + location);
    }

    /**
     * Location string for a DataSource-backed repository's cache identity: the JDBC URL of
     * the instance behind {@code dataSource} plus the repository's own location marker — two
     * repositories over different databases must never share one cache tree, even when their
     * table and namespace names match. Best-effort and read lazily (it opens a connection):
     * when the URL cannot be read, the plain location is returned and an explicit {@code
     * cacheDir} remains the disambiguator.
     *
     * @param dataSource the data source whose instance URL is read (never null)
     * @param location the repository's own location marker (never null)
     * @return {@code <url>@<location>} when the URL is readable, else {@code location}
     */
    public static String dataSourceIdentity(DataSource dataSource, String location) {
        try (Connection connection = dataSource.getConnection()) {
            String url = connection.getMetaData().getURL();
            if (url != null && !url.isBlank()) {
                return url + "@" + location;
            }
        } catch (Exception e) {
            // URL unreadable (mocked data source, exhausted pool): fall back to the marker.
        }
        return location;
    }

    /**
     * Returns the local directory of {@code (repoIdentity, name, version)} when a complete
     * materialization exists — a hit requires {@code SKILL.md}, so an incomplete or
     * concurrently deleted directory is a miss, not an error. Unusable inputs (an invalid or
     * Windows-reserved skill name, a null or blank version) are a miss too, mirroring {@link
     * #serve}.
     *
     * @param repoIdentity the repository identity (never null)
     * @param name the requested skill name (validated here; never null)
     * @param version the full version reference; null or blank is a miss (never a valid key)
     * @return the materialized directory, or {@code empty()} on miss
     */
    public Optional<Path> lookup(String repoIdentity, String name, String version) {
        if (!isUsableNameSegment(name) || version == null || version.isBlank()) {
            return Optional.empty();
        }
        Path dir = versionDir(repoIdentity, name, versionDirName(version));
        if (isComplete(dir)) {
            return Optional.of(dir);
        }
        return Optional.empty();
    }

    /**
     * Materializes {@code (repoIdentity, name, version)}: under the process-level key lock,
     * re-checks the cache, then invokes {@code fetcher} (the repository's {@code
     * getSkill} wrapper) and atomically installs the returned skill as an immutable
     * version directory — unless a complete directory already exists, which is then reused
     * without invoking the fetcher. A fetcher miss ({@code empty()} — an unreachable
     * version) installs nothing and returns {@code empty()}; the caller applies its own
     * unreachable-version policy.
     *
     * <p>Unusable inputs fail fast: an invalid or Windows-reserved name, a null or blank
     * version, or a fetched skill whose metadata name differs from {@code name} — a
     * mismatched entry would be installed yet forever unreadable, since the summary reader
     * refuses a manifest that disagrees with its key.
     *
     * @param repoIdentity the repository identity (never null)
     * @param name the skill name (validated; never null)
     * @param version the full version reference (never null or blank)
     * @param fetcher supplies the complete skill for this version (never null)
     * @return the materialized (or reused) directory, or {@code empty()} when the fetcher
     *     reports the version unreachable
     */
    public Optional<Path> materialize(
            String repoIdentity,
            String name,
            String version,
            Supplier<Optional<AgentSkill>> fetcher) {
        SkillUtil.requireValidSkillName(name);
        if (RESERVED_DIR_NAME.matcher(name).matches()) {
            throw new IllegalArgumentException(
                    "Skill name '"
                            + name
                            + "' is a Windows-reserved device name and cannot become a mirror"
                            + " directory segment");
        }
        Objects.requireNonNull(version, "version must not be null");
        if (version.isBlank()) {
            throw new IllegalArgumentException("version must not be blank");
        }
        Objects.requireNonNull(fetcher, "fetcher must not be null");
        String dirName = versionDirName(version);
        String key = lockKey(repoIdentity, name, dirName);
        ReentrantLock lock = KEY_LOCKS.computeIfAbsent(key, k -> new ReentrantLock());
        lock.lock();
        try {
            Path dir = versionDir(repoIdentity, name, dirName);
            if (isComplete(dir)) {
                return Optional.of(dir);
            }
            Optional<AgentSkill> fetched = fetcher.get();
            if (fetched.isEmpty()) {
                return Optional.empty();
            }
            AgentSkill skill = fetched.get();
            if (!Objects.equals(skill.getName(), name)) {
                throw new IllegalArgumentException(
                        "Fetched skill declares name '"
                                + skill.getName()
                                + "' but is keyed as '"
                                + name
                                + "'; refusing to install a mismatched entry");
            }
            return Optional.of(install(repoIdentity, name, dirName, skill));
        } finally {
            lock.unlock();
            // Best-effort removal keeps the lock set bounded by in-flight keys, not by every
            // key ever served. A benign race (the entry vanishes while a late waiter still
            // holds the old lock) costs at most one redundant fetch — the atomic move still
            // elects a single installer.
            if (!lock.hasQueuedThreads()) {
                KEY_LOCKS.remove(key, lock);
            }
        }
    }

    /**
     * Serves {@code (repoIdentity, name, version)} through the cache and returns the summary
     * form that {@link
     * io.agentscope.core.skill.repository.AgentSkillRepository#getSkill(String,
     * String)} requires: a hit is a pure local read, a miss fetches through {@code fetcher}
     * and installs the skill before the summary is parsed. The returned instance carries the
     * version stamp, an empty in-memory resource map (content lives on disk) and {@code
     * originDir} pointing at the version directory.
     *
     * <p>Blank or otherwise unusable names and blank versions yield {@code empty()} without
     * contacting the fetcher, so repositories keep their {@code null}-on-unresolvable
     * semantics. A complete directory that fails to parse into a summary (corrupted or
     * foreign manifest — not a fetch miss) is self-healing: the entry is evicted and rebuilt
     * once through the fetcher, so a bad cache never poisons the key permanently.
     *
     * @param repoIdentity the repository identity (never null)
     * @param name the requested skill name
     * @param version the resolved, non-blank version reference to serve at
     * @param source the source identifier stamped on the summary (never null)
     * @param fetcher supplies the complete in-memory skill for this version on cache miss
     * @return the summary-form skill, or {@code empty()} when the inputs are unusable or the
     *     fetcher reports the version unreachable
     */
    public Optional<AgentSkill> serve(
            String repoIdentity,
            String name,
            String version,
            String source,
            Supplier<Optional<AgentSkill>> fetcher) {
        if (!isUsableNameSegment(name) || version == null || version.isBlank()) {
            return Optional.empty();
        }
        Optional<Path> cached = materialize(repoIdentity, name, version, fetcher);
        if (cached.isEmpty()) {
            return Optional.empty();
        }
        AgentSkill summary =
                SkillSummaryReader.readFromDirectory(cached.get(), name, version, source);
        if (summary != null) {
            return Optional.of(summary);
        }
        // A complete-but-unparseable entry is corruption, not a fetch miss — evict it and
        // rebuild once; leaving it in place would fail every later serve of this key.
        evict(repoIdentity, name, version);
        return materialize(repoIdentity, name, version, fetcher)
                .flatMap(
                        dir ->
                                Optional.ofNullable(
                                        SkillSummaryReader.readFromDirectory(
                                                dir, name, version, source)));
    }

    /**
     * Removes the version directory of {@code (repoIdentity, name, version)}, if present —
     * the counterpart of out-of-band deletion, for callers that verified an entry is bad
     * (an unparseable manifest) and want the next materialization to start clean. Takes the
     * same key lock as {@link #materialize} so an eviction cannot interleave with a
     * concurrent install of the same key; missing directories are a no-op.
     *
     * @param repoIdentity the repository identity (never null)
     * @param name the skill name (validated here; never null)
     * @param version the full version reference (never null or blank)
     */
    public void evict(String repoIdentity, String name, String version) {
        if (!isUsableNameSegment(name) || version == null || version.isBlank()) {
            return;
        }
        String dirName = versionDirName(version);
        String key = lockKey(repoIdentity, name, dirName);
        ReentrantLock lock = KEY_LOCKS.computeIfAbsent(key, k -> new ReentrantLock());
        lock.lock();
        try {
            deleteRecursively(versionDir(repoIdentity, name, dirName));
        } finally {
            lock.unlock();
            if (!lock.hasQueuedThreads()) {
                KEY_LOCKS.remove(key, lock);
            }
        }
    }

    /** Builds the lock key: root plus the three key segments. */
    private String lockKey(String repoIdentity, String name, String dirName) {
        return root + "|" + repoIdentity + "|" + name + "|" + dirName;
    }

    /**
     * Whether a skill name is usable as a mirror path segment: valid per {@link
     * SkillUtil#isValidSkillName} and not a Windows-reserved device name ("nul", "com1",
     * ...). The name pattern alone admits the reserved names, yet they cannot host a
     * directory on Windows; the version ({@link #versionDirName}) and repo-identity
     * segments already reject them — this extends the same rule to the name segment.
     */
    private static boolean isUsableNameSegment(String name) {
        return SkillUtil.isValidSkillName(name) && !RESERVED_DIR_NAME.matcher(name).matches();
    }

    /**
     * The directory name of a version (and, via {@link #repoIdentity(String, String)}, of a
     * repository identity): the string verbatim when it is already a safe directory name,
     * otherwise sanitized with a short hash suffix of the original string so distinct inputs
     * keep distinct directories. Verbatim use requires that sanitization changed nothing (any
     * change loses information) and that the name survives every supported filesystem: no
     * uppercase (case-insensitive filesystems would collide "V1" with "v1"), no
     * Windows-reserved device name, no trailing dot, bounded length. The mapping is
     * deterministic, so all processes agree on the path of a given key.
     */
    static String versionDirName(String version) {
        String safe = sanitizeDirChars(version);
        if (safe.equals(version) && isVerbatimDirName(safe)) {
            return safe;
        }
        String truncated =
                safe.length() > MAX_VERSION_DIR_NAME
                        ? safe.substring(0, MAX_VERSION_DIR_NAME)
                        : safe;
        return truncated + "-" + hashOf(version, 12);
    }

    /** Whether the sanitized name is safe to use verbatim (non-empty, bounded, all-lowercase,
     * no trailing dot, not a Windows-reserved device name). */
    private static boolean isVerbatimDirName(String name) {
        return !name.isEmpty()
                && name.length() <= MAX_VERSION_DIR_NAME
                && name.chars().noneMatch(Character::isUpperCase)
                && !name.endsWith(".")
                && !RESERVED_DIR_NAME.matcher(name).matches();
    }

    /** Replaces every character that is unsafe in a directory name with an underscore. */
    private static String sanitizeDirChars(String raw) {
        StringBuilder safe = new StringBuilder(raw.length());
        for (char c : raw.toCharArray()) {
            safe.append(Character.isLetterOrDigit(c) || c == '.' || c == '-' || c == '_' ? c : '_');
        }
        return safe.toString();
    }

    /**
     * Installs a fetched skill as an immutable version directory: writes a temporary sibling,
     * then atomically moves it into place. {@link FileAlreadyExistsException} means another
     * process won the race — its complete directory becomes the cache entry; an incomplete
     * leftover target (a delete that crashed out of band) is cleared first so the move can
     * succeed on filesystems that cannot replace an existing directory. A resource key that
     * resolves to the manifest is refused — the manifest is regenerated from metadata — and
     * every failure path deletes the temporary sibling before propagating.
     */
    private Path install(String repoIdentity, String name, String dirName, AgentSkill skill) {
        Path target = versionDir(repoIdentity, name, dirName);
        Path parent = target.getParent();
        Path tmp = null;
        try {
            Files.createDirectories(parent);
            tmp = Files.createDirectory(parent.resolve(TMP_PREFIX + UUID.randomUUID()));
            // The transported content is frontmatter-stripped (the standard in-memory form), so
            // the manifest is regenerated from metadata on disk — same convention as
            // SkillFileSystemHelper's save flow; otherwise the summary reader could never
            // recover name/description from the materialized file.
            Files.writeString(
                    tmp.resolve(SKILL_FILE),
                    MarkdownSkillParser.generate(
                            skill.getMetadata(),
                            Objects.requireNonNull(skill.getSkillContent(), "skill content")),
                    StandardCharsets.UTF_8);
            Map<String, String> resources = skill.getResources();
            if (resources != null) {
                for (Map.Entry<String, String> resource : resources.entrySet()) {
                    Path file =
                            SkillUtil.resolveContainedPath(tmp, resource.getKey())
                                    .orElseThrow(
                                            () ->
                                                    new IllegalArgumentException(
                                                            "Resource path escapes skill dir: "
                                                                    + resource.getKey()));
                    if (SKILL_FILE.equalsIgnoreCase(tmp.relativize(file).toString())) {
                        // Case-insensitive on purpose: on case-insensitive filesystems the
                        // resource would clobber the regenerated manifest, and everywhere else
                        // a package carrying both spellings is ambiguous.
                        throw new IllegalArgumentException(
                                "Resources must not contain "
                                        + SKILL_FILE
                                        + "; the manifest is regenerated from metadata: "
                                        + resource.getKey());
                    }
                    Files.createDirectories(file.getParent());
                    String content = resource.getValue();
                    if (content.startsWith(BASE64_PREFIX)) {
                        // Same transport convention as SkillFileSystemHelper/saveSkills: binary
                        // resources travel as base64 text and are decoded on disk write.
                        byte[] decoded;
                        try {
                            decoded =
                                    Base64.getDecoder()
                                            .decode(content.substring(BASE64_PREFIX.length()));
                        } catch (IllegalArgumentException e) {
                            throw new IllegalArgumentException(
                                    "Invalid base64 transport content for resource '"
                                            + resource.getKey()
                                            + "'",
                                    e);
                        }
                        Files.write(file, decoded);
                    } else {
                        Files.writeString(file, content, StandardCharsets.UTF_8);
                    }
                }
            }
            // An incomplete target never holds a valid entry, so clearing it loses nothing;
            // without this, a crash-mid-delete leftover would fail the move forever on
            // filesystems that cannot replace directories (Windows).
            if (!isComplete(target)) {
                deleteRecursively(target);
            }
            Files.move(tmp, target, StandardCopyOption.ATOMIC_MOVE);
        } catch (AtomicMoveNotSupportedException e) {
            throw new UnsupportedOperationException(
                    "Mirror root does not support atomic moves (same-volume required): " + root, e);
        } catch (FileSystemException e) {
            // Both spellings of "something already sits at target": EEXIST maps to
            // FileAlreadyExistsException, while POSIX rename(2) over a non-empty directory
            // surfaces as ENOTEMPTY (a plain FileSystemException). A complete target means
            // another process won the install — adopt its directory; an incomplete one is
            // cleared and the move retried once before giving up.
            if (isComplete(target)) {
                return target;
            }
            deleteRecursively(target);
            try {
                Files.move(tmp, target, StandardCopyOption.ATOMIC_MOVE);
                return target;
            } catch (IOException retry) {
                if (isComplete(target)) {
                    return target;
                }
                throw new IllegalStateException(
                        "Concurrent eviction while materializing (retry resolves it): " + target,
                        retry);
            }
        } catch (IOException e) {
            throw new UncheckedIOException("Cannot materialize skill version: " + target, e);
        } finally {
            // Every failure path — including the RuntimeExceptions thrown for refused
            // resources — must leave no temporary sibling; after a successful move tmp no
            // longer exists and this is a no-op.
            deleteRecursively(tmp);
        }
        return target;
    }

    /** Resolves the version directory path under this store's root. */
    private Path versionDir(String repoIdentity, String name, String dirName) {
        return root.resolve(repoIdentity).resolve(name).resolve(dirName);
    }

    /**
     * A version directory counts as complete (hence reusable) when the skill manifest is
     * present — the atomic move guarantees a directory only ever appears complete, so a
     * missing manifest means eviction is in progress in some process.
     */
    private static boolean isComplete(Path dir) {
        return Files.isDirectory(dir) && Files.isRegularFile(dir.resolve(SKILL_FILE));
    }

    /**
     * Deletes a tree children-first, swallowing per-entry failures — used only for
     * cache-internal cleanup: a failed materialization's temporary sibling, and an
     * incomplete leftover target before its replacement.
     */
    private static void deleteRecursively(Path dir) {
        if (dir == null || !Files.exists(dir)) {
            return;
        }
        List<Path> paths;
        try (var stream = Files.walk(dir)) {
            paths = stream.toList();
        } catch (IOException e) {
            // Cannot even list the tree; leave it to the next sweep.
            return;
        }
        for (int i = paths.size() - 1; i >= 0; i--) {
            deleteQuietly(paths.get(i));
        }
    }

    /** Deletes one entry if present, swallowing individual failures (temp cleanup only). */
    private static void deleteQuietly(Path path) {
        try {
            Files.deleteIfExists(path);
        } catch (IOException e) {
            // Single-file failure during temp cleanup is not observable by callers.
        }
    }

    /** Returns the first {@code hexChars} hex characters of the SHA-256 of {@code value}. */
    static String hashOf(String value, int hexChars) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] hash = digest.digest(value.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(hash).substring(0, Math.min(hexChars, hash.length * 2));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 unavailable", e);
        }
    }
}

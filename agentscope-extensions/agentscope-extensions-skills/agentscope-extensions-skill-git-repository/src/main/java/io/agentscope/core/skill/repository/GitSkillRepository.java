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
import io.agentscope.core.skill.mirror.SkillMirrorStore;
import io.agentscope.core.skill.util.SkillFileSystemHelper;
import io.agentscope.core.skill.util.SkillUtil;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.stream.Stream;
import java.util.stream.StreamSupport;
import org.eclipse.jgit.api.CloneCommand;
import org.eclipse.jgit.api.Git;
import org.eclipse.jgit.api.LsRemoteCommand;
import org.eclipse.jgit.api.errors.InvalidRemoteException;
import org.eclipse.jgit.api.errors.TransportException;
import org.eclipse.jgit.lib.ObjectId;
import org.eclipse.jgit.lib.ProgressMonitor;
import org.eclipse.jgit.lib.Ref;
import org.eclipse.jgit.lib.Repository;
import org.eclipse.jgit.revwalk.RevCommit;
import org.eclipse.jgit.revwalk.RevWalk;
import org.eclipse.jgit.treewalk.TreeWalk;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Git-based read-only implementation of AgentSkillRepository.
 *
 * <p>This repository clones a remote Git repository to a local temporary directory and accesses
 * skills directly from the file system. Synchronization is triggered on read operations
 * ({@link #getSkill(String)}, {@link #getAllSkillNames()}, {@link #getAllSkills()},
 * {@link #skillExists(String)}).
 *
 * <p>Each read performs a lightweight remote reference check; a pull is only executed when the
 * remote HEAD changes, keeping freshness with minimal network overhead.
 *
 * <p>Auto-sync can be disabled via constructors that accept {@code autoSync=false}. When disabled,
 * call {@link #sync()} to initialize and refresh before read operations.
 *
 * <p><b>Features:</b>
 *
 * <ul>
 *   <li>Read-only access to Git repositories
 *   <li>Automatic synchronization on each read operation
 *   <li>Support for both HTTPS and SSH URLs
 *   <li>Branch selection support
 *   <li>Progress logging for Git operations
 *   <li>Automatic cleanup of temporary directories
 * </ul>
 *
 * <p><b>Authentication:</b> This implementation relies on system-level Git configuration for
 * authentication:
 *
 * <ul>
 *   <li>HTTPS: Uses credentials from ~/.gitconfig or credential helpers
 *   <li>SSH: Uses SSH keys from ~/.ssh/ and ssh-agent
 * </ul>
 *
 * <p><b>Usage Examples:</b>
 *
 * <pre>{@code
 * // Public repository with default branch
 * GitSkillRepository repo = new GitSkillRepository(
 *     "https://github.com/agentscope/skills.git"
 * );
 * AgentSkill skill = repo.getSkill("calculator");
 * repo.close();
 *
 * // Specify a branch
 * GitSkillRepository repo = new GitSkillRepository(
 *     "https://github.com/agentscope/skills.git",
 *     "develop"
 * );
 *
 * // Private repository (requires system Git credentials)
 * GitSkillRepository repo = new GitSkillRepository(
 *     "git@github.com:myorg/private-skills.git"
 * );
 * }</pre>
 *
 * <p><b>Temporary Directory:</b> Uses {@link Files#createTempDirectory(String,
 * java.nio.file.attribute.FileAttribute[])} to create a temporary directory. The directory is
 * automatically cleaned up on JVM shutdown or when {@link #close()} is called.
 *
 * <p><b>Versioning:</b> the version token is the resolved commit sha (a sha always
 * identifies the same content). A {@code null}-version pull serves HEAD after the usual
 * sync-on-read; a commit id, tag, branch, or revision expression is also a legal version.
 * Versioned reads materialize the commit tree (JGit blob reads — the working tree is never
 * touched) into the version cache and return stamped summaries.
 *
 * @see FileSystemSkillRepository
 * @see AgentSkillRepository
 */
public class GitSkillRepository implements AgentSkillRepository {

    private static final Logger logger = LoggerFactory.getLogger(GitSkillRepository.class);

    private final String remoteUrl;
    private final String branch;
    private final Path localPath;
    private final String source;
    private final boolean autoSync;
    private final String skillsRoot;
    private final boolean tempDirectory;
    private final Thread shutdownHook;
    private Path skillsPath;
    private volatile String lastRemoteRef;

    /**
     * Version-materialization cache root, resolved at construction (explicit {@code cacheDir}
     * &gt; {@value SkillMirrorStore#CACHE_DIR_PROPERTY} property &gt; default home root). The
     * store itself is only created on first serve ({@link SkillMirrorStore#of(Path)}), so
     * construction never touches the disk.
     */
    private final Path cacheRoot;

    /** The SKILL.md file name used to identify and parse skill directories in the tree. */
    private static final String SKILL_FILE_NAME = "SKILL.md";

    /**
     * Creates a GitSkillRepository with the specified remote URL using the default branch.
     *
     * @param remoteUrl The Git repository URL (HTTPS or SSH)
     * @throws IllegalArgumentException if remoteUrl is null or empty
     * @throws RuntimeException if temporary directory creation fails
     */
    public GitSkillRepository(String remoteUrl) {
        this(remoteUrl, null, null, null, true);
    }

    /**
     * Creates a GitSkillRepository with the specified remote URL and local path.
     *
     * @param remoteUrl The Git repository URL (HTTPS or SSH)
     * @param localPath The local path to clone the repository (null to use temporary directory)
     * @throws IllegalArgumentException if remoteUrl is null or empty
     * @throws RuntimeException if temporary directory creation fails
     */
    public GitSkillRepository(String remoteUrl, Path localPath) {
        this(remoteUrl, null, localPath, null, true);
    }

    /**
     * Creates a GitSkillRepository with the specified remote URL and branch.
     *
     * @param remoteUrl The Git repository URL (HTTPS or SSH)
     * @param branch The branch name to clone (null for remote default branch)
     * @throws IllegalArgumentException if remoteUrl is null or empty
     * @throws RuntimeException if temporary directory creation fails
     */
    public GitSkillRepository(String remoteUrl, String branch) {
        this(remoteUrl, branch, null, null, true);
    }

    /**
     * Creates a GitSkillRepository with the specified remote URL, local path, and source.
     *
     * @param remoteUrl The Git repository URL (HTTPS or SSH)
     * @param localPath The local path to clone the repository (null to use temporary directory)
     * @param source The custom source identifier (null to use default)
     * @throws IllegalArgumentException if remoteUrl is null or empty
     * @throws RuntimeException if temporary directory creation fails
     */
    public GitSkillRepository(String remoteUrl, Path localPath, String source) {
        this(remoteUrl, null, localPath, source, true);
    }

    /**
     * Creates a GitSkillRepository with the specified remote URL, branch, and local path.
     *
     * @param remoteUrl The Git repository URL (HTTPS or SSH)
     * @param branch The branch name to clone (null for remote default branch)
     * @param localPath The local path to clone the repository (null to use temporary directory)
     * @throws IllegalArgumentException if remoteUrl is null or empty
     * @throws RuntimeException if temporary directory creation fails
     */
    public GitSkillRepository(String remoteUrl, String branch, Path localPath) {
        this(remoteUrl, branch, localPath, null, true);
    }

    /**
     * Creates a GitSkillRepository with the specified remote URL and auto-update option.
     *
     * @param remoteUrl The Git repository URL (HTTPS or SSH)
     * @param autoSync Whether to auto-sync on read operations
     * @throws IllegalArgumentException if remoteUrl is null or empty
     * @throws RuntimeException if temporary directory creation fails
     */
    public GitSkillRepository(String remoteUrl, boolean autoSync) {
        this(remoteUrl, null, null, null, autoSync);
    }

    /**
     * Creates a GitSkillRepository with the specified remote URL, branch, local path, and source.
     *
     * @param remoteUrl The Git repository URL (HTTPS or SSH)
     * @param branch The branch name to clone (null for remote default branch)
     * @param localPath The local path to clone the repository (null to use temporary directory)
     * @param source The custom source identifier (null to use default)
     * @throws IllegalArgumentException if remoteUrl is null or empty
     * @throws RuntimeException if temporary directory creation fails
     */
    public GitSkillRepository(String remoteUrl, String branch, Path localPath, String source) {
        this(remoteUrl, branch, localPath, source, true);
    }

    /**
     * Creates a GitSkillRepository with the specified remote URL, branch, local path, source,
     * and auto-update option.
     *
     * @param remoteUrl The Git repository URL (HTTPS or SSH)
     * @param branch The branch name to clone (null for remote default branch)
     * @param localPath The local path to clone the repository (null to use temporary directory)
     * @param source The custom source identifier (null to use default)
     * @param autoSync Whether to auto-sync on read operations
     * @throws IllegalArgumentException if remoteUrl is null or empty
     * @throws RuntimeException if temporary directory creation fails
     */
    public GitSkillRepository(
            String remoteUrl, String branch, Path localPath, String source, boolean autoSync) {
        this(remoteUrl, branch, localPath, source, autoSync, null);
    }

    /**
     * Creates a GitSkillRepository with an explicit in-repo skills root directory.
     *
     * <p>{@code skillsRoot} pins where inside the cloned repository the skill folders live (each
     * containing a {@code SKILL.md}). When non-null, the value is treated as a path relative to the
     * repo root; absolute paths and paths containing {@code ..} segments are rejected. When null,
     * the implementation falls back to the legacy convention: use {@code <repo>/skills/} if it
     * exists, otherwise the repo root.
     *
     * @param remoteUrl  The Git repository URL (HTTPS or SSH)
     * @param branch     The branch name to clone (null for remote default branch)
     * @param localPath  The local path to clone the repository (null to use temporary directory)
     * @param source     The custom source identifier (null to use default)
     * @param autoSync   Whether to auto-sync on read operations
     * @param skillsRoot Optional in-repo subdirectory containing skill folders
     * @throws IllegalArgumentException if {@code remoteUrl} is blank, or {@code skillsRoot} is
     *     absolute / contains a {@code ..} segment / would escape the repo root
     * @throws RuntimeException if temporary directory creation fails
     */
    public GitSkillRepository(
            String remoteUrl,
            String branch,
            Path localPath,
            String source,
            boolean autoSync,
            String skillsRoot) {
        this(remoteUrl, branch, localPath, source, autoSync, skillsRoot, null);
    }

    /**
     * Creates a GitSkillRepository with an explicit in-repo skills root directory and an
     * explicit version-materialization cache root.
     *
     * <p>Every versioned read is materialized into an immutable version directory under the
     * cache root and served in summary form from there; the cache root resolves as explicit
     * {@code cacheDir} &gt; {@value SkillMirrorStore#CACHE_DIR_PROPERTY} system property
     * &gt; {@code ~/.agentscope/skill-cache/<repoIdentity>/}. The cache identity derives
     * from the remote URL, so multiple instances and processes share one tree.
     *
     * @param remoteUrl  The Git repository URL (HTTPS or SSH)
     * @param branch     The branch name to clone (null for remote default branch)
     * @param localPath  The local path to clone the repository (null to use temporary directory)
     * @param source     The custom source identifier (null to use default)
     * @param autoSync   Whether to auto-sync on read operations
     * @param skillsRoot Optional in-repo subdirectory containing skill folders
     * @param cacheDir   Explicit cache root for version materialization (null resolves the
     *                   property / default root; the directory is created on first use, not
     *                   at construction)
     * @throws IllegalArgumentException if {@code remoteUrl} is blank, {@code skillsRoot} is
     *     absolute / contains a {@code ..} segment / would escape the repo root, or no cache
     *     root can be resolved ({@code user.home} unset without an explicit {@code cacheDir}
     *     or property override)
     * @throws RuntimeException if temporary directory creation fails
     */
    public GitSkillRepository(
            String remoteUrl,
            String branch,
            Path localPath,
            String source,
            boolean autoSync,
            String skillsRoot,
            Path cacheDir) {
        if (remoteUrl == null || remoteUrl.trim().isEmpty()) {
            throw new IllegalArgumentException("Remote URL cannot be null or empty");
        }
        this.remoteUrl = remoteUrl.trim();
        this.branch = branch;
        this.source = source;
        this.autoSync = autoSync;
        this.skillsRoot = normalizeSkillsRoot(skillsRoot);
        this.cacheRoot =
                SkillMirrorStore.resolveBuiltinRoot(
                        cacheDir, SkillMirrorStore.repoIdentity("git", remoteUrl.trim()));

        if (localPath != null) {
            // User-specified path - not temporary, will not be auto-deleted
            this.localPath = localPath;
            this.tempDirectory = false;
            this.shutdownHook = null;
            logger.info("Using user-specified directory for Git repository: {}", localPath);
        } else {
            // Create temporary directory using JDK standard API
            try {
                this.localPath = Files.createTempDirectory("agentscope-git-skills-");
                this.tempDirectory = true;
                logger.info("Created temporary directory for Git repository: {}", localPath);

                this.shutdownHook =
                        SkillFileSystemHelper.registerTempDirectoryCleanup(this.localPath);
            } catch (IOException e) {
                throw new RuntimeException(
                        "Failed to create temporary directory for Git repository", e);
            }
        }
    }

    /**
     * Serves the named skill at the given version reference through the version cache (see
     * the class-level versioning note). The reference is resolved to its underlying commit
     * sha (annotated tags peeled), which is both the version stamp and the cache key — an
     * immutable key, so a reference that later moves (a branch or a retagged release) can
     * never serve a stale cache directory. A cache hit is a pure local read; a miss reads
     * the commit's tree and installs the file set as an immutable version directory. An
     * unknown revision, a skill absent from that commit, or a blank name answers {@code
     * null}.
     */
    @Override
    public AgentSkill getSkill(String name, String version) {
        if (name == null || name.isBlank()) {
            return null;
        }
        ensureAutoSynced();
        String ref = version == null || version.isBlank() ? "HEAD" : version;
        String resolved = resolveCommitSha(ref);
        if (resolved == null) {
            return null;
        }
        return SkillMirrorStore.of(cacheRoot)
                .serve(
                        SkillMirrorStore.repoIdentity("git", remoteUrl),
                        name,
                        resolved,
                        getSource(),
                        () -> Optional.ofNullable(readSkillFromCommit(name, resolved)))
                .orElse(null);
    }

    /**
     * Resolves any revision string (sha, abbreviated sha, tag, branch, {@code HEAD~n}) to
     * the peeled commit's full sha — the canonical version key and stamp — or {@code null}
     * when the reference is unknown or does not point at a commit.
     */
    private String resolveCommitSha(String ref) {
        try (Git git = Git.open(localPath.toFile())) {
            Repository repo = git.getRepository();
            ObjectId id = repo.resolve(ref);
            if (id == null) {
                return null;
            }
            try (RevWalk revWalk = new RevWalk(repo)) {
                return revWalk.peel(revWalk.parseAny(id)).getName();
            }
        } catch (Exception e) {
            logger.debug("Failed to resolve version reference '{}': {}", ref, e.getMessage());
            return null;
        }
    }

    /**
     * Reads the named skill's complete file set from the given commit's tree as in-memory
     * transport content — the fetcher of the cache-miss path. Returns {@code null} for an
     * unknown revision or a skill absent from that commit.
     */
    private AgentSkill readSkillFromCommit(String name, String version) {
        try (Git git = Git.open(localPath.toFile())) {
            Repository repo = git.getRepository();
            ObjectId commitId = repo.resolve(version);
            if (commitId == null) {
                return null;
            }
            RevCommit commit;
            try (RevWalk revWalk = new RevWalk(repo)) {
                commit = revWalk.parseCommit(commitId);
            }
            String skillDir = locateSkillDir(repo, commit, name);
            if (skillDir == null) {
                return null;
            }
            String skillMd = null;
            Map<String, String> resources = new LinkedHashMap<>();
            try (TreeWalk walk = new TreeWalk(repo)) {
                walk.addTree(commit.getTree());
                walk.setRecursive(true);
                while (walk.next()) {
                    String relative = relativePathUnder(walk.getPathString(), skillDir);
                    if (relative == null || isHiddenSegment(relative)) {
                        continue;
                    }
                    byte[] bytes = walk.getObjectReader().open(walk.getObjectId(0)).getBytes();
                    String content = decodeBlob(bytes);
                    if (SKILL_FILE_NAME.equals(relative)) {
                        skillMd = content;
                    } else {
                        resources.put(relative, content);
                    }
                }
            }
            if (skillMd == null) {
                return null;
            }
            AgentSkill skill = SkillUtil.createFrom(skillMd, resources, getSource());
            if (!Objects.equals(skill.getName(), name)) {
                // The directory name matched but its manifest declares another name: the
                // skill does not exist under the requested name at this commit. Null, like
                // the store's own refusal to install a manifest that disagrees with its key.
                return null;
            }
            return skill.toBuilder().version(version).build();
        } catch (Exception e) {
            logger.warn("Versioned read of '{}' at {} failed: {}", name, version, e.getMessage());
            return null;
        }
    }

    /**
     * Locates the named skill's directory inside the commit: the explicit {@code skillsRoot}
     * when configured, else the legacy convention (a {@code skills/} subdir when the commit
     * has one, else the repository root). The directory is matched first by the conventional
     * {@code <dir> == <name>} layout, falling back to scanning each skill directory's
     * SKILL.md frontmatter name (the working-tree loader matches by metadata name too).
     */
    private String locateSkillDir(Repository repo, RevCommit commit, String name)
            throws IOException {
        String direct;
        String scanPrefix;
        ObjectId scanTree;
        if (skillsRoot != null) {
            direct = skillsRoot + "/" + name;
            scanPrefix = skillsRoot + "/";
            scanTree = subtreeOf(repo, commit, skillsRoot);
        } else if (hasTreeDir(repo, commit, "skills")) {
            direct = "skills/" + name;
            scanPrefix = "skills/";
            scanTree = subtreeOf(repo, commit, "skills");
        } else {
            direct = name;
            scanPrefix = "";
            scanTree = commit.getTree();
        }
        if (hasTreeDir(repo, commit, direct)) {
            return direct;
        }
        if (scanTree == null) {
            return null;
        }
        // Fallback: match by the SKILL.md metadata name across the skill directories.
        try (TreeWalk walk = new TreeWalk(repo)) {
            walk.addTree(scanTree);
            walk.setRecursive(false);
            while (walk.next()) {
                if (!walk.isSubtree() || isHiddenSegment(walk.getNameString())) {
                    continue;
                }
                String candidate = scanPrefix + walk.getNameString();
                String skillMd = readTreeFile(repo, commit, candidate + "/" + SKILL_FILE_NAME);
                if (skillMd != null && matchesSkillName(skillMd, name)) {
                    return candidate;
                }
            }
        }
        return null;
    }

    /** The subtree id of an in-commit directory, or {@code null} when absent or a file. */
    private ObjectId subtreeOf(Repository repo, RevCommit commit, String path) throws IOException {
        try (TreeWalk walk = TreeWalk.forPath(repo, path, commit.getTree())) {
            return walk != null && walk.isSubtree() ? walk.getObjectId(0) : null;
        }
    }

    /** Whether the commit's root (or the given first-level path) is a directory in the tree. */
    private boolean hasTreeDir(Repository repo, RevCommit commit, String path) throws IOException {
        try (TreeWalk walk = TreeWalk.forPath(repo, path, commit.getTree())) {
            return walk != null && walk.isSubtree();
        }
    }

    /** Reads one file's bytes from the commit tree as UTF-8 text; {@code null} when absent. */
    private String readTreeFile(Repository repo, RevCommit commit, String path) throws IOException {
        try (TreeWalk walk = TreeWalk.forPath(repo, path, commit.getTree())) {
            if (walk == null || walk.isSubtree()) {
                return null;
            }
            return new String(
                    walk.getObjectReader().open(walk.getObjectId(0)).getBytes(),
                    StandardCharsets.UTF_8);
        }
    }

    /** Whether the SKILL.md frontmatter declares the given skill name. */
    private static boolean matchesSkillName(String skillMd, String name) {
        try {
            return name.equals(SkillUtil.createFrom(skillMd, null).getName());
        } catch (Exception e) {
            return false;
        }
    }

    /** The walk path relative to the skill directory, or {@code null} when outside it. */
    private static String relativePathUnder(String path, String skillDir) {
        if (!path.startsWith(skillDir + "/")) {
            return null;
        }
        return path.substring(skillDir.length() + 1);
    }

    /** Whether any segment of the relative path starts with a dot (hidden entry). */
    private static boolean isHiddenSegment(String relativePath) {
        for (String segment : relativePath.split("/")) {
            if (segment.startsWith(".")) {
                return true;
            }
        }
        return false;
    }

    /**
     * Decodes a blob as UTF-8 text, or wraps undecodable (binary) bytes in the {@code
     * base64:} transport convention shared with SkillFileSystemHelper and SkillMirrorStore,
     * so binary resources materialize byte-exact instead of as UTF-8 replacement characters.
     */
    private static String decodeBlob(byte[] bytes) {
        try {
            return StandardCharsets.UTF_8
                    .newDecoder()
                    .onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT)
                    .decode(ByteBuffer.wrap(bytes))
                    .toString();
        } catch (CharacterCodingException e) {
            return "base64:" + Base64.getEncoder().encodeToString(bytes);
        }
    }

    @Override
    public List<String> getAllSkillNames() {
        ensureAutoSynced();
        return SkillFileSystemHelper.getAllSkillNames(skillsPath);
    }

    @Override
    public List<AgentSkill> getAllSkills() {
        ensureAutoSynced();
        List<AgentSkill> skills = SkillFileSystemHelper.getAllSkills(skillsPath, getSource());
        String head = resolveLocalHead();
        if (head == null) {
            return skills;
        }
        List<AgentSkill> stamped = new ArrayList<>(skills.size());
        for (AgentSkill skill : skills) {
            stamped.add(skill == null ? null : skill.toBuilder().version(head).build());
        }
        return stamped;
    }

    @Override
    public boolean skillExists(String skillName) {
        ensureAutoSynced();
        return SkillFileSystemHelper.skillExists(skillsPath, skillName);
    }

    /**
     * Triggers a manual synchronization of the repository.
     */
    public void sync() {
        ensureRepositorySynced();
    }

    @Override
    public boolean save(List<AgentSkill> skills, boolean force) {
        logger.warn("GitSkillRepository is read-only, save operation ignored");
        return false;
    }

    @Override
    public boolean delete(String skillName) {
        logger.warn("GitSkillRepository is read-only, delete operation ignored");
        return false;
    }

    @Override
    public void setWriteable(boolean writeable) {
        logger.warn("GitSkillRepository is read-only, set writeable operation ignored");
    }

    @Override
    public boolean isWriteable() {
        return false;
    }

    @Override
    /**
     * Performs manual cleanup of the temporary local repository directory.
     *
     * <p>This is optional: temporary directories are also deleted automatically when the JVM
     * terminates. Call this method if you want to release disk space earlier.
     */
    public void close() {
        if (!tempDirectory) {
            return;
        }

        if (shutdownHook != null) {
            try {
                Runtime.getRuntime().removeShutdownHook(shutdownHook);
            } catch (IllegalStateException e) {
                logger.debug("JVM is shutting down, cannot remove shutdown hook", e);
            }
        }

        try {
            SkillFileSystemHelper.deleteDirectory(localPath);
        } catch (IOException | RuntimeException e) {
            // Cleanup is best-effort (the JVM shutdown hook retries): a RuntimeException
            // escapes the helper on Windows when JGit's pack mmap is not yet released —
            // close() must never blow up on a locked temporary clone.
            logger.warn("Failed to cleanup local repository directory: {}", localPath, e);
        }
    }

    @Override
    public AgentSkillRepositoryInfo getRepositoryInfo() {
        return new AgentSkillRepositoryInfo("git", remoteUrl, false);
    }

    @Override
    public String getSource() {
        return source != null ? source : buildDefaultSource();
    }

    private String buildDefaultSource() {
        String repoIdentifier = extractRepositoryIdentifier(remoteUrl);
        // Use hyphen instead of colon to avoid Windows path issues
        String source =
                branch != null ? "git-" + repoIdentifier + "@" + branch : "git-" + repoIdentifier;
        // Replace Windows reserved characters with hyphen that might be in branch names
        // Note: forward slash (/) is kept as it's valid in source identifiers (owner/repo format)
        // and is not a Windows reserved character in the context of file name
        return source.replaceAll("[\\\\:*?\"<>|]", "-");
    }

    /**
     * Extracts a concise repository identifier from a Git URL.
     *
     * <p>Examples:
     * <ul>
     *   <li>https://github.com/owner/repo.git → owner/repo</li>
     *   <li>git@github.com:owner/repo.git → owner/repo</li>
     *   <li>https://gitlab.com/group/subgroup/project.git → group/subgroup/project</li>
     * </ul>
     *
     * @param url The Git repository URL
     * @return A concise identifier for the repository
     */
    private String extractRepositoryIdentifier(String url) {
        // Remove .git suffix if present
        String normalized = url.endsWith(".git") ? url.substring(0, url.length() - 4) : url;

        // Handle SSH format: git@host:owner/repo
        if (normalized.contains("@") && normalized.contains(":")) {
            int colonIndex = normalized.lastIndexOf(':');
            return normalized.substring(colonIndex + 1);
        }

        // Handle HTTPS format: https://host/owner/repo
        // Also handles file:// protocol for local testing
        if (normalized.contains("://")) {
            int hostEndIndex = normalized.indexOf('/', normalized.indexOf("://") + 3);
            if (hostEndIndex != -1 && hostEndIndex + 1 < normalized.length()) {
                return normalized.substring(hostEndIndex + 1);
            }
        }

        // Fallback: return the last part after the last slash
        int lastSlash = normalized.lastIndexOf('/');
        return lastSlash != -1 ? normalized.substring(lastSlash + 1) : normalized;
    }

    /**
     * Validates a user-supplied in-repo skills root.
     *
     * <p>Rejects absolute paths and paths whose segments contain {@code ".."}, so a malicious or
     * mistaken config cannot read files outside the cloned repository tree. Blank input is treated
     * as "unspecified" and returns null (preserving the legacy auto-detect behavior).
     */
    private static String normalizeSkillsRoot(String raw) {
        if (raw == null) {
            return null;
        }
        String trimmed = raw.trim();
        if (trimmed.isEmpty()) {
            return null;
        }
        Path p = Path.of(trimmed);
        if (p.isAbsolute()) {
            throw new IllegalArgumentException(
                    "skillsRoot must be a path relative to the repository root, got: " + raw);
        }
        for (Path segment : p) {
            if (segment.toString().equals("..")) {
                throw new IllegalArgumentException(
                        "skillsRoot must not contain '..' segments, got: " + raw);
            }
        }
        return trimmed;
    }

    /**
     * Ensures the Git repository is synchronized (cloned or pulled) before accessing skills.
     *
     * @throws IllegalStateException if local path exists but is not a valid Git repository
     * @throws RuntimeException if clone or pull operations fail
     */
    private synchronized void ensureRepositorySynced() {
        try {
            boolean isEmpty = false;
            try {
                isEmpty = !Files.exists(localPath) || isEmptyDirectory(localPath);
            } catch (IOException e) {
                throw new RuntimeException(
                        "Unable to access local temporary directory: "
                                + localPath
                                + "\nError details: "
                                + e.getMessage(),
                        e);
            }

            if (isEmpty) {
                // Directory does not exist or is empty -> clone
                cloneRepository();
            } else if (isGitRepository(localPath)) {
                // Is a Git repository -> pull updates only when remote ref changes
                if (hasRemoteUpdates()) {
                    pullRepository();
                } else {
                    logger.debug("Remote repository has no updates, skipping pull");
                }
            } else {
                // Not a Git repository -> error
                throw new IllegalStateException(
                        "Local path exists but is not a valid Git repository: "
                                + localPath
                                + "\n"
                                + "Possible causes:\n"
                                + "  1. Previous clone operation was not completed\n"
                                + "  2. Directory was manually modified\n"
                                + "Solution: Manually delete the directory and retry, or call"
                                + " close() method to cleanup");
            }

            // Ensure skills path is initialized
            if (skillsPath == null) {
                if (skillsRoot != null) {
                    Path explicit = localPath.resolve(skillsRoot).normalize();
                    if (!explicit.startsWith(localPath.toAbsolutePath().normalize())
                            && !explicit.startsWith(localPath.normalize())) {
                        throw new IllegalStateException(
                                "Configured skillsRoot escapes the repository: " + skillsRoot);
                    }
                    if (!Files.isDirectory(explicit)) {
                        throw new IllegalStateException(
                                "Configured skillsRoot does not exist in repository: "
                                        + skillsRoot
                                        + " (resolved to "
                                        + explicit
                                        + ")");
                    }
                    skillsPath = explicit;
                    logger.info("Using configured skillsRoot subdirectory: {}", skillsPath);
                } else {
                    // Convention: skills are located in the "skills" subdirectory if it exists
                    Path skillsSubDir = localPath.resolve("skills");
                    if (Files.exists(skillsSubDir) && Files.isDirectory(skillsSubDir)) {
                        skillsPath = skillsSubDir;
                        logger.info("Found skills subdirectory, using: {}", skillsPath);
                    } else {
                        skillsPath = localPath;
                        logger.info(
                                "No skills subdirectory found, using repository root: {}",
                                skillsPath);
                    }
                }

                logger.info(
                        "Initialized skills path for: {} (skills path: {})", remoteUrl, skillsPath);
            }
        } catch (RuntimeException e) {
            throw e;
        } catch (Exception e) {
            throw new RuntimeException("Failed to sync Git repository: " + remoteUrl, e);
        }
    }

    private void ensureAutoSynced() {
        if (autoSync) {
            ensureRepositorySynced();
            return;
        }
        if (skillsPath == null) {
            throw new IllegalStateException(
                    "Repository is not synchronized. Call sync() before reading skills.");
        }
    }

    /**
     * Checks if a directory is empty.
     *
     * @param path The directory path to check
     * @return true if the directory is empty, false otherwise
     * @throws IOException if an I/O error occurs
     */
    private boolean isEmptyDirectory(Path path) throws IOException {
        try (Stream<Path> entries = Files.list(path)) {
            return !entries.findFirst().isPresent();
        }
    }

    /**
     * Checks if a path is a Git repository.
     *
     * @param path The path to check
     * @return true if the path contains a .git directory, false otherwise
     */
    private boolean isGitRepository(Path path) {
        return Files.exists(path.resolve(".git"));
    }

    /**
     * Clones the remote Git repository to the local path.
     *
     * @throws RuntimeException if clone operation fails with detailed error messages
     */
    private void cloneRepository() {
        logger.info("Cloning Git repository: {} to {}", remoteUrl, localPath);

        try {
            CloneCommand cloneCommand =
                    Git.cloneRepository()
                            .setURI(remoteUrl)
                            .setDirectory(localPath.toFile())
                            .setProgressMonitor(new LoggingProgressMonitor());

            if (branch != null) {
                cloneCommand.setBranch(branch);
                logger.info("Using branch: {}", branch);
            }

            try (Git git = cloneCommand.call()) {
                logger.info("Successfully cloned repository: {}", remoteUrl);
                ObjectId head = git.getRepository().resolve("HEAD");
                lastRemoteRef = head != null ? head.getName() : null;
            }

        } catch (TransportException e) {
            String errorMsg = e.getMessage().toLowerCase();

            if (errorMsg.contains("auth") || errorMsg.contains("authentication")) {
                throw new RuntimeException(
                        "Repository authentication failed: "
                                + remoteUrl
                                + "\n\n"
                                + "Hint: Please ensure Git credentials are configured in your"
                                + " system:\n"
                                + "  - HTTPS: Run 'git config --global credential.helper store' or"
                                + " use GitHub CLI\n"
                                + "  - SSH: Ensure SSH keys are added to ssh-agent and remote"
                                + " repository\n\n"
                                + "Verification: Run 'git clone "
                                + remoteUrl
                                + "' in terminal to test authentication",
                        e);
            } else if (errorMsg.contains("not found")
                    || errorMsg.contains("repository not found")) {
                throw new RuntimeException(
                        "Remote repository does not exist or is inaccessible: "
                                + remoteUrl
                                + "\n\n"
                                + "Please check:\n"
                                + "  1. URL is correct\n"
                                + "  2. Repository exists\n"
                                + "  3. You have access permission",
                        e);
            } else {
                throw new RuntimeException(
                        "Unable to connect to remote repository: "
                                + remoteUrl
                                + "\n"
                                + "Please check network connection and URL correctness\n"
                                + "Error details: "
                                + e.getMessage(),
                        e);
            }

        } catch (InvalidRemoteException e) {
            if (branch != null) {
                throw new RuntimeException(
                        "Specified branch does not exist: "
                                + branch
                                + "\n"
                                + "Repository: "
                                + remoteUrl
                                + "\n\n"
                                + "Solutions:\n"
                                + "  1. Check if branch name is correct\n"
                                + "  2. Use default branch (do not specify branch parameter)",
                        e);
            } else {
                throw new RuntimeException(
                        "Invalid remote repository address: "
                                + remoteUrl
                                + "\n"
                                + "Please check URL format",
                        e);
            }

        } catch (Exception e) {
            throw new RuntimeException(
                    "Failed to clone repository: "
                            + remoteUrl
                            + "\n"
                            + "Error details: "
                            + e.getMessage(),
                    e);
        }
    }

    /**
     * Pulls updates from the remote Git repository.
     *
     * @throws RuntimeException if pull operation fails
     */
    private void pullRepository() {
        logger.debug("Pulling updates from Git repository: {}", remoteUrl);

        try (Git git = Git.open(localPath.toFile())) {
            git.pull().setProgressMonitor(new LoggingProgressMonitor()).call();

            logger.info("Successfully pulled updates from: {}", remoteUrl);
            lastRemoteRef = resolveLocalHead();

        } catch (TransportException e) {
            throw new RuntimeException(
                    "Unable to pull updates from remote repository: "
                            + remoteUrl
                            + "\n"
                            + "Please check network connection\n"
                            + "Error details: "
                            + e.getMessage(),
                    e);

        } catch (IOException e) {
            throw new RuntimeException(
                    "Unable to open local Git repository: "
                            + localPath
                            + "\n"
                            + "Local repository may be corrupted, suggest deleting local cache\n"
                            + "Error details: "
                            + e.getMessage(),
                    e);

        } catch (Exception e) {
            throw new RuntimeException(
                    "Failed to pull updates: "
                            + remoteUrl
                            + "\n"
                            + "Local repository may have issues, suggest deleting local cache: "
                            + localPath
                            + "\n"
                            + "Error details: "
                            + e.getMessage(),
                    e);
        }
    }

    private boolean hasRemoteUpdates() {
        String remoteRef = resolveRemoteHead();
        if (remoteRef == null) {
            return true;
        }
        if (lastRemoteRef == null) {
            lastRemoteRef = resolveLocalHead();
        }
        return !Objects.equals(remoteRef, lastRemoteRef);
    }

    private String resolveRemoteHead() {
        try {
            LsRemoteCommand command = Git.lsRemoteRepository().setRemote(remoteUrl).setHeads(true);
            Iterable<Ref> refs = command.call();
            Optional<Ref> target =
                    StreamSupport.stream(refs.spliterator(), false)
                            .filter(this::matchesBranch)
                            .findFirst();
            if (target.isEmpty()) {
                return null;
            }
            ObjectId id = target.get().getObjectId();
            return id != null ? id.getName() : null;
        } catch (Exception e) {
            logger.debug("Failed to resolve remote head, will perform pull", e);
            return null;
        }
    }

    private boolean matchesBranch(Ref ref) {
        if (branch == null || branch.isBlank()) {
            return true;
        }
        String expected = "refs/heads/" + branch;
        return expected.equals(ref.getName());
    }

    private String resolveLocalHead() {
        try (Git git = Git.open(localPath.toFile())) {
            ObjectId head = git.getRepository().resolve("HEAD");
            return head != null ? head.getName() : null;
        } catch (Exception e) {
            logger.debug("Failed to resolve local head", e);
            return null;
        }
    }

    /** Custom progress monitor that logs Git operation progress to the logger. */
    private static class LoggingProgressMonitor implements ProgressMonitor {
        private String currentTask;
        private int totalWork;
        private int completed;

        @Override
        public void start(int totalTasks) {
            logger.debug("Git operation started with {} tasks", totalTasks);
        }

        @Override
        public void beginTask(String title, int totalWork) {
            this.currentTask = title;
            this.totalWork = totalWork;
            this.completed = 0;
            logger.debug("Git task started: {}", title);
        }

        @Override
        public void update(int completed) {
            this.completed += completed;
            if (totalWork > 0 && logger.isDebugEnabled()) {
                int percentage = (this.completed * 100) / totalWork;
                logger.debug(
                        "Git progress [{}]: {}/{} ({}%)",
                        currentTask, this.completed, totalWork, percentage);
            }
        }

        @Override
        public void endTask() {
            logger.debug("Git task completed: {}", currentTask);
        }

        @Override
        public boolean isCancelled() {
            return false;
        }

        @Override
        public void showDuration(boolean enabled) {
            // Not used in this implementation
        }
    }
}

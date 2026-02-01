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
import java.util.List;
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
    private final boolean tempDirectory;
    private final Thread shutdownHook;
    private Path skillsPath;
    private volatile String lastRemoteRef;

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
        if (remoteUrl == null || remoteUrl.trim().isEmpty()) {
            throw new IllegalArgumentException("Remote URL cannot be null or empty");
        }
        this.remoteUrl = remoteUrl.trim();
        this.branch = branch;
        this.source = source;
        this.autoSync = autoSync;

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
                logger.info("Created temporary directory for Git repository: {}", this.localPath);

                this.shutdownHook =
                        SkillFileSystemHelper.registerTempDirectoryCleanup(this.localPath);
            } catch (IOException e) {
                throw new RuntimeException(
                        "Failed to create temporary directory for Git repository", e);
            }
        }
    }

    @Override
    public AgentSkill getSkill(String name) {
        ensureAutoSynced();
        return SkillFileSystemHelper.loadSkill(skillsPath, name, getSource());
    }

    @Override
    public List<String> getAllSkillNames() {
        ensureAutoSynced();
        return SkillFileSystemHelper.getAllSkillNames(skillsPath);
    }

    @Override
    public List<AgentSkill> getAllSkills() {
        ensureAutoSynced();
        return SkillFileSystemHelper.getAllSkills(skillsPath, getSource());
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
        } catch (IOException e) {
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
        return branch != null ? "git:" + repoIdentifier + "@" + branch : "git:" + repoIdentifier;
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
        if (normalized.contains("://")) {
            int lastSlashIndex = normalized.indexOf('/', normalized.indexOf("://") + 3);
            if (lastSlashIndex != -1) {
                return normalized.substring(lastSlashIndex + 1);
            }
        }

        // Fallback: return the last part after the last slash
        int lastSlash = normalized.lastIndexOf('/');
        return lastSlash != -1 ? normalized.substring(lastSlash + 1) : normalized;
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
                // Convention: skills are located in the "skills" subdirectory if it exists
                Path skillsSubDir = localPath.resolve("skills");

                if (Files.exists(skillsSubDir) && Files.isDirectory(skillsSubDir)) {
                    skillsPath = skillsSubDir;
                    logger.info("Found skills subdirectory, using: {}", skillsPath);
                } else {
                    skillsPath = localPath;
                    logger.info(
                            "No skills subdirectory found, using repository root: {}", skillsPath);
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

        } catch (org.eclipse.jgit.api.errors.TransportException e) {
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

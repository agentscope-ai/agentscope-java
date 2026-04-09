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

import java.io.IOException;
import java.net.URISyntaxException;
import java.net.URL;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.StandardCopyOption;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.List;

import org.eclipse.jgit.api.Git;
import org.eclipse.jgit.api.errors.GitAPIException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import io.agentscope.core.skill.AgentSkill;
import io.agentscope.core.skill.util.SkillFileSystemHelper;

/**
 * GitSkillRepository - Downloads skills from a Git repository and loads them from the local filesystem.
 *
 * <p>This repository directly implements {@link AgentSkillRepository} and:
 * <ol>
 *   <li>Clones/pulls a Git repository to a temporary directory</li>
 *   <li>Copies the {@code skills/} directory content to a local working directory</li>
 *   <li>Delegates skill I/O to {@link SkillFileSystemHelper}</li>
 * </ol>
 *
 * <p>Directory structure expected in the Git repository:
 * <pre>
 * repo/
 * └── skills/
 *     ├── skill-a/
 *     │   └── SKILL.md
 *     └── skill-b/
 *         └── SKILL.md
 * </pre>
 */
public class GitSkillRepository implements AgentSkillRepository {

    private static final Logger logger = LoggerFactory.getLogger(GitSkillRepository.class);
    private static final String SKILLS_DIR = "skills";
    private static final String GIT_SKILL_PATH = "git-skills-repo-";
    private final String gitRepoUrl;
    private final Path tempPath;
    private final Path skillsPath;
    private volatile boolean gitInitialized = false;

    /**
     * Creates a GitSkillRepository. The classpath root is resolved via
     * {@code GitSkillRepository.class.getClassLoader().getResource("")}, then:
     * <ul>
     *   <li>{@code skillsPath = <classpathRoot>/git-skills-repo-<repoName>/}</li>
     *   <li>{@code tempPath   = <classpathRoot>/temp/}</li>
     * </ul>
     *
     * @param gitRepoUrl Git repository URL
     * @throws IOException if classpath root cannot be resolved or directories cannot be created
     */
    public GitSkillRepository(String gitRepoUrl) throws IOException {
        this(gitRepoUrl, GitSkillRepository.class.getClassLoader());
    }

    /**
     * Creates a GitSkillRepository using the given {@code classLoader} to locate the classpath root.
     *
     * @param gitRepoUrl  Git repository URL
     * @param classLoader ClassLoader used to resolve the classpath root
     * @throws IOException if classpath root cannot be resolved or directories cannot be created
     */
    public GitSkillRepository(String gitRepoUrl, ClassLoader classLoader) throws IOException {
        try {
            this.gitRepoUrl = gitRepoUrl;
            String repoName = extractRepoName(gitRepoUrl);

            URL classpathRootUrl = classLoader.getResource("");
            logger.info("Classpath root URL: {}", classpathRootUrl);
            if (classpathRootUrl == null) {
                throw new IOException("Cannot resolve classpath root from classloader");
            }

            Path classpathRoot = Path.of(classpathRootUrl.toURI());
            this.skillsPath = Files.createDirectories(classpathRoot.resolve(GIT_SKILL_PATH + repoName));
            this.tempPath   = Files.createDirectories(classpathRoot.resolve("temp"));

            logger.info("Skills path: {}", this.skillsPath);
            logger.info("Temp   path: {}", this.tempPath);

        } catch (URISyntaxException e) {
            throw new IOException("Invalid classpath root URI", e);
        }
    }

    private static String extractRepoName(String gitRepoUrl) {
        if (gitRepoUrl == null || gitRepoUrl.isEmpty()) {
            return "unknown";
        }
        String url = gitRepoUrl.endsWith(".git")
                ? gitRepoUrl.substring(0, gitRepoUrl.length() - 4)
                : gitRepoUrl;
        int lastSlash = url.lastIndexOf('/');
        return lastSlash >= 0 && lastSlash < url.length() - 1
                ? url.substring(lastSlash + 1)
                : url;
    }

    /**
     * Ensures Git repository is initialized before loading skills.
     */
    private synchronized void ensureGitInitialized() {
        if (gitInitialized) {
            return;
        }
        try {
            logger.info("Initializing Git skill repository from: {}", gitRepoUrl);
            downloadGitRepository();

            Path sourceSkillsPath = tempPath.resolve(SKILLS_DIR);
            if (!Files.exists(sourceSkillsPath)) {
                throw new IllegalStateException("skills/ directory not found in repository: " + gitRepoUrl);
            }

            copySkillsToPath(sourceSkillsPath, skillsPath);
            gitInitialized = true;
            logger.info("Git skill repository initialized successfully, skills at: {}", skillsPath);

        } catch (GitAPIException | IOException e) {
            throw new RuntimeException("Failed to initialize Git skill repository", e);
        } finally {
            cleanupTemp();
        }
    }

    private void downloadGitRepository() throws GitAPIException, IOException {
        if (Files.exists(tempPath.resolve(".git"))) {
            logger.info("Pulling latest changes from: {}", gitRepoUrl);
            try (Git git = Git.open(tempPath.toFile())) {
                git.pull().call();
            }
        } else {
            logger.info("Cloning repository {} to: {}", gitRepoUrl, tempPath);
            Git.cloneRepository()
                    .setURI(gitRepoUrl)
                    .setDirectory(tempPath.toFile())
                    .call()
                    .close();
        }
    }

    private void copySkillsToPath(Path sourceSkillsPath, Path targetPath) throws IOException {
        logger.info("Copying skills from {} to {}", sourceSkillsPath, targetPath);
        long skillCount = Files.list(sourceSkillsPath)
                .filter(Files::isDirectory)
                .filter(path -> !path.getFileName().toString().startsWith("."))
                .map(skillDir -> {
                    try {
                        String skillName = skillDir.getFileName().toString();
                        Path targetSkillDir = targetPath.resolve(skillName);

                        if (Files.exists(targetSkillDir)) {
                            logger.info("Overwriting existing skill: {}", skillName);
                            deleteDirectory(targetSkillDir);
                        }

                        copyDirectory(skillDir, targetSkillDir);
                        logger.debug("Copied skill: {}", skillName);
                        return 1;
                    } catch (IOException e) {
                        logger.warn("Failed to copy skill: {}", skillDir, e);
                        return 0;
                    }
                }).count();
        logger.info("Copied {} skill directories to {}", skillCount, targetPath);
    }

    private void copyDirectory(Path source, Path target) throws IOException {
        Files.walkFileTree(source, new SimpleFileVisitor<Path>() {
            @Override
            public FileVisitResult preVisitDirectory(Path dir, BasicFileAttributes attrs) throws IOException {
                if (dir.getFileName().toString().startsWith(".")) {
                    return FileVisitResult.SKIP_SUBTREE;
                }
                Path targetDir = target.resolve(source.relativize(dir));
                Files.createDirectories(targetDir);
                return FileVisitResult.CONTINUE;
            }

            @Override
            public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) throws IOException {
                if (file.getFileName().toString().startsWith(".")) {
                    return FileVisitResult.CONTINUE;
                }
                Files.copy(file, target.resolve(source.relativize(file)), StandardCopyOption.REPLACE_EXISTING);
                return FileVisitResult.CONTINUE;
            }
        });
    }

    private void deleteDirectory(Path directory) throws IOException {
        Files.walkFileTree(directory, new SimpleFileVisitor<Path>() {
            @Override
            public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) throws IOException {
                Files.delete(file);
                return FileVisitResult.CONTINUE;
            }

            @Override
            public FileVisitResult postVisitDirectory(Path dir, IOException exc) throws IOException {
                Files.delete(dir);
                return FileVisitResult.CONTINUE;
            }
        });
    }

    // -------------------------------------------------------------------------
    // AgentSkillRepository implementation
    // -------------------------------------------------------------------------

    @Override
    public AgentSkill getSkill(String skillName) {
        ensureGitInitialized();
        return SkillFileSystemHelper.loadSkill(skillsPath, skillName, getSource());
    }

    @Override
    public List<String> getAllSkillNames() {
        ensureGitInitialized();
        return SkillFileSystemHelper.getAllSkillNames(skillsPath);
    }

    @Override
    public List<AgentSkill> getAllSkills() {
        ensureGitInitialized();
        return SkillFileSystemHelper.getAllSkills(skillsPath, getSource());
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
    public boolean skillExists(String skillName) {
        ensureGitInitialized();
        return SkillFileSystemHelper.skillExists(skillsPath, skillName);
    }

    @Override
    public AgentSkillRepositoryInfo getRepositoryInfo() {
        return new AgentSkillRepositoryInfo("git", gitRepoUrl, false);
    }

    @Override
    public String getSource() {
        return gitRepoUrl;
    }

    @Override
    public void setWriteable(boolean writeable) {
        logger.warn("GitSkillRepository is read-only, set writeable operation ignored");
    }

    @Override
    public boolean isWriteable() {
        return false;
    }

    // -------------------------------------------------------------------------
    // Git-specific operations
    // -------------------------------------------------------------------------

    /**
     * Refreshes the repository by re-downloading from Git.
     */
    public void refresh() {
        gitInitialized = false;
        ensureGitInitialized();
    }

    /**
     * Cleans up the temporary clone directory.
     */
    private void cleanupTemp() {
        try {
            if (Files.exists(tempPath)) {
                deleteDirectory(tempPath);
                logger.info("Cleaned up temp clone directory: {}", tempPath);
            }
        } catch (IOException e) {
            logger.warn("Failed to cleanup temp clone directory", e);
        }
    }

    @Override
    public void close() {
        cleanupTemp();
    }

    /**
     * Gets the Git repository URL.
     */
    public String getGitRepoUrl() {
        return gitRepoUrl;
    }

    /**
     * Gets the local path where skills are stored after cloning.
     */
    public Path getSkillsPath() {
        return skillsPath;
    }
}

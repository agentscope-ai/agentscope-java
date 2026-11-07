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

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.agentscope.core.skill.AgentSkill;
import io.agentscope.core.skill.util.MarkdownSkillParser;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.eclipse.jgit.api.Git;
import org.eclipse.jgit.lib.ObjectId;
import org.eclipse.jgit.revwalk.RevTag;
import org.eclipse.jgit.revwalk.RevWalk;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Unit tests for GitSkillRepository.
 *
 * <p>
 * Tests Git-based skill repository functionality using local Git repositories
 * (file:// protocol)
 * to avoid network dependencies.
 *
 * <p>
 * Tagged as "unit" - fast running tests without external network dependencies.
 */
@Tag("unit")
@DisplayName("GitSkillRepository Unit Tests")
class GitSkillRepositoryTest {

    @TempDir Path tempDir;

    /** Explicit version-materialization cache root — serving tests never touch the real home. */
    @TempDir Path cacheDir;

    private GitSkillRepository repository;

    @AfterEach
    void tearDown() {
        if (repository != null) {
            try {
                repository.close();
            } catch (Exception e) {
                // Ignore cleanup errors in tests
            }
        }
    }

    // ==================== Constructor Tests ====================

    @Test
    @DisplayName("Should create repository with valid URL")
    void testConstructor_ValidUrl() {
        repository = new GitSkillRepository("https://github.com/test/repo.git");
        assertNotNull(repository);
        assertEquals("git", repository.getRepositoryInfo().getType());
        assertFalse(repository.isWriteable());
    }

    @Test
    @DisplayName("Should throw exception when URL is null")
    void testConstructor_NullUrl() {
        IllegalArgumentException exception =
                assertThrows(IllegalArgumentException.class, () -> new GitSkillRepository(null));
        assertTrue(exception.getMessage().contains("cannot be null"));
    }

    @Test
    @DisplayName("Should throw exception when URL is empty")
    void testConstructor_EmptyUrl() {
        IllegalArgumentException exception =
                assertThrows(IllegalArgumentException.class, () -> new GitSkillRepository(""));
        assertTrue(exception.getMessage().contains("cannot be null or empty"));
    }

    @Test
    @DisplayName("Should create repository with branch")
    void testConstructor_WithBranch() {
        repository = new GitSkillRepository("https://github.com/test/repo.git", "develop");
        assertNotNull(repository);
    }

    // ==================== Local Git Repository Tests ====================

    /**
     * Creates a local Git repository with a test skill for testing purposes.
     *
     * @return Path to the created Git repository
     * @throws Exception if repository creation fails
     */
    private Path createLocalGitRepository() throws Exception {
        Path repoDir = tempDir.resolve("test-repo");
        Files.createDirectories(repoDir);

        // Initialize repository using JGit
        try (Git git = Git.init().setDirectory(repoDir.toFile()).call()) {
            // Create skill directory structure
            Path skillDir = repoDir.resolve("test-skill");
            Files.createDirectories(skillDir);

            // Create SKILL.md
            String skillContent =
                    """
                    ---
                    name: test-skill
                    description: A test skill
                    ---

                    # Test Skill
                    This is a test skill.
                    """;
            Files.writeString(skillDir.resolve("SKILL.md"), skillContent);

            // Commit
            git.add().addFilepattern(".").call();
            git.commit().setMessage("Initial commit").call();
        }

        return repoDir;
    }

    /**
     * Creates a local Git repository with skills in a "skills" subdirectory.
     *
     * @return Path to the created Git repository
     * @throws Exception if repository creation fails
     */
    private Path createLocalGitRepositoryWithSkillsSubdir() throws Exception {
        Path repoDir = tempDir.resolve("test-repo-with-subdir");
        Files.createDirectories(repoDir);

        // Initialize repository using JGit
        try (Git git = Git.init().setDirectory(repoDir.toFile()).call()) {
            // Create skills subdirectory
            Path skillsDir = repoDir.resolve("skills");
            Files.createDirectories(skillsDir);

            // Create test skill in skills subdirectory
            Path skillDir = skillsDir.resolve("subdir-skill");
            Files.createDirectories(skillDir);

            String skillContent =
                    """
                    ---
                    name: subdir-skill
                    description: A skill in skills subdirectory
                    ---

                    # Subdir Skill
                    This skill is located in the skills/ subdirectory.
                    """;
            Files.writeString(skillDir.resolve("SKILL.md"), skillContent);

            // Commit
            git.add().addFilepattern(".").call();
            git.commit().setMessage("Initial commit with skills subdir").call();
        }

        return repoDir;
    }

    @Test
    @DisplayName("Should load skill from local Git repository")
    void testGetSkill_FromLocalRepo() throws Exception {
        // Create local Git repository
        Path localRepo = createLocalGitRepository();

        // Access local repository using file:// protocol (no network required)
        repository =
                new GitSkillRepository(
                        "file://" + localRepo.toString(), null, null, null, true, null, cacheDir);

        // Verify skill can be loaded
        AgentSkill skill = repository.getSkill("test-skill");
        assertNotNull(skill);
        assertEquals("test-skill", skill.getName());
        assertEquals("A test skill", skill.getDescription());
    }

    @Test
    @DisplayName("Should require manual sync when autoSync is disabled")
    void testManualSyncRequiredWhenAutoSyncDisabled() throws Exception {
        Path localRepo = createLocalGitRepository();
        repository =
                new GitSkillRepository(
                        "file://" + localRepo.toString(), null, null, null, false, null, cacheDir);

        assertThrows(IllegalStateException.class, () -> repository.getSkill("test-skill"));

        repository.sync();
        AgentSkill skill = repository.getSkill("test-skill");
        assertNotNull(skill);
        assertEquals("test-skill", skill.getName());
    }

    @Test
    @DisplayName("Should load skill from skills subdirectory when it exists")
    void testGetSkill_FromSkillsSubdirectory() throws Exception {
        // Create local Git repository with skills in subdirectory
        Path localRepo = createLocalGitRepositoryWithSkillsSubdir();

        // Access local repository
        repository =
                new GitSkillRepository(
                        "file://" + localRepo.toString(), null, null, null, true, null, cacheDir);

        // Verify skill can be loaded from skills subdirectory
        AgentSkill skill = repository.getSkill("subdir-skill");
        assertNotNull(skill);
        assertEquals("subdir-skill", skill.getName());
        assertEquals("A skill in skills subdirectory", skill.getDescription());
    }

    @Test
    @DisplayName("Should use repository root when skills subdirectory does not exist")
    void testGetSkill_FallbackToRoot() throws Exception {
        // Create repository without skills subdirectory
        Path localRepo = createLocalGitRepository();

        // Access local repository
        repository =
                new GitSkillRepository(
                        "file://" + localRepo.toString(), null, null, null, true, null, cacheDir);

        // Verify skill can be loaded from repository root
        AgentSkill skill = repository.getSkill("test-skill");
        assertNotNull(skill);
        assertEquals("test-skill", skill.getName());
    }

    @Test
    @DisplayName("Should get all skill names from local repository")
    void testGetAllSkillNames_FromLocalRepo() throws Exception {
        Path localRepo = createLocalGitRepository();
        repository =
                new GitSkillRepository(
                        "file://" + localRepo.toString(), null, null, null, true, null, cacheDir);

        List<String> skillNames = repository.getAllSkillNames();
        assertNotNull(skillNames);
        assertEquals(1, skillNames.size());
        assertTrue(skillNames.contains("test-skill"));
    }

    @Test
    @DisplayName("Should get all skills from local repository")
    void testGetAllSkills_FromLocalRepo() throws Exception {
        Path localRepo = createLocalGitRepository();
        repository =
                new GitSkillRepository(
                        "file://" + localRepo.toString(), null, null, null, true, null, cacheDir);

        List<AgentSkill> skills = repository.getAllSkills();
        assertNotNull(skills);
        assertEquals(1, skills.size());
        assertEquals("test-skill", skills.get(0).getName());
    }

    @Test
    @DisplayName("Should check skill existence in local repository")
    void testSkillExists_FromLocalRepo() throws Exception {
        Path localRepo = createLocalGitRepository();
        repository =
                new GitSkillRepository(
                        "file://" + localRepo.toString(), null, null, null, true, null, cacheDir);

        assertTrue(repository.skillExists("test-skill"));
        assertFalse(repository.skillExists("non-existent-skill"));
    }

    @Test
    @DisplayName("Should pull updates from local repository")
    void testPull_FromLocalRepo() throws Exception {
        // Create local Git repository
        Path localRepo = createLocalGitRepository();

        // First access (clone)
        repository =
                new GitSkillRepository(
                        "file://" + localRepo.toString(), null, null, null, true, null, cacheDir);
        List<String> skillNames1 = repository.getAllSkillNames();
        assertEquals(1, skillNames1.size());

        // Add new skill to original repository
        Path newSkillDir = localRepo.resolve("new-skill");
        Files.createDirectories(newSkillDir);
        Files.writeString(
                newSkillDir.resolve("SKILL.md"),
                """
                ---
                name: new-skill
                description: A new skill
                ---
                # New Skill
                """);

        try (Git git = Git.open(localRepo.toFile())) {
            git.add().addFilepattern(".").call();
            git.commit().setMessage("Add new skill").call();
        }

        // Second access (pull)
        List<String> skillNames2 = repository.getAllSkillNames();
        assertEquals(2, skillNames2.size());
        assertTrue(skillNames2.contains("new-skill"));
    }

    // ==================== Branch Tests ====================

    @Test
    @DisplayName("Should clone specific branch")
    void testClone_SpecificBranch() throws Exception {
        // Create local repository with multiple branches
        Path localRepo = createLocalGitRepository();

        try (Git git = Git.open(localRepo.toFile())) {
            // Create new branch
            git.branchCreate().setName("develop").call();
            git.checkout().setName("develop").call();

            // Add new skill in develop branch
            Path devSkillDir = localRepo.resolve("dev-skill");
            Files.createDirectories(devSkillDir);
            Files.writeString(
                    devSkillDir.resolve("SKILL.md"),
                    """
                    ---
                    name: dev-skill
                    description: Dev branch skill
                    ---
                    # Dev Skill
                    """);

            git.add().addFilepattern(".").call();
            git.commit().setMessage("Add dev skill").call();
        }

        // Clone develop branch
        repository =
                new GitSkillRepository(
                        "file://" + localRepo.toString(),
                        "develop",
                        null,
                        null,
                        true,
                        null,
                        cacheDir);

        List<String> skillNames = repository.getAllSkillNames();
        assertTrue(skillNames.contains("dev-skill"), "Should contain skill from develop branch");
    }

    // ==================== Read-Only Tests ====================

    @Test
    @DisplayName("Should return false for save operation")
    void testSave_ReadOnly() throws Exception {
        Path localRepo = createLocalGitRepository();
        repository =
                new GitSkillRepository(
                        "file://" + localRepo.toString(), null, null, null, true, null, cacheDir);

        AgentSkill skill = repository.getSkill("test-skill");
        boolean result = repository.save(List.of(skill), false);

        assertFalse(result, "Save should return false for read-only repository");
    }

    @Test
    @DisplayName("Should return false for delete operation")
    void testDelete_ReadOnly() throws Exception {
        Path localRepo = createLocalGitRepository();
        repository =
                new GitSkillRepository(
                        "file://" + localRepo.toString(), null, null, null, true, null, cacheDir);

        boolean result = repository.delete("test-skill");

        assertFalse(result, "Delete should return false for read-only repository");
    }

    @Test
    @DisplayName("Should always return false for isWriteable")
    void testIsWriteable() throws Exception {
        Path localRepo = createLocalGitRepository();
        repository =
                new GitSkillRepository(
                        "file://" + localRepo.toString(), null, null, null, true, null, cacheDir);

        assertFalse(repository.isWriteable());

        // Try to set as writeable, should be ignored
        repository.setWriteable(true);
        assertFalse(repository.isWriteable());
    }

    // ==================== Repository Info Tests ====================

    @Test
    @DisplayName("Should return correct repository info")
    void testGetRepositoryInfo() {
        String testUrl = "https://github.com/test/repo.git";
        repository = new GitSkillRepository(testUrl, null, null, null, true, null, cacheDir);

        AgentSkillRepositoryInfo info = repository.getRepositoryInfo();
        assertNotNull(info);
        assertEquals("git", info.getType());
        assertEquals(testUrl, info.getLocation());
        assertFalse(info.isWritable());
    }

    @Test
    @DisplayName("Should return correct source identifier without Windows reserved characters")
    void testGetSource() {
        String testUrl = "https://github.com/test/repo.git";
        repository = new GitSkillRepository(testUrl, null, null, null, true, null, cacheDir);

        String source = repository.getSource();
        // Using hyphen instead of colon to avoid Windows path issues
        assertEquals("git-test/repo", source);
        assertFalse(source.contains(":"), "Source should not contain Windows reserved characters");
    }

    @Test
    @DisplayName("Should return custom source identifier when provided")
    void testGetSource_Custom() {
        String testUrl = "https://github.com/test/repo.git";
        String customSource = "git:custom-source";
        repository =
                new GitSkillRepository(testUrl, null, null, customSource, true, null, cacheDir);

        String source = repository.getSource();
        assertEquals(customSource, source);
    }

    // ==================== Skills Root Tests ====================

    @Test
    @DisplayName("Should load skills from configured skillsRoot subdirectory")
    void testGetSkill_FromCustomSkillsRoot() throws Exception {
        // Build a repo with skills nested under packs/marketing/ — a layout
        // the default convention would not find.
        Path repoDir = tempDir.resolve("custom-root-repo");
        Files.createDirectories(repoDir);
        try (Git git = Git.init().setDirectory(repoDir.toFile()).call()) {
            Path nested = repoDir.resolve("packs").resolve("marketing").resolve("greeter");
            Files.createDirectories(nested);
            Files.writeString(
                    nested.resolve("SKILL.md"),
                    """
                    ---
                    name: greeter
                    description: A skill nested under packs/marketing
                    ---
                    # Greeter
                    """);
            git.add().addFilepattern(".").call();
            git.commit().setMessage("nested skill").call();
        }

        repository =
                new GitSkillRepository(
                        "file://" + repoDir.toString(),
                        null,
                        null,
                        null,
                        true,
                        "packs/marketing",
                        cacheDir);

        AgentSkill skill = repository.getSkill("greeter");
        assertNotNull(skill);
        assertEquals("greeter", skill.getName());
        assertEquals("A skill nested under packs/marketing", skill.getDescription());
    }

    @Test
    @DisplayName("Should reject skillsRoot containing '..'")
    void testConstructor_RejectsParentSkillsRoot() {
        assertThrows(
                IllegalArgumentException.class,
                () ->
                        new GitSkillRepository(
                                "https://github.com/test/repo.git",
                                null,
                                null,
                                null,
                                true,
                                "../escape"));
    }

    // ==================== Error Scenario Tests ====================

    @Test
    @DisplayName("Should throw exception when repository not found")
    void testClone_RepositoryNotFound() {
        repository =
                new GitSkillRepository(
                        "https://github.com/definitely-does-not-exist-repo-99999.git");

        RuntimeException exception =
                assertThrows(RuntimeException.class, () -> repository.getSkill("test"));

        String message = exception.getMessage();
        // Error message may contain various network/repository related texts
        // Accept any non-null message since network errors vary by environment
        assertNotNull(message, "Exception message should not be null");
        // Log the actual message for debugging purposes
        // CI environments may have different error messages
        assertTrue(
                message.contains("not found")
                        || message.contains("does not exist")
                        || message.contains("Unable to connect")
                        || message.contains("inaccessible")
                        || message.contains("Invalid remote repository address")
                        || message.contains("Authentication")
                        || message.contains("HTTP")
                        || message.contains("404")
                        || message.contains("timeout")
                        || message.toLowerCase().contains("error")
                        || message.toLowerCase().contains("failed"),
                "Error message should mention repository or network issue: " + message);
    }

    @Test
    @DisplayName("Should throw exception for invalid URL format")
    void testClone_InvalidUrl() {
        repository = new GitSkillRepository("not-a-valid-git-url");

        RuntimeException exception =
                assertThrows(RuntimeException.class, () -> repository.getSkill("test"));

        String message = exception.getMessage();
        assertNotNull(message, "Exception message should not be null");
        // Accept any non-empty message since error formats vary by JGit version and environment
        assertFalse(message.isEmpty(), "Exception message should not be empty");
    }

    // ==================== Version Tests ====================

    @Test
    @DisplayName("Should report the HEAD sha as a stable token that tracks new commits")
    void testVersionContract_HeadShaTokenTracksCommits() throws Exception {
        Path localRepo = createLocalGitRepository();
        repository =
                new GitSkillRepository(
                        "file://" + localRepo.toString(), null, null, null, false, null, cacheDir);
        repository.sync();

        String v1Sha = headSha(localRepo);
        String first = latestVersionOf(repository, "test-skill");
        String second = latestVersionOf(repository, "test-skill");

        assertEquals(v1Sha, first, "The token must be the local clone's HEAD sha");
        assertEquals(
                first,
                second,
                "The token must be stable while the repository content is unchanged");

        String v2Sha = commitSkillUpdate(localRepo, "This is version two.", "v2");
        repository.sync();

        String afterCommit = latestVersionOf(repository, "test-skill");
        assertEquals(
                v2Sha,
                afterCommit,
                "After syncing a new commit the token must report the new HEAD sha");
    }

    @Test
    @DisplayName("Should report the HEAD sha for skills living under the skills subdirectory")
    void testVersionContract_HeadShaTokenForSkillsSubdirectory() throws Exception {
        Path localRepo = createLocalGitRepositoryWithSkillsSubdir();
        repository =
                new GitSkillRepository(
                        "file://" + localRepo.toString(), null, null, null, false, null, cacheDir);
        repository.sync();

        String token = latestVersionOf(repository, "subdir-skill");

        assertEquals(
                headSha(localRepo), token, "The skills/ prefix must not change the HEAD-sha token");
    }

    /**
     * The served body, parsed from the summary's materialized SKILL.md: {@code getSkill}
     * serves summary form (no body in memory), with {@code originDir} pointing at the
     * materialized version directory.
     */
    private static String bodyOf(AgentSkill skill) throws IOException {
        return MarkdownSkillParser.parse(
                        Files.readString(
                                skill.getOriginDir().orElseThrow().resolve("SKILL.md"),
                                StandardCharsets.UTF_8))
                .getContent();
    }

    @Test
    @DisplayName("Should restore historical content by commit sha with stamped versions")
    void testVersionContract_RestoreHistoricalContentBySha() throws Exception {
        Path localRepo = createLocalGitRepository();
        String v1Sha = headSha(localRepo);
        String v2Sha = commitSkillUpdate(localRepo, "This is version two.", "v2");
        repository =
                new GitSkillRepository(
                        "file://" + localRepo.toString(), null, null, null, false, null, cacheDir);
        repository.sync();

        AgentSkill atV1 = repository.getSkill("test-skill", v1Sha);
        assertNotNull(atV1, "The historical commit must resolve the skill");
        assertTrue(bodyOf(atV1).contains("test skill"), "The v1 commit must serve the v1 body");
        assertEquals(v1Sha, atV1.getVersion(), "A versioned read carries the requested sha");

        AgentSkill atV2 = repository.getSkill("test-skill", v2Sha);
        assertTrue(bodyOf(atV2).contains("version two"), "The v2 commit must serve the v2 body");
        assertEquals(v2Sha, atV2.getVersion());

        // Roundtrip: getSkill is a latest pull, so serving its own stamped version must
        // return the same content.
        AgentSkill current = repository.getSkill("test-skill");
        assertEquals(
                bodyOf(current).replace("\r\n", "\n"),
                bodyOf(repository.getSkill("test-skill", current.getVersion()))
                        .replace("\r\n", "\n"),
                "The HEAD-stamped version must roundtrip to the working-tree content");
        assertEquals(headSha(localRepo), current.getVersion());
    }

    @Test
    @DisplayName("Should restore skills from the skills subdirectory by commit sha")
    void testVersionContract_RestoreFromSkillsSubdirectory() throws Exception {
        Path localRepo = createLocalGitRepositoryWithSkillsSubdir();
        String sha = headSha(localRepo);
        repository =
                new GitSkillRepository(
                        "file://" + localRepo.toString(), null, null, null, false, null, cacheDir);
        repository.sync();

        AgentSkill skill = repository.getSkill("subdir-skill", sha);

        assertNotNull(skill, "The skills/ subtree must resolve by commit sha");
        assertEquals("subdir-skill", skill.getName());
        assertEquals(sha, skill.getVersion());
    }

    @Test
    @DisplayName("Should answer null for unknown revisions, missing skills and blank arguments")
    void testVersionContract_NullForUnresolvableArguments() throws Exception {
        Path localRepo = createLocalGitRepository();
        repository =
                new GitSkillRepository(
                        "file://" + localRepo.toString(), null, null, null, false, null, cacheDir);
        repository.sync();

        assertNull(
                latestVersionOf(repository, "no-such-skill"),
                "A skill this repository does not serve must report no version");
        assertNull(latestVersionOf(repository, " "), "A blank skill name must report no version");
        assertNull(
                repository.getSkill("test-skill", "1234567890123456789012345678901234567890"),
                "An unknown revision must not resolve");
        assertNull(
                repository.getSkill("no-such-skill", headSha(localRepo)),
                "A skill absent from the commit must not resolve");
        assertNull(
                repository.getSkill(" ", headSha(localRepo)),
                "A blank skill name must not resolve");
    }

    @Test
    @DisplayName("Should serve an annotated tag version as the tagged commit with a sha stamp")
    void testVersionContract_AnnotatedTagResolvesToTaggedCommit() throws Exception {
        Path localRepo = createLocalGitRepository();
        try (Git git = Git.open(localRepo.toFile())) {
            git.tag().setName("v1.0").setMessage("release v1.0").call();
            try (RevWalk revWalk = new RevWalk(git.getRepository())) {
                assertTrue(
                        revWalk.parseAny(
                                        git.getRepository()
                                                .exactRef("refs/tags/v1.0")
                                                .getObjectId())
                                instanceof RevTag,
                        "The test fixture must create an annotated (not lightweight) tag");
            }
        }
        repository =
                new GitSkillRepository(
                        "file://" + localRepo.toString(), null, null, null, false, null, cacheDir);
        repository.sync();

        AgentSkill skill = repository.getSkill("test-skill", "v1.0");

        assertNotNull(skill, "An annotated tag must resolve to its tagged commit");
        assertTrue(
                bodyOf(skill).contains("test skill"), "The tagged commit must serve its content");
        assertEquals(
                headSha(localRepo),
                skill.getVersion(),
                "A tag reference is served as the underlying commit sha stamp");
    }

    @Test
    @DisplayName("Should materialize binary resources byte-exact")
    void testVersionContract_BinaryResourceRoundTripsByteExact() throws Exception {
        Path localRepo = createLocalGitRepository();
        byte[] iconBytes = {
            (byte) 0x89,
            0x50,
            0x4e,
            0x47,
            0x0d,
            0x0a,
            0x1a,
            0x0a,
            0x00,
            (byte) 0xff,
            (byte) 0xfe,
            (byte) 0x89
        };
        try (Git git = Git.open(localRepo.toFile())) {
            Files.write(localRepo.resolve("test-skill").resolve("icon.png"), iconBytes);
            git.add().addFilepattern(".").call();
            git.commit().setMessage("add binary icon").call();
        }
        repository =
                new GitSkillRepository(
                        "file://" + localRepo.toString(), null, null, null, false, null, cacheDir);
        repository.sync();

        AgentSkill skill = repository.getSkill("test-skill", null);

        assertNotNull(skill);
        assertArrayEquals(
                iconBytes,
                Files.readAllBytes(skill.getOriginDir().orElseThrow().resolve("icon.png")),
                "A binary resource must materialise byte-exact, not as UTF-8 replacement"
                        + " characters");
    }

    @Test
    @DisplayName("Should never serve stale cache for a moving reference used as version")
    void testVersionContract_MovingRefVersionTracksTheRef() throws Exception {
        Path localRepo = createLocalGitRepository();
        repository =
                new GitSkillRepository(
                        "file://" + localRepo.toString(), null, null, null, true, null, cacheDir);

        assertEquals(
                "This is a test skill.",
                bodyOf(repository.getSkill("test-skill", "HEAD"))
                        .lines()
                        .filter(line -> !line.isBlank() && !line.startsWith("#"))
                        .findFirst()
                        .orElseThrow()
                        .trim());

        commitSkillUpdate(localRepo, "This is version two.", "v2");

        AgentSkill viaHead = repository.getSkill("test-skill", "HEAD");
        assertTrue(
                bodyOf(viaHead).contains("version two"),
                "An explicit moving reference must track the ref after sync, not serve the"
                        + " stale cached directory keyed by the raw ref string");
        assertEquals(
                headSha(localRepo),
                viaHead.getVersion(),
                "The moving reference must be stamped as its current commit sha");
        assertTrue(
                bodyOf(repository.getSkill("test-skill", null)).contains("version two"),
                "The null-version pull must agree with the explicit HEAD reference");
    }

    @Test
    @DisplayName("Should answer null when the directory name matches but the manifest does not")
    void testVersionContract_NullForManifestNameMismatch() throws Exception {
        Path repoDir = tempDir.resolve("mismatch-repo");
        Files.createDirectories(repoDir.resolve("foo"));
        Files.writeString(
                repoDir.resolve("foo").resolve("SKILL.md"),
                "---\nname: bar\ndescription: another skill\n---\n\nbody");
        try (Git git = Git.init().setDirectory(repoDir.toFile()).call()) {
            git.add().addFilepattern(".").call();
            git.commit().setMessage("initial").call();
        }
        repository =
                new GitSkillRepository(
                        "file://" + repoDir.toString(), null, null, null, false, null, cacheDir);
        repository.sync();

        assertNull(
                repository.getSkill("foo", null),
                "A directory hit whose manifest declares another name is an honest null, not"
                        + " an exception");
    }

    /** Commits a content update to the test-skill SKILL.md and returns the new commit id. */
    private String commitSkillUpdate(Path repoDir, String contentLine, String message)
            throws Exception {
        try (Git git = Git.open(repoDir.toFile())) {
            Files.writeString(
                    repoDir.resolve("test-skill").resolve("SKILL.md"),
                    "---\nname: test-skill\ndescription: A test skill\n---\n\n# Test Skill\n"
                            + contentLine
                            + "\n");
            git.add().addFilepattern(".").call();
            return git.commit().setMessage(message).call().getName();
        }
    }

    /** Returns the current HEAD commit id of the given local repository. */
    private String headSha(Path repoDir) throws Exception {
        try (Git git = Git.open(repoDir.toFile())) {
            ObjectId head = git.getRepository().resolve("HEAD");
            return head != null ? head.getName() : null;
        }
    }

    /**
     * The skill's current version, read from a {@code null}-version pull's stamp; {@code
     * null} when the skill does not resolve.
     */
    private static String latestVersionOf(GitSkillRepository repo, String name) {
        AgentSkill current = repo.getSkill(name, null);
        return current != null ? current.getVersion() : null;
    }
}

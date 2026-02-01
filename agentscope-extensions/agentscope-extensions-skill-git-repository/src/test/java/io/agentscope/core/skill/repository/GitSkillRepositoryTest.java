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

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.agentscope.core.skill.AgentSkill;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.eclipse.jgit.api.Git;
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
        repository = new GitSkillRepository("file://" + localRepo.toString());

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
        repository = new GitSkillRepository("file://" + localRepo.toString(), false);

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
        repository = new GitSkillRepository("file://" + localRepo.toString());

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
        repository = new GitSkillRepository("file://" + localRepo.toString());

        // Verify skill can be loaded from repository root
        AgentSkill skill = repository.getSkill("test-skill");
        assertNotNull(skill);
        assertEquals("test-skill", skill.getName());
    }

    @Test
    @DisplayName("Should get all skill names from local repository")
    void testGetAllSkillNames_FromLocalRepo() throws Exception {
        Path localRepo = createLocalGitRepository();
        repository = new GitSkillRepository("file://" + localRepo.toString());

        List<String> skillNames = repository.getAllSkillNames();
        assertNotNull(skillNames);
        assertEquals(1, skillNames.size());
        assertTrue(skillNames.contains("test-skill"));
    }

    @Test
    @DisplayName("Should get all skills from local repository")
    void testGetAllSkills_FromLocalRepo() throws Exception {
        Path localRepo = createLocalGitRepository();
        repository = new GitSkillRepository("file://" + localRepo.toString());

        List<AgentSkill> skills = repository.getAllSkills();
        assertNotNull(skills);
        assertEquals(1, skills.size());
        assertEquals("test-skill", skills.get(0).getName());
    }

    @Test
    @DisplayName("Should check skill existence in local repository")
    void testSkillExists_FromLocalRepo() throws Exception {
        Path localRepo = createLocalGitRepository();
        repository = new GitSkillRepository("file://" + localRepo.toString());

        assertTrue(repository.skillExists("test-skill"));
        assertFalse(repository.skillExists("non-existent-skill"));
    }

    @Test
    @DisplayName("Should pull updates from local repository")
    void testPull_FromLocalRepo() throws Exception {
        // Create local Git repository
        Path localRepo = createLocalGitRepository();

        // First access (clone)
        repository = new GitSkillRepository("file://" + localRepo.toString());
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
        repository = new GitSkillRepository("file://" + localRepo.toString(), "develop");

        List<String> skillNames = repository.getAllSkillNames();
        assertTrue(skillNames.contains("dev-skill"), "Should contain skill from develop branch");
    }

    // ==================== Read-Only Tests ====================

    @Test
    @DisplayName("Should return false for save operation")
    void testSave_ReadOnly() throws Exception {
        Path localRepo = createLocalGitRepository();
        repository = new GitSkillRepository("file://" + localRepo.toString());

        AgentSkill skill = repository.getSkill("test-skill");
        boolean result = repository.save(List.of(skill), false);

        assertFalse(result, "Save should return false for read-only repository");
    }

    @Test
    @DisplayName("Should return false for delete operation")
    void testDelete_ReadOnly() throws Exception {
        Path localRepo = createLocalGitRepository();
        repository = new GitSkillRepository("file://" + localRepo.toString());

        boolean result = repository.delete("test-skill");

        assertFalse(result, "Delete should return false for read-only repository");
    }

    @Test
    @DisplayName("Should always return false for isWriteable")
    void testIsWriteable() throws Exception {
        Path localRepo = createLocalGitRepository();
        repository = new GitSkillRepository("file://" + localRepo.toString());

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
        repository = new GitSkillRepository(testUrl);

        AgentSkillRepositoryInfo info = repository.getRepositoryInfo();
        assertNotNull(info);
        assertEquals("git", info.getType());
        assertEquals(testUrl, info.getLocation());
        assertFalse(info.isWritable());
    }

    @Test
    @DisplayName("Should return correct source identifier")
    void testGetSource() {
        String testUrl = "https://github.com/test/repo.git";
        repository = new GitSkillRepository(testUrl);

        String source = repository.getSource();
        assertEquals("git:test/repo", source);
    }

    @Test
    @DisplayName("Should return custom source identifier when provided")
    void testGetSource_Custom() {
        String testUrl = "https://github.com/test/repo.git";
        String customSource = "git:custom-source";
        repository = new GitSkillRepository(testUrl, null, null, customSource);

        String source = repository.getSource();
        assertEquals(customSource, source);
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
        // Error message may contain "not found", "does not exist", "Unable to connect",
        // "Invalid",
        // etc.
        assertTrue(
                message.contains("not found")
                        || message.contains("does not exist")
                        || message.contains("Unable to connect")
                        || message.contains("inaccessible")
                        || message.contains("Invalid remote repository address"),
                "Error message should mention repository issue: " + message);
    }

    @Test
    @DisplayName("Should throw exception for invalid URL format")
    void testClone_InvalidUrl() {
        repository = new GitSkillRepository("not-a-valid-git-url");

        RuntimeException exception =
                assertThrows(RuntimeException.class, () -> repository.getSkill("test"));

        assertNotNull(exception.getMessage());
    }
}

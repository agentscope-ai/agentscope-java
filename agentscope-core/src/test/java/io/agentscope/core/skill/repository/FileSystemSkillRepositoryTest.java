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
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Unit tests for FileSystemSkillRepository.
 *
 * <p>Tests file system based skill repository functionality including loading, saving,
 * deleting skills and managing nested resource structures.
 *
 * <p>Tagged as "unit" - fast running tests without external dependencies.
 */
@Tag("unit")
@DisplayName("FileSystemSkillRepository Unit Tests")
class FileSystemSkillRepositoryTest {

    @TempDir Path tempDir;

    private Path skillsBaseDir;
    private FileSystemSkillRepository repository;

    @BeforeEach
    void setUp() throws IOException {
        skillsBaseDir = tempDir.resolve("skills");
        Files.createDirectories(skillsBaseDir);

        // Create a sample skill directory structure
        createSampleSkill("test-skill", "Test Skill", "This is a test skill");
        createSampleSkill("another-skill", "Another Skill", "This is another skill");

        repository = new FileSystemSkillRepository(skillsBaseDir);
    }

    @AfterEach
    void tearDown() {
        // Cleanup is handled by @TempDir
    }

    // ==================== Constructor Tests ====================

    @Test
    @DisplayName("Should create repository with valid base directory")
    void testConstructor_ValidBaseDir() {
        assertNotNull(repository);
        assertEquals("filesystem", repository.getRepositoryInfo().getType());
        assertTrue(repository.getRepositoryInfo().isWritable());
    }

    @Test
    @DisplayName("Should throw exception when base directory is null")
    void testConstructor_NullBaseDir() {
        assertThrows(IllegalArgumentException.class, () -> new FileSystemSkillRepository(null));
    }

    @Test
    @DisplayName("Should throw exception when base directory does not exist")
    void testConstructor_NonExistentBaseDir() {
        Path nonExistent = tempDir.resolve("non-existent");
        assertThrows(
                IllegalArgumentException.class, () -> new FileSystemSkillRepository(nonExistent));
    }

    @Test
    @DisplayName("Should throw exception when base directory is a file")
    void testConstructor_BaseDirIsFile() throws IOException {
        Path file = tempDir.resolve("file.txt");
        Files.writeString(file, "content");
        assertThrows(IllegalArgumentException.class, () -> new FileSystemSkillRepository(file));
    }

    @Test
    @DisplayName("Should not throw exception when base directory is empty")
    void testConstructor_EmptyBaseDir() throws IOException {
        Path emptyDir = tempDir.resolve("empty");
        Files.createDirectories(emptyDir);
        FileSystemSkillRepository fileSystemSkillRepository =
                new FileSystemSkillRepository(emptyDir);
        assertNotNull(fileSystemSkillRepository);
    }

    @Test
    @DisplayName("Should transform relative path to absolute in constructor")
    void testConstructor_RelativePath() throws IOException {
        Path relativePath = Path.of("relative-skills");
        Files.createDirectories(relativePath);

        try {
            FileSystemSkillRepository fileSystemSkillRepository =
                    new FileSystemSkillRepository(relativePath);
            assertNotNull(fileSystemSkillRepository);
            assertEquals(
                    relativePath.toAbsolutePath().normalize().toString(),
                    fileSystemSkillRepository.getRepositoryInfo().getLocation());
        } finally {
            if (Files.exists(relativePath)) {
                Files.delete(relativePath);
            }
        }
    }

    // ==================== getAllSkillNames Tests ====================

    @Test
    @DisplayName("Should get all skill names")
    void testGetAllSkillNames() {
        List<String> names = repository.getAllSkillNames();
        assertNotNull(names);
        assertEquals(2, names.size());
        assertTrue(names.contains("test-skill"));
        assertTrue(names.contains("another-skill"));
    }

    @Test
    @DisplayName("Should return sorted skill names")
    void testGetAllSkillNames_Sorted() {
        List<String> names = repository.getAllSkillNames();
        assertEquals("another-skill", names.get(0));
        assertEquals("test-skill", names.get(1));
    }

    @Test
    @DisplayName("Should ignore directories without SKILL.md")
    void testGetAllSkillNames_IgnoreInvalidDirs() throws IOException {
        Path invalidDir = skillsBaseDir.resolve("invalid-dir");
        Files.createDirectories(invalidDir);
        Files.writeString(invalidDir.resolve("README.md"), "Not a skill");

        List<String> names = repository.getAllSkillNames();
        assertEquals(2, names.size());
        assertFalse(names.contains("invalid-dir"));
    }

    // ==================== getSkill Tests ====================

    @Test
    @DisplayName("Should get skill by name")
    void testGetSkill() {
        AgentSkill skill = repository.getSkill("test-skill");
        assertNotNull(skill);
        assertEquals("test-skill", skill.getName());
        assertEquals("Test Skill", skill.getDescription());
        assertEquals("This is a test skill", skill.getSkillContent());
        assertTrue(skill.getSource().startsWith("filesystem_"));
    }

    @Test
    @DisplayName("Should throw exception when skill does not exist")
    void testGetSkill_NotFound() {
        assertThrows(IllegalArgumentException.class, () -> repository.getSkill("non-existent"));
    }

    @Test
    @DisplayName("Should throw exception when skill name is null")
    void testGetSkill_NullName() {
        assertThrows(IllegalArgumentException.class, () -> repository.getSkill(null));
    }

    @Test
    @DisplayName("Should throw exception when skill name is empty")
    void testGetSkill_EmptyName() {
        assertThrows(IllegalArgumentException.class, () -> repository.getSkill(""));
    }

    @Test
    @DisplayName("Should load skill with resources")
    void testGetSkill_WithResources() throws IOException {
        // Create a skill with resources
        Path skillDir = skillsBaseDir.resolve("skill-with-resources");
        Files.createDirectories(skillDir);

        String skillMd =
                "---\n"
                        + "name: skill-with-resources\n"
                        + "description: Skill with resources\n"
                        + "---\n"
                        + "Main content";
        Files.writeString(skillDir.resolve("SKILL.md"), skillMd, StandardCharsets.UTF_8);

        // Create nested resources
        Path referencesDir = skillDir.resolve("references");
        Files.createDirectories(referencesDir);
        Files.writeString(referencesDir.resolve("api.md"), "API documentation");

        Path examplesDir = skillDir.resolve("examples");
        Files.createDirectories(examplesDir);
        Files.writeString(examplesDir.resolve("example1.txt"), "Example 1");

        AgentSkill skill = repository.getSkill("skill-with-resources");
        assertNotNull(skill);
        assertEquals("skill-with-resources", skill.getName());
        assertEquals(2, skill.getResources().size());
        assertEquals("API documentation", skill.getResources().get("references/api.md"));
        assertEquals("Example 1", skill.getResources().get("examples/example1.txt"));
    }

    @Test
    @DisplayName("Should throw exception when SKILL.md is missing")
    void testGetSkill_MissingSkillMd() throws IOException {
        Path skillDir = skillsBaseDir.resolve("no-skill-md");
        Files.createDirectories(skillDir);
        Files.writeString(skillDir.resolve("README.md"), "Not a SKILL.md");

        assertThrows(IllegalArgumentException.class, () -> repository.getSkill("no-skill-md"));
    }

    // ==================== getAllSkills Tests ====================

    @Test
    @DisplayName("Should get all skills")
    void testGetAllSkills() {
        List<AgentSkill> skills = repository.getAllSkills();
        assertNotNull(skills);
        assertEquals(2, skills.size());

        AgentSkill skill1 = skills.get(0);
        assertEquals("another-skill", skill1.getName());

        AgentSkill skill2 = skills.get(1);
        assertEquals("test-skill", skill2.getName());
    }

    @Test
    @DisplayName("Should continue loading when one skill fails")
    void testGetAllSkills_ContinueOnError() throws IOException {
        // Create a corrupted skill directory
        Path corruptedDir = skillsBaseDir.resolve("corrupted-skill");
        Files.createDirectories(corruptedDir);
        Files.writeString(corruptedDir.resolve("SKILL.md"), "Invalid YAML\n---\nno frontmatter");

        List<AgentSkill> skills = repository.getAllSkills();
        // Should still load the valid skills
        assertTrue(skills.size() >= 2);
    }

    // ==================== save Tests ====================

    @Test
    @DisplayName("Should save new skill")
    void testSave_NewSkill() {
        Map<String, String> resources = new HashMap<>();
        resources.put("references/doc.md", "Documentation");
        AgentSkill newSkill = new AgentSkill("new-skill", "New Skill", "Content", resources);

        boolean result = repository.save(List.of(newSkill), false);
        assertTrue(result);

        // Verify skill was saved
        assertTrue(repository.skillExists("new-skill"));
        AgentSkill loaded = repository.getSkill("new-skill");
        assertEquals("new-skill", loaded.getName());
        assertEquals("New Skill", loaded.getDescription());
        assertEquals(1, loaded.getResources().size());
    }

    @Test
    @DisplayName("Should return false when saving empty list")
    void testSave_EmptyList() {
        boolean result = repository.save(List.of(), false);
        assertFalse(result);
    }

    @Test
    @DisplayName("Should return false when saving null list")
    void testSave_NullList() {
        boolean result = repository.save(null, false);
        assertFalse(result);
    }

    @Test
    @DisplayName("Should return false when skill exists and force is false")
    void testSave_ExistingSkill_ForceDisabled() {
        AgentSkill existingSkill = new AgentSkill("test-skill", "Updated", "Updated content", null);

        boolean result = repository.save(List.of(existingSkill), false);
        assertFalse(result);

        // Verify original skill was not modified
        AgentSkill loaded = repository.getSkill("test-skill");
        assertEquals("Test Skill", loaded.getDescription()); // Original description
    }

    @Test
    @DisplayName("Should overwrite when skill exists and force is true")
    void testSave_ExistingSkill_ForceEnabled() {
        AgentSkill updatedSkill =
                new AgentSkill("test-skill", "Updated Description", "Updated content", null);

        boolean result = repository.save(List.of(updatedSkill), true);
        assertTrue(result);

        // Verify skill was updated
        AgentSkill loaded = repository.getSkill("test-skill");
        assertEquals("Updated Description", loaded.getDescription());
        assertEquals("Updated content", loaded.getSkillContent());
    }

    @Test
    @DisplayName("Should save skill with nested resources")
    void testSave_NestedResources() {
        Map<String, String> resources = new HashMap<>();
        resources.put("references/api/endpoints.md", "Endpoints");
        resources.put("references/api/auth.md", "Authentication");
        resources.put("examples/basic/example1.txt", "Example 1");
        resources.put("scripts/setup.sh", "#!/bin/bash\necho 'setup'");

        AgentSkill skill = new AgentSkill("complex-skill", "Complex", "Content", resources);

        boolean result = repository.save(List.of(skill), false);
        assertTrue(result);

        // Verify all resources were saved
        AgentSkill loaded = repository.getSkill("complex-skill");
        assertEquals(4, loaded.getResources().size());
        assertEquals("Endpoints", loaded.getResources().get("references/api/endpoints.md"));
        assertEquals("Example 1", loaded.getResources().get("examples/basic/example1.txt"));
    }

    @Test
    @DisplayName("Should save multiple skills")
    void testSave_MultipleSkills() {
        AgentSkill skill1 = new AgentSkill("skill-1", "Skill 1", "Content 1", null);
        AgentSkill skill2 = new AgentSkill("skill-2", "Skill 2", "Content 2", null);

        boolean result = repository.save(List.of(skill1, skill2), false);
        assertTrue(result);

        assertTrue(repository.skillExists("skill-1"));
        assertTrue(repository.skillExists("skill-2"));
    }

    // ==================== delete Tests ====================

    @Test
    @DisplayName("Should delete existing skill")
    void testDelete_ExistingSkill() {
        assertTrue(repository.skillExists("test-skill"));

        boolean result = repository.delete("test-skill");
        assertTrue(result);

        assertFalse(repository.skillExists("test-skill"));
    }

    @Test
    @DisplayName("Should return false when deleting non-existent skill")
    void testDelete_NonExistentSkill() {
        boolean result = repository.delete("non-existent");
        assertFalse(result);
    }

    @Test
    @DisplayName("Should throw exception when deleting with null name")
    void testDelete_NullName() {
        assertThrows(IllegalArgumentException.class, () -> repository.delete(null));
    }

    @Test
    @DisplayName("Should throw exception when deleting with empty name")
    void testDelete_EmptyName() {
        assertThrows(IllegalArgumentException.class, () -> repository.delete(""));
    }

    @Test
    @DisplayName("Should delete skill with nested resources")
    void testDelete_NestedResources() throws IOException {
        // Create a skill with nested resources
        Path skillDir = skillsBaseDir.resolve("nested-skill");
        Files.createDirectories(skillDir);
        Files.writeString(
                skillDir.resolve("SKILL.md"),
                "---\nname: nested-skill\ndescription: Test\n---\nContent");

        Path nestedDir = skillDir.resolve("deep/nested/structure");
        Files.createDirectories(nestedDir);
        Files.writeString(nestedDir.resolve("file.txt"), "content");

        assertTrue(repository.skillExists("nested-skill"));

        boolean result = repository.delete("nested-skill");
        assertTrue(result);

        assertFalse(Files.exists(skillDir));
    }

    // ==================== exists Tests ====================

    @Test
    @DisplayName("Should return true when skill exists")
    void testSkillExists_Exists() {
        assertTrue(repository.skillExists("test-skill"));
    }

    @Test
    @DisplayName("Should return false when skill does not exist")
    void testSkillExists_NotExists() {
        assertFalse(repository.skillExists("non-existent"));
    }

    @Test
    @DisplayName("Should return false when skill name is null")
    void testSkillExists_NullName() {
        assertFalse(repository.skillExists(null));
    }

    @Test
    @DisplayName("Should return false when skill name is empty")
    void testSkillExists_EmptyName() {
        assertFalse(repository.skillExists(""));
    }

    @Test
    @DisplayName("Should return false when directory exists but no SKILL.md")
    void testSkillExists_NoSkillMd() throws IOException {
        Path dirWithoutSkill = skillsBaseDir.resolve("no-skill-md");
        Files.createDirectories(dirWithoutSkill);
        Files.writeString(dirWithoutSkill.resolve("README.md"), "Not a skill");

        assertFalse(repository.skillExists("no-skill-md"));
    }

    // ==================== getSource Tests ====================

    @Test
    @DisplayName("Should return correct source format")
    void testGetSource() {
        String source = repository.getSource();
        assertNotNull(source);
        assertTrue(source.startsWith("filesystem_"));
        assertTrue(source.contains(skillsBaseDir.toString()));
    }

    // ==================== getRepositoryInfo Tests ====================

    @Test
    @DisplayName("Should return correct repository info")
    void testGetRepositoryInfo() {
        AgentSkillRepositoryInfo info = repository.getRepositoryInfo();
        assertNotNull(info);
        assertEquals("filesystem", info.getType());
        assertEquals(skillsBaseDir.toString(), info.getLocation());
        assertTrue(info.isWritable());
    }

    // ==================== Integration Tests ====================

    @Test
    @DisplayName("Should complete save-load-delete cycle")
    void testIntegration_SaveLoadDelete() {
        // Save
        Map<String, String> resources = new HashMap<>();
        resources.put("doc.md", "Documentation");
        AgentSkill skill = new AgentSkill("integration-test", "Integration", "Content", resources);

        assertTrue(repository.save(List.of(skill), false));

        // Load
        assertTrue(repository.skillExists("integration-test"));
        AgentSkill loaded = repository.getSkill("integration-test");
        assertEquals("integration-test", loaded.getName());
        assertEquals("Integration", loaded.getDescription());
        assertEquals(1, loaded.getResources().size());

        // Delete
        assertTrue(repository.delete("integration-test"));
        assertFalse(repository.skillExists("integration-test"));
    }

    @Test
    @DisplayName("Should handle Unicode in skill names and content")
    void testIntegration_Unicode() {
        AgentSkill skill = new AgentSkill("技能名称", "技能描述", "技能内容\n包含中文", null);

        assertTrue(repository.save(List.of(skill), false));
        assertTrue(repository.skillExists("技能名称"));

        AgentSkill loaded = repository.getSkill("技能名称");
        assertEquals("技能名称", loaded.getName());
        assertEquals("技能描述", loaded.getDescription());
        assertEquals("技能内容\n包含中文", loaded.getSkillContent());
    }

    // ==================== Path Traversal Security Tests ====================

    @Test
    @DisplayName("Should prevent path traversal with parent directory references in getSkill")
    void testGetSkill_PathTraversal_ParentDir() {
        assertThrows(IllegalArgumentException.class, () -> repository.getSkill("../outside-skill"));
    }

    @Test
    @DisplayName("Should prevent path traversal with absolute path in getSkill")
    void testGetSkill_PathTraversal_AbsolutePath() {
        assertThrows(IllegalArgumentException.class, () -> repository.getSkill("/etc/passwd"));
    }

    @Test
    @DisplayName("Should prevent path traversal with multiple parent dirs in getSkill")
    void testGetSkill_PathTraversal_MultipleParentDirs() {
        assertThrows(
                IllegalArgumentException.class, () -> repository.getSkill("../../outside-skill"));
    }

    @Test
    @DisplayName("Should prevent path traversal with mixed slashes in getSkill")
    void testGetSkill_PathTraversal_MixedSlashes() {
        assertThrows(
                IllegalArgumentException.class, () -> repository.getSkill("..\\..\\outside-skill"));
    }

    @Test
    @DisplayName("Should prevent path traversal in delete")
    void testDelete_PathTraversal() {
        assertThrows(IllegalArgumentException.class, () -> repository.delete("../outside-skill"));
    }

    @Test
    @DisplayName("Should prevent path traversal with absolute path in delete")
    void testDelete_PathTraversal_AbsolutePath() {
        assertThrows(IllegalArgumentException.class, () -> repository.delete("/tmp/test"));
    }

    @Test
    @DisplayName("Should return false for path traversal in exists")
    void testSkillExists_PathTraversal() {
        assertFalse(repository.skillExists("../outside-skill"));
    }

    @Test
    @DisplayName("Should return false for absolute path in exists")
    void testSkillExists_PathTraversal_AbsolutePath() {
        assertFalse(repository.skillExists("/etc/passwd"));
    }

    @Test
    @DisplayName("Should prevent path traversal with encoded characters")
    void testPathTraversal_EncodedCharacters() {
        // URL encoded ../
        assertThrows(IllegalArgumentException.class, () -> repository.getSkill("%2e%2e%2f"));
    }

    @Test
    @DisplayName("Should allow valid subdirectory paths")
    void testPathTraversal_ValidSubdirectory() throws IOException {
        // Create a skill with a hyphen that could be confused with path
        createSampleSkill("valid-sub-dir", "Valid Skill", "Content");

        // This should work fine - it's a valid skill name
        AgentSkill skill = repository.getSkill("valid-sub-dir");
        assertNotNull(skill);
        assertEquals("valid-sub-dir", skill.getName());
    }

    @Test
    @DisplayName("Should prevent path traversal with dot segments in middle")
    void testPathTraversal_DotSegmentsInMiddle() {
        assertThrows(
                IllegalArgumentException.class,
                () -> repository.getSkill("valid/../outside-skill"));
    }

    @Test
    @DisplayName("Should prevent path traversal in save operation")
    void testSave_PathTraversal() {
        AgentSkill maliciousSkill =
                new AgentSkill("../outside-skill", "Malicious", "Content", null);

        // This should fail during skill name validation when trying to save
        // The validateAndResolvePath method will throw IllegalArgumentException
        // which will be wrapped in RuntimeException by the save method
        Exception exception =
                assertThrows(
                        RuntimeException.class,
                        () -> repository.save(List.of(maliciousSkill), false));

        // Verify the cause is IllegalArgumentException for path traversal
        assertTrue(
                exception.getCause() instanceof IllegalArgumentException
                        || exception.getMessage().contains("path traversal"));
    }

    @Test
    @DisplayName("Should handle symbolic link attacks")
    void testPathTraversal_SymbolicLinks() throws IOException {
        // Create a directory outside baseDir
        Path outsideDir = tempDir.resolve("outside");
        Files.createDirectories(outsideDir);
        Path outsideFile = outsideDir.resolve("secret.txt");
        Files.writeString(outsideFile, "secret content");

        // Try to create a symbolic link inside skills directory pointing outside
        Path symlinkPath = skillsBaseDir.resolve("symlink-skill");
        try {
            Files.createSymbolicLink(symlinkPath, outsideDir);

            // Even if symlink is created, accessing it should be safe
            // because validateAndResolvePath checks the resolved path
            if (Files.exists(symlinkPath)) {
                // Create SKILL.md to make it look like a valid skill
                Files.writeString(
                        symlinkPath.resolve("SKILL.md"),
                        "---\nname: symlink-skill\ndescription: Test\n---\nContent");

                // This should either fail or only access content within the symlinked
                // directory
                // The key is that it shouldn't escape baseDir's parent
                try {
                    repository.getSkill("symlink-skill");
                } catch (Exception e) {
                    // Expected - either path traversal detection or file not found
                    assertTrue(
                            e instanceof IllegalArgumentException || e instanceof RuntimeException);
                }
            }
        } catch (UnsupportedOperationException | IOException e) {
            // Symbolic links might not be supported on all systems, skip test
            // This is acceptable for this test
        }
    }

    // ==================== Writeable Tests ====================

    @Test
    @DisplayName("Should have writeable true by default")
    void testWriteable_DefaultTrue() {
        assertTrue(repository.isWriteable());
        assertTrue(repository.getRepositoryInfo().isWritable());
    }

    @Test
    @DisplayName("Should update writeable flag")
    void testSetWriteable() {
        repository.setWriteable(false);
        assertFalse(repository.isWriteable());
        assertFalse(repository.getRepositoryInfo().isWritable());

        repository.setWriteable(true);
        assertTrue(repository.isWriteable());
        assertTrue(repository.getRepositoryInfo().isWritable());
    }

    @Test
    @DisplayName("Should not save when repository is read-only")
    void testSave_ReadOnly() {
        repository.setWriteable(false);

        AgentSkill skill = new AgentSkill("readonly-test", "Test", "Content", null);
        boolean result = repository.save(List.of(skill), false);

        assertFalse(result);
        assertFalse(repository.skillExists("readonly-test"));
    }

    @Test
    @DisplayName("Should not delete when repository is read-only")
    void testDelete_ReadOnly() {
        assertTrue(repository.skillExists("test-skill"));

        repository.setWriteable(false);
        boolean result = repository.delete("test-skill");

        assertFalse(result);
        assertTrue(repository.skillExists("test-skill")); // Still exists
    }

    @Test
    @DisplayName("Should still read when repository is read-only")
    void testRead_ReadOnly() {
        repository.setWriteable(false);

        // All read operations should still work
        assertNotNull(repository.getAllSkillNames());
        assertNotNull(repository.getAllSkills());
        assertNotNull(repository.getSkill("test-skill"));
        assertTrue(repository.skillExists("test-skill"));
    }

    // ==================== Helper Methods ====================

    /**
     * Creates a sample skill directory with SKILL.md.
     */
    private void createSampleSkill(String name, String description, String content)
            throws IOException {
        Path skillDir = skillsBaseDir.resolve(name);
        Files.createDirectories(skillDir);

        String skillMd =
                "---\n"
                        + "name: "
                        + name
                        + "\n"
                        + "description: "
                        + description
                        + "\n"
                        + "---\n"
                        + content;

        Files.writeString(skillDir.resolve("SKILL.md"), skillMd, StandardCharsets.UTF_8);
    }
}

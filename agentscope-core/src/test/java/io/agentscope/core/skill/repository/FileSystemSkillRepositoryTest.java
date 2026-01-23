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

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
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

    // ==================== getSource Tests ====================

    @Test
    @DisplayName("Should return correct source format")
    void testGetSource() {
        String source = repository.getSource();
        assertNotNull(source);
        assertTrue(source.startsWith("filesystem_"));
        String expectedSuffix =
                skillsBaseDir.getParent().getFileName() + "/" + skillsBaseDir.getFileName();
        assertEquals("filesystem_" + expectedSuffix, source);
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
        assertFalse(repository.save(null, false));
    }

    @Test
    @DisplayName("Should not delete when repository is read-only")
    void testDelete_ReadOnly() {
        repository.setWriteable(false);
        assertFalse(repository.delete("test-skill"));
    }

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

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
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.agentscope.core.skill.AgentSkill;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Collections;
import java.util.LinkedHashMap;
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

    // ==================== getSource Tests ====================

    @Test
    @DisplayName("Should return default source with format: filesystem-parent_child")
    void testGetSource_DefaultSource() {
        assertTrue(repository.getSource().matches("filesystem-[^_]+_skills"));
    }

    @Test
    @DisplayName("Should return custom source when provided")
    void testGetSource_CustomSource() {
        FileSystemSkillRepository repo =
                new FileSystemSkillRepository(skillsBaseDir, true, "custom-source");
        assertEquals("custom-source", repo.getSource());
    }

    @Test
    @DisplayName("Should use default source when null")
    void testGetSource_Null() {
        FileSystemSkillRepository repo = new FileSystemSkillRepository(skillsBaseDir, true, null);
        assertTrue(repo.getSource().startsWith("filesystem-"));
    }

    // ==================== buildDefaultSourceSuffix Tests ====================

    @Test
    @DisplayName("Should build source: parent_child")
    void testBuildSourceSuffix_TwoLevels() throws IOException {
        Path dir = tempDir.resolve("parent").resolve("child");
        Files.createDirectories(dir);
        assertEquals("filesystem-parent_child", new FileSystemSkillRepository(dir).getSource());
    }

    @Test
    @DisplayName("Should use last two levels for deep paths")
    void testBuildSourceSuffix_DeepPath() throws IOException {
        Path dir = tempDir.resolve("a/b/c/d");
        Files.createDirectories(dir);
        assertEquals("filesystem-c_d", new FileSystemSkillRepository(dir).getSource());
    }

    @Test
    @DisplayName("Should preserve special chars in path")
    void testBuildSourceSuffix_SpecialChars() throws IOException {
        Path dir = tempDir.resolve("my-project").resolve("skills_v1.0");
        Files.createDirectories(dir);
        assertEquals(
                "filesystem-my-project_skills_v1.0",
                new FileSystemSkillRepository(dir).getSource());
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

    @Test
    @DisplayName("Should discover and load a UTF-8 SKILL.md with a BOM")
    void testLoadSkillWithUtf8Bom() throws IOException {
        Path skillDir = Files.createDirectories(skillsBaseDir.resolve("bom-skill"));
        String content = "# Instructions\r\nPreserve this \uFEFF character.";
        String markdown =
                "\uFEFF---\r\nname: bom-skill\r\ndescription: A BOM skill\r\n---\r\n" + content;
        Files.writeString(skillDir.resolve("SKILL.md"), markdown, StandardCharsets.UTF_8);

        assertEquals(3, repository.getAllSkills().size());
        assertTrue(repository.getAllSkillNames().contains("bom-skill"));
        AgentSkill skill = repository.getSkill("bom-skill");
        assertNotNull(skill);
        assertEquals("bom-skill", skill.getName());
        assertEquals("A BOM skill", skill.getDescription());
        assertNull(skill.getSkillContent(), "summaries carry no body in memory");
        assertNotNull(
                repository.getSkill("bom-skill", null),
                "the discovered name must resolve through the serving method");
    }

    // ==================== Save Tests ====================

    @Test
    @DisplayName("Should save a new skill successfully")
    void testSave_NewSkill() throws IOException {
        AgentSkill skill = new AgentSkill("new-skill", "New Skill", "New skill content", null);

        assertTrue(repository.save(List.of(skill), false));
        assertTrue(repository.skillExists("new-skill"));

        AgentSkill loaded = repository.getSkill("new-skill");
        assertNotNull(loaded);
        assertEquals("new-skill", loaded.getName());
        assertEquals("New Skill", loaded.getDescription());
        assertTrue(skillBody(loaded).contains("New skill content"));
    }

    @Test
    @DisplayName("Should save skill with resources")
    void testSave_WithResources() {
        Map<String, String> resources =
                Map.of(
                        "references/guide.md", "# Guide\nSome guide content",
                        "config.json", "{\"key\": \"value\"}");
        AgentSkill skill =
                new AgentSkill(
                        "resource-skill", "Resource Skill", "Skill with resources", resources);

        assertTrue(repository.save(List.of(skill), false));

        AgentSkill loaded = repository.getSkill("resource-skill");
        assertNotNull(loaded);
        // Summary form: resources resolve on demand through originDir, not in memory.
        assertEquals("# Guide\nSome guide content", loaded.getResource("references/guide.md"));
        assertEquals("{\"key\": \"value\"}", loaded.getResource("config.json"));
    }

    @Test
    @DisplayName("Should round trip full metadata through filesystem repository")
    void testSave_RoundTripFullMetadata() {
        Map<String, Object> metadata = new LinkedHashMap<>();
        metadata.put("name", "repo-metadata-skill");
        metadata.put("description", "Repository Metadata Skill");
        metadata.put("homepage", "https://example.com/repo");
        metadata.put(
                "metadata",
                Map.of(
                        "clawdbot",
                        Map.of("emoji", "📋", "requires", Map.of("env", List.of("API_KEY")))));
        AgentSkill skill = new AgentSkill(metadata, "Repository content", null, null);

        assertTrue(repository.save(List.of(skill), false));

        AgentSkill loaded = repository.getSkill("repo-metadata-skill");
        assertEquals(List.copyOf(metadata.keySet()), List.copyOf(loaded.getMetadata().keySet()));
        assertEquals("https://example.com/repo", loaded.getMetadataValue("homepage"));

        @SuppressWarnings("unchecked")
        Map<String, Object> loadedMetadata =
                (Map<String, Object>) loaded.getMetadataValue("metadata");
        @SuppressWarnings("unchecked")
        Map<String, Object> clawdbot = (Map<String, Object>) loadedMetadata.get("clawdbot");

        assertEquals("📋", clawdbot.get("emoji"));
        assertEquals(Map.of("env", List.of("API_KEY")), clawdbot.get("requires"));
    }

    @Test
    @DisplayName("Should fail to save existing skill without force")
    void testSave_ExistingWithoutForce() {
        AgentSkill skill = new AgentSkill("test-skill", "Updated", "Updated content", null);

        assertFalse(repository.save(List.of(skill), false));
    }

    @Test
    @DisplayName("Should overwrite existing skill with force")
    void testSave_ExistingWithForce() throws IOException {
        AgentSkill skill = new AgentSkill("test-skill", "Updated Skill", "Updated content", null);

        assertTrue(repository.save(List.of(skill), true));

        AgentSkill loaded = repository.getSkill("test-skill");
        assertNotNull(loaded);
        assertEquals("Updated Skill", loaded.getDescription());
        assertTrue(skillBody(loaded).contains("Updated content"));
    }

    @Test
    @DisplayName("Should return false when saving null list")
    void testSave_NullList() {
        assertFalse(repository.save(null, false));
    }

    @Test
    @DisplayName("Should return false when saving empty list")
    void testSave_EmptyList() {
        assertFalse(repository.save(Collections.emptyList(), false));
    }

    @Test
    @DisplayName("Should save multiple skills at once")
    void testSave_MultipleSkills() {
        AgentSkill skill1 = new AgentSkill("multi-skill-1", "Multi 1", "Content 1", null);
        AgentSkill skill2 = new AgentSkill("multi-skill-2", "Multi 2", "Content 2", null);

        assertTrue(repository.save(List.of(skill1, skill2), false));
        assertTrue(repository.skillExists("multi-skill-1"));
        assertTrue(repository.skillExists("multi-skill-2"));
    }

    @Test
    @DisplayName("Saved skill source should not contain colon to avoid Windows path issues")
    void testSave_SourceHasNoColon() {
        AgentSkill skill = new AgentSkill("colon-test-skill", "Colon Test", "Content", null);

        assertTrue(repository.save(List.of(skill), false));

        String source = repository.getSource();
        assertFalse(source.contains(":"), "Source '" + source + "' should not contain colon");
        assertTrue(source.startsWith("filesystem-"));
    }

    /** The SKILL.md body read from the summary's {@code originDir} — summaries carry no
     * body in memory, so body assertions go to disk. */
    private static String skillBody(AgentSkill skill) throws IOException {
        return Files.readString(
                skill.getOriginDir().orElseThrow().resolve("SKILL.md"), StandardCharsets.UTF_8);
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

    // ==================== Version Semantics Tests ====================

    @Test
    @DisplayName("Flat layout: null/blank version serves current content, unstamped")
    void testVersionContract_FlatServesCurrentUnstamped() throws IOException {
        // Arrange: a resource file so the disk-fallback surface is exercised too.
        Path note = skillsBaseDir.resolve("test-skill").resolve("notes").resolve("a.txt");
        Files.createDirectories(note.getParent());
        Files.writeString(note, "on disk", StandardCharsets.UTF_8);

        // Act: null and blank versions both serve the current content.
        AgentSkill byNull = repository.getSkill("test-skill", null);
        AgentSkill byBlank = repository.getSkill("test-skill", "  ");

        // Assert: current content, unstamped.
        assertNotNull(byNull);
        assertNotNull(byBlank);
        assertEquals("test-skill", byNull.getName());
        assertNull(byNull.getVersion(), "the flat layout carries no version — unstamped");
        assertNull(byBlank.getVersion());
        assertTrue(
                byNull.getOriginDir().isPresent(),
                "a directory-backed skill carries its own directory as originDir");
        assertTrue(
                byNull.getResources().isEmpty(),
                "the serving form carries no physical file content");
        assertEquals(
                "on disk",
                byNull.getResource("notes/a.txt"),
                "resources resolve from originDir on demand");

        // Every pull is the current bytes: a rewrite is visible on the next pull.
        createSampleSkill("test-skill", "Rewritten", "new body");
        AgentSkill again = repository.getSkill("test-skill", null);
        assertNotNull(again);
        assertTrue(skillBody(again).contains("new body"));
        assertNull(again.getVersion());
    }

    @Test
    @DisplayName("Flat layout: a non-null version has nothing to address — honest null")
    void testVersionContract_FlatNonNullVersionAnswersNull() {
        assertNull(repository.getSkill("test-skill", "1.0.0"));
        assertNull(repository.getSkill("test-skill", "deadbeef"));
    }

    @Test
    @DisplayName("A directory that is neither a flat skill nor a version container answers null")
    void testVersionContract_NeitherLayoutAnswersNull() throws IOException {
        Files.createDirectories(skillsBaseDir.resolve("stray-dir"));
        assertNull(repository.getSkill("stray-dir", null));
        assertNull(repository.getSkill("stray-dir", "1"));
        assertNull(repository.getSkill("no-such-skill", null));
        assertNull(repository.getSkill("../escape", null), "traversal is rejected");
    }

    @Test
    @DisplayName("Versioned container: latest is the lexicographically greatest name")
    void testVersionContract_VersionedContainerLatestIsStringMax() throws IOException {
        createSampleVersion("deploy-skill", "2025", "ancient body");
        createSampleVersion("deploy-skill", "20260101120000", "Deploy v1 body");
        createSampleVersion("deploy-skill", "20260201130000", "Deploy v2 body");

        assertTrue(
                repository.getAllSkillNames().contains("deploy-skill"),
                "a versioned container is discovered by its directory name");

        AgentSkill latest = repository.getSkill("deploy-skill", null);
        assertNotNull(latest);
        assertEquals("20260201130000", latest.getVersion(), "the stamp is the version dir name");
        assertTrue(skillBody(latest).contains("Deploy v2 body"));
        assertEquals(
                skillsBaseDir.resolve("deploy-skill").resolve("20260201130000"),
                latest.getOriginDir().get(),
                "originDir is the materialised version directory");
    }

    @Test
    @DisplayName("Versioned container: a specific version serves exactly that directory")
    void testVersionContract_VersionedContainerExactVersion() throws IOException {
        createSampleVersion("deploy-skill", "20260101120000", "Deploy v1 body");
        createSampleVersion("deploy-skill", "20260201130000", "Deploy v2 body");

        AgentSkill pinned = repository.getSkill("deploy-skill", "20260101120000");
        assertNotNull(pinned);
        assertEquals("20260101120000", pinned.getVersion());
        assertTrue(skillBody(pinned).contains("Deploy v1 body"));

        assertNull(repository.getSkill("deploy-skill", "9999"), "unknown version");
        assertNull(
                repository.getSkill("deploy-skill", "../20260101120000"),
                "the version is matched against names, never used as a path");
    }

    @Test
    @DisplayName("A direct SKILL.md wins over version directories in the same skill folder")
    void testVersionContract_FlatTakesPrecedenceOverVersionDirs() throws IOException {
        createSampleSkill("mixed-skill", "Mixed", "flat body");
        createSampleVersion("mixed-skill", "20260201130000", "versioned body");

        AgentSkill skill = repository.getSkill("mixed-skill", null);
        assertNotNull(skill);
        assertNull(skill.getVersion(), "the direct SKILL.md makes it a flat, unversioned skill");
        assertTrue(skillBody(skill).contains("flat body"));
    }

    @Test
    @DisplayName("Discovery and serving share the directory-name key, not the metadata name")
    void testVersionContract_DiscoveryKeysOnDirectoryName() throws IOException {
        Path dir = skillsBaseDir.resolve("dir-name");
        Files.createDirectories(dir);
        Files.writeString(
                dir.resolve("SKILL.md"),
                "---\nname: meta-name\ndescription: mismatched\n---\nbody\n",
                StandardCharsets.UTF_8);

        List<String> names = repository.getAllSkillNames();
        assertTrue(names.contains("dir-name"), "discovery lists the directory name");
        assertFalse(names.contains("meta-name"), "the metadata name is not a discovery key");

        AgentSkill served = repository.getSkill("dir-name", null);
        assertNotNull(served, "the discovered name must resolve through the serving method");
        assertEquals("meta-name", served.getName(), "the content keeps its declared name");
    }

    @Test
    @DisplayName("An unreadable (non-UTF-8) SKILL.md answers null, never an exception")
    void testVersionContract_UnreadableSkillMdAnswersNull() throws IOException {
        Path dir = skillsBaseDir.resolve("gbk-skill");
        Files.createDirectories(dir);
        Files.write(
                dir.resolve("SKILL.md"),
                "---\nname: gbk-skill\ndescription: d\n---\n正文\n".getBytes("GBK"));

        assertNull(repository.getSkill("gbk-skill", null));
    }

    @Test
    @DisplayName("force-save refuses to flatten a versioned container")
    void testSave_ForceRefusesVersionedContainer() throws IOException {
        createSampleVersion("deploy-skill", "20260101120000", "v1 body");
        createSampleVersion("deploy-skill", "20260201130000", "v2 body");
        AgentSkill flat = new AgentSkill("deploy-skill", "Deploy", "flat body", null);

        assertFalse(repository.save(List.of(flat), true));

        assertTrue(
                Files.isRegularFile(
                        skillsBaseDir
                                .resolve("deploy-skill")
                                .resolve("20260101120000")
                                .resolve("SKILL.md")),
                "every version directory must survive the refused save");
        AgentSkill latest = repository.getSkill("deploy-skill", null);
        assertNotNull(latest);
        assertEquals("20260201130000", latest.getVersion());
    }

    @Test
    @DisplayName("skillExists answers true for a versioned container")
    void testSkillExists_VersionedContainer() throws IOException {
        createSampleVersion("deploy-skill", "20260101120000", "v1 body");
        assertTrue(repository.skillExists("deploy-skill"));
    }

    /** Writes one version directory of {@code skillName} holding a complete SKILL.md. */
    private void createSampleVersion(String skillName, String version, String content)
            throws IOException {
        Path dir = skillsBaseDir.resolve(skillName).resolve(version);
        Files.createDirectories(dir);
        Files.writeString(
                dir.resolve("SKILL.md"),
                "---\n"
                        + "name: "
                        + skillName
                        + "\n"
                        + "description: "
                        + skillName
                        + " "
                        + version
                        + "\n---\n"
                        + content,
                StandardCharsets.UTF_8);
    }
}

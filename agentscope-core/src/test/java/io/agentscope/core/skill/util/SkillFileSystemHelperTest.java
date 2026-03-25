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
package io.agentscope.core.skill.util;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
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
import java.util.Base64;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledOnOs;
import org.junit.jupiter.api.condition.OS;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.ArgumentMatchers;
import org.mockito.MockedStatic;
import org.mockito.Mockito;

/**
 * Unit tests for SkillFileSystemHelper.
 */
@Tag("unit")
@DisplayName("SkillFileSystemHelper Unit Tests")
class SkillFileSystemHelperTest {

    @TempDir Path tempDir;

    private Path skillsBaseDir;

    @BeforeEach
    void setUp() throws IOException {
        skillsBaseDir = tempDir.resolve("skills");
        Files.createDirectories(skillsBaseDir);

        createSampleSkill("test-skill", "Test Skill", "This is a test skill");
        createSampleSkill("another-skill", "Another Skill", "This is another skill");
    }

    @Test
    @DisplayName("Should get all skill names from metadata")
    void testGetAllSkillNames_FromMetadata() throws IOException {
        Path dir = skillsBaseDir.resolve("dir-name");
        Files.createDirectories(dir);
        Files.writeString(
                dir.resolve("SKILL.md"),
                "---\nname: meta-name\ndescription: Meta\n---\nContent",
                StandardCharsets.UTF_8);

        List<String> names = SkillFileSystemHelper.getAllSkillNames(skillsBaseDir);
        assertTrue(names.contains("meta-name"));
        assertFalse(names.contains("dir-name"));
    }

    @Test
    @DisplayName("Should ignore directories without SKILL.md")
    void testGetAllSkillNames_IgnoreInvalidDirs() throws IOException {
        Path invalidDir = skillsBaseDir.resolve("invalid-dir");
        Files.createDirectories(invalidDir);
        Files.writeString(invalidDir.resolve("README.md"), "Not a skill");

        List<String> names = SkillFileSystemHelper.getAllSkillNames(skillsBaseDir);
        assertFalse(names.contains("invalid-dir"));
    }

    @Test
    @DisplayName("Should load skill by metadata name")
    void testLoadSkill_ByMetadataName() {
        AgentSkill skill = SkillFileSystemHelper.loadSkill(skillsBaseDir, "test-skill", "source");
        assertNotNull(skill);
        assertEquals("test-skill", skill.getName());
        assertEquals("source", skill.getSource());
    }

    @Test
    @DisplayName("Should throw when skill not found")
    void testLoadSkill_NotFound() {
        assertThrows(
                IllegalArgumentException.class,
                () -> SkillFileSystemHelper.loadSkill(skillsBaseDir, "missing", "source"));
    }

    @Test
    @DisplayName("Should load all skills")
    void testGetAllSkills() {
        List<AgentSkill> skills = SkillFileSystemHelper.getAllSkills(skillsBaseDir, "source");
        assertNotNull(skills);
        assertEquals(2, skills.size());
    }

    @Test
    @DisplayName("Should save new skill")
    void testSaveSkills_NewSkill() {
        Map<String, String> resources = new HashMap<>();
        resources.put("references/doc.md", "Documentation");
        AgentSkill newSkill = new AgentSkill("new-skill", "New Skill", "Content", resources);

        boolean result = SkillFileSystemHelper.saveSkills(skillsBaseDir, List.of(newSkill), false);
        assertTrue(result);

        AgentSkill loaded = SkillFileSystemHelper.loadSkill(skillsBaseDir, "new-skill", "source");
        assertEquals("new-skill", loaded.getName());
        assertEquals(1, loaded.getResources().size());
    }

    @Test
    @DisplayName("Should return false when saving empty list")
    void testSaveSkills_EmptyList() {
        assertFalse(SkillFileSystemHelper.saveSkills(skillsBaseDir, List.of(), false));
    }

    @Test
    @DisplayName("Should return false when saving null list")
    void testSaveSkills_NullList() {
        assertFalse(SkillFileSystemHelper.saveSkills(skillsBaseDir, null, false));
    }

    @Test
    @DisplayName("Should return false when skill exists and force is false")
    void testSaveSkills_ExistingSkill_ForceDisabled() {
        AgentSkill existingSkill = new AgentSkill("test-skill", "Updated", "Updated content", null);
        boolean result =
                SkillFileSystemHelper.saveSkills(skillsBaseDir, List.of(existingSkill), false);
        assertFalse(result);

        AgentSkill loaded = SkillFileSystemHelper.loadSkill(skillsBaseDir, "test-skill", "source");
        assertEquals("Test Skill", loaded.getDescription());
    }

    @Test
    @DisplayName("Should overwrite when skill exists and force is true")
    void testSaveSkills_ExistingSkill_ForceEnabled() {
        AgentSkill updatedSkill =
                new AgentSkill("test-skill", "Updated Description", "Updated content", null);

        boolean result =
                SkillFileSystemHelper.saveSkills(skillsBaseDir, List.of(updatedSkill), true);
        assertTrue(result);

        AgentSkill loaded = SkillFileSystemHelper.loadSkill(skillsBaseDir, "test-skill", "source");
        assertEquals("Updated Description", loaded.getDescription());
        assertEquals("Updated content", loaded.getSkillContent());
    }

    @Test
    @DisplayName("Should delete existing skill")
    void testDeleteSkill_Existing() {
        assertTrue(SkillFileSystemHelper.skillExists(skillsBaseDir, "test-skill"));
        assertTrue(SkillFileSystemHelper.deleteSkill(skillsBaseDir, "test-skill"));
        assertFalse(SkillFileSystemHelper.skillExists(skillsBaseDir, "test-skill"));
    }

    @Test
    @DisplayName("Should return false when deleting non-existent skill")
    void testDeleteSkill_NotFound() {
        assertFalse(SkillFileSystemHelper.deleteSkill(skillsBaseDir, "missing"));
    }

    @Test
    @DisplayName("Should return false for null or empty skill names in exists")
    void testSkillExists_InvalidName() {
        assertFalse(SkillFileSystemHelper.skillExists(skillsBaseDir, null));
        assertFalse(SkillFileSystemHelper.skillExists(skillsBaseDir, ""));
    }

    @Test
    @DisplayName("Should validate and resolve path")
    void testValidateAndResolvePath() {
        Path resolved = SkillFileSystemHelper.validateAndResolvePath(skillsBaseDir, "test-skill");
        assertTrue(resolved.startsWith(skillsBaseDir));
    }

    @Test
    @DisplayName("Should prevent path traversal in validateAndResolvePath")
    void testValidateAndResolvePath_PathTraversal() {
        assertThrows(
                IllegalArgumentException.class,
                () -> SkillFileSystemHelper.validateAndResolvePath(skillsBaseDir, "../outside"));
    }

    @Test
    @DisplayName("Should delete directory recursively")
    void testDeleteDirectory() throws IOException {
        Path dir = tempDir.resolve("to-delete");
        Files.createDirectories(dir.resolve("nested"));
        Files.writeString(dir.resolve("nested/file.txt"), "content");

        SkillFileSystemHelper.deleteDirectory(dir);
        assertFalse(Files.exists(dir));
    }

    @Test
    @DisplayName("Should encode binary resources as Base64 on load")
    void testLoadResources_EncodesBinaryAsBase64() throws IOException {
        Path skillDir = skillsBaseDir.resolve("binary-skill");
        Files.createDirectories(skillDir.resolve("assets"));
        Files.writeString(
                skillDir.resolve("SKILL.md"),
                "---\nname: binary-skill\ndescription: Binary\n---\nContent",
                StandardCharsets.UTF_8);

        byte[] original = new byte[] {0x00, 0x01, (byte) 0xFF};
        Path binaryFile = skillDir.resolve("assets/data.bin");
        Files.write(binaryFile, original);

        AgentSkill skill = SkillFileSystemHelper.loadSkill(skillsBaseDir, "binary-skill", "src");
        String encoded = skill.getResources().get("assets/data.bin");

        assertNotNull(encoded);
        assertTrue(encoded.startsWith("base64:"));

        String base64 = encoded.substring("base64:".length());
        byte[] decoded = Base64.getDecoder().decode(base64);
        assertArrayEquals(original, decoded);
    }

    @Test
    @DisplayName("Should decode Base64 resources when saving")
    void testSaveSkills_DecodesBase64ToBinary() throws IOException {
        byte[] original = new byte[] {0x10, 0x20, (byte) 0x80, (byte) 0xFF};
        String base64 = Base64.getEncoder().encodeToString(original);

        Map<String, String> resources =
                Map.of("bin/data.bin", "base64:" + base64, "readme.txt", "plain text");
        AgentSkill newSkill = new AgentSkill("binary-save", "Binary Save", "Content", resources);

        boolean result = SkillFileSystemHelper.saveSkills(skillsBaseDir, List.of(newSkill), false);
        assertTrue(result);

        Path savedBinary = skillsBaseDir.resolve("binary-save/bin/data.bin");
        byte[] savedBytes = Files.readAllBytes(savedBinary);
        assertArrayEquals(original, savedBytes);

        Path savedText = skillsBaseDir.resolve("binary-save/readme.txt");
        assertEquals("plain text", Files.readString(savedText, StandardCharsets.UTF_8));
    }

    @Test
    @DisplayName("Should load normal readable files")
    void shouldLoadNormalResourceFiles() throws IOException {
        createSampleSkill("normal-skill", "Test Normal", "Test content");
        Path skillDir = skillsBaseDir.resolve("normal-skill");
        Path normalFile = skillDir.resolve("normal_resource.txt");
        Files.writeString(normalFile, "normal", StandardCharsets.UTF_8);

        AgentSkill skill =
                SkillFileSystemHelper.loadSkill(skillsBaseDir, "normal-skill", "test-source");

        assertNotNull(skill);
        assertTrue(
                skill.getResources().containsKey("normal_resource.txt"),
                "Normal file should be loaded");
    }

    @Test
    @DisplayName("Should filter out unreadable files")
    void shouldFilterUnreadableFiles() throws IOException {
        createSampleSkill("unreadable-skill", "Test Unreadable", "Test content");
        Path skillDir = skillsBaseDir.resolve("unreadable-skill");
        Path unreadableFile = skillDir.resolve("secret.txt");
        Files.writeString(unreadableFile, "secret", StandardCharsets.UTF_8);

        try (MockedStatic<Files> mockedFiles =
                Mockito.mockStatic(Files.class, Mockito.CALLS_REAL_METHODS)) {
            mockedFiles
                    .when(() -> Files.isReadable(ArgumentMatchers.any(Path.class)))
                    .thenAnswer(
                            invocation -> {
                                Path p = invocation.getArgument(0);
                                if (p.getFileName().toString().equals("secret.txt")) return false;
                                return invocation.callRealMethod();
                            });

            AgentSkill skill =
                    SkillFileSystemHelper.loadSkill(
                            skillsBaseDir, "unreadable-skill", "test-source");
            assertFalse(
                    skill.getResources().containsKey("secret.txt"),
                    "Unreadable file should be filtered out");
        }
    }

    @Test
    @DisplayName("Should explicitly filter out dot-files and files within dot-directories")
    void shouldFilterDotFilesAndDirectories() throws IOException {
        createSampleSkill("dot-skill", "Test Dot Files", "Test content");
        Path skillDir = skillsBaseDir.resolve("dot-skill");

        Path dotFile = skillDir.resolve(".DS_Store");
        Files.writeString(dotFile, "garbage", StandardCharsets.UTF_8);

        Path dotDir = skillDir.resolve(".hidden_dir");
        Files.createDirectories(dotDir);
        Path dotDirFile = dotDir.resolve("config.txt");
        Files.writeString(dotDirFile, "hidden config", StandardCharsets.UTF_8);

        AgentSkill skill =
                SkillFileSystemHelper.loadSkill(skillsBaseDir, "dot-skill", "test-source");

        assertFalse(skill.getResources().containsKey(".DS_Store"), "Dot file should be filtered");
        assertFalse(
                skill.getResources().containsKey(".hidden_dir/config.txt"),
                "File inside dot directory should be filtered");
    }

    @Test
    @DisplayName("Should default to loading the file if isHidden() throws IOException")
    void shouldHandleIOExceptionDuringAttributeCheck() throws IOException {
        createSampleSkill("io-exception-skill", "Test IO Exception", "Test content");
        Path skillDir = skillsBaseDir.resolve("io-exception-skill");
        Path triggerFile = skillDir.resolve("error_trigger.txt");
        Files.writeString(triggerFile, "trigger", StandardCharsets.UTF_8);

        try (MockedStatic<Files> mockedFiles =
                Mockito.mockStatic(Files.class, Mockito.CALLS_REAL_METHODS)) {
            mockedFiles
                    .when(() -> Files.isHidden(ArgumentMatchers.any(Path.class)))
                    .thenAnswer(
                            invocation -> {
                                Path p = invocation.getArgument(0);
                                if (p.getFileName().toString().equals("error_trigger.txt")) {
                                    throw new IOException("Simulated IO Exception for testing");
                                }
                                return invocation.callRealMethod();
                            });

            AgentSkill skill =
                    SkillFileSystemHelper.loadSkill(
                            skillsBaseDir, "io-exception-skill", "test-source");
            assertTrue(
                    skill.getResources().containsKey("error_trigger.txt"),
                    "File causing IOException should default to being loaded");
        }
    }

    @Test
    @EnabledOnOs(OS.WINDOWS)
    @DisplayName("Should filter OS-level hidden files on Windows")
    void shouldFilterOsHiddenFilesOnWindows() throws IOException {
        createSampleSkill("os-hidden-skill", "Test OS Hidden", "Test content");
        Path skillDir = skillsBaseDir.resolve("os-hidden-skill");

        Path osHiddenFile = skillDir.resolve("os_hidden_file.txt");
        Files.writeString(osHiddenFile, "hidden data", StandardCharsets.UTF_8);

        try {
            Files.setAttribute(osHiddenFile, "dos:hidden", true);
        } catch (Exception ignored) {
        }

        AgentSkill skill =
                SkillFileSystemHelper.loadSkill(skillsBaseDir, "os-hidden-skill", "test-source");
        assertFalse(
                skill.getResources().containsKey("os_hidden_file.txt"),
                "OS hidden file should be filtered out on Windows");
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

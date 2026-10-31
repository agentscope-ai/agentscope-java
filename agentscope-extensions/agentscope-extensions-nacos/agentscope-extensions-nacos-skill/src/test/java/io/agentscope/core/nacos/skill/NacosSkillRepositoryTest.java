/*
 * Copyright 2024-2026 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package io.agentscope.core.nacos.skill;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.alibaba.nacos.api.ai.AiService;
import com.alibaba.nacos.api.exception.NacosException;
import io.agentscope.core.skill.AgentSkill;
import io.agentscope.core.skill.SkillBox;
import io.agentscope.core.skill.repository.AgentSkillRepositoryInfo;
import io.agentscope.core.tool.Toolkit;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Properties;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

/**
 * Unit tests for {@link NacosSkillRepository}.
 */
@ExtendWith(MockitoExtension.class)
class NacosSkillRepositoryTest {

    @Mock private AiService aiService;

    private NacosSkillRepository repository;

    @BeforeEach
    void setUp() {
        repository = new NacosSkillRepository(aiService, "public");
    }

    @Test
    @DisplayName("Should throw when AiService is null")
    void testConstructorWithNullAiService() {
        IllegalArgumentException e =
                assertThrows(
                        IllegalArgumentException.class,
                        () -> new NacosSkillRepository(null, "public"));
        assertEquals("AiService cannot be null", e.getMessage());
    }

    @Test
    @DisplayName("Should return null when getSkill is called with null name")
    void testGetSkillWithNullName() {
        assertNull(repository.getSkill(null));
    }

    @Test
    @DisplayName("Should return null when getSkill is called with blank name")
    void testGetSkillWithBlankName() {
        assertNull(repository.getSkill("   "));
    }

    @Test
    @DisplayName("Should return null when getSkill is called with empty name")
    void testGetSkillWithEmptyName() {
        assertNull(repository.getSkill(""));
    }

    @Test
    @DisplayName("Should return null when skill not found (downloadSkillZip returns null)")
    void testGetSkillWhenDownloadSkillZipReturnsNull() throws NacosException {
        when(aiService.downloadSkillZip("missing-skill")).thenReturn(null);

        assertNull(repository.getSkill("missing-skill"));
    }

    @Test
    @DisplayName("Should return null when NacosException is NOT_FOUND")
    void testGetSkillWhenNacosExceptionNotFound() throws NacosException {
        when(aiService.downloadSkillZip("missing-skill"))
                .thenThrow(new NacosException(NacosException.NOT_FOUND, "not found"));

        assertNull(repository.getSkill("missing-skill"));
    }

    @Test
    @DisplayName("Should return null for other NacosException")
    void testGetSkillWhenOtherNacosException() throws NacosException {
        when(aiService.downloadSkillZip("my-skill"))
                .thenThrow(new NacosException(500, "server error"));

        assertNull(repository.getSkill("my-skill"));
    }

    @Test
    @DisplayName("Should return AgentSkill when skill is found")
    void testGetSkillSuccess() throws NacosException, IOException {
        when(aiService.downloadSkillZip("test-skill"))
                .thenReturn(
                        createSkillZip(
                                "test-skill",
                                "A test skill",
                                "Do something",
                                "data.txt",
                                "sample resource"));

        AgentSkill result = repository.getSkill("test-skill");

        assertNotNull(result);
        assertEquals("test-skill", result.getName());
        assertEquals("A test skill", result.getDescription());
        assertEquals("Do something", result.getSkillContent());
        assertEquals("nacos@public", result.getSource());
    }

    @Test
    @DisplayName("Should keep binary resource entry when loading from zip")
    void testGetSkillWithBinaryResource() throws NacosException, IOException {
        byte[] pngLikeBytes =
                new byte[] {
                    (byte) 0x89, 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A, 0x00, (byte) 0xFF
                };
        when(aiService.downloadSkillZip("binary-skill"))
                .thenReturn(
                        createSkillZip(
                                "binary-skill",
                                "Binary skill",
                                "Use binary assets",
                                "assets/logo.png",
                                pngLikeBytes));

        AgentSkill result = repository.getSkill("binary-skill");
        String resource = result.getResource("assets/logo.png");

        assertNotNull(resource);
        assertEquals(new String(pngLikeBytes, StandardCharsets.UTF_8), resource);
    }

    @Test
    @DisplayName("Should trim skill name when calling downloadSkillZip")
    void testGetSkillTrimsName() throws NacosException, IOException {
        when(aiService.downloadSkillZip("trimmed"))
                .thenReturn(createSkillZip("trimmed", "Desc", "Content", null, (String) null));

        repository.getSkill("  trimmed  ");

        verify(aiService).downloadSkillZip("trimmed");
    }

    @Test
    @DisplayName(
            "Should use downloadSkillZipByVersion when version is set in application Properties")
    void testGetSkillUsesByVersionWhenConfigured(@TempDir Path cacheDir)
            throws NacosException, IOException {
        Properties props = new Properties();
        props.setProperty(NacosSkillRepository.SKILL_VERSION_PATH, "v1");
        NacosSkillRepository repo =
                new NacosSkillRepository(aiService, "public", props, null, cacheDir);
        when(aiService.downloadSkillZipByVersion("my-skill", "v1"))
                .thenReturn(createSkillZip("my-skill", "Desc", "Content", null, (String) null));

        AgentSkill skill = repo.getSkill("my-skill");

        assertNotNull(skill);
        assertEquals("v1", skill.getVersion());
        verify(aiService).downloadSkillZipByVersion("my-skill", "v1");
        verify(aiService, never()).downloadSkillZipByLabel(anyString(), anyString());
        verify(aiService, never()).downloadSkillZip(anyString());
    }

    @Test
    @DisplayName("Two clusters sharing one cache root never swap content")
    void testTwoClustersShareNoCacheEntries(@TempDir Path cacheDir)
            throws NacosException, IOException {
        Properties propsA = new Properties();
        propsA.setProperty("serverAddr", "cluster-a:8848");
        propsA.setProperty(NacosSkillRepository.SKILL_VERSION_PATH, "v1");
        Properties propsB = new Properties();
        propsB.setProperty("serverAddr", "cluster-b:8848");
        propsB.setProperty(NacosSkillRepository.SKILL_VERSION_PATH, "v1");
        AiService clusterB = org.mockito.Mockito.mock(AiService.class);

        when(aiService.downloadSkillZipByVersion("my-skill", "v1"))
                .thenReturn(
                        createSkillZip("my-skill", "Desc", "Cluster A body", null, (String) null));
        when(clusterB.downloadSkillZipByVersion("my-skill", "v1"))
                .thenReturn(
                        createSkillZip("my-skill", "Desc", "Cluster B body", null, (String) null));

        NacosSkillRepository repoA =
                new NacosSkillRepository(aiService, "public", propsA, null, cacheDir);
        NacosSkillRepository repoB =
                new NacosSkillRepository(clusterB, "public", propsB, null, cacheDir);

        // Same namespace, same skill, same version key, one shared cache root — only the
        // server address distinguishes the clusters, so each must serve its own bytes.
        String manifestA =
                Files.readString(
                        repoA.getSkill("my-skill").getOriginDir().get().resolve("SKILL.md"));
        String manifestB =
                Files.readString(
                        repoB.getSkill("my-skill").getOriginDir().get().resolve("SKILL.md"));
        assertTrue(manifestA.contains("Cluster A body"), "cluster A serves its own content");
        assertTrue(manifestB.contains("Cluster B body"), "cluster B serves its own content");
    }

    @Test
    @DisplayName(
            "Should use downloadSkillZipByLabel when only label is set in application Properties")
    void testGetSkillUsesByLabelWhenConfigured() throws NacosException, IOException {
        Properties props = new Properties();
        props.setProperty(NacosSkillRepository.SKILL_LABEL_PATH, "stable");
        NacosSkillRepository repo = new NacosSkillRepository(aiService, "public", props);
        when(aiService.downloadSkillZipByLabel("my-skill", "stable"))
                .thenReturn(createSkillZip("my-skill", "Desc", "Content", null, (String) null));

        AgentSkill skill = repo.getSkill("my-skill");

        assertNotNull(skill);
        verify(aiService).downloadSkillZipByLabel("my-skill", "stable");
        verify(aiService, never()).downloadSkillZip(anyString());
    }

    @Test
    @DisplayName(
            "Should prefer downloadSkillZipByVersion when both version and label are configured")
    void testGetSkillVersionTakesPrecedenceOverLabel(@TempDir Path cacheDir)
            throws NacosException, IOException {
        Properties props = new Properties();
        props.setProperty(NacosSkillRepository.SKILL_VERSION_PATH, "2.0");
        props.setProperty(NacosSkillRepository.SKILL_LABEL_PATH, "stable");
        NacosSkillRepository repo =
                new NacosSkillRepository(aiService, "public", props, null, cacheDir);
        when(aiService.downloadSkillZipByVersion("s", "2.0"))
                .thenReturn(createSkillZip("s", "D", "C", null, (String) null));

        repo.getSkill("s");

        verify(aiService).downloadSkillZipByVersion("s", "2.0");
        verify(aiService, never()).downloadSkillZipByLabel(anyString(), anyString());
    }

    @Test
    @DisplayName("Should use JVM system property for version when Properties are absent")
    void testVersionResolvedFromJvmProperty(@TempDir Path cacheDir)
            throws NacosException, IOException {
        String key = NacosSkillRepository.SKILL_VERSION_PATH;
        String previous = System.getProperty(key);
        try {
            System.setProperty(key, "from-jvm");
            NacosSkillRepository repo =
                    new NacosSkillRepository(aiService, "public", null, null, cacheDir);
            when(aiService.downloadSkillZipByVersion("n", "from-jvm"))
                    .thenReturn(createSkillZip("n", "D", "C", null, (String) null));

            repo.getSkill("n");

            verify(aiService).downloadSkillZipByVersion("n", "from-jvm");
        } finally {
            if (previous == null) {
                System.clearProperty(key);
            } else {
                System.setProperty(key, previous);
            }
        }
    }

    @Test
    @DisplayName("Should prefer application Properties over JVM system property for version")
    void testApplicationVersionOverridesJvm(@TempDir Path cacheDir)
            throws NacosException, IOException {
        String key = NacosSkillRepository.SKILL_VERSION_PATH;
        String previous = System.getProperty(key);
        try {
            System.setProperty(key, "jvm-ver");
            Properties props = new Properties();
            props.setProperty(key, "app-ver");
            NacosSkillRepository repo =
                    new NacosSkillRepository(aiService, "public", props, null, cacheDir);
            when(aiService.downloadSkillZipByVersion("n", "app-ver"))
                    .thenReturn(createSkillZip("n", "D", "C", null, (String) null));

            repo.getSkill("n");

            verify(aiService).downloadSkillZipByVersion("n", "app-ver");
        } finally {
            if (previous == null) {
                System.clearProperty(key);
            } else {
                System.setProperty(key, previous);
            }
        }
    }

    @Test
    @DisplayName("Should read version from application Properties before JVM system property")
    void testApplicationPropertiesVersionBeforeJvm(@TempDir Path cacheDir)
            throws NacosException, IOException {
        String key = NacosSkillRepository.SKILL_VERSION_PATH;
        String previous = System.getProperty(key);
        try {
            System.setProperty(key, "jvm-should-lose");
            Properties props = new Properties();
            props.setProperty(key, "from-properties-file");
            NacosSkillRepository repo =
                    new NacosSkillRepository(aiService, "public", props, null, cacheDir);
            when(aiService.downloadSkillZipByVersion("n", "from-properties-file"))
                    .thenReturn(createSkillZip("n", "D", "C", null, (String) null));

            repo.getSkill("n");

            verify(aiService).downloadSkillZipByVersion("n", "from-properties-file");
        } finally {
            if (previous == null) {
                System.clearProperty(key);
            } else {
                System.setProperty(key, previous);
            }
        }
    }

    @Test
    @DisplayName("Should read label from application Properties")
    void testLabelFromApplicationProperties() throws NacosException, IOException {
        Properties props = new Properties();
        props.setProperty(NacosSkillRepository.SKILL_LABEL_PATH, "prod");
        NacosSkillRepository repo = new NacosSkillRepository(aiService, "public", props);
        when(aiService.downloadSkillZipByLabel("x", "prod"))
                .thenReturn(createSkillZip("x", "D", "C", null, (String) null));

        repo.getSkill("x");

        verify(aiService).downloadSkillZipByLabel("x", "prod");
    }

    @Test
    @DisplayName("Should return false for skillExists with null")
    void testSkillExistsWithNull() {
        assertFalse(repository.skillExists(null));
    }

    @Test
    @DisplayName("Should return false for skillExists with blank")
    void testSkillExistsWithBlank() {
        assertFalse(repository.skillExists("   "));
    }

    @Test
    @DisplayName("Should return true when skill exists")
    void testSkillExistsWhenFound() throws NacosException {
        when(aiService.downloadSkillZip("exists")).thenReturn(new byte[] {1});

        assertTrue(repository.skillExists("exists"));
    }

    @Test
    @DisplayName("Should return false when skill not found")
    void testSkillExistsWhenNotFound() throws NacosException {
        when(aiService.downloadSkillZip("missing")).thenReturn(null);

        assertFalse(repository.skillExists("missing"));
    }

    @Test
    @DisplayName("Should return false when NacosException NOT_FOUND")
    void testSkillExistsWhenNacosNotFound() throws NacosException {
        when(aiService.downloadSkillZip("missing"))
                .thenThrow(new NacosException(NacosException.NOT_FOUND, "not found"));

        assertFalse(repository.skillExists("missing"));
    }

    @Test
    @DisplayName("Should return correct repository info")
    void testGetRepositoryInfo() {
        AgentSkillRepositoryInfo info = repository.getRepositoryInfo();

        assertNotNull(info);
        assertEquals("nacos", info.getType());
        assertEquals("namespace:public", info.getLocation());
        assertFalse(info.isWritable());
    }

    @Test
    @DisplayName("Should return correct source")
    void testGetSource() {
        assertEquals("nacos@public", repository.getSource());
    }

    @Test
    @DisplayName("Should encode namespace characters without delimiter ambiguity")
    void testSourceEncodesNamespaceCharacters() {
        NacosSkillRepository hyphenRepo = new NacosSkillRepository(aiService, "team-a");
        NacosSkillRepository underscoreRepo = new NacosSkillRepository(aiService, "team_a");

        assertEquals("nacos@team-a", hyphenRepo.getSource());
        assertEquals("nacos@team_a", underscoreRepo.getSource());
        assertNotEquals(hyphenRepo.getSource(), underscoreRepo.getSource());
    }

    @Test
    @DisplayName("Should use default namespace when namespaceId is blank")
    void testDefaultNamespace() {
        try (NacosSkillRepository repo = new NacosSkillRepository(aiService, null)) {
            assertEquals("nacos@public", repo.getSource());
            assertEquals("namespace:public", repo.getRepositoryInfo().getLocation());
        }
    }

    @Test
    @DisplayName("Should upload skill resources using a Windows-safe skill ID")
    void testUploadSkillResourcesWithWindowsSafeSkillId(@TempDir Path tempDir)
            throws NacosException, IOException {
        when(aiService.downloadSkillZip("resource-skill"))
                .thenReturn(
                        createSkillZip(
                                "resource-skill",
                                "Resource skill",
                                "Use the script",
                                "scripts/run.sh",
                                "echo hello"));
        AgentSkill skill = repository.getSkill("resource-skill");
        SkillBox skillBox = new SkillBox(new Toolkit());
        skillBox.setWorkDir(tempDir);
        skillBox.registerSkill(skill);

        assertDoesNotThrow(skillBox::uploadSkillFiles);

        Path uploadedResource =
                tempDir.resolve("skills").resolve(skill.getSkillId()).resolve("scripts/run.sh");
        assertTrue(Files.isRegularFile(uploadedResource));
        assertEquals("echo hello", Files.readString(uploadedResource));
    }

    @Test
    @DisplayName("Should always return false for isWriteable")
    void testIsWriteable() {
        assertFalse(repository.isWriteable());
        repository.setWriteable(true);
        assertFalse(repository.isWriteable());
    }

    @Test
    @DisplayName("Should return empty list for getAllSkillNames")
    void testGetAllSkillNames() {
        assertTrue(repository.getAllSkillNames().isEmpty());
    }

    @Test
    @DisplayName("Should return empty list for getAllSkills")
    void testGetAllSkills() {
        assertTrue(repository.getAllSkills().isEmpty());
    }

    @Test
    @DisplayName("Should return false for save")
    void testSave() {
        assertFalse(repository.save(List.of(), false));
    }

    @Test
    @DisplayName("Should return false for delete")
    void testDelete() {
        assertFalse(repository.delete("any-skill"));
    }

    @Test
    @DisplayName("null knownSkillNames is treated the same as empty list")
    void testNullKnownSkillNamesTreatedAsEmpty() {
        NacosSkillRepository repo = new NacosSkillRepository(aiService, "public", null, null);

        assertTrue(repo.getAllSkillNames().isEmpty());
        assertTrue(repo.getAllSkills().isEmpty());
    }

    @Test
    @DisplayName("getAllSkillNames returns knownSkillNames when configured")
    void testGetAllSkillNamesWithKnownNames() {
        NacosSkillRepository repo =
                new NacosSkillRepository(aiService, "public", null, List.of("skill-a", "skill-b"));

        List<String> names = repo.getAllSkillNames();

        assertEquals(List.of("skill-a", "skill-b"), names);
    }

    @Test
    @DisplayName("getAllSkills fetches each knownSkillName and returns loaded skills")
    void testGetAllSkillsWithKnownNames() throws Exception {
        NacosSkillRepository repo =
                new NacosSkillRepository(aiService, "public", null, List.of("skill-a", "skill-b"));
        when(aiService.downloadSkillZip("skill-a"))
                .thenReturn(createSkillZip("skill-a", "Desc A", "Content A", null, (String) null));
        when(aiService.downloadSkillZip("skill-b"))
                .thenReturn(createSkillZip("skill-b", "Desc B", "Content B", null, (String) null));

        List<AgentSkill> skills = repo.getAllSkills();

        assertEquals(2, skills.size());
        assertEquals("skill-a", skills.get(0).getName());
        assertEquals("skill-b", skills.get(1).getName());
    }

    @Test
    @DisplayName("getAllSkills skips skills that fail to load and returns the rest")
    void testGetAllSkillsSkipsFailedSkill() throws Exception {
        NacosSkillRepository repo =
                new NacosSkillRepository(aiService, "public", null, List.of("good", "bad"));
        when(aiService.downloadSkillZip("good"))
                .thenReturn(createSkillZip("good", "Desc", "Content", null, (String) null));
        when(aiService.downloadSkillZip("bad"))
                .thenThrow(new NacosException(NacosException.NOT_FOUND, "not found"));

        List<AgentSkill> skills = repo.getAllSkills();

        assertEquals(1, skills.size());
        assertEquals("good", skills.get(0).getName());
    }

    private static byte[] createSkillZip(
            String name,
            String description,
            String skillContent,
            String resourcePath,
            String resourceContent)
            throws IOException {
        return createSkillZip(
                name,
                description,
                skillContent,
                resourcePath,
                resourceContent == null ? null : resourceContent.getBytes());
    }

    private static byte[] createSkillZip(
            String name,
            String description,
            String skillContent,
            String resourcePath,
            byte[] resourceBytes)
            throws IOException {
        String root = "skill-package";
        String skillMd =
                "---\n"
                        + "name: "
                        + name
                        + "\n"
                        + "description: "
                        + description
                        + "\n"
                        + "---\n"
                        + skillContent;
        try (ByteArrayOutputStream baos = new ByteArrayOutputStream();
                ZipOutputStream zos = new ZipOutputStream(baos)) {
            zos.putNextEntry(new ZipEntry(root + "/SKILL.md"));
            zos.write(skillMd.getBytes());
            zos.closeEntry();
            if (resourcePath != null && resourceBytes != null) {
                zos.putNextEntry(new ZipEntry(root + "/" + resourcePath));
                zos.write(resourceBytes);
                zos.closeEntry();
            }
            zos.finish();
            return baos.toByteArray();
        }
    }
}

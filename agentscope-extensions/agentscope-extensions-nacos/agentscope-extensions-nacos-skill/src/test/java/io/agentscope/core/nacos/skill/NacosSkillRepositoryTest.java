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

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.alibaba.nacos.api.ai.AiService;
import com.alibaba.nacos.api.exception.NacosException;
import io.agentscope.core.skill.AgentSkill;
import io.agentscope.core.skill.repository.AgentSkillRepositoryInfo;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Properties;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
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
    @DisplayName("Should throw when getSkill is called with null name")
    void testGetSkillWithNullName() {
        IllegalArgumentException e =
                assertThrows(IllegalArgumentException.class, () -> repository.getSkill(null));
        assertEquals("Skill name cannot be null or empty", e.getMessage());
    }

    @Test
    @DisplayName("Should throw when getSkill is called with blank name")
    void testGetSkillWithBlankName() {
        IllegalArgumentException e =
                assertThrows(IllegalArgumentException.class, () -> repository.getSkill("   "));
        assertEquals("Skill name cannot be null or empty", e.getMessage());
    }

    @Test
    @DisplayName("Should throw when getSkill is called with empty name")
    void testGetSkillWithEmptyName() {
        IllegalArgumentException e =
                assertThrows(IllegalArgumentException.class, () -> repository.getSkill(""));
        assertEquals("Skill name cannot be null or empty", e.getMessage());
    }

    @Test
    @DisplayName("Should throw when skill not found (downloadSkillZip returns null)")
    void testGetSkillWhenDownloadSkillZipReturnsNull() throws NacosException {
        when(aiService.downloadSkillZip("missing-skill")).thenReturn(null);

        IllegalArgumentException e =
                assertThrows(
                        IllegalArgumentException.class, () -> repository.getSkill("missing-skill"));
        assertEquals("Skill not found: missing-skill", e.getMessage());
    }

    @Test
    @DisplayName("Should throw IllegalArgumentException when NacosException is NOT_FOUND")
    void testGetSkillWhenNacosExceptionNotFound() throws NacosException {
        NacosException nacosEx = new NacosException(NacosException.NOT_FOUND, "not found");
        when(aiService.downloadSkillZip("missing-skill")).thenThrow(nacosEx);

        IllegalArgumentException e =
                assertThrows(
                        IllegalArgumentException.class, () -> repository.getSkill("missing-skill"));
        assertEquals("Skill not found: missing-skill", e.getMessage());
        assertEquals(nacosEx, e.getCause());
    }

    @Test
    @DisplayName("Should throw RuntimeException for other NacosException")
    void testGetSkillWhenOtherNacosException() throws NacosException {
        NacosException nacosEx = new NacosException(500, "server error");
        when(aiService.downloadSkillZip("my-skill")).thenThrow(nacosEx);

        RuntimeException e =
                assertThrows(RuntimeException.class, () -> repository.getSkill("my-skill"));
        assertEquals("Failed to load skill from Nacos: my-skill", e.getMessage());
        assertEquals(nacosEx, e.getCause());
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
        assertEquals("nacos:public", result.getSource());
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
    void testGetSkillUsesByVersionWhenConfigured() throws NacosException, IOException {
        Properties props = new Properties();
        props.setProperty(NacosSkillRepository.SKILL_VERSION_PATH, "v1");
        NacosSkillRepository repo = new NacosSkillRepository(aiService, "public", props);
        when(aiService.downloadSkillZipByVersion("my-skill", "v1"))
                .thenReturn(createSkillZip("my-skill", "Desc", "Content", null, (String) null));

        AgentSkill skill = repo.getSkill("my-skill");

        assertNotNull(skill);
        verify(aiService).downloadSkillZipByVersion("my-skill", "v1");
        verify(aiService, never()).downloadSkillZipByLabel(anyString(), anyString());
        verify(aiService, never()).downloadSkillZip(anyString());
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
    void testGetSkillVersionTakesPrecedenceOverLabel() throws NacosException, IOException {
        Properties props = new Properties();
        props.setProperty(NacosSkillRepository.SKILL_VERSION_PATH, "2.0");
        props.setProperty(NacosSkillRepository.SKILL_LABEL_PATH, "stable");
        NacosSkillRepository repo = new NacosSkillRepository(aiService, "public", props);
        when(aiService.downloadSkillZipByVersion("s", "2.0"))
                .thenReturn(createSkillZip("s", "D", "C", null, (String) null));

        repo.getSkill("s");

        verify(aiService).downloadSkillZipByVersion("s", "2.0");
        verify(aiService, never()).downloadSkillZipByLabel(anyString(), anyString());
    }

    @Test
    @DisplayName("Should use JVM system property for version when Properties are absent")
    void testVersionResolvedFromJvmProperty() throws NacosException, IOException {
        String key = NacosSkillRepository.SKILL_VERSION_PATH;
        String previous = System.getProperty(key);
        try {
            System.setProperty(key, "from-jvm");
            NacosSkillRepository repo = new NacosSkillRepository(aiService, "public");
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
    void testApplicationVersionOverridesJvm() throws NacosException, IOException {
        String key = NacosSkillRepository.SKILL_VERSION_PATH;
        String previous = System.getProperty(key);
        try {
            System.setProperty(key, "jvm-ver");
            Properties props = new Properties();
            props.setProperty(key, "app-ver");
            NacosSkillRepository repo = new NacosSkillRepository(aiService, "public", props);
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
    void testApplicationPropertiesVersionBeforeJvm() throws NacosException, IOException {
        String key = NacosSkillRepository.SKILL_VERSION_PATH;
        String previous = System.getProperty(key);
        try {
            System.setProperty(key, "jvm-should-lose");
            Properties props = new Properties();
            props.setProperty(key, "from-properties-file");
            NacosSkillRepository repo = new NacosSkillRepository(aiService, "public", props);
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
        assertEquals("nacos:public", repository.getSource());
    }

    @Test
    @DisplayName("Should use default namespace when namespaceId is blank")
    void testDefaultNamespace() {
        try (NacosSkillRepository repo = new NacosSkillRepository(aiService, null)) {
            assertEquals("nacos:public", repo.getSource());
            assertEquals("namespace:public", repo.getRepositoryInfo().getLocation());
        }
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

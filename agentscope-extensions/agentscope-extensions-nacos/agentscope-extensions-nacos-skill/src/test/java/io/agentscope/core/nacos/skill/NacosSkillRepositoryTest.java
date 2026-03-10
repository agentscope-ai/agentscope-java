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
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.alibaba.nacos.api.ai.AiService;
import com.alibaba.nacos.api.ai.model.skills.Skill;
import com.alibaba.nacos.api.exception.NacosException;
import io.agentscope.core.skill.AgentSkill;
import io.agentscope.core.skill.repository.AgentSkillRepositoryInfo;
import java.util.List;
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
    @DisplayName("Should throw when skill not found (loadSkill returns null)")
    void testGetSkillWhenLoadSkillReturnsNull() throws NacosException {
        when(aiService.loadSkill("missing-skill")).thenReturn(null);

        IllegalArgumentException e =
                assertThrows(
                        IllegalArgumentException.class,
                        () -> repository.getSkill("missing-skill"));
        assertEquals("Skill not found: missing-skill", e.getMessage());
    }

    @Test
    @DisplayName("Should throw IllegalArgumentException when NacosException is NOT_FOUND")
    void testGetSkillWhenNacosExceptionNotFound() throws NacosException {
        NacosException nacosEx = new NacosException(NacosException.NOT_FOUND, "not found");
        when(aiService.loadSkill("missing-skill")).thenThrow(nacosEx);

        IllegalArgumentException e =
                assertThrows(
                        IllegalArgumentException.class,
                        () -> repository.getSkill("missing-skill"));
        assertEquals("Skill not found: missing-skill", e.getMessage());
        assertEquals(nacosEx, e.getCause());
    }

    @Test
    @DisplayName("Should throw RuntimeException for other NacosException")
    void testGetSkillWhenOtherNacosException() throws NacosException {
        NacosException nacosEx = new NacosException(500, "server error");
        when(aiService.loadSkill("my-skill")).thenThrow(nacosEx);

        RuntimeException e =
                assertThrows(RuntimeException.class, () -> repository.getSkill("my-skill"));
        assertEquals("Failed to load skill from Nacos: my-skill", e.getMessage());
        assertEquals(nacosEx, e.getCause());
    }

    @Test
    @DisplayName("Should return AgentSkill when skill is found")
    void testGetSkillSuccess() throws NacosException {
        Skill nacosSkill = mockNacosSkill("test-skill", "A test skill", "Do something");
        when(aiService.loadSkill("test-skill")).thenReturn(nacosSkill);

        AgentSkill result = repository.getSkill("test-skill");

        assertNotNull(result);
        assertEquals("test-skill", result.getName());
        assertEquals("A test skill", result.getDescription());
        assertEquals("Do something", result.getSkillContent());
        assertEquals("nacos:public", result.getSource());
    }

    @Test
    @DisplayName("Should trim skill name when calling loadSkill")
    void testGetSkillTrimsName() throws NacosException {
        Skill nacosSkill = mockNacosSkill("trimmed", "Desc", "Content");
        when(aiService.loadSkill("trimmed")).thenReturn(nacosSkill);

        repository.getSkill("  trimmed  ");

        verify(aiService).loadSkill("trimmed");
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
        when(aiService.loadSkill("exists")).thenReturn(mock(Skill.class));

        assertTrue(repository.skillExists("exists"));
    }

    @Test
    @DisplayName("Should return false when skill not found")
    void testSkillExistsWhenNotFound() throws NacosException {
        when(aiService.loadSkill("missing")).thenReturn(null);

        assertFalse(repository.skillExists("missing"));
    }

    @Test
    @DisplayName("Should return false when NacosException NOT_FOUND")
    void testSkillExistsWhenNacosNotFound() throws NacosException {
        when(aiService.loadSkill("missing"))
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

    private static Skill mockNacosSkill(String name, String description, String instruction) {
        Skill skill = mock(Skill.class);
        when(skill.getName()).thenReturn(name);
        when(skill.getDescription()).thenReturn(description);
        when(skill.getInstruction()).thenReturn(instruction);
        when(skill.getResource()).thenReturn(null);
        return skill;
    }
}

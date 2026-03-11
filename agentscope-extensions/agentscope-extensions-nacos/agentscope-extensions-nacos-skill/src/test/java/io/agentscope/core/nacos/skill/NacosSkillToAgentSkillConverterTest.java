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
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.alibaba.nacos.api.ai.model.skills.Skill;
import com.alibaba.nacos.api.ai.model.skills.SkillResource;
import io.agentscope.core.skill.AgentSkill;
import java.util.HashMap;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

/**
 * Unit tests for {@link NacosSkillToAgentSkillConverter}.
 */
@ExtendWith(MockitoExtension.class)
class NacosSkillToAgentSkillConverterTest {

    @Mock private Skill nacosSkill;

    @Test
    @DisplayName("Should throw when Nacos Skill is null")
    void testToAgentSkillWithNullSkill() {
        IllegalArgumentException e =
                assertThrows(
                        IllegalArgumentException.class,
                        () -> NacosSkillToAgentSkillConverter.toAgentSkill(null, "nacos:public"));
        assertEquals("Nacos Skill cannot be null", e.getMessage());
    }

    @Test
    @DisplayName("Should use default 'unknown' when name is null")
    void testDefaultNameWhenNull() {
        when(nacosSkill.getName()).thenReturn(null);
        when(nacosSkill.getDescription()).thenReturn("desc");
        when(nacosSkill.getInstruction()).thenReturn("instruction");
        when(nacosSkill.getResource()).thenReturn(null);

        AgentSkill result =
                NacosSkillToAgentSkillConverter.toAgentSkill(nacosSkill, "nacos:public");

        assertEquals("unknown", result.getName());
    }

    @Test
    @DisplayName("Should use default 'unknown' when name is blank")
    void testDefaultNameWhenBlank() {
        when(nacosSkill.getName()).thenReturn("   ");
        when(nacosSkill.getDescription()).thenReturn("desc");
        when(nacosSkill.getInstruction()).thenReturn("instruction");
        when(nacosSkill.getResource()).thenReturn(null);

        AgentSkill result =
                NacosSkillToAgentSkillConverter.toAgentSkill(nacosSkill, "nacos:public");

        assertEquals("unknown", result.getName());
    }

    @Test
    @DisplayName("Should use default '(no description)' when description is null")
    void testDefaultDescriptionWhenNull() {
        when(nacosSkill.getName()).thenReturn("my-skill");
        when(nacosSkill.getDescription()).thenReturn(null);
        when(nacosSkill.getInstruction()).thenReturn("instruction");
        when(nacosSkill.getResource()).thenReturn(null);

        AgentSkill result =
                NacosSkillToAgentSkillConverter.toAgentSkill(nacosSkill, "nacos:public");

        assertEquals("(no description)", result.getDescription());
    }

    @Test
    @DisplayName("Should use default '(no description)' when description is blank")
    void testDefaultDescriptionWhenBlank() {
        when(nacosSkill.getName()).thenReturn("my-skill");
        when(nacosSkill.getDescription()).thenReturn("");
        when(nacosSkill.getInstruction()).thenReturn("instruction");
        when(nacosSkill.getResource()).thenReturn(null);

        AgentSkill result =
                NacosSkillToAgentSkillConverter.toAgentSkill(nacosSkill, "nacos:public");

        assertEquals("(no description)", result.getDescription());
    }

    @Test
    @DisplayName("Should use default '(no instruction)' when instruction is null")
    void testDefaultInstructionWhenNull() {
        when(nacosSkill.getName()).thenReturn("my-skill");
        when(nacosSkill.getDescription()).thenReturn("desc");
        when(nacosSkill.getInstruction()).thenReturn(null);
        when(nacosSkill.getResource()).thenReturn(null);

        AgentSkill result =
                NacosSkillToAgentSkillConverter.toAgentSkill(nacosSkill, "nacos:public");

        assertEquals("(no instruction)", result.getSkillContent());
    }

    @Test
    @DisplayName("Should use default '(no instruction)' when instruction is blank")
    void testDefaultInstructionWhenBlank() {
        when(nacosSkill.getName()).thenReturn("my-skill");
        when(nacosSkill.getDescription()).thenReturn("desc");
        when(nacosSkill.getInstruction()).thenReturn("  ");
        when(nacosSkill.getResource()).thenReturn(null);

        AgentSkill result =
                NacosSkillToAgentSkillConverter.toAgentSkill(nacosSkill, "nacos:public");

        assertEquals("(no instruction)", result.getSkillContent());
    }

    @Test
    @DisplayName("Should trim name, description, and instruction")
    void testTrimsFields() {
        when(nacosSkill.getName()).thenReturn("  my-skill  ");
        when(nacosSkill.getDescription()).thenReturn("  desc  ");
        when(nacosSkill.getInstruction()).thenReturn("  content  ");
        when(nacosSkill.getResource()).thenReturn(null);

        AgentSkill result =
                NacosSkillToAgentSkillConverter.toAgentSkill(nacosSkill, "nacos:public");

        assertEquals("my-skill", result.getName());
        assertEquals("desc", result.getDescription());
        assertEquals("content", result.getSkillContent());
    }

    @Test
    @DisplayName("Should return empty resources when resource map is null")
    void testNullResourceMap() {
        when(nacosSkill.getName()).thenReturn("my-skill");
        when(nacosSkill.getDescription()).thenReturn("desc");
        when(nacosSkill.getInstruction()).thenReturn("instruction");
        when(nacosSkill.getResource()).thenReturn(null);

        AgentSkill result =
                NacosSkillToAgentSkillConverter.toAgentSkill(nacosSkill, "nacos:public");

        assertNotNull(result.getResources());
        assertTrue(result.getResources().isEmpty());
    }

    @Test
    @DisplayName("Should return empty resources when resource map is empty")
    void testEmptyResourceMap() {
        when(nacosSkill.getName()).thenReturn("my-skill");
        when(nacosSkill.getDescription()).thenReturn("desc");
        when(nacosSkill.getInstruction()).thenReturn("instruction");
        when(nacosSkill.getResource()).thenReturn(new HashMap<>());

        AgentSkill result =
                NacosSkillToAgentSkillConverter.toAgentSkill(nacosSkill, "nacos:public");

        assertTrue(result.getResources().isEmpty());
    }

    @Test
    @DisplayName("Should map resources correctly")
    void testResourceMapping() {
        SkillResource res1 = mock(SkillResource.class);
        when(res1.getContent()).thenReturn("content1");
        SkillResource res2 = mock(SkillResource.class);
        when(res2.getContent()).thenReturn("content2");

        Map<String, SkillResource> resourceMap = new HashMap<>();
        resourceMap.put("ref/guide.md", res1);
        resourceMap.put("examples/sample.txt", res2);

        when(nacosSkill.getName()).thenReturn("my-skill");
        when(nacosSkill.getDescription()).thenReturn("desc");
        when(nacosSkill.getInstruction()).thenReturn("instruction");
        when(nacosSkill.getResource()).thenReturn(resourceMap);

        AgentSkill result = NacosSkillToAgentSkillConverter.toAgentSkill(nacosSkill, "nacos:ns1");

        assertEquals(2, result.getResources().size());
        assertEquals("content1", result.getResource("ref/guide.md"));
        assertEquals("content2", result.getResource("examples/sample.txt"));
    }

    @Test
    @DisplayName("Should use 'resource' as key when resource key is null")
    void testNullResourceKey() {
        SkillResource res = mock(SkillResource.class);
        when(res.getContent()).thenReturn("content");
        Map<String, SkillResource> resourceMap = new HashMap<>();
        resourceMap.put(null, res);

        when(nacosSkill.getName()).thenReturn("my-skill");
        when(nacosSkill.getDescription()).thenReturn("desc");
        when(nacosSkill.getInstruction()).thenReturn("instruction");
        when(nacosSkill.getResource()).thenReturn(resourceMap);

        AgentSkill result =
                NacosSkillToAgentSkillConverter.toAgentSkill(nacosSkill, "nacos:public");

        assertEquals("content", result.getResource("resource"));
    }

    @Test
    @DisplayName("Should use empty string when SkillResource content is null")
    void testNullResourceContent() {
        SkillResource res = mock(SkillResource.class);
        when(res.getContent()).thenReturn(null);
        Map<String, SkillResource> resourceMap = new HashMap<>();
        resourceMap.put("key", res);

        when(nacosSkill.getName()).thenReturn("my-skill");
        when(nacosSkill.getDescription()).thenReturn("desc");
        when(nacosSkill.getInstruction()).thenReturn("instruction");
        when(nacosSkill.getResource()).thenReturn(resourceMap);

        AgentSkill result =
                NacosSkillToAgentSkillConverter.toAgentSkill(nacosSkill, "nacos:public");

        assertEquals("", result.getResource("key"));
    }

    @Test
    @DisplayName("Should use empty string when SkillResource is null")
    void testNullSkillResource() {
        Map<String, SkillResource> resourceMap = new HashMap<>();
        resourceMap.put("key", null);

        when(nacosSkill.getName()).thenReturn("my-skill");
        when(nacosSkill.getDescription()).thenReturn("desc");
        when(nacosSkill.getInstruction()).thenReturn("instruction");
        when(nacosSkill.getResource()).thenReturn(resourceMap);

        AgentSkill result =
                NacosSkillToAgentSkillConverter.toAgentSkill(nacosSkill, "nacos:public");

        assertEquals("", result.getResource("key"));
    }

    @Test
    @DisplayName("Should pass source to AgentSkill")
    void testSourcePassedToAgentSkill() {
        when(nacosSkill.getName()).thenReturn("my-skill");
        when(nacosSkill.getDescription()).thenReturn("desc");
        when(nacosSkill.getInstruction()).thenReturn("instruction");
        when(nacosSkill.getResource()).thenReturn(null);

        AgentSkill result =
                NacosSkillToAgentSkillConverter.toAgentSkill(nacosSkill, "nacos:custom-ns");

        assertEquals("nacos:custom-ns", result.getSource());
    }
}

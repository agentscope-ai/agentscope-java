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

package io.agentscope.core.skill;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.when;

import io.agentscope.core.message.TextBlock;
import io.agentscope.core.message.ToolResultBlock;
import io.agentscope.core.tool.AgentTool;
import io.agentscope.core.tool.ToolCallParam;
import io.agentscope.core.tool.Toolkit;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class SkillToolFactoryTest {

    @Mock private SkillRegistry skillRegistry;

    @Mock private Toolkit toolkit;

    @Mock private SkillBox skillBox;

    private SkillToolFactory skillToolFactory;

    @BeforeEach
    void setUp() {
        skillToolFactory = new SkillToolFactory(skillRegistry, toolkit, skillBox);
    }

    @Test
    @DisplayName("Should include model info in response when skill has model")
    void test_buildSkillMarkdownResponse_skillWithModel_includesModelInfo() {
        // Arrange
        AgentSkill skillWithModel =
                new AgentSkill(
                        "code_review",
                        "Code review skill",
                        "Review code according to best practices",
                        null,
                        "custom",
                        "qwen-turbo");

        String skillId = skillWithModel.getSkillId();
        RegisteredSkill registeredSkill = new RegisteredSkill(skillId);

        setupSkillRegistryMocks(skillId, skillWithModel, registeredSkill);

        // Act
        AgentTool loadTool = skillToolFactory.createSkillAccessToolAgentTool();
        ToolResultBlock result =
                loadTool.callAsync(
                                ToolCallParam.builder()
                                        .input(Map.of("skillId", skillId, "path", "SKILL.md"))
                                        .build())
                        .block();

        // Assert
        assertNotNull(result);
        String content = getTextContent(result);
        assertTrue(content.contains("Model: qwen-turbo"), "Response should contain model info");
        assertTrue(
                content.contains("**Note:**"), "Response should contain note about sub-agent tool");
        assertTrue(
                content.contains("call_code_review"),
                "Response should mention the sub-agent tool name");
    }

    @Test
    @DisplayName("Should not include model info in response when skill has no model")
    void test_buildSkillMarkdownResponse_skillWithoutModel_excludesModelInfo() {
        // Arrange
        AgentSkill skillWithoutModel =
                new AgentSkill(
                        "simple_skill", "Simple skill", "Do something", null, "custom", null);

        String skillId = skillWithoutModel.getSkillId();
        RegisteredSkill registeredSkill = new RegisteredSkill(skillId);

        setupSkillRegistryMocks(skillId, skillWithoutModel, registeredSkill);

        // Act
        AgentTool loadTool = skillToolFactory.createSkillAccessToolAgentTool();
        ToolResultBlock result =
                loadTool.callAsync(
                                ToolCallParam.builder()
                                        .input(Map.of("skillId", skillId, "path", "SKILL.md"))
                                        .build())
                        .block();

        // Assert
        assertNotNull(result);
        String content = getTextContent(result);
        assertFalse(content.contains("Model:"), "Response should NOT contain model info");
        assertFalse(
                content.contains("**Note:**"),
                "Response should NOT contain note about sub-agent tool");
    }

    @Test
    @DisplayName("Should not include model info when model is empty string")
    void test_buildSkillMarkdownResponse_emptyModel_excludesModelInfo() {
        // Arrange
        AgentSkill skillWithEmptyModel =
                new AgentSkill(
                        "empty_model_skill",
                        "Skill with empty model",
                        "Do something",
                        null,
                        "custom",
                        "");

        String skillId = skillWithEmptyModel.getSkillId();
        RegisteredSkill registeredSkill = new RegisteredSkill(skillId);

        setupSkillRegistryMocks(skillId, skillWithEmptyModel, registeredSkill);

        // Act
        AgentTool loadTool = skillToolFactory.createSkillAccessToolAgentTool();
        ToolResultBlock result =
                loadTool.callAsync(
                                ToolCallParam.builder()
                                        .input(Map.of("skillId", skillId, "path", "SKILL.md"))
                                        .build())
                        .block();

        // Assert
        assertNotNull(result);
        String content = getTextContent(result);
        assertFalse(
                content.contains("Model:"),
                "Response should NOT contain model info for empty model");
        assertFalse(
                content.contains("**Note:**"), "Response should NOT contain note for empty model");
    }

    @Test
    @DisplayName("Should include correct tool name format in hint")
    void test_buildSkillMarkdownResponse_modelHint_correctToolName() {
        // Arrange
        AgentSkill skill =
                new AgentSkill(
                        "my_cool_skill",
                        "My cool skill",
                        "Cool instructions",
                        null,
                        "custom",
                        "qwen-plus");

        String skillId = skill.getSkillId();
        RegisteredSkill registeredSkill = new RegisteredSkill(skillId);

        setupSkillRegistryMocks(skillId, skill, registeredSkill);

        // Act
        AgentTool loadTool = skillToolFactory.createSkillAccessToolAgentTool();
        ToolResultBlock result =
                loadTool.callAsync(
                                ToolCallParam.builder()
                                        .input(Map.of("skillId", skillId, "path", "SKILL.md"))
                                        .build())
                        .block();

        // Assert
        assertNotNull(result);
        String content = getTextContent(result);
        assertTrue(
                content.contains("call_my_cool_skill"),
                "Response should contain correct tool name format");
        assertTrue(
                content.contains("model 'qwen-plus'"),
                "Response should mention the configured model");
    }

    /** Sets up common mocks for skill registry. */
    private void setupSkillRegistryMocks(
            String skillId, AgentSkill skill, RegisteredSkill registeredSkill) {
        when(skillRegistry.exists(skillId)).thenReturn(true);
        when(skillRegistry.getSkill(skillId)).thenReturn(skill);
        when(skillRegistry.getRegisteredSkill(skillId)).thenReturn(registeredSkill);
        // Use lenient for stubbing that may not be invoked in all tests
        lenient()
                .when(skillRegistry.getAllRegisteredSkills())
                .thenReturn(Map.of(skillId, registeredSkill));
        lenient().when(toolkit.getToolGroup(any())).thenReturn(null);
    }

    /** Extracts text content from ToolResultBlock output. */
    private String getTextContent(ToolResultBlock result) {
        return result.getOutput().stream()
                .filter(block -> block instanceof TextBlock)
                .map(block -> ((TextBlock) block).getText())
                .findFirst()
                .orElse("");
    }
}

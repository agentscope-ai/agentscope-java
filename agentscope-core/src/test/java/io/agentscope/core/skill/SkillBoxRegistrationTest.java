/*
 * Copyright 2024-2026 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      https://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package io.agentscope.core.skill;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import io.agentscope.core.model.Model;
import io.agentscope.core.tool.Toolkit;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

/**
 * Unit tests for SkillBox.SkillRegistration runtimeModel and sub-agent creation.
 *
 * <p>These tests verify that skills with models automatically create SubAgentTools.
 */
@Tag("unit")
class SkillBoxRegistrationTest {

    private Toolkit toolkit;
    private SkillBox skillBox;
    private Model qwenTurboModel;
    private Model qwenPlusModel;

    @BeforeEach
    void setUp() {
        toolkit = new Toolkit();
        qwenTurboModel = mock(Model.class);
        when(qwenTurboModel.getModelName()).thenReturn("qwen-turbo");

        qwenPlusModel = mock(Model.class);
        when(qwenPlusModel.getModelName()).thenReturn("qwen-plus");

        SkillModelProvider provider =
                MapBasedSkillModelProvider.builder()
                        .register("qwen-turbo", qwenTurboModel)
                        .register("qwen-plus", qwenPlusModel)
                        .build();

        skillBox = new SkillBox(toolkit, null, null, provider);
    }

    @Nested
    @DisplayName("Sub-Agent Tool Creation Tests")
    class SubAgentToolCreationTests {

        @Test
        @DisplayName("Should create sub-agent tool when skill has model")
        void shouldCreateSubAgentToolWhenSkillHasModel() {
            AgentSkill skill =
                    AgentSkill.builder()
                            .name("code_review")
                            .description("Code review skill")
                            .skillContent("Review code")
                            .model("qwen-turbo")
                            .build();

            skillBox.registration().skill(skill).apply();

            // Verify sub-agent tool was created
            assertNotNull(toolkit.getTool("call_code_review"));
        }

        @Test
        @DisplayName("Should not create sub-agent tool when skill has no model")
        void shouldNotCreateSubAgentToolWhenSkillHasNoModel() {
            AgentSkill skill =
                    AgentSkill.builder()
                            .name("simple_skill")
                            .description("Simple skill")
                            .skillContent("Do something")
                            .build();

            skillBox.registration().skill(skill).apply();

            // No sub-agent tool should be created
            assertNull(toolkit.getTool("call_simple_skill"));
        }

        @Test
        @DisplayName("Should use runtime model over skill model")
        void shouldUseRuntimeModelOverSkillModel() {
            AgentSkill skill =
                    AgentSkill.builder()
                            .name("code_review")
                            .description("Code review skill")
                            .skillContent("Review code")
                            .model("qwen-turbo")
                            .build();

            skillBox.registration().skill(skill).runtimeModel("qwen-plus").apply();

            // Tool should still be created
            assertNotNull(toolkit.getTool("call_code_review"));
        }

        @Test
        @DisplayName("Should not create sub-agent when model not found")
        void shouldNotCreateSubAgentWhenModelNotFound() {
            AgentSkill skill =
                    AgentSkill.builder()
                            .name("unknown_model_skill")
                            .description("Skill with unknown model")
                            .skillContent("Content")
                            .model("unknown_model")
                            .build();

            skillBox.registration().skill(skill).apply();

            // Should not crash, just log warning
            assertNull(toolkit.getTool("call_unknown_model_skill"));
        }

        @Test
        @DisplayName("Should not create sub-agent when no model provider")
        void shouldNotCreateSubAgentWhenNoModelProvider() {
            SkillBox boxWithoutProvider = new SkillBox(toolkit);

            AgentSkill skill =
                    AgentSkill.builder()
                            .name("code_review")
                            .description("Code review skill")
                            .skillContent("Review code")
                            .model("qwen-turbo")
                            .build();

            boxWithoutProvider.registration().skill(skill).apply();

            // Should not crash, just log warning
            assertNull(toolkit.getTool("call_code_review"));
        }
    }

    @Nested
    @DisplayName("Runtime Model Priority Tests")
    class RuntimeModelPriorityTests {

        @Test
        @DisplayName("Should create sub-agent with runtime model even when skill has no model")
        void shouldCreateSubAgentWithRuntimeModelEvenWhenSkillHasNoModel() {
            AgentSkill skill =
                    AgentSkill.builder()
                            .name("dynamic_skill")
                            .description("Dynamic skill")
                            .skillContent("Do something")
                            .build();

            skillBox.registration().skill(skill).runtimeModel("qwen-turbo").apply();

            // Tool should be created with runtime model
            assertNotNull(toolkit.getTool("call_dynamic_skill"));
        }

        @Test
        @DisplayName("Should not create sub-agent when runtime model is blank")
        void shouldNotCreateSubAgentWhenRuntimeModelIsBlank() {
            AgentSkill skill =
                    AgentSkill.builder()
                            .name("blank_runtime_skill")
                            .description("Blank runtime skill")
                            .skillContent("Content")
                            .build();

            skillBox.registration().skill(skill).runtimeModel("  ").apply();

            // No tool should be created
            assertNull(toolkit.getTool("call_blank_runtime_skill"));
        }
    }

    @Nested
    @DisplayName("createSubAgentToolForSkill Tests")
    class CreateSubAgentToolForSkillTests {

        @Test
        @DisplayName("Should return false when modelRef is null")
        void shouldReturnFalseWhenModelRefIsNull() {
            AgentSkill skill =
                    AgentSkill.builder()
                            .name("test")
                            .description("Test")
                            .skillContent("Content")
                            .build();

            boolean result = skillBox.createSubAgentToolForSkill(skill, toolkit, null);

            assertNull(toolkit.getTool("call_test"));
        }

        @Test
        @DisplayName("Should return false when modelRef is blank")
        void shouldReturnFalseWhenModelRefIsBlank() {
            AgentSkill skill =
                    AgentSkill.builder()
                            .name("test")
                            .description("Test")
                            .skillContent("Content")
                            .build();

            boolean result = skillBox.createSubAgentToolForSkill(skill, toolkit, "  ");

            assertNull(toolkit.getTool("call_test"));
        }

        @Test
        @DisplayName("Should return false when toolkit is null")
        void shouldReturnFalseWhenToolkitIsNull() {
            AgentSkill skill =
                    AgentSkill.builder()
                            .name("test")
                            .description("Test")
                            .skillContent("Content")
                            .model("test-model")
                            .build();

            boolean result = skillBox.createSubAgentToolForSkill(skill, null, "test-model");

            assertNull(toolkit.getTool("call_test"));
        }

        @Test
        @DisplayName("Should return false when no model provider configured")
        void shouldReturnFalseWhenNoModelProvider() {
            SkillBox boxWithoutProvider = new SkillBox(toolkit);
            AgentSkill skill =
                    AgentSkill.builder()
                            .name("test")
                            .description("Test")
                            .skillContent("Content")
                            .model("test-model")
                            .build();

            boolean result =
                    boxWithoutProvider.createSubAgentToolForSkill(skill, toolkit, "test-model");

            assertNull(toolkit.getTool("call_test"));
        }
    }
}

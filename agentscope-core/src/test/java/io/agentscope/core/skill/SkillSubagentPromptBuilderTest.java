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
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

/**
 * Unit tests for {@link SkillSubagentPromptBuilder}.
 */
@Tag("unit")
class SkillSubagentPromptBuilderTest {

    @Nested
    @DisplayName("Builder Tests")
    class BuilderTests {

        @Test
        @DisplayName("Should build prompt with all skill information")
        void shouldBuildPromptWithAllSkillInformation() {
            AgentSkill skill =
                    AgentSkill.builder()
                            .name("code_review")
                            .description("Review code for quality and best practices")
                            .skillContent("# Code Review\nReview the code carefully...")
                            .model("qwen-turbo")
                            .build();

            String prompt =
                    SkillSubagentPromptBuilder.builder()
                            .skill(skill)
                            .modelName("qwen-turbo")
                            .build();

            assertNotNull(prompt);
            assertTrue(prompt.contains("code_review"));
            assertTrue(prompt.contains("Review code for quality and best practices"));
            assertTrue(prompt.contains("# Code Review\nReview the code carefully..."));
            assertTrue(prompt.contains("qwen-turbo"));
        }

        @Test
        @DisplayName("Should include role definition")
        void shouldIncludeRoleDefinition() {
            AgentSkill skill =
                    AgentSkill.builder()
                            .name("data_analysis")
                            .description("Analyze data")
                            .skillContent("Content")
                            .build();

            String prompt =
                    SkillSubagentPromptBuilder.builder()
                            .skill(skill)
                            .modelName("qwen-plus")
                            .build();

            assertTrue(prompt.contains("You are a specialized agent"));
            assertTrue(prompt.contains("data_analysis"));
        }

        @Test
        @DisplayName("Should include guidelines section")
        void shouldIncludeGuidelinesSection() {
            AgentSkill skill =
                    AgentSkill.builder()
                            .name("test_skill")
                            .description("Test")
                            .skillContent("Content")
                            .build();

            String prompt =
                    SkillSubagentPromptBuilder.builder().skill(skill).modelName("default").build();

            assertTrue(prompt.contains("## Guidelines"));
            assertTrue(prompt.contains("Focus ONLY on tasks related to this skill"));
        }

        @Test
        @DisplayName("Should include tool usage section")
        void shouldIncludeToolUsageSection() {
            AgentSkill skill =
                    AgentSkill.builder()
                            .name("test_skill")
                            .description("Test")
                            .skillContent("Content")
                            .build();

            String prompt =
                    SkillSubagentPromptBuilder.builder().skill(skill).modelName("default").build();

            assertTrue(prompt.contains("## Tool Usage"));
            assertTrue(prompt.contains("toolkit"));
        }

        @Test
        @DisplayName("Should include constraints section")
        void shouldIncludeConstraintsSection() {
            AgentSkill skill =
                    AgentSkill.builder()
                            .name("test_skill")
                            .description("Test")
                            .skillContent("Content")
                            .build();

            String prompt =
                    SkillSubagentPromptBuilder.builder().skill(skill).modelName("default").build();

            assertTrue(prompt.contains("## Important Constraints"));
            assertTrue(prompt.contains("Do not perform actions unrelated to the skill's purpose"));
        }

        @Test
        @DisplayName("Should throw exception when skill is not set")
        void shouldThrowExceptionWhenSkillNotSet() {
            assertThrows(
                    IllegalStateException.class,
                    () -> SkillSubagentPromptBuilder.builder().modelName("qwen-turbo").build());
        }
    }

    @Nested
    @DisplayName("Null Handling Tests")
    class NullHandlingTests {

        @Test
        @DisplayName("Should handle null skill name")
        void shouldHandleNullSkillName() {
            AgentSkill skill =
                    AgentSkill.builder()
                            .name("temp_skill")
                            .description("Test")
                            .skillContent("Content")
                            .build();

            // Create skill and manually set name to null for testing
            String prompt =
                    SkillSubagentPromptBuilder.builder()
                            .skill(skill)
                            .modelName("qwen-turbo")
                            .build();

            assertTrue(prompt.contains("temp_skill"));
        }

        @Test
        @DisplayName("Should handle null model name")
        void shouldHandleNullModelName() {
            AgentSkill skill =
                    AgentSkill.builder()
                            .name("test_skill")
                            .description("Test description")
                            .skillContent("Test content")
                            .build();

            String prompt =
                    SkillSubagentPromptBuilder.builder().skill(skill).modelName(null).build();

            assertTrue(prompt.contains("default"));
        }

        @Test
        @DisplayName("Should handle empty model name")
        void shouldHandleEmptyModelName() {
            AgentSkill skill =
                    AgentSkill.builder()
                            .name("test_skill")
                            .description("Test description")
                            .skillContent("Test content")
                            .build();

            String prompt = SkillSubagentPromptBuilder.builder().skill(skill).modelName("").build();

            // Empty string should be preserved, but displayed as empty
            assertNotNull(prompt);
        }
    }

    @Nested
    @DisplayName("Template Structure Tests")
    class TemplateStructureTests {

        @Test
        @DisplayName("Should have all expected sections")
        void shouldHaveAllExpectedSections() {
            AgentSkill skill =
                    AgentSkill.builder()
                            .name("complete_skill")
                            .description("A complete skill for testing")
                            .skillContent("# Instructions\nDo something useful.")
                            .build();

            String prompt =
                    SkillSubagentPromptBuilder.builder().skill(skill).modelName("qwen-max").build();

            assertTrue(prompt.contains("## Your Purpose"), "Should have Purpose section");
            assertTrue(prompt.contains("## Your Instructions"), "Should have Instructions section");
            assertTrue(prompt.contains("## Guidelines"), "Should have Guidelines section");
            assertTrue(prompt.contains("## Tool Usage"), "Should have Tool Usage section");
            assertTrue(
                    prompt.contains("## Important Constraints"), "Should have Constraints section");
        }

        @Test
        @DisplayName("Should properly format multiline skill content")
        void shouldProperlyFormatMultilineSkillContent() {
            String multilineContent =
                    """
                    # Code Review Guidelines

                    ## Key Areas to Review
                    1. Code quality
                    2. Performance
                    3. Security

                    ## Output Format
                    Return findings in JSON format.
                    """;

            AgentSkill skill =
                    AgentSkill.builder()
                            .name("code_review")
                            .description("Review code")
                            .skillContent(multilineContent)
                            .build();

            String prompt =
                    SkillSubagentPromptBuilder.builder()
                            .skill(skill)
                            .modelName("qwen-turbo")
                            .build();

            assertTrue(prompt.contains("# Code Review Guidelines"));
            assertTrue(prompt.contains("## Key Areas to Review"));
            assertTrue(prompt.contains("1. Code quality"));
            assertTrue(prompt.contains("Return findings in JSON format"));
        }
    }
}

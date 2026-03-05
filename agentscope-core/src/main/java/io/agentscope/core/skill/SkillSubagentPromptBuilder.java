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

/**
 * Builder for constructing system prompts for skill-based sub-agents.
 *
 * <p>This class creates comprehensive system prompts that include:
 *
 * <ul>
 *   <li>Role definition - Who the sub-agent is
 *   <li>Skill description - What the skill does
 *   <li>Detailed instructions - How to execute the skill
 *   <li>Behavioral guidelines - Constraints and best practices
 *   <li>Tool usage guidance - How to use available tools
 * </ul>
 *
 * <p>This ensures sub-agents have clear boundaries and understand their role, preventing
 * behavior drift and ensuring focused, accurate responses.
 *
 * <p>Example usage:
 *
 * <pre>{@code
 * String systemPrompt = SkillSubagentPromptBuilder.builder()
 *     .skill(skill)
 *     .modelName("qwen-turbo")
 *     .build();
 * }</pre>
 */
public class SkillSubagentPromptBuilder {

    private static final String SKILL_SUBAGENT_TEMPLATE =
            """
            You are a specialized agent for the skill: {skillName}.

            ## Your Purpose

            {skillDescription}

            ## Your Instructions

            {skillContent}

            ## Guidelines

            - Focus ONLY on tasks related to this skill
            - Use the available tools appropriately to complete the task
            - If the task is outside this skill's scope, clearly state so
            - Be concise and accurate in your responses
            - Report your findings clearly without unnecessary elaboration
            - Do not make assumptions about data or files that are not provided
            - Always verify information before making claims

            ## Tool Usage

            - You have access to tools through the toolkit
            - Choose the most appropriate tool for each subtask
            - Chain tool calls when necessary for complex operations
            - Handle tool errors gracefully and report issues clearly

            ## Important Constraints

            - Do not perform actions unrelated to the skill's purpose
            - Do not modify files unless explicitly required by the skill
            - Do not share sensitive information in your responses
            - Always respect the skill's intended use case

            ---
            *Executing with model: {modelName}*
            """;

    private AgentSkill skill;
    private String modelName;

    private SkillSubagentPromptBuilder() {}

    /**
     * Creates a new builder instance.
     *
     * @return New builder
     */
    public static SkillSubagentPromptBuilder builder() {
        return new SkillSubagentPromptBuilder();
    }

    /**
     * Sets the skill for which to build the system prompt.
     *
     * @param skill The skill definition
     * @return This builder
     */
    public SkillSubagentPromptBuilder skill(AgentSkill skill) {
        this.skill = skill;
        return this;
    }

    /**
     * Sets the model name being used for execution.
     *
     * @param modelName The model name
     * @return This builder
     */
    public SkillSubagentPromptBuilder modelName(String modelName) {
        this.modelName = modelName;
        return this;
    }

    /**
     * Builds the complete system prompt for the skill sub-agent.
     *
     * @return The formatted system prompt
     * @throws IllegalStateException if skill is not set
     */
    public String build() {
        if (skill == null) {
            throw new IllegalStateException("Skill must be set before building");
        }

        String name = skill.getName() != null ? skill.getName() : "unknown";
        String description =
                skill.getDescription() != null
                        ? skill.getDescription()
                        : "No description provided.";
        String content =
                skill.getSkillContent() != null
                        ? skill.getSkillContent()
                        : "No instructions provided.";
        String model = modelName != null ? modelName : "default";

        return SKILL_SUBAGENT_TEMPLATE
                .replace("{skillName}", name)
                .replace("{skillDescription}", description)
                .replace("{skillContent}", content)
                .replace("{modelName}", model);
    }
}

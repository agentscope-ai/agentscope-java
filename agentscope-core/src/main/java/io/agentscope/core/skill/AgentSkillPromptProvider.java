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
package io.agentscope.core.skill;

/**
 * Generates skill system prompts for agents to understand available skills.
 *
 * <p>This provider creates system prompts containing information about available skills
 * that the LLM can dynamically load and use.
 *
 * <p><b>Usage example:</b>
 * <pre>{@code
 * AgentSkillPromptProvider provider = new AgentSkillPromptProvider(registry);
 * String prompt = provider.getSkillSystemPrompt();
 * }</pre>
 */
public class AgentSkillPromptProvider {
    private final SkillRegistry skillRegistry;

    public static final String DEFAULT_AGENT_SKILL_INSTRUCTION =
            """
            ## Available Skills

            <usage>
            When you need to perform tasks, check if any of the available skills below can help complete the task more effectively. Skills provide specialized capabilities, tools, and domain knowledge.

            How to use skills:
            - Load skill: load_skill_resource(skillId="<skill-id>", path="SKILL.md")
            - The skill will be activated and its documentation loaded with detailed instructions
            - Additional resources (scripts, configs, templates) can be loaded using the same tool with different paths

            Usage notes:
            - Only use skills listed in <available_skills> below
            - Do not load a skill that is already activated in your current session
            - Loading SKILL.md activates the skill and makes its tools available
            - Each skill load is persistent within the session
            - If you don't know available resources, use an invalid path to list them
            </usage>

            <available_skills>

            """;

    // skillId, skillName, skillDescription
    public static final String DEFAULT_AGENT_SKILL_TEMPLATE =
            """
            <skill>
            <skill-id>%s</skill-id>
            <name>%s</name>
            <description>%s</description>
            </skill>

            """;

    /**
     * Creates a skill prompt provider.
     *
     * @param registry The skill registry containing registered skills
     */
    public AgentSkillPromptProvider(SkillRegistry registry) {
        this.skillRegistry = registry;
    }

    /**
     * Gets the skill system prompt for the agent.
     *
     * <p>Generates a system prompt containing all registered skills.
     *
     * @return The skill system prompt, or empty string if no skills exist
     */
    public String getSkillSystemPrompt() {
        StringBuilder sb = new StringBuilder();

        // Check if there are any skills
        if (skillRegistry.getAllRegisteredSkills().isEmpty()) {
            return "";
        }

        // Add instruction header
        sb.append(DEFAULT_AGENT_SKILL_INSTRUCTION);

        // Add each skill
        for (RegisteredSkill registered : skillRegistry.getAllRegisteredSkills().values()) {
            AgentSkill skill = skillRegistry.getSkill(registered.getSkillId());
            sb.append(
                    String.format(
                            DEFAULT_AGENT_SKILL_TEMPLATE,
                            skill.getSkillId(),
                            skill.getName(),
                            skill.getDescription()));
        }

        // Close available_skills tag
        sb.append("</available_skills>");

        return sb.toString();
    }
}

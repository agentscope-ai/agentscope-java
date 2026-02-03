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
    private final String instruction;
    private final String template;

    public static final String DEFAULT_AGENT_SKILL_INSTRUCTION =
            """
            ## Available Skills

            <usage>
            When you need to perform tasks, check if any of the available skills below can help complete the task more effectively. Skills provide specialized capabilities, tools, and domain knowledge.

            How to use skills:
            - Load skill: load_skill_through_path(skillId="<skill-id>", path="SKILL.md")
            - The skill will be activated and its documentation loaded with detailed instructions
            - Additional resources (scripts, assets, references) can be loaded using the same tool with different paths

            Usage notes:
            - Only use skills listed in <available_skills> below
            - Loading SKILL.md activates the skill and will make its tools available

            Code execution guidance:
            - If a task requires running code, use the code execution tools (shell/read/write)
                when they are available
            - Code execution tools operate in workDir; skill scripts are uploaded to
                workDir/skills/<skill-id>/scripts (for example: workDir/skills/skill-ID/scripts/shell.sh)
            - Example (shell): execute_shell_command(command="skills/skill-ID/scripts/shell.sh")

            Template fields explanation:
            - <name>: The skill's display name. Use it along with <description> to determine if this skill is relevant to your current task
            - <description>: Detailed description of when and how to use this skill. Read carefully to decide whether to load this skill
            - <skill-id>: The unique identifier used to load the skill via load_skill_through_path tool
            </usage>

            <available_skills>

            """;

    // skillName, skillDescription, skillId
    public static final String DEFAULT_AGENT_SKILL_TEMPLATE =
            """
            <skill>
            <name>%s</name>
            <description>%s</description>
            <skill-id>%s</skill-id>
            </skill>

            """;

    /**
     * Creates a skill prompt provider.
     *
     * @param registry The skill registry containing registered skills
     */
    public AgentSkillPromptProvider(SkillRegistry registry) {
        this(registry, null, null);
    }

    /**
     * Creates a skill prompt provider with custom instruction and template.
     *
     * @param registry The skill registry containing registered skills
     * @param instruction Custom instruction header (null or blank uses default)
     * @param template Custom skill template (null or blank uses default)
     */
    public AgentSkillPromptProvider(SkillRegistry registry, String instruction, String template) {
        this.skillRegistry = registry;
        this.instruction =
                instruction == null || instruction.isBlank()
                        ? DEFAULT_AGENT_SKILL_INSTRUCTION
                        : instruction;
        this.template =
                template == null || template.isBlank() ? DEFAULT_AGENT_SKILL_TEMPLATE : template;
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
        sb.append(instruction);

        // Add each skill
        for (RegisteredSkill registered : skillRegistry.getAllRegisteredSkills().values()) {
            AgentSkill skill = skillRegistry.getSkill(registered.getSkillId());
            sb.append(
                    String.format(
                            template, skill.getName(), skill.getDescription(), skill.getSkillId()));
        }

        // Close available_skills tag
        sb.append("</available_skills>");

        return sb.toString();
    }
}

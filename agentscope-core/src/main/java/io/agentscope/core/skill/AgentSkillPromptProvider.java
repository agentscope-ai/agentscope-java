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
            "# Agent Skills\n"
                + "Agent skills are specialized capabilities you can load on-demand to handle"
                + " specific tasks. Each skill below includes a brief description. When you need to"
                + " use a skill:\n"
                + "1. Use `skill_md_load_tool` with the skillId to read its detailed SKILL.md"
                + " documentation\n"
                + "2. If the skill requires additional resources, use `get_all_resources_path_tool`"
                + " to see what's available\n"
                + "3. Load specific resources with `skill_resources_load_tool` as needed\n\n"
                + "Only load skill details when you actually need them for the current task.\n\n"
                + "## Available Skills\n";

    // skillId, skillDescription
    public static final String DEFAULT_AGENT_SKILL_TEMPLATE =
            """
            ### %s
            %s
            check "SKILL.md" for how to use this skill
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
        for (RegisteredSkill registered : skillRegistry.getAllRegisteredSkills().values()) {
            AgentSkill skill = skillRegistry.getSkill(registered.getSkillId());

            if (sb.isEmpty()) {
                sb.append(DEFAULT_AGENT_SKILL_INSTRUCTION);
            }
            sb.append(
                    String.format(
                            DEFAULT_AGENT_SKILL_TEMPLATE,
                            skill.getSkillId(),
                            skill.getDescription()));
        }

        return sb.toString();
    }
}

package io.agentscope.core.agent.test;

import io.agentscope.core.skill.AgentSkill;
import io.agentscope.core.skill.SkillBox;

public class MockSkillBox extends SkillBox {
    /**
     * Register a mock skill for testing.
     *
     * @param skillName The name of the skill
     * @param description The description of the skill
     * @param groupName The skill group name (null for ungrouped)
     * @param active Whether the skill group should be active
     */
    public void registerMockSkill(
            String skillName, String description, String groupName, boolean active) {
        AgentSkill skill =
                new AgentSkill(skillName, description, "# " + skillName + " Content", null);

        if (groupName != null) {
            createSkillGroup(groupName, groupName + " description", active);
            registration().skill(skill).skillGroup(groupName).apply();
        } else {
            registerAgentSkill(skill);
        }
    }
}

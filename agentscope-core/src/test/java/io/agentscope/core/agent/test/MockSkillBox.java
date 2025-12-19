/*
 * Copyright 2024-2025 the original author or authors.
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

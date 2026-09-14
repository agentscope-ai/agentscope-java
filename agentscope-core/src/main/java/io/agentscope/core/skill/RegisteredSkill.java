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
 * Metadata wrapper for registered skills.
 *
 * <p>Records skill registration information including skill ID and activation state.
 * The activation state determines whether the skill's associated tools are available to the LLM.
 *
 */
class RegisteredSkill {
    private final String skillId;
    private boolean active; // whether this skill is being used by llm, if using need activate the
    // Whether the SKILL.md entry content has actually been delivered to the model.
    // Distinct from `active`: loading any resource activates the skill (#1569), but
    // only a served SKILL.md means its content is already in context.
    private boolean entryLoaded;

    /**
     * Creates a registered skill.
     *
     * @param skillId The skill ID
     */
    public RegisteredSkill(String skillId) {
        this.skillId = skillId;
        this.active = false;
        this.entryLoaded = false;
    }

    /**
     * Marks the SKILL.md entry content as delivered.
     *
     * @param entryLoaded whether the entry content has been served
     */
    public void setEntryLoaded(boolean entryLoaded) {
        this.entryLoaded = entryLoaded;
    }

    /**
     * Gets whether the SKILL.md entry content has been delivered.
     *
     * @return true if the entry content has been served
     */
    public boolean isEntryLoaded() {
        return entryLoaded;
    }

    /**
     * Sets the activation state.
     *
     * @param active Whether to activate the skill
     */
    public void setActive(boolean active) {
        this.active = active;
    }

    /**
     * Gets the activation state.
     *
     * @return true if active, false otherwise
     */
    public boolean isActive() {
        return active;
    }

    /**
     * Gets the skill ID.
     *
     * @return The skill ID
     */
    public String getSkillId() {
        return skillId;
    }

    /**
     * Gets the tool group name for this skill.
     *
     * @return The tool group name
     */
    public String getToolsGroupName() {
        return skillId + "_skill_tools";
    }
}

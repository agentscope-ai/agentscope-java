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
package io.agentscope.core.tool;

import java.util.HashSet;
import java.util.Objects;
import java.util.Set;

/**
 * A {@link ToolGroup} that is bound to a specific skill. When the associated skill is in use,
 * this tool group should be activated alongside it.
 *
 * <p>The {@link #getDescription()} method appends a reminder to the base description, informing
 * the model that this tool group must be activated whenever the bound skill is active.
 *
 * <p>Scope defaults to {@link ToolGroupScope#META} so the agent can manage it via
 * {@code reset_equipped_tools}.
 *
 * <p><b>Usage Example:</b>
 *
 * <pre>{@code
 * SkillToolGroup group = SkillToolGroup.skillBuilder()
 *     .name("data_analysis_tools")
 *     .description("Tools for data analysis")
 *     .activateOnSkill("data_analysis")
 *     .active(false)
 *     .build();
 * toolkit.registerToolGroup(group);
 * }</pre>
 */
public class SkillToolGroup extends ToolGroup {

    private final String activateOnSkill;
    private final String rawDescription;

    private SkillToolGroup(Builder builder) {
        super(
                builder.name,
                builder.description,
                builder.active,
                ToolGroupScope.META,
                builder.tools);
        this.activateOnSkill =
                Objects.requireNonNull(builder.activateOnSkill, "activateOnSkill cannot be null");
        this.rawDescription = builder.description != null ? builder.description : "";
    }

    /**
     * Returns the name of the skill that this tool group is bound to.
     *
     * @return The skill name (never null)
     */
    public String getActivateOnSkill() {
        return activateOnSkill;
    }

    /**
     * Returns the enhanced description that includes a reminder about the skill binding.
     *
     * @return The base description plus a skill-activation reminder
     */
    @Override
    public String getDescription() {
        return rawDescription
                + "\n\n**IMPORTANT: This tool group MUST be activated whenever the skill '"
                + activateOnSkill
                + "' is in use.**";
    }

    @Override
    public ToolGroup copy() {
        SkillToolGroup copy =
                SkillToolGroup.skillBuilder()
                        .name(getName())
                        .description(rawDescription)
                        .active(isActive())
                        .activateOnSkill(activateOnSkill)
                        .tools(getTools())
                        .build();
        return copy;
    }

    /**
     * Creates a new builder for constructing SkillToolGroup instances.
     *
     * @return A new SkillToolGroup builder instance
     */
    public static Builder skillBuilder() {
        return new Builder();
    }

    /** Builder for constructing SkillToolGroup instances. */
    public static class Builder {

        private String name;
        private String description = "";
        private boolean active = false;
        private String activateOnSkill;
        private Set<String> tools = new HashSet<>();

        /**
         * Sets the name of the tool group.
         *
         * @param name The group name (required)
         * @return This builder for method chaining
         */
        public Builder name(String name) {
            this.name = name;
            return this;
        }

        /**
         * Sets the base description of the tool group.
         *
         * @param description The group description
         * @return This builder for method chaining
         */
        public Builder description(String description) {
            this.description = description;
            return this;
        }

        /**
         * Sets the initial activation state.
         *
         * @param active true for active, false for inactive (default)
         * @return This builder for method chaining
         */
        public Builder active(boolean active) {
            this.active = active;
            return this;
        }

        /**
         * Sets the skill that this tool group is bound to.
         *
         * @param skillName The skill name (required)
         * @return This builder for method chaining
         */
        public Builder activateOnSkill(String skillName) {
            this.activateOnSkill = skillName;
            return this;
        }

        /**
         * Sets the initial set of tools in this group.
         *
         * @param tools The tool names (a defensive copy will be made)
         * @return This builder for method chaining
         */
        public Builder tools(Set<String> tools) {
            this.tools = new HashSet<>(tools);
            return this;
        }

        /**
         * Builds a new SkillToolGroup with the configured settings.
         *
         * @return A new SkillToolGroup instance
         * @throws NullPointerException if name or activateOnSkill is null
         */
        public SkillToolGroup build() {
            return new SkillToolGroup(this);
        }
    }
}

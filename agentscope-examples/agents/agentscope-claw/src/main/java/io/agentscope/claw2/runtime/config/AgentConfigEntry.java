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
package io.agentscope.claw2.runtime.config;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;
import io.agentscope.harness.agent.HarnessAgent;
import java.util.List;

/**
 * Per-agent section in {@code agentscope.json} under {@code agents.<agentId>}.
 *
 * <p>After the agent is built, {@link HarnessAgent} automatically loads additional
 * workspace-scoped configuration (for example {@code subagents/*.md}) from the resolved {@link
 * #workspace} directory.
 *
 * <p>Fields mirror OpenClaw's agent definition schema:
 *
 * <ul>
 *   <li>{@link #model} — override model id (e.g. {@code "anthropic/claude-opus-4-7"})
 *   <li>{@link #tools} — allow/deny lists for built-in tools
 *   <li>{@link #identity} — display name and emoji override
 *   <li>{@link #groupChat} — mention gating for group/room channels
 *   <li>{@link #sandbox} — sandbox mode and scope
 *   <li>{@link #skills} — skill allowlist/denylist
 * </ul>
 */
@JsonInclude(JsonInclude.Include.NON_EMPTY)
@JsonIgnoreProperties(ignoreUnknown = true)
public class AgentConfigEntry {

    @JsonProperty("name")
    private String name;

    @JsonProperty("description")
    private String description;

    @JsonProperty("sysPrompt")
    private String sysPrompt;

    /**
     * Workspace root for this agent. Relative paths are resolved against the bootstrap working
     * directory.
     */
    @JsonProperty("workspace")
    private String workspace;

    @JsonProperty("maxIters")
    private Integer maxIters;

    @JsonProperty("environmentMemory")
    private String environmentMemory;

    /**
     * Legacy single-repository field. Retained for backward compatibility with existing {@code
     * agentscope.json} files. New configurations should prefer {@link #skillRepositories}. When
     * both are set the singular value is prepended to the list.
     */
    @JsonProperty("skillRepository")
    private SkillRepositoryConfigEntry skillRepository;

    /** Ordered list of skill repositories to bind to the agent (additive). */
    @JsonProperty("skillRepositories")
    private List<SkillRepositoryConfigEntry> skillRepositories;

    /**
     * Optional model id override (e.g. {@code "anthropic/claude-opus-4-7"}). When null the
     * bootstrap-level model is used.
     */
    @JsonProperty("model")
    private String model;

    /**
     * Tool allow / deny lists. Only tools whose name is in {@code allow} are offered to the agent
     * (when non-empty). Tools in {@code deny} are always removed regardless of {@code allow}.
     */
    @JsonProperty("tools")
    private ToolsConfig tools;

    /** Display identity overrides (name, emoji). */
    @JsonProperty("identity")
    private IdentityConfig identity;

    /** Group chat gating configuration (mention patterns, requireMention). */
    @JsonProperty("groupChat")
    private GroupChatConfig groupChat;

    /** Sandbox mode and scope. */
    @JsonProperty("sandbox")
    private SandboxConfig sandbox;

    /** Skill allow / deny lists. */
    @JsonProperty("skills")
    private SkillsConfig skills;

    // -----------------------------------------------------------------
    //  Getters / setters
    // -----------------------------------------------------------------

    public String getName() {
        return name;
    }

    public void setName(String name) {
        this.name = name;
    }

    public String getDescription() {
        return description;
    }

    public void setDescription(String description) {
        this.description = description;
    }

    public String getSysPrompt() {
        return sysPrompt;
    }

    public void setSysPrompt(String sysPrompt) {
        this.sysPrompt = sysPrompt;
    }

    public String getWorkspace() {
        return workspace;
    }

    public void setWorkspace(String workspace) {
        this.workspace = workspace;
    }

    public Integer getMaxIters() {
        return maxIters;
    }

    public void setMaxIters(Integer maxIters) {
        this.maxIters = maxIters;
    }

    public String getEnvironmentMemory() {
        return environmentMemory;
    }

    public void setEnvironmentMemory(String environmentMemory) {
        this.environmentMemory = environmentMemory;
    }

    public SkillRepositoryConfigEntry getSkillRepository() {
        return skillRepository;
    }

    public void setSkillRepository(SkillRepositoryConfigEntry skillRepository) {
        this.skillRepository = skillRepository;
    }

    public List<SkillRepositoryConfigEntry> getSkillRepositories() {
        return skillRepositories;
    }

    public void setSkillRepositories(List<SkillRepositoryConfigEntry> skillRepositories) {
        this.skillRepositories = skillRepositories;
    }

    /**
     * Returns the effective ordered list of skill repository configs, merging the legacy {@link
     * #skillRepository} (singular) into the head of {@link #skillRepositories}. Returns an empty
     * list when neither is set.
     */
    public List<SkillRepositoryConfigEntry> getEffectiveSkillRepositories() {
        java.util.ArrayList<SkillRepositoryConfigEntry> out = new java.util.ArrayList<>();
        if (skillRepository != null) {
            out.add(skillRepository);
        }
        if (skillRepositories != null) {
            for (SkillRepositoryConfigEntry e : skillRepositories) {
                if (e != null) out.add(e);
            }
        }
        return out;
    }

    public String getModel() {
        return model;
    }

    public void setModel(String model) {
        this.model = model;
    }

    public ToolsConfig getTools() {
        return tools;
    }

    public void setTools(ToolsConfig tools) {
        this.tools = tools;
    }

    public IdentityConfig getIdentity() {
        return identity;
    }

    public void setIdentity(IdentityConfig identity) {
        this.identity = identity;
    }

    public GroupChatConfig getGroupChat() {
        return groupChat;
    }

    public void setGroupChat(GroupChatConfig groupChat) {
        this.groupChat = groupChat;
    }

    public SandboxConfig getSandbox() {
        return sandbox;
    }

    public void setSandbox(SandboxConfig sandbox) {
        this.sandbox = sandbox;
    }

    public SkillsConfig getSkills() {
        return skills;
    }

    public void setSkills(SkillsConfig skills) {
        this.skills = skills;
    }

    // -----------------------------------------------------------------
    //  Nested config types
    // -----------------------------------------------------------------

    @JsonInclude(JsonInclude.Include.NON_EMPTY)
    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class ToolsConfig {

        /** When non-empty, only tools in this list are offered to the agent. */
        @JsonProperty("allow")
        private List<String> allow;

        /** Tools in this list are always removed, even if present in {@code allow}. */
        @JsonProperty("deny")
        private List<String> deny;

        public List<String> getAllow() {
            return allow;
        }

        public void setAllow(List<String> allow) {
            this.allow = allow;
        }

        public List<String> getDeny() {
            return deny;
        }

        public void setDeny(List<String> deny) {
            this.deny = deny;
        }
    }

    @JsonInclude(JsonInclude.Include.NON_EMPTY)
    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class IdentityConfig {

        /** Display name override (shown in chat UI and logs). */
        @JsonProperty("name")
        private String name;

        /** Emoji shorthand for quick visual identification. */
        @JsonProperty("emoji")
        private String emoji;

        public String getName() {
            return name;
        }

        public void setName(String name) {
            this.name = name;
        }

        public String getEmoji() {
            return emoji;
        }

        public void setEmoji(String emoji) {
            this.emoji = emoji;
        }
    }

    @JsonInclude(JsonInclude.Include.NON_EMPTY)
    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class GroupChatConfig {

        /**
         * List of patterns (exact strings or prefixes) that trigger the agent in group messages.
         * When empty the agent responds to all messages in the group.
         */
        @JsonProperty("mentionPatterns")
        private List<String> mentionPatterns;

        /**
         * When {@code true} the agent only responds when a mention pattern matches. Defaults to
         * {@code false} (respond to all).
         */
        @JsonProperty("requireMention")
        private Boolean requireMention;

        public List<String> getMentionPatterns() {
            return mentionPatterns;
        }

        public void setMentionPatterns(List<String> mentionPatterns) {
            this.mentionPatterns = mentionPatterns;
        }

        public Boolean getRequireMention() {
            return requireMention;
        }

        public void setRequireMention(Boolean requireMention) {
            this.requireMention = requireMention;
        }
    }

    @JsonInclude(JsonInclude.Include.NON_EMPTY)
    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class SandboxConfig {

        /**
         * Sandbox mode. One of: {@code "off"} (default, no sandbox), {@code "all"} (always
         * sandboxed), {@code "prompt"} (user-approved per session).
         */
        @JsonProperty("mode")
        private String mode;

        /**
         * Sandbox scope. One of: {@code "agent"} (one container per agent), {@code "session"}
         * (one container per session), {@code "shared"} (shared across agents).
         */
        @JsonProperty("scope")
        private String scope;

        public String getMode() {
            return mode;
        }

        public void setMode(String mode) {
            this.mode = mode;
        }

        public String getScope() {
            return scope;
        }

        public void setScope(String scope) {
            this.scope = scope;
        }
    }

    @JsonInclude(JsonInclude.Include.NON_EMPTY)
    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class SkillsConfig {

        /** When non-empty, only skills in this list are loaded for the agent. */
        @JsonProperty("allow")
        private List<String> allow;

        /** Skills in this list are never loaded, even if present in {@code allow}. */
        @JsonProperty("deny")
        private List<String> deny;

        public List<String> getAllow() {
            return allow;
        }

        public void setAllow(List<String> allow) {
            this.allow = allow;
        }

        public List<String> getDeny() {
            return deny;
        }

        public void setDeny(List<String> deny) {
            this.deny = deny;
        }
    }
}

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
package io.agentscope.builder.web.persistence.jpa;

import jakarta.persistence.CascadeType;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.Lob;
import jakarta.persistence.OneToMany;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import java.util.ArrayList;
import java.util.List;

/**
 * Persistent representation of a user-custom agent definition. Keyed by the synthetic
 * {@code row_id} and uniquely identified by the {@code (owner_id, agent_id)} pair.
 *
 * <h2>Relationship to the user table</h2>
 *
 * <p>{@code owner_id} is a soft foreign key to {@link UserEntity#getUserId()}. JPA does not
 * manage cascading deletes; the application-level
 * {@link io.agentscope.builder.web.api.AdminUserController#delete} flow takes responsibility
 * for revoking grants and removing the agents owned by a deleted user. The schema keeps
 * {@code owner_id} as an indexed plain column so deployments may add a database-level
 * {@code FOREIGN KEY ... ON DELETE CASCADE} via migration scripts when desired.
 *
 * <h2>Storage layout</h2>
 *
 * <ul>
 *   <li>Scalar metadata (name, description, model, runAs, ...) lives in dedicated columns.
 *   <li>List-shaped settings (tools allow/deny, skills allow/deny, group-chat mention patterns)
 *       are persisted as JSON strings in a single column each. This keeps the schema portable
 *       across MySQL / PostgreSQL / H2 without introducing per-list join tables for what are
 *       effectively bounded configuration lists, and matches the wire format used by the
 *       JSON-file backend so migration in either direction is straightforward.
 *   <li>{@link AgentShareEntity} is mapped as a one-to-many relationship with
 *       {@code orphanRemoval = true}; updates to the shares list replace the whole collection
 *       atomically inside one transaction.
 * </ul>
 */
@Entity
@Table(
        name = "builder_agent",
        uniqueConstraints =
                @UniqueConstraint(
                        name = "ux_builder_agent_owner_agent",
                        columnNames = {"owner_id", "agent_id"}),
        indexes = {
            @Index(name = "ix_builder_agent_owner", columnList = "owner_id"),
            @Index(name = "ix_builder_agent_agent_id", columnList = "agent_id")
        })
public class AgentEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "row_id")
    private Long rowId;

    @Column(name = "owner_id", length = 128, nullable = false)
    private String ownerId;

    @Column(name = "agent_id", length = 128, nullable = false)
    private String agentId;

    /**
     * User-supplied workspace path for this agent (verbatim, may be {@code null}). When non-null,
     * absolute paths are used as-is on disk; relative paths resolve under
     * {@code ${cwd}/.agentscope/}. When {@code null}, the agent id is used in place of the path.
     * See {@link io.agentscope.builder.web.workspace.SharedWorkspacePaths#resolveAgentDataPath}
     * for the full resolution rules. Authoritative — runtime code reads this column to locate
     * per-agent data on disk.
     */
    @Column(name = "workspace_path", length = 1024)
    private String workspacePath;

    @Column(name = "name", length = 200)
    private String name;

    @Lob
    @Column(name = "description")
    private String description;

    @Lob
    @Column(name = "sys_prompt")
    private String sysPrompt;

    @Column(name = "model", length = 100)
    private String model;

    @Column(name = "max_iters")
    private Integer maxIters;

    @Lob
    @Column(name = "tools_allow_json")
    private String toolsAllowJson;

    @Lob
    @Column(name = "tools_deny_json")
    private String toolsDenyJson;

    @Column(name = "identity_name", length = 200)
    private String identityName;

    @Column(name = "identity_emoji", length = 32)
    private String identityEmoji;

    @Lob
    @Column(name = "group_chat_mention_patterns_json")
    private String groupChatMentionPatternsJson;

    @Column(name = "group_chat_require_mention")
    private Boolean groupChatRequireMention;

    @Lob
    @Column(name = "skills_allow_json")
    private String skillsAllowJson;

    @Lob
    @Column(name = "skills_deny_json")
    private String skillsDenyJson;

    @Column(name = "run_as", length = 20)
    private String runAs;

    @Column(name = "fork_of", length = 128)
    private String forkOf;

    /**
     * JSON-serialised {@link io.agentscope.builder.runtime.config.SkillRepositoryConfigEntry} list
     * — same wire format as {@code agentscope.json}'s {@code skillRepositories} section so
     * configs migrate either direction. Null when the agent uses only the implicit workspace
     * overlay.
     */
    @Lob
    @Column(name = "skill_repositories_json")
    private String skillRepositoriesJson;

    /**
     * Sandbox execution mode ({@code local} / {@code sandbox}); null falls back to the platform
     * default at runtime.
     */
    @Column(name = "sandbox_mode", length = 16)
    private String sandboxMode;

    /**
     * Sandbox isolation scope ({@code SESSION} / {@code USER} / {@code AGENT} / {@code GLOBAL});
     * only meaningful when {@link #sandboxMode} is {@code sandbox}.
     */
    @Column(name = "sandbox_scope", length = 16)
    private String sandboxScope;

    @Column(name = "created_at")
    private long createdAt;

    @Column(name = "updated_at")
    private long updatedAt;

    @OneToMany(
            mappedBy = "agent",
            cascade = CascadeType.ALL,
            orphanRemoval = true,
            fetch = FetchType.EAGER)
    private List<AgentShareEntity> shares = new ArrayList<>();

    public AgentEntity() {}

    public Long getRowId() {
        return rowId;
    }

    public void setRowId(Long rowId) {
        this.rowId = rowId;
    }

    public String getOwnerId() {
        return ownerId;
    }

    public void setOwnerId(String ownerId) {
        this.ownerId = ownerId;
    }

    public String getAgentId() {
        return agentId;
    }

    public void setAgentId(String agentId) {
        this.agentId = agentId;
    }

    public String getWorkspacePath() {
        return workspacePath;
    }

    public void setWorkspacePath(String workspacePath) {
        this.workspacePath = workspacePath;
    }

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

    public String getModel() {
        return model;
    }

    public void setModel(String model) {
        this.model = model;
    }

    public Integer getMaxIters() {
        return maxIters;
    }

    public void setMaxIters(Integer maxIters) {
        this.maxIters = maxIters;
    }

    public String getToolsAllowJson() {
        return toolsAllowJson;
    }

    public void setToolsAllowJson(String toolsAllowJson) {
        this.toolsAllowJson = toolsAllowJson;
    }

    public String getToolsDenyJson() {
        return toolsDenyJson;
    }

    public void setToolsDenyJson(String toolsDenyJson) {
        this.toolsDenyJson = toolsDenyJson;
    }

    public String getIdentityName() {
        return identityName;
    }

    public void setIdentityName(String identityName) {
        this.identityName = identityName;
    }

    public String getIdentityEmoji() {
        return identityEmoji;
    }

    public void setIdentityEmoji(String identityEmoji) {
        this.identityEmoji = identityEmoji;
    }

    public String getGroupChatMentionPatternsJson() {
        return groupChatMentionPatternsJson;
    }

    public void setGroupChatMentionPatternsJson(String groupChatMentionPatternsJson) {
        this.groupChatMentionPatternsJson = groupChatMentionPatternsJson;
    }

    public Boolean getGroupChatRequireMention() {
        return groupChatRequireMention;
    }

    public void setGroupChatRequireMention(Boolean groupChatRequireMention) {
        this.groupChatRequireMention = groupChatRequireMention;
    }

    public String getSkillsAllowJson() {
        return skillsAllowJson;
    }

    public void setSkillsAllowJson(String skillsAllowJson) {
        this.skillsAllowJson = skillsAllowJson;
    }

    public String getSkillsDenyJson() {
        return skillsDenyJson;
    }

    public void setSkillsDenyJson(String skillsDenyJson) {
        this.skillsDenyJson = skillsDenyJson;
    }

    public String getRunAs() {
        return runAs;
    }

    public void setRunAs(String runAs) {
        this.runAs = runAs;
    }

    public String getForkOf() {
        return forkOf;
    }

    public void setForkOf(String forkOf) {
        this.forkOf = forkOf;
    }

    public String getSkillRepositoriesJson() {
        return skillRepositoriesJson;
    }

    public void setSkillRepositoriesJson(String skillRepositoriesJson) {
        this.skillRepositoriesJson = skillRepositoriesJson;
    }

    public String getSandboxMode() {
        return sandboxMode;
    }

    public void setSandboxMode(String sandboxMode) {
        this.sandboxMode = sandboxMode;
    }

    public String getSandboxScope() {
        return sandboxScope;
    }

    public void setSandboxScope(String sandboxScope) {
        this.sandboxScope = sandboxScope;
    }

    public long getCreatedAt() {
        return createdAt;
    }

    public void setCreatedAt(long createdAt) {
        this.createdAt = createdAt;
    }

    public long getUpdatedAt() {
        return updatedAt;
    }

    public void setUpdatedAt(long updatedAt) {
        this.updatedAt = updatedAt;
    }

    public List<AgentShareEntity> getShares() {
        return shares;
    }

    public void setShares(List<AgentShareEntity> shares) {
        this.shares = shares;
    }
}

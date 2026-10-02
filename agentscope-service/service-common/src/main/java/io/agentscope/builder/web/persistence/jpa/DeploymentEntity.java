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

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.Table;

/**
 * A deployment binds an agent (optionally pinned to a version) and an execution environment to a
 * trigger — {@code cron}, {@code webhook}, or {@code manual} — so it can be fired automatically
 * or on demand without a human driving a chat session.
 */
@Entity
@Table(
        name = "builder_deployment",
        indexes = {
            @Index(name = "ix_builder_deployment_owner", columnList = "owner_id"),
            @Index(name = "ix_builder_deployment_agent", columnList = "agent_id"),
            @Index(name = "ix_builder_deployment_webhook_token", columnList = "webhook_token")
        })
public class DeploymentEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "row_id")
    private Long rowId;

    @Column(name = "deployment_id", length = 64, nullable = false, unique = true)
    private String deploymentId;

    @Column(name = "owner_id", length = 128, nullable = false)
    private String ownerId;

    @Column(name = "name", length = 200, nullable = false)
    private String name;

    @Column(name = "agent_id", length = 128, nullable = false)
    private String agentId;

    /** Pinned agent version, or {@code null} to always run the latest version. */
    @Column(name = "agent_version")
    private Integer agentVersion;

    @Column(name = "environment_id", length = 64, nullable = false)
    private String environmentId;

    /** {@code cron} | {@code webhook} | {@code manual} */
    @Column(name = "trigger_type", length = 32, nullable = false)
    private String triggerType;

    @Column(name = "cron_expression", length = 128)
    private String cronExpression;

    @Column(name = "webhook_token", length = 128, unique = true)
    private String webhookToken;

    @Column(name = "enabled", nullable = false)
    private boolean enabled;

    @Column(name = "last_run_at")
    private Long lastRunAt;

    @Column(name = "last_session_id", length = 64)
    private String lastSessionId;

    @Column(name = "last_status", length = 32)
    private String lastStatus;

    @Column(name = "created_at", nullable = false)
    private long createdAt;

    @Column(name = "updated_at", nullable = false)
    private long updatedAt;

    @Column(name = "archived_at")
    private Long archivedAt;

    public DeploymentEntity() {}

    public Long getRowId() {
        return rowId;
    }

    public void setRowId(Long rowId) {
        this.rowId = rowId;
    }

    public String getDeploymentId() {
        return deploymentId;
    }

    public void setDeploymentId(String deploymentId) {
        this.deploymentId = deploymentId;
    }

    public String getOwnerId() {
        return ownerId;
    }

    public void setOwnerId(String ownerId) {
        this.ownerId = ownerId;
    }

    public String getName() {
        return name;
    }

    public void setName(String name) {
        this.name = name;
    }

    public String getAgentId() {
        return agentId;
    }

    public void setAgentId(String agentId) {
        this.agentId = agentId;
    }

    public Integer getAgentVersion() {
        return agentVersion;
    }

    public void setAgentVersion(Integer agentVersion) {
        this.agentVersion = agentVersion;
    }

    public String getEnvironmentId() {
        return environmentId;
    }

    public void setEnvironmentId(String environmentId) {
        this.environmentId = environmentId;
    }

    public String getTriggerType() {
        return triggerType;
    }

    public void setTriggerType(String triggerType) {
        this.triggerType = triggerType;
    }

    public String getCronExpression() {
        return cronExpression;
    }

    public void setCronExpression(String cronExpression) {
        this.cronExpression = cronExpression;
    }

    public String getWebhookToken() {
        return webhookToken;
    }

    public void setWebhookToken(String webhookToken) {
        this.webhookToken = webhookToken;
    }

    public boolean isEnabled() {
        return enabled;
    }

    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
    }

    public Long getLastRunAt() {
        return lastRunAt;
    }

    public void setLastRunAt(Long lastRunAt) {
        this.lastRunAt = lastRunAt;
    }

    public String getLastSessionId() {
        return lastSessionId;
    }

    public void setLastSessionId(String lastSessionId) {
        this.lastSessionId = lastSessionId;
    }

    public String getLastStatus() {
        return lastStatus;
    }

    public void setLastStatus(String lastStatus) {
        this.lastStatus = lastStatus;
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

    public Long getArchivedAt() {
        return archivedAt;
    }

    public void setArchivedAt(Long archivedAt) {
        this.archivedAt = archivedAt;
    }
}

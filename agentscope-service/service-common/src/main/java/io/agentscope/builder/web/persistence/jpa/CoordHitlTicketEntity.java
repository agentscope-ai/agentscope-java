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
import jakarta.persistence.Lob;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;

/** Shared HITL tool-confirmation tickets. */
@Entity
@Table(
        name = "builder_coord_hitl",
        uniqueConstraints = {
            @UniqueConstraint(
                    name = "uk_builder_coord_hitl_session_tool",
                    columnNames = {"session_id", "tool_use_id"}),
            @UniqueConstraint(
                    name = "uk_builder_coord_hitl_execution_tool",
                    columnNames = {
                        "session_id",
                        "attempt_id",
                        "dispatch_generation",
                        "turn_id",
                        "tool_use_id"
                    })
        },
        indexes = {
            @Index(name = "ix_builder_coord_hitl_session", columnList = "session_id"),
            @Index(name = "ix_builder_coord_hitl_expires", columnList = "expires_at")
        })
public class CoordHitlTicketEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "row_id")
    private Long rowId;

    @Column(name = "tool_use_id", length = 255, nullable = false)
    private String toolUseId;

    @Column(name = "session_id", length = 255, nullable = false)
    private String sessionId;

    @Column(name = "owner_id", length = 128)
    private String ownerId;

    @Column(name = "approval_id", length = 255)
    private String approvalId;

    @Column(name = "agent_task_id", length = 255)
    private String agentTaskId;

    @Column(name = "attempt_id", length = 255)
    private String attemptId;

    @Column(name = "dispatch_generation", nullable = false)
    private long dispatchGeneration;

    @Column(name = "turn_id", length = 255)
    private String turnId;

    @Column(name = "continuation_lease_id", length = 255)
    private String continuationLeaseId;

    @Column(name = "tool_name", length = 255)
    private String toolName;

    @Lob
    @Column(name = "input_json")
    private String inputJson;

    @Column(name = "resolution_status", length = 32)
    private String resolutionStatus;

    @Column(name = "decision_version", nullable = false)
    private long decisionVersion;

    @Column(name = "resolved_allow")
    private Boolean resolvedAllow;

    @Column(name = "deny_message", length = 1024)
    private String denyMessage;

    @Column(name = "created_at", nullable = false)
    private long createdAt;

    @Column(name = "expires_at", nullable = false)
    private long expiresAt;

    @Column(name = "resolved_at")
    private Long resolvedAt;

    @Column(name = "continuation_ready")
    private Boolean continuationReady;

    public Long getRowId() {
        return rowId;
    }

    public void setRowId(Long rowId) {
        this.rowId = rowId;
    }

    public String getToolUseId() {
        return toolUseId;
    }

    public void setToolUseId(String toolUseId) {
        this.toolUseId = toolUseId;
    }

    public String getSessionId() {
        return sessionId;
    }

    public void setSessionId(String sessionId) {
        this.sessionId = sessionId;
    }

    public String getOwnerId() {
        return ownerId;
    }

    public void setOwnerId(String ownerId) {
        this.ownerId = ownerId;
    }

    public String getApprovalId() {
        return approvalId;
    }

    public void setApprovalId(String approvalId) {
        this.approvalId = approvalId;
    }

    public String getAgentTaskId() {
        return agentTaskId;
    }

    public void setAgentTaskId(String agentTaskId) {
        this.agentTaskId = agentTaskId;
    }

    public String getAttemptId() {
        return attemptId;
    }

    public void setAttemptId(String attemptId) {
        this.attemptId = attemptId;
    }

    public long getDispatchGeneration() {
        return dispatchGeneration;
    }

    public void setDispatchGeneration(long dispatchGeneration) {
        this.dispatchGeneration = dispatchGeneration;
    }

    public String getTurnId() {
        return turnId;
    }

    public void setTurnId(String turnId) {
        this.turnId = turnId;
    }

    public String getContinuationLeaseId() {
        return continuationLeaseId;
    }

    public void setContinuationLeaseId(String continuationLeaseId) {
        this.continuationLeaseId = continuationLeaseId;
    }

    public String getToolName() {
        return toolName;
    }

    public void setToolName(String toolName) {
        this.toolName = toolName;
    }

    public String getInputJson() {
        return inputJson;
    }

    public void setInputJson(String inputJson) {
        this.inputJson = inputJson;
    }

    public String getResolutionStatus() {
        return resolutionStatus;
    }

    public void setResolutionStatus(String resolutionStatus) {
        this.resolutionStatus = resolutionStatus;
    }

    public long getDecisionVersion() {
        return decisionVersion;
    }

    public void setDecisionVersion(long decisionVersion) {
        this.decisionVersion = decisionVersion;
    }

    public Boolean getResolvedAllow() {
        return resolvedAllow;
    }

    public void setResolvedAllow(Boolean resolvedAllow) {
        this.resolvedAllow = resolvedAllow;
    }

    public String getDenyMessage() {
        return denyMessage;
    }

    public void setDenyMessage(String denyMessage) {
        this.denyMessage = denyMessage;
    }

    public long getCreatedAt() {
        return createdAt;
    }

    public void setCreatedAt(long createdAt) {
        this.createdAt = createdAt;
    }

    public long getExpiresAt() {
        return expiresAt;
    }

    public void setExpiresAt(long expiresAt) {
        this.expiresAt = expiresAt;
    }

    public Long getResolvedAt() {
        return resolvedAt;
    }

    public void setResolvedAt(Long resolvedAt) {
        this.resolvedAt = resolvedAt;
    }

    public Boolean getContinuationReady() {
        return continuationReady;
    }

    public void setContinuationReady(Boolean continuationReady) {
        this.continuationReady = continuationReady;
    }
}

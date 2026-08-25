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
        uniqueConstraints =
                @UniqueConstraint(
                        name = "uk_builder_coord_hitl_tool",
                        columnNames = {"tool_use_id"}),
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

    @Column(name = "tool_name", length = 255)
    private String toolName;

    @Lob
    @Column(name = "input_json")
    private String inputJson;

    @Column(name = "resolved_allow")
    private Boolean resolvedAllow;

    @Column(name = "deny_message", length = 1024)
    private String denyMessage;

    @Column(name = "created_at", nullable = false)
    private long createdAt;

    @Column(name = "expires_at", nullable = false)
    private long expiresAt;

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
}

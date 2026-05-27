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
package io.agentscope.dataagent.web.persistence.jpa;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.Lob;
import jakarta.persistence.Table;

/**
 * One user-submitted nomination of a skill / subagent / memory snippet for promotion to the
 * shared workspace under admin approval.
 *
 * <p>Lifecycle: {@code PENDING} → {@code APPROVED} (payload materialized under
 * {@code ${dataagentHome}/shared/<target_type>/<target_path>}) or {@code REJECTED} (closed with a
 * reason). The payload snapshot is kept verbatim so re-approval after a workflow change still
 * lands the same content even if the contributor mutated their copy.
 */
@Entity
@Table(
        name = "dataagent_contribution",
        indexes = {
            @Index(name = "ix_dataagent_contribution_status", columnList = "status"),
            @Index(name = "ix_dataagent_contribution_source_user", columnList = "source_user_id"),
            @Index(
                    name = "ix_dataagent_contribution_target",
                    columnList = "target_type, target_path")
        })
public class ContributionEntity {

    public static final String STATUS_PENDING = "PENDING";
    public static final String STATUS_APPROVED = "APPROVED";
    public static final String STATUS_REJECTED = "REJECTED";

    public static final String TARGET_SKILL = "skill";
    public static final String TARGET_SUBAGENT = "subagent";
    public static final String TARGET_MEMORY = "memory";

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "id")
    private Long id;

    @Column(name = "status", length = 16, nullable = false)
    private String status = STATUS_PENDING;

    /** Contributor's user id. */
    @Column(name = "source_user_id", length = 128, nullable = false)
    private String sourceUserId;

    /** Contributor's agent id (the workspace the contribution was harvested from). */
    @Column(name = "source_agent_id", length = 128)
    private String sourceAgentId;

    /** One of {@link #TARGET_SKILL}, {@link #TARGET_SUBAGENT}, {@link #TARGET_MEMORY}. */
    @Column(name = "target_type", length = 32, nullable = false)
    private String targetType;

    /**
     * Workspace-relative path the payload should land at within
     * {@code ${dataagentHome}/shared/<target_type>/}. For skills this is the skill directory name
     * (e.g. {@code "cohort-builder"}); for subagents the subagent file name (e.g.
     * {@code "report-writer.md"}); for memory the snippet filename
     * (e.g. {@code "2026-05-22-cohort-rules.md"}).
     */
    @Column(name = "target_path", length = 512, nullable = false)
    private String targetPath;

    @Column(name = "rationale", length = 1024)
    private String rationale;

    /**
     * Verbatim snapshot of the payload at nomination time. For skills this is the SKILL.md
     * content (side resources are deferred to v2); for subagents the markdown body; for memory
     * snippets the raw text.
     */
    @Lob
    @Column(name = "payload", nullable = false)
    private String payload;

    @Column(name = "reviewer_user_id", length = 128)
    private String reviewerUserId;

    @Column(name = "reviewer_note", length = 1024)
    private String reviewerNote;

    @Column(name = "created_at", nullable = false)
    private long createdAt;

    @Column(name = "updated_at", nullable = false)
    private long updatedAt;

    public ContributionEntity() {}

    public Long getId() {
        return id;
    }

    public void setId(Long id) {
        this.id = id;
    }

    public String getStatus() {
        return status;
    }

    public void setStatus(String status) {
        this.status = status;
    }

    public String getSourceUserId() {
        return sourceUserId;
    }

    public void setSourceUserId(String sourceUserId) {
        this.sourceUserId = sourceUserId;
    }

    public String getSourceAgentId() {
        return sourceAgentId;
    }

    public void setSourceAgentId(String sourceAgentId) {
        this.sourceAgentId = sourceAgentId;
    }

    public String getTargetType() {
        return targetType;
    }

    public void setTargetType(String targetType) {
        this.targetType = targetType;
    }

    public String getTargetPath() {
        return targetPath;
    }

    public void setTargetPath(String targetPath) {
        this.targetPath = targetPath;
    }

    public String getRationale() {
        return rationale;
    }

    public void setRationale(String rationale) {
        this.rationale = rationale;
    }

    public String getPayload() {
        return payload;
    }

    public void setPayload(String payload) {
        this.payload = payload;
    }

    public String getReviewerUserId() {
        return reviewerUserId;
    }

    public void setReviewerUserId(String reviewerUserId) {
        this.reviewerUserId = reviewerUserId;
    }

    public String getReviewerNote() {
        return reviewerNote;
    }

    public void setReviewerNote(String reviewerNote) {
        this.reviewerNote = reviewerNote;
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
}

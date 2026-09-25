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
import jakarta.persistence.UniqueConstraint;

/** Durable hands work-queue rows + sandbox-ready metadata. */
@Entity
@Table(
        name = "builder_coord_work",
        uniqueConstraints =
                @UniqueConstraint(
                        name = "uk_builder_coord_work_lease",
                        columnNames = {"lease_id"}),
        indexes = {
            @Index(name = "ix_builder_coord_work_status_env", columnList = "status,environment_id"),
            @Index(name = "ix_builder_coord_work_session", columnList = "session_id")
        })
public class CoordWorkItemEntity {

    public static final String STATUS_QUEUED = "queued";
    public static final String STATUS_STARTING = "starting";
    public static final String STATUS_ACTIVE = "active";
    public static final String STATUS_STOPPING = "stopping";
    public static final String STATUS_STOPPED = "stopped";

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "row_id")
    private Long rowId;

    @Column(name = "lease_id", length = 64, nullable = false)
    private String leaseId;

    @Column(name = "session_id", length = 255, nullable = false)
    private String sessionId;

    @Column(name = "environment_id", length = 128, nullable = false)
    private String environmentId;

    @Column(name = "owner_id", length = 128, nullable = false)
    private String ownerId;

    @Column(name = "status", length = 32, nullable = false)
    private String status;

    @Column(name = "claimed_by", length = 255)
    private String claimedBy;

    @Column(name = "work_dir", length = 1024)
    private String workDir;

    @Column(name = "created_at", nullable = false)
    private long createdAt;

    @Column(name = "updated_at", nullable = false)
    private long updatedAt;

    public Long getRowId() {
        return rowId;
    }

    public void setRowId(Long rowId) {
        this.rowId = rowId;
    }

    public String getLeaseId() {
        return leaseId;
    }

    public void setLeaseId(String leaseId) {
        this.leaseId = leaseId;
    }

    public String getSessionId() {
        return sessionId;
    }

    public void setSessionId(String sessionId) {
        this.sessionId = sessionId;
    }

    public String getEnvironmentId() {
        return environmentId;
    }

    public void setEnvironmentId(String environmentId) {
        this.environmentId = environmentId;
    }

    public String getOwnerId() {
        return ownerId;
    }

    public void setOwnerId(String ownerId) {
        this.ownerId = ownerId;
    }

    public String getStatus() {
        return status;
    }

    public void setStatus(String status) {
        this.status = status;
    }

    public String getClaimedBy() {
        return claimedBy;
    }

    public void setClaimedBy(String claimedBy) {
        this.claimedBy = claimedBy;
    }

    public String getWorkDir() {
        return workDir;
    }

    public void setWorkDir(String workDir) {
        this.workDir = workDir;
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

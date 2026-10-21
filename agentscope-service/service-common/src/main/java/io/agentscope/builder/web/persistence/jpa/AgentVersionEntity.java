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

/**
 * Immutable configuration snapshot for one version of a user-custom agent. Head pointer lives on
 * {@link AgentEntity#getHeadVersion()}; updates create a new row and bump the head.
 */
@Entity
@Table(
        name = "builder_agent_version",
        uniqueConstraints =
                @UniqueConstraint(
                        name = "ux_builder_agent_version",
                        columnNames = {"owner_id", "agent_id", "version"}),
        indexes = {
            @Index(name = "ix_builder_agent_version_owner_agent", columnList = "owner_id, agent_id")
        })
public class AgentVersionEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "row_id")
    private Long rowId;

    @Column(name = "owner_id", length = 128, nullable = false)
    private String ownerId;

    @Column(name = "agent_id", length = 128, nullable = false)
    private String agentId;

    @Column(name = "version", nullable = false)
    private int version;

    /** Full JSON snapshot of the versioned configuration fields. */
    @Lob
    @Column(name = "snapshot_json", nullable = false)
    private String snapshotJson;

    @Column(name = "created_at", nullable = false)
    private long createdAt;

    public AgentVersionEntity() {}

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

    public int getVersion() {
        return version;
    }

    public void setVersion(int version) {
        this.version = version;
    }

    public String getSnapshotJson() {
        return snapshotJson;
    }

    public void setSnapshotJson(String snapshotJson) {
        this.snapshotJson = snapshotJson;
    }

    public long getCreatedAt() {
        return createdAt;
    }

    public void setCreatedAt(long createdAt) {
        this.createdAt = createdAt;
    }
}

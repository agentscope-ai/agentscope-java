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

/** Shared lease rows: turn ownership and deployment fire windows. */
@Entity
@Table(
        name = "builder_coord_lease",
        uniqueConstraints =
                @UniqueConstraint(
                        name = "uk_builder_coord_lease_key",
                        columnNames = {"lease_kind", "lease_key"}),
        indexes = {
            @Index(name = "ix_builder_coord_lease_expires", columnList = "expires_at"),
            @Index(name = "ix_builder_coord_lease_kind", columnList = "lease_kind")
        })
public class CoordLeaseEntity {

    public static final String KIND_TURN = "turn";
    public static final String KIND_FIRE = "fire";

    /** Cross-replica turn interrupt request keyed by session id. */
    public static final String KIND_INTERRUPT = "interrupt";

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "row_id")
    private Long rowId;

    @Column(name = "lease_kind", length = 32, nullable = false)
    private String leaseKind;

    /** Session id for turn leases; {@code deploymentId:fireWindow} for fire leases. */
    @Column(name = "lease_key", length = 255, nullable = false)
    private String leaseKey;

    @Column(name = "owner_id", length = 128)
    private String ownerId;

    @Column(name = "instance_id", length = 255, nullable = false)
    private String instanceId;

    @Column(name = "acquired_at", nullable = false)
    private long acquiredAt;

    @Column(name = "expires_at", nullable = false)
    private long expiresAt;

    public Long getRowId() {
        return rowId;
    }

    public void setRowId(Long rowId) {
        this.rowId = rowId;
    }

    public String getLeaseKind() {
        return leaseKind;
    }

    public void setLeaseKind(String leaseKind) {
        this.leaseKind = leaseKind;
    }

    public String getLeaseKey() {
        return leaseKey;
    }

    public void setLeaseKey(String leaseKey) {
        this.leaseKey = leaseKey;
    }

    public String getOwnerId() {
        return ownerId;
    }

    public void setOwnerId(String ownerId) {
        this.ownerId = ownerId;
    }

    public String getInstanceId() {
        return instanceId;
    }

    public void setInstanceId(String instanceId) {
        this.instanceId = instanceId;
    }

    public long getAcquiredAt() {
        return acquiredAt;
    }

    public void setAcquiredAt(long acquiredAt) {
        this.acquiredAt = acquiredAt;
    }

    public long getExpiresAt() {
        return expiresAt;
    }

    public void setExpiresAt(long expiresAt) {
        this.expiresAt = expiresAt;
    }
}

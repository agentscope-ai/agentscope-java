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
package io.agentscope.builder.portal.persistence;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.Table;

/** A fixed public cursor and small aggregate; resource histories are shared across snapshots. */
@Entity
@Table(
        name = "builder_portal_snapshot",
        indexes = {
            @Index(name = "ix_portal_snapshot_session", columnList = "session_id,expires_at"),
            @Index(name = "ix_portal_snapshot_expiry", columnList = "expires_at,id")
        })
public class PortalSnapshotEntity {
    @Id
    @Column(length = 64)
    public String id;

    @Column(name = "session_id", nullable = false, length = 256)
    public String sessionId;

    @Column(name = "turn_id", length = 64)
    public String turnId;

    @Column(nullable = false)
    public long sequence;

    @Column(name = "expires_at", nullable = false)
    public long expiresAt;

    @Column(nullable = false, columnDefinition = "TEXT")
    public String usage;
}

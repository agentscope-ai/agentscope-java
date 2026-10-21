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
import jakarta.persistence.UniqueConstraint;

/** Indexed read grants from a committed policy projection; never an independent authority. */
@Entity
@Table(
        name = "builder_portal_session_permission",
        indexes = {
            @Index(
                    name = "ix_portal_session_permission_page",
                    columnList =
                            "principal_kind,principal_id,tenant,namespace,created_at,session_id")
        },
        uniqueConstraints =
                @UniqueConstraint(
                        name = "uq_portal_session_permission",
                        columnNames = {"session_id", "principal_kind", "principal_id"}))
public class PortalSessionPermissionEntity {
    @Id
    @Column(length = 64)
    public String id;

    @Column(name = "session_id", nullable = false, length = 256)
    public String sessionId;

    @Column(name = "principal_kind", nullable = false, length = 32)
    public String principalKind;

    @Column(name = "principal_id", nullable = false, length = 256)
    public String principalId;

    @Column(nullable = false, length = 128)
    public String tenant;

    @Column(nullable = false, length = 128)
    public String namespace;

    @Column(name = "policy_id", length = 64)
    public String policyId;

    @Column(name = "policy_version", nullable = false)
    public long policyVersion;

    @Column(name = "valid_until", nullable = false)
    public long validUntil;

    @Column(name = "created_at", nullable = false)
    public long createdAt;
}

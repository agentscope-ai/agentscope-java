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
package io.agentcore.managed.server.agentservice.persistence;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.Table;

/** Prior resource values are retained only while a live pagination snapshot needs them. */
@Entity
@Table(
        name = "builder_portal_resource_version",
        indexes = {
            @Index(
                    name = "ix_portal_resource_version_page",
                    columnList = "session_id,kind,resource_key,valid_from,valid_through"),
            @Index(name = "ix_portal_resource_version_expiry", columnList = "expires_at,id"),
            @Index(
                    name = "ix_portal_resource_version_turn_page",
                    columnList = "session_id,kind,turn_id,resource_key,valid_from,valid_through")
        })
public class AgentServiceResourceVersionEntity {
    @Id
    @Column(length = 64)
    public String id;

    @Column(name = "session_id", nullable = false, length = 256)
    public String sessionId;

    @Column(name = "turn_id", length = 64)
    public String turnId;

    @Column(nullable = false, length = 32)
    public String kind;

    @Column(name = "resource_key", nullable = false, length = 512)
    public String resourceKey;

    @Column(name = "valid_from", nullable = false)
    public long validFrom;

    @Column(name = "valid_through", nullable = false)
    public long validThrough;

    @Column(name = "expires_at", nullable = false)
    public long expiresAt;

    @Column(nullable = false, columnDefinition = "TEXT")
    public String data;
}

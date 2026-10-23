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
import jakarta.persistence.UniqueConstraint;
import java.time.Instant;

/**
 * Persistent representation of a single user-owned skill marketplace.
 *
 * <p>Differs from claw's {@code marketplaces} block in {@code agentscope.json}: in builder every
 * marketplace belongs to exactly one user and is invisible to others. The {@code (user_id,
 * marketplace_id)} pair is unique — a user cannot have two marketplaces with the same id, but two
 * different users may reuse the same id without collision.
 */
@Entity
@Table(
        name = "dataagent_user_marketplace",
        uniqueConstraints =
                @UniqueConstraint(
                        name = "uk_user_marketplace",
                        columnNames = {"user_id", "marketplace_id"}),
        indexes = {@Index(name = "ix_user_marketplace_user", columnList = "user_id")})
public class UserMarketplaceEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "id")
    private Long id;

    @Column(name = "user_id", length = 128, nullable = false)
    private String userId;

    @Column(name = "marketplace_id", length = 128, nullable = false)
    private String marketplaceId;

    @Column(name = "type", length = 32, nullable = false)
    private String type;

    /**
     * Provider properties serialised as JSON. We store the raw JSON to keep schema neutral across
     * MySQL/PostgreSQL/H2 — Hibernate's JSON column types vary between dialects.
     */
    @Lob
    @Column(name = "properties_json")
    private String propertiesJson;

    @Column(name = "created_at")
    private Instant createdAt = Instant.now();

    @Column(name = "updated_at")
    private Instant updatedAt = Instant.now();

    public UserMarketplaceEntity() {}

    public UserMarketplaceEntity(
            String userId, String marketplaceId, String type, String propertiesJson) {
        this.userId = userId;
        this.marketplaceId = marketplaceId;
        this.type = type;
        this.propertiesJson = propertiesJson;
    }

    public Long getId() {
        return id;
    }

    public String getUserId() {
        return userId;
    }

    public void setUserId(String userId) {
        this.userId = userId;
    }

    public String getMarketplaceId() {
        return marketplaceId;
    }

    public void setMarketplaceId(String marketplaceId) {
        this.marketplaceId = marketplaceId;
    }

    public String getType() {
        return type;
    }

    public void setType(String type) {
        this.type = type;
    }

    public String getPropertiesJson() {
        return propertiesJson;
    }

    public void setPropertiesJson(String propertiesJson) {
        this.propertiesJson = propertiesJson;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public void setCreatedAt(Instant createdAt) {
        this.createdAt = createdAt;
    }

    public Instant getUpdatedAt() {
        return updatedAt;
    }

    public void setUpdatedAt(Instant updatedAt) {
        this.updatedAt = updatedAt;
    }
}

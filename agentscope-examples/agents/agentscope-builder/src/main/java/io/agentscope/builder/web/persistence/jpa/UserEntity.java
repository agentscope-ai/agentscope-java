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
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.Table;
import java.time.Instant;

/**
 * Persistent representation of a builder user. Keyed by stable {@code userId} (the same value
 * used as the HarnessAgent namespace key on disk in file mode) so migration from a JSON store
 * preserves all references.
 *
 * <p>Roles are stored as a comma-separated string to keep the schema portable across MySQL,
 * PostgreSQL and H2 without introducing a join table. The {@code username} column is unique and
 * indexed for fast {@code findByUsername} lookups.
 */
@Entity
@Table(
        name = "builder_user",
        indexes = {
            @Index(name = "ix_builder_user_username", columnList = "username", unique = true)
        })
public class UserEntity {

    @Id
    @Column(name = "user_id", length = 128, nullable = false)
    private String userId;

    @Column(name = "username", length = 191, nullable = false)
    private String username;

    @Column(name = "password_hash", length = 200, nullable = false)
    private String passwordHash;

    /**
     * Comma-separated role list. {@code null} or empty is treated as {@code "user"} at the API
     * boundary.
     */
    @Column(name = "roles_csv", length = 500)
    private String rolesCsv;

    @Column(name = "created_at")
    private Instant createdAt = Instant.now();

    public UserEntity() {}

    public UserEntity(String userId, String username, String passwordHash, String rolesCsv) {
        this.userId = userId;
        this.username = username;
        this.passwordHash = passwordHash;
        this.rolesCsv = rolesCsv;
    }

    public String getUserId() {
        return userId;
    }

    public void setUserId(String userId) {
        this.userId = userId;
    }

    public String getUsername() {
        return username;
    }

    public void setUsername(String username) {
        this.username = username;
    }

    public String getPasswordHash() {
        return passwordHash;
    }

    public void setPasswordHash(String passwordHash) {
        this.passwordHash = passwordHash;
    }

    public String getRolesCsv() {
        return rolesCsv;
    }

    public void setRolesCsv(String rolesCsv) {
        this.rolesCsv = rolesCsv;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public void setCreatedAt(Instant createdAt) {
        this.createdAt = createdAt;
    }
}

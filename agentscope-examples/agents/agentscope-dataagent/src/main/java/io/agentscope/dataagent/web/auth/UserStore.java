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
package io.agentscope.dataagent.web.auth;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import java.util.List;
import java.util.Optional;
import org.springframework.security.crypto.password.PasswordEncoder;

/**
 * Abstraction over the user registry.
 *
 * <p>The only bundled implementation is
 * {@link io.agentscope.dataagent.web.persistence.jpa.JpaUserStore}, which persists via Spring Data
 * JPA. Out of the box the default DataSource is embedded H2 in file mode at
 * {@code ${user.home}/.agentscope-builder/db}; activate the {@code jdbc} Spring profile (or
 * set {@code BUILDER_DB_URL}) to switch to MySQL or PostgreSQL. The MySQL and PostgreSQL JDBC
 * drivers ship at runtime scope.
 *
 * <p>Implementations are expected to be thread-safe and seed a default {@code admin} user on
 * first start when the store is empty.
 */
public interface UserStore {

    /** Returns the password encoder used for hashing and verification. */
    PasswordEncoder passwordEncoder();

    /** Finds a user by stable identifier. */
    Optional<UserRecord> findById(String userId);

    /** Finds a user by username (case-insensitive). */
    Optional<UserRecord> findByUsername(String username);

    /** Returns every registered user. The returned list is an immutable snapshot. */
    List<UserRecord> listAll();

    /**
     * Creates a new user. Throws {@link IllegalArgumentException} if the userId or username is
     * already taken.
     */
    UserRecord createUser(String userId, String username, String password, List<String> roles);

    /** Verifies a plain-text password against a stored {@link UserRecord}'s hash. */
    boolean verifyPassword(UserRecord user, String rawPassword);

    /** Updates the password of an existing user. Returns the updated record, or empty if absent. */
    Optional<UserRecord> updatePassword(String userId, String newPassword);

    /** Updates the roles of an existing user. Returns the updated record, or empty if absent. */
    Optional<UserRecord> updateRoles(String userId, List<String> newRoles);

    /**
     * Deletes a user by userId. Returns {@code true} if the user existed and was removed. The
     * last admin cannot be deleted ({@link IllegalStateException}).
     */
    boolean deleteUser(String userId);

    /**
     * JSON-serializable user record.
     *
     * @param userId stable unique identifier (used as HarnessAgent namespace key)
     * @param username display name
     * @param passwordHash BCrypt hash
     * @param roles list of role strings (e.g. {@code ["user"]}, {@code ["user","admin"]})
     */
    @JsonIgnoreProperties(ignoreUnknown = true)
    record UserRecord(String userId, String username, String passwordHash, List<String> roles) {
        public boolean hasRole(String role) {
            return roles != null && roles.contains(role);
        }
    }
}

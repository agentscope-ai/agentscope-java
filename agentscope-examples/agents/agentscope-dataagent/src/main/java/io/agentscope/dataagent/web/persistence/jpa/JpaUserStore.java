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

import io.agentscope.dataagent.web.auth.UserStore;
import jakarta.annotation.PostConstruct;
import java.util.Arrays;
import java.util.List;
import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.transaction.annotation.Transactional;

/**
 * JPA-backed {@link UserStore}. This is the only {@link UserStore} implementation shipped with
 * the builder; it is always wired in by {@link JpaPersistenceConfig}.
 *
 * <p>On first start, if the {@code dataagent_user} table is empty, a default {@code admin/admin}
 * user is seeded so an operator can sign in immediately after pointing the dataagent at a fresh
 * database. The seeded password is logged with a "change immediately" warning, matching the
 * file-mode behavior.
 *
 * <p>Wired through {@link io.agentscope.dataagent.web.persistence.jpa.JpaPersistenceConfig};
 * declared as a plain {@code @Service}-flavored Spring bean (annotated via the configuration
 * class rather than {@code @Component} so the JPA backend stays inert when the configuration
 * does not load).
 */
@Transactional
public class JpaUserStore implements UserStore {

    private static final Logger log = LoggerFactory.getLogger(JpaUserStore.class);

    private final UserEntityRepository repository;
    private final PasswordEncoder encoder = new BCryptPasswordEncoder();

    public JpaUserStore(UserEntityRepository repository) {
        this.repository = repository;
    }

    @PostConstruct
    void seedDefaultAdmin() {
        // existsById, not count() > 0, so the admin row is created even if other seed scripts
        // (e.g. the H2-only data-h2.sql that adds bob / alice for local dev) populated the table
        // before this @PostConstruct fired. Re-running on an already-seeded admin is a no-op.
        if (repository.existsById("admin")) {
            return;
        }
        UserEntity admin = new UserEntity("admin", "admin", encoder.encode("admin"), "user,admin");
        repository.save(admin);
        log.info(
                "Initialized default admin user in database (password: admin) — change"
                        + " immediately!");
    }

    @Override
    public PasswordEncoder passwordEncoder() {
        return encoder;
    }

    @Override
    @Transactional(readOnly = true)
    public Optional<UserRecord> findById(String userId) {
        return repository.findById(userId).map(JpaUserStore::toRecord);
    }

    @Override
    @Transactional(readOnly = true)
    public Optional<UserRecord> findByUsername(String username) {
        if (username == null) return Optional.empty();
        return repository.findByUsernameIgnoreCase(username).map(JpaUserStore::toRecord);
    }

    @Override
    @Transactional(readOnly = true)
    public List<UserRecord> listAll() {
        return repository.findAll().stream().map(JpaUserStore::toRecord).toList();
    }

    @Override
    public UserRecord createUser(
            String userId, String username, String password, List<String> roles) {
        if (repository.existsById(userId)) {
            throw new IllegalArgumentException("userId already exists: " + userId);
        }
        if (repository.existsByUsernameIgnoreCase(username)) {
            throw new IllegalArgumentException("username already exists: " + username);
        }
        List<String> effectiveRoles = roles != null ? roles : List.of("user");
        UserEntity entity =
                new UserEntity(
                        userId, username, encoder.encode(password), joinRoles(effectiveRoles));
        repository.save(entity);
        return toRecord(entity);
    }

    @Override
    public boolean verifyPassword(UserRecord user, String rawPassword) {
        return encoder.matches(rawPassword, user.passwordHash());
    }

    @Override
    public Optional<UserRecord> updatePassword(String userId, String newPassword) {
        return repository
                .findById(userId)
                .map(
                        u -> {
                            u.setPasswordHash(encoder.encode(newPassword));
                            UserEntity saved = repository.save(u);
                            log.info("Password updated for user '{}'", userId);
                            return toRecord(saved);
                        });
    }

    @Override
    public Optional<UserRecord> updateRoles(String userId, List<String> newRoles) {
        return repository
                .findById(userId)
                .map(
                        u -> {
                            u.setRolesCsv(joinRoles(newRoles));
                            return toRecord(repository.save(u));
                        });
    }

    @Override
    public boolean deleteUser(String userId) {
        Optional<UserEntity> target = repository.findById(userId);
        if (target.isEmpty()) return false;
        if (hasRole(target.get(), "admin")) {
            long admins = repository.findAll().stream().filter(u -> hasRole(u, "admin")).count();
            if (admins <= 1) {
                throw new IllegalStateException("Cannot delete the last admin user");
            }
        }
        repository.deleteById(userId);
        log.info("Deleted user '{}'", userId);
        return true;
    }

    // -----------------------------------------------------------------
    //  Mapping helpers
    // -----------------------------------------------------------------

    private static UserRecord toRecord(UserEntity u) {
        return new UserRecord(
                u.getUserId(), u.getUsername(), u.getPasswordHash(), splitRoles(u.getRolesCsv()));
    }

    private static List<String> splitRoles(String csv) {
        if (csv == null || csv.isBlank()) return List.of("user");
        return Arrays.stream(csv.split(",")).map(String::trim).filter(s -> !s.isEmpty()).toList();
    }

    private static String joinRoles(List<String> roles) {
        if (roles == null || roles.isEmpty()) return "user";
        return String.join(",", roles);
    }

    private static boolean hasRole(UserEntity u, String role) {
        return splitRoles(u.getRolesCsv()).contains(role);
    }
}

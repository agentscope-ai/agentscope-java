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
package io.agentscope.builder.web.auth;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.locks.ReentrantReadWriteLock;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Component;

/**
 * JSON-file-backed user registry for the claw web application.
 *
 * <p>Persists user records to {@code ${cwd}/.agentscope/users.json}. Thread-safe via a
 * read-write lock. On first start, creates a default {@code admin} user if the file does not exist.
 *
 * <p>User records contain:
 * <ul>
 *   <li>{@code userId} — stable unique identifier (used as HarnessAgent namespace key)
 *   <li>{@code username} — display name
 *   <li>{@code passwordHash} — BCrypt hash
 *   <li>{@code roles} — list of role strings (e.g. {@code ["user"]}, {@code ["user","admin"]})
 * </ul>
 */
@Component
public class UserStore {

    private static final Logger log = LoggerFactory.getLogger(UserStore.class);
    private static final ObjectMapper MAPPER =
            new ObjectMapper().enable(SerializationFeature.INDENT_OUTPUT);

    /** JSON-serializable user record. */
    @JsonIgnoreProperties(ignoreUnknown = true)
    public record UserRecord(
            String userId, String username, String passwordHash, List<String> roles) {

        public boolean hasRole(String role) {
            return roles != null && roles.contains(role);
        }
    }

    private final Path storeFile;
    private final PasswordEncoder encoder = new BCryptPasswordEncoder();
    private final ReentrantReadWriteLock lock = new ReentrantReadWriteLock();
    private List<UserRecord> users = new ArrayList<>();

    public UserStore() {
        this(
                Paths.get(System.getProperty("user.dir"))
                        .resolve(".agentscope")
                        .resolve("users.json"));
    }

    public UserStore(Path storeFile) {
        this.storeFile = storeFile;
        load();
    }

    /** Returns the password encoder used for hashing and verification. */
    public PasswordEncoder passwordEncoder() {
        return encoder;
    }

    /**
     * Finds a user by {@code userId}.
     */
    public Optional<UserRecord> findById(String userId) {
        lock.readLock().lock();
        try {
            return users.stream().filter(u -> u.userId().equals(userId)).findFirst();
        } finally {
            lock.readLock().unlock();
        }
    }

    /**
     * Finds a user by {@code username} (case-insensitive).
     */
    public Optional<UserRecord> findByUsername(String username) {
        if (username == null) return Optional.empty();
        String lower = username.toLowerCase();
        lock.readLock().lock();
        try {
            return users.stream()
                    .filter(u -> u.username() != null && u.username().toLowerCase().equals(lower))
                    .findFirst();
        } finally {
            lock.readLock().unlock();
        }
    }

    /**
     * Returns all user records.
     */
    public List<UserRecord> listAll() {
        lock.readLock().lock();
        try {
            return List.copyOf(users);
        } finally {
            lock.readLock().unlock();
        }
    }

    /**
     * Creates a new user. Throws {@link IllegalArgumentException} if the userId or username is
     * already taken.
     */
    public UserRecord createUser(
            String userId, String username, String password, List<String> roles) {
        lock.writeLock().lock();
        try {
            if (users.stream().anyMatch(u -> u.userId().equals(userId))) {
                throw new IllegalArgumentException("userId already exists: " + userId);
            }
            if (users.stream()
                    .anyMatch(
                            u -> u.username() != null && u.username().equalsIgnoreCase(username))) {
                throw new IllegalArgumentException("username already exists: " + username);
            }
            UserRecord record =
                    new UserRecord(
                            userId,
                            username,
                            encoder.encode(password),
                            roles != null ? List.copyOf(roles) : List.of("user"));
            users.add(record);
            flush();
            return record;
        } finally {
            lock.writeLock().unlock();
        }
    }

    /**
     * Verifies a plain-text password against a stored {@link UserRecord}'s hash.
     */
    public boolean verifyPassword(UserRecord user, String rawPassword) {
        return encoder.matches(rawPassword, user.passwordHash());
    }

    /**
     * Updates the password of an existing user. Returns the updated record, or empty if not found.
     */
    public Optional<UserRecord> updatePassword(String userId, String newPassword) {
        lock.writeLock().lock();
        try {
            for (int i = 0; i < users.size(); i++) {
                UserRecord u = users.get(i);
                if (u.userId().equals(userId)) {
                    UserRecord updated =
                            new UserRecord(
                                    u.userId(),
                                    u.username(),
                                    encoder.encode(newPassword),
                                    u.roles());
                    users.set(i, updated);
                    flush();
                    log.info("Password updated for user '{}'", userId);
                    return Optional.of(updated);
                }
            }
            return Optional.empty();
        } finally {
            lock.writeLock().unlock();
        }
    }

    /**
     * Updates the roles of an existing user. Returns the updated record, or empty if not found.
     */
    public Optional<UserRecord> updateRoles(String userId, List<String> newRoles) {
        lock.writeLock().lock();
        try {
            for (int i = 0; i < users.size(); i++) {
                UserRecord u = users.get(i);
                if (u.userId().equals(userId)) {
                    UserRecord updated =
                            new UserRecord(
                                    u.userId(),
                                    u.username(),
                                    u.passwordHash(),
                                    List.copyOf(newRoles));
                    users.set(i, updated);
                    flush();
                    return Optional.of(updated);
                }
            }
            return Optional.empty();
        } finally {
            lock.writeLock().unlock();
        }
    }

    /**
     * Deletes a user by userId. Returns {@code true} if the user existed and was removed.
     * The last admin cannot be deleted.
     */
    public boolean deleteUser(String userId) {
        lock.writeLock().lock();
        try {
            Optional<UserRecord> target =
                    users.stream().filter(u -> u.userId().equals(userId)).findFirst();
            if (target.isEmpty()) return false;
            // Guard: don't delete the last admin
            boolean isAdmin = target.get().hasRole("admin");
            if (isAdmin) {
                long adminCount = users.stream().filter(u -> u.hasRole("admin")).count();
                if (adminCount <= 1) {
                    throw new IllegalStateException("Cannot delete the last admin user");
                }
            }
            users.removeIf(u -> u.userId().equals(userId));
            flush();
            log.info("Deleted user '{}'", userId);
            return true;
        } finally {
            lock.writeLock().unlock();
        }
    }

    // -----------------------------------------------------------------
    //  Internal
    // -----------------------------------------------------------------

    private void load() {
        lock.writeLock().lock();
        try {
            if (!Files.isRegularFile(storeFile)) {
                initDefaults();
                return;
            }
            String json = Files.readString(storeFile, StandardCharsets.UTF_8);
            if (json.isBlank()) {
                initDefaults();
                return;
            }
            users =
                    new ArrayList<>(
                            MAPPER.readValue(json, new TypeReference<List<UserRecord>>() {}));
            log.info("Loaded {} users from {}", users.size(), storeFile);
        } catch (IOException e) {
            log.warn("Failed to load user store from {}: {}", storeFile, e.getMessage());
            initDefaults();
        } finally {
            lock.writeLock().unlock();
        }
    }

    private void initDefaults() {
        users = new ArrayList<>();
        UserRecord admin =
                new UserRecord("admin", "admin", encoder.encode("admin"), List.of("user", "admin"));
        users.add(admin);
        log.info("Initialized default admin user (password: admin) — change immediately!");
        flush();
    }

    private void flush() {
        try {
            Files.createDirectories(storeFile.getParent());
            Path tmp = storeFile.resolveSibling(storeFile.getFileName() + ".tmp");
            byte[] bytes = MAPPER.writeValueAsBytes(users);
            Files.write(
                    tmp, bytes, StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING);
            Files.move(
                    tmp,
                    storeFile,
                    java.nio.file.StandardCopyOption.REPLACE_EXISTING,
                    java.nio.file.StandardCopyOption.ATOMIC_MOVE);
        } catch (IOException e) {
            log.warn("Failed to flush user store to {}: {}", storeFile, e.getMessage());
        }
    }
}

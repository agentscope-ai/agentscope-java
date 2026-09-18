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
package io.agentscope.extensions.postgresql.sandbox;

import io.agentscope.harness.agent.sandbox.SandboxExecutionGuard;
import io.agentscope.harness.agent.sandbox.SandboxIsolationKey;
import io.agentscope.harness.agent.sandbox.SandboxLease;
import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Duration;
import java.util.Objects;
import javax.sql.DataSource;
import org.apache.commons.codec.digest.MurmurHash3;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * PostgreSQL advisory-lock-based {@link SandboxExecutionGuard}.
 *
 * <p>Each lock is identified by a 64-bit key derived from the {@link SandboxIsolationKey}'s scope
 * and value via MurmurHash3. The lock is acquired with {@code pg_advisory_lock(key)} and released
 * with {@code pg_advisory_unlock(key)}.
 *
 * <p>The lock is tied to the database connection — when the connection closes, the lock is
 * automatically released. The returned {@link SandboxLease#close()} calls
 * {@code pg_advisory_unlock(key)} and closes the underlying connection.
 *
 * <p>A configurable timeout is supported by polling {@code pg_try_advisory_lock(key)} until the
 * lock is acquired or the timeout expires, matching the semantics of MySQL's
 * {@code GET_LOCK(name, timeout)}.
 */
public final class PostgresSandboxExecutionGuard implements SandboxExecutionGuard {

    private static final Logger log = LoggerFactory.getLogger(PostgresSandboxExecutionGuard.class);

    private static final String DEFAULT_KEY_PREFIX = "agentscope:sandbox:lock:";

    private final DataSource dataSource;
    private final String keyPrefix;
    private final int lockTimeoutSeconds;

    private PostgresSandboxExecutionGuard(Builder builder) {
        this.dataSource = builder.dataSource;
        this.keyPrefix = builder.keyPrefix;
        this.lockTimeoutSeconds = builder.lockTimeoutSeconds;
    }

    public static Builder builder(DataSource dataSource) {
        return new Builder(dataSource);
    }

    @Override
    public SandboxLease tryEnter(SandboxIsolationKey key) throws InterruptedException {
        long lockName = composeLockKey(key);
        log.debug("[sandbox-guard] Acquiring PostgreSQL advisory lock: {}", lockName);

        long deadline = System.nanoTime() + Duration.ofSeconds(lockTimeoutSeconds).toNanos();
        try {
            Connection conn = dataSource.getConnection();
            try {
                while (true) {
                    try (PreparedStatement ps =
                            conn.prepareStatement("SELECT pg_try_advisory_lock(?)")) {
                        ps.setLong(1, lockName);
                        try (ResultSet rs = ps.executeQuery()) {
                            if (rs.next() && rs.getBoolean(1)) {
                                log.debug(
                                        "[sandbox-guard] Acquired PostgreSQL advisory lock: {}",
                                        lockName);
                                return new PostgresLease(conn, lockName);
                            }
                        }
                    }

                    if (System.nanoTime() >= deadline) {
                        conn.close();
                        throw new InterruptedException(
                                "Timed out waiting for PostgreSQL advisory lock: "
                                        + lockName
                                        + " (timeout="
                                        + lockTimeoutSeconds
                                        + "s)");
                    }

                    Thread.sleep(100L);
                }
            } catch (InterruptedException e) {
                try {
                    conn.close();
                } catch (SQLException closeEx) {
                    log.warn(
                            "[sandbox-guard] Failed to close lock connection after interrupt: {}",
                            closeEx.getMessage());
                }
                throw e;
            } catch (SQLException e) {
                try {
                    conn.close();
                } catch (SQLException closeEx) {
                    log.warn(
                            "[sandbox-guard] Failed to close lock connection: {}",
                            closeEx.getMessage());
                }
                throw new RuntimeException(
                        "Failed to acquire PostgreSQL advisory lock: " + lockName, e);
            }
        } catch (SQLException e) {
            throw new RuntimeException(
                    "Failed to acquire PostgreSQL advisory lock: " + lockName, e);
        }
    }

    private long composeLockKey(SandboxIsolationKey key) {
        String raw = keyPrefix + key.getScope().name().toLowerCase() + ":" + key.getValue();
        long[] hash = MurmurHash3.hash128(raw.getBytes(StandardCharsets.UTF_8));
        return hash[0];
    }

    private static final class PostgresLease implements SandboxLease {

        private final Connection conn;
        private final long lockName;

        PostgresLease(Connection conn, long lockName) {
            this.conn = conn;
            this.lockName = lockName;
        }

        @Override
        public void close() {
            try (PreparedStatement ps = conn.prepareStatement("SELECT pg_advisory_unlock(?)")) {
                ps.setLong(1, lockName);
                ps.executeQuery();
                log.debug("[sandbox-guard] Released PostgreSQL advisory lock: {}", lockName);
            } catch (Exception e) {
                log.warn(
                        "[sandbox-guard] Failed to release PostgreSQL advisory lock {}: {}",
                        lockName,
                        e.getMessage(),
                        e);
            } finally {
                try {
                    conn.close();
                } catch (SQLException e) {
                    log.warn("[sandbox-guard] Failed to close lock connection: {}", e.getMessage());
                }
            }
        }
    }

    public static final class Builder {

        private static final int DEFAULT_LOCK_TIMEOUT_SECONDS = 1800; // 30 minutes

        private final DataSource dataSource;
        private String keyPrefix = DEFAULT_KEY_PREFIX;
        private int lockTimeoutSeconds = DEFAULT_LOCK_TIMEOUT_SECONDS;

        private Builder(DataSource dataSource) {
            this.dataSource = Objects.requireNonNull(dataSource, "dataSource");
        }

        public Builder keyPrefix(String keyPrefix) {
            if (keyPrefix == null || keyPrefix.isBlank()) {
                throw new IllegalArgumentException("keyPrefix must not be blank");
            }
            this.keyPrefix = keyPrefix.endsWith(":") ? keyPrefix : keyPrefix + ":";
            return this;
        }

        public Builder lockTimeout(Duration timeout) {
            Objects.requireNonNull(timeout, "timeout");
            if (timeout.isNegative() || timeout.isZero()) {
                throw new IllegalArgumentException("timeout must be positive");
            }
            this.lockTimeoutSeconds = (int) timeout.toSeconds();
            return this;
        }

        public PostgresSandboxExecutionGuard build() {
            return new PostgresSandboxExecutionGuard(this);
        }
    }
}

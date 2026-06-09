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
package io.agentscope.extensions.mysql.sandbox;

import io.agentscope.harness.agent.sandbox.SandboxExecutionGuard;
import io.agentscope.harness.agent.sandbox.SandboxIsolationKey;
import io.agentscope.harness.agent.sandbox.SandboxLease;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Duration;
import java.util.Objects;
import javax.sql.DataSource;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * JDBC-backed {@link SandboxExecutionGuard} using MySQL {@code GET_LOCK()} / {@code RELEASE_LOCK()}.
 *
 * <p>Each lock is identified by a string key derived from the {@link SandboxIsolationKey}'s scope
 * and value. {@code GET_LOCK()} blocks until the lock is available or the timeout expires.
 * The lock is tied to the database connection — when the connection closes, the lock is
 * automatically released.
 *
 * <p>The returned {@link SandboxLease#close()} calls {@code RELEASE_LOCK()} and closes the
 * underlying connection, ensuring the lock is freed even if the caller forgets to release it.
 *
 * <p><b>Important:</b> MySQL named locks are server-scoped, not database-scoped. Use a unique
 * {@code keyPrefix} to avoid collisions with other applications sharing the same MySQL server.
 */
public final class JdbcSandboxExecutionGuard implements SandboxExecutionGuard {

    private static final Logger log = LoggerFactory.getLogger(JdbcSandboxExecutionGuard.class);

    private static final String DEFAULT_KEY_PREFIX = "agentscope:sandbox:lock:";

    private final DataSource dataSource;
    private final String keyPrefix;
    private final int lockTimeoutSeconds;

    private JdbcSandboxExecutionGuard(Builder builder) {
        this.dataSource = builder.dataSource;
        this.keyPrefix = builder.keyPrefix;
        this.lockTimeoutSeconds = builder.lockTimeoutSeconds;
    }

    public static Builder builder(DataSource dataSource) {
        return new Builder(dataSource);
    }

    @Override
    public SandboxLease tryEnter(SandboxIsolationKey key) throws InterruptedException {
        String lockName = composeLockName(key);
        log.debug("[sandbox-guard] Acquiring MySQL lock: {}", lockName);

        try {
            Connection conn = dataSource.getConnection();
            try (PreparedStatement ps = conn.prepareStatement("SELECT GET_LOCK(?, ?)")) {
                ps.setString(1, lockName);
                ps.setInt(2, lockTimeoutSeconds);
                try (ResultSet rs = ps.executeQuery()) {
                    if (rs.next() && rs.getInt(1) == 1) {
                        log.debug("[sandbox-guard] Acquired MySQL lock: {}", lockName);
                        return new MysqlLease(conn, lockName);
                    }
                }
            } catch (Exception e) {
                conn.close();
                throw e;
            }
            conn.close();
            throw new InterruptedException(
                    "Timed out waiting for MySQL lock: "
                            + lockName
                            + " (timeout="
                            + lockTimeoutSeconds
                            + "s)");
        } catch (InterruptedException e) {
            throw e;
        } catch (SQLException e) {
            throw new RuntimeException("Failed to acquire MySQL lock: " + lockName, e);
        }
    }

    private String composeLockName(SandboxIsolationKey key) {
        String raw = keyPrefix + key.getScope().name().toLowerCase() + ":" + key.getValue();
        if (raw.length() <= 64) {
            return raw;
        }
        // MySQL GET_LOCK() supports max 64 characters; hash if longer
        int hash = raw.hashCode();
        return raw.substring(0, 50) + ":" + Integer.toHexString(hash);
    }

    private static final class MysqlLease implements SandboxLease {

        private final Connection conn;
        private final String lockName;

        MysqlLease(Connection conn, String lockName) {
            this.conn = conn;
            this.lockName = lockName;
        }

        @Override
        public void close() {
            try {
                try (PreparedStatement ps = conn.prepareStatement("SELECT RELEASE_LOCK(?)")) {
                    ps.setString(1, lockName);
                    ps.executeQuery();
                    log.debug("[sandbox-guard] Released MySQL lock: {}", lockName);
                }
            } catch (Exception e) {
                log.warn(
                        "[sandbox-guard] Failed to release MySQL lock {}: {}",
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

        public JdbcSandboxExecutionGuard build() {
            return new JdbcSandboxExecutionGuard(this);
        }
    }
}

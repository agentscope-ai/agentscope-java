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
package io.agentscope.extensions.jdbc.dialect;

import io.agentscope.harness.agent.sandbox.SandboxLease;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.Objects;
import javax.sql.DataSource;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * MySQL-native {@link SandboxLockStrategy} using {@code GET_LOCK()} /
 * {@code RELEASE_LOCK()}.
 *
 * <p>MySQL named locks are server-scoped (not database-scoped) and tied to the
 * connection — the lock is automatically released when the connection closes.
 * This makes them safe against JVM crashes: the connection pool reclaims the
 * connection and the lock is freed.
 *
 * <p>{@code GET_LOCK()} names are limited to 64 characters. Names exceeding that
 * length are truncated and suffixed with a hash digest to stay unique.
 *
 * @author shanhongyu
 */
public class MysqlLockStrategy implements SandboxLockStrategy {

    private static final Logger log = LoggerFactory.getLogger(MysqlLockStrategy.class);

    private static final int MAX_LOCK_NAME_LENGTH = 64;

    private final DataSource dataSource;

    /**
     * Creates a MySQL lock strategy.
     *
     * @param dataSource the MySQL data source; must not be {@code null}
     */
    public MysqlLockStrategy(DataSource dataSource) {
        this.dataSource = Objects.requireNonNull(dataSource, "dataSource");
    }

    @Override
    public SandboxLease tryEnter(String lockName, int timeoutSeconds) throws InterruptedException {
        Objects.requireNonNull(lockName, "lockName");
        String normalized = normalizeLockName(lockName);
        log.debug("[mysql-lock] Acquiring: {}", normalized);

        try {
            Connection conn = dataSource.getConnection();
            try (PreparedStatement ps = conn.prepareStatement("SELECT GET_LOCK(?, ?)")) {
                ps.setString(1, normalized);
                ps.setInt(2, timeoutSeconds);
                try (ResultSet rs = ps.executeQuery()) {
                    if (rs.next() && rs.getInt(1) == 1) {
                        log.debug("[mysql-lock] Acquired: {}", normalized);
                        return new MysqlLease(conn, normalized);
                    }
                }
            } catch (Exception e) {
                conn.close();
                throw e;
            }
            // GET_LOCK returned 0 (timeout) or NULL (error)
            conn.close();
            throw new InterruptedException(
                    "Timed out waiting for MySQL lock: "
                            + normalized
                            + " (timeout="
                            + timeoutSeconds
                            + "s)");
        } catch (InterruptedException e) {
            throw e;
        } catch (SQLException e) {
            throw new RuntimeException("Failed to acquire MySQL lock: " + normalized, e);
        }
    }

    /**
     * Truncates the lock name to MySQL's 64-character limit, appending a hash
     * suffix when truncation occurs.
     */
    private static String normalizeLockName(String lockName) {
        if (lockName.length() <= MAX_LOCK_NAME_LENGTH) {
            return lockName;
        }
        int hash = lockName.hashCode();
        return lockName.substring(0, 50) + ":" + Integer.toHexString(hash);
    }

    /** Lease backed by MySQL {@code RELEASE_LOCK()} and connection close. */
    private static final class MysqlLease implements SandboxLease {

        private final Connection conn;
        private final String lockName;

        MysqlLease(Connection conn, String lockName) {
            this.conn = conn;
            this.lockName = lockName;
        }

        @Override
        public void close() {
            try (PreparedStatement ps = conn.prepareStatement("SELECT RELEASE_LOCK(?)")) {
                ps.setString(1, lockName);
                ps.executeQuery();
                log.debug("[mysql-lock] Released: {}", lockName);
            } catch (Exception e) {
                log.warn("[mysql-lock] Failed to release {}: {}", lockName, e.getMessage(), e);
            } finally {
                try {
                    conn.close();
                } catch (SQLException e) {
                    log.warn("[mysql-lock] Failed to close connection: {}", e.getMessage());
                }
            }
        }
    }
}

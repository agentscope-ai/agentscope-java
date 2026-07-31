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
import java.sql.SQLException;
import java.sql.SQLIntegrityConstraintViolationException;
import java.sql.Statement;
import java.util.Objects;
import javax.sql.DataSource;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Portable {@link SandboxLockStrategy} backed by a dedicated lock table.
 *
 * <p>This strategy works on <strong>any</strong> JDBC database: it creates a table
 * with {@code lock_name} as the primary key, acquires a lock by INSERTing the name
 * (a primary-key violation means another holder owns the lock), and releases by
 * DELETEing the row. Contended acquires poll with exponential backoff until the
 * timeout expires.
 *
 * <p>Databases with native advisory locks (MySQL {@code GET_LOCK}, PostgreSQL
 * {@code pg_advisory_lock}) should provide their own {@link SandboxLockStrategy}
 * and override {@link JdbcDialect#lockStrategy(DataSource)} accordingly. This
 * class is the default fallback returned by {@link JdbcDialect}.
 *
 * <h2>Stale-lock caveat</h2>
 *
 * <p>If the JVM crashes while holding a table-based lock, the row remains in the
 * table and blocks future acquires until it is manually removed. For production
 * deployments where crash recovery matters, prefer a database with native
 * advisory locks that auto-release on connection close.
 *
 * @author shanhongyu
 */
public class TableBasedLockStrategy implements SandboxLockStrategy {

    private static final Logger log = LoggerFactory.getLogger(TableBasedLockStrategy.class);

    /** Default lock table name. */
    public static final String DEFAULT_LOCK_TABLE = "agentscope_distributed_locks";

    private static final String CREATE_TABLE_SQL =
            "CREATE TABLE IF NOT EXISTS %s (lock_name VARCHAR(255) NOT NULL PRIMARY KEY)";

    private static final String INSERT_LOCK_SQL = "INSERT INTO %s (lock_name) VALUES (?)";

    private static final String DELETE_LOCK_SQL = "DELETE FROM %s WHERE lock_name = ?";

    private static final int INITIAL_BACKOFF_MS = 50;
    private static final int MAX_BACKOFF_MS = 1000;

    private final DataSource dataSource;
    private final String lockTableName;

    /**
     * Creates a strategy that uses {@value #DEFAULT_LOCK_TABLE}.
     *
     * @param dataSource the JDBC data source; must not be {@code null}
     */
    public TableBasedLockStrategy(DataSource dataSource) {
        this(dataSource, DEFAULT_LOCK_TABLE);
    }

    /**
     * Creates a strategy with a custom lock table name.
     *
     * @param dataSource the JDBC data source; must not be {@code null}
     * @param lockTableName the lock table name; must not be {@code null} or blank
     */
    public TableBasedLockStrategy(DataSource dataSource, String lockTableName) {
        this.dataSource = Objects.requireNonNull(dataSource, "dataSource");
        this.lockTableName = Objects.requireNonNull(lockTableName, "lockTableName").trim();
        if (this.lockTableName.isEmpty()) {
            throw new IllegalArgumentException("lockTableName must not be blank");
        }
        ensureTableExists();
    }

    private void ensureTableExists() {
        String ddl = String.format(CREATE_TABLE_SQL, lockTableName);
        try (Connection conn = dataSource.getConnection();
                Statement stmt = conn.createStatement()) {
            stmt.execute(ddl);
        } catch (SQLException e) {
            throw new IllegalStateException("Failed to initialize lock table: " + lockTableName, e);
        }
    }

    @Override
    public SandboxLease tryEnter(String lockName, int timeoutSeconds) throws InterruptedException {
        Objects.requireNonNull(lockName, "lockName");
        if (timeoutSeconds < 0) {
            throw new IllegalArgumentException("timeoutSeconds must be non-negative");
        }

        String insertSql = String.format(INSERT_LOCK_SQL, lockTableName);
        long deadline = System.currentTimeMillis() + timeoutSeconds * 1000L;
        int backoff = INITIAL_BACKOFF_MS;

        while (true) {
            try (Connection conn = dataSource.getConnection();
                    PreparedStatement ps = conn.prepareStatement(insertSql)) {
                ps.setString(1, lockName);
                ps.executeUpdate();
                log.debug("[table-lock] Acquired lock '{}' in table '{}'", lockName, lockTableName);
                return new TableLease(dataSource, lockTableName, lockName);
            } catch (SQLException e) {
                if (!isDuplicateKey(e)) {
                    throw new RuntimeException(
                            "Failed to acquire table-based lock: " + lockName, e);
                }
                // Lock is held by another caller — check deadline and wait
                if (System.currentTimeMillis() >= deadline) {
                    throw new InterruptedException(
                            "Timed out waiting for table-based lock: "
                                    + lockName
                                    + " (timeout="
                                    + timeoutSeconds
                                    + "s)");
                }
                Thread.sleep(backoff);
                backoff = Math.min(backoff * 2, MAX_BACKOFF_MS);
            }
        }
    }

    /**
     * Returns {@code true} if the exception represents a primary-key / unique
     * constraint violation across the common JDBC drivers.
     */
    private static boolean isDuplicateKey(SQLException e) {
        if (e instanceof SQLIntegrityConstraintViolationException) {
            return true;
        }
        String state = e.getSQLState();
        return state != null && state.startsWith("23");
    }

    /** Lease backed by a DELETE on the lock table. */
    private static final class TableLease implements SandboxLease {

        private final DataSource dataSource;
        private final String lockTableName;
        private final String lockName;

        TableLease(DataSource dataSource, String lockTableName, String lockName) {
            this.dataSource = dataSource;
            this.lockTableName = lockTableName;
            this.lockName = lockName;
        }

        @Override
        public void close() {
            String deleteSql = String.format(DELETE_LOCK_SQL, lockTableName);
            try (Connection conn = dataSource.getConnection();
                    PreparedStatement ps = conn.prepareStatement(deleteSql)) {
                ps.setString(1, lockName);
                ps.executeUpdate();
                log.debug("[table-lock] Released lock '{}' in table '{}'", lockName, lockTableName);
            } catch (SQLException e) {
                log.warn(
                        "[table-lock] Failed to release lock '{}' in table '{}': {}",
                        lockName,
                        lockTableName,
                        e.getMessage());
            }
        }
    }
}

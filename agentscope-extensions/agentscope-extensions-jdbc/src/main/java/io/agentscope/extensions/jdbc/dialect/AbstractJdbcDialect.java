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

import io.agentscope.extensions.jdbc.dialect.table.SessionStateDialect;
import io.agentscope.extensions.jdbc.dialect.table.SnapshotDialect;
import io.agentscope.extensions.jdbc.dialect.table.StoreDialect;
import io.agentscope.harness.agent.sandbox.SandboxLease;
import java.sql.Connection;
import java.sql.DatabaseMetaData;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.sql.SQLIntegrityConstraintViolationException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import javax.sql.DataSource;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Aggregate dialect abstract class — implements all table-domain interfaces and the
 * {@link SandboxLockStrategy} contract.
 *
 * <p>Holds a unified table prefix and per-table name overrides. Vendor classes
 * extend this class and override only the methods where their SQL diverges from
 * ANSI defaults. Lock behavior is also overridable: the default {@link #tryEnter}
 * uses a portable table-based lock; vendor dialects with native advisory locks
 * (e.g. MySQL {@code GET_LOCK}) override {@code tryEnter}.
 *
 * <p>Table-name resolution priority: <strong>per-table override &gt; prefix + base</strong>.
 * The {@code final} resolution methods lock the logic in the base class; vendor
 * dialects inherit them and read the assembled names.
 *
 * <p>Fields are mutable at assembly time (set by {@link AbstractJdbcDialectBuilder})
 * and effectively read-only after {@code build()} returns.
 *
 * <h2>Auto-detection</h2>
 *
 * <p>{@link #from(DataSource)} returns a {@link AbstractJdbcDialectBuilder} that uses JDK
 * SPI ({@link java.util.ServiceLoader}) to discover all dialect implementations on the
 * classpath. Dialects are sorted by {@link #getOrder()} (ascending) then by inheritance
 * depth (child before parent). The first dialect whose {@link #supports(DatabaseMetaData)}
 * returns {@code true} wins.
 *
 * @author shanhongyu
 */
public abstract class AbstractJdbcDialect
        implements StoreDialect, SessionStateDialect, SnapshotDialect, SandboxLockStrategy {

    private static final Logger LOG = LoggerFactory.getLogger(AbstractJdbcDialect.class);

    private String tablePrefix = "agentscope_";
    private String storeTableNameOverride;
    private String sessionStateTableNameOverride;
    private String snapshotTableNameOverride;

    private DataSource dataSource;

    /**
     * Whether the lock table has been created for this dialect instance. Guards the
     * {@code CREATE TABLE IF NOT EXISTS} DDL so it runs once per instance instead of on
     * every {@link #tryEnter(String, int)} acquisition. Cannot be static: each instance
     * (and each fresh in-memory database in tests) needs its own idempotent create.
     */
    private boolean lockTableEnsured;

    // ------------------------------------------------------------------
    //  Final table-name resolution (override > prefix + base)
    // ------------------------------------------------------------------

    @Override
    public final String storeTableName() {
        return Objects.requireNonNullElseGet(
                storeTableNameOverride, () -> tablePrefix + StoreDialect.super.storeTableName());
    }

    @Override
    public final String sessionStateTableName() {
        return Objects.requireNonNullElseGet(
                sessionStateTableNameOverride,
                () -> tablePrefix + SessionStateDialect.super.sessionStateTableName());
    }

    @Override
    public final String snapshotTableName() {
        return Objects.requireNonNullElseGet(
                snapshotTableNameOverride,
                () -> tablePrefix + SnapshotDialect.super.snapshotTableName());
    }

    // ------------------------------------------------------------------
    //  All-table DDL collection (centralized for builder)
    // ------------------------------------------------------------------

    /**
     * Collects create-table DDL for all table-domain interfaces, executed by the builder
     * in a single connection during {@code build()}.
     *
     * @return all table DDL statements in registration order
     */
    List<String> createTableDdls() {
        List<String> ddls = new ArrayList<>();
        ddls.add(storeCreateTableSql());
        ddls.add(sessionStateCreateTableSql());
        ddls.add(snapshotCreateTableSql());
        return ddls;
    }

    // ------------------------------------------------------------------
    //  Package-private setters (builder assembly only)
    // ------------------------------------------------------------------

    final void tablePrefix(String tablePrefix) {
        this.tablePrefix = tablePrefix;
    }

    final void storeTableName(String tableName) {
        this.storeTableNameOverride = tableName;
    }

    final void sessionStateTableName(String tableName) {
        this.sessionStateTableNameOverride = tableName;
    }

    final void snapshotTableName(String tableName) {
        this.snapshotTableNameOverride = tableName;
    }

    /**
     * Binds the DataSource used for lock acquisition. Called by the builder and
     * {@code JdbcDistributedStore}; not intended for direct application use.
     */
    public void bindDataSource(DataSource dataSource) {
        this.dataSource = Objects.requireNonNull(dataSource, "dataSource");
    }

    /** Returns the bound DataSource (for vendor lock implementations). */
    protected DataSource getDataSource() {
        if (dataSource == null) {
            throw new IllegalStateException(
                    "DataSource not bound; use AbstractJdbcDialect.from(ds).build()"
                            + " or JdbcDistributedStore.create(ds)");
        }
        return dataSource;
    }

    // ------------------------------------------------------------------
    //  Vendor contract
    // ------------------------------------------------------------------

    /**
     * Whether this dialect supports the target database.
     *
     * @param metaData JDBC metadata (connection stays open)
     * @return true if this dialect handles the database
     * @throws SQLException if metadata reading fails
     */
    public abstract boolean supports(DatabaseMetaData metaData) throws SQLException;

    /**
     * Detection priority (lower wins). Default 100.
     */
    public int getOrder() {
        return 100;
    }

    // ------------------------------------------------------------------
    //  SandboxLockStrategy — default table-based implementation
    // ------------------------------------------------------------------

    private static final int INITIAL_BACKOFF_MS = 50;
    private static final int MAX_BACKOFF_MS = 1000;

    /** Lock table name, derived from the table prefix. */
    protected String lockTableName() {
        return tablePrefix + "distributed_locks";
    }

    @Override
    public SandboxLease tryEnter(String lockName, int timeoutSeconds) throws InterruptedException {
        Objects.requireNonNull(lockName, "lockName");
        if (timeoutSeconds < 0) {
            throw new IllegalArgumentException("timeoutSeconds must be non-negative");
        }
        DataSource ds = getDataSource();

        ensureLockTable(ds);
        String insertSql = "INSERT INTO " + lockTableName() + " (lock_name) VALUES (?)";
        long deadline = System.currentTimeMillis() + timeoutSeconds * 1000L;
        int backoff = INITIAL_BACKOFF_MS;

        while (true) {
            try (Connection conn = ds.getConnection();
                    PreparedStatement ps = conn.prepareStatement(insertSql)) {
                ps.setString(1, lockName);
                ps.executeUpdate();
                LOG.debug("[table-lock] Acquired '{}'", lockName);
                return new TableLease(ds, lockTableName(), lockName);
            } catch (SQLException e) {
                if (!isDuplicateKey(e)) {
                    throw new RuntimeException(
                            "Failed to acquire table-based lock: " + lockName, e);
                }
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

    private void ensureLockTable(DataSource ds) {
        if (lockTableEnsured) {
            return;
        }
        String ddl =
                "CREATE TABLE IF NOT EXISTS "
                        + lockTableName()
                        + " (lock_name VARCHAR(255) NOT NULL PRIMARY KEY)";
        try (Connection conn = ds.getConnection();
                Statement stmt = conn.createStatement()) {
            stmt.execute(ddl);
            lockTableEnsured = true;
        } catch (SQLException e) {
            throw new IllegalStateException(
                    "Failed to initialize lock table: " + lockTableName(), e);
        }
    }

    private static boolean isDuplicateKey(SQLException e) {
        if (e instanceof SQLIntegrityConstraintViolationException) {
            return true;
        }
        String state = e.getSQLState();
        if (state != null && state.startsWith("23")) {
            // 23xxx = integrity constraint violation in SQL:2003
            return true;
        }
        // SQLite reports constraint violations as errorCode=19 with a null SQLSTATE.
        String className = e.getClass().getName();
        return e.getErrorCode() == 19 && className.startsWith("org.sqlite.");
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
            String deleteSql = "DELETE FROM " + lockTableName + " WHERE lock_name = ?";
            try (Connection conn = dataSource.getConnection();
                    PreparedStatement ps = conn.prepareStatement(deleteSql)) {
                ps.setString(1, lockName);
                ps.executeUpdate();
                LOG.debug("[table-lock] Released '{}'", lockName);
            } catch (SQLException e) {
                LOG.warn("[table-lock] Failed to release '{}': {}", lockName, e.getMessage());
            }
        }
    }

    // ------------------------------------------------------------------
    //  Entry point
    // ------------------------------------------------------------------

    /**
     * Entry point for auto-detection. Returns a builder for chainable configuration.
     */
    public static AbstractJdbcDialectBuilder from(DataSource dataSource) {
        if (dataSource == null) {
            throw new IllegalArgumentException("dataSource cannot be null");
        }
        return new AbstractJdbcDialectBuilder(dataSource);
    }
}

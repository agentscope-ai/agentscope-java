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
package io.agentscope.extensions.jdbc.dialect.vendor;

import io.agentscope.extensions.jdbc.dialect.AbstractJdbcDialect;
import io.agentscope.extensions.jdbc.dialect.BoundSql;
import io.agentscope.harness.agent.sandbox.SandboxLease;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.sql.Connection;
import java.sql.DatabaseMetaData;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.concurrent.TimeUnit;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * PostgreSQL dialect — the ANSI baseline.
 *
 * <p>Business SQL inherits ANSI defaults from the table-domain interfaces. Only
 * create-table DDL and UPSERT syntax (which use PostgreSQL-specific {@code ON CONFLICT}
 * and types like {@code TEXT}/{@code BYTEA}) are overridden.
 *
 * @author shanhongyu
 */
public class PostgresDialect extends AbstractJdbcDialect {

    private static final Logger LOG = LoggerFactory.getLogger(PostgresDialect.class);

    // ------------------------------------------------------------------
    //  StoreDialect
    // ------------------------------------------------------------------

    @Override
    public List<String> storeCreateTableDdls() {
        return List.of(
                "CREATE TABLE IF NOT EXISTS "
                        + storeTableName()
                        + " ("
                        + "  namespace_path VARCHAR(2048) NOT NULL,"
                        + "  item_key       VARCHAR(255)  NOT NULL,"
                        + "  value_json     TEXT          NOT NULL,"
                        + "  version        BIGINT        NOT NULL,"
                        + "  updated_at     BIGINT        NOT NULL,"
                        + "  PRIMARY KEY (namespace_path, item_key)"
                        + ")",
                // PostgreSQL cannot express a secondary index inside CREATE TABLE.
                "CREATE INDEX IF NOT EXISTS "
                        + storeTableName()
                        + "_namespace_idx ON "
                        + storeTableName()
                        + " (namespace_path)");
    }

    @Override
    public BoundSql storeUpsert(String namespacePath, String key, String json, long timestamp) {
        String table = storeTableName();
        return new BoundSql(
                "INSERT INTO "
                        + table
                        + " (namespace_path, item_key, value_json, version, updated_at)"
                        + " VALUES (?, ?, ?, 1, ?)"
                        + " ON CONFLICT (namespace_path, item_key) DO UPDATE SET"
                        + "   value_json = EXCLUDED.value_json,"
                        + "   version    = "
                        + table
                        + ".version + 1,"
                        + "   updated_at = EXCLUDED.updated_at",
                namespacePath,
                key,
                json,
                timestamp);
    }

    // ------------------------------------------------------------------
    //  SessionStateDialect
    // ------------------------------------------------------------------

    @Override
    public List<String> sessionStateCreateTableDdls() {
        return List.of(
                "CREATE TABLE IF NOT EXISTS "
                        + sessionStateTableName()
                        + " ("
                        + "  session_id  VARCHAR(255) NOT NULL,"
                        + "  state_key   VARCHAR(255) NOT NULL,"
                        + "  item_index  INT          NOT NULL DEFAULT 0,"
                        + "  state_data  TEXT         NOT NULL,"
                        + "  version     BIGINT       NOT NULL DEFAULT 0,"
                        + "  created_at  TIMESTAMP    DEFAULT CURRENT_TIMESTAMP,"
                        + "  updated_at  TIMESTAMP    DEFAULT CURRENT_TIMESTAMP,"
                        + "  PRIMARY KEY (session_id, state_key, item_index)"
                        + ")",
                // PostgreSQL cannot express a secondary index inside CREATE TABLE.
                "CREATE INDEX IF NOT EXISTS "
                        + sessionStateTableName()
                        + "_session_idx ON "
                        + sessionStateTableName()
                        + " (session_id)");
    }

    @Override
    public BoundSql sessionStateUpsert(
            String sessionId, String stateKey, int itemIndex, String stateData) {
        return new BoundSql(
                "INSERT INTO "
                        + sessionStateTableName()
                        + " (session_id, state_key, item_index, state_data, version)"
                        + " VALUES (?, ?, ?, ?, 1)"
                        + " ON CONFLICT (session_id, state_key, item_index) DO UPDATE SET"
                        + "   state_data = EXCLUDED.state_data,"
                        + "   version    = "
                        + sessionStateTableName()
                        + ".version + 1",
                sessionId,
                stateKey,
                itemIndex,
                stateData);
    }

    @Override
    public BoundSql sessionStateCheckTableExists(String tableName) {
        return new BoundSql(
                "SELECT 1 FROM information_schema.tables"
                        + " WHERE table_schema = current_schema() AND table_name = ?",
                tableName);
    }

    // ------------------------------------------------------------------
    //  SnapshotDialect
    // ------------------------------------------------------------------

    @Override
    public List<String> snapshotCreateTableDdls() {
        return List.of(
                "CREATE TABLE IF NOT EXISTS "
                        + snapshotTableName()
                        + " ("
                        + "  snapshot_id VARCHAR(512) NOT NULL PRIMARY KEY, "
                        + "  data BYTEA NOT NULL, "
                        + "  created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP"
                        + ")");
    }

    @Override
    public BoundSql snapshotUpsert(String snapshotId, InputStream data) {
        return new BoundSql(
                "INSERT INTO "
                        + snapshotTableName()
                        + " (snapshot_id, data) VALUES (?, ?)"
                        + " ON CONFLICT (snapshot_id) DO UPDATE SET"
                        + "   data = EXCLUDED.data, created_at = CURRENT_TIMESTAMP",
                snapshotId,
                data);
    }

    // ------------------------------------------------------------------
    //  SandboxLockStrategy — PostgreSQL native advisory locks
    // ------------------------------------------------------------------

    @Override
    public SandboxLease tryEnter(String lockName, int timeoutSeconds) throws InterruptedException {
        Objects.requireNonNull(lockName, "lockName");
        if (timeoutSeconds < 0) {
            throw new IllegalArgumentException("timeoutSeconds must be non-negative");
        }
        long lockKey = composeLockKey(lockName);
        long deadline = System.currentTimeMillis() + timeoutSeconds * 1000L;
        LOG.debug("[postgres-lock] Acquiring: {} -> {}", lockName, lockKey);

        try {
            Connection conn = getDataSource().getConnection();
            try {
                while (true) {
                    try (PreparedStatement ps =
                            conn.prepareStatement("SELECT pg_try_advisory_lock(?)")) {
                        ps.setLong(1, lockKey);
                        try (ResultSet rs = ps.executeQuery()) {
                            if (rs.next() && rs.getBoolean(1)) {
                                LOG.debug("[postgres-lock] Acquired: {}", lockKey);
                                return new PostgresLease(conn, lockKey);
                            }
                        }
                    }
                    if (System.currentTimeMillis() >= deadline) {
                        throw new InterruptedException(
                                "Timed out waiting for PostgreSQL advisory lock: "
                                        + lockName
                                        + " (timeout="
                                        + timeoutSeconds
                                        + "s)");
                    }
                    TimeUnit.MILLISECONDS.sleep(100);
                }
            } catch (InterruptedException e) {
                closeConnection(conn);
                throw e;
            } catch (SQLException e) {
                closeConnection(conn);
                throw new RuntimeException(
                        "Failed to acquire PostgreSQL advisory lock: " + lockName, e);
            }
        } catch (SQLException e) {
            throw new RuntimeException(
                    "Failed to acquire PostgreSQL advisory lock: " + lockName, e);
        }
    }

    /**
     * Maps a lock name to a 64-bit advisory-lock key by folding the first 8 bytes of its SHA-256
     * digest. Distinct names collide with probability ~2^-32, matching the legacy PostgreSQL
     * module's MurmurHash3 width.
     */
    private static long composeLockKey(String lockName) {
        try {
            byte[] digest =
                    MessageDigest.getInstance("SHA-256")
                            .digest(lockName.getBytes(StandardCharsets.UTF_8));
            long key = 0L;
            for (int i = 0; i < Long.BYTES; i++) {
                key = (key << 8) | (digest[i] & 0xFFL);
            }
            return key;
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 algorithm not available", e);
        }
    }

    /**
     * Lease backed by {@code pg_advisory_unlock} plus connection close. Closing the connection also
     * releases the advisory lock automatically as a safety net.
     */
    private static final class PostgresLease implements SandboxLease {

        private final Connection conn;
        private final long lockKey;

        PostgresLease(Connection conn, long lockKey) {
            this.conn = conn;
            this.lockKey = lockKey;
        }

        @Override
        public void close() {
            try (PreparedStatement ps = conn.prepareStatement("SELECT pg_advisory_unlock(?)")) {
                ps.setLong(1, lockKey);
                ps.executeQuery();
                LOG.debug("[postgres-lock] Released: {}", lockKey);
            } catch (Exception e) {
                LOG.warn(
                        "[postgres-lock] Failed to release PostgreSQL advisory lock {}: {}",
                        lockKey,
                        e.getMessage(),
                        e);
            } finally {
                closeConnection(conn);
            }
        }
    }

    // ------------------------------------------------------------------
    //  Detection
    // ------------------------------------------------------------------

    @Override
    public boolean supports(DatabaseMetaData metaData) throws SQLException {
        return metaData.getDatabaseProductName().toLowerCase(Locale.ROOT).contains("postgresql");
    }
}

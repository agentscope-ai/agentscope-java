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
import java.util.HexFormat;
import java.util.List;
import java.util.Locale;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * MySQL / MariaDB dialect.
 *
 * <p>Overrides DDL ({@code LONGTEXT}, {@code ENGINE=InnoDB}, {@code utf8mb4}), UPSERT
 * syntax ({@code ON DUPLICATE KEY UPDATE}), table-existence check ({@code DATABASE()}),
 * and lock acquisition ({@code GET_LOCK} / {@code RELEASE_LOCK}). All other business SQL
 * inherits ANSI defaults.
 *
 * @author shanhongyu
 */
public class MysqlDialect extends AbstractJdbcDialect {

    private static final Logger log = LoggerFactory.getLogger(MysqlDialect.class);

    /** MySQL {@code GET_LOCK} name limit (characters, ASCII lock names are 1 byte each). */
    private static final int MAX_LOCK_NAME_LENGTH = 64;

    /**
     * Hex chars of the SHA-256 digest embedded in a normalized lock name — 32 hex chars = 128
     * bits, matching the reviewer-recommended collision strength.
     */
    private static final int LOCK_NAME_HASH_HEX_LENGTH = 32;

    private static final String SHA_256 = "SHA-256";

    // ------------------------------------------------------------------
    //  StoreDialect
    // ------------------------------------------------------------------

    @Override
    public List<String> storeCreateTableDdls() {
        // Keep composite PK under InnoDB utf8mb4 3072-byte limit:
        // (512 + 255) * 4 = 3068 bytes. idx_namespace (512 * 4 = 2048 bytes) also fits.
        return List.of(
                "CREATE TABLE IF NOT EXISTS "
                        + storeTableName()
                        + " ("
                        + "  namespace_path VARCHAR(512)  NOT NULL,"
                        + "  item_key       VARCHAR(255)  NOT NULL,"
                        + "  value_json     LONGTEXT      NOT NULL,"
                        + "  version        BIGINT        NOT NULL,"
                        + "  updated_at     BIGINT        NOT NULL,"
                        + "  PRIMARY KEY (namespace_path, item_key),"
                        + "  INDEX idx_namespace (namespace_path)"
                        + ") ENGINE=InnoDB DEFAULT CHARSET=utf8mb4");
    }

    @Override
    public BoundSql storeUpsert(String namespacePath, String key, String json, long timestamp) {
        return new BoundSql(
                "INSERT INTO "
                        + storeTableName()
                        + " (namespace_path, item_key, value_json, version, updated_at)"
                        + " VALUES (?, ?, ?, 1, ?)"
                        + " ON DUPLICATE KEY UPDATE"
                        + "   value_json = VALUES(value_json),"
                        + "   version    = version + 1,"
                        + "   updated_at = VALUES(updated_at)",
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
                """
                CREATE TABLE IF NOT EXISTS %s (
                  session_id  VARCHAR(255) NOT NULL,
                  state_key   VARCHAR(255) NOT NULL,
                  item_index  INT          NOT NULL DEFAULT 0,
                  state_data  LONGTEXT     NOT NULL,
                  created_at  DATETIME     DEFAULT CURRENT_TIMESTAMP,
                  updated_at  DATETIME     DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
                  PRIMARY KEY (session_id, state_key, item_index),
                  INDEX idx_session (session_id)
                ) DEFAULT CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci\
                """
                        .formatted(sessionStateTableName()));
    }

    @Override
    public BoundSql sessionStateUpsert(
            String sessionId, String stateKey, int itemIndex, String stateData) {
        return new BoundSql(
                "INSERT INTO "
                        + sessionStateTableName()
                        + " (session_id, state_key, item_index, state_data) VALUES (?, ?, ?, ?)"
                        + " ON DUPLICATE KEY UPDATE state_data = VALUES(state_data)",
                sessionId,
                stateKey,
                itemIndex,
                stateData);
    }

    @Override
    public BoundSql sessionStateCheckTableExists(String tableName) {
        return new BoundSql(
                "SELECT 1 FROM INFORMATION_SCHEMA.TABLES"
                        + " WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = ?",
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
                        + "  data LONGBLOB NOT NULL, "
                        + "  created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP"
                        + ")");
    }

    @Override
    public BoundSql snapshotUpsert(String snapshotId, InputStream data) {
        return new BoundSql(
                "INSERT INTO "
                        + snapshotTableName()
                        + " (snapshot_id, data) VALUES (?, ?)"
                        + " ON DUPLICATE KEY UPDATE"
                        + "   data = VALUES(data),"
                        + "   created_at = CURRENT_TIMESTAMP",
                snapshotId,
                data);
    }

    // ------------------------------------------------------------------
    //  SandboxLockStrategy — MySQL native GET_LOCK
    // ------------------------------------------------------------------

    @Override
    public SandboxLease tryEnter(String lockName, int timeoutSeconds) throws InterruptedException {
        String normalized = normalizeLockName(lockName);
        log.debug("[mysql-lock] Acquiring: {}", normalized);

        try {
            Connection conn = getDataSource().getConnection();
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

    private static String normalizeLockName(String lockName) {
        if (lockName.length() <= MAX_LOCK_NAME_LENGTH) {
            return lockName;
        }
        // Truncate the prefix and append a SHA-256 digest so that two distinct long lock
        // names can never map to the same normalized name (String.hashCode is only 32 bits
        // and collides under high cardinality — a collision would let one agent's
        // RELEASE_LOCK release another agent's lock).
        String hash = sha256Hex(lockName);
        int prefixLength = MAX_LOCK_NAME_LENGTH - 1 - LOCK_NAME_HASH_HEX_LENGTH;
        return lockName.substring(0, prefixLength)
                + ":"
                + hash.substring(0, LOCK_NAME_HASH_HEX_LENGTH);
    }

    private static String sha256Hex(String value) {
        try {
            MessageDigest digest = MessageDigest.getInstance(SHA_256);
            return HexFormat.of().formatHex(digest.digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 algorithm not available", e);
        }
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

    // ------------------------------------------------------------------
    //  Detection
    // ------------------------------------------------------------------

    @Override
    public boolean supports(DatabaseMetaData metaData) throws SQLException {
        String name = metaData.getDatabaseProductName().toLowerCase(Locale.ROOT);
        return name.contains("mysql") || name.contains("mariadb");
    }
}

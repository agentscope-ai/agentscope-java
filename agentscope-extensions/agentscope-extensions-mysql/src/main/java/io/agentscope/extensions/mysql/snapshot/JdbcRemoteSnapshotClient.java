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
package io.agentscope.extensions.mysql.snapshot;

import io.agentscope.harness.agent.sandbox.snapshot.RemoteSnapshotClient;
import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.Objects;
import javax.sql.DataSource;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * {@link RemoteSnapshotClient} backed by a JDBC BLOB column.
 *
 * <p>Stores sandbox workspace tar archives in a database table with columns
 * {@code (snapshot_id VARCHAR PK, data LONGBLOB, created_at TIMESTAMP)}.
 *
 * <p>The table is auto-created if it does not exist when {@code initializeSchema} is true.
 */
public class JdbcRemoteSnapshotClient implements RemoteSnapshotClient {

    private static final Logger log = LoggerFactory.getLogger(JdbcRemoteSnapshotClient.class);

    private static final String DEFAULT_TABLE = "agentscope_snapshots";

    private final DataSource dataSource;
    private final String tableName;

    public JdbcRemoteSnapshotClient(DataSource dataSource, boolean initializeSchema) {
        this(dataSource, DEFAULT_TABLE, initializeSchema);
    }

    public JdbcRemoteSnapshotClient(
            DataSource dataSource, String tableName, boolean initializeSchema) {
        this.dataSource = Objects.requireNonNull(dataSource, "dataSource");
        this.tableName = tableName != null ? tableName : DEFAULT_TABLE;
        if (initializeSchema) {
            initSchema();
        }
    }

    private void initSchema() {
        String ddl =
                "CREATE TABLE IF NOT EXISTS "
                        + tableName
                        + " ("
                        + "snapshot_id VARCHAR(512) NOT NULL PRIMARY KEY, "
                        + "data LONGBLOB NOT NULL, "
                        + "created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP"
                        + ")";
        try (Connection conn = dataSource.getConnection();
                Statement stmt = conn.createStatement()) {
            stmt.execute(ddl);
        } catch (SQLException e) {
            log.warn("Failed to initialize snapshot table '{}': {}", tableName, e.getMessage());
        }
    }

    @Override
    public void upload(String snapshotId, InputStream data) throws Exception {
        byte[] bytes = data.readAllBytes();
        String sql =
                "INSERT INTO "
                        + tableName
                        + " (snapshot_id, data) VALUES (?, ?) "
                        + "ON DUPLICATE KEY UPDATE data = VALUES(data), "
                        + "created_at = CURRENT_TIMESTAMP";
        try (Connection conn = dataSource.getConnection();
                PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, snapshotId);
            ps.setBytes(2, bytes);
            ps.executeUpdate();
        }
    }

    @Override
    public InputStream download(String snapshotId) throws Exception {
        String sql = "SELECT data FROM " + tableName + " WHERE snapshot_id = ?";
        try (Connection conn = dataSource.getConnection();
                PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, snapshotId);
            try (ResultSet rs = ps.executeQuery()) {
                if (!rs.next()) {
                    throw new java.io.FileNotFoundException(
                            "Snapshot not found in database: " + snapshotId);
                }
                return new ByteArrayInputStream(rs.getBytes("data"));
            }
        }
    }

    @Override
    public boolean exists(String snapshotId) throws Exception {
        String sql = "SELECT 1 FROM " + tableName + " WHERE snapshot_id = ?";
        try (Connection conn = dataSource.getConnection();
                PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, snapshotId);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next();
            }
        }
    }
}

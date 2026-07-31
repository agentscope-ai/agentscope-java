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
package io.agentscope.extensions.jdbc.snapshot;

import io.agentscope.extensions.jdbc.dialect.SnapshotStoreDialect;
import io.agentscope.harness.agent.sandbox.snapshot.RemoteSnapshotClient;
import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.Objects;
import java.util.regex.Pattern;
import javax.sql.DataSource;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * {@link RemoteSnapshotClient} backed by a JDBC BLOB column, with zero inline SQL.
 *
 * <p>Stores sandbox workspace tar archives in a database table. All SQL is sourced
 * from {@link SnapshotStoreDialect}, supporting MySQL, PostgreSQL, H2, and SQLite.
 *
 * @author shanhongyu
 */
public class JdbcRemoteSnapshotClient implements RemoteSnapshotClient {

    private static final Logger log = LoggerFactory.getLogger(JdbcRemoteSnapshotClient.class);

    static final String DEFAULT_TABLE = "agentscope_snapshots";

    private static final Pattern VALID_TABLE_NAME = Pattern.compile("[A-Za-z_][A-Za-z0-9_]*");

    private final DataSource dataSource;
    private final String tableName;

    // Pre-resolved SQL (table name substituted once at construction)
    private final String upsertSql;
    private final String selectSql;
    private final String existsSql;

    /**
     * Creates a client with the default table name and auto-creates the table.
     *
     * @param dataSource the JDBC data source; must not be {@code null}
     * @param dialect the snapshot dialect for SQL generation; must not be {@code null}
     */
    public JdbcRemoteSnapshotClient(DataSource dataSource, SnapshotStoreDialect dialect) {
        this(dataSource, dialect, DEFAULT_TABLE, true);
    }

    /**
     * Creates a client with full configuration.
     *
     * @param dataSource the JDBC data source; must not be {@code null}
     * @param dialect the snapshot dialect for SQL generation; must not be {@code null}
     * @param tableName the snapshots table name; defaults to {@value #DEFAULT_TABLE}
     * @param initializeSchema when {@code true}, auto-creates the table
     */
    public JdbcRemoteSnapshotClient(
            DataSource dataSource,
            SnapshotStoreDialect dialect,
            String tableName,
            boolean initializeSchema) {
        this.dataSource = Objects.requireNonNull(dataSource, "dataSource");
        Objects.requireNonNull(dialect, "dialect");
        String resolved = tableName != null ? tableName : DEFAULT_TABLE;
        if (!VALID_TABLE_NAME.matcher(resolved).matches()) {
            throw new IllegalArgumentException(
                    "tableName must match [A-Za-z_][A-Za-z0-9_]*, got: " + resolved);
        }
        this.tableName = resolved;
        this.upsertSql = String.format(dialect.getUpsertSnapshotSql(), tableName);
        this.selectSql = String.format(dialect.getSelectSnapshotSql(), tableName);
        this.existsSql = String.format(dialect.getExistsSnapshotSql(), tableName);
        if (initializeSchema) {
            initSchema(dialect);
        }
    }

    private void initSchema(SnapshotStoreDialect dialect) {
        String ddl = String.format(dialect.getCreateSnapshotTableSql(), tableName);
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
        try (Connection conn = dataSource.getConnection();
                PreparedStatement ps = conn.prepareStatement(upsertSql)) {
            ps.setString(1, snapshotId);
            ps.setBytes(2, bytes);
            ps.executeUpdate();
        }
    }

    @Override
    public InputStream download(String snapshotId) throws Exception {
        try (Connection conn = dataSource.getConnection();
                PreparedStatement ps = conn.prepareStatement(selectSql)) {
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
        try (Connection conn = dataSource.getConnection();
                PreparedStatement ps = conn.prepareStatement(existsSql)) {
            ps.setString(1, snapshotId);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next();
            }
        }
    }
}

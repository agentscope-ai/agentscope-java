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

import io.agentscope.extensions.jdbc.dialect.BoundSql;
import io.agentscope.extensions.jdbc.dialect.table.SnapshotDialect;
import io.agentscope.harness.agent.sandbox.snapshot.RemoteSnapshotClient;
import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.List;
import java.util.Objects;
import javax.sql.DataSource;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * {@link RemoteSnapshotClient} backed by a JDBC BLOB column, with zero inline SQL.
 *
 * <p>All SQL is sourced from {@link SnapshotDialect}, supporting MySQL, PostgreSQL, H2,
 * and SQLite.
 *
 * @author shanhongyu
 */
public class JdbcRemoteSnapshotClient implements RemoteSnapshotClient {

    private static final Logger log = LoggerFactory.getLogger(JdbcRemoteSnapshotClient.class);

    private final DataSource dataSource;
    private final SnapshotDialect dialect;

    /**
     * Creates a client with auto table creation.
     *
     * @param dataSource the JDBC data source
     * @param dialect the snapshot dialect
     */
    public JdbcRemoteSnapshotClient(DataSource dataSource, SnapshotDialect dialect) {
        this(dataSource, dialect, true);
    }

    /**
     * Creates a client with optional auto table creation.
     *
     * @param dataSource the JDBC data source
     * @param dialect the snapshot dialect
     * @param initializeSchema when true, auto-creates the table
     */
    public JdbcRemoteSnapshotClient(
            DataSource dataSource, SnapshotDialect dialect, boolean initializeSchema) {
        this.dataSource = Objects.requireNonNull(dataSource, "dataSource");
        this.dialect = Objects.requireNonNull(dialect, "dialect");
        if (initializeSchema) {
            initSchema();
        }
    }

    private void initSchema() {
        String ddl = dialect.snapshotCreateTableSql();
        try (Connection conn = dataSource.getConnection();
                Statement stmt = conn.createStatement()) {
            stmt.execute(ddl);
        } catch (SQLException e) {
            log.warn("Failed to initialize snapshot table: {}", e.getMessage());
        }
    }

    @Override
    public void upload(String snapshotId, InputStream data) throws Exception {
        byte[] bytes = data.readAllBytes();
        BoundSql boundSql = dialect.snapshotUpsert(snapshotId, bytes);
        try (Connection conn = dataSource.getConnection();
                PreparedStatement ps = conn.prepareStatement(boundSql.sql())) {
            bindParams(ps, boundSql.params());
            ps.executeUpdate();
        }
    }

    @Override
    public InputStream download(String snapshotId) throws Exception {
        BoundSql boundSql = dialect.snapshotSelect(snapshotId);
        try (Connection conn = dataSource.getConnection();
                PreparedStatement ps = conn.prepareStatement(boundSql.sql())) {
            bindParams(ps, boundSql.params());
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
        BoundSql boundSql = dialect.snapshotExists(snapshotId);
        try (Connection conn = dataSource.getConnection();
                PreparedStatement ps = conn.prepareStatement(boundSql.sql())) {
            bindParams(ps, boundSql.params());
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next();
            }
        }
    }

    private static void bindParams(PreparedStatement ps, List<Object> params) throws SQLException {
        for (int i = 0; i < params.size(); i++) {
            ps.setObject(i + 1, params.get(i));
        }
    }
}

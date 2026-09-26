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

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.agentscope.extensions.jdbc.H2TestSupport;
import io.agentscope.extensions.jdbc.dialect.vendor.H2Dialect;
import io.agentscope.extensions.jdbc.state.JdbcAgentStateStore;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.ResultSetMetaData;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.Locale;
import java.util.Set;
import java.util.TreeSet;
import javax.sql.DataSource;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * H2 tests for the consolidated {@code build()} flow: table creation plus column validation
 * of all three tables, in both {@code autoCreateTable} modes, including the migration
 * scenarios from the deprecated mysql/postgresql extensions (same table names, missing
 * columns) that {@code CREATE TABLE IF NOT EXISTS} used to adopt silently.
 *
 * @author shanhongyu
 */
@DisplayName("AbstractJdbcDialectBuilder schema creation and validation (H2)")
class DialectBuilderSchemaValidationTest {

    // ------------------------------------------------------------------
    //  Main paths
    // ------------------------------------------------------------------

    @Test
    @DisplayName("build() on a fresh database creates all three tables with the declared columns")
    void freshDatabaseBuildCreatesAndValidates() throws Exception {
        DataSource ds = H2TestSupport.createDataSource("schema_fresh_build");

        AbstractJdbcDialect dialect = AbstractJdbcDialect.from(ds).build();

        assertEquals(
                Set.of("namespace_path", "item_key", "value_json", "version", "updated_at"),
                actualColumns(ds, dialect.storeTableName()));
        assertEquals(
                Set.of(
                        "session_id",
                        "state_key",
                        "item_index",
                        "state_data",
                        "version",
                        "created_at",
                        "updated_at"),
                actualColumns(ds, dialect.sessionStateTableName()));
        assertEquals(
                Set.of("snapshot_id", "data", "created_at"),
                actualColumns(ds, dialect.snapshotTableName()));
    }

    @Test
    @DisplayName("build() blocks after a manual DROP COLUMN, naming table and missing column")
    void buildBlocksOnDroppedColumn() throws Exception {
        DataSource ds = H2TestSupport.createDataSource("schema_dropped_column");
        AbstractJdbcDialect dialect = AbstractJdbcDialect.from(ds).build();
        execute(ds, "ALTER TABLE " + dialect.sessionStateTableName() + " DROP COLUMN version");

        IllegalStateException exception =
                assertThrows(
                        IllegalStateException.class, () -> AbstractJdbcDialect.from(ds).build());

        String message = exception.getMessage();
        assertTrue(
                message.contains(dialect.sessionStateTableName()),
                "message must name the table: " + message);
        assertTrue(message.contains("version"), "message must name the missing column: " + message);
        assertTrue(
                message.toLowerCase(Locale.ROOT).contains("state_key"),
                "message must list the actual columns: " + message);
        assertTrue(
                message.toUpperCase(Locale.ROOT).contains("CREATE TABLE"),
                "message must carry the reference DDL: " + message);
    }

    @Test
    @DisplayName("autoCreateTable(false) on an empty database blocks with the reference DDL")
    void autoCreateFalseOnEmptyDatabaseBlocks() {
        DataSource ds = H2TestSupport.createDataSource("schema_empty_no_create");

        IllegalStateException exception =
                assertThrows(
                        IllegalStateException.class,
                        () -> AbstractJdbcDialect.from(ds).autoCreateTable(false).build());

        String message = exception.getMessage();
        assertTrue(
                message.contains("agentscope_store"),
                "message must name the first missing table: " + message);
        assertTrue(
                message.toUpperCase(Locale.ROOT).contains("CREATE TABLE"),
                "message must carry the reference DDL: " + message);
    }

    // ------------------------------------------------------------------
    //  Migration scenarios
    // ------------------------------------------------------------------

    @Test
    @DisplayName(
            "a legacy sessions table without version blocks build(), all missing columns at once")
    void legacySessionsTableBlocksBuild() throws Exception {
        DataSource ds = H2TestSupport.createDataSource("schema_legacy_sessions");
        // The shape the deprecated mysql/postgresql extensions created: same table name,
        // no version / created_at / updated_at columns.
        execute(
                ds,
                "CREATE TABLE agentscope_sessions ("
                        + "  session_id VARCHAR(255) NOT NULL,"
                        + "  state_key  VARCHAR(255) NOT NULL,"
                        + "  item_index INT NOT NULL DEFAULT 0,"
                        + "  state_data CLOB NOT NULL,"
                        + "  PRIMARY KEY (session_id, state_key, item_index)"
                        + ")");

        IllegalStateException exception =
                assertThrows(
                        IllegalStateException.class, () -> AbstractJdbcDialect.from(ds).build());

        String message = exception.getMessage();
        assertTrue(message.contains("agentscope_sessions"), "message must name the table");
        // All missing columns are reported in one go, not one per round-trip.
        assertTrue(message.contains("version"), "message must list 'version': " + message);
        assertTrue(message.contains("created_at"), "message must list 'created_at': " + message);
        assertTrue(message.contains("updated_at"), "message must list 'updated_at': " + message);
    }

    @Test
    @DisplayName("a second build() on the same database is idempotent")
    void secondBuildIsIdempotent() {
        DataSource ds = H2TestSupport.createDataSource("schema_idempotent");

        assertDoesNotThrow(() -> AbstractJdbcDialect.from(ds).build());
        assertDoesNotThrow(() -> AbstractJdbcDialect.from(ds).build());
    }

    @Test
    @DisplayName("directly constructing a store no longer issues any DDL")
    void directStoreConstructionIssuesNoDdl() throws Exception {
        DataSource ds = H2TestSupport.createDataSource("schema_no_direct_ddl");

        // The two-argument constructor performs null checks only — no validation, no DDL.
        assertDoesNotThrow(() -> new JdbcAgentStateStore(ds, new H2Dialect()));

        assertEquals(0, tableCount(ds), "no table must have been created");
    }

    // ------------------------------------------------------------------
    //  Helpers
    // ------------------------------------------------------------------

    /**
     * Runs a single SQL statement.
     *
     * @param ds the database to run it on
     * @param sql the statement
     */
    private static void execute(DataSource ds, String sql) throws SQLException {
        try (Connection conn = ds.getConnection();
                Statement stmt = conn.createStatement()) {
            stmt.execute(sql);
        }
    }

    /**
     * The table's actual column names, lower-cased.
     *
     * @param ds the database holding the table
     * @param table the table to probe
     * @return actual column names, lower-cased
     */
    private static Set<String> actualColumns(DataSource ds, String table) throws SQLException {
        try (Connection conn = ds.getConnection();
                Statement stmt = conn.createStatement();
                ResultSet rs = stmt.executeQuery("SELECT * FROM " + table + " WHERE 1=0")) {
            ResultSetMetaData metaData = rs.getMetaData();
            Set<String> columns = new TreeSet<>();
            for (int i = 1; i <= metaData.getColumnCount(); i++) {
                columns.add(metaData.getColumnLabel(i).toLowerCase(Locale.ROOT));
            }
            return columns;
        }
    }

    /**
     * Number of tables in H2's PUBLIC schema.
     *
     * @param ds the database to count in
     * @return the table count
     */
    private static int tableCount(DataSource ds) throws SQLException {
        try (Connection conn = ds.getConnection();
                Statement stmt = conn.createStatement();
                ResultSet rs =
                        stmt.executeQuery(
                                "SELECT COUNT(*) FROM INFORMATION_SCHEMA.TABLES"
                                        + " WHERE TABLE_SCHEMA = 'PUBLIC'")) {
            rs.next();
            return rs.getInt(1);
        }
    }
}

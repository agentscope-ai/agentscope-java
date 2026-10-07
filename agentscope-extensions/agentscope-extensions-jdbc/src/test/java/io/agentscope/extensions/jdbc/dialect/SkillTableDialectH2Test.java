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
import io.agentscope.extensions.jdbc.dialect.vendor.MysqlDialect;
import io.agentscope.extensions.jdbc.dialect.vendor.PostgresDialect;
import io.agentscope.extensions.jdbc.dialect.vendor.SqliteDialect;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.ResultSetMetaData;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.TreeSet;
import javax.sql.DataSource;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * H2 tests for the skill table-group dialects: DDL execution, required-column validation
 * (a pre-{@code metadata_json} table is blocked), generated-key inserts, and the
 * cascading foreign key — the two behaviors the deprecated skill mysql/postgresql modules
 * relied on that the base tables never exercised. Missing-table interception through the
 * builder is covered by {@code DialectBuilderTableGroupsTest}.
 *
 * @author shanhongyu
 */
@DisplayName("Skill table dialects (H2)")
class SkillTableDialectH2Test {

    /** The column set every vendor's skill DDL declares. */
    private static final Set<String> SKILL_COLUMNS =
            Set.of(
                    "id",
                    "name",
                    "description",
                    "skill_content",
                    "source",
                    "metadata_json",
                    "created_at",
                    "updated_at");

    /** The column set every vendor's skill-resources DDL declares. */
    private static final Set<String> SKILL_RESOURCE_COLUMNS =
            Set.of("id", "resource_path", "resource_content", "created_at", "updated_at");

    // ------------------------------------------------------------------
    //  DDL execution and validation
    // ------------------------------------------------------------------

    @Test
    @DisplayName("skill DDLs create both tables with the declared columns and validate clean")
    void freshCreateValidates() throws Exception {
        DataSource ds = H2TestSupport.createDataSource("skill_ddl_fresh");
        H2Dialect dialect = new H2Dialect();

        executeDdls(ds, dialect.skillCreateTableDdls());
        executeDdls(ds, dialect.skillResourcesCreateTableDdls());

        try (Connection conn = ds.getConnection()) {
            assertDoesNotThrow(
                    () ->
                            TableSchemaValidator.validate(
                                    conn,
                                    dialect.skillTableName(),
                                    dialect.skillCreateTableDdls()));
            assertDoesNotThrow(
                    () ->
                            TableSchemaValidator.validate(
                                    conn,
                                    dialect.skillResourcesTableName(),
                                    dialect.skillResourcesCreateTableDdls()));
        }
        assertEquals(SKILL_COLUMNS, actualColumns(ds, dialect.skillTableName()));
        assertEquals(SKILL_RESOURCE_COLUMNS, actualColumns(ds, dialect.skillResourcesTableName()));
    }

    @Test
    @DisplayName("a table missing a declared column is blocked, naming it and the reference DDL")
    void missingColumnBlocked() throws Exception {
        DataSource ds = H2TestSupport.createDataSource("skill_ddl_missing_column");
        H2Dialect dialect = new H2Dialect();
        // The exact shape the deprecated skill modules created before metadata_json existed:
        // every declared column except metadata_json. Under the required-column policy the
        // user must perceive this at the check phase and run the ALTER, not be silently
        // degraded.
        execute(
                ds,
                "CREATE TABLE "
                        + dialect.skillTableName()
                        + " ("
                        + "  id BIGINT AUTO_INCREMENT PRIMARY KEY,"
                        + "  name VARCHAR(255) NOT NULL UNIQUE,"
                        + "  description CLOB NOT NULL,"
                        + "  skill_content CLOB NOT NULL,"
                        + "  source VARCHAR(255) NOT NULL,"
                        + "  created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,"
                        + "  updated_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP"
                        + ")");

        try (Connection conn = ds.getConnection()) {
            IllegalStateException exception =
                    assertThrows(
                            IllegalStateException.class,
                            () ->
                                    TableSchemaValidator.validate(
                                            conn,
                                            dialect.skillTableName(),
                                            dialect.skillCreateTableDdls()));
            assertTrue(
                    exception.getMessage().contains("metadata_json"),
                    "message must name the missing column: " + exception.getMessage());
            assertTrue(
                    exception.getMessage().toUpperCase(Locale.ROOT).contains("CREATE TABLE"),
                    "message must carry the reference DDL: " + exception.getMessage());
        }
    }

    // ------------------------------------------------------------------
    //  Auto-increment and cascade — the two ported behaviors
    // ------------------------------------------------------------------

    @Test
    @DisplayName("insert returns the auto-increment id and skill delete cascades to resources")
    void generatedKeysAndCascadeDelete() throws Exception {
        DataSource ds = H2TestSupport.createDataSource("skill_ddl_genkeys_cascade");
        H2Dialect dialect = new H2Dialect();
        executeDdls(ds, dialect.skillCreateTableDdls());
        executeDdls(ds, dialect.skillResourcesCreateTableDdls());

        try (Connection conn = ds.getConnection()) {
            long skillId;
            try (PreparedStatement ps =
                    conn.prepareStatement(
                            "INSERT INTO "
                                    + dialect.skillTableName()
                                    + " (name, description, skill_content, source)"
                                    + " VALUES ('s1', 'd', 'c', 'test')",
                            Statement.RETURN_GENERATED_KEYS)) {
                ps.executeUpdate();
                try (ResultSet keys = ps.getGeneratedKeys()) {
                    assertTrue(keys.next(), "generated key must be returned");
                    skillId = keys.getLong(1);
                    assertTrue(skillId > 0, "auto-increment id must be positive");
                }
            }

            try (PreparedStatement ps =
                    conn.prepareStatement(
                            "INSERT INTO "
                                    + dialect.skillResourcesTableName()
                                    + " (id, resource_path, resource_content)"
                                    + " VALUES (?, 'docs/readme.md', 'hello')")) {
                ps.setLong(1, skillId);
                ps.executeUpdate();
            }

            try (PreparedStatement ps =
                    conn.prepareStatement(
                            "DELETE FROM " + dialect.skillTableName() + " WHERE name = 's1'")) {
                ps.executeUpdate();
            }

            try (PreparedStatement ps =
                            conn.prepareStatement(
                                    "SELECT COUNT(*) FROM " + dialect.skillResourcesTableName());
                    ResultSet rs = ps.executeQuery()) {
                rs.next();
                assertEquals(
                        0, rs.getInt(1), "ON DELETE CASCADE must remove the skill's resources");
            }
        }
    }

    // ------------------------------------------------------------------
    //  DDL shapes parse for every vendor
    // ------------------------------------------------------------------

    @Test
    @DisplayName("every vendor's skill DDL parses to the expected column set")
    void allVendorDdlsParse() {
        for (AbstractJdbcDialect dialect :
                List.of(
                        new H2Dialect(),
                        new MysqlDialect(),
                        new PostgresDialect(),
                        new SqliteDialect())) {
            assertEquals(
                    SKILL_COLUMNS,
                    TableSchemaValidator.CreateTableDdlParser.parseColumns(
                            dialect.skillCreateTableDdls().get(0)),
                    dialect.getClass().getSimpleName() + " skill DDL must parse");
            assertEquals(
                    SKILL_RESOURCE_COLUMNS,
                    TableSchemaValidator.CreateTableDdlParser.parseColumns(
                            dialect.skillResourcesCreateTableDdls().get(0)),
                    dialect.getClass().getSimpleName() + " skill-resources DDL must parse");
        }
    }

    // ------------------------------------------------------------------
    //  Helpers
    // ------------------------------------------------------------------

    /** Executes every DDL statement of a dialect on the given database. */
    private static void executeDdls(DataSource ds, List<String> ddls) throws SQLException {
        try (Connection conn = ds.getConnection();
                Statement stmt = conn.createStatement()) {
            for (String ddl : ddls) {
                stmt.execute(ddl);
            }
        }
    }

    /** Executes one SQL statement on the given database. */
    private static void execute(DataSource ds, String sql) throws SQLException {
        try (Connection conn = ds.getConnection();
                Statement stmt = conn.createStatement()) {
            stmt.execute(sql);
        }
    }

    /** The table’s actual column names, lower-cased. */
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
}

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

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.agentscope.extensions.jdbc.dialect.TableSchemaValidator.CreateTableDdlParser;
import io.agentscope.extensions.jdbc.dialect.vendor.H2Dialect;
import io.agentscope.extensions.jdbc.dialect.vendor.MysqlDialect;
import io.agentscope.extensions.jdbc.dialect.vendor.PostgresDialect;
import io.agentscope.extensions.jdbc.dialect.vendor.SqliteDialect;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;

/**
 * Unit tests for {@link CreateTableDdlParser} — every in-tree vendor DDL plus tokenizer
 * boundaries (nested parens, quoted identifiers, constraint segments, comments, malformed
 * input).
 *
 * @author shanhongyu
 */
@DisplayName("CreateTableDdlParser column extraction")
class CreateTableDdlParserTest {

    private static final Set<String> STORE_COLUMNS =
            Set.of("namespace_path", "item_key", "value_json", "version", "updated_at");

    private static final Set<String> SESSIONS_COLUMNS =
            Set.of(
                    "session_id",
                    "state_key",
                    "item_index",
                    "state_data",
                    "version",
                    "created_at",
                    "updated_at");

    private static final Set<String> SNAPSHOTS_COLUMNS =
            Set.of("snapshot_id", "data", "created_at");

    /**
     * One argument set per in-tree vendor.
     *
     * @return {@code {dialect simple name, store DDLs, sessions DDLs, snapshots DDLs}} per
     *     vendor
     */
    static List<Object[]> vendorDdls() {
        List<AbstractJdbcDialect> vendors =
                List.of(
                        new H2Dialect(),
                        new MysqlDialect(),
                        new PostgresDialect(),
                        new SqliteDialect());
        return vendors.stream()
                .<Object[]>map(
                        d ->
                                new Object[] {
                                    d.getClass().getSimpleName(),
                                    d.storeCreateTableDdls(),
                                    d.sessionStateCreateTableDdls(),
                                    d.snapshotCreateTableDdls()
                                })
                .toList();
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("vendorDdls")
    @DisplayName("all in-tree vendor DDLs parse to the declared column sets")
    void allVendorDdlsParse(
            String vendor,
            List<String> storeDdls,
            List<String> sessionsDdls,
            List<String> snapshotsDdls) {
        assertEquals(STORE_COLUMNS, unionOf(storeDdls), vendor + " store DDL");
        assertEquals(SESSIONS_COLUMNS, unionOf(sessionsDdls), vendor + " sessions DDL");
        assertEquals(SNAPSHOTS_COLUMNS, unionOf(snapshotsDdls), vendor + " snapshots DDL");
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("vendorDdls")
    @DisplayName("the first parseable statement of a DDL list carries the full column set")
    void firstParseableDdlCarriesAllColumns(
            String vendor,
            List<String> storeDdls,
            List<String> sessionsDdls,
            List<String> snapshotsDdls) {
        assertEquals(STORE_COLUMNS, firstNonEmpty(storeDdls), vendor + " store DDL");
        assertEquals(SESSIONS_COLUMNS, firstNonEmpty(sessionsDdls), vendor + " sessions DDL");
        assertEquals(SNAPSHOTS_COLUMNS, firstNonEmpty(snapshotsDdls), vendor + " snapshots DDL");
    }

    // ------------------------------------------------------------------
    //  Vendors in TableSchemaValidator's isTableMissing list without an
    //  in-tree dialect
    // ------------------------------------------------------------------

    /**
     * One argument set per vendor in {@code TableSchemaValidator.isTableMissing}'s
     * compatibility list that has no in-tree dialect. Vendors sharing a parent grammar are
     * covered by {@link #vendorDdls()}: MariaDB, TiDB and OceanBase (MySQL mode) with
     * MySQL; KingbaseES, openGauss/GaussDB and PolarDB with PostgreSQL; OceanBase (Oracle
     * mode) with Oracle.
     *
     * @return {@code {vendor name, DDL, expected column set}} per vendor
     */
    static List<Object[]> outOfTreeVendorDdls() {
        return List.of(
                new Object[] {
                    "Oracle",
                    """
                    CREATE TABLE agentscope_store (
                      namespace_path VARCHAR2(512) NOT NULL,
                      item_key VARCHAR2(255) NOT NULL,
                      value_json CLOB NOT NULL,
                      version NUMBER(19) DEFAULT 0 NOT NULL,
                      updated_at TIMESTAMP DEFAULT SYSDATE,
                      CONSTRAINT pk_store PRIMARY KEY (namespace_path, item_key),
                      CONSTRAINT ck_path CHECK (namespace_path <> 'x,y')
                    )
                    """,
                    Set.of("namespace_path", "item_key", "value_json", "version", "updated_at")
                },
                new Object[] {
                    "SQL Server",
                    """
                    CREATE TABLE [dbo].[agentscope_store] (
                      [namespace_path] NVARCHAR(512) NOT NULL,
                      [item_key] NVARCHAR(255) NOT NULL,
                      [value_json] NVARCHAR(MAX) NOT NULL,
                      [version] BIGINT NOT NULL DEFAULT 0,
                      [updated_at] DATETIME2 NOT NULL DEFAULT SYSUTCDATETIME(),
                      CONSTRAINT [pk_store] PRIMARY KEY ([namespace_path], [item_key])
                    )
                    """,
                    Set.of("namespace_path", "item_key", "value_json", "version", "updated_at")
                },
                new Object[] {
                    "DB2",
                    """
                    CREATE TABLE agentscope_sessions (
                      id BIGINT NOT NULL GENERATED BY DEFAULT AS IDENTITY,
                      session_id VARCHAR(255) NOT NULL,
                      state_data CLOB(1048576) NOT NULL,
                      version BIGINT NOT NULL WITH DEFAULT 0,
                      PRIMARY KEY (id)
                    ) ORGANIZE BY ROW
                    """,
                    Set.of("id", "session_id", "state_data", "version")
                },
                new Object[] {
                    "HSQLDB",
                    """
                    CREATE TABLE agentscope_sessions (
                      id BIGINT GENERATED BY DEFAULT AS IDENTITY PRIMARY KEY,
                      session_id VARCHAR(255) NOT NULL,
                      state_data CLOB NOT NULL,
                      version BIGINT NOT NULL DEFAULT 0
                    )
                    """,
                    Set.of("id", "session_id", "state_data", "version")
                },
                new Object[] {
                    "Derby",
                    """
                    CREATE TABLE agentscope_sessions (
                      id BIGINT NOT NULL GENERATED ALWAYS AS IDENTITY,
                      session_id VARCHAR(255) NOT NULL,
                      state_data CLOB NOT NULL,
                      version BIGINT NOT NULL DEFAULT 0,
                      CONSTRAINT pk_sessions PRIMARY KEY (id)
                    )
                    """,
                    Set.of("id", "session_id", "state_data", "version")
                },
                new Object[] {
                    "Firebird",
                    """
                    CREATE TABLE agentscope_snapshots (
                      snapshot_id VARCHAR(64) NOT NULL,
                      data BLOB SUBTYPE TEXT NOT NULL,
                      created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
                      CONSTRAINT pk_snapshots PRIMARY KEY (snapshot_id)
                    )
                    """,
                    Set.of("snapshot_id", "data", "created_at")
                },
                new Object[] {
                    "DM",
                    """
                    CREATE TABLE agentscope_snapshots (
                      snapshot_id VARCHAR(64) NOT NULL,
                      data CLOB NOT NULL,
                      created_at TIMESTAMP(6) DEFAULT CURRENT_TIMESTAMP,
                      CONSTRAINT pk_snapshots PRIMARY KEY (snapshot_id)
                    )
                    """,
                    Set.of("snapshot_id", "data", "created_at")
                },
                new Object[] {
                    "Informix / GBase 8s",
                    """
                    CREATE TABLE agentscope_sessions (
                      id SERIAL NOT NULL,
                      session_id VARCHAR(255) NOT NULL,
                      state_data LVARCHAR(4096) NOT NULL,
                      version INTEGER NOT NULL,
                      PRIMARY KEY (session_id)
                    ) LOCK MODE ROW
                    """,
                    Set.of("id", "session_id", "state_data", "version")
                });
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("outOfTreeVendorDdls")
    @DisplayName("vendor shapes without in-tree dialects parse (isTableMissing envelope)")
    void outOfTreeVendorDdlsParse(String vendor, String ddl, Set<String> expected) {
        assertEquals(expected, CreateTableDdlParser.parseColumns(ddl), vendor + " DDL");
    }

    // ------------------------------------------------------------------
    //  Tokenizer boundaries
    // ------------------------------------------------------------------

    @Test
    @DisplayName("commas nested in types like DECIMAL(10,2) do not split segments")
    void nestedParenCommasDoNotSplit() {
        Set<String> columns =
                CreateTableDdlParser.parseColumns(
                        "CREATE TABLE t ("
                                + " amount DECIMAL(10,2) NOT NULL,"
                                + " ratio DECIMAL(5, 3),"
                                + " name VARCHAR(50)"
                                + ")");
        assertEquals(Set.of("amount", "ratio", "name"), columns);
    }

    @Test
    @DisplayName("quoted identifiers are unquoted and kept")
    void quotedIdentifiersAreUnquoted() {
        Set<String> columns =
                CreateTableDdlParser.parseColumns(
                        "CREATE TABLE t (\"CamelCol\" INT, `tick_col` TEXT, [bracket_col] INT,"
                                + " plain INT)");
        assertEquals(Set.of("camelcol", "tick_col", "bracket_col", "plain"), columns);
    }

    @Test
    @DisplayName("a quoted PRIMARY is a column, not a constraint keyword")
    void quotedKeywordIsAColumn() {
        Set<String> columns =
                CreateTableDdlParser.parseColumns("CREATE TABLE t (\"primary\" INT, id INT)");
        assertEquals(Set.of("primary", "id"), columns);
    }

    @Test
    @DisplayName("constraint segments are skipped, not mistaken for columns")
    void constraintSegmentsAreSkipped() {
        Set<String> columns =
                CreateTableDdlParser.parseColumns(
                        "CREATE TABLE t ("
                                + " id INT,"
                                + " parent_id INT,"
                                + " PRIMARY KEY (id),"
                                + " FOREIGN KEY (parent_id) REFERENCES p(id),"
                                + " UNIQUE KEY uq (id, parent_id),"
                                + " KEY idx_t (parent_id),"
                                + " INDEX idx2 (parent_id),"
                                + " CONSTRAINT ck CHECK (id > 0),"
                                + " CHECK (id < 100),"
                                + " EXCLUDE USING gist (id WITH =),"
                                + " FULLTEXT KEY ft (id),"
                                + " SPATIAL INDEX sp (id)"
                                + ")");
        assertEquals(Set.of("id", "parent_id"), columns);
    }

    @Test
    @DisplayName("multi-line text-block DDL with inline COLLATE modifiers parses")
    void textBlockDdlParses() {
        Set<String> columns =
                CreateTableDdlParser.parseColumns(
                        """
                        CREATE TABLE IF NOT EXISTS legacy_sessions (
                          session_id  VARCHAR(255) COLLATE utf8mb4_bin NOT NULL,
                          state_key   VARCHAR(255) COLLATE utf8mb4_bin NOT NULL,
                          state_data  LONGTEXT     NOT NULL,
                          version     BIGINT       NOT NULL DEFAULT 0,
                          PRIMARY KEY (session_id, state_key),
                          INDEX idx_session (session_id)
                        ) DEFAULT CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci\
                        """);
        assertEquals(Set.of("session_id", "state_key", "state_data", "version"), columns);
    }

    @Test
    @DisplayName("line and block comments are ignored")
    void commentsAreIgnored() {
        Set<String> columns =
                CreateTableDdlParser.parseColumns(
                        """
                        -- table comment, with a comma
                        CREATE TABLE /* block, comment */ t (
                          id INT, -- trailing comment, with a comma
                          name TEXT /* inline, comment */
                        )\
                        """);
        assertEquals(Set.of("id", "name"), columns);
    }

    @Test
    @DisplayName("column names are lower-cased for case-insensitive comparison")
    void columnNamesAreLowerCased() {
        Set<String> columns =
                CreateTableDdlParser.parseColumns("CREATE TABLE T (Id INT, NAME VARCHAR(10))");
        assertEquals(Set.of("id", "name"), columns);
    }

    // ------------------------------------------------------------------
    //  Unparseable input → empty set (validation skipped)
    // ------------------------------------------------------------------

    @Test
    @DisplayName("null and blank input return an empty set")
    void nullAndBlankReturnEmpty() {
        assertTrue(CreateTableDdlParser.parseColumns(null).isEmpty());
        assertTrue(CreateTableDdlParser.parseColumns("").isEmpty());
        assertTrue(CreateTableDdlParser.parseColumns("   ").isEmpty());
    }

    @Test
    @DisplayName("non-CREATE-TABLE statements return an empty set")
    void nonCreateTableStatementsReturnEmpty() {
        assertTrue(
                CreateTableDdlParser.parseColumns(
                                "CREATE INDEX idx_session ON agentscope_sessions (session_id)")
                        .isEmpty());
        assertTrue(CreateTableDdlParser.parseColumns("DROP TABLE agentscope_sessions").isEmpty());
        assertTrue(CreateTableDdlParser.parseColumns("CREATE VIEW v AS SELECT 1").isEmpty());
        assertTrue(CreateTableDdlParser.parseColumns("insert into t values (1)").isEmpty());
    }

    @Test
    @DisplayName("malformed CREATE TABLE statements return an empty set")
    void malformedCreateTableReturnsEmpty() {
        // No column list at all.
        assertTrue(CreateTableDdlParser.parseColumns("CREATE TABLE t").isEmpty());
        // Unterminated column list.
        assertTrue(CreateTableDdlParser.parseColumns("CREATE TABLE t (id INT").isEmpty());
        // A segment that is not a recognizable identifier: refuse to guess.
        assertTrue(CreateTableDdlParser.parseColumns("CREATE TABLE t (123 INT, id INT)").isEmpty());
        // Empty quoted identifier.
        assertTrue(
                CreateTableDdlParser.parseColumns("CREATE TABLE t (\"\" INT, id INT)").isEmpty());
    }

    @Test
    @DisplayName("temporary-table variants still parse")
    void temporaryTableVariantsParse() {
        assertEquals(
                Set.of("id"),
                CreateTableDdlParser.parseColumns("CREATE TEMPORARY TABLE t (id INT)"));
        assertEquals(
                Set.of("id"),
                CreateTableDdlParser.parseColumns("create unlogged table t (id INT)"));
    }

    /**
     * Union of the columns parsed from every DDL in the list.
     *
     * @param ddls the dialect's DDL statements
     * @return all columns any DDL declares
     */
    private static Set<String> unionOf(List<String> ddls) {
        Set<String> union = new LinkedHashSet<>();
        for (String ddl : ddls) {
            union.addAll(CreateTableDdlParser.parseColumns(ddl));
        }
        return union;
    }

    /**
     * Columns of the first DDL that parses; empty when none does.
     *
     * @param ddls the dialect's DDL statements
     * @return the first parseable column set, or empty
     */
    private static Set<String> firstNonEmpty(List<String> ddls) {
        for (String ddl : ddls) {
            Set<String> columns = CreateTableDdlParser.parseColumns(ddl);
            if (!columns.isEmpty()) {
                return columns;
            }
        }
        return Set.of();
    }
}

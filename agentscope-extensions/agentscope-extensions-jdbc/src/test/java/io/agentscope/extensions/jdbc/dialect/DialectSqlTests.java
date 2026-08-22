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
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.agentscope.extensions.jdbc.dialect.vendor.H2Dialect;
import io.agentscope.extensions.jdbc.dialect.vendor.MysqlDialect;
import io.agentscope.extensions.jdbc.dialect.vendor.PostgresDialect;
import io.agentscope.extensions.jdbc.dialect.vendor.SqliteDialect;
import java.io.ByteArrayInputStream;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Unit tests for dialect SQL generation, verifying vendor-specific syntax differences.
 *
 * @author shanhongyu
 */
@DisplayName("Dialect SQL generation tests")
class DialectSqlTests {

    // ------------------------------------------------------------------
    //  Table-name resolution (prefix + base, override)
    // ------------------------------------------------------------------

    @Test
    @DisplayName("default prefix produces expected table names")
    void defaultPrefixTableNames() {
        var d = new PostgresDialect();
        assertEquals("agentscope_store", d.storeTableName());
        assertEquals("agentscope_sessions", d.sessionStateTableName());
        assertEquals("agentscope_snapshots", d.snapshotTableName());
    }

    @Test
    @DisplayName("custom prefix via builder")
    void customPrefixTableNames() {
        var ds = new org.h2.jdbcx.JdbcDataSource();
        ds.setUrl("jdbc:h2:mem:prefix_test;DB_CLOSE_DELAY=-1");
        ds.setUser("sa");
        var d = AbstractJdbcDialect.from(ds).tablePrefix("custom_").autoCreateTable(false).build();
        assertEquals("custom_store", d.storeTableName());
        assertEquals("custom_sessions", d.sessionStateTableName());
    }

    @Test
    @DisplayName("per-table name override takes priority over prefix")
    void perTableOverride() {
        var ds = new org.h2.jdbcx.JdbcDataSource();
        ds.setUrl("jdbc:h2:mem:override_test;DB_CLOSE_DELAY=-1");
        ds.setUser("sa");
        var d =
                AbstractJdbcDialect.from(ds)
                        .tablePrefix("custom_")
                        .storeTableName("my_kv_table")
                        .autoCreateTable(false)
                        .build();
        assertEquals("my_kv_table", d.storeTableName());
        assertEquals("custom_sessions", d.sessionStateTableName());
    }

    // ------------------------------------------------------------------
    //  StoreDialect — UPSERT syntax differences
    // ------------------------------------------------------------------

    @Test
    @DisplayName("PostgresDialect storeUpsert uses ON CONFLICT")
    void postgresStoreUpsertUsesOnConflict() {
        BoundSql bs = new PostgresDialect().storeUpsert("ns", "k", "{}", 1L);
        assertTrue(bs.sql().contains("ON CONFLICT"));
        assertTrue(bs.sql().contains("EXCLUDED.value_json"));
        assertEquals(4, bs.params().size());
    }

    @Test
    @DisplayName("MysqlDialect storeUpsert uses ON DUPLICATE KEY")
    void mysqlStoreUpsertUsesOnDuplicateKey() {
        BoundSql bs = new MysqlDialect().storeUpsert("ns", "k", "{}", 1L);
        assertTrue(bs.sql().contains("ON DUPLICATE KEY UPDATE"));
        assertTrue(bs.sql().contains("VALUES(value_json)"));
    }

    @Test
    @DisplayName("H2Dialect storeUpsert uses MERGE INTO")
    void h2StoreUpsertUsesMergeInto() {
        BoundSql bs = new H2Dialect().storeUpsert("ns", "k", "{}", 1L);
        assertTrue(bs.sql().contains("MERGE INTO"));
    }

    @Test
    @DisplayName("SqliteDialect storeUpsert uses ON CONFLICT")
    void sqliteStoreUpsertUsesOnConflict() {
        BoundSql bs = new SqliteDialect().storeUpsert("ns", "k", "{}", 1L);
        assertTrue(bs.sql().contains("ON CONFLICT"));
    }

    // ------------------------------------------------------------------
    //  StoreDialect — DDL type differences
    // ------------------------------------------------------------------

    @Test
    @DisplayName("MysqlDialect store DDL has LONGTEXT and ENGINE=InnoDB")
    void mysqlStoreDdlHasInnoDB() {
        String ddl = new MysqlDialect().storeCreateTableDdls().get(0);
        assertTrue(ddl.contains("LONGTEXT"));
        assertTrue(ddl.contains("ENGINE=InnoDB"));
        assertTrue(ddl.contains("utf8mb4"));
    }

    @Test
    @DisplayName("SqliteDialect store DDL uses TEXT and INTEGER")
    void sqliteStoreDdlUsesTextInteger() {
        String ddl = new SqliteDialect().storeCreateTableDdls().get(0);
        assertTrue(ddl.contains("TEXT"));
        assertTrue(ddl.contains("INTEGER"));
    }

    @Test
    @DisplayName("H2Dialect store DDL uses CLOB")
    void h2StoreDdlUsesClob() {
        assertTrue(new H2Dialect().storeCreateTableDdls().get(0).contains("CLOB"));
    }

    // ------------------------------------------------------------------
    //  Secondary indexes (namespace_path / session_id)
    // ------------------------------------------------------------------

    @Test
    @DisplayName("MysqlDialect inlines the namespace and session indexes in CREATE TABLE")
    void mysqlDdlInlinesIndexes() {
        var d = new MysqlDialect();
        List<String> store = d.storeCreateTableDdls();
        assertEquals(1, store.size());
        assertTrue(store.get(0).contains("INDEX idx_namespace (namespace_path)"));

        List<String> session = d.sessionStateCreateTableDdls();
        assertEquals(1, session.size());
        assertTrue(session.get(0).contains("INDEX idx_session (session_id)"));

        // MySQL has no CREATE INDEX IF NOT EXISTS, so the three tables stay three DDLs.
        assertEquals(3, d.createTableDdls().size());
    }

    @Test
    @DisplayName("PostgresDialect emits a separate idempotent index DDL after each CREATE TABLE")
    void postgresIndexDdl() {
        assertStoreAndSessionIndexes(new PostgresDialect());
    }

    @Test
    @DisplayName("H2 and SQLite dialects emit a separate idempotent index DDL")
    void h2AndSqliteIndexDdl() {
        for (AbstractJdbcDialect d : List.of(new H2Dialect(), new SqliteDialect())) {
            assertStoreAndSessionIndexes(d);
        }
    }

    private static void assertStoreAndSessionIndexes(AbstractJdbcDialect d) {
        List<String> store = d.storeCreateTableDdls();
        assertEquals(2, store.size());
        assertTrue(store.get(0).startsWith("CREATE TABLE IF NOT EXISTS"));
        assertTrue(store.get(1).startsWith("CREATE INDEX IF NOT EXISTS"));
        assertTrue(store.get(1).contains("namespace_path"));

        List<String> session = d.sessionStateCreateTableDdls();
        assertEquals(2, session.size());
        assertTrue(session.get(0).startsWith("CREATE TABLE IF NOT EXISTS"));
        assertTrue(session.get(1).startsWith("CREATE INDEX IF NOT EXISTS"));
        assertTrue(session.get(1).contains("session_id"));
    }

    // ------------------------------------------------------------------
    //  SessionStateDialect — UPSERT + table existence check
    // ------------------------------------------------------------------

    @Test
    @DisplayName("MysqlDialect state UPSERT uses ON DUPLICATE KEY and INFORMATION_SCHEMA")
    void mysqlStateUpsertAndCheck() {
        var d = new MysqlDialect();
        BoundSql upsert = d.sessionStateUpsert("sid", "key", 0, "data");
        assertTrue(upsert.sql().contains("ON DUPLICATE KEY UPDATE"));

        BoundSql check = d.sessionStateCheckTableExists("my_table");
        assertTrue(check.sql().contains("DATABASE()"));
    }

    @Test
    @DisplayName("H2Dialect state UPSERT uses MERGE INTO and INFORMATION_SCHEMA")
    void h2StateUpsertAndCheck() {
        var d = new H2Dialect();
        BoundSql upsert = d.sessionStateUpsert("sid", "key", 0, "data");
        assertTrue(upsert.sql().contains("MERGE INTO"));

        BoundSql check = d.sessionStateCheckTableExists("my_table");
        assertTrue(check.sql().contains("INFORMATION_SCHEMA"));
    }

    @Test
    @DisplayName("SqliteDialect state check uses sqlite_master")
    void sqliteStateCheckUsesSqliteMaster() {
        BoundSql check = new SqliteDialect().sessionStateCheckTableExists("my_table");
        assertTrue(check.sql().contains("sqlite_master"));
    }

    // ------------------------------------------------------------------
    //  SnapshotDialect — UPSERT + DDL differences
    // ------------------------------------------------------------------

    @Test
    @DisplayName("PostgresDialect snapshot DDL uses BYTEA")
    void postgresSnapshotDdlUsesBytea() {
        assertTrue(new PostgresDialect().snapshotCreateTableDdls().get(0).contains("BYTEA"));
    }

    @Test
    @DisplayName("MysqlDialect snapshot DDL uses LONGBLOB")
    void mysqlSnapshotDdlUsesLongBlob() {
        assertTrue(new MysqlDialect().snapshotCreateTableDdls().get(0).contains("LONGBLOB"));
    }

    @Test
    @DisplayName("H2Dialect snapshot UPSERT uses full MERGE INTO with created_at update")
    void h2SnapshotUpsertUpdatesCreatedAt() {
        BoundSql bs =
                new H2Dialect().snapshotUpsert("snap", new ByteArrayInputStream(new byte[] {1, 2}));
        assertTrue(bs.sql().contains("MERGE INTO"));
        assertTrue(bs.sql().contains("WHEN MATCHED THEN UPDATE SET"));
        assertTrue(bs.sql().contains("created_at = CURRENT_TIMESTAMP"));
    }

    // ------------------------------------------------------------------
    //  ANSI defaults inherited identically
    // ------------------------------------------------------------------

    @Test
    @DisplayName("all dialects inherit identical storeInsert from default")
    void allInheritStoreInsert() {
        String expected =
                "INSERT INTO agentscope_store"
                        + " (namespace_path, item_key, value_json, version, updated_at)"
                        + " VALUES (?, ?, ?, 1, ?)";
        assertEquals(expected, new PostgresDialect().storeInsert("a", "b", "c", 1L).sql());
        assertEquals(expected, new MysqlDialect().storeInsert("a", "b", "c", 1L).sql());
        assertEquals(expected, new H2Dialect().storeInsert("a", "b", "c", 1L).sql());
        assertEquals(expected, new SqliteDialect().storeInsert("a", "b", "c", 1L).sql());
    }

    @Test
    @DisplayName("MysqlDialect IS a SandboxLockStrategy")
    void mysqlDialectIsLockStrategy() {
        assertTrue(
                new MysqlDialect()
                        instanceof io.agentscope.extensions.jdbc.dialect.SandboxLockStrategy);
    }

    // ------------------------------------------------------------------
    //  InnoDB utf8mb4 index limit (ported from MysqlJdbcStoreDialectTest)
    // ------------------------------------------------------------------

    private static final int INNODB_UTF8MB4_INDEX_LIMIT_BYTES = 3072;
    private static final int UTF8MB4_MAX_BYTES_PER_CHAR = 4;

    @Test
    @DisplayName("MysqlDialect store PK fits InnoDB utf8mb4 3072-byte limit")
    void mysqlStorePkFitsInnoDbUtf8mb4Limit() {
        String ddl = new MysqlDialect().storeCreateTableDdls().get(0);

        int namespacePathLength = varcharLength(ddl, "namespace_path");
        int itemKeyLength = varcharLength(ddl, "item_key");
        long compositePkBytes =
                (long) (namespacePathLength + itemKeyLength) * UTF8MB4_MAX_BYTES_PER_CHAR;

        assertTrue(
                compositePkBytes <= INNODB_UTF8MB4_INDEX_LIMIT_BYTES,
                () ->
                        String.format(
                                "Composite PK is %d bytes, over the InnoDB utf8mb4 limit of %d"
                                        + " bytes",
                                compositePkBytes, INNODB_UTF8MB4_INDEX_LIMIT_BYTES));
    }

    private static int varcharLength(String ddl, String columnName) {
        Matcher matcher =
                Pattern.compile("(?i)\\b" + Pattern.quote(columnName) + "\\s+VARCHAR\\((\\d+)\\)")
                        .matcher(ddl);
        if (!matcher.find()) {
            throw new IllegalStateException("Missing VARCHAR definition for " + columnName);
        }
        return Integer.parseInt(matcher.group(1));
    }

    // ------------------------------------------------------------------
    //  Table-name validation (SQL injection guard)
    // ------------------------------------------------------------------

    @Test
    @DisplayName("builder rejects invalid table prefix")
    void builderRejectsInvalidPrefix() {
        var ds = new org.h2.jdbcx.JdbcDataSource();
        ds.setUrl("jdbc:h2:mem:validation_test;DB_CLOSE_DELAY=-1");
        ds.setUser("sa");
        assertThrows(
                IllegalArgumentException.class,
                () -> AbstractJdbcDialect.from(ds).tablePrefix("evil; DROP TABLE"));
    }

    @Test
    @DisplayName("builder rejects invalid store table name")
    void builderRejectsInvalidTableName() {
        var ds = new org.h2.jdbcx.JdbcDataSource();
        ds.setUrl("jdbc:h2:mem:validation_test2;DB_CLOSE_DELAY=-1");
        ds.setUser("sa");
        assertThrows(
                IllegalArgumentException.class,
                () -> AbstractJdbcDialect.from(ds).storeTableName("t; DROP TABLE users"));
    }

    @Test
    @DisplayName("builder accepts valid table prefix and name")
    void builderAcceptsValidNames() {
        var ds = new org.h2.jdbcx.JdbcDataSource();
        ds.setUrl("jdbc:h2:mem:validation_test3;DB_CLOSE_DELAY=-1");
        ds.setUser("sa");
        var d =
                AbstractJdbcDialect.from(ds)
                        .tablePrefix("my_app_")
                        .storeTableName("my_store")
                        .autoCreateTable(false)
                        .build();
        assertEquals("my_store", d.storeTableName());
        assertEquals("my_app_sessions", d.sessionStateTableName());
    }
}

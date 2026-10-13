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
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.agentscope.extensions.jdbc.dialect.vendor.H2Dialect;
import io.agentscope.extensions.jdbc.dialect.vendor.MysqlDialect;
import io.agentscope.extensions.jdbc.dialect.vendor.PostgresDialect;
import io.agentscope.extensions.jdbc.dialect.vendor.SqliteDialect;
import java.io.ByteArrayInputStream;
import java.util.ArrayList;
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
        var d = AbstractJdbcDialect.from(ds).tablePrefix("custom_").build();
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
    @DisplayName("MysqlDialect key columns pin a binary collation so keys stay case-sensitive")
    void mysqlKeyColumnsUseBinaryCollation() {
        var d = new MysqlDialect();

        // Compared against whitespace-normalised DDL: the column layout is cosmetic, only the
        // column/collation pairing is contractual.
        assertTrue(
                normalise(d.storeCreateTableDdls().get(0))
                        .contains("namespace_path VARCHAR(512) COLLATE utf8mb4_bin NOT NULL"));
        assertTrue(
                normalise(d.storeCreateTableDdls().get(0))
                        .contains("item_key VARCHAR(255) COLLATE utf8mb4_bin NOT NULL"));

        String session = normalise(d.sessionStateCreateTableDdls().get(0));
        assertTrue(session.contains("session_id VARCHAR(255) COLLATE utf8mb4_bin NOT NULL"));
        assertTrue(session.contains("state_key VARCHAR(255) COLLATE utf8mb4_bin NOT NULL"));

        // snapshot_id is a primary key too, so it gets the same treatment.
        assertTrue(
                normalise(d.snapshotCreateTableDdls().get(0))
                        .contains(
                                "snapshot_id VARCHAR(512) COLLATE utf8mb4_bin NOT NULL PRIMARY"
                                        + " KEY"));
    }

    private static String normalise(final String ddl) {
        return ddl.replaceAll("\\s+", " ").trim();
    }

    @Test
    @DisplayName("only MysqlDialect key columns are case-sensitive; payload columns are untouched")
    void mysqlPayloadColumnsKeepDefaultCollation() {
        String store = normalise(new MysqlDialect().storeCreateTableDdls().get(0));
        assertTrue(store.contains("value_json LONGTEXT NOT NULL"));
        assertFalse(store.contains("LONGTEXT COLLATE"));

        // The other dialects compare keys case-sensitively by default, so no explicit
        // collation must leak into their DDL.
        for (AbstractJdbcDialect d :
                List.of(new PostgresDialect(), new H2Dialect(), new SqliteDialect())) {
            assertFalse(d.storeCreateTableDdls().get(0).contains("COLLATE"));
            assertFalse(d.sessionStateCreateTableDdls().get(0).contains("COLLATE"));
        }
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
        assertEquals(3, d.createTableDdls().values().stream().mapToInt(List::size).sum());
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
    @DisplayName("MysqlDialect state UPSERT uses ON DUPLICATE KEY UPDATE")
    void mysqlStateUpsert() {
        var d = new MysqlDialect();
        BoundSql upsert = d.sessionStateUpsert("sid", "key", 0, "data");
        assertTrue(upsert.sql().contains("ON DUPLICATE KEY UPDATE"));

        assertTrue(upsert.sql().replaceAll("\\s+", " ").contains("version = version + 1"));
    }

    @Test
    @DisplayName("PostgresDialect state UPSERT increments the stored version on conflict")
    void postgresStateUpsertIncrementsVersion() {
        var d = new PostgresDialect();
        BoundSql upsert = d.sessionStateUpsert("sid", "key", 0, "data");
        assertTrue(
                upsert.sql().contains("ON CONFLICT (session_id, state_key, item_index) DO UPDATE"));
        assertTrue(
                upsert.sql()
                        .replaceAll("\\s+", " ")
                        .contains("version = " + d.sessionStateTableName() + ".version + 1"));
    }

    @Test
    @DisplayName("H2Dialect state UPSERT uses MERGE INTO")
    void h2StateUpsert() {
        var d = new H2Dialect();
        BoundSql upsert = d.sessionStateUpsert("sid", "key", 0, "data");
        assertTrue(upsert.sql().contains("MERGE INTO"));
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
    //  SkillDialect — namespace-scoped statements
    // ------------------------------------------------------------------

    @Test
    @DisplayName("skill statements scope every operation to one namespace")
    void skillSqlIsNamespaceScoped() {
        var d = new H2Dialect();

        BoundSql select = d.skillSelectByName("team-a", "code-review");
        assertEquals(
                "SELECT id, name, description, skill_content, source, metadata_json FROM"
                        + " agentscope_skills WHERE namespace = ? AND name = ?",
                select.sql());
        assertEquals(List.of("team-a", "code-review"), select.params());

        assertTrue(d.skillSelectAll("team-a").sql().endsWith("WHERE namespace = ? ORDER BY name"));
        assertTrue(
                d.skillSelectAllNames("team-a")
                        .sql()
                        .contains("WHERE namespace = ? ORDER BY name"));
        assertTrue(d.skillExists("team-a", "s").sql().contains("WHERE namespace = ? AND name = ?"));
        assertEquals(
                "SELECT id FROM agentscope_skills WHERE namespace = ? AND name = ?",
                d.skillSelectIdByName("team-a", "s").sql());

        BoundSql insert = d.skillInsert("team-a", "s", "d", "c", "src", null);
        assertTrue(
                insert.sql()
                        .contains(
                                "(namespace, name, description, skill_content, source,"
                                        + " metadata_json)"));
        assertEquals(
                java.util.Arrays.asList("team-a", "s", "d", "c", "src", null), insert.params());

        assertTrue(
                d.skillDeleteByName("team-a", "s")
                        .sql()
                        .contains("WHERE namespace = ? AND name = ?"));
        assertTrue(d.skillDeleteAll("team-a").sql().contains("WHERE namespace = ?"));

        assertEquals(
                "SELECT id, resource_path, resource_content FROM agentscope_skill_resources"
                        + " WHERE namespace = ?",
                d.skillResourcesSelectAll("team-a").sql());
        assertTrue(d.skillResourcesDeleteAll("team-a").sql().endsWith("WHERE namespace = ?"));
        assertTrue(
                d.skillResourcesInsert("team-a", 1L, "docs/a.md", "a")
                        .sql()
                        .contains("(namespace, id, resource_path, resource_content)"));
    }

    @Test
    @DisplayName("resources DDLs index namespace; MySQL pins it to utf8mb4_bin")
    void skillDdlsIndexAndCollateNamespace() {
        // The resources table is shared by every namespace and its bulk statements filter
        // by namespace, so each vendor must cover that predicate with an index.
        for (AbstractJdbcDialect dialect :
                List.of(new H2Dialect(), new PostgresDialect(), new SqliteDialect())) {
            assertEquals(
                    "CREATE INDEX IF NOT EXISTS "
                            + dialect.skillResourcesTableName()
                            + "_namespace_idx ON "
                            + dialect.skillResourcesTableName()
                            + " (namespace)",
                    dialect.skillResourcesCreateTableDdls().get(1),
                    dialect.getClass().getSimpleName() + " must index resources.namespace");
        }
        assertTrue(
                new MysqlDialect()
                        .skillResourcesCreateTableDdls()
                        .get(0)
                        .contains("INDEX idx_namespace (namespace)"),
                "MySQL declares the resources index inline");

        // The namespace is the isolation boundary: on MySQL it must not fold case under
        // the table's case-insensitive default collation.
        MysqlDialect mysql = new MysqlDialect();
        assertTrue(
                mysql.skillCreateTableDdls().get(0).contains("COLLATE utf8mb4_bin"),
                "MySQL skill DDL must pin namespace to utf8mb4_bin");
        assertTrue(
                mysql.skillResourcesCreateTableDdls().get(0).contains("COLLATE utf8mb4_bin"),
                "MySQL resources DDL must pin namespace to utf8mb4_bin");
    }

    /** The assignments of a statement — the span between SET and WHERE (or the end). */
    private static final Pattern SET_CLAUSE =
            Pattern.compile("(?is)\\bSET\\b(.*?)(?:\\bWHERE\\b|$)");

    /** Whether the statement assigns the namespace column inside its SET clause. */
    private static boolean assignsNamespace(String sql) {
        Matcher setClause = SET_CLAUSE.matcher(sql);
        return setClause.find() && setClause.group(1).matches("(?is).*\\bnamespace\\s*=.*");
    }

    @Test
    @DisplayName("no skill statement assigns namespace in a SET clause (write-once contract)")
    void skillStatementsNeverUpdateNamespace() {
        // Namespace is bound per repository instance and write-once per row: a skill never
        // moves namespaces through this library, so its resources can never be stranded by
        // an in-library write. Updates may exist, but namespace must never be assigned —
        // only the SET clause is checked, so namespace predicates stay legal.
        List<BoundSql> statements = new ArrayList<>();
        for (AbstractJdbcDialect d :
                List.of(
                        new H2Dialect(),
                        new MysqlDialect(),
                        new PostgresDialect(),
                        new SqliteDialect())) {
            statements.add(d.skillSelectByName("ns", "s"));
            statements.add(d.skillSelectAll("ns"));
            statements.add(d.skillSelectAllNames("ns"));
            statements.add(d.skillExists("ns", "s"));
            statements.add(d.skillSelectIdByName("ns", "s"));
            statements.add(d.skillInsert("ns", "s", "d", "c", "src", null));
            statements.add(d.skillDeleteByName("ns", "s"));
            statements.add(d.skillDeleteAll("ns"));
            statements.add(d.skillResourcesInsert("ns", 1L, "docs/a.md", "a"));
            statements.add(d.skillResourcesSelectAll("ns"));
            statements.add(d.skillResourcesDeleteAll("ns"));
            statements.add(d.skillResourcesSelectBySkillId(1L));
            statements.add(d.skillResourcesDeleteBySkillId(1L));
        }
        for (BoundSql statement : statements) {
            assertFalse(
                    assignsNamespace(statement.sql()),
                    "namespace is write-once; no statement may assign it: " + statement.sql());
        }

        // The SET-clause scope is the contract: a namespace predicate is legitimate, an
        // assignment is not — both sides of that line are pinned here.
        assertFalse(
                assignsNamespace("UPDATE t SET description = ? WHERE namespace = ? AND name = ?"),
                "a namespace predicate must not trip the guard");
        assertTrue(
                assignsNamespace("UPDATE t SET namespace = ? WHERE name = ?"),
                "a namespace assignment must trip the guard");
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
    @DisplayName("builder rejects invalid table names, skill overrides included")
    void builderRejectsInvalidTableName() {
        var ds = new org.h2.jdbcx.JdbcDataSource();
        ds.setUrl("jdbc:h2:mem:validation_test2;DB_CLOSE_DELAY=-1");
        ds.setUser("sa");
        assertThrows(
                IllegalArgumentException.class,
                () -> AbstractJdbcDialect.from(ds).storeTableName("t; DROP TABLE users"));
        assertThrows(
                IllegalArgumentException.class,
                () -> AbstractJdbcDialect.from(ds).skillTableName("t; DROP TABLE users"));
        assertThrows(
                IllegalArgumentException.class,
                () -> AbstractJdbcDialect.from(ds).skillResourcesTableName("bad name"));
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
                        .build();
        assertEquals("my_store", d.storeTableName());
        assertEquals("my_app_sessions", d.sessionStateTableName());
    }
}

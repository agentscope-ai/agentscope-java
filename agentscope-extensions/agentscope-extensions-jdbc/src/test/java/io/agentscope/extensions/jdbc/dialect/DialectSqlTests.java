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
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Unit tests for SQL generation in each dialect implementation. These tests verify
 * that the correct database-specific syntax is produced and that ANSI defaults are
 * inherited where expected.
 *
 * @author shanhongyu
 */
@DisplayName("Dialect SQL generation tests")
class DialectSqlTests {

    // ------------------------------------------------------------------
    //  PostgresDialect — ANSI baseline
    // ------------------------------------------------------------------

    @Test
    @DisplayName("PostgresDialect createTable uses VARCHAR and TEXT")
    void postgresCreateTableUsesAnsiTypes() {
        String sql = new PostgresDialect().getCreateTableSql();
        assertTrue(sql.contains("VARCHAR(2048)"), "should use VARCHAR(2048) for namespace_path");
        assertTrue(sql.contains("TEXT"), "should use TEXT for value_json");
        assertFalse(sql.contains("ENGINE="), "should not have MySQL ENGINE clause");
    }

    @Test
    @DisplayName("PostgresDialect upsert uses ON CONFLICT")
    void postgresUpsertUsesOnConflict() {
        String sql = new PostgresDialect().getUpsertSql();
        assertTrue(sql.contains("ON CONFLICT"), "should use ON CONFLICT syntax");
        assertTrue(sql.contains("EXCLUDED.value_json"), "should reference EXCLUDED");
    }

    @Test
    @DisplayName("PostgresDialect blobType is BYTEA")
    void postgresBlobTypeIsBytea() {
        assertEquals("BYTEA", new PostgresDialect().getBlobType());
    }

    @Test
    @DisplayName("PostgresDialect inherits ANSI insertSql from default")
    void postgresInheritsInsertSql() {
        String sql = new PostgresDialect().getInsertSql();
        assertTrue(sql.contains("VALUES (?, ?, ?, 1, ?)"));
    }

    @Test
    @DisplayName("PostgresDialect inherits ANSI casUpdateSql from default")
    void postgresInheritsCasUpdateSql() {
        String sql = new PostgresDialect().getCasUpdateSql();
        assertTrue(sql.contains("version = version + 1"));
        assertTrue(sql.contains("AND version = ?"));
    }

    @Test
    @DisplayName("PostgresDialect checkTableExists uses current_schema()")
    void postgresCheckTableExistsUsesCurrentSchema() {
        String sql = new PostgresDialect().getCheckTableExistsSql();
        assertTrue(sql.contains("current_schema()"));
    }

    // ------------------------------------------------------------------
    //  MysqlDialect — MySQL-specific overrides
    // ------------------------------------------------------------------

    @Test
    @DisplayName("MysqlDialect createTable uses LONGTEXT and ENGINE=InnoDB")
    void mysqlCreateTableUsesLongTextAndInnoDB() {
        String sql = new MysqlDialect().getCreateTableSql();
        assertTrue(sql.contains("LONGTEXT"), "should use LONGTEXT for value_json");
        assertTrue(sql.contains("ENGINE=InnoDB"), "should use ENGINE=InnoDB");
        assertTrue(sql.contains("utf8mb4"), "should specify utf8mb4 charset");
    }

    @Test
    @DisplayName("MysqlDialect PK fits InnoDB utf8mb4 3072-byte limit")
    void mysqlPkFitsInnodbLimit() {
        String ddl = new MysqlDialect().getCreateTableSql();
        // namespace_path VARCHAR(512) + item_key VARCHAR(255) = 767 chars
        // 767 * 4 bytes/char (utf8mb4) = 3068 bytes < 3072 limit
        assertTrue(ddl.contains("VARCHAR(512)"), "namespace_path should be VARCHAR(512)");
        assertTrue(ddl.contains("VARCHAR(255)"), "item_key should be VARCHAR(255)");
    }

    @Test
    @DisplayName("MysqlDialect upsert uses ON DUPLICATE KEY")
    void mysqlUpsertUsesOnDuplicateKey() {
        String sql = new MysqlDialect().getUpsertSql();
        assertTrue(sql.contains("ON DUPLICATE KEY UPDATE"));
        assertTrue(sql.contains("VALUES(value_json)"));
    }

    @Test
    @DisplayName("MysqlDialect quoteIdentifier uses backticks")
    void mysqlQuoteIdentifierUsesBackticks() {
        assertEquals("`my_table`", new MysqlDialect().quoteIdentifier("my_table"));
    }

    @Test
    @DisplayName("MysqlDialect createDatabaseSql returns non-null")
    void mysqlCreateDatabaseSqlIsNonNull() {
        String sql = new MysqlDialect().getCreateDatabaseSql("agentscope");
        assertNotNull(sql);
        assertTrue(sql.contains("CREATE DATABASE IF NOT EXISTS"));
        assertTrue(sql.contains("utf8mb4"));
    }

    @Test
    @DisplayName("MysqlDialect createDatabaseSql is null for ANSI databases")
    void postgresCreateDatabaseSqlIsNull() {
        // The ANSI default returns null — PostgreSQL, H2, SQLite skip DB creation
        assertNull(new PostgresDialect().getCreateDatabaseSql("agentscope"));
        assertNull(new H2Dialect().getCreateDatabaseSql("agentscope"));
        assertNull(new SqliteDialect().getCreateDatabaseSql("agentscope"));
    }

    @Test
    @DisplayName("MysqlDialect fullTableReference includes database prefix")
    void mysqlFullTableReferenceIncludesDatabase() {
        String ref = new MysqlDialect().getFullTableReference("agentscope", "sessions");
        assertEquals("`agentscope`.`sessions`", ref);
    }

    @Test
    @DisplayName("PostgresDialect fullTableReference is just quoted table name")
    void postgresFullTableReferenceIsQuotedTable() {
        String ref = new PostgresDialect().getFullTableReference("agentscope", "sessions");
        assertEquals("\"sessions\"", ref);
    }

    @Test
    @DisplayName("MysqlDialect blobType is LONGBLOB")
    void mysqlBlobTypeIsLongBlob() {
        assertEquals("LONGBLOB", new MysqlDialect().getBlobType());
    }

    // ------------------------------------------------------------------
    //  H2Dialect
    // ------------------------------------------------------------------

    @Test
    @DisplayName("H2Dialect createTable uses CLOB")
    void h2CreateTableUsesClob() {
        String sql = new H2Dialect().getCreateTableSql();
        assertTrue(sql.contains("CLOB"), "should use CLOB for value_json");
    }

    @Test
    @DisplayName("H2Dialect upsert uses MERGE INTO")
    void h2UpsertUsesMergeInto() {
        String sql = new H2Dialect().getUpsertSql();
        assertTrue(sql.contains("MERGE INTO"), "H2 should use MERGE INTO for UPSERT");
    }

    @Test
    @DisplayName("H2Dialect checkTableExists uses INFORMATION_SCHEMA")
    void h2CheckTableExistsUsesInformationSchema() {
        String sql = new H2Dialect().getCheckTableExistsSql();
        assertTrue(sql.contains("INFORMATION_SCHEMA.TABLES"));
    }

    // ------------------------------------------------------------------
    //  SqliteDialect
    // ------------------------------------------------------------------

    @Test
    @DisplayName("SqliteDialect createTable uses TEXT and INTEGER")
    void sqliteCreateTableUsesTextAndInteger() {
        String sql = new SqliteDialect().getCreateTableSql();
        assertTrue(sql.contains("TEXT"), "should use TEXT for string columns");
        assertTrue(sql.contains("INTEGER"), "should use INTEGER for numeric columns");
        assertFalse(sql.contains("VARCHAR"), "should not use VARCHAR");
    }

    @Test
    @DisplayName("SqliteDialect checkTableExists uses sqlite_master")
    void sqliteCheckTableExistsUsesSqliteMaster() {
        String sql = new SqliteDialect().getCheckTableExistsSql();
        assertTrue(sql.contains("sqlite_master"));
    }

    @Test
    @DisplayName("SqliteDialect upsert uses ON CONFLICT")
    void sqliteUpsertUsesOnConflict() {
        String sql = new SqliteDialect().getUpsertSql();
        assertTrue(sql.contains("ON CONFLICT"));
    }

    // ------------------------------------------------------------------
    //  Cross-dialect: ANSI defaults inherited
    // ------------------------------------------------------------------

    @Test
    @DisplayName("All dialects inherit identical insertSql from default")
    void allDialectsInheritIdenticalInsertSql() {
        String expected =
                new JdbcStoreDialect() {
                    @Override
                    public String getCreateTableSql() {
                        return "";
                    }

                    @Override
                    public String getUpsertSql() {
                        return "";
                    }
                }.getInsertSql();

        assertEquals(expected, new PostgresDialect().getInsertSql());
        assertEquals(expected, new MysqlDialect().getInsertSql());
        assertEquals(expected, new H2Dialect().getInsertSql());
        assertEquals(expected, new SqliteDialect().getInsertSql());
    }

    @Test
    @DisplayName("All dialects inherit identical casUpdateSql from default")
    void allDialectsInheritIdenticalCasUpdateSql() {
        String expected =
                new JdbcStoreDialect() {
                    @Override
                    public String getCreateTableSql() {
                        return "";
                    }

                    @Override
                    public String getUpsertSql() {
                        return "";
                    }
                }.getCasUpdateSql();

        assertEquals(expected, new PostgresDialect().getCasUpdateSql());
        assertEquals(expected, new MysqlDialect().getCasUpdateSql());
        assertEquals(expected, new H2Dialect().getCasUpdateSql());
        assertEquals(expected, new SqliteDialect().getCasUpdateSql());
    }
}

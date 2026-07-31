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

/**
 * Database-specific SQL for the sessions table operated on by
 * {@code io.agentscope.extensions.jdbc.state.JdbcAgentStateStore}.
 *
 * <p>The sessions table stores agent state keyed by {@code (session_id, state_key,
 * item_index)} — single states use {@code item_index = 0}; list states use one row
 * per element with incrementing {@code item_index}.
 *
 * <h2>Abstract methods (variation points)</h2>
 * <ul>
 *   <li>{@link #getCreateSessionsTableSql()} — DDL types and options differ per
 *       database (LONGTEXT / TEXT / CLOB; ENGINE=InnoDB for MySQL).
 *   <li>{@link #getUpsertStateSql()} — UPSERT syntax differs per database.
 *   <li>{@link #getCheckTableExistsSql()} — table-existence metadata queries
 *       differ ({@code information_schema} vs {@code sqlite_master} etc.).
 * </ul>
 *
 * <h2>Default methods (ANSI baseline)</h2>
 * <ul>
 *   <li>{@link #getInsertStateSql()} — plain ANSI INSERT for list items.
 *   <li>{@link #getSelectStateSql()} — single-state fetch by item_index.
 *   <li>{@link #getSelectStateListSql()} — ordered list fetch.
 *   <li>{@link #getDeleteStateByKeySql()} — delete all items for a state key.
 *   <li>{@link #getDeleteSessionSql()} — delete an entire session.
 *   <li>{@link #getSelectMaxIndexSql()} — {@code MAX(item_index)} for change
 *       detection.
 *   <li>{@link #getExistsSql()} — session existence probe.
 *   <li>{@link #getListSessionIdsSql()} — list sessions by user prefix.
 *   <li>{@link #quoteIdentifier(String)} — ANSI double-quote identifier quoting.
 *   <li>{@link #getCreateDatabaseSql(String)} — returns {@code null} (skip) by
 *       default; overridden by MySQL to emit {@code CREATE DATABASE IF NOT EXISTS}.
 *   <li>{@link #getFullTableReference(String, String)} — ANSI baseline returns
 *       only the quoted table name; MySQL overrides to prepend the database name.
 * </ul>
 *
 * <p>All SQL templates use {@code %s} for the resolved table reference (produced by
 * {@link #getFullTableReference}).
 *
 * @author shanhongyu
 */
public interface AgentStateStoreDialect {

    // ------------------------------------------------------------------
    //  Abstract — must override per database
    // ------------------------------------------------------------------

    /**
     * Returns the {@code CREATE TABLE IF NOT EXISTS} DDL for the sessions table.
     *
     * <p>Required columns: {@code session_id VARCHAR(255)},
     * {@code state_key VARCHAR(255)}, {@code item_index INT},
     * {@code state_data} (TEXT-like), plus a primary key on
     * {@code (session_id, state_key, item_index)}.
     *
     * @return CREATE TABLE statement; must use {@code %s} for the table name
     */
    String getCreateSessionsTableSql();

    /**
     * Returns the UPSERT statement for a single state value.
     *
     * <p>Bind parameters in order: {@code (session_id, state_key, item_index,
     * state_data)}. On conflict, the implementation must update
     * {@code state_data} with the new value.
     *
     * @return UPSERT statement; must use {@code %s} for the table name
     */
    String getUpsertStateSql();

    /**
     * Returns the SQL to check whether the sessions table exists.
     *
     * <p>One bind parameter: the table name (string). The implementation is
     * responsible for scoping the search to the right schema/database using
     * built-in functions (e.g. {@code current_schema()}, {@code DATABASE()}).
     *
     * @return table-existence probe SQL with one {@code ?} placeholder for the table name
     */
    String getCheckTableExistsSql();

    // ------------------------------------------------------------------
    //  Default — ANSI baseline, inherited by most dialects
    // ------------------------------------------------------------------

    /**
     * Returns the plain INSERT for list items (one row per element).
     *
     * <p>Bind parameters in order: {@code (session_id, state_key, item_index,
     * state_data)}.
     */
    default String getInsertStateSql() {
        return "INSERT INTO %s (session_id, state_key, item_index, state_data)"
                + " VALUES (?, ?, ?, ?)";
    }

    /**
     * Returns the single-state SELECT (by {@code item_index = 0}).
     *
     * <p>Bind parameters: {@code (session_id, state_key, item_index)}.
     * Projection: {@code state_data}.
     */
    default String getSelectStateSql() {
        return "SELECT state_data FROM %s"
                + " WHERE session_id = ? AND state_key = ? AND item_index = ?";
    }

    /**
     * Returns the ordered list SELECT (all items for a key, ordered by item_index).
     *
     * <p>Bind parameters: {@code (session_id, state_key)}. Projection:
     * {@code state_data}.
     */
    default String getSelectStateListSql() {
        return "SELECT state_data FROM %s"
                + " WHERE session_id = ? AND state_key = ? ORDER BY item_index";
    }

    /**
     * Returns the DELETE for all items under a given state key.
     *
     * <p>Bind parameters: {@code (session_id, state_key)}.
     */
    default String getDeleteStateByKeySql() {
        return "DELETE FROM %s WHERE session_id = ? AND state_key = ?";
    }

    /**
     * Returns the DELETE for an entire session (all keys and items).
     *
     * <p>Bind parameters: {@code (session_id)}.
     */
    default String getDeleteSessionSql() {
        return "DELETE FROM %s WHERE session_id = ?";
    }

    /**
     * Returns the {@code MAX(item_index)} query used by the hash-based change
     * detection logic.
     *
     * <p>Bind parameters: {@code (session_id, state_key)}. Projection:
     * {@code MAX(item_index)} (returns NULL when no rows exist).
     */
    default String getSelectMaxIndexSql() {
        return "SELECT MAX(item_index) FROM %s WHERE session_id = ? AND state_key = ?";
    }

    /**
     * Returns the session-existence probe.
     *
     * <p>Bind parameters: {@code (session_id)}.
     */
    default String getExistsSql() {
        return "SELECT 1 FROM %s WHERE session_id = ? LIMIT 1";
    }

    /**
     * Returns the query that lists distinct session IDs matching a user prefix.
     *
     * <p>Bind parameters: {@code (prefix_pattern)} where the pattern already
     * includes the trailing {@code %}. Projection: {@code session_id}.
     */
    default String getListSessionIdsSql() {
        return "SELECT DISTINCT session_id FROM %s WHERE session_id LIKE ? ORDER BY session_id";
    }

    /**
     * Quotes a SQL identifier using ANSI double quotes.
     *
     * <p>Databases that require different quoting (MySQL backticks) should override
     * this method.
     *
     * @param identifier the raw identifier (database name, table name, etc.)
     * @return the quoted identifier
     */
    default String quoteIdentifier(String identifier) {
        return "\"" + identifier + "\"";
    }

    /**
     * Returns the DDL to create the database (schema), or {@code null} to skip.
     *
     * <p>The ANSI default returns {@code null} — PostgreSQL, H2, and SQLite use the
     * connection's current database and do not need an explicit CREATE DATABASE.
     * MySQL overrides this to emit
     * {@code CREATE DATABASE IF NOT EXISTS `name` DEFAULT CHARACTER SET utf8mb4}.
     *
     * @param databaseName the logical database name
     * @return CREATE DATABASE DDL, or {@code null} to skip database creation
     */
    default String getCreateDatabaseSql(String databaseName) {
        return null;
    }

    /**
     * Returns the fully-qualified table reference for use in SQL statements.
     *
     * <p>The ANSI default returns only the quoted table name — PostgreSQL, H2, and
     * SQLite resolve the table within the connection's current database/schema.
     * MySQL overrides this to prepend the quoted database name
     * ({@code `database`.`table`}).
     *
     * @param databaseName the logical database name (may be ignored by ANSI DBs)
     * @param tableName the table name
     * @return the resolved table reference for SQL interpolation
     */
    default String getFullTableReference(String databaseName, String tableName) {
        return quoteIdentifier(tableName);
    }
}

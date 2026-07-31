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
 * Database-specific SQL for the KV store table operated on by
 * {@code io.agentscope.extensions.jdbc.store.JdbcStore}.
 *
 * <p>This interface declares the two SQL statements that genuinely vary between
 * vendors as <strong>abstract</strong> (create-table DDL and UPSERT syntax) and
 * provides ANSI-standard default implementations for the remaining statements
 * (INSERT, CAS update, SELECT, DELETE, search).
 *
 * <p>All SQL templates use the {@code %s} placeholder for the table name so the
 * component can substitute the configured table name once at construction time.
 *
 * <h2>Abstract methods (variation points)</h2>
 * <ul>
 *   <li>{@link #getCreateTableSql()} — schema bootstrap; column types differ per
 *       database (LONGTEXT / TEXT / CLOB) and MySQL needs {@code ENGINE=InnoDB}.
 *   <li>{@link #getUpsertSql()} — UPSERT syntax differs per database
 *       ({@code ON DUPLICATE KEY}, {@code ON CONFLICT}, {@code MERGE INTO}).
 * </ul>
 *
 * <h2>Default methods (ANSI baseline)</h2>
 * <ul>
 *   <li>{@link #getInsertSql()} — plain ANSI INSERT; identical in every database.
 *   <li>{@link #getCasUpdateSql()} — conditional UPDATE on {@code version}; ANSI.
 *   <li>{@link #getSelectSql()} — single-item fetch; ANSI.
 *   <li>{@link #getDeleteSql()} — single-item delete; ANSI.
 *   <li>{@link #getSearchSql()} — LIKE-prefix search with pagination; ANSI.
 *   <li>{@link #getLikeEscapeChar()} — escape character for LIKE patterns.
 * </ul>
 *
 * @author shanhongyu
 */
public interface JdbcStoreDialect {

    // ------------------------------------------------------------------
    //  Abstract — must override per database
    // ------------------------------------------------------------------

    /**
     * Returns the {@code CREATE TABLE IF NOT EXISTS} DDL for the KV store table.
     *
     * <p>Required columns: {@code namespace_path}, {@code item_key},
     * {@code value_json} (TEXT-like), {@code version BIGINT},
     * {@code updated_at BIGINT}, primary key on {@code (namespace_path, item_key)}.
     *
     * @return CREATE TABLE statement; must use {@code %s} for the table name
     */
    String getCreateTableSql();

    /**
     * Returns the UPSERT statement that inserts a new row with {@code version=1}
     * or updates an existing row with {@code version = version + 1}.
     *
     * <p>Bind parameters in order: {@code (namespace_path, item_key, value_json,
     * updated_at)}. The dialect is responsible for setting the version expression.
     *
     * @return UPSERT statement; must use {@code %s} for the table name
     */
    String getUpsertSql();

    // ------------------------------------------------------------------
    //  Default — ANSI baseline, inherited by all dialects
    // ------------------------------------------------------------------

    /**
     * Returns the plain INSERT for create-only writes (version starts at 1).
     *
     * <p>Bind parameters in order: {@code (namespace_path, item_key, value_json,
     * updated_at)}. A primary-key violation indicates the row already exists.
     *
     * @return INSERT statement; must use {@code %s} for the table name
     */
    default String getInsertSql() {
        return "INSERT INTO %s (namespace_path, item_key, value_json, version, updated_at)"
                + " VALUES (?, ?, ?, 1, ?)";
    }

    /**
     * Returns the conditional UPDATE used by the CAS path.
     *
     * <p>Bind parameters in order:
     * {@code (value_json, updated_at, namespace_path, item_key, expectedVersion)}.
     * The statement must set {@code version = version + 1} and filter on
     * {@code WHERE ... AND version = ?}. An affected-row count of 1 means success.
     *
     * @return UPDATE statement; must use {@code %s} for the table name
     */
    default String getCasUpdateSql() {
        return "UPDATE %s SET value_json = ?, version = version + 1, updated_at = ?"
                + " WHERE namespace_path = ? AND item_key = ? AND version = ?";
    }

    /**
     * Returns the single-item SELECT.
     *
     * <p>Bind parameters: {@code (namespace_path, item_key)}. Projection must be
     * {@code (value_json, version)} in that column order.
     */
    default String getSelectSql() {
        return "SELECT value_json, version FROM %s WHERE namespace_path = ? AND item_key = ?";
    }

    /**
     * Returns the single-item DELETE.
     *
     * <p>Bind parameters: {@code (namespace_path, item_key)}.
     */
    default String getDeleteSql() {
        return "DELETE FROM %s WHERE namespace_path = ? AND item_key = ?";
    }

    /**
     * Returns the namespace-prefix search query with pagination.
     *
     * <p>Bind parameters: {@code (namespace_like_pattern, limit, offset)}. Projection
     * must be {@code (item_key, value_json, version)} in that column order.
     *
     * <p>The default uses {@code ESCAPE '!'} (universal across MySQL, PostgreSQL,
     * SQLite, H2) and {@code ORDER BY item_key} for deterministic paging.
     */
    default String getSearchSql() {
        return "SELECT item_key, value_json, version FROM %s"
                + " WHERE namespace_path LIKE ? ESCAPE '!'"
                + " ORDER BY item_key LIMIT ? OFFSET ?";
    }

    /**
     * The escape character paired with {@link #getSearchSql}'s {@code ESCAPE} clause.
     * Callers use this when building the LIKE pattern to escape literal
     * {@code %}, {@code _}, and the escape character itself.
     */
    default char getLikeEscapeChar() {
        return '!';
    }
}

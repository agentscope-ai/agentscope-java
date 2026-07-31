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
 * Database-specific SQL for the snapshots table operated on by
 * {@code io.agentscope.extensions.jdbc.snapshot.JdbcRemoteSnapshotClient}.
 *
 * <p>The snapshots table stores binary workspace tar archives keyed by
 * {@code snapshot_id}.
 *
 * <h2>Abstract methods (variation points)</h2>
 * <ul>
 *   <li>{@link #getUpsertSnapshotSql()} — UPSERT syntax differs per database
 *       ({@code ON DUPLICATE KEY}, {@code ON CONFLICT}, {@code MERGE INTO}).
 * </ul>
 *
 * <h2>Default methods (ANSI baseline)</h2>
 * <ul>
 *   <li>{@link #getBlobType()} — returns {@code BLOB}; Oracle/SQLite may override.
 *   <li>{@link #getCreateSnapshotTableSql()} — DDL for the snapshots table, using
 *       {@link #getBlobType()} for the data column.
 *   <li>{@link #getSelectSnapshotSql()} — single-snapshot download query.
 *   <li>{@link #getExistsSnapshotSql()} — snapshot existence probe.
 * </ul>
 *
 * <p>All SQL templates use {@code %s} for the table name.
 *
 * @author shanhongyu
 */
public interface SnapshotStoreDialect {

    // ------------------------------------------------------------------
    //  Abstract — must override per database
    // ------------------------------------------------------------------

    /**
     * Returns the idempotent UPSERT for uploading a snapshot.
     *
     * <p>Bind parameters: {@code (snapshot_id, data_bytes)}. On conflict the
     * statement must overwrite the {@code data} column and refresh
     * {@code created_at}.
     *
     * @return UPSERT statement; must use {@code %s} for the table name
     */
    String getUpsertSnapshotSql();

    // ------------------------------------------------------------------
    //  Default — ANSI baseline, inherited by most dialects
    // ------------------------------------------------------------------

    /**
     * Returns the BLOB column type used in the DDL.
     *
     * <p>The ANSI default is {@code BLOB}. MySQL overrides with {@code LONGBLOB};
     * PostgreSQL uses {@code BYTEA}; Oracle uses {@code BLOB} natively.
     */
    default String getBlobType() {
        return "BLOB";
    }

    /**
     * Returns the {@code CREATE TABLE IF NOT EXISTS} DDL for the snapshots table.
     *
     * <p>The {@code data} column type is sourced from {@link #getBlobType()} so that
     * each database's preferred binary type is used. The statement uses
     * {@code %s} for the table name.
     */
    default String getCreateSnapshotTableSql() {
        return "CREATE TABLE IF NOT EXISTS %s ("
                + "snapshot_id VARCHAR(512) NOT NULL PRIMARY KEY, "
                + "data "
                + getBlobType()
                + " NOT NULL, "
                + "created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP"
                + ")";
    }

    /**
     * Returns the single-snapshot SELECT.
     *
     * <p>Bind parameters: {@code (snapshot_id)}. Projection: {@code data}.
     */
    default String getSelectSnapshotSql() {
        return "SELECT data FROM %s WHERE snapshot_id = ?";
    }

    /**
     * Returns the snapshot existence probe.
     *
     * <p>Bind parameters: {@code (snapshot_id)}.
     */
    default String getExistsSnapshotSql() {
        return "SELECT 1 FROM %s WHERE snapshot_id = ? LIMIT 1";
    }
}

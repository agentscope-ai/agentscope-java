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
package io.agentscope.extensions.jdbc.dialect.table;

import io.agentscope.extensions.jdbc.dialect.BoundSql;

/**
 * Table-domain dialect interface for the snapshots table.
 *
 * <p>Stores binary workspace tar archives keyed by {@code snapshot_id}.
 *
 * <p>Method names are prefixed with {@code snapshot} to avoid collision when
 * the aggregate class implements multiple table-domain interfaces.
 *
 * @author shanhongyu
 */
public interface SnapshotDialect {

    /** Base table name (without prefix). */
    default String snapshotTableName() {
        return "snapshots";
    }

    // ------------------------------------------------------------------
    //  Abstract — must override per database
    // ------------------------------------------------------------------

    /** CREATE TABLE DDL for the snapshots table. Must be idempotent. */
    String snapshotCreateTableSql();

    /** Idempotent UPSERT for uploading a snapshot. */
    BoundSql snapshotUpsert(String snapshotId, byte[] data);

    // ------------------------------------------------------------------
    //  Default — ANSI baseline
    // ------------------------------------------------------------------

    /** Single-snapshot SELECT. Projection: data. */
    default BoundSql snapshotSelect(String snapshotId) {
        return new BoundSql(
                "SELECT data FROM " + snapshotTableName() + " WHERE snapshot_id = ?", snapshotId);
    }

    /** Snapshot existence probe. */
    default BoundSql snapshotExists(String snapshotId) {
        return new BoundSql(
                "SELECT 1 FROM " + snapshotTableName() + " WHERE snapshot_id = ? LIMIT 1",
                snapshotId);
    }
}

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
 * Table-domain dialect interface for the KV store table.
 *
 * <p>Defines the create-table DDL (abstract, varies per database) and ANSI-standard
 * business SQL (default methods returning {@link BoundSql}). The aggregate class
 * {@code AbstractJdbcDialect} implements this interface along with the other table-domain
 * interfaces; vendor classes extend the aggregate and override only what differs.
 *
 * <p>Method names are prefixed with {@code store} to avoid collision when the
 * aggregate class implements multiple table-domain interfaces.
 *
 * @author shanhongyu
 */
public interface StoreDialect {

    /** Base table name (without prefix). */
    default String storeTableName() {
        return "store";
    }

    // ------------------------------------------------------------------
    //  Abstract — must override per database
    // ------------------------------------------------------------------

    /** CREATE TABLE DDL for the KV store table. Must be idempotent. */
    String storeCreateTableSql();

    /** UPSERT: insert new row with version=1 or update existing row with version+1. */
    BoundSql storeUpsert(String namespacePath, String key, String json, long timestamp);

    // ------------------------------------------------------------------
    //  Default — ANSI baseline
    // ------------------------------------------------------------------

    /** INSERT for create-only writes (version starts at 1). PK violation = row exists. */
    default BoundSql storeInsert(String namespacePath, String key, String json, long timestamp) {
        return new BoundSql(
                "INSERT INTO "
                        + storeTableName()
                        + " (namespace_path, item_key, value_json, version, updated_at)"
                        + " VALUES (?, ?, ?, 1, ?)",
                namespacePath,
                key,
                json,
                timestamp);
    }

    /** Conditional UPDATE for CAS path. Returns affected-row count (1=success). */
    default BoundSql storeCasUpdate(
            String json, long timestamp, String namespacePath, String key, long expectedVersion) {
        return new BoundSql(
                "UPDATE "
                        + storeTableName()
                        + " SET value_json = ?, version = version + 1, updated_at = ?"
                        + " WHERE namespace_path = ? AND item_key = ? AND version = ?",
                json,
                timestamp,
                namespacePath,
                key,
                expectedVersion);
    }

    /** Single-item SELECT. Projection: (value_json, version). */
    default BoundSql storeSelect(String namespacePath, String key) {
        return new BoundSql(
                "SELECT value_json, version FROM "
                        + storeTableName()
                        + " WHERE namespace_path = ? AND item_key = ?",
                namespacePath,
                key);
    }

    /** Single-item DELETE. */
    default BoundSql storeDelete(String namespacePath, String key) {
        return new BoundSql(
                "DELETE FROM " + storeTableName() + " WHERE namespace_path = ? AND item_key = ?",
                namespacePath,
                key);
    }

    /** Namespace-prefix search with pagination. Projection: (item_key, value_json, version). */
    default BoundSql storeSearch(String likePattern, int limit, int offset) {
        return new BoundSql(
                "SELECT item_key, value_json, version FROM "
                        + storeTableName()
                        + " WHERE namespace_path LIKE ? ESCAPE '"
                        + storeLikeEscapeChar()
                        + "'"
                        + " ORDER BY item_key LIMIT ? OFFSET ?",
                likePattern,
                limit,
                offset);
    }

    /** Escape character for LIKE patterns in {@link #storeSearch}. */
    default char storeLikeEscapeChar() {
        return '!';
    }
}

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
 * Table-domain dialect interface for the session-state table.
 *
 * <p>Stores agent state keyed by {@code (session_id, state_key, item_index)}.
 * Single states use {@code item_index = 0}; list states use one row per element.
 *
 * <p>Method names are prefixed with {@code sessionState} to avoid collision when
 * the aggregate class implements multiple table-domain interfaces.
 *
 * @author shanhongyu
 */
public interface SessionStateDialect {

    /** Base table name (without prefix). */
    default String sessionStateTableName() {
        return "sessions";
    }

    // ------------------------------------------------------------------
    //  Abstract — must override per database
    // ------------------------------------------------------------------

    /** CREATE TABLE DDL for the sessions table. Must be idempotent. */
    String sessionStateCreateTableSql();

    /** UPSERT a single state value. On conflict, updates state_data. */
    BoundSql sessionStateUpsert(String sessionId, String stateKey, int itemIndex, String stateData);

    /** Table-existence probe SQL. One bind param: the table name. */
    BoundSql sessionStateCheckTableExists(String tableName);

    // ------------------------------------------------------------------
    //  Default — ANSI baseline
    // ------------------------------------------------------------------

    /** INSERT for list items (one row per element). */
    default BoundSql sessionStateInsert(
            String sessionId, String stateKey, int itemIndex, String stateData) {
        return new BoundSql(
                "INSERT INTO "
                        + sessionStateTableName()
                        + " (session_id, state_key, item_index, state_data) VALUES (?, ?, ?, ?)",
                sessionId,
                stateKey,
                itemIndex,
                stateData);
    }

    /** Single-state SELECT by item_index. Projection: state_data. */
    default BoundSql sessionStateSelect(String sessionId, String stateKey, int itemIndex) {
        return new BoundSql(
                "SELECT state_data FROM "
                        + sessionStateTableName()
                        + " WHERE session_id = ? AND state_key = ? AND item_index = ?",
                sessionId,
                stateKey,
                itemIndex);
    }

    /** Ordered list SELECT. Projection: state_data. */
    default BoundSql sessionStateSelectList(String sessionId, String stateKey) {
        return new BoundSql(
                "SELECT state_data FROM "
                        + sessionStateTableName()
                        + " WHERE session_id = ? AND state_key = ? ORDER BY item_index",
                sessionId,
                stateKey);
    }

    /** DELETE all items for a state key. */
    default BoundSql sessionStateDeleteByKey(String sessionId, String stateKey) {
        return new BoundSql(
                "DELETE FROM "
                        + sessionStateTableName()
                        + " WHERE session_id = ? AND state_key = ?",
                sessionId,
                stateKey);
    }

    /** DELETE an entire session. */
    default BoundSql sessionStateDeleteSession(String sessionId) {
        return new BoundSql(
                "DELETE FROM " + sessionStateTableName() + " WHERE session_id = ?", sessionId);
    }

    /** MAX(item_index) for change detection. Returns NULL when no rows exist. */
    default BoundSql sessionStateSelectMaxIndex(String sessionId, String stateKey) {
        return new BoundSql(
                "SELECT MAX(item_index) FROM "
                        + sessionStateTableName()
                        + " WHERE session_id = ? AND state_key = ?",
                sessionId,
                stateKey);
    }

    /** Session-existence probe. */
    default BoundSql sessionStateExists(String sessionId) {
        return new BoundSql(
                "SELECT 1 FROM " + sessionStateTableName() + " WHERE session_id = ? LIMIT 1",
                sessionId);
    }

    /** List distinct session IDs matching a prefix pattern. */
    default BoundSql sessionStateListSessionIds(String prefixPattern) {
        return new BoundSql(
                "SELECT DISTINCT session_id FROM "
                        + sessionStateTableName()
                        + " WHERE session_id LIKE ? ESCAPE '"
                        + sessionStateLikeEscapeChar()
                        + "' ORDER BY session_id",
                prefixPattern);
    }

    /** Escape character for LIKE patterns in {@link #sessionStateListSessionIds}. */
    default char sessionStateLikeEscapeChar() {
        return '!';
    }
}

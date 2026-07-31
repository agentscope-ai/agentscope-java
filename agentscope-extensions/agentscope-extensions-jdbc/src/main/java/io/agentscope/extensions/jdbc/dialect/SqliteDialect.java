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
 * SQLite dialect for {@link JdbcDialect}.
 *
 * <p>Overrides the methods that diverge from the ANSI baseline:
 * <ul>
 *   <li>{@link #getCreateTableSql()} — SQLite-native {@code TEXT}/{@code INTEGER} types
 *   <li>{@link #getCreateSessionsTableSql()} — same type mapping
 *   <li>{@link #getCheckTableExistsSql()} — SQLite uses {@code sqlite_master}, not
 *       {@code information_schema}
 * </ul>
 *
 * <p>All other methods (UPSERT via {@code ON CONFLICT}, INSERT, CAS, SELECT, DELETE,
 * search, quote identifier, BLOB type) are inherited from the ANSI defaults or
 * implemented identically to the ANSI baseline. SQLite 3.24+ is required for
 * {@code ON CONFLICT DO UPDATE} support.
 *
 * @author shanhongyu
 */
public class SqliteDialect implements JdbcDialect {

    // ------------------------------------------------------------------
    //  JdbcStoreDialect
    // ------------------------------------------------------------------

    @Override
    public String getCreateTableSql() {
        return "CREATE TABLE IF NOT EXISTS %s ("
                + "  namespace_path TEXT    NOT NULL,"
                + "  item_key       TEXT    NOT NULL,"
                + "  value_json     TEXT    NOT NULL,"
                + "  version        INTEGER NOT NULL,"
                + "  updated_at     INTEGER NOT NULL,"
                + "  PRIMARY KEY (namespace_path, item_key)"
                + ")";
    }

    @Override
    public String getUpsertSql() {
        return "INSERT INTO %s (namespace_path, item_key, value_json, version, updated_at)"
                + " VALUES (?, ?, ?, 1, ?)"
                + " ON CONFLICT(namespace_path, item_key) DO UPDATE SET"
                + "   value_json = excluded.value_json,"
                + "   version    = version + 1,"
                + "   updated_at = excluded.updated_at";
    }

    // ------------------------------------------------------------------
    //  AgentStateStoreDialect
    // ------------------------------------------------------------------

    @Override
    public String getCreateSessionsTableSql() {
        return "CREATE TABLE IF NOT EXISTS %s ("
                + "  session_id  TEXT    NOT NULL,"
                + "  state_key   TEXT    NOT NULL,"
                + "  item_index  INTEGER NOT NULL DEFAULT 0,"
                + "  state_data  TEXT    NOT NULL,"
                + "  PRIMARY KEY (session_id, state_key, item_index)"
                + ")";
    }

    @Override
    public String getUpsertStateSql() {
        return "INSERT INTO %s (session_id, state_key, item_index, state_data)"
                + " VALUES (?, ?, ?, ?)"
                + " ON CONFLICT(session_id, state_key, item_index) DO UPDATE SET"
                + "   state_data = excluded.state_data";
    }

    @Override
    public String getCheckTableExistsSql() {
        // One bind parameter: the table name. SQLite uses sqlite_master.
        return "SELECT 1 FROM sqlite_master WHERE type = 'table' AND name = ?";
    }

    // ------------------------------------------------------------------
    //  SnapshotStoreDialect
    // ------------------------------------------------------------------

    @Override
    public String getUpsertSnapshotSql() {
        return "INSERT INTO %s (snapshot_id, data) VALUES (?, ?)"
                + " ON CONFLICT(snapshot_id) DO UPDATE SET"
                + "   data = excluded.data, created_at = CURRENT_TIMESTAMP";
    }
}

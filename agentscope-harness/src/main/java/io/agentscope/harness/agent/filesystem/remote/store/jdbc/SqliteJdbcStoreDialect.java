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
package io.agentscope.harness.agent.filesystem.remote.store.jdbc;

/**
 * SQLite dialect for {@link JdbcStore}. Requires SQLite 3.24 or newer for {@code ON CONFLICT
 * ... DO UPDATE} (UPSERT) support.
 */
public class SqliteJdbcStoreDialect implements JdbcStoreDialect {

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

    @Override
    public String getInsertSql() {
        return "INSERT INTO %s (namespace_path, item_key, value_json, version, updated_at)"
                + " VALUES (?, ?, ?, 1, ?)";
    }

    @Override
    public String getCasUpdateSql() {
        return "UPDATE %s SET value_json = ?, version = version + 1, updated_at = ?"
                + " WHERE namespace_path = ? AND item_key = ? AND version = ?";
    }
}

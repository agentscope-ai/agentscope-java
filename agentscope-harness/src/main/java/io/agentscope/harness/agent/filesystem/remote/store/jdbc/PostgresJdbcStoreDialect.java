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

/** PostgreSQL dialect for {@link JdbcStore}. */
public class PostgresJdbcStoreDialect implements JdbcStoreDialect {

    @Override
    public String getCreateTableSql() {
        return "CREATE TABLE IF NOT EXISTS %s ("
                + "  namespace_path VARCHAR(2048) NOT NULL,"
                + "  item_key       VARCHAR(255)  NOT NULL,"
                + "  value_json     TEXT          NOT NULL,"
                + "  version        BIGINT        NOT NULL,"
                + "  updated_at     BIGINT        NOT NULL,"
                + "  PRIMARY KEY (namespace_path, item_key)"
                + ")";
    }

    @Override
    public String getUpsertSql() {
        return "INSERT INTO %s (namespace_path, item_key, value_json, version, updated_at)"
                + " VALUES (?, ?, ?, 1, ?)"
                + " ON CONFLICT (namespace_path, item_key) DO UPDATE SET"
                + "   value_json = EXCLUDED.value_json,"
                + "   version    = %1$s.version + 1,"
                + "   updated_at = EXCLUDED.updated_at";
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

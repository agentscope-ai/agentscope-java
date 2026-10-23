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
package io.agentscope.extensions.mysql.store;

/**
 * H2 dialect for {@link JdbcStore}.
 *
 * <p>Uses the PostgreSQL-compatible {@code ON CONFLICT ... DO UPDATE} syntax, supported by H2
 * 2.x. Earlier H2 versions require the legacy {@code MERGE INTO} syntax and are not supported.
 */
public class H2JdbcStoreDialect implements JdbcStoreDialect {

    @Override
    public String getCreateTableSql() {
        return "CREATE TABLE IF NOT EXISTS %s ("
                + "  namespace_path VARCHAR(2048) NOT NULL,"
                + "  item_key       VARCHAR(255)  NOT NULL,"
                + "  value_json     CLOB          NOT NULL,"
                + "  version        BIGINT        NOT NULL,"
                + "  updated_at     BIGINT        NOT NULL,"
                + "  PRIMARY KEY (namespace_path, item_key)"
                + ")";
    }

    @Override
    public String getUpsertSql() {
        return "MERGE INTO %s AS t USING (VALUES (?, ?, ?, ?)) AS s(np, ik, vj, ts)"
                + " ON t.namespace_path = s.np AND t.item_key = s.ik"
                + " WHEN MATCHED THEN UPDATE SET"
                + "   value_json = s.vj,"
                + "   version    = t.version + 1,"
                + "   updated_at = s.ts"
                + " WHEN NOT MATCHED THEN INSERT (namespace_path, item_key, value_json, version,"
                + " updated_at) VALUES (s.np, s.ik, s.vj, 1, s.ts)";
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

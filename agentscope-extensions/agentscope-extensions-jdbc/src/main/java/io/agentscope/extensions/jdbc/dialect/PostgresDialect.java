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
 * PostgreSQL dialect — the ANSI baseline for {@link JdbcDialect}.
 *
 * <p>Every method implementation in this class uses standard PostgreSQL / ANSI SQL.
 * This class serves as the reference baseline: other database dialects override
 * only the methods where their syntax diverges from the patterns established here.
 *
 * <p>PostgreSQL has native advisory locks ({@code pg_advisory_lock}), but for
 * simplicity the default {@link JdbcDialect#lockStrategy} ({@link
 * TableBasedLockStrategy}) is used here. Override
 * {@link #lockStrategy(javax.sql.DataSource)} if native advisory lock performance
 * is required.
 *
 * @author shanhongyu
 */
public class PostgresDialect implements JdbcDialect {

    // ------------------------------------------------------------------
    //  JdbcStoreDialect
    // ------------------------------------------------------------------

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

    // ------------------------------------------------------------------
    //  AgentStateStoreDialect
    // ------------------------------------------------------------------

    @Override
    public String getCreateSessionsTableSql() {
        return "CREATE TABLE IF NOT EXISTS %s ("
                + "  session_id  VARCHAR(255) NOT NULL,"
                + "  state_key   VARCHAR(255) NOT NULL,"
                + "  item_index  INT          NOT NULL DEFAULT 0,"
                + "  state_data  TEXT         NOT NULL,"
                + "  created_at  TIMESTAMP    DEFAULT CURRENT_TIMESTAMP,"
                + "  updated_at  TIMESTAMP    DEFAULT CURRENT_TIMESTAMP,"
                + "  PRIMARY KEY (session_id, state_key, item_index)"
                + ")";
    }

    @Override
    public String getUpsertStateSql() {
        return "INSERT INTO %s (session_id, state_key, item_index, state_data)"
                + " VALUES (?, ?, ?, ?)"
                + " ON CONFLICT (session_id, state_key, item_index) DO UPDATE SET"
                + "   state_data = EXCLUDED.state_data";
    }

    @Override
    public String getCheckTableExistsSql() {
        // One bind parameter: the table name. Scoped to current_schema().
        return "SELECT 1 FROM information_schema.tables"
                + " WHERE table_schema = current_schema() AND table_name = ?";
    }

    // ------------------------------------------------------------------
    //  SnapshotStoreDialect
    // ------------------------------------------------------------------

    @Override
    public String getUpsertSnapshotSql() {
        // PostgreSQL uses BYTEA for binary data; override getBlobType accordingly.
        return "INSERT INTO %s (snapshot_id, data) VALUES (?, ?)"
                + " ON CONFLICT (snapshot_id) DO UPDATE SET"
                + "   data = EXCLUDED.data, created_at = CURRENT_TIMESTAMP";
    }

    @Override
    public String getBlobType() {
        return "BYTEA";
    }
}

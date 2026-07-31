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
 * H2 dialect for {@link JdbcDialect}.
 *
 * <p>Uses {@code CLOB} for large text columns and {@code MERGE INTO} for UPSERT
 * operations. H2 2.x does not support PostgreSQL's {@code ON CONFLICT} syntax in
 * all modes, so the ANSI SQL {@code MERGE INTO} statement is used instead — this
 * is H2's native, well-tested UPSERT mechanism.
 *
 * <p>All other SQL (INSERT, CAS UPDATE, SELECT, DELETE, search, table-existence
 * check, identifier quoting) follows the ANSI baseline.
 *
 * @author shanhongyu
 */
public class H2Dialect implements JdbcDialect {

    // ------------------------------------------------------------------
    //  JdbcStoreDialect
    // ------------------------------------------------------------------

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
                + " WHEN NOT MATCHED THEN INSERT"
                + "   (namespace_path, item_key, value_json, version, updated_at)"
                + "   VALUES (s.np, s.ik, s.vj, 1, s.ts)";
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
                + "  state_data  CLOB         NOT NULL,"
                + "  created_at  TIMESTAMP    DEFAULT CURRENT_TIMESTAMP,"
                + "  updated_at  TIMESTAMP    DEFAULT CURRENT_TIMESTAMP,"
                + "  PRIMARY KEY (session_id, state_key, item_index)"
                + ")";
    }

    @Override
    public String getUpsertStateSql() {
        return "MERGE INTO %s AS t USING (VALUES (?, ?, ?, ?)) AS s(sid, sk, ii, sd)"
                + " ON t.session_id = s.sid AND t.state_key = s.sk AND t.item_index = s.ii"
                + " WHEN MATCHED THEN UPDATE SET state_data = s.sd"
                + " WHEN NOT MATCHED THEN INSERT"
                + "   (session_id, state_key, item_index, state_data)"
                + "   VALUES (s.sid, s.sk, s.ii, s.sd)";
    }

    @Override
    public String getCheckTableExistsSql() {
        return "SELECT 1 FROM INFORMATION_SCHEMA.TABLES WHERE TABLE_NAME = ?";
    }

    // ------------------------------------------------------------------
    //  SnapshotStoreDialect
    // ------------------------------------------------------------------

    @Override
    public String getUpsertSnapshotSql() {
        // H2's simplified MERGE: replaces the entire row on key conflict.
        // This correctly handles binary parameters, unlike the VALUES subquery form.
        return "MERGE INTO %s (snapshot_id, data) KEY (snapshot_id) VALUES (?, ?)";
    }
}

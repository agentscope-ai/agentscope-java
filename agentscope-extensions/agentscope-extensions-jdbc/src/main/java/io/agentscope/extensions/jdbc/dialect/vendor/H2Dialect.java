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
package io.agentscope.extensions.jdbc.dialect.vendor;

import io.agentscope.extensions.jdbc.dialect.AbstractJdbcDialect;
import io.agentscope.extensions.jdbc.dialect.BoundSql;
import java.sql.DatabaseMetaData;
import java.sql.SQLException;
import java.util.Locale;

/**
 * H2 dialect.
 *
 * <p>Uses {@code CLOB} for large text columns and {@code MERGE INTO} for UPSERT.
 * All other business SQL inherits ANSI defaults.
 *
 * @author shanhongyu
 */
public class H2Dialect extends AbstractJdbcDialect {

    // ------------------------------------------------------------------
    //  StoreDialect
    // ------------------------------------------------------------------

    @Override
    public String storeCreateTableSql() {
        return "CREATE TABLE IF NOT EXISTS "
                + storeTableName()
                + " ("
                + "  namespace_path VARCHAR(2048) NOT NULL,"
                + "  item_key       VARCHAR(255)  NOT NULL,"
                + "  value_json     CLOB          NOT NULL,"
                + "  version        BIGINT        NOT NULL,"
                + "  updated_at     BIGINT        NOT NULL,"
                + "  PRIMARY KEY (namespace_path, item_key)"
                + ")";
    }

    @Override
    public BoundSql storeUpsert(String namespacePath, String key, String json, long timestamp) {
        return new BoundSql(
                "MERGE INTO "
                        + storeTableName()
                        + " AS t USING (VALUES (?, ?, ?, ?)) AS s(np, ik, vj, ts)"
                        + " ON t.namespace_path = s.np AND t.item_key = s.ik"
                        + " WHEN MATCHED THEN UPDATE SET"
                        + "   value_json = s.vj, version = t.version + 1, updated_at = s.ts"
                        + " WHEN NOT MATCHED THEN INSERT"
                        + "   (namespace_path, item_key, value_json, version, updated_at)"
                        + "   VALUES (s.np, s.ik, s.vj, 1, s.ts)",
                namespacePath,
                key,
                json,
                timestamp);
    }

    // ------------------------------------------------------------------
    //  SessionStateDialect
    // ------------------------------------------------------------------

    @Override
    public String sessionStateCreateTableSql() {
        return "CREATE TABLE IF NOT EXISTS "
                + sessionStateTableName()
                + " ("
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
    public BoundSql sessionStateUpsert(
            String sessionId, String stateKey, int itemIndex, String stateData) {
        return new BoundSql(
                "MERGE INTO "
                        + sessionStateTableName()
                        + " AS t USING (VALUES (?, ?, ?, ?)) AS s(sid, sk, ii, sd)"
                        + " ON t.session_id = s.sid AND t.state_key = s.sk AND t.item_index = s.ii"
                        + " WHEN MATCHED THEN UPDATE SET state_data = s.sd"
                        + " WHEN NOT MATCHED THEN INSERT"
                        + "   (session_id, state_key, item_index, state_data)"
                        + "   VALUES (s.sid, s.sk, s.ii, s.sd)",
                sessionId,
                stateKey,
                itemIndex,
                stateData);
    }

    @Override
    public BoundSql sessionStateCheckTableExists(String tableName) {
        return new BoundSql(
                "SELECT 1 FROM INFORMATION_SCHEMA.TABLES WHERE TABLE_NAME = ?", tableName);
    }

    // ------------------------------------------------------------------
    //  SnapshotDialect
    // ------------------------------------------------------------------

    @Override
    public String snapshotCreateTableSql() {
        return "CREATE TABLE IF NOT EXISTS "
                + snapshotTableName()
                + " ("
                + "  snapshot_id VARCHAR(512) NOT NULL PRIMARY KEY, "
                + "  data BLOB NOT NULL, "
                + "  created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP"
                + ")";
    }

    @Override
    public BoundSql snapshotUpsert(String snapshotId, byte[] data) {
        return new BoundSql(
                "MERGE INTO "
                        + snapshotTableName()
                        + " AS t USING (VALUES (?, CAST(? AS BLOB))) AS s(sid, dat)"
                        + " ON t.snapshot_id = s.sid"
                        + " WHEN MATCHED THEN UPDATE SET"
                        + "   data = s.dat, created_at = CURRENT_TIMESTAMP"
                        + " WHEN NOT MATCHED THEN INSERT"
                        + "   (snapshot_id, data, created_at)"
                        + "   VALUES (s.sid, s.dat, CURRENT_TIMESTAMP)",
                snapshotId,
                data);
    }

    // ------------------------------------------------------------------
    //  Detection
    // ------------------------------------------------------------------

    @Override
    public boolean supports(DatabaseMetaData metaData) throws SQLException {
        return metaData.getDatabaseProductName().toLowerCase(Locale.ROOT).contains("h2");
    }
}

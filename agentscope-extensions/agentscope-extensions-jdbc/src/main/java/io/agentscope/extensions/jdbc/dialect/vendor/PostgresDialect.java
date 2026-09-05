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
import java.io.InputStream;
import java.sql.DatabaseMetaData;
import java.sql.SQLException;
import java.util.List;
import java.util.Locale;

/**
 * PostgreSQL dialect — the ANSI baseline.
 *
 * <p>Business SQL inherits ANSI defaults from the table-domain interfaces. Only
 * create-table DDL and UPSERT syntax (which use PostgreSQL-specific {@code ON CONFLICT}
 * and types like {@code TEXT}/{@code BYTEA}) are overridden.
 *
 * @author shanhongyu
 */
public class PostgresDialect extends AbstractJdbcDialect {

    // ------------------------------------------------------------------
    //  StoreDialect
    // ------------------------------------------------------------------

    @Override
    public List<String> storeCreateTableDdls() {
        return List.of(
                "CREATE TABLE IF NOT EXISTS "
                        + storeTableName()
                        + " ("
                        + "  namespace_path VARCHAR(2048) NOT NULL,"
                        + "  item_key       VARCHAR(255)  NOT NULL,"
                        + "  value_json     TEXT          NOT NULL,"
                        + "  version        BIGINT        NOT NULL,"
                        + "  updated_at     BIGINT        NOT NULL,"
                        + "  PRIMARY KEY (namespace_path, item_key)"
                        + ")",
                // PostgreSQL cannot express a secondary index inside CREATE TABLE.
                "CREATE INDEX IF NOT EXISTS "
                        + storeTableName()
                        + "_namespace_idx ON "
                        + storeTableName()
                        + " (namespace_path)");
    }

    @Override
    public BoundSql storeUpsert(String namespacePath, String key, String json, long timestamp) {
        String table = storeTableName();
        return new BoundSql(
                "INSERT INTO "
                        + table
                        + " (namespace_path, item_key, value_json, version, updated_at)"
                        + " VALUES (?, ?, ?, 1, ?)"
                        + " ON CONFLICT (namespace_path, item_key) DO UPDATE SET"
                        + "   value_json = EXCLUDED.value_json,"
                        + "   version    = "
                        + table
                        + ".version + 1,"
                        + "   updated_at = EXCLUDED.updated_at",
                namespacePath,
                key,
                json,
                timestamp);
    }

    // ------------------------------------------------------------------
    //  SessionStateDialect
    // ------------------------------------------------------------------

    @Override
    public List<String> sessionStateCreateTableDdls() {
        return List.of(
                "CREATE TABLE IF NOT EXISTS "
                        + sessionStateTableName()
                        + " ("
                        + "  session_id  VARCHAR(255) NOT NULL,"
                        + "  state_key   VARCHAR(255) NOT NULL,"
                        + "  item_index  INT          NOT NULL DEFAULT 0,"
                        + "  state_data  TEXT         NOT NULL,"
                        + "  created_at  TIMESTAMP    DEFAULT CURRENT_TIMESTAMP,"
                        + "  updated_at  TIMESTAMP    DEFAULT CURRENT_TIMESTAMP,"
                        + "  PRIMARY KEY (session_id, state_key, item_index)"
                        + ")",
                // PostgreSQL cannot express a secondary index inside CREATE TABLE.
                "CREATE INDEX IF NOT EXISTS "
                        + sessionStateTableName()
                        + "_session_idx ON "
                        + sessionStateTableName()
                        + " (session_id)");
    }

    @Override
    public BoundSql sessionStateUpsert(
            String sessionId, String stateKey, int itemIndex, String stateData) {
        return new BoundSql(
                "INSERT INTO "
                        + sessionStateTableName()
                        + " (session_id, state_key, item_index, state_data) VALUES (?, ?, ?, ?)"
                        + " ON CONFLICT (session_id, state_key, item_index) DO UPDATE SET"
                        + "   state_data = EXCLUDED.state_data",
                sessionId,
                stateKey,
                itemIndex,
                stateData);
    }

    @Override
    public BoundSql sessionStateCheckTableExists(String tableName) {
        return new BoundSql(
                "SELECT 1 FROM information_schema.tables"
                        + " WHERE table_schema = current_schema() AND table_name = ?",
                tableName);
    }

    // ------------------------------------------------------------------
    //  SnapshotDialect
    // ------------------------------------------------------------------

    @Override
    public List<String> snapshotCreateTableDdls() {
        return List.of(
                "CREATE TABLE IF NOT EXISTS "
                        + snapshotTableName()
                        + " ("
                        + "  snapshot_id VARCHAR(512) NOT NULL PRIMARY KEY, "
                        + "  data BYTEA NOT NULL, "
                        + "  created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP"
                        + ")");
    }

    @Override
    public BoundSql snapshotUpsert(String snapshotId, InputStream data) {
        return new BoundSql(
                "INSERT INTO "
                        + snapshotTableName()
                        + " (snapshot_id, data) VALUES (?, ?)"
                        + " ON CONFLICT (snapshot_id) DO UPDATE SET"
                        + "   data = EXCLUDED.data, created_at = CURRENT_TIMESTAMP",
                snapshotId,
                data);
    }

    // ------------------------------------------------------------------
    //  Detection
    // ------------------------------------------------------------------

    @Override
    public boolean supports(DatabaseMetaData metaData) throws SQLException {
        return metaData.getDatabaseProductName().toLowerCase(Locale.ROOT).contains("postgresql");
    }
}

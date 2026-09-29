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
    public List<String> storeCreateTableDdls() {
        return List.of(
                "CREATE TABLE IF NOT EXISTS "
                        + storeTableName()
                        + " ("
                        + "  namespace_path VARCHAR(2048) NOT NULL,"
                        + "  item_key       VARCHAR(255)  NOT NULL,"
                        + "  value_json     CLOB          NOT NULL,"
                        + "  version        BIGINT        NOT NULL,"
                        + "  updated_at     BIGINT        NOT NULL,"
                        + "  PRIMARY KEY (namespace_path, item_key)"
                        + ")",
                // H2 cannot express a secondary index inside CREATE TABLE.
                "CREATE INDEX IF NOT EXISTS "
                        + storeTableName()
                        + "_namespace_idx ON "
                        + storeTableName()
                        + " (namespace_path)");
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
    public List<String> sessionStateCreateTableDdls() {
        return List.of(
                "CREATE TABLE IF NOT EXISTS "
                        + sessionStateTableName()
                        + " ("
                        + "  session_id  VARCHAR(255) NOT NULL,"
                        + "  state_key   VARCHAR(255) NOT NULL,"
                        + "  item_index  INT          NOT NULL DEFAULT 0,"
                        + "  state_data  CLOB         NOT NULL,"
                        + "  version     BIGINT       NOT NULL DEFAULT 0,"
                        + "  created_at  TIMESTAMP    DEFAULT CURRENT_TIMESTAMP,"
                        + "  updated_at  TIMESTAMP    DEFAULT CURRENT_TIMESTAMP,"
                        + "  PRIMARY KEY (session_id, state_key, item_index)"
                        + ")",
                // H2 cannot express a secondary index inside CREATE TABLE.
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
                "MERGE INTO "
                        + sessionStateTableName()
                        + " AS t USING (VALUES (?, ?, ?, ?)) AS s(sid, sk, ii, sd)"
                        + " ON t.session_id = s.sid AND t.state_key = s.sk AND t.item_index = s.ii"
                        + " WHEN MATCHED THEN UPDATE SET state_data = s.sd, version = t.version + 1"
                        + " WHEN NOT MATCHED THEN INSERT"
                        + "   (session_id, state_key, item_index, state_data, version)"
                        + "   VALUES (s.sid, s.sk, s.ii, s.sd, 1)",
                sessionId,
                stateKey,
                itemIndex,
                stateData);
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
                        + "  data BLOB NOT NULL, "
                        + "  created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP"
                        + ")");
    }

    @Override
    public BoundSql snapshotUpsert(String snapshotId, InputStream data) {
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
    //  SkillDialect / SkillResourcesDialect
    // ------------------------------------------------------------------

    /**
     * Skill tables ported from the deprecated skill-mysql-repository module, translated to
     * H2 types: {@code CLOB} for text payloads, {@code AUTO_INCREMENT} identity id, FK with
     * cascade delete. {@code metadata_json} is declared nullable so rows written before the
     * column existed still validate — but the column itself is required: schema validation
     * compares declared columns, so a legacy table lacking it needs
     * {@code ALTER TABLE ... ADD COLUMN metadata_json CLOB} first (see {@link
     * io.agentscope.extensions.jdbc.dialect.table.SkillDialect}).
     */
    @Override
    public List<String> skillCreateTableDdls() {
        return List.of(
                "CREATE TABLE IF NOT EXISTS "
                        + skillTableName()
                        + " ("
                        + "  id            BIGINT AUTO_INCREMENT PRIMARY KEY,"
                        + "  name          VARCHAR(255) NOT NULL UNIQUE,"
                        + "  description   CLOB         NOT NULL,"
                        + "  skill_content CLOB         NOT NULL,"
                        + "  source        VARCHAR(255) NOT NULL,"
                        + "  metadata_json CLOB,"
                        + "  created_at    TIMESTAMP DEFAULT CURRENT_TIMESTAMP,"
                        + "  updated_at    TIMESTAMP DEFAULT CURRENT_TIMESTAMP"
                        + "    ON UPDATE CURRENT_TIMESTAMP"
                        + ")");
    }

    @Override
    public List<String> skillResourcesCreateTableDdls() {
        return List.of(
                "CREATE TABLE IF NOT EXISTS "
                        + skillResourcesTableName()
                        + " ("
                        + "  id               BIGINT       NOT NULL,"
                        + "  resource_path    VARCHAR(500) NOT NULL,"
                        + "  resource_content CLOB         NOT NULL,"
                        + "  created_at       TIMESTAMP DEFAULT CURRENT_TIMESTAMP,"
                        + "  updated_at       TIMESTAMP DEFAULT CURRENT_TIMESTAMP"
                        + "    ON UPDATE CURRENT_TIMESTAMP,"
                        + "  PRIMARY KEY (id, resource_path),"
                        + "  FOREIGN KEY (id) REFERENCES "
                        + skillTableName()
                        + "(id) ON DELETE CASCADE"
                        + ")");
    }

    // ------------------------------------------------------------------
    //  Detection
    // ------------------------------------------------------------------

    @Override
    public boolean supports(DatabaseMetaData metaData) throws SQLException {
        return metaData.getDatabaseProductName().toLowerCase(Locale.ROOT).contains("h2");
    }
}

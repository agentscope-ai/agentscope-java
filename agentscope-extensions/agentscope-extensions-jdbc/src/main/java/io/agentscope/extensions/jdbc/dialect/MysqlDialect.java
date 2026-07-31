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

import javax.sql.DataSource;

/**
 * MySQL / MariaDB dialect for {@link JdbcDialect}.
 *
 * <p>Overrides the methods that diverge from the ANSI baseline:
 * <ul>
 *   <li>{@link #getCreateTableSql()} — {@code LONGTEXT} + {@code ENGINE=InnoDB}
 *   <li>{@link #getUpsertSql()} — {@code ON DUPLICATE KEY UPDATE}
 *   <li>{@link #getCreateSessionsTableSql()} — {@code LONGTEXT} + utf8mb4
 *   <li>{@link #getUpsertStateSql()} — {@code ON DUPLICATE KEY UPDATE}
 *   <li>{@link #getCheckTableExistsSql()} — {@code INFORMATION_SCHEMA.TABLES}
 *   <li>{@link #getUpsertSnapshotSql()} — {@code ON DUPLICATE KEY UPDATE}
 *   <li>{@link #getBlobType()} — {@code LONGBLOB}
 *   <li>{@link #quoteIdentifier(String)} — backtick quoting
 *   <li>{@link #getCreateDatabaseSql(String)} — {@code CREATE DATABASE IF NOT EXISTS}
 *   <li>{@link #getFullTableReference(String, String)} — {@code `db`.`table`}
 *   <li>{@link #lockStrategy(DataSource)} — {@link MysqlLockStrategy}
 * </ul>
 *
 * <p>All other methods (INSERT, CAS UPDATE, SELECT, DELETE, search, LIKE escape)
 * are inherited from the ANSI defaults.
 *
 * @author shanhongyu
 */
public class MysqlDialect implements JdbcDialect {

    // ------------------------------------------------------------------
    //  JdbcStoreDialect
    // ------------------------------------------------------------------

    @Override
    public String getCreateTableSql() {
        // Keep the composite PK under InnoDB's utf8mb4 3072-byte index limit:
        // (512 + 255) * 4 = 3068 bytes.
        return "CREATE TABLE IF NOT EXISTS %s ("
                + "  namespace_path VARCHAR(512)  NOT NULL,"
                + "  item_key       VARCHAR(255)  NOT NULL,"
                + "  value_json     LONGTEXT      NOT NULL,"
                + "  version        BIGINT        NOT NULL,"
                + "  updated_at     BIGINT        NOT NULL,"
                + "  PRIMARY KEY (namespace_path, item_key)"
                + ") ENGINE=InnoDB DEFAULT CHARSET=utf8mb4";
    }

    @Override
    public String getUpsertSql() {
        return "INSERT INTO %s (namespace_path, item_key, value_json, version, updated_at)"
                + " VALUES (?, ?, ?, 1, ?)"
                + " ON DUPLICATE KEY UPDATE"
                + "   value_json = VALUES(value_json),"
                + "   version    = version + 1,"
                + "   updated_at = VALUES(updated_at)";
    }

    // ------------------------------------------------------------------
    //  AgentStateStoreDialect
    // ------------------------------------------------------------------

    @Override
    public String getCreateSessionsTableSql() {
        return "CREATE TABLE IF NOT EXISTS %s (  session_id  VARCHAR(255) NOT NULL,  state_key  "
                + " VARCHAR(255) NOT NULL,  item_index  INT          NOT NULL DEFAULT 0, "
                + " state_data  LONGTEXT     NOT NULL,  created_at  DATETIME     DEFAULT"
                + " CURRENT_TIMESTAMP,  updated_at  DATETIME     DEFAULT CURRENT_TIMESTAMP ON"
                + " UPDATE CURRENT_TIMESTAMP,  PRIMARY KEY (session_id, state_key, item_index))"
                + " DEFAULT CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci";
    }

    @Override
    public String getUpsertStateSql() {
        return "INSERT INTO %s (session_id, state_key, item_index, state_data)"
                + " VALUES (?, ?, ?, ?)"
                + " ON DUPLICATE KEY UPDATE state_data = VALUES(state_data)";
    }

    @Override
    public String getCheckTableExistsSql() {
        // One bind parameter: the table name. DATABASE() scopes to current DB.
        return "SELECT 1 FROM INFORMATION_SCHEMA.TABLES"
                + " WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = ?";
    }

    @Override
    public String quoteIdentifier(String identifier) {
        return "`" + identifier + "`";
    }

    @Override
    public String getCreateDatabaseSql(String databaseName) {
        return "CREATE DATABASE IF NOT EXISTS `"
                + databaseName
                + "`"
                + " DEFAULT CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci";
    }

    @Override
    public String getFullTableReference(String databaseName, String tableName) {
        return quoteIdentifier(databaseName) + "." + quoteIdentifier(tableName);
    }

    // ------------------------------------------------------------------
    //  SnapshotStoreDialect
    // ------------------------------------------------------------------

    @Override
    public String getBlobType() {
        return "LONGBLOB";
    }

    @Override
    public String getUpsertSnapshotSql() {
        return "INSERT INTO %s (snapshot_id, data) VALUES (?, ?)"
                + " ON DUPLICATE KEY UPDATE"
                + "   data = VALUES(data),"
                + "   created_at = CURRENT_TIMESTAMP";
    }

    // ------------------------------------------------------------------
    //  JdbcDialect — lock strategy
    // ------------------------------------------------------------------

    @Override
    public SandboxLockStrategy lockStrategy(DataSource dataSource) {
        return new MysqlLockStrategy(dataSource);
    }
}

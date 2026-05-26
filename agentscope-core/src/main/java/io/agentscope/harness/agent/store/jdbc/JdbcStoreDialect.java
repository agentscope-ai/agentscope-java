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
package io.agentscope.harness.agent.store.jdbc;

import java.sql.DatabaseMetaData;
import java.sql.SQLException;
import javax.sql.DataSource;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Database-specific SQL for {@link JdbcStore}.
 *
 * <p>Each dialect renders the four SQL statements that vary between vendors:
 *
 * <ul>
 *   <li>{@link #getCreateTableSql} — schema bootstrap, used when
 *       {@code JdbcStore.Builder#initializeSchema(true)} is set.
 *   <li>{@link #getUpsertSql} — unconditional write that increments {@code version} on update.
 *   <li>{@link #getInsertSql} — create-only insert, used by the
 *       {@link io.agentscope.harness.agent.store.BaseStore#putIfVersion} CAS path when
 *       {@code expectedVersion == 0}; primary-key conflicts indicate the row already exists.
 *   <li>{@link #getCasUpdateSql} — conditional update used by the
 *       {@link io.agentscope.harness.agent.store.BaseStore#putIfVersion} CAS path when
 *       {@code expectedVersion > 0}; relies on {@code WHERE version = ?} returning zero rows on
 *       mismatch.
 * </ul>
 *
 * <p>The fixed-shape SQL statements ({@link #getSelectSql}, {@link #getDeleteSql},
 * {@link #getSearchSql}) have safe defaults in this interface.
 *
 * <p>All SQL templates use the {@code %s} placeholder for the table name so that callers can
 * customise it via {@code JdbcStore.Builder#tableName}.
 */
public interface JdbcStoreDialect {

    Logger LOG = LoggerFactory.getLogger(JdbcStoreDialect.class);

    /**
     * Returns the SQL to create the store table if it does not yet exist.
     *
     * <p>The table must have columns: {@code namespace_path VARCHAR(2048)},
     * {@code item_key VARCHAR(255)}, {@code value_json} (TEXT-like), {@code version BIGINT},
     * {@code updated_at BIGINT}, plus a primary key on {@code (namespace_path, item_key)}.
     *
     * @return CREATE TABLE statement; must use {@code %s} for the table name
     */
    String getCreateTableSql();

    /**
     * Returns the SQL to insert-or-update an item, incrementing {@code version} on update.
     *
     * <p>Bind parameters in order: {@code (namespace_path, item_key, value_json, updated_at)} —
     * the dialect is responsible for setting {@code version=1} on insert and
     * {@code version=version+1} on update.
     *
     * @return UPSERT statement; must use {@code %s} for the table name
     */
    String getUpsertSql();

    /**
     * Returns the SQL to insert a new row with {@code version=1}.
     *
     * <p>Bind parameters in order: {@code (namespace_path, item_key, value_json, updated_at)}.
     * Used by {@code putIfVersion(... expectedVersion=0)}: a primary-key violation indicates the
     * row already exists and the CAS attempt has failed.
     *
     * @return INSERT statement; must use {@code %s} for the table name
     */
    String getInsertSql();

    /**
     * Returns the SQL to conditionally update an item only when the stored version matches.
     *
     * <p>Bind parameters in order:
     * {@code (value_json, updated_at, namespace_path, item_key, expectedVersion)}. Implementations
     * must set {@code version = version + 1}. The caller treats an affected-row count of {@code 1}
     * as success.
     *
     * @return UPDATE statement; must use {@code %s} for the table name
     */
    String getCasUpdateSql();

    /**
     * Returns the SQL to fetch a single item.
     *
     * <p>Bind parameters in order: {@code (namespace_path, item_key)}. Projection must be
     * {@code (value_json, version)} in that column order.
     */
    default String getSelectSql() {
        return "SELECT value_json, version FROM %s WHERE namespace_path = ? AND item_key = ?";
    }

    /**
     * Returns the SQL to delete a single item.
     *
     * <p>Bind parameters in order: {@code (namespace_path, item_key)}.
     */
    default String getDeleteSql() {
        return "DELETE FROM %s WHERE namespace_path = ? AND item_key = ?";
    }

    /**
     * Returns the SQL to list items whose namespace_path matches a {@code LIKE} pattern.
     *
     * <p>Bind parameters in order: {@code (namespace_like_pattern, limit, offset)}. Projection
     * must be {@code (item_key, value_json, version)} in that column order.
     *
     * <p>The default uses {@code ESCAPE '!'} (universal across MySQL, PostgreSQL, SQLite, H2 and
     * avoids the backslash-vs-{@code standard_conforming_strings} pitfalls) and
     * {@code ORDER BY item_key} to match {@link io.agentscope.harness.agent.store.InMemoryStore}'s
     * sort behavior. The {@code item_key} sort approximates the in-memory implementation's
     * compound-key sort closely enough for paging.
     */
    default String getSearchSql() {
        return "SELECT item_key, value_json, version FROM %s"
                + " WHERE namespace_path LIKE ? ESCAPE '!'"
                + " ORDER BY item_key LIMIT ? OFFSET ?";
    }

    /**
     * The escape character paired with {@link #getSearchSql}'s {@code ESCAPE} clause. Callers must
     * use this when building the {@code LIKE} pattern to escape literal {@code %}, {@code _}, and
     * the escape character itself.
     */
    default char getLikeEscapeChar() {
        return '!';
    }

    /**
     * Detects the dialect from the {@link DataSource}'s database product name.
     *
     * <p>Defaults to {@link PostgresJdbcStoreDialect} when the product cannot be determined or is
     * not recognised — this keeps unknown engines functional under the SQL-standard subset.
     *
     * @param dataSource the JDBC data source to probe; must not be {@code null}
     * @return a dialect appropriate for the underlying database
     */
    static JdbcStoreDialect from(DataSource dataSource) {
        String productName = null;
        try (var conn = dataSource.getConnection()) {
            DatabaseMetaData md = conn.getMetaData();
            productName = md.getDatabaseProductName();
        } catch (SQLException e) {
            LOG.warn(
                    "Failed to detect JDBC database product name; defaulting to Postgres dialect",
                    e);
        }
        if (productName == null || productName.isBlank()) {
            return new PostgresJdbcStoreDialect();
        }
        return switch (productName) {
            case "PostgreSQL" -> new PostgresJdbcStoreDialect();
            case "MySQL", "MariaDB" -> new MysqlJdbcStoreDialect();
            case "SQLite" -> new SqliteJdbcStoreDialect();
            case "H2" -> new H2JdbcStoreDialect();
            default -> {
                LOG.warn(
                        "Unrecognised JDBC database product '{}'; defaulting to Postgres dialect",
                        productName);
                yield new PostgresJdbcStoreDialect();
            }
        };
    }
}

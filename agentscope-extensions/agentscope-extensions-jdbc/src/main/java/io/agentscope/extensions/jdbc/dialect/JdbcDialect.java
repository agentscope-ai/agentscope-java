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

import java.sql.DatabaseMetaData;
import java.sql.SQLException;
import javax.sql.DataSource;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Aggregate dialect interface that unifies all three component-level dialects:
 * {@link JdbcStoreDialect} (KV store), {@link AgentStateStoreDialect} (session
 * state), and {@link SnapshotStoreDialect} (sandbox snapshots).
 *
 * <p>A concrete dialect class implements this single interface to provide full
 * coverage for all four JDBC components. The compiler enforces that every
 * abstract method across the three sub-interfaces is covered. Default methods
 * supply ANSI/PostgreSQL SQL baselines so that ANSI-compliant databases (e.g.
 * PostgreSQL) can inherit everything with zero overrides.
 *
 * <h2>Lock strategy</h2>
 *
 * <p>The default {@link #lockStrategy(DataSource)} returns a
 * {@link TableBasedLockStrategy} — a portable fallback that works on any JDBC
 * database. Databases with native advisory locks (MySQL {@code GET_LOCK},
 * PostgreSQL {@code pg_advisory_lock}) override this method to return a more
 * efficient strategy.
 *
 * <h2>Auto-detection</h2>
 *
 * <p>{@link #from(DataSource)} probes the JDBC {@link DatabaseMetaData} and
 * returns the matching dialect implementation. Unrecognised databases default to
 * {@link PostgresDialect} (ANSI SQL-standard baseline).
 *
 * @author shanhongyu
 */
public interface JdbcDialect
        extends JdbcStoreDialect, AgentStateStoreDialect, SnapshotStoreDialect {

    Logger LOG = LoggerFactory.getLogger(JdbcDialect.class);

    /**
     * Returns the lock strategy for this database.
     *
     * <p>The default returns a {@link TableBasedLockStrategy} — a portable,
     * table-INSERT-based lock that works on any JDBC database. Databases with
     * native advisory locks should override this method.
     *
     * @param dataSource the JDBC data source for lock connections
     * @return a {@link SandboxLockStrategy} appropriate for this database
     */
    default SandboxLockStrategy lockStrategy(DataSource dataSource) {
        return new TableBasedLockStrategy(dataSource);
    }

    /**
     * Auto-detects the dialect from the data source's database product name.
     *
     * <p>Supported products:
     * <ul>
     *   <li>{@code PostgreSQL} → {@link PostgresDialect}
     *   <li>{@code MySQL}, {@code MariaDB} → {@link MysqlDialect}
     *   <li>{@code H2} → {@link H2Dialect}
     *   <li>{@code SQLite} → {@link SqliteDialect}
     * </ul>
     *
     * <p>Unrecognised or undetectable products default to {@link PostgresDialect}
     * (ANSI SQL-standard baseline), with a warning logged.
     *
     * @param dataSource the JDBC data source to probe; must not be {@code null}
     * @return a dialect appropriate for the underlying database
     * @throws NullPointerException if {@code dataSource} is {@code null}
     */
    static JdbcDialect from(DataSource dataSource) {
        if (dataSource == null) {
            throw new NullPointerException("dataSource must not be null");
        }

        String productName = null;
        try (var conn = dataSource.getConnection()) {
            DatabaseMetaData md = conn.getMetaData();
            productName = md.getDatabaseProductName();
        } catch (SQLException e) {
            LOG.warn(
                    "Failed to detect JDBC database product name; defaulting to"
                            + " Postgres dialect",
                    e);
        }

        if (productName == null || productName.isBlank()) {
            LOG.warn("JDBC product name is blank; defaulting to Postgres dialect");
            return new PostgresDialect();
        }

        return switch (productName) {
            case "PostgreSQL" -> new PostgresDialect();
            case "MySQL", "MariaDB" -> new MysqlDialect();
            case "H2" -> new H2Dialect();
            case "SQLite" -> new SqliteDialect();
            default -> {
                LOG.warn(
                        "Unrecognised JDBC database product '{}'; defaulting to"
                                + " Postgres dialect",
                        productName);
                yield new PostgresDialect();
            }
        };
    }
}
